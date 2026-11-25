/*
 * 阅读说明
 * 评论、评价与资源点赞的关系缓存、计数校准和数据库同步。
 * 显式条数标记证明空集合，受损且有积压的状态保留现场，完整状态才允许写库。
 */
package com.catgal.server.service.impl;

import Message.LikeCountMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.common.exception.BusinessException;
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
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.BIZ_TYPE_LIKED_TIMES_KEY;
import static com.catgal.common.constants.RedisConstant.*;
import static com.catgal.server.redis.RedisScriptLoader.load;

/**
 * 点赞关系先写 Redis，再由脏任务同步数据库。
 * 业务写入、关系同步和内容删除共用目标行锁，顺序为目标行、Redis 状态、关系明细。
 * expected 标记等于 Set 条数才表示完整状态，0 表示已确认清空。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikeRecordServiceImpl extends ServiceImpl<LikeRecordMapper, LikeRecord> implements ILikeRecordService {

    private static final DefaultRedisScript<Long> LIKE_SCRIPT =
            load("lua/like/add.lua", Long.class);
    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT =
            load("lua/like/remove.lua", Long.class);

    private static final DefaultRedisScript<List> SNAPSHOT_SCRIPT = load("lua/like/snapshot.lua", List.class);
    private static final DefaultRedisScript<List> INSPECT_SCRIPT = load("lua/like/inspect.lua", List.class);
    private static final DefaultRedisScript<Long> HYDRATE_SCRIPT = load("lua/like/hydrate.lua", Long.class);
    private static final DefaultRedisScript<Long> CLEAR_SCRIPT = load("lua/like/clear.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;
    private final RedisDirtySetProcessor dirtySetProcessor;
    private final LikeTargetValidator likeTargetValidator;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public Boolean like(LikeDTO dto) {
        long owner = currentUserProvider.requireUserId();
        validateRequest(dto);
        LikeRecordMapper.Target target = baseMapper.lockTarget(dto.getType(), dto.getTargetId());
        if (target == null || !Integer.valueOf(1).equals(target.status())) {
            throw BusinessException.notFound("点赞目标不存在或已隐藏");
        }
        ensureState(dto.getType(), dto.getTargetId());
        return change(LIKE_SCRIPT, dto.getType(), dto.getTargetId(), owner);
    }

    @Override
    @Transactional
    public Boolean unlike(LikeDTO dto) {
        long owner = currentUserProvider.requireUserId();
        validateRequest(dto);
        if (baseMapper.lockTarget(dto.getType(), dto.getTargetId()) == null) {
            removeDeletedTarget(dto.getType(), dto.getTargetId());
            return false;
        }
        ensureState(dto.getType(), dto.getTargetId());
        return change(UNLIKE_SCRIPT, dto.getType(), dto.getTargetId(), owner);
    }

    // 目标行锁协调点赞与内容删除，缓存状态确认后用 Lua 同时更新成员和脏任务。
    private boolean change(DefaultRedisScript<Long> script, String type, Long id, long owner) {
        Long result = redisTemplate.execute(script, mutationKeys(type, id), Long.toString(owner), id.toString());
        if (result == null) throw new IllegalStateException("点赞操作未返回结果");
        if (result == -2) throw unavailable();
        if (result == -3) throw BusinessException.conflict("点赞数量已达上限");
        return result == 1;
    }

    // 冷状态回灌须校验明细与计数队列，待落库状态受损时保留现场等待恢复。
    private void ensureState(String type, Long id) {
        // 调用方已经持有目标行锁。完整缓存直接使用，干净的旧缓存或冷缓存才允许从数据库加载。
        List<?> state = redisTemplate.execute(INSPECT_SCRIPT, List.of(
                StringUtils.format(LIKE_USER_KEY, type, id), StringUtils.format(LIKE_EXPECTED_KEY, type, id)), "");
        if (inspectedState(Collections.singletonList(state), 0) != null) return;
        List<LikeRecord> records = baseMapper.selectList(new LambdaQueryWrapper<LikeRecord>()
                .select(LikeRecord::getUserId).eq(LikeRecord::getType, type).eq(LikeRecord::getTargetId, id));
        List<String> arguments = new ArrayList<>();
        arguments.add(id.toString());
        for (LikeRecord record : records) {
            if (record.getUserId() == null || record.getUserId() <= 0) throw unavailable();
            arguments.add(record.getUserId().toString());
        }
        String recordQueue = StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, type);
        String countQueue = StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, type);
        Long restored = redisTemplate.execute(HYDRATE_SCRIPT, List.of(
                StringUtils.format(LIKE_USER_KEY, type, id), StringUtils.format(LIKE_EXPECTED_KEY, type, id),
                StringUtils.format(LIKE_BIZ_IDS_KEY, type), recordQueue, recordQueue + ":processing",
                countQueue, countQueue + ":processing"), arguments.toArray());
        if (!Long.valueOf(1).equals(restored)) throw unavailable();
    }

    private void validateRequest(LikeDTO dto) {
        if (dto == null || dto.getTargetId() == null || dto.getTargetId() <= 0) {
            throw BusinessException.badRequest("点赞目标ID必须为正整数");
        }
        likeTargetValidator.requireSupportedType(dto.getType());
    }

    @Override
    // 批量读取成员与完整性标记，受损或未加载的展示查询使用数据库确认状态。
    public Map<Long, Boolean> batchIsLiked(Long userId, String type, List<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) return new HashMap<>();
        likeTargetValidator.requireSupportedType(type);
        List<Long> ids = uniqueIds(targetIds);
        List<?> states = inspect(type, ids, userId.toString());
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            List<?> state = inspectedState(states, index);
            if (state == null) missing.add(ids.get(index));
            else result.put(ids.get(index), ((Number) state.get(1)).longValue() == 1);
        }
        if (!missing.isEmpty()) {
            missing.forEach(id -> result.put(id, false));
            baseMapper.selectList(new LambdaQueryWrapper<LikeRecord>().select(LikeRecord::getTargetId)
                    .eq(LikeRecord::getType, type).eq(LikeRecord::getUserId, userId)
                    .in(LikeRecord::getTargetId, missing)).forEach(row -> result.put(row.getTargetId(), true));
        }
        return result;
    }

    /** 批量读取经过完整性校验的点赞数，缺失状态批量回源数据库。 */
    @Override
    // 明确区分有效零计数和缓存缺失，完整状态才可直接用于展示。
    public Map<Long, Long> getLikeCounts(List<Long> bizIds, String type) {
        if (bizIds == null || bizIds.isEmpty()) return Collections.emptyMap();
        likeTargetValidator.requireSupportedType(type);
        List<Long> ids = uniqueIds(bizIds);
        List<?> states = inspect(type, ids, "");
        Map<Long, Long> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            List<?> state = inspectedState(states, index);
            if (state == null) missing.add(ids.get(index));
            else result.put(ids.get(index), ((Number) state.get(0)).longValue());
        }
        if (!missing.isEmpty()) {
            missing.forEach(id -> result.put(id, 0L));
            baseMapper.countByTargets(type, missing).forEach(row -> result.put(row.targetId(), row.total()));
        }
        return result;
    }

    private List<Long> uniqueIds(List<Long> ids) {
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) throw BusinessException.badRequest("点赞目标ID必须为正整数");
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private List<?> inspect(String type, List<Long> ids, String owner) {
        try {
            // Pipeline keeps one network round trip while Lua pairs each count with its marker.
            // 每个结果为 [完整计数, 本人是否点赞]，负计数表示需回源或等待恢复。
            return redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : ids) {
                    connection.scriptingCommands().eval(INSPECT_SCRIPT.getScriptAsString().getBytes(StandardCharsets.UTF_8),
                            ReturnType.MULTI, 2,
                            StringUtils.format(LIKE_USER_KEY, type, id).getBytes(StandardCharsets.UTF_8),
                            StringUtils.format(LIKE_EXPECTED_KEY, type, id).getBytes(StandardCharsets.UTF_8),
                            owner.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        } catch (RuntimeException failure) {
            log.warn("点赞查询回源数据库, type={}, errorType={}", type, failure.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<?> inspectedState(List<?> states, int index) {
        if (states == null || index >= states.size() || !(states.get(index) instanceof List<?> state)
                || state.size() != 2 || !(state.get(0) instanceof Number count) || count.longValue() < 0
                || !(state.get(1) instanceof Number)) return null;
        return state;
    }

    @Override
    // 先验证完整快照再替换关系，数据库失败让任务保留或重新排队。
    public void fullSyncLikeRecordsToDB(String bizType, int batchSize, int batchBizSize) {
        likeTargetValidator.requireSupportedType(bizType);
        if (batchSize <= 0 || batchBizSize <= 0) throw BusinessException.badRequest("同步批次必须为正整数");
        String changeKey = StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, bizType);
        int processed = dirtySetProcessor.processBatch(changeKey, batchBizSize, true, claimed -> {
            Map<Long, List<Long>> snapshots = new LinkedHashMap<>();
            // 多目标统一按 ID 加锁，降低两个批次以相反顺序获取锁的风险。
            for (Long id : claimed.stream().map(Long::valueOf).sorted().toList()) {
                if (id <= 0) throw new IllegalStateException("无效的点赞同步ID");
                if (baseMapper.lockTarget(bizType, id) == null) {
                    snapshots.put(id, List.of());
                    afterCommitClear(bizType, id);
                } else {
                    List<Long> snapshot = verifiedSnapshot(bizType, id);
                    if (snapshot == null) throw unavailable();
                    snapshots.put(id, snapshot);
                }
            }
            // Validate the entire batch before replacing any database relationships.
            // 缺失快照会抛错并回滚整个批次，脏任务处理器随后把 processing 放回 pending。
            snapshots.forEach((id, owners) -> {
                baseMapper.delete(scope(bizType, id));
                List<LikeRecord> records = owners.stream().map(owner -> new LikeRecord()
                        .setType(bizType).setTargetId(id).setUserId(owner)).toList();
                for (int offset = 0; offset < records.size(); offset += batchSize) {
                    if (!saveBatch(records.subList(offset, Math.min(offset + batchSize, records.size())))) {
                        throw new IllegalStateException("点赞明细写入失败");
                    }
                }
            });
        });
        log.info("点赞明细同步完成, bizType={}, 业务数={}", bizType, processed);
    }

    // 用原子脚本读取成员及其条数标记，保证同步使用的是同一时刻的完整集合。
    private List<Long> verifiedSnapshot(String type, Long id) {
        // Lua 同时读取条数标记和成员，避免分开读取时把并发写入误判为缺失。
        List<?> values = redisTemplate.execute(SNAPSHOT_SCRIPT, List.of(
                StringUtils.format(LIKE_USER_KEY, type, id), StringUtils.format(LIKE_EXPECTED_KEY, type, id)));
        if (values == null || values.isEmpty() || !Long.valueOf(1).equals(values.get(0))) return null;
        Set<Long> owners = new LinkedHashSet<>();
        for (Object value : values.subList(1, values.size())) {
            long owner = Long.parseLong(value.toString());
            if (owner <= 0 || !owners.add(owner)) throw new IllegalStateException("点赞快照归属无效");
        }
        return new ArrayList<>(owners);
    }

    @Override
    // 有界批次发送目标校准通知，消费者重新读取当前状态以处理乱序。
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        likeTargetValidator.requireSupportedType(bizType);
        if (maxBizSize <= 0) throw BusinessException.badRequest("同步批次必须为正整数");
        String changeKey = StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, bizType);
        dirtySetProcessor.processBatch(changeKey, maxBizSize, false, claimed -> {
            List<Long> ids = uniqueIds(claimed.stream().map(Long::valueOf).toList());
            List<?> states = inspect(bizType, ids, "");
            List<LikeCountMessage> messages = new ArrayList<>(claimed.size());
            for (int index = 0; index < ids.size(); index++) {
                Long id = ids.get(index);
                List<?> count = inspectedState(states, index);
                if (count == null) {
                    // A vanished target can only consume a zero; a live target keeps its dirty task.
                    if (baseMapper.lockTarget(bizType, id) != null) throw unavailable();
                }
                messages.add(LikeCountMessage.builder().bizType(bizType).bizId(id)
                        .likeCount(count == null ? 0L : ((Number) count.get(0)).longValue()).build());
            }
            // 发布确认成功后处理器才删除 processing。异常时整批返回 pending，重复通知由消费者重新核对当前计数。
            mqHelper.sendConfirmed(LIKE_COUNT_EXCHANGE, StringUtils.format(BIZ_TYPE_LIKED_TIMES_KEY, bizType), messages);
        });
    }

    @Override
    @Transactional
    // 目标锁内读取当前完整关系计数并更新数据库，异常向外传播进入监听器重试。
    public void syncLikeCounts(String bizType, List<Long> targetIds) {
        likeTargetValidator.requireSupportedType(bizType);
        if (targetIds == null || targetIds.isEmpty()) return;
        Map<Long, LikeRecordMapper.Target> targets = new LinkedHashMap<>();
        // 这里与 like/unlike/物理删除使用同一行锁，读取后的计数在本事务内有稳定来源。
        for (Long id : uniqueIds(targetIds).stream().sorted().toList()) {
            LikeRecordMapper.Target target = baseMapper.lockTarget(bizType, id);
            if (target != null) targets.put(id, target);
        }
        if (targets.isEmpty()) return;
        List<Long> ids = new ArrayList<>(targets.keySet());
        List<?> states = inspect(bizType, ids, "");
        Map<Long, Integer> currentCounts = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            List<?> state = inspectedState(states, index);
            // 消费者需要当前完整计数，展示接口允许的数据库回退不能用于覆盖数据库计数。
            if (state == null) throw unavailable();
            currentCounts.put(ids.get(index), Math.toIntExact(((Number) state.get(0)).longValue()));
        }
        currentCounts.forEach((id, count) -> {
            // 重复、延迟或乱序消息都读取当前状态。值已一致时跳过更新，保留原更新时间。
            if (!Objects.equals(targets.get(id).likeCount(), count)) {
                if (baseMapper.updateTargetLikeCount(bizType, id, count, LocalDateTime.now()) != 1) {
                    throw BusinessException.internalError("点赞计数更新失败");
                }
            }
        });
    }

    /** 清理已物理删除内容的点赞关系，提交后回收缓存。 */
    @Override
    @Transactional
    // 物理内容删除事务中移除持久化关系，提交后清除 Redis 派生状态。
    public void clearLikeCache(String bizType, Long bizId) {
        if (bizId == null || StringUtils.isBlank(bizType)) return;
        likeTargetValidator.requireSupportedType(bizType);
        if (bizId <= 0) throw BusinessException.badRequest("点赞目标ID必须为正整数");
        if (baseMapper.lockTarget(bizType, bizId) != null) throw BusinessException.conflict("点赞目标仍存在");
        removeDeletedTarget(bizType, bizId);
    }

    private void removeDeletedTarget(String type, Long id) {
        baseMapper.delete(scope(type, id));
        afterCommitClear(type, id);
    }

    private void afterCommitClear(String type, Long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("点赞生命周期操作需要数据库事务");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    Long cleared = redisTemplate.execute(CLEAR_SCRIPT, mutationKeys(type, id), id.toString());
                    if (!Long.valueOf(1).equals(cleared)) throw new IllegalStateException("点赞清理未返回成功");
                } catch (RuntimeException failure) {
                    // The source and relationship deletion already committed. Queue an idempotent retry.
                    // 数据库已提交，清理失败通过脏队列继续重试，避免把已完成的删除返回成失败。
                    try {
                        redisTemplate.opsForSet().add(StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, type), id.toString());
                    } catch (RuntimeException retryFailure) {
                        if (retryFailure != failure) failure.addSuppressed(retryFailure);
                    }
                    log.warn("已删除内容的点赞缓存待清理, type={}, targetId={}, errorType={}",
                            type, id, failure.getClass().getSimpleName());
                }
            }
        });
    }

    private LambdaQueryWrapper<LikeRecord> scope(String type, Long id) {
        return new LambdaQueryWrapper<LikeRecord>().eq(LikeRecord::getType, type).eq(LikeRecord::getTargetId, id);
    }

    private List<String> mutationKeys(String type, Long id) {
        return List.of(StringUtils.format(LIKE_USER_KEY, type, id), StringUtils.format(LIKE_EXPECTED_KEY, type, id),
                StringUtils.format(LIKE_BIZ_IDS_KEY, type), StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, type),
                StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, type));
    }

    private BusinessException unavailable() {
        return new BusinessException(503, 503, "点赞缓存待恢复，请稍后重试");
    }
}
