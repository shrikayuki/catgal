package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import static com.catgal.common.constants.RedisConstant.*;

/**
 * 切换步骤为 LEGACY → DRAINING → 导入已核对签到 → ACTIVE。
 * DRAINING 拒绝新来源，允许旧 MQ 和脏任务排空。共享行锁让切换等待已进入的操作结束。
 * 只导入有消费凭据或已有流水佐证的签到，不推测历史余额，也不自动修正不一致数据。
 */
@Service
@RequiredArgsConstructor
public class RewardControlServiceImpl implements IRewardControlService {
    private final RewardRuntimeMapper runtime;
    private final UserMapper users;
    private final IUserAccountStatusService accounts;
    private final IConfirmedRewardPublisher publisher;
    private final AmqpAdmin rabbitAdmin;
    private final RabbitListenerEndpointRegistry listeners;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final Environment environment;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile boolean ready;
    private volatile boolean activeSeen;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Set<String> LEGACY_QUEUES = Set.of("cute.points.sign_in.queue",
            "cute.points.draw_consumed.queue", "draw.record.queue");

    @PostConstruct
    public void detectSchema() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('reward_runtime','reward_request','reward_outbox')", Integer.class);
        ready = Objects.equals(count, 3);
    }

    @Override public boolean schemaReady() { return ready; }
    @Override
    public String mode() {
        if (!ready) return "LEGACY";
        // ACTIVE 是终态，确认后可缓存，避免每次命中积分 Redis 都额外查询控制表。
        // 写入事务仍读取持锁的真实控制行，缓存不承担资金写入授权。
        if (activeSeen) return "ACTIVE";
        String mode = requireRuntime(runtime.current()).getMode();
        if ("ACTIVE".equals(mode)) activeSeen = true;
        return mode;
    }
    @Override public boolean isActive() { return "ACTIVE".equals(mode()); }

    @Override
    public <T> T legacySource(Supplier<T> action) {
        if (!ready) return action.get();
        return transactions.execute(status -> {
            if (!"LEGACY".equals(requireRuntime(runtime.lockShared()).getMode()))
                throw new BusinessException(50321, 503, "积分系统正在切换，请稍后使用原请求号重试");
            return action.get();
        });
    }

    @Override
    public <T> T legacyDrain(Supplier<T> action) {
        if (!ready) return action.get();
        return transactions.execute(status -> {
            if (!Set.of("LEGACY", "DRAINING").contains(requireRuntime(runtime.lockShared()).getMode()))
                throw new AmqpRejectAndDontRequeueException("旧积分消息已隔离，请先核对切换前账本");
            return action.get();
        });
    }

    @Override
    public void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("积分写入必须处于数据库事务内");
        if (!ready || !"ACTIVE".equals(requireRuntime(runtime.lockShared()).getMode()))
            throw new BusinessException(50321, 503, "新积分事务尚未激活，请稍后重试");
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaReady", ready); result.put("mode", mode());
        if (!ready) return result;
        RewardRuntime state = requireRuntime(runtime.current());
        List<String> blockers = drainBlockers();
        result.put("baselineCursor", state.getBaselineCursor().toString());
        result.put("baselineComplete", state.getBaselineComplete()); result.put("baselineDate", state.getBaselineDate());
        result.put("blockers", blockers);
        result.put("readyToActivate", "DRAINING".equals(state.getMode()) && blockers.isEmpty()
                && Boolean.TRUE.equals(state.getBaselineComplete()) && LocalDate.now(clock).equals(state.getBaselineDate()));
        return result;
    }

    @Override
    public Map<String, Object> beginDrain() {
        exclusive(() -> {
            RewardRuntime state = requireRuntime(runtime.lockExclusive());
            requireAdministrator();
            if ("ACTIVE".equals(state.getMode())) throw BusinessException.conflict("新积分事务已激活");
            if ("LEGACY".equals(state.getMode())) jdbc.update("UPDATE reward_runtime SET mode='DRAINING',"
                    + "baseline_cursor=0,baseline_complete=0,baseline_date=?,updated_by=?,update_time=UTC_TIMESTAMP(6) WHERE id=1",
                    LocalDate.now(clock), UserContext.getUserId());
            return null;
        });
        return status();
    }

    @Override
    public Map<String, Object> importBaseline() {
        exclusive(() -> {
            RewardRuntime state = requireRuntime(runtime.lockExclusive());
            requireAdministrator(); requireDraining(state); requireDrained();
            LocalDate today = LocalDate.now(clock);
            // 跨午夜后重新扫描本月，已导入请求有唯一键保护。
            long cursor = today.equals(state.getBaselineDate()) ? state.getBaselineCursor() : 0L;
            List<Long> ids = jdbc.queryForList("SELECT id FROM user WHERE id>? ORDER BY id LIMIT 25", Long.class, cursor);
            for (Long userId : ids) {
                verifyBalance(userId);
                importSigns(userId, today);
                cursor = userId;
            }
            verifyPrizeSnapshots();
            jdbc.update("UPDATE reward_runtime SET baseline_cursor=?,baseline_complete=?,baseline_date=?,"
                    + "updated_by=?,update_time=UTC_TIMESTAMP(6) WHERE id=1", cursor, ids.size() < 25,
                    today, UserContext.getUserId());
            return null;
        });
        return status();
    }

    @Override
    public Map<String, Object> activate() {
        if (!"DRAINING".equals(mode())) throw BusinessException.conflict("请先进入旧积分排空阶段");
        // 先在屏障事务外停止旧消费者，让当前处理提交和 ACK 结束。
        // 然后同时检查 broker 实时 ready 数与 management unacked，避免只依赖滞后的统计采样。
        try {
            stopLegacyConsumers();
            exclusive(() -> {
                RewardRuntime state = requireRuntime(runtime.lockExclusive());
                requireAdministrator();
                requireDraining(state);
                requireDrained();
                if (!Boolean.TRUE.equals(state.getBaselineComplete()) || !LocalDate.now(clock).equals(state.getBaselineDate()))
                    throw BusinessException.conflict("请先完成今天的旧账本和签到基线核对");
                // 排他屏障下再次核对全部余额，防止导入后有人从外部改动旧缓存或数据库。
                for (Long id : jdbc.queryForList("SELECT id FROM user ORDER BY id", Long.class)) verifyBalance(id);
                verifyPrizeSnapshots();
                jdbc.update("UPDATE reward_runtime SET mode='ACTIVE',updated_by=?,activated_at=UTC_TIMESTAMP(6),"
                        + "update_time=UTC_TIMESTAMP(6) WHERE id=1", UserContext.getUserId());
                return null;
            });
        } finally {
            if (!isActive()) startLegacyConsumers();
        }
        return status();
    }

    @Override
    public Map<String, Object> cancelDrain() {
        exclusive(() -> {
            RewardRuntime state = requireRuntime(runtime.lockExclusive());
            requireAdministrator(); requireDraining(state);
            jdbc.update("UPDATE reward_runtime SET mode='LEGACY',updated_by=?,update_time=UTC_TIMESTAMP(6) WHERE id=1",
                    UserContext.getUserId());
            return null;
        });
        return status();
    }

    private List<String> drainBlockers() {
        List<String> blockers = new ArrayList<>();
        // ACTIVE 的发布器处理新 Outbox，正常的在途通知不属于旧流程切换阻塞项。
        if (!isActive() && publisher.inFlight() != 0) blockers.add("旧积分消息仍在等待发送确认");
        try {
            for (String queue : LEGACY_QUEUES) {
                var information = rabbitAdmin.getQueueInfo(queue);
                if (information == null || information.getMessageCount() > 0)
                    blockers.add(queue + " 的 broker 实时检查尚未排空");
            }
        } catch (RuntimeException failure) { blockers.add("无法直接核对 broker 的旧队列状态"); }
        try {
            for (String key : List.of(CUTE_POINTS_CHANGE_SET_KEY, PRIZE_POOL_CHANGE_SET_KEY)) {
                for (String suffix : List.of("", ":processing")) {
                    Long size = redis.opsForSet().size(key + suffix);
                    if (size != null && size > 0) blockers.add(key + suffix + " 仍有 " + size + " 项");
                }
                if (Boolean.TRUE.equals(redis.hasKey("lock:dirty-set:" + key))) blockers.add(key + " 同步任务尚未结束");
            }
        } catch (RuntimeException failure) { blockers.add("无法核对 Redis 旧任务，请检查连接和键类型"); }
        try {
            JsonNode queues = managementQueues();
            Set<String> found = new HashSet<>();
            for (JsonNode queue : queues) {
                String name = queue.path("name").asText();
                if (!LEGACY_QUEUES.contains(name)) continue;
                found.add(name);
                if (!queue.has("messages_ready") || !queue.has("messages_unacknowledged"))
                    blockers.add(name + " 缺少队列统计");
                else if (queue.path("messages_ready").asLong() + queue.path("messages_unacknowledged").asLong() > 0)
                    blockers.add(name + " 仍有待消费或未确认消息");
            }
            if (!found.containsAll(LEGACY_QUEUES)) blockers.add("旧积分队列未全部声明，无法确认排空");
        } catch (Exception failure) { blockers.add("无法读取 RabbitMQ Management 排空状态"); }
        return blockers;
    }

    private JsonNode managementQueues() throws Exception {
        String host = environment.getRequiredProperty("spring.rabbitmq.host");
        int port = environment.getProperty("catgal.rewards.management-port", Integer.class, 15672);
        String vhost = environment.getProperty("spring.rabbitmq.virtual-host", "/");
        URI uri = new URI("http", null, host, port, "/api/queues/", null, null);
        URI endpoint = URI.create(uri.toASCIIString() + URLEncoder.encode(vhost, StandardCharsets.UTF_8));
        String credentials = environment.getRequiredProperty("spring.rabbitmq.username") + ":"
                + environment.getRequiredProperty("spring.rabbitmq.password");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("队列状态读取失败");
        JsonNode result = json.readTree(response.body());
        if (!result.isArray()) throw new IllegalStateException("队列状态格式错误");
        return result;
    }

    private void verifyBalance(Long userId) {
        String key = StringUtils.format(CUTE_POINTS_KEY, userId);
        Map<Object, Object> cached = redis.opsForHash().entries(key);
        if (cached.isEmpty()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT cute_points,total_earned,total_spent FROM user_cute_points WHERE user_id=?", userId);
        Map<String, Object> stored = rows.isEmpty() ? Map.of("cute_points", 0, "total_earned", 0, "total_spent", 0) : rows.get(0);
        Map<String, String> fields = Map.of(CUTE_POINTS_HASH_KEY, "cute_points", TOTAL_EARNED_HASH_KEY,
                "total_earned", TOTAL_SPENT_HASH_KEY, "total_spent");
        for (var field : fields.entrySet()) {
            String value = Objects.toString(cached.get(field.getKey()), "");
            if (!value.equals(Objects.toString(stored.get(field.getValue()))))
                throw BusinessException.conflict("用户 " + userId + " 的旧积分缓存与 MySQL 不一致，请先核对，未自动改写");
        }
    }

    private void importSigns(Long userId, LocalDate today) {
        String signKey = StringUtils.format(SIGN_DATE_USER_KEY, today.format(MONTH), userId);
        for (int day = 1; day <= today.getDayOfMonth(); day++) {
            LocalDate date = today.withDayOfMonth(day);
            String bizId = "sign:" + userId + ":" + date;
            boolean signed = Boolean.TRUE.equals(redis.opsForValue().getBit(signKey, day - 1));
            boolean consumed = Boolean.TRUE.equals(redis.hasKey(StringUtils.format(CUTE_POINTS_MESSAGE_PROCESSED_KEY, bizId)));
            Integer ledgerCount = jdbc.queryForObject("SELECT COUNT(*) FROM cute_points_record WHERE biz_id=? AND user_id=? AND type=1 AND cute_points=1", Integer.class, bizId, userId);
            if (!signed && !consumed && Objects.equals(ledgerCount, 0)) continue;
            if (signed && !consumed && Objects.equals(ledgerCount, 0))
                throw BusinessException.conflict("用户 " + userId + " 在 " + date + " 仅有签到位，缺少到账凭据，请人工核对");
            jdbc.update("INSERT INTO reward_request(user_id,action,request_key,biz_id,business_date,status,legacy_imported) "
                    + "VALUES(?,'SIGN',?,?,?,'COMPLETED',1) ON DUPLICATE KEY UPDATE id=id", userId, date.toString(), bizId, date);
        }
    }

    private void verifyPrizeSnapshots() {
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,month,total_stock,remain_stock FROM prize_pool ORDER BY id")) {
            Object cached = redis.opsForHash().get(StringUtils.format(PRIZE_DATE_CACHE_KEY, row.get("month")), row.get("id").toString());
            if (cached == null) continue;
            try {
                JsonNode prize = json.readTree(cached.toString());
                if (!prize.has("remainStock") || prize.path("remainStock").asInt(-1) != ((Number) row.get("remain_stock")).intValue()
                        || prize.path("totalStock").asInt(-1) != ((Number) row.get("total_stock")).intValue())
                    throw BusinessException.conflict("奖品 " + row.get("id") + " 的旧库存与 MySQL 不一致，请先核对");
            } catch (JsonProcessingException failure) { throw BusinessException.conflict("旧奖池缓存格式异常，请核对后重试"); }
        }
    }

    private <T> T exclusive(Supplier<T> action) {
        if (!ready) throw BusinessException.conflict("请先执行 V19 并重启新版 Java 服务");
        // 管理操作可能包含有限批次 Redis 读取，独立模板避免改写其他业务共用的默认事务配置。
        TransactionTemplate bounded = new TransactionTemplate(transactions.getTransactionManager());
        bounded.setTimeout(60);
        return bounded.execute(status -> action.get());
    }
    private void requireAdministrator() {
        Long id = UserContext.getUserId();
        if (id == null) throw BusinessException.unauthorized("用户未登录");
        User actor = users.selectForUpdate(id); accounts.requireActive(actor);
        if (!Objects.equals(actor.getRole(), 2)) throw BusinessException.forbidden("需要管理员权限");
    }
    private void requireDraining(RewardRuntime state) {
        if (!"DRAINING".equals(state.getMode())) throw BusinessException.conflict("请先进入旧积分排空阶段");
    }
    private void requireDrained() {
        List<String> blockers = drainBlockers();
        if (!blockers.isEmpty()) throw BusinessException.conflict(String.join("，", blockers));
    }
    private void stopLegacyConsumers() {
        for (String id : List.of("legacyRewardPoints", "legacyDrawRecords")) {
            var container = listeners.getListenerContainer(id);
            if (container == null) throw BusinessException.conflict("旧积分消费者未就绪，无法建立切换边界");
            container.stop();
        }
    }
    private void startLegacyConsumers() {
        for (String id : List.of("legacyRewardPoints", "legacyDrawRecords")) {
            var container = listeners.getListenerContainer(id);
            if (container != null && !container.isRunning()) container.start();
        }
    }
    private RewardRuntime requireRuntime(RewardRuntime value) {
        if (value == null) throw BusinessException.conflict("积分切换控制记录缺失，请核对 V19");
        return value;
    }
}
