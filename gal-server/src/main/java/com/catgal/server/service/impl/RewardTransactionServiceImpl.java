package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.domain.vo.SignVO;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.common.enums.PointsType;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.catgal.common.constants.CutePointsPrizeConstant.*;

/**
 * 新积分写入的唯一业务入口。
 * 锁序固定为切换屏障共享锁 → 用户 → 积分行 → 奖品 ID 升序。
 * MySQL 提交包含余额、流水、库存、结果与 Outbox，Redis/MQ 故障不会撤销已提交结果。
 */
@Service
@RequiredArgsConstructor
public class RewardTransactionServiceImpl extends ServiceImpl<RewardRequestMapper, RewardRequest>
        implements IRewardTransactionService {
    public static final int DRAW_REJECTED = 40921;
    private final IRewardControlService control;
    private final UserMapper users;
    private final UserCutePointsMapper balances;
    private final CutePointsRecordMapper ledger;
    private final PrizePoolMapper prizes;
    private final DrawRecordMapper draws;
    private final RewardRequestMapper requests;
    private final RewardOutboxMapper outbox;
    private final IUserAccountStatusService accounts;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signIn(Long userId) {
        control.requireActiveTransaction();
        lockUser(userId);
        LocalDate date = LocalDate.now(clock);
        if (find(userId, "SIGN", date.toString()) != null) return;
        UserCutePoints balance = balanceForUpdate(userId);
        String bizId = "sign:" + userId + ":" + date;
        changePoints(balance, SIGN_IN_REWARD, PointsType.SIGN_IN, bizId);
        RewardRequest request = request(userId, "SIGN", date.toString(), bizId, date);
        request.setStatus("COMPLETED");
        insertWithEvent(request);
    }

    @Override
    public SignVO signRecords(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now(clock);
        List<RewardRequest> records = requests.selectList(Wrappers.<RewardRequest>lambdaQuery()
                .eq(RewardRequest::getUserId, userId).eq(RewardRequest::getAction, "SIGN")
                .eq(RewardRequest::getStatus, "COMPLETED")
                .ge(RewardRequest::getBusinessDate, today.withDayOfMonth(1))
                .le(RewardRequest::getBusinessDate, today));
        Set<Integer> days = new TreeSet<>();
        records.forEach(record -> days.add(record.getBusinessDate().getDayOfMonth()));
        int continuous = 0;
        for (int day = today.getDayOfMonth(); day > 0 && days.contains(day); day--) continuous++;
        SignVO result = new SignVO();
        result.setSignedDays(days.stream().map(Integer::longValue).toList());
        result.setContinuousDays(continuous);
        return result;
    }

    @Override
    public UserPrizeVO draw(Long userId, String requestKey) {
        String key = normalizeRequestKey(requestKey);
        // 业务拒绝也先提交请求结果，再在事务外转换为 HTTP 错误，避免失败幂等记录随异常回滚。
        DrawOutcome result = transactions.execute(status -> drawInTransaction(userId, key));
        Objects.requireNonNull(result);
        return resultOf(result.request(), result.replayed());
    }

    private DrawOutcome drawInTransaction(Long userId, String key) {
        control.requireActiveTransaction();
        lockUser(userId);
        RewardRequest existing = find(userId, "DRAW", key);
        if (existing != null) return new DrawOutcome(existing, true);
        LocalDate date = LocalDate.now(clock);
        RewardRequest request = request(userId, "DRAW", key, "draw:" + userId + ":" + key, date);
        UserCutePoints balance = balanceForUpdate(userId);
        int cost = Math.abs(PRIZE_POOL_CONSUME);
        if (balance.getCutePoints() < cost) return new DrawOutcome(reject(request, "萌萌点不足"), false);
        // 当月全部奖品按 ID 锁定，管理员也采用同样顺序。权重使用 long 累加，支持多个大权重。
        List<PrizePool> available = prizes.lockMonth(date.format(DateTimeFormatter.ofPattern("yyyyMM"))).stream()
                .filter(prize -> Objects.equals(prize.getStatus(), 1) && prize.getWeight() > 0
                        && (prize.getTotalStock() == 0 || prize.getRemainStock() > 0)).toList();
        if (available.isEmpty()) return new DrawOutcome(reject(request, "暂无可用奖品"), false);
        long totalWeight = available.stream().mapToLong(PrizePool::getWeight).sum();
        long pick = ThreadLocalRandom.current().nextLong(totalWeight);
        PrizePool selected = available.get(available.size() - 1);
        for (PrizePool prize : available) {
            pick -= prize.getWeight();
            if (pick < 0) { selected = prize; break; }
        }
        if (selected.getTotalStock() > 0) {
            selected.setRemainStock(selected.getRemainStock() - 1);
            selected.setRevision(nextRevision(selected.getRevision()));
            selected.setUpdateTime(LocalDateTime.now(clock));
            requireOne(prizes.updateById(selected));
        }
        changePoints(balance, PRIZE_POOL_CONSUME, PointsType.LOTTERY_COST, request.getBizId());
        DrawRecord draw = new DrawRecord();
        draw.setBizId(request.getBizId()); draw.setPrizeId(selected.getId()); draw.setUserId(userId);
        draw.setCreateTime(LocalDateTime.now(clock)); draw.setUpdateTime(draw.getCreateTime());
        requireOne(draws.insert(draw));
        UserPrizeVO view = new UserPrizeVO();
        view.setId(selected.getId()); view.setUserId(userId); view.setRequestKey(key);
        view.setBizId(request.getBizId()); view.setSpentCutePoints(cost); view.setPointsBalance(balance.getCutePoints());
        view.setPrizeName(selected.getPrizeName()); view.setPrizeImage(selected.getPrizeImage());
        view.setPrizeType(selected.getPrizeType()); view.setReplayed(false);
        request.setStatus("COMPLETED"); request.setResultJson(encode(view));
        insertWithEvent(request);
        return new DrawOutcome(request, false);
    }

    @Override
    public UserPrizeVO drawResult(Long userId, String requestKey) {
        requireUserId(userId);
        RewardRequest request = find(userId, "DRAW", normalizeRequestKey(requestKey));
        if (request == null) throw new BusinessException(40421, 404, "该请求尚无已提交结果，可使用相同请求号重试");
        return resultOf(request, true);
    }

    private RewardRequest reject(RewardRequest request, String reason) {
        request.setStatus("REJECTED"); request.setRejectionReason(reason);
        requireOne(requests.insert(request));
        return request;
    }

    private UserPrizeVO resultOf(RewardRequest request, boolean replayed) {
        if ("REJECTED".equals(request.getStatus()))
            throw new BusinessException(DRAW_REJECTED, 409, request.getRejectionReason());
        try {
            UserPrizeVO view = json.readValue(request.getResultJson(), UserPrizeVO.class);
            view.setReplayed(replayed);
            return view;
        } catch (JsonProcessingException exception) {
            throw BusinessException.internalError("抽奖历史结果无法读取", exception);
        }
    }

    private void changePoints(UserCutePoints balance, int delta, PointsType type, String bizId) {
        int before = balance.getCutePoints();
        try {
            balance.setCutePoints(Math.addExact(before, delta));
            if (delta > 0) balance.setTotalEarned(Math.addExact(balance.getTotalEarned(), delta));
            else balance.setTotalSpent(Math.addExact(balance.getTotalSpent(), -delta));
            balance.setRevision(nextRevision(balance.getRevision()));
        } catch (ArithmeticException exception) {
            throw BusinessException.conflict("萌萌点或累计数达到上限，请联系管理员核对");
        }
        if (balance.getCutePoints() < 0) throw BusinessException.conflict("萌萌点不足");
        balance.setUpdateTime(LocalDateTime.now(clock));
        requireOne(balances.updateById(balance));
        CutePointsRecord record = new CutePointsRecord();
        record.setUserId(balance.getUserId()); record.setCutePoints(delta); record.setType(type.getCode());
        record.setBizId(bizId); record.setBeforePoints(before); record.setAfterPoints(balance.getCutePoints());
        record.setRemark(type.getDesc()); record.setCreateTime(LocalDateTime.now(clock));
        requireOne(ledger.insert(record));
    }

    private UserCutePoints balanceForUpdate(Long userId) {
        UserCutePoints balance = balances.lockBalance(userId);
        // 用户行已持锁，同用户首次建余额不会竞争。缺失余额初始化为 0，历史余额由切换基线保证。
        if (balance == null) {
            balance = new UserCutePoints().setUserId(userId).setCutePoints(0).setTotalEarned(0)
                    .setTotalSpent(0).setRevision(0L).setCreateTime(LocalDateTime.now(clock))
                    .setUpdateTime(LocalDateTime.now(clock));
            requireOne(balances.insert(balance));
        }
        if (balance.getCutePoints() < 0 || balance.getTotalEarned() < 0 || balance.getTotalSpent() < 0)
            throw BusinessException.conflict("积分账本异常，请联系管理员核对");
        return balance;
    }

    private void lockUser(Long userId) {
        requireUserId(userId);
        User user = users.selectForUpdate(userId);
        accounts.requireActive(user);
    }

    private void requireUserId(Long id) {
        if (id == null || id <= 0) throw BusinessException.unauthorized("用户未登录");
    }

    private RewardRequest find(Long userId, String action, String key) {
        return requests.selectOne(Wrappers.<RewardRequest>lambdaQuery().eq(RewardRequest::getUserId, userId)
                .eq(RewardRequest::getAction, action).eq(RewardRequest::getRequestKey, key));
    }

    private RewardRequest request(Long userId, String action, String key, String bizId, LocalDate date) {
        RewardRequest request = new RewardRequest();
        request.setUserId(userId); request.setAction(action); request.setRequestKey(key); request.setBizId(bizId);
        request.setBusinessDate(date); request.setLegacyImported(false); request.setCreateTime(LocalDateTime.now(clock));
        return request;
    }

    private void insertWithEvent(RewardRequest request) {
        requireOne(requests.insert(request));
        RewardOutbox event = new RewardOutbox();
        event.setRequestId(request.getId());
        // 调度列统一写 UTC，不依赖 MySQL 会话时区。业务日期仍按站点时钟解释。
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        event.setNextAttemptAt(now);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        requireOne(outbox.insert(event));
    }

    public static String normalizeRequestKey(String key) {
        if (key == null || !key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw BusinessException.badRequest("Idempotency-Key 必须是 UUID");
        return key.toLowerCase(Locale.ROOT);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw BusinessException.internalError("无法保存抽奖结果", exception); }
    }

    private long nextRevision(Long revision) { return Math.addExact(revision, 1L); }
    private void requireOne(int count) {
        if (count != 1) throw BusinessException.conflict("积分事务写入失败，请使用原请求号重试");
    }
    private record DrawOutcome(RewardRequest request, boolean replayed) { }
}
