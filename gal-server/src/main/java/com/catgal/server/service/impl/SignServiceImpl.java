/*
 * 阅读说明
 * 签到按运行模式分流，ACTIVE 委托 MySQL 事务服务，同日请求和积分一起持久化。
 * LEGACY 使用每用户每月 Bitmap，日期减一对应位偏移，积分经固定业务号消息异步处理。
 */
package com.catgal.server.service.impl;

import Message.UserCutePointsMessage;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.vo.SignVO;
import com.catgal.common.enums.PointsType;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.service.ISignService;
import com.catgal.server.service.IRewardControlService;
import com.catgal.server.service.IRewardTransactionService;
import com.catgal.server.service.IConfirmedRewardPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.catgal.common.constants.CutePointsPrizeConstant.SIGN_IN_REWARD;
import static com.catgal.common.constants.CutePointsTypeConstant.SIGN_IN;
import static com.catgal.common.constants.DateFormatConstant.YM_FORMATTER;
import static com.catgal.common.constants.MqConstants.Exchange.CUTE_POINT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.BIZ_TYPE_CUTE_POINTS_KEY;
import static com.catgal.common.constants.RedisConstant.SIGN_DATE_USER_KEY;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignServiceImpl implements ISignService {

    private final StringRedisTemplate redisTemplate;
    private final IConfirmedRewardPublisher mqHelper;
    private final IRewardControlService control;
    private final IRewardTransactionService rewards;

    @Override
    // 新流程在数据库事务内完成，旧来源持有共享切换屏障，防止跨过激活边界继续发送事件。
    public void signIn() {
        if (control.isActive()) {
            rewards.signIn(UserContext.getUserId());
            return;
        }
        control.legacySource(() -> { signInLegacy(); return null; });
    }

    private void signInLegacy() {
        // 旧 Bitmap 记录以日期定位，消息业务号保护重复发放，排空阶段仍允许消费者处理存量消息。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }

        LocalDate date = LocalDate.now(com.catgal.server.service.IUserAccountStatusService.TIME_ZONE);
        String signKey = getSignKey(userId, date);
        long offset = buildOffset(date);

        // SETBIT 返回该位的旧值
        // 旧值为 true  → 已经签到过（幂等）
        // 旧值为 false → 首次签到成功
        Boolean alreadySigned = redisTemplate.opsForValue().setBit(signKey, offset, true);

        if (Boolean.TRUE.equals(alreadySigned)) {
            // 旧值是 true，说明早已签过，本次是重复调用
            log.info("用户 {} 在 {} 已签到，本次为幂等调用", userId, date);
            return;
        }

        // 旧值是 false，签到成功，执行后续业务逻辑
        log.info("用户 {} 在 {} 签到成功", userId, date);

        // 发放萌萌点、更新连续签到等业务逻辑
        UserCutePointsMessage message = new UserCutePointsMessage();
        message.setBizId("sign:" + userId + ":" + date);
        message.setUserId(userId);
        message.setPointsType(PointsType.SIGN_IN);
        message.setCutePointsChange(SIGN_IN_REWARD);

        try {
            mqHelper.send(CUTE_POINT_EXCHANGE,
                    StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, SIGN_IN), message);
        } catch (RuntimeException exception) {
            // 发送失败时恢复签到位，使客户端重试能够重新投递积分消息。
            redisTemplate.opsForValue().setBit(signKey, offset, false);
            throw exception;
        }

    }

    @Override
    // 新流程读取持久请求，旧流程读取当月位图，统一装配本月日期与连续天数。
    public SignVO getSignRecords() {
        if (control.isActive()) return rewards.signRecords(UserContext.getUserId());
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }

        LocalDate today = LocalDate.now(com.catgal.server.service.IUserAccountStatusService.TIME_ZONE);
        String signKey = getSignKey(userId, today);
        int dayInMonth = today.getDayOfMonth();
        List<Long> result = redisTemplate.execute(
                (RedisCallback<List<Long>>) connection ->
                        connection.bitField(
                                signKey.getBytes(),
                                BitFieldSubCommands.create()
                                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayInMonth))
                                        .valueAt(0)
                        )
        );

        if (result == null || result.isEmpty()) {
            log.debug("签到记录为空");
            return null;
        }
        int bit = result.get(0).intValue();
        //获得连续签到天数
        int continuousDays = Integer.numberOfTrailingZeros(~bit);
        List<Long> signRecords = new ArrayList<>();
        for (int i = 0; i < dayInMonth; i++) {
            if ((bit&1) == 1) {
                signRecords.add((long)(dayInMonth - i));
            }
            bit = bit>>1;
        }
        SignVO vo = new SignVO();
        vo.setContinuousDays(continuousDays);
        vo.setSignedDays(signRecords);
        return vo;
    }

    /**
     * 生成签到 Key
     * 格式：sign:{yyyyMM}:user:{userId}
     */
    private String getSignKey(Long userId, LocalDate date) {
        String yearMonth = date.format(YM_FORMATTER);
        return StringUtils.format(SIGN_DATE_USER_KEY, yearMonth, userId);
    }

    /**
     * 计算本月内的位偏移量（0-based）
     * 5月1日 → offset=0, 5月23日 → offset=22
     */
    private long buildOffset(LocalDate date) {
        return date.getDayOfMonth() - 1;
    }
}
