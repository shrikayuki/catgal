/*
 * 阅读说明
 * 管理员维护用户角色与封禁状态，复用现有 user 和 admin_operation_log 表。
 * 统一管理员集合、操作者、目标的锁序，状态变更与成功审计一起提交。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.dto.admin.AdminUserStatusUpdateDTO;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminUserVO;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IAdminUserService;
import com.catgal.server.service.IUserAccountStatusService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.catgal.common.constants.UserRoleConstant.ADMIN;
import static com.catgal.server.service.IUserAccountStatusService.TIME_ZONE;

/** 用户管理统一锁管理员集合、操作者、目标，再更新状态和同事务审计。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserServiceImpl extends ServiceImpl<UserMapper, User> implements IAdminUserService {
    private final UserMapper userMapper;
    private final IUserAccountStatusService accountStatus;
    private final IAdminOperationLogService auditService;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    // 用户行与审计版本在同一只读事务中查询，REPEATABLE READ 隔离级别下共用一致性快照。
    @Override
    @Transactional(readOnly = true)
    public PageDTO<AdminUserVO> pageUsers(AdminPageQuery query) {
        if (query.getStatus() != null && query.getStatus() != 0 && query.getStatus() != 1) {
            throw BusinessException.badRequest("账号状态只能为正常或封禁");
        }
        var wrapper = new LambdaQueryWrapper<User>()
                .select(User::getId, User::getUsername, User::getPhone, User::getSignature, User::getAvatarUrl,
                        User::getAvatarFileId, User::getRole, User::getStatus, User::getBanReason,
                        User::getBannedUntil, User::getCreateTime, User::getUpdateTime)
                .eq(query.getRole() != null, User::getRole, query.getRole())
                .and(StringUtils.isNotBlank(query.getKeyword()), condition -> condition.like(User::getUsername, query.getKeyword())
                        .or().like(User::getPhone, query.getKeyword()));
        LocalDateTime now = accountStatus.now();
        // 筛选与有效状态共用到期语义，不等待定时任务修改 status，也不会把到期用户藏在封禁筛选里。
        if (Objects.equals(query.getStatus(), 1)) {
            wrapper.and(condition -> condition.eq(User::getStatus, 1).or(expired -> expired.eq(User::getStatus, 0)
                    .le(User::getBannedUntil, now)));
        } else if (Objects.equals(query.getStatus(), 0)) {
            wrapper.eq(User::getStatus, 0).and(condition -> condition.isNull(User::getBannedUntil).or().gt(User::getBannedUntil, now));
        }
        var page = userMapper.selectPage(query.<User>toSafePage(), wrapper);
        // 整页只聚合查询一次审计版本，避免每个用户额外访问一次数据库。
        Map<Long, Long> revisions = auditService.userRevisions(page.getRecords().stream().map(User::getId).toList());
        return PageDTO.of(page, user -> toView(user, revisions.getOrDefault(user.getId(), 0L)));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserVO getUser(Long userId) { return toView(requireUser(userId, false)); }

    /** 先完成持锁授权与版本核对，再写角色和审计，锁一直持有到事务结束。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateRole(Long userId, Integer role, String expectedVersion) {
        if (role == null || role < 0 || role > 2) throw BusinessException.badRequest("角色只能为0、1或2");
        List<Long> adminIds = lockAdministrators();
        User actor = requireAdministrator();
        User target = Objects.equals(actor.getId(), userId) ? actor : requireUser(userId, true);
        checkVersion(target, expectedVersion);
        // 先验证版本再判断无变化，过期表单仍需管理员重新核对当前账号状态。
        if (Objects.equals(target.getRole(), role)) return toView(target);
        if (Objects.equals(actor.getId(), userId) && role != ADMIN) throw BusinessException.conflict("不能取消自己的管理员角色");
        protectLastAdministrator(target, role != ADMIN, adminIds);
        Map<String, Object> before = snapshot(target);
        LocalDateTime updatedAt = accountStatus.now().truncatedTo(ChronoUnit.SECONDS);
        int changed = userMapper.update(null, Wrappers.<User>lambdaUpdate().eq(User::getId, userId)
                .set(User::getRole, role).set(User::getUpdateTime, updatedAt));
        if (changed != 1) throw BusinessException.conflict("用户角色已发生变化，请刷新后重试");
        target.setRole(role); target.setUpdateTime(updatedAt);
        // 审计服务要求加入本事务，插入失败会连同前面的用户更新一起回滚。
        auditService.appendUserChange(actor.getId(), "USER_ROLE_CHANGE", userId, before, snapshot(target), null);
        log.info("管理员修改用户角色, adminId={}, targetUserId={}, newRole={}", actor.getId(), userId, role);
        return toView(target);
    }

    /**
     * status=0 发起封禁，status=1 解封，期限为空的封禁长期生效。
     * 状态版本由调用方读取后回传，用来发现表单打开期间发生的角色或封禁变化。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateStatus(Long userId, AdminUserStatusUpdateDTO request) {
        validateStatusRequest(request);
        List<Long> adminIds = lockAdministrators();
        User actor = requireAdministrator();
        User target = Objects.equals(actor.getId(), userId) ? actor : requireUser(userId, true);
        checkVersion(target, request.getStateVersion());
        if (request.getStatus() == 0 && Objects.equals(actor.getId(), userId)) throw BusinessException.conflict("不能封禁自己的管理员账号");
        protectLastAdministrator(target, request.getStatus() == 0, adminIds);
        // 请求时间先换算到北京时间再截断到数据库秒精度，未来时间校验也使用该保存值。
        LocalDateTime until = request.getBannedUntil() == null ? null
                : request.getBannedUntil().atZoneSameInstant(TIME_ZONE).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
        if (until != null && !until.isAfter(accountStatus.now())) throw BusinessException.badRequest("封禁截止时间必须晚于当前时间");
        String banReason = request.getStatus() == 0 ? request.getReason().trim() : null;
        // 当前版本对应的状态、原因和期限都相同时直接返回，避免重复写入成功审计。
        if (Objects.equals(target.getStatus(), request.getStatus()) && Objects.equals(target.getBanReason(), banReason)
                && Objects.equals(target.getBannedUntil(), until)) return toView(target);
        Map<String, Object> before = snapshot(target);
        LocalDateTime updatedAt = accountStatus.now().truncatedTo(ChronoUnit.SECONDS);
        // 明确 set(null) 清除解封后的原因和期限，避免 updateById 的忽略空字段策略保留旧封禁信息。
        int changed = userMapper.update(null, Wrappers.<User>lambdaUpdate().eq(User::getId, userId)
                .set(User::getStatus, request.getStatus()).set(User::getBanReason, banReason)
                .set(User::getBannedUntil, until).set(User::getUpdateTime, updatedAt));
        if (changed != 1) throw BusinessException.conflict("用户状态已发生变化，请刷新后重试");
        target.setStatus(request.getStatus()); target.setBanReason(banReason); target.setBannedUntil(until); target.setUpdateTime(updatedAt);
        auditService.appendUserChange(actor.getId(), request.getStatus() == 0 ? "USER_BAN" : "USER_UNBAN",
                userId, before, snapshot(target), request.getReason().trim());
        log.info("管理员修改用户状态, adminId={}, targetUserId={}, status={}", actor.getId(), userId, request.getStatus());
        return toView(target);
    }

    /** Mapper 按 ID 升序锁管理员集合，此后依次锁操作者和目标，角色与状态写入共用顺序。 */
    private List<Long> lockAdministrators() {
        currentUser.requireUserId();
        // 无论目标原角色是什么，角色和封禁写入始终先锁同一管理员集合，防止两名管理员互相降级形成空集合。
        return userMapper.selectAdminIdsForUpdate();
    }

    private User requireAdministrator() {
        User actor = userMapper.selectForUpdate(currentUser.requireUserId());
        // HTTP 鉴权与业务提交之间可能有人修改操作者，这里持锁后再次核验。
        accountStatus.requireActive(actor);
        if (!Objects.equals(actor.getRole(), ADMIN)) throw BusinessException.forbidden("当前账号已失去管理员权限");
        return actor;
    }

    private User requireUser(Long id, boolean lock) {
        if (id == null || id <= 0) throw BusinessException.badRequest("用户ID须为正数");
        User user = lock ? userMapper.selectForUpdate(id) : userMapper.selectById(id);
        if (user == null) throw BusinessException.notFound("用户不存在");
        return user;
    }

    // 已到期封禁按有效状态计入正常管理员，永久或未到期封禁账号排除在数量之外。
    private void protectLastAdministrator(User target, boolean removing, List<Long> ids) {
        if (removing && Objects.equals(target.getRole(), ADMIN) && accountStatus.effectiveStatus(target) == 1) {
            long active = userMapper.selectBatchIds(ids).stream().filter(user -> accountStatus.effectiveStatus(user) == 1).count();
            if (active <= 1) throw BusinessException.conflict("系统必须至少保留一个正常管理员");
        }
    }

    // 必须在目标用户行锁内调用，此时前一个管理操作已提交，审计版本可用于冲突判断。
    private void checkVersion(User user, String expected) {
        if (expected != null && !Objects.equals(expected, stateVersion(user))) {
            throw BusinessException.conflict("用户状态已被修改，请刷新核对后重试");
        }
    }

    // Service 也校验调用前提，便于未来通过非 HTTP 入口复用时保留相同状态约束。
    private void validateStatusRequest(AdminUserStatusUpdateDTO request) {
        if (request == null || request.getStatus() == null || (request.getStatus() != 0 && request.getStatus() != 1)
                || request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 255
                || request.getStateVersion() == null || !request.getStateVersion().matches("[0-9a-f]{64}")) {
            throw BusinessException.badRequest("请填写有效状态、操作原因和状态版本");
        }
        if (request.getStatus() == 1 && request.getBannedUntil() != null) throw BusinessException.badRequest("解封操作不能设置封禁期限");
    }

    private AdminUserVO toView(User user) {
        return toView(user, auditService.userRevisions(List.of(user.getId())).getOrDefault(user.getId(), 0L));
    }

    // 对外同时投影有效状态与原始状态，后台可识别“期限已到但原始字段保留”的账号。
    private AdminUserVO toView(User user, Long revision) {
        AdminUserVO view = new AdminUserVO();
        view.setId(user.getId()); view.setUsername(user.getUsername()); view.setPhone(user.getPhone());
        view.setSignature(user.getSignature()); view.setAvatarUrl(com.catgal.server.storage.FileAccessPaths.avatar(user));
        view.setRole(user.getRole()); view.setStatus(accountStatus.effectiveStatus(user)); view.setStoredStatus(user.getStatus());
        view.setBanReason(user.getBanReason()); view.setBanExpired(accountStatus.isBanExpired(user));
        view.setBannedUntil(user.getBannedUntil() == null ? null : user.getBannedUntil().atZone(TIME_ZONE).toOffsetDateTime());
        view.setStateVersion(stateVersion(user, revision)); view.setCreateTime(user.getCreateTime()); view.setUpdateTime(user.getUpdateTime());
        return view;
    }

    // 固定字段及序列化顺序同时用于脱敏审计与状态版本，扩展时同步核对审计字段白名单。
    private Map<String, Object> snapshot(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId()); data.put("username", user.getUsername()); data.put("role", user.getRole());
        data.put("storedStatus", user.getStatus()); data.put("status", accountStatus.effectiveStatus(user));
        data.put("banReason", user.getBanReason());
        data.put("bannedUntil", user.getBannedUntil() == null ? null : user.getBannedUntil().atZone(TIME_ZONE).toOffsetDateTime().toString());
        return data;
    }

    private String stateVersion(User user) {
        return stateVersion(user, auditService.userRevisions(List.of(user.getId())).getOrDefault(user.getId(), 0L));
    }

    // 此哈希承担表单并发校验。后续归档审计时需要保留最新版本锚点或迁移独立递增版本。
    private String stateVersion(User user, Long revision) {
        // 最新已提交审计编号使 A→B→A 仍获得新版本，秒精度时间和单纯状态哈希都无法保证这一点。
        // 用户状态和审计同事务写入，持有用户行锁时读取的版本包含前一个已提交管理操作。
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                objectMapper.writeValueAsBytes(List.of(snapshot(user), revision)))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("用户状态版本生成失败", failure); }
    }
}
