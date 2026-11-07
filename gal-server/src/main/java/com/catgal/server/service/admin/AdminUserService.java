package com.catgal.server.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminUserVO;
import com.catgal.server.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.catgal.common.constants.UserRoleConstant.ADMIN;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final UserMapper userMapper;

    public PageDTO<AdminUserVO> pageUsers(AdminPageQuery query) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .select(User::getId, User::getUsername, User::getPhone, User::getSignature,
                        User::getAvatarUrl, User::getRole, User::getCreateTime, User::getUpdateTime)
                .eq(query.getRole() != null, User::getRole, query.getRole())
                .and(StringUtils.isNotBlank(query.getKeyword()), condition -> condition
                        .like(User::getUsername, query.getKeyword())
                        .or()
                        .like(User::getPhone, query.getKeyword()));
        Page<User> page = userMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page, AdminUserVO.class);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateRole(Long userId, Integer role) {
        if (userId == null || role == null || role < 0 || role > 2) {
            throw BusinessException.badRequest("用户ID或角色不合法");
        }
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw BusinessException.notFound("用户不存在");
        }
        Integer oldRole = target.getRole();
        if (Objects.equals(oldRole, role)) {
            return BeanUtils.copyProperties(target, AdminUserVO.class);
        }
        if (Objects.equals(UserContext.getUserId(), userId) && !Objects.equals(role, ADMIN)) {
            throw BusinessException.conflict("不能取消自己的管理员角色");
        }
        if (Objects.equals(oldRole, ADMIN) && !Objects.equals(role, ADMIN)) {
            List<Long> adminIds = userMapper.selectAdminIdsForUpdate();
            if (adminIds.size() <= 1) {
                throw BusinessException.conflict("系统必须至少保留一个管理员");
            }
        }

        User update = new User();
        update.setId(userId);
        update.setRole(role);
        if (userMapper.updateById(update) != 1) {
            throw BusinessException.conflict("用户角色已被并发修改，请刷新后重试");
        }
        target.setRole(role);
        log.info("管理员修改用户角色, adminId={}, targetUserId={}, oldRole={}, newRole={}",
                UserContext.getUserId(), userId, oldRole, role);
        return BeanUtils.copyProperties(target, AdminUserVO.class);
    }
}
