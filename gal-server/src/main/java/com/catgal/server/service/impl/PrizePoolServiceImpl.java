package com.catgal.server.service.impl;


import Message.UserCutePointsMessage;
import cn.hutool.json.JSONUtil;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.constants.MqConstants;
import com.catgal.common.constants.RedisConstant;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PrizeStockUpdateDTO;
import com.catgal.common.domain.vo.PrizeVO;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.common.enums.PointsType;
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

    private final StringRedisTemplate redisTemplate;
    private final IUserCutePointsService userCutePointsService;
    private final RabbitMqHelper mqHelper;
    private final PrizePoolMapper prizePoolMapper;
    private final DrawRecordMapper drawRecordMapper;


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
        List<PrizeVO> prizes = getPrizeCache();

        // 将权重设为 null，不返回给前端
        prizes.forEach(prize -> prize.setWeight(null));

        return prizes;
    }

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

    private TreeMap<Integer, Long> getWeightNum(List<PrizeVO> prizes) {
        // 过滤：权重>0 且 (无限库存 或 剩余库存>0)
        List<PrizeVO> sorted = prizes.stream()
                .filter(p -> p.getWeight() != null && p.getWeight() > 0  && (p.getTotalStock() == 0 || p.getRemainStock() > 0))
                .sorted(Comparator.comparingInt(PrizeVO::getWeight))
                .toList();

        // 累加：key = 累加上限, value = 奖品ID
        TreeMap<Integer, Long> weightMap = new TreeMap<>();
        int cursor = 0;
        for (PrizeVO p : sorted) {
            cursor += p.getWeight();
            weightMap.put(cursor, p.getId());
        }

        return weightMap;
    }

    @Override
    public UserPrizeVO drawPrizePool() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户不存在");
        }

        // 1. 获取当前奖池（包含实时库存）
        List<PrizeVO> prizeInfos = getPrizeCache();

        // 2. 构建权重映射（自动过滤库存为0的奖品）
        TreeMap<Integer, Long> weightMap = getWeightNum(prizeInfos);

        // 3. 校验是否有可用奖品
        if (weightMap.isEmpty()) {
            throw new RuntimeException("暂无可用奖品");
        }

        // 4. 抽奖
        UserPrizeVO userPrizeVO = doDraw(userId, weightMap);

        // 5. 扣减库存（如果是有限库存）
        boolean success = decrementPrizeStock(userPrizeVO.getId());
        if (!success) {
            throw new RuntimeException("库存扣减失败");
        }
        // 6.发MQ消息增加中奖记录
        DrawRecord drawRecord = new DrawRecord();
        drawRecord.setUserId(userId);
        drawRecord.setPrizeId(userPrizeVO.getId());
        mqHelper.send(MqConstants.Exchange.DRAW_RECORD_EXCHANGE, MqConstants.Key.DRAW_RECORD_KEY, drawRecord);



        return userPrizeVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkRemainStockAndCreateDrawRecord(DrawRecord message) {
        //prizePoolMapper.decrementPrizeStock(message.getPrizeId());
        redisTemplate.opsForSet().add(RedisConstant.PRIZE_POOL_CHANGE_SET_KEY, String.valueOf(message.getPrizeId()));
        //更新记录 谢谢参与不存
        PrizePool prize = getById(message.getPrizeId());
        if (Objects.equals(prize.getPrizeName(), "谢谢参与")) {
            log.error("不参与中奖记录");
            return;
        }
        drawRecordMapper.insert(message);

    }

    @Override
    public void prizeStockToDB(String redisKey, Integer batchSize) {
        String currentMonth = LocalDate.now().format(YM_FORMATTER);
        String hashKey = StringUtils.format(PRIZE_DATE_CACHE_KEY, currentMonth);

        int totalSynced = 0;

        while (true) {
            // 1. 从 Set 中批量取出奖品ID
            List<String> prizeIds = redisTemplate.opsForSet().pop(redisKey, batchSize);
            if (CollUtils.isEmpty(prizeIds)) {
                break;
            }

            // 2. 批量从 Hash 获取数据
            List<Object> dataList = redisTemplate.opsForHash().multiGet(hashKey,
                    prizeIds.stream().map(id -> (Object) id).collect(Collectors.toList()));

            // 3. 构建批量更新参数
            // Service 中使用
            List<PrizeStockUpdateDTO> updateList = new ArrayList<>();

            for (int i = 0; i < prizeIds.size(); i++) {
                if (dataList.get(i) == null) continue;

                PrizeVO prizeVO = JSONUtil.toBean(dataList.get(i).toString(), PrizeVO.class);

                PrizeStockUpdateDTO update = new PrizeStockUpdateDTO();
                update.setId(Long.valueOf(prizeIds.get(i)));
                update.setRemainStock(prizeVO.getRemainStock());
                updateList.add(update);
            }

            // 4. 一条SQL批量更新
            if (!updateList.isEmpty()) {
                int updated = prizePoolMapper.batchUpdateRemainStock(updateList);
                totalSynced += updated;
                log.debug("批量同步库存: 本次{}条, 累计{}条", updated, totalSynced);
            }
        }

        log.info("库存同步完成，共{}条", totalSynced);
    }

    private boolean decrementPrizeStock(Long prizeId) {
        // 先查询奖品的 totalStock
        String month = YM_FORMATTER.format(LocalDate.now());
        String hashKey = StringUtils.format(PRIZE_DATE_CACHE_KEY, month);

        String prizeJson = (String) redisTemplate.opsForHash().get(hashKey, String.valueOf(prizeId));
        PrizeVO prize = JsonUtils.toBean(prizeJson, PrizeVO.class);

        // 如果 totalStock == 0，说明是无限库存，不需要扣
        if (prize.getTotalStock() == 0) {
            return true;
        }

        // 有限库存才执行扣减
        String script =
                "local prize = redis.call('hget', KEYS[1], KEYS[2]) " +
                        "if not prize then return 0 end " +
                        "local obj = cjson.decode(prize) " +
                        "if obj.remainStock >= tonumber(ARGV[1]) then " +
                        "    obj.remainStock = obj.remainStock - tonumber(ARGV[1]) " +
                        "    redis.call('hset', KEYS[1], KEYS[2], cjson.encode(obj)) " +
                        "    return 1 " +
                        "end " +
                        "return 0";

        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                List.of(hashKey, String.valueOf(prizeId)),
                String.valueOf(1)
        );

        return result != null && result == 1;
    }

    private UserPrizeVO doDraw(Long userId, TreeMap<Integer, Long> weightMap) {
        Integer userCutePointsCache = userCutePointsService.getUserCutePointsCache(userId);

        if (userCutePointsCache == null || userCutePointsCache <= -PRIZE_POOL_CONSUME) {
            throw new RuntimeException("用户积分不足");
        }
        // 获取总权重（TreeMap的最后一个key）
        int totalWeight = weightMap.lastKey();

        // 生成随机数 [1, totalWeight]
        int randomNum = ThreadLocalRandom.current().nextInt(totalWeight) + 1;

        // TreeMap的ceilingEntry二分查找
        Map.Entry<Integer, Long> entry = weightMap.ceilingEntry(randomNum);

        if (entry == null) {
            throw new RuntimeException("抽奖异常");
        }
        // 扣减积分
        UserCutePointsMessage message = new UserCutePointsMessage();
        message.setUserId(userId);
        message.setPointsType(PointsType.LOTTERY_COST);
        message.setCutePointsChange(PRIZE_POOL_CONSUME);
        mqHelper.send(CUTE_POINT_EXCHANGE, StringUtils.format(BIZ_TYPE_CUTE_POINTS_KEY, DRAW_CONSUMED), message);


        // 返回结果
        UserPrizeVO result = new UserPrizeVO();
        result.setUserId(userId);
        result.setId(entry.getValue());
        result.setSpentCutePoints(Math.abs(PRIZE_POOL_CONSUME));


        return result;
    }


}
