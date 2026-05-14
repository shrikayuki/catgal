package com.catgal.server.service.impl;

import Message.LikeCountMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.LikeRecord;
import com.catgal.server.mapper.LikeRecordMapper;
import com.catgal.server.service.ILikeRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Queue.LIKE_COUNT_QUEUE_TEMPLATE;
import static com.catgal.common.constants.RedisConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LikeRecordServiceImpl extends ServiceImpl<LikeRecordMapper, LikeRecord> implements ILikeRecordService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;

    @Override
    public Boolean like(LikeDTO dto) {
        log.info("点赞请求: type={}, targetId={}, userId={}",
                dto.getType(), dto.getTargetId(), UserContext.getUserId());

        String type = dto.getType();
        Long bizId = dto.getTargetId();
        Long userId = UserContext.getUserId();

        // 1. 用户点赞集合（用于去重和判断是否点赞）
        String userKey = StringUtils.format(LIKE_USER_KEY, type, bizId);
        Long added = redisTemplate.opsForSet().add(userKey, String.valueOf(userId));

        if (added != null && added > 0) {
            //3.该业务的点赞id
            String bizIdsKey = StringUtils.format(LIKE_BIZ_IDS_KEY, type);
            redisTemplate.opsForSet().add(bizIdsKey, String.valueOf(bizId));
            return true;
        }

        log.warn("重复点赞, userId={}, targetId={}", userId, bizId);
        return false;
    }

    @Override
    public Boolean unlike(LikeDTO dto) {
        log.info("取消点赞请求: type={}, targetId={}, userId={}",
                dto.getType(), dto.getTargetId(), UserContext.getUserId());

        String type = dto.getType();
        Long bizId = dto.getTargetId();
        Long userId = UserContext.getUserId();

        // 1. 从用户点赞集合中移除
        String userKey = StringUtils.format(LIKE_USER_KEY, type, bizId);
        Long removed = redisTemplate.opsForSet().remove(userKey, String.valueOf(userId));

        if (removed != null && removed > 0) {
            //3.该业务的点赞id
            String bizIdsKey = StringUtils.format(LIKE_BIZ_IDS_KEY, type);
            Long remaining = redisTemplate.opsForSet().size(userKey);
            if (remaining == 0) {
                redisTemplate.opsForSet().remove(bizIdsKey, String.valueOf(bizId));
                log.info("该业务无点赞记录");
            }
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
                byte[] keyBytes = key.getBytes();
                byte[] memberBytes = userId.toString().getBytes();
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
                byte[] keyBytes = userKey.getBytes();
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


    /**
     * 获取并移除需要同步的业务ID（增量用）
     */
    private Set<Long> popBizIds(String type, int maxSize) {
        String key = StringUtils.format(LIKE_BIZ_IDS_KEY, type);
        List<String> bizIdStrs = redisTemplate.opsForSet().pop(key, maxSize);

        if (CollUtils.isEmpty(bizIdStrs)) {
            return new HashSet<>();
        }

        return bizIdStrs.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /**
     * 获取所有业务ID（全量用，不移除）
     */
    private Set<Long> getBizIds(String type) {
        String key = StringUtils.format(LIKE_BIZ_IDS_KEY, type);
        Set<String> bizIdStrs = redisTemplate.opsForSet().members(key);

        if (CollUtils.isEmpty(bizIdStrs)) {
            return new HashSet<>();
        }

        return bizIdStrs.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    @Override
    @Transactional
    public void fullSyncLikeRecordsToDB(String bizType, int batchSize, int batchBizSize) {
        Set<Long> bizIds = getBizIds(bizType);
        if (CollUtils.isEmpty(bizIds)) {
            log.info("没有需要同步的业务数据");
            return;
        }

        int totalRecords = 0;
        List<Long> bizIdList = new ArrayList<>(bizIds);

        for (int i = 0; i < bizIdList.size(); i += batchBizSize) {
            int end = Math.min(i + batchBizSize, bizIdList.size());
            List<Long> batchBizIds = bizIdList.subList(i, end);

            for (Long bizId : batchBizIds) {
                String userKey = StringUtils.format(LIKE_USER_KEY, bizType, bizId);
                Set<String> userIds = redisTemplate.opsForSet().members(userKey);

                if (CollUtils.isEmpty(userIds)) {
                    continue;
                }

                baseMapper.delete(
                        new LambdaQueryWrapper<LikeRecord>()
                                .eq(LikeRecord::getType, bizType)
                                .eq(LikeRecord::getTargetId, bizId)
                );

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
                    totalRecords += (batchEnd - j);
                }
            }

            log.info("已处理 {}/{} 个业务", end, bizIdList.size());
        }

        log.info("全量同步完成, 业务数={}, 点赞记录数={}", bizIdList.size(), totalRecords);
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        // 使用 pop 增量读取并移除
        Set<Long> bizIds = popBizIds(bizType, maxBizSize);
        if (CollUtils.isEmpty(bizIds)) {
            log.debug("没有需要同步的业务数据, bizType={}", bizType);
            return;
        }

        List<LikeCountMessage> allMessages = new ArrayList<>();

        for (Long bizId : bizIds) {
            String userKey = StringUtils.format(LIKE_USER_KEY, bizType, bizId);
            Long likeCount = redisTemplate.opsForSet().size(userKey);

            if (likeCount == null || likeCount == 0) {
                // 点赞数为0，不需要处理（已经在 pop 时移除了）
                continue;
            }

            LikeCountMessage message = LikeCountMessage.builder()
                    .bizType(bizType)
                    .bizId(bizId)
                    .likeCount(likeCount)
                    .build();
            allMessages.add(message);
        }

        if (!allMessages.isEmpty()) {
            mqHelper.send(
                    LIKE_COUNT_EXCHANGE,
                    StringUtils.format(LIKE_COUNT_QUEUE_TEMPLATE, bizType),
                    allMessages
            );
            log.info("发送点赞数消息, bizType={}, 数量={}", bizType, allMessages.size());
        }
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

        log.debug("清理点赞缓存, bizType={}, bizId={}", bizType, bizId);
    }


}