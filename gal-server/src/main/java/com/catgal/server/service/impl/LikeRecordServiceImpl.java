package com.catgal.server.service.impl;

import Message.LikeCountMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.LikeRecord;
import com.catgal.server.mapper.LikeRecordMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.LikeTargetValidator;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.BIZ_TYPE_LIKED_TIMES_KEY;
import static com.catgal.common.constants.MqConstants.Queue.LIKE_COUNT_QUEUE_TEMPLATE;
import static com.catgal.common.constants.RedisConstant.*;
import static com.catgal.server.redis.RedisScriptLoader.load;

@Service
@RequiredArgsConstructor
@Slf4j
public class LikeRecordServiceImpl extends ServiceImpl<LikeRecordMapper, LikeRecord> implements ILikeRecordService {

    private static final DefaultRedisScript<Long> LIKE_SCRIPT =
            load("lua/like/add.lua", Long.class);
    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT =
            load("lua/like/remove.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;
    private final RedisDirtySetProcessor dirtySetProcessor;
    private final LikeTargetValidator likeTargetValidator;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public Boolean like(LikeDTO dto) {
        Long userId = currentUserProvider.requireUserId();
        log.info("点赞请求: type={}, targetId={}, userId={}",
                dto.getType(), dto.getTargetId(), userId);

        String type = dto.getType();
        Long bizId = dto.getTargetId();
        likeTargetValidator.requireLikeable(type, bizId);

        // 1. 用户点赞集合（用于去重和判断是否点赞）
        String userKey = StringUtils.format(LIKE_USER_KEY, type, bizId);
        String bizIdsKey = StringUtils.format(LIKE_BIZ_IDS_KEY, type);
        Long added = redisTemplate.execute(LIKE_SCRIPT, List.of(
                        userKey,
                        bizIdsKey,
                        StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, type),
                        StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, type)
                ),
                String.valueOf(userId), String.valueOf(bizId));

        if (added != null && added > 0) {
            return true;
        }

        log.warn("重复点赞, userId={}, targetId={}", userId, bizId);
        return false;
    }

    @Override
    public Boolean unlike(LikeDTO dto) {
        Long userId = currentUserProvider.requireUserId();
        log.info("取消点赞请求: type={}, targetId={}, userId={}",
                dto.getType(), dto.getTargetId(), userId);

        String type = dto.getType();
        Long bizId = dto.getTargetId();
        likeTargetValidator.requireSupportedType(type);

        // 1. 从用户点赞集合中移除
        String userKey = StringUtils.format(LIKE_USER_KEY, type, bizId);
        String bizIdsKey = StringUtils.format(LIKE_BIZ_IDS_KEY, type);
        Long removed = redisTemplate.execute(UNLIKE_SCRIPT, List.of(
                        userKey,
                        bizIdsKey,
                        StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, type),
                        StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, type)
                ),
                String.valueOf(userId), String.valueOf(bizId));

        if (removed != null && removed > 0) {
            return true;
        }

        log.warn("取消点赞失败, 未找到点赞记录, userId={}, targetId={}", userId, bizId);
        return false;
    }

    @Override
    public Map<Long, Boolean> batchIsLiked(Long userId, String type, List<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return new HashMap<>();
        }

        // 使用Pipeline批量查询
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long targetId : targetIds) {
                String key = StringUtils.format(LIKE_USER_KEY, type, targetId);
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                byte[] memberBytes = userId.toString().getBytes(StandardCharsets.UTF_8);
                connection.setCommands().sIsMember(keyBytes, memberBytes);
            }
            return null;
        });

        Map<Long, Boolean> resultMap = new HashMap<>();
        for (int i = 0; i < targetIds.size(); i++) {
            resultMap.put(targetIds.get(i), (Boolean) results.get(i));
        }
        return resultMap;
    }

    /**
     * 批量获取点赞数（直接用 SCARD）
     */
    @Override
    public Map<Long, Long> getLikeCounts(List<Long> bizIds, String type) {
        if (bizIds == null || bizIds.isEmpty()) {
            return Collections.emptyMap();
        }

        log.info("批量获取点赞数，type: {}, bizIds数量: {}", type, bizIds.size());

        // 使用 Pipeline 批量获取 Set 大小
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long bizId : bizIds) {
                String userKey = StringUtils.format(LIKE_USER_KEY, type, bizId);
                byte[] keyBytes = userKey.getBytes(StandardCharsets.UTF_8);
                connection.setCommands().sCard(keyBytes);
            }
            return null;
        });

        Map<Long, Long> likeCountMap = new HashMap<>();
        for (int i = 0; i < bizIds.size(); i++) {
            Long bizId = bizIds.get(i);
            Long count = 0L;
            if (results.get(i) != null) {
                count = (Long) results.get(i);
            }
            likeCountMap.put(bizId, count);
        }

        return likeCountMap;
    }


    @Override
    public void fullSyncLikeRecordsToDB(String bizType, int batchSize, int batchBizSize) {
        String changeKey = StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, bizType);
        int processed = dirtySetProcessor.processBatch(changeKey, batchBizSize, true, claimed -> {
            for (String bizIdValue : claimed) {
                Long bizId = Long.valueOf(bizIdValue);
                String userKey = StringUtils.format(LIKE_USER_KEY, bizType, bizId);
                Set<String> userIds = redisTemplate.opsForSet().members(userKey);
                baseMapper.delete(
                        new LambdaQueryWrapper<LikeRecord>()
                                .eq(LikeRecord::getType, bizType)
                                .eq(LikeRecord::getTargetId, bizId)
                );

                if (CollUtils.isEmpty(userIds)) {
                    continue;
                }

                List<LikeRecord> records = new ArrayList<>();
                for (String userIdStr : userIds) {
                    LikeRecord record = new LikeRecord();
                    record.setType(bizType);
                    record.setTargetId(bizId);
                    record.setUserId(Long.parseLong(userIdStr));
                    records.add(record);
                }

                for (int j = 0; j < records.size(); j += batchSize) {
                    int batchEnd = Math.min(j + batchSize, records.size());
                    saveBatch(records.subList(j, batchEnd));
                }
            }
        });
        log.info("点赞明细同步完成, bizType={}, 业务数={}", bizType, processed);
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        String changeKey = StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, bizType);
        dirtySetProcessor.processBatch(changeKey, maxBizSize, false, claimed -> {
            List<LikeCountMessage> messages = new ArrayList<>(claimed.size());
            for (String bizIdValue : claimed) {
                Long bizId = Long.valueOf(bizIdValue);
                Long likeCount = redisTemplate.opsForSet().size(StringUtils.format(LIKE_USER_KEY, bizType, bizId));
                messages.add(LikeCountMessage.builder()
                        .bizType(bizType)
                        .bizId(bizId)
                        .likeCount(likeCount == null ? 0L : likeCount)
                        .build());
            }
            mqHelper.send(LIKE_COUNT_EXCHANGE,
                    StringUtils.format(BIZ_TYPE_LIKED_TIMES_KEY, bizType), messages);
            log.info("发送点赞数消息, bizType={}, 数量={}", bizType, messages.size());
        });
    }

    /**
     * 清理点赞缓存
     * @param bizType 业务类型: comment/review/resource
     * @param bizId 业务ID
     */
    @Override
    public void clearLikeCache(String bizType, Long bizId) {
        if (bizId == null || StringUtils.isBlank(bizType)) {
            return;
        }

        // 1. 删除点赞用户集合
        String likeKey = StringUtils.format(LIKE_USER_KEY, bizType, bizId);
        redisTemplate.delete(likeKey);

        // 2. 从全局业务ID集合中移除
        String bizIdsKey = StringUtils.format(LIKE_BIZ_IDS_KEY, bizType);
        redisTemplate.opsForSet().remove(bizIdsKey, String.valueOf(bizId));
        redisTemplate.opsForSet().add(StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, bizType), String.valueOf(bizId));
        redisTemplate.opsForSet().add(StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, bizType), String.valueOf(bizId));

        log.debug("清理点赞缓存, bizType={}, bizId={}", bizType, bizId);
    }


}
