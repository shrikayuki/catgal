/*
 * 阅读说明
 * AdminPrizeService 负责管理端对应业务的查询与状态维护。
 * Controller 处理参数绑定，事务、校验、缓存更新和结果装配在本服务完成。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.dto.admin.AdminPrizeSaveDTO;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.service.IPrizePoolService;
import com.catgal.server.service.IAdminPrizeService;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IRewardControlService;
import com.catgal.server.service.IUserAccountStatusService;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.domain.po.User;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.catgal.common.constants.DateFormatConstant.YM_FORMATTER;
import static com.catgal.common.constants.RedisConstant.PRIZE_POOL_ADMIN_LOCK_KEY;
import static com.catgal.common.constants.RedisConstant.PRIZE_POOL_CHANGE_SET_KEY;
import static com.catgal.server.redis.RedisScriptLoader.load;

@Service
@RequiredArgsConstructor
public class AdminPrizeServiceImpl implements IAdminPrizeService {

    private static final int STOCK_SYNC_BATCH_SIZE = 500;
    private static final Duration MAINTENANCE_LOCK_TTL = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            load("lua/common/release-lock.lua", Long.class);

    private final PrizePoolMapper prizePoolMapper;
    private final IPrizePoolService prizePoolService;
    private final StringRedisTemplate redisTemplate;
    private final IRewardControlService control;
    private final TransactionTemplate transactions;
    private final UserMapper users;
    private final IUserAccountStatusService accounts;
    private final IAdminOperationLogService auditService;
    private final Clock clock;

    public PageDTO<PrizePool> pagePrizes(AdminPageQuery query) {
        LambdaQueryWrapper<PrizePool> wrapper = new LambdaQueryWrapper<PrizePool>()
                .eq(StringUtils.isNotBlank(query.getMonth()), PrizePool::getMonth,
                        query.getMonth() == null ? null : query.getMonth().replace("-", ""))
                .eq(query.getStatus() != null, PrizePool::getStatus, query.getStatus())
                .like(StringUtils.isNotBlank(query.getKeyword()), PrizePool::getPrizeName, query.getKeyword())
                .orderByDesc(PrizePool::getId);
        Page<PrizePool> page = prizePoolMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page);
    }

    public PrizePool createPrize(AdminPrizeSaveDTO dto) {
        validate(dto);
        PrizePool prize = toEntity(dto, null);
        if (control.isActive()) return activeSave(null, dto);
        return legacyWrite(prize.getMonth(), () -> {
            if (prizePoolMapper.insert(prize) != 1) {
                throw BusinessException.internalError("新增奖品失败");
            }
            return recordPrizeChange("PRIZE_CREATE", null, prize.getId());
        });
    }

    public PrizePool updatePrize(Long prizeId, AdminPrizeSaveDTO dto) {
        validate(dto);
        if (control.isActive()) return activeSave(prizeId, dto);
        PrizePool existing = prizePoolMapper.selectById(prizeId);
        if (existing == null) {
            throw BusinessException.notFound("奖品不存在");
        }
        if (!existing.getMonth().equals(dto.getMonth().replace("-", ""))) {
            throw BusinessException.badRequest("不能修改奖品所属月份，请新建奖品");
        }
        PrizePool update = toEntity(dto, prizeId);
        return legacyWrite(existing.getMonth(), () -> {
            PrizePool before = lockPrize(prizeId);
            if (prizePoolMapper.updateById(update) != 1) {
                throw BusinessException.conflict("奖品已被并发修改，请刷新后重试");
            }
            return recordPrizeChange("PRIZE_UPDATE", before, prizeId);
        });
    }

    public PrizePool updateStatus(Long prizeId, Integer status, String expectedRevision) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.badRequest("奖品状态只能是0停用或1启用");
        }
        PrizePool existing = prizePoolMapper.selectById(prizeId);
        if (existing == null) {
            throw BusinessException.notFound("奖品不存在");
        }
        if (control.isActive()) return transactions.execute(transaction -> {
            control.requireActiveTransaction(); requireAdministrator();
            PrizePool locked = prizePoolMapper.lockMonth(existing.getMonth()).stream()
                    .filter(prize -> prize.getId().equals(prizeId)).findFirst()
                    .orElseThrow(() -> BusinessException.notFound("奖品不存在"));
            checkRevision(locked, expectedRevision);
            if (status.equals(locked.getStatus())) return locked;
            // 保留锁定快照作为变更前状态，只更新启停字段和同一个库存版本。
            PrizePool update = new PrizePool().setId(prizeId).setStatus(status)
                    .setRevision(Math.addExact(locked.getRevision(), 1L))
                    .setUpdateTime(LocalDateTime.now(IUserAccountStatusService.TIME_ZONE));
            if (prizePoolMapper.updateById(update) != 1) throw BusinessException.conflict("奖品状态更新失败");
            return recordPrizeChange(status == 1 ? "PRIZE_ENABLE" : "PRIZE_DISABLE", locked, prizeId);
        });
        return updateLegacyStatus(prizeId, existing.getMonth(), status);
    }

    private PrizePool updateLegacyStatus(Long prizeId, String month, Integer status) {
        return legacyWrite(month, () -> {
            PrizePool before = lockPrize(prizeId);
            if (status.equals(before.getStatus())) return before;
            PrizePool update = new PrizePool().setId(prizeId).setStatus(status).setUpdateTime(LocalDateTime.now());
            if (prizePoolMapper.updateById(update) != 1) {
                throw BusinessException.conflict("奖品状态已被并发修改，请刷新后重试");
            }
            return recordPrizeChange(status == 1 ? "PRIZE_ENABLE" : "PRIZE_DISABLE", before, prizeId);
        });
    }

    private PrizePool lockPrize(Long prizeId) {
        // 当月 Redis 库存同步先独立完成，这里持锁读取管理修改前的库存和状态。
        PrizePool prize = prizePoolMapper.lockPrize(prizeId);
        if (prize == null) throw BusinessException.notFound("奖品不存在");
        return prize;
    }

    private <T> T legacyWrite(String month, Supplier<T> action) {
        return control.legacySource(() -> withCurrentMonthMaintenance(month, () -> transactions.execute(transaction -> {
            // 切换屏障存在时加入原事务，未启用屏障时仍保证奖品写入和审计一起提交。
            T result = action.get();
            refreshIfCurrent(month);
            return result;
        })));
    }

    // 当月奖池先获取维护锁并同步脏库存，再执行维护和缓存刷新，最后按 token 释放锁。
    private <T> T withCurrentMonthMaintenance(String month, Supplier<T> action) {
        if (!currentMonth().equals(month)) {
            return action.get();
        }
        String lockKey = StringUtils.format(PRIZE_POOL_ADMIN_LOCK_KEY, month);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, MAINTENANCE_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw BusinessException.conflict("当前奖池正在维护，请稍后重试");
        }
        try {
            prizePoolService.prizeStockToDB(PRIZE_POOL_CHANGE_SET_KEY, STOCK_SYNC_BATCH_SIZE);
            return action.get();
        } finally {
            // 新的切换屏障使旧维护加入数据库事务，维护锁需覆盖提交以及提交后的缓存刷新。
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
                    }
                });
            } else redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    private void refreshIfCurrent(String month) {
        if (currentMonth().equals(month)) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { prizePoolService.refreshPrizeCache(month); }
                });
            } else prizePoolService.refreshPrizeCache(month);
        }
    }

    private PrizePool toEntity(AdminPrizeSaveDTO dto, Long id) {
        PrizePool prize = new PrizePool();
        prize.setId(id);
        prize.setPrizeName(dto.getPrizeName().trim());
        prize.setPrizeImage(dto.getPrizeImage());
        prize.setPrizeType(dto.getPrizeType());
        prize.setMonth(dto.getMonth().replace("-", ""));
        prize.setWeight(dto.getWeight());
        prize.setTotalStock(dto.getTotalStock());
        prize.setRemainStock(dto.getRemainStock());
        prize.setStatus(dto.getStatus());
        if (id == null) {
            prize.setRevision(0L);
            prize.setCreateTime(LocalDateTime.now());
        }
        prize.setUpdateTime(LocalDateTime.now());
        return prize;
    }

    // 检查月份、权重和库存组合，totalStock=0 的无限库存规则需与抽奖 Lua 对齐。
    private void validate(AdminPrizeSaveDTO dto) {
        try {
            YearMonth.parse(dto.getMonth());
        } catch (RuntimeException exception) {
            throw BusinessException.badRequest("奖池月份格式必须为yyyy-MM");
        }
        if (dto.getTotalStock() == 0 && dto.getRemainStock() != 0) {
            throw BusinessException.badRequest("无限库存奖品的剩余库存必须为0");
        }
        if (dto.getTotalStock() > 0 && dto.getRemainStock() > dto.getTotalStock()) {
            throw BusinessException.badRequest("剩余库存不能大于总库存");
        }
    }

    private String currentMonth() {
        return LocalDate.now(clock).format(YM_FORMATTER);
    }

    /** ACTIVE 的锁序与抽奖一致。表单版本必须覆盖管理员看到的库存快照。 */
    private PrizePool activeSave(Long id, AdminPrizeSaveDTO dto) {
        return transactions.execute(transaction -> {
            control.requireActiveTransaction(); requireAdministrator();
            String month = dto.getMonth().replace("-", "");
            List<PrizePool> locked = prizePoolMapper.lockMonth(month);
            PrizePool value = toEntity(dto, id);
            PrizePool existing = null;
            if (id == null) {
                if (prizePoolMapper.insert(value) != 1) throw BusinessException.internalError("新增奖品失败");
            } else {
                existing = locked.stream().filter(prize -> prize.getId().equals(id)).findFirst()
                        .orElseThrow(() -> BusinessException.conflict("奖品不存在或月份已变化"));
                checkRevision(existing, dto.getRevision());
                value.setRevision(Math.addExact(existing.getRevision(), 1L));
                if (prizePoolMapper.updateById(value) != 1) throw BusinessException.conflict("奖品更新失败");
            }
            // 审计与业务写入共享当前事务，失败时保留原库存和版本。快照使用实际持久化值。
            return recordPrizeChange(id == null ? "PRIZE_CREATE" : "PRIZE_UPDATE", existing, value.getId());
        });
    }

    private PrizePool recordPrizeChange(String operation, PrizePool before, Long prizeId) {
        PrizePool persisted = prizePoolMapper.selectById(prizeId);
        auditService.appendPrizeChange(UserContext.getUserId(), operation, before, persisted);
        return persisted;
    }

    private void checkRevision(PrizePool existing, String expected) {
        if (expected == null || !expected.equals(existing.getRevision().toString()))
            throw BusinessException.conflict("奖品或库存已变化，请刷新后重新确认");
    }

    private void requireAdministrator() {
        Long id = UserContext.getUserId();
        if (id == null) throw BusinessException.unauthorized("用户未登录");
        User user = users.selectForUpdate(id); accounts.requireActive(user);
        if (!Integer.valueOf(2).equals(user.getRole())) throw BusinessException.forbidden("需要管理员权限");
    }
}
