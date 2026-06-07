package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.UserFansOrFollowsVO;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FollowRecord;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.FollowRecordMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IFollowRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.*;

/**
 * <p>
 * 关注记录表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-06-05
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowRecordServiceImpl extends ServiceImpl<FollowRecordMapper, FollowRecord> implements IFollowRecordService {

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public Boolean follow(Long followedId) {
        // 1. 校验
        Long fanId = UserContext.getUserId();
        if (fanId == null) {
            log.error("用户未登录");
            return false;
        }
        if (fanId.equals(followedId)) {
            log.error("不可关注自己");
            return false;
        }

        String fanKey = StringUtils.format(USER_FANS_KEY, followedId);
        String followKey = StringUtils.format(USER_FOLLOWS_KEY, fanId);

        // 2. 检查是否已关注（防重复）
        Double score = redisTemplate.opsForZSet().score(followKey, followedId.toString());
        if (score != null) {
            log.info("已经关注过了");
            return true;
        }

        long now = System.currentTimeMillis();

        // 3. 使用 Lua 脚本保证原子性（或手动回滚）
        try {
            // 添加粉丝关系
            redisTemplate.opsForZSet().add(fanKey, fanId.toString(), now);
            // 添加关注关系
            redisTemplate.opsForZSet().add(followKey, followedId.toString(), now);

            // 5. 标记需要同步到 MySQL
            redisTemplate.opsForSet().add(FOLLOW_CHANGE_SET_KEY, followedId.toString());

            log.info("关注成功: {} -> {}", fanId, followedId);

        } catch (Exception e) {
            log.error("关注失败", e);
            // 回滚
            redisTemplate.opsForZSet().remove(fanKey, fanId.toString());
            redisTemplate.opsForZSet().remove(followKey, followedId.toString());
            return false;
        }

        return true;
    }

    @Override
    @Transactional
    public Boolean unfollow(Long followedId) {
        Long fanId = UserContext.getUserId();
        if (fanId == null) {
            log.error("用户未登录");
            return false;
        }
        if (fanId.equals(followedId)) {
            log.error("不可取消关注自己");
            return false;
        }

        String fanKey = StringUtils.format(USER_FANS_KEY, followedId);
        String followKey = StringUtils.format(USER_FOLLOWS_KEY, fanId);

        try {
            // 1. 删除关系
            Long removed1 = redisTemplate.opsForZSet().remove(fanKey, fanId.toString());
            Long removed2 = redisTemplate.opsForZSet().remove(followKey, followedId.toString());

            if (removed1 == null || removed1 == 0) {
                log.info("未关注过");
                return true;
            }

            // 2. 标记需要同步到 MySQL
            redisTemplate.opsForSet().add(FOLLOW_CHANGE_SET_KEY, followedId.toString());

            log.info("取消关注成功: {} -> {}", fanId, followedId);

        } catch (Exception e) {
            log.error("取消关注失败", e);
            return false;
        }

        return true;
    }

    @Override
    public PageDTO<UserFansOrFollowsVO> queryUserFansPage(Long userId, PageQuery query) {
        Long curUserId = UserContext.getUserId();
        if (userId == null) {
            if (curUserId == null) {
                throw new RuntimeException("当前用户未登录");
            }
            userId = curUserId;
        }
        String fansKey = StringUtils.format(USER_FANS_KEY, userId);
        Long fansCache = redisTemplate.opsForZSet().size(fansKey);
        boolean isFansCacheNull = fansCache == null || fansCache <= 0;

        return getUserFansOrFollowPage(userId, query, isFansCacheNull, true);
    }

    @Override
    public PageDTO<UserFansOrFollowsVO> queryUserFollowsPage(Long userId, PageQuery query) {
        Long curUserId = UserContext.getUserId();
        if (userId == null) {
            if (curUserId == null) {
                throw new RuntimeException("当前用户未登录");
            }
            userId = curUserId;
        }
        String followsKey = StringUtils.format(USER_FOLLOWS_KEY, userId);
        Long followsCache = redisTemplate.opsForZSet().size(followsKey);
        boolean isFollowsCacheNull = followsCache == null || followsCache <= 0;

        return getUserFansOrFollowPage(userId, query, isFollowsCacheNull, false);
    }

    private PageDTO<UserFansOrFollowsVO> getUserFansOrFollowPage(Long userId, PageQuery query,
                                                                 boolean isCacheNull, boolean isQueryFans) {

        // 缓存为空，从数据库查询
        if (isCacheNull) {
            return queryFromDB(userId, query, isQueryFans);
        }

        // 缓存有数据，从 Redis 分页查询
        String key = isQueryFans
                ? StringUtils.format(USER_FANS_KEY, userId)
                : StringUtils.format(USER_FOLLOWS_KEY, userId);

        int pageNo = query.getPageNo();
        int pageSize = query.getPageSize();
        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize - 1;

        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);

        if (CollUtils.isEmpty(tuples)) {
            return PageDTO.empty(new Page<>());
        }

        // 提取用户ID列表
        List<Long> userIds = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            userIds.add(Long.valueOf(tuple.getValue()));
        }

        // 批量查询用户信息
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // 当前用户ID
        Long currentUserId = UserContext.getUserId();

        // 查粉丝列表时，需要知道当前用户关注了哪些人（只要登录了就查）
        Set<String> currentFollowing = null;
        if (isQueryFans && currentUserId != null) {
            String currentFollowingKey = StringUtils.format(USER_FOLLOWS_KEY, currentUserId);
            Set<String> followingSet = redisTemplate.opsForZSet()
                    .range(currentFollowingKey, 0, -1);
            currentFollowing = followingSet != null ? followingSet : Collections.emptySet();
        }

        // 组装返回
        List<UserFansOrFollowsVO> voList = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long targetId = Long.valueOf(tuple.getValue());
            User user = userMap.get(targetId);
            if (user == null) continue;

            UserFansOrFollowsVO vo = new UserFansOrFollowsVO();
            vo.setUserId(targetId);
            vo.setUsername(user.getUsername());
            vo.setAvatarUrl(user.getAvatarUrl());
            vo.setSignature(user.getSignature());

            if (isQueryFans) {
                // 粉丝列表：当前用户是否关注了这个粉丝
                if (currentUserId != null && currentFollowing != null) {
                    vo.setIsFollow(currentFollowing.contains(String.valueOf(targetId)));
                } else {
                    vo.setIsFollow(false);
                }
            } else {
                // 关注列表：永远为 true
                vo.setIsFollow(true);
            }

            voList.add(vo);
        }

        Long total = redisTemplate.opsForZSet().size(key);
        long pages = (total + pageSize - 1) / pageSize;

        Page<UserFansOrFollowsVO> mpPage = new Page<>(pageNo, pageSize);
        mpPage.setTotal(total);
        mpPage.setPages(pages);
        mpPage.setRecords(voList);

        return PageDTO.of(mpPage);
    }

    private PageDTO<UserFansOrFollowsVO> queryFromDB(Long userId, PageQuery query, boolean isQueryFans) {
        Page<FollowRecord> page = new Page<>(query.getPageNo(), query.getPageSize());
        page.addOrder(new OrderItem());

        LambdaQueryWrapper<FollowRecord> wrapper = new LambdaQueryWrapper<>();
        if (isQueryFans) {
            wrapper.eq(FollowRecord::getFollowedId, userId);
        } else {
            wrapper.eq(FollowRecord::getFanId, userId);
        }
        wrapper.orderByDesc(FollowRecord::getFollowTime);

        page = baseMapper.selectPage(page, wrapper);

        if (CollUtils.isEmpty(page.getRecords())) {
            return PageDTO.empty(page);
        }

        // 提取用户ID
        List<Long> userIds = page.getRecords().stream()
                .map(isQueryFans ? FollowRecord::getFanId : FollowRecord::getFollowedId)
                .collect(Collectors.toList());

        // 批量查询用户信息
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Long currentUserId = UserContext.getUserId();

        // 查粉丝列表时，需要知道当前用户关注了哪些人
        Set<String> currentFollowing = null;
        if (isQueryFans && currentUserId != null) {
            String currentFollowingKey = StringUtils.format(USER_FOLLOWS_KEY, currentUserId);
            Set<String> followingSet = redisTemplate.opsForZSet()
                    .range(currentFollowingKey, 0, -1);
            currentFollowing = followingSet != null ? followingSet : Collections.emptySet();
        }

        // 组装返回
        List<UserFansOrFollowsVO> voList = new ArrayList<>();
        for (FollowRecord record : page.getRecords()) {
            Long targetId = isQueryFans ? record.getFanId() : record.getFollowedId();
            User user = userMap.get(targetId);
            if (user == null) continue;

            UserFansOrFollowsVO vo = new UserFansOrFollowsVO();
            vo.setUserId(targetId);
            vo.setUsername(user.getUsername());
            vo.setAvatarUrl(user.getAvatarUrl());
            vo.setSignature(user.getSignature());

            if (isQueryFans) {
                if (currentUserId != null && currentFollowing != null) {
                    vo.setIsFollow(currentFollowing.contains(String.valueOf(targetId)));
                } else {
                    vo.setIsFollow(false);
                }
            } else {
                vo.setIsFollow(true);
            }

            voList.add(vo);
        }

        Page<UserFansOrFollowsVO> mpPage = new Page<>(query.getPageNo(), query.getPageSize());
        mpPage.setTotal(page.getTotal());
        mpPage.setPages(page.getPages());
        mpPage.setRecords(voList);

        return PageDTO.of(mpPage);
    }

    @Override
    public Integer userFansCount(Long userId) {
        String fansKey = StringUtils.format(USER_FANS_KEY, userId);
        Long fansCount = redisTemplate.opsForZSet().size(fansKey);
        if (fansCount == null) {
            fansCount = baseMapper.selectCount(new LambdaQueryWrapper<FollowRecord>().eq(FollowRecord::getFollowedId, userId));
        }
        return fansCount.intValue();
    }

    @Override
    public Integer userFollowCount(Long userId) {
        String followKey = StringUtils.format(USER_FOLLOWS_KEY, userId);
        Long followCount = redisTemplate.opsForZSet().size(followKey);
        if (followCount == null) {
            followCount = baseMapper.selectCount(new LambdaQueryWrapper<FollowRecord>().eq(FollowRecord::getFanId, userId));
        }
        return followCount.intValue();
    }

    @Override
    public boolean isMyFollow(Long userId, Long targetId){
        String followKey = StringUtils.format(USER_FOLLOWS_KEY, userId);
        Double score = redisTemplate.opsForZSet().score(followKey, targetId.toString());
        return score != null;

    }
}
