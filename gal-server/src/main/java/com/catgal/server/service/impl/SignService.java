package com.catgal.server.service.impl;

import Message.UserCutePointsMessage;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.vo.SignVO;
import com.catgal.common.enums.PointsType;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.service.ISignService;
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
public class SignService implements ISignService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;

    @Override
    public void signIn() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户不存在");
        }

        LocalDate date = LocalDate.now();
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
        message.setUserId(userId);
        message.setPointsType(PointsType.SIGN_IN);
        message.setCutePointsChange(SIGN_IN_REWARD);

        mqHelper.send(CUTE_POINT_EXCHANGE,
                StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, SIGN_IN)
                ,message);

    }

    @Override
    public SignVO getSignRecords() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户不存在");
        }

        LocalDate today = LocalDate.now();
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