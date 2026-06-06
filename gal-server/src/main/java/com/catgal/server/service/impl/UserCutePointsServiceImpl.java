package com.catgal.server.service.impl;

import com.catgal.common.constants.RedisConstant;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.UserCutePoints;
import com.catgal.server.mapper.UserCutePointsMapper;
import com.catgal.server.service.IUserCutePointsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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


    @Override
    public Integer getUserCutePointsCache(Long userId) {
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        String pointKey = StringUtils.format(CUTE_POINTS_KEY, userId);
        Integer cutePoints = getHashInt(pointKey, RedisConstant.CUTE_POINTS_HASH_KEY);
        if (cutePoints == null || cutePoints <= 0) {
            UserCutePoints userCute = getById(userId);
            if (userCute == null) {
                log.debug("数据还未同步");
                return null;
            }
            Integer newCutePoints = userCute.getCutePoints();
            redisTemplate.opsForHash().increment(key, RedisConstant.CUTE_POINTS_HASH_KEY, newCutePoints);
            return newCutePoints;

        }
        return cutePoints;
    }

    @Override
    @Transactional
    public void userCutePointsToDB(String key, Integer batchSize) {
        Long size = redisTemplate.opsForSet().size(key);
        if (size == null || size <= 0) {
            log.debug("没有需要同步的用户萌萌点数据");
            return;
        }
        while (true) {
            List<String> IdStrList = redisTemplate.opsForSet().pop(RedisConstant.CUTE_POINTS_CHANGE_SET_KEY, batchSize);
            if (CollUtils.isEmpty(IdStrList)) {
                log.debug("用户萌萌点记录同步结束,退出");
                break;
            }
            Set<UserCutePoints> userPointsList = IdStrList.stream().map(id -> {
                UserCutePoints userCutePoints = new UserCutePoints();
                userCutePoints.setUserId(Long.parseLong(id));
                String pointKey = StringUtils.format(CUTE_POINTS_KEY, id);
                userCutePoints.setCutePoints(getHashInt(pointKey, RedisConstant.CUTE_POINTS_HASH_KEY));
                userCutePoints.setTotalEarned(getHashInt(pointKey, RedisConstant.TOTAL_EARNED_HASH_KEY));
                userCutePoints.setTotalSpent(getHashInt(pointKey, RedisConstant.TOTAL_SPENT_HASH_KEY));
                return userCutePoints;
            }).collect(Collectors.toSet());
            if (CollUtils.isEmpty(userPointsList)) {
                continue;
            }
            saveOrUpdateBatch(userPointsList);

        }
    }

    private int getHashInt(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        if (value == null) return 0;
        return Integer.parseInt(value.toString());
    }
}
