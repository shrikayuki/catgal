package com.catgal.server.service.admin;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

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
@Slf4j
public class AdminPrizeService {

    private static final int STOCK_SYNC_BATCH_SIZE = 500;
    private static final Duration MAINTENANCE_LOCK_TTL = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            load("lua/common/release-lock.lua", Long.class);

    private final PrizePoolMapper prizePoolMapper;
    private final IPrizePoolService prizePoolService;
    private final StringRedisTemplate redisTemplate;

    public PageDTO<PrizePool> pagePrizes(AdminPageQuery query) {
        LambdaQueryWrapper<PrizePool> wrapper = new LambdaQueryWrapper<PrizePool>()
                .eq(StringUtils.isNotBlank(query.getMonth()), PrizePool::getMonth, query.getMonth())
                .eq(query.getStatus() != null, PrizePool::getStatus, query.getStatus())
                .like(StringUtils.isNotBlank(query.getKeyword()), PrizePool::getPrizeName, query.getKeyword());
        Page<PrizePool> page = prizePoolMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page);
    }

    public PrizePool createPrize(AdminPrizeSaveDTO dto) {
        validate(dto);
        PrizePool prize = toEntity(dto, null);
        return withCurrentMonthMaintenance(dto.getMonth(), () -> {
            if (prizePoolMapper.insert(prize) != 1) {
                throw BusinessException.internalError("新增奖品失败");
            }
            refreshIfCurrent(dto.getMonth());
            log.info("管理员新增奖品, adminId={}, prizeId={}, month={}",
                    UserContext.getUserId(), prize.getId(), dto.getMonth());
            return prize;
        });
    }

    public PrizePool updatePrize(Long prizeId, AdminPrizeSaveDTO dto) {
        validate(dto);
        PrizePool existing = prizePoolMapper.selectById(prizeId);
        if (existing == null) {
            throw BusinessException.notFound("奖品不存在");
        }
        if (!existing.getMonth().equals(dto.getMonth())) {
            throw BusinessException.badRequest("不能修改奖品所属月份，请新建奖品");
        }
        PrizePool update = toEntity(dto, prizeId);
        return withCurrentMonthMaintenance(existing.getMonth(), () -> {
            if (prizePoolMapper.updateById(update) != 1) {
                throw BusinessException.conflict("奖品已被并发修改，请刷新后重试");
            }
            refreshIfCurrent(existing.getMonth());
            log.info("管理员修改奖品, adminId={}, prizeId={}, month={}",
                    UserContext.getUserId(), prizeId, existing.getMonth());
            return prizePoolMapper.selectById(prizeId);
        });
    }

    public PrizePool updateStatus(Long prizeId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.badRequest("奖品状态只能是0停用或1启用");
        }
        PrizePool existing = prizePoolMapper.selectById(prizeId);
        if (existing == null) {
            throw BusinessException.notFound("奖品不存在");
        }
        if (status.equals(existing.getStatus())) {
            return withCurrentMonthMaintenance(existing.getMonth(), () -> {
                refreshIfCurrent(existing.getMonth());
                return existing;
            });
        }
        Integer oldStatus = existing.getStatus();
        return withCurrentMonthMaintenance(existing.getMonth(), () -> {
            PrizePool update = new PrizePool();
            update.setId(prizeId);
            update.setStatus(status);
            update.setUpdateTime(LocalDateTime.now());
            if (prizePoolMapper.updateById(update) != 1) {
                throw BusinessException.conflict("奖品状态已被并发修改，请刷新后重试");
            }
            refreshIfCurrent(existing.getMonth());
            existing.setStatus(status);
            log.info("管理员修改奖品状态, adminId={}, prizeId={}, oldStatus={}, newStatus={}",
                    UserContext.getUserId(), prizeId, oldStatus, status);
            return existing;
        });
    }

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
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    private void refreshIfCurrent(String month) {
        if (currentMonth().equals(month)) {
            prizePoolService.refreshPrizeCache(month);
        }
    }

    private PrizePool toEntity(AdminPrizeSaveDTO dto, Long id) {
        PrizePool prize = new PrizePool();
        prize.setId(id);
        prize.setPrizeName(dto.getPrizeName().trim());
        prize.setPrizeImage(dto.getPrizeImage());
        prize.setPrizeType(dto.getPrizeType());
        prize.setMonth(dto.getMonth());
        prize.setWeight(dto.getWeight());
        prize.setTotalStock(dto.getTotalStock());
        prize.setRemainStock(dto.getRemainStock());
        prize.setStatus(dto.getStatus());
        if (id == null) {
            prize.setCreateTime(LocalDateTime.now());
        }
        prize.setUpdateTime(LocalDateTime.now());
        return prize;
    }

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
        return LocalDate.now().format(YM_FORMATTER);
    }
}
