package com.catgal.server.service.impl;

import com.catgal.common.constants.RedisConstant;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.UserCutePoints;
import com.catgal.server.mapper.UserCutePointsMapper;
import com.catgal.server.service.IUserCutePointsService;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.CUTE_POINTS_KEY;

/**
 * <p>
 * 用户萌萌点汇总表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserCutePointsServiceImpl extends ServiceImpl<UserCutePointsMapper, UserCutePoints> implements IUserCutePointsService {

    private final StringRedisTemplate redisTemplate;
    private final RedisDirtySetProcessor dirtySetProcessor;


    @Override
    public Integer getUserCutePointsCache(Long userId) {
        if (userId == null) {
            return null;
        }
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        Integer cachedPoints = getHashIntNullable(key, RedisConstant.CUTE_POINTS_HASH_KEY);
        Integer cachedEarned = getHashIntNullable(key, RedisConstant.TOTAL_EARNED_HASH_KEY);
        Integer cachedSpent = getHashIntNullable(key, RedisConstant.TOTAL_SPENT_HASH_KEY);
        if (cachedPoints != null && cachedEarned != null && cachedSpent != null) {
            return cachedPoints;
        }

        UserCutePoints stored = getById(userId);
        int cutePoints = valueOrZero(stored == null ? null : stored.getCutePoints());
        int totalEarned = valueOrZero(stored == null ? null : stored.getTotalEarned());
        int totalSpent = valueOrZero(stored == null ? null : stored.getTotalSpent());

        // 只填充缺失字段，避免并发积分消息已写入后又被数据库旧值覆盖。
        redisTemplate.opsForHash().putIfAbsent(key, RedisConstant.CUTE_POINTS_HASH_KEY,
                String.valueOf(cutePoints));
        redisTemplate.opsForHash().putIfAbsent(key, RedisConstant.TOTAL_EARNED_HASH_KEY,
                String.valueOf(totalEarned));
        redisTemplate.opsForHash().putIfAbsent(key, RedisConstant.TOTAL_SPENT_HASH_KEY,
                String.valueOf(totalSpent));

        Integer initialized = getHashIntNullable(key, RedisConstant.CUTE_POINTS_HASH_KEY);
        return initialized == null ? cutePoints : initialized;
    }

    @Override
    public void userCutePointsToDB(String key, Integer batchSize) {
        int processed = dirtySetProcessor.processBatch(key, batchSize, true, claimed -> {
            Set<UserCutePoints> userPointsList = claimed.stream().map(id -> {
                Long userId = Long.parseLong(id);
                // 先补齐可能部分丢失的 Hash 字段，避免用默认 0 覆盖 MySQL 的累计值。
                getUserCutePointsCache(userId);
                UserCutePoints userCutePoints = new UserCutePoints();
                userCutePoints.setUserId(userId);
                String pointKey = StringUtils.format(CUTE_POINTS_KEY, id);
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(pointKey))) {
                    throw new IllegalStateException("萌萌点缓存缺失，拒绝用 0 覆盖数据库: userId=" + id);
                }
                userCutePoints.setCutePoints(valueOrZero(getHashIntNullable(pointKey, RedisConstant.CUTE_POINTS_HASH_KEY)));
                userCutePoints.setTotalEarned(valueOrZero(getHashIntNullable(pointKey, RedisConstant.TOTAL_EARNED_HASH_KEY)));
                userCutePoints.setTotalSpent(valueOrZero(getHashIntNullable(pointKey, RedisConstant.TOTAL_SPENT_HASH_KEY)));
                return userCutePoints;
            }).collect(Collectors.toSet());
            if (CollUtils.isEmpty(userPointsList)) {
                return;
            }
            saveOrUpdateBatch(userPointsList);
        });
        log.info("用户萌萌点同步完成, 处理用户数={}", processed);
    }

    private Integer getHashIntNullable(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        if (value == null) return null;
        return Integer.parseInt(value.toString());
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
