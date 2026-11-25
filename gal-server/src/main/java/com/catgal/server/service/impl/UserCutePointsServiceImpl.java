/*
 * 阅读说明
 * ACTIVE 读取 MySQL 已提交余额或版本投影，fresh 用于写操作后的立即可见性。
 * LEGACY 保留旧缓存与批量落库，余额 0 是有效值，有待落库任务时禁止用旧数据库值补齐快照。
 */
package com.catgal.server.service.impl;

import com.catgal.common.constants.RedisConstant;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.UserCutePoints;
import com.catgal.server.mapper.UserCutePointsMapper;
import com.catgal.server.service.IUserCutePointsService;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.catgal.server.service.IRewardControlService;
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
    private final IRewardControlService control;


    @Override
    // 读取积分缓存，缺失时从数据库余额初始化，区分合法 0 和缺失字段。
    public Integer getUserCutePointsCache(Long userId) {
        return getUserCutePointsCache(userId, false);
    }

    @Override
    public Integer getUserCutePointsCache(Long userId, boolean fresh) {
        if (userId == null) {
            return null;
        }
        if (control.isActive()) {
            // 新缓存只接收已提交账本的版本投影。fresh 保证操作后的立即可见性。
            // Redis 断连或投影损坏时回库，扣款始终由事务服务读取 MySQL。
            if (!fresh) {
                try {
                    Object cached = redisTemplate.opsForHash().get(RewardOutboxServiceImpl.CACHE_PREFIX + userId, "balance");
                    if (cached != null) {
                        int balance = Integer.parseInt(cached.toString());
                        if (balance >= 0) return balance;
                    }
                } catch (RuntimeException failure) {
                    log.warn("新积分投影读取失败，回源 MySQL, userId={}, type={}", userId, failure.getClass().getSimpleName());
                }
            }
            UserCutePoints stored = getById(userId);
            return stored == null ? 0 : stored.getCutePoints();
        }
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        Integer cachedPoints = getHashIntNullable(key, RedisConstant.CUTE_POINTS_HASH_KEY);
        Integer cachedEarned = getHashIntNullable(key, RedisConstant.TOTAL_EARNED_HASH_KEY);
        Integer cachedSpent = getHashIntNullable(key, RedisConstant.TOTAL_SPENT_HASH_KEY);
        if (cachedPoints != null && cachedEarned != null && cachedSpent != null) {
            return cachedPoints;
        }

        // 有待落库任务时，缺失字段可能承载尚未提交的变动，禁止拿数据库旧值补齐后确认任务。
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisConstant.CUTE_POINTS_CHANGE_SET_KEY, userId.toString()))
                || Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisConstant.CUTE_POINTS_CHANGE_SET_KEY + ":processing", userId.toString())))
            throw new IllegalStateException("积分存在未落库任务且缓存不完整，保留现场待恢复: userId=" + userId);

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
    // 对已领取用户批量同步积分，任务成功后由处理器确认，异常保留恢复入口。
    public void userCutePointsToDB(String key, Integer batchSize) {
        if (control.isActive()) return;
        int processed = dirtySetProcessor.processBatch(key, batchSize, true, claimed -> {
            control.legacyDrain(() -> {
                Set<UserCutePoints> userPointsList = claimed.stream().map(id -> {
                    Long userId = Long.parseLong(id);
                    // 已领取的任务必须具有完整原始快照。丢失缓存时保留 processing，禁止回灌旧余额后确认。
                    UserCutePoints userCutePoints = new UserCutePoints();
                    userCutePoints.setUserId(userId);
                    String pointKey = StringUtils.format(CUTE_POINTS_KEY, id);
                    if (!Boolean.TRUE.equals(redisTemplate.hasKey(pointKey))) {
                        throw new IllegalStateException("萌萌点缓存缺失，拒绝用 0 覆盖数据库: userId=" + id);
                    }
                    userCutePoints.setCutePoints(requireHashInt(pointKey, RedisConstant.CUTE_POINTS_HASH_KEY));
                    userCutePoints.setTotalEarned(requireHashInt(pointKey, RedisConstant.TOTAL_EARNED_HASH_KEY));
                    userCutePoints.setTotalSpent(requireHashInt(pointKey, RedisConstant.TOTAL_SPENT_HASH_KEY));
                    return userCutePoints;
                }).collect(Collectors.toSet());
                if (CollUtils.isEmpty(userPointsList)) {
                    return null;
                }
                saveOrUpdateBatch(userPointsList);
                return null;
            });
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

    private int requireHashInt(String key, String field) {
        Integer value = getHashIntNullable(key, field);
        if (value == null || value < 0) throw new IllegalStateException("积分同步快照缺失或为负数，拒绝覆盖数据库");
        return value;
    }
}
