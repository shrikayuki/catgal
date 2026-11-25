/*
 * 阅读说明
 * 用户管理、内容治理、奖池维护及 Redis 运维审计。
 * MySQL 业务变更同事务记录。跨 Redis 和 MySQL 的运维请求独立记录执行结果。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.AdminRedisOperation;
import com.catgal.server.domain.AdminRedisOperation.Outcome;
import com.catgal.server.domain.po.AdminOperationLog;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.query.admin.AdminContentOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminOperationLogFilter;
import com.catgal.server.domain.query.admin.AdminOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminPrizeOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminRedisOperationLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;
import com.catgal.server.mapper.AdminOperationLogMapper;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IUserAccountStatusService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;
import static com.catgal.server.service.IUserAccountStatusService.TIME_ZONE;

/** 审计的持久化与只读查询。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminOperationLogServiceImpl extends ServiceImpl<AdminOperationLogMapper, AdminOperationLog>
        implements IAdminOperationLogService {
    // 增加可审计字段时同时检查调用方快照和前端展示，敏感凭据始终排除在快照之外。
    private static final Set<String> USER_SNAPSHOT_FIELDS = Set.of("id", "username", "role", "storedStatus", "status", "banReason", "bannedUntil");
    private static final Set<String> CONTENT_SNAPSHOT_FIELDS = Set.of("gameId", "status");
    private static final Set<String> USER_OPERATIONS = Set.of("USER_BAN", "USER_UNBAN", "USER_ROLE_CHANGE");
    private static final Set<String> CONTENT_OPERATIONS = Set.of("COMMENT_HIDE", "COMMENT_RESTORE", "REVIEW_HIDE", "REVIEW_RESTORE");
    private static final Set<String> PRIZE_OPERATIONS = Set.of("PRIZE_CREATE", "PRIZE_UPDATE", "PRIZE_ENABLE", "PRIZE_DISABLE");
    private static final Set<String> PRIZE_SNAPSHOT_FIELDS = Set.of(
            "prizeName", "prizeType", "month", "weight", "totalStock", "remainStock", "status", "revision");
    private static final String REDIS_MODULE = "redis_consistency";
    private static final String REDIS_OUTCOME_UNCONFIRMED = "REDIS_OUTCOME_UNCONFIRMED";
    private static final String REDIS_EXECUTION_FAILED = "REDIS_EXECUTION_FAILED";
    private final ObjectMapper objectMapper;
    private final IUserAccountStatusService accountStatus;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendUserChange(Long actorId, String operation, Long targetId,
                                 Map<String, Object> before, Map<String, Object> after, String reason) {
        // 必须加入业务事务，审计插入失败将回滚状态，禁止把成功日志提前单独提交。
        if (!USER_OPERATIONS.contains(operation)
                || !USER_SNAPSHOT_FIELDS.containsAll(before.keySet()) || !USER_SNAPSHOT_FIELDS.containsAll(after.keySet())) {
            throw new IllegalArgumentException("用户审计字段超出允许范围");
        }
        append(actorId, "user", operation, "user", targetId, before, after, reason);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendContentStatusChange(Long actorId, String targetType, Long targetId,
                                          Long gameId, Integer oldStatus, int newStatus) {
        if (newStatus != 0 && newStatus != 1) {
            throw new IllegalArgumentException("内容审计状态只能是隐藏或正常");
        }
        String operation = switch (targetType) {
            case "comment" -> newStatus == 0 ? "COMMENT_HIDE" : "COMMENT_RESTORE";
            case "review" -> newStatus == 0 ? "REVIEW_HIDE" : "REVIEW_RESTORE";
            default -> throw new IllegalArgumentException("内容审计目标只能是评论或评价");
        };
        append(actorId, "content", operation, targetType, targetId,
                contentSnapshot(gameId, oldStatus), contentSnapshot(gameId, newStatus), null);
    }

    private Map<String, Object> contentSnapshot(Long gameId, Integer status) {
        // 历史状态可为空，编号以字符串输出，避免浏览器转换大整数丢失精度。
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("gameId", gameId == null ? null : gameId.toString());
        snapshot.put("status", status);
        return snapshot;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendPrizeChange(Long actorId, String operation, PrizePool before, PrizePool after) {
        if (!PRIZE_OPERATIONS.contains(operation) || after == null || ("PRIZE_CREATE".equals(operation) != (before == null))
                || (before != null && !Objects.equals(before.getId(), after.getId()))) {
            throw new IllegalArgumentException("奖品审计操作或快照无效");
        }
        append(actorId, "prize", operation, "prize", after.getId(), prizeSnapshot(before), prizeSnapshot(after), null);
    }

    private Map<String, Object> prizeSnapshot(PrizePool prize) {
        if (prize == null) return Map.of();
        // 用明确字段记录库存和版本。图片地址可能含访问参数，不写入快照。
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("prizeName", prize.getPrizeName());
        snapshot.put("prizeType", prize.getPrizeType());
        snapshot.put("month", prize.getMonth());
        snapshot.put("weight", prize.getWeight());
        snapshot.put("totalStock", prize.getTotalStock());
        snapshot.put("remainStock", prize.getRemainStock());
        snapshot.put("status", prize.getStatus());
        snapshot.put("revision", prize.getRevision().toString());
        return snapshot;
    }

    private void append(Long actorId, String module, String operation, String targetType, Long targetId,
                        Map<String, Object> before, Map<String, Object> after, String reason) {
        if (targetId == null || targetId <= 0) throw new IllegalArgumentException("审计目标编号无效");
        AdminOperationLog entry = newEntry(actorId, module, operation, targetType, targetId);
        entry.setBeforeData(json(before)); entry.setAfterData(json(after)); entry.setReason(reason);
        entry.setResult(1);
        insert(entry);
    }

    private AdminOperationLog newEntry(Long actorId, String module, String operation, String targetType, Long targetId) {
        if (actorId == null || actorId <= 0) throw new IllegalArgumentException("审计操作者编号无效");
        AdminOperationLog entry = new AdminOperationLog();
        entry.setAdminUserId(actorId); entry.setModule(module); entry.setOperation(operation);
        entry.setTargetType(targetType); entry.setTargetId(targetId == null ? null : targetId.toString());
        // HTTP 请求由 RequestTraceFilter 生成追踪号，后台任务等入口使用独立 UUID。
        String requestId = MDC.get(REQUEST_ID_HEADER);
        entry.setRequestId(requestId != null && requestId.matches("[A-Za-z0-9_-]{1,64}") ? requestId : UUID.randomUUID().toString());
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            // 保存实际连接对端，避免直接信任客户端可伪造的 X-Forwarded-For。
            String peer = attributes.getRequest().getRemoteAddr();
            if (peer != null && peer.length() <= 45) entry.setIpAddress(peer);
        }
        entry.setCreateTime(accountStatus.now());
        return entry;
    }

    private void insert(AdminOperationLog entry) {
        if (!save(entry)) throw new IllegalStateException("管理员操作审计写入失败");
    }

    @Override
    public <T> T auditRedisMaintenance(Long actorId, AdminRedisOperation operation, Long targetId, Supplier<T> action) {
        if ((operation == AdminRedisOperation.COUNTERS_SYNC) != (targetId == null)) {
            throw new IllegalArgumentException("Redis 运维目标与操作范围不匹配");
        }
        AdminOperationLog entry = newEntry(actorId, REDIS_MODULE, operation.name(), operation.targetType(), targetId);
        entry.setResult(0);
        entry.setErrorMessage(REDIS_OUTCOME_UNCONFIRMED);
        TransactionTemplate auditTransaction = new TransactionTemplate(transactionManager);
        auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 先提交请求记录。Redis 和 MySQL 分开写入，应用退出时仍能通过追踪号找到未确认的操作。
        auditTransaction.executeWithoutResult(status -> {
            insert(entry);
            if (entry.getId() == null) throw new IllegalStateException("Redis 运维审计缺少记录编号");
        });

        T result;
        try {
            result = action.get();
        } catch (RuntimeException failure) {
            finishRedisMaintenance(auditTransaction, entry, Outcome.FAILED);
            throw failure;
        }
        finishRedisMaintenance(auditTransaction, entry, Outcome.COMPLETED);
        return result;
    }

    private void finishRedisMaintenance(TransactionTemplate transaction, AdminOperationLog entry, Outcome outcome) {
        try {
            transaction.executeWithoutResult(status -> {
                boolean completed = outcome == Outcome.COMPLETED;
                int updated = baseMapper.update(null, Wrappers.<AdminOperationLog>lambdaUpdate()
                        .eq(AdminOperationLog::getId, entry.getId())
                        .eq(AdminOperationLog::getModule, REDIS_MODULE)
                        .eq(AdminOperationLog::getResult, 0)
                        .eq(AdminOperationLog::getErrorMessage, REDIS_OUTCOME_UNCONFIRMED)
                        .set(AdminOperationLog::getResult, completed ? 1 : 0)
                        .set(AdminOperationLog::getErrorMessage, completed ? null : REDIS_EXECUTION_FAILED));
                if (updated != 1) throw new IllegalStateException("Redis 运维审计结果更新失败");
            });
        } catch (RuntimeException failure) {
            // 此时动作可能已生效，保留未确认记录及原 HTTP 结果，避免客户端把审计异常当成修复失败而重复执行。
            log.warn("Redis 运维审计结果未能确认, auditId={}, requestId={}, outcome={}, errorType={}",
                    entry.getId(), entry.getRequestId(), outcome, failure.getClass().getSimpleName());
        }
    }

    @Override
    public Map<Long, Long> userRevisions(Collection<Long> ids) {
        // 空页无需执行 SQL，同时避免 Mapper 生成空的 IN 条件。
        if (ids.isEmpty()) return Map.of();
        Map<Long, Long> revisions = new HashMap<>();
        for (var row : baseMapper.userRevisions(ids.stream().map(String::valueOf).toList())) {
            revisions.put(Long.valueOf(row.targetId()), row.revision());
        }
        return revisions;
    }

    @Override
    public PageDTO<AdminOperationLogVO> pageUserOperations(AdminOperationLogQuery query) {
        // 用户查询始终限定在原有模块，内容审计不会混进旧页面或用户版本锚点。
        var wrapper = auditFilter(query, "user")
                .eq(AdminOperationLog::getTargetType, "user")
                .eq(query.getTargetUserId() != null, AdminOperationLog::getTargetId, String.valueOf(query.getTargetUserId()))
                .eq(query.getOperation() != null, AdminOperationLog::getOperation, query.getOperation());
        return PageDTO.of(page(query.toSafePage(), wrapper), row -> toView(row, USER_SNAPSHOT_FIELDS));
    }

    @Override
    public PageDTO<AdminOperationLogVO> pageContentOperations(AdminContentOperationLogQuery query) {
        var wrapper = auditFilter(query, "content")
                .eq(AdminOperationLog::getResult, 1)
                .in(AdminOperationLog::getTargetType, "comment", "review")
                .in(AdminOperationLog::getOperation, CONTENT_OPERATIONS)
                .eq(query.getTargetType() != null, AdminOperationLog::getTargetType, query.getTargetType())
                .eq(query.getTargetId() != null, AdminOperationLog::getTargetId, String.valueOf(query.getTargetId()))
                .eq(query.getOperation() != null, AdminOperationLog::getOperation, query.getOperation());
        return PageDTO.of(page(query.toSafePage(), wrapper), row -> {
            AdminOperationLogVO view = toView(row, CONTENT_SNAPSHOT_FIELDS);
            view.setTargetType(row.getTargetType());
            view.setReason(null);
            return view;
        });
    }

    @Override
    public PageDTO<AdminOperationLogVO> pagePrizeOperations(AdminPrizeOperationLogQuery query) {
        var wrapper = auditFilter(query, "prize")
                .eq(AdminOperationLog::getResult, 1)
                .eq(AdminOperationLog::getTargetType, "prize")
                .in(AdminOperationLog::getOperation, PRIZE_OPERATIONS)
                .eq(query.getTargetId() != null, AdminOperationLog::getTargetId, String.valueOf(query.getTargetId()))
                .eq(query.getOperation() != null, AdminOperationLog::getOperation, query.getOperation());
        return PageDTO.of(page(query.toSafePage(), wrapper), row -> {
            AdminOperationLogVO view = toView(row, PRIZE_SNAPSHOT_FIELDS);
            view.setTargetType("prize");
            view.setReason(null);
            return view;
        });
    }

    @Override
    public PageDTO<AdminOperationLogVO> pageRedisOperations(AdminRedisOperationLogQuery query) {
        var wrapper = auditFilter(query, REDIS_MODULE)
                .eq(query.getOperation() != null, AdminOperationLog::getOperation, Objects.toString(query.getOperation(), null))
                .eq(query.getTargetId() != null, AdminOperationLog::getTargetId, Objects.toString(query.getTargetId(), null));
        if (query.getOutcome() != null) {
            switch (query.getOutcome()) {
                case COMPLETED -> wrapper.eq(AdminOperationLog::getResult, 1);
                case FAILED -> wrapper.eq(AdminOperationLog::getResult, 0)
                        .eq(AdminOperationLog::getErrorMessage, REDIS_EXECUTION_FAILED);
                case UNCONFIRMED -> wrapper.eq(AdminOperationLog::getResult, 0)
                        .eq(AdminOperationLog::getErrorMessage, REDIS_OUTCOME_UNCONFIRMED);
            }
        }
        return PageDTO.of(page(query.toSafePage(), wrapper), row -> {
            // 运维记录只展示执行元数据，快照和内部错误摘要不进入响应。
            AdminOperationLogVO view = operationMetadata(row);
            view.setTargetType(row.getTargetType());
            view.setBeforeData(Map.of());
            view.setAfterData(Map.of());
            view.setOutcome(Integer.valueOf(1).equals(row.getResult()) ? Outcome.COMPLETED
                    : REDIS_EXECUTION_FAILED.equals(row.getErrorMessage()) ? Outcome.FAILED : Outcome.UNCONFIRMED);
            return view;
        });
    }

    private LambdaQueryWrapper<AdminOperationLog> auditFilter(AdminOperationLogFilter query, String module) {
        if (query.getFrom() != null && query.getTo() != null && query.getFrom().isAfter(query.getTo())) {
            throw BusinessException.badRequest("开始时间不能晚于结束时间");
        }
        var wrapper = Wrappers.<AdminOperationLog>lambdaQuery().eq(AdminOperationLog::getModule, module)
                .eq(query.getAdminUserId() != null, AdminOperationLog::getAdminUserId, query.getAdminUserId())
                .eq(query.getRequestId() != null && !query.getRequestId().isBlank(), AdminOperationLog::getRequestId, query.getRequestId());
        // 数据库存储站点本地时间，接收不同偏移的请求时先统一到北京时间。
        if (query.getFrom() != null) wrapper.ge(AdminOperationLog::getCreateTime, query.getFrom().atZoneSameInstant(TIME_ZONE).toLocalDateTime());
        if (query.getTo() != null) wrapper.le(AdminOperationLog::getCreateTime, query.getTo().atZoneSameInstant(TIME_ZONE).toLocalDateTime());
        return wrapper;
    }

    private AdminOperationLogVO toView(AdminOperationLog row, Set<String> snapshotFields) {
        AdminOperationLogVO view = operationMetadata(row);
        view.setBeforeData(readJson(row.getBeforeData(), snapshotFields));
        view.setAfterData(readJson(row.getAfterData(), snapshotFields));
        view.setReason(row.getReason());
        return view;
    }

    private AdminOperationLogVO operationMetadata(AdminOperationLog row) {
        AdminOperationLogVO view = new AdminOperationLogVO();
        view.setId(row.getId()); view.setAdminUserId(row.getAdminUserId()); view.setOperation(row.getOperation());
        view.setTargetId(row.getTargetId());
        view.setRequestId(row.getRequestId()); view.setIpAddress(row.getIpAddress());
        view.setCreateTime(row.getCreateTime().atZone(TIME_ZONE).toOffsetDateTime());
        return view;
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("审计序列化失败", failure); }
    }

    private Map<String, Object> readJson(String value, Set<String> snapshotFields) {
        if (value == null) return Map.of();
        try {
            Map<String, Object> values = objectMapper.readValue(value, new TypeReference<>() { });
            // 查询时再次过滤，历史或其他入口写入的额外字段也不会直接透传到管理页面。
            values.keySet().retainAll(snapshotFields);
            return values;
        }
        catch (JsonProcessingException failure) { throw new IllegalStateException("审计快照格式错误", failure); }
    }
}
