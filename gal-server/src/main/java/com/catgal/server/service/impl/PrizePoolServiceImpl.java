/*
 * 阅读说明
 * 月度奖池读取与抽奖模式路由。ACTIVE 的库存、扣款、结果交给 MySQL 事务服务。
 * Redis Lua 扣库存与旧 MQ 逻辑仅供 LEGACY 和切换排空，阅读时注意两条路径的权威数据边界。
 */
package com.catgal.server.service.impl;


import Message.UserCutePointsMessage;
import cn.hutool.json.JSONUtil;
import com.catgal.common.constants.MqConstants;
import com.catgal.common.constants.RedisConstant;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PrizeStockUpdateDTO;
import com.catgal.common.domain.vo.PrizeVO;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.common.enums.PointsType;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.JsonUtils;

import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.po.UserCutePoints;
import com.catgal.server.mapper.DrawRecordMapper;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.mapper.UserCutePointsMapper;
import com.catgal.server.service.IDrawRecordService;
import com.catgal.server.service.IPrizePoolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IUserCutePointsService;
import com.catgal.server.service.IUserService;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.catgal.server.service.IRewardControlService;
import com.catgal.server.service.IRewardTransactionService;
import com.catgal.server.service.IConfirmedRewardPublisher;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.catgal.common.constants.CutePointsPrizeConstant.PRIZE_POOL_CONSUME;
import static com.catgal.common.constants.CutePointsTypeConstant.DRAW_CONSUMED;
import static com.catgal.common.constants.DateFormatConstant.YM_FORMATTER;
import static com.catgal.common.constants.MqConstants.Exchange.CUTE_POINT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.BIZ_TYPE_CUTE_POINTS_KEY;
import static com.catgal.common.constants.RedisConstant.PRIZE_DATE_CACHE_KEY;
import static com.catgal.common.constants.RedisConstant.PRIZE_POOL_ADMIN_LOCK_KEY;
import static com.catgal.server.redis.RedisScriptLoader.load;


/**
 * <p>
 * 奖池表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-25
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrizePoolServiceImpl extends ServiceImpl<PrizePoolMapper, PrizePool> implements IPrizePoolService {

    private static final DefaultRedisScript<Long> DECREMENT_STOCK_SCRIPT =
            load("lua/prize/decrement-stock.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final IUserCutePointsService userCutePointsService;
    private final IConfirmedRewardPublisher mqHelper;
    private final PrizePoolMapper prizePoolMapper;
    private final DrawRecordMapper drawRecordMapper;
    private final RedisDirtySetProcessor dirtySetProcessor;
    private final IRewardControlService control;
    private final IRewardTransactionService rewards;


    @Override
    public List<PrizeVO> getPoolInfoByMonth(String month) {

        List<PrizePool> list = lambdaQuery().eq(PrizePool::getMonth, month).eq(PrizePool::getStatus, 1).list();
        if (CollUtils.isEmpty(list)) {
            return CollUtils.newArrayList();
        }
        return BeanUtils.copyList(list, PrizeVO.class);

    }

    @Override
    public List<PrizeVO> getCurrentPrizePool() {
        List<PrizeVO> prizes = control.isActive()
                ? getPoolInfoByMonth(LocalDate.now(com.catgal.server.service.IUserAccountStatusService.TIME_ZONE).format(YM_FORMATTER))
                : getPrizeCache();

        // 将权重设为 null，不返回给前端
        prizes.forEach(prize -> {
            prize.setDrawable(prize.getWeight() != null && prize.getWeight() > 0
                    && (prize.getTotalStock() == 0 || prize.getRemainStock() > 0));
            prize.setWeight(null);
        });

        return prizes;
    }

    // 优先读取当月 Hash，缓存缺失时回源 MySQL，展示和抽奖各自处理返回字段。
    private List<PrizeVO> getPrizeCache() {
        String month = LocalDate.now().format(YM_FORMATTER);
        String key = StringUtils.format(PRIZE_DATE_CACHE_KEY, month);

        // 从 Redis Hash 拿所有奖品
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        List<PrizeVO> prizes;
        if (CollUtils.isEmpty(entries)) {
            // Redis 没有，查 MySQL
            prizes = getPoolInfoByMonth(month);
            log.warn("Redis 奖池缓存为空，回源 MySQL，month={}", month);
        } else {
            // Redis 数据转 VO
            prizes = entries.values().stream()
                    .map(v -> JsonUtils.toBean(v.toString(), PrizeVO.class))
                    .toList();
        }

        return prizes;
    }

    // 有效奖品构成累计权重区间，随机数落在哪个区间就选择对应奖品。
    private TreeMap<Long, Long> getWeightNum(List<PrizeVO> prizes) {
        // 过滤：权重>0 且 (无限库存 或 剩余库存>0)
        List<PrizeVO> sorted = prizes.stream()
                .filter(p -> p.getWeight() != null && p.getWeight() > 0  && (p.getTotalStock() == 0 || p.getRemainStock() > 0))
                .sorted(Comparator.comparingInt(PrizeVO::getWeight))
                .toList();

        // 累加：key = 累加上限, value = 奖品ID
        TreeMap<Long, Long> weightMap = new TreeMap<>();
        long cursor = 0;
        for (PrizeVO p : sorted) {
            cursor += p.getWeight();
            weightMap.put(cursor, p.getId());
        }

        return weightMap;
    }

    @Override
    // 兼容原有 Java 调用入口，新客户端通过带请求号的重载获得网络重试幂等。
    public UserPrizeVO drawPrizePool() {
        return drawPrizePool(null);
    }

    @Override
    public UserPrizeVO drawPrizePool(String requestKey) {
        if (control.isActive()) return rewards.draw(UserContext.getUserId(),
                requestKey == null ? UUID.randomUUID().toString() : requestKey);
        return control.legacySource(this::drawLegacy);
    }

    private UserPrizeVO drawLegacy() {
        // 旧流程分组件提交，只在 LEGACY 屏障内执行，ACTIVE 不再走这条积分和库存写入路径。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }

        String currentMonth = LocalDate.now().format(YM_FORMATTER);
        String maintenanceLock = StringUtils.format(PRIZE_POOL_ADMIN_LOCK_KEY, currentMonth);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(maintenanceLock))) {
            throw BusinessException.conflict("奖池正在维护，请稍后重试");
        }

        // 1. 获取当前奖池（包含实时库存）
        List<PrizeVO> prizeInfos = getPrizeCache();

        // 2. 构建权重映射（自动过滤库存为0的奖品）
        TreeMap<Long, Long> weightMap = getWeightNum(prizeInfos);

        // 3. 校验是否有可用奖品
        if (weightMap.isEmpty()) {
            throw BusinessException.conflict("暂无可用奖品");
        }

        // 4. 抽奖
        UserPrizeVO userPrizeVO = doDraw(userId, weightMap);
        String drawBizId = UUID.randomUUID().toString();

        // 5. 扣减库存（如果是有限库存）
        boolean success = decrementPrizeStock(userPrizeVO.getId());
        if (!success) {
            throw BusinessException.conflict("库存扣减失败");
        }
        // 库存扣减成功后再发送积分扣减，避免维护锁或库存冲突时仍扣用户积分。
        UserCutePointsMessage pointsMessage = new UserCutePointsMessage();
        pointsMessage.setBizId(drawBizId);
        pointsMessage.setUserId(userId);
        pointsMessage.setPointsType(PointsType.LOTTERY_COST);
        pointsMessage.setCutePointsChange(PRIZE_POOL_CONSUME);
        mqHelper.send(CUTE_POINT_EXCHANGE,
                StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, DRAW_CONSUMED), pointsMessage);

        // 6.发MQ消息增加中奖记录
        DrawRecord drawRecord = new DrawRecord();
        drawRecord.setBizId(drawBizId);
        drawRecord.setUserId(userId);
        drawRecord.setPrizeId(userPrizeVO.getId());
        mqHelper.send(MqConstants.Exchange.DRAW_RECORD_EXCHANGE, MqConstants.Key.DRAW_RECORD_KEY, drawRecord);



        return userPrizeVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 消费者通过业务号幂等插入记录，重复通知复用数据库唯一约束。
    public void checkRemainStockAndCreateDrawRecord(DrawRecord message) {
        control.legacyDrain(() -> { persistLegacyDrawRecord(message); return null; });
    }

    private void persistLegacyDrawRecord(DrawRecord message) {
        if (message == null || message.getUserId() == null || message.getPrizeId() == null
                || StringUtils.isBlank(message.getBizId())) {
            throw BusinessException.badRequest("抽奖记录消息缺少必要字段");
        }
        int inserted = drawRecordMapper.insertIdempotent(message);
        if (inserted == 0) {
            log.info("忽略重复抽奖记录消息, bizId={}", message.getBizId());
        }

    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    // 领取库存脏任务后批量读取 Hash 并更新数据库，缺少库存快照时抛错保留任务。
    public void prizeStockToDB(String redisKey, Integer batchSize) {
        if (control.isActive()) return;
        int processed = dirtySetProcessor.processBatch(redisKey, batchSize, true, prizeIds -> {
            control.legacyDrain(() -> {
                // 脏集合只有奖品 ID，跨月任务必须按数据库所属月份寻找缓存。
                // 直接使用系统当前月份会让上月未完成的任务永久读不到快照。
                List<PrizePool> stored = prizePoolMapper.selectBatchIds(prizeIds);
                if (stored.size() != prizeIds.size()) throw new IllegalStateException("库存同步奖品缺失，保留任务待核对");
                List<PrizeStockUpdateDTO> updateList = new ArrayList<>();
                for (PrizePool prize : stored) {
                    String hashKey = StringUtils.format(PRIZE_DATE_CACHE_KEY, prize.getMonth());
                    Object cached = redisTemplate.opsForHash().get(hashKey, prize.getId().toString());
                    if (cached == null) throw new IllegalStateException("奖池缓存缺失，拒绝确认库存同步: prizeId=" + prize.getId());
                    PrizeVO snapshot = JSONUtil.toBean(cached.toString(), PrizeVO.class);
                    Integer remaining = snapshot.getRemainStock();
                    if (remaining == null || remaining < 0 || !Objects.equals(snapshot.getTotalStock(), prize.getTotalStock())
                            || (prize.getTotalStock() > 0 && remaining > prize.getTotalStock())
                            || (prize.getTotalStock() == 0 && remaining != 0))
                        throw new IllegalStateException("奖池库存快照异常，保留任务待核对: prizeId=" + prize.getId());
                    PrizeStockUpdateDTO update = new PrizeStockUpdateDTO();
                    update.setId(prize.getId()); update.setRemainStock(remaining);
                    updateList.add(update);
                }
                if (!updateList.isEmpty()) prizePoolMapper.batchUpdateRemainStock(updateList);
                return null;
            });
        });
        log.info("库存同步完成，共处理{}条", processed);
    }

    @Override
    // 用数据库当前启用奖品重建指定月份 Hash，管理员维护流程负责调用时机。
    public void refreshPrizeCache(String month) {
        String hashKey = StringUtils.format(PRIZE_DATE_CACHE_KEY, month);
        List<PrizeVO> prizes = getPoolInfoByMonth(month);
        redisTemplate.delete(hashKey);
        if (CollUtils.isEmpty(prizes)) {
            return;
        }
        Map<String, String> values = prizes.stream().collect(Collectors.toMap(
                prize -> prize.getId().toString(),
                JsonUtils::toJsonStr,
                (left, right) -> left
        ));
        redisTemplate.opsForHash().putAll(hashKey, values);
    }

    // 维护锁检查、无限库存判断和有限库存扣减在一个 Lua 内完成。
    private boolean decrementPrizeStock(Long prizeId) {
        // 先查询奖品的 totalStock
        String month = YM_FORMATTER.format(LocalDate.now());
        String hashKey = StringUtils.format(PRIZE_DATE_CACHE_KEY, month);

        String maintenanceLock = StringUtils.format(PRIZE_POOL_ADMIN_LOCK_KEY, month);
        // 无限库存和有限库存都进入同一 Lua；这样维护锁检查与库存判断保持原子。
        // Lua 在修改库存前校验 JSON、整数范围和脏集合类型，避免库存已扣却在 SADD 时失败。
        // 单次脚本的完整性保护与抽奖跨积分、消息和数据库的持久化恢复分别由各阶段承担。
        Long result = redisTemplate.execute(
                DECREMENT_STOCK_SCRIPT,
                List.of(hashKey, maintenanceLock, RedisConstant.PRIZE_POOL_CHANGE_SET_KEY),
                String.valueOf(prizeId),
                String.valueOf(1)
        );
        if (result != null && result == -1) {
            throw BusinessException.conflict("奖池正在维护，请稍后重试");
        }
        return result != null && result == 1;
    }

    // 读取当前积分进行准入判断，再对累计权重范围取随机数，返回抽中奖品定位。
    private UserPrizeVO doDraw(Long userId, TreeMap<Long, Long> weightMap) {
        Integer userCutePointsCache = userCutePointsService.getUserCutePointsCache(userId);

        if (userCutePointsCache == null || userCutePointsCache < -PRIZE_POOL_CONSUME) {
            throw BusinessException.conflict("用户积分不足");
        }
        // 获取总权重（TreeMap的最后一个key）
        long totalWeight = weightMap.lastKey();

        // 生成随机数 [1, totalWeight]
        long randomNum = ThreadLocalRandom.current().nextLong(totalWeight) + 1;

        // TreeMap的ceilingEntry二分查找
        Map.Entry<Long, Long> entry = weightMap.ceilingEntry(randomNum);

        if (entry == null) {
            throw BusinessException.internalError("抽奖异常");
        }
        // 返回结果
        UserPrizeVO result = new UserPrizeVO();
        result.setUserId(userId);
        result.setId(entry.getValue());
        result.setSpentCutePoints(Math.abs(PRIZE_POOL_CONSUME));


        return result;
    }


}
