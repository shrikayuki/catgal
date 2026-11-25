/*
 * 阅读说明
 * 注册、登录、本人资料和用户主页的业务入口。
 * 密码通过 BCrypt 处理，登录检查有效账号状态，主页 tab 由查询策略分发。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.*;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.*;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.*;
import com.catgal.server.service.userhome.UserHomeTabQueryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

import static com.catgal.common.constants.UserRoleConstant.USER;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final ICommentService commentService;
    private final IGameReviewService reviewService;
    private final IFavoriteFolderService favoriteFolderService;
    private final IResourceService resourceService;
    private final IUserCutePointsService userCutePointsService;

    private final IStorageFileService storageFileService;
    private final JwtUtils jwtUtils;
    private final IUserAccountStatusService accountStatusService;

    private final IFollowRecordService followService;
    private final CurrentUserProvider currentUserProvider;
    private final UserHomeTabQueryRegistry userHomeTabQueryRegistry;



    @Override
    @Transactional(rollbackFor = Exception.class)
    // 注册先校验身份字段与重复账号，再编码密码，显式赋予初始普通角色和正常状态。
    public void register(RegisterDTO dto) {
        // 1. 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        Long count = baseMapper.selectCount(wrapper);
        if (count > 0) {
            throw BusinessException.conflict("用户名已存在");
        }

        // 2. 创建新用户
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(PasswordUtils.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setSignature(dto.getSignature());
        user.setRole(0);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        // 3. 保存
        baseMapper.insert(user);
        log.info("用户注册成功: {}", dto.getUsername());
    }

    @Override
    // 先校验账号密码，再检查当前封禁状态，成功后签发 JWT 并组装登录响应。
    public LoginVO login(LoginDTO dto) {
        // 1. 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User user = baseMapper.selectOne(wrapper);

        if (user == null) {
            throw BusinessException.unauthorized("用户不存在");
        }

        // 2. 验证密码
        if (!PasswordUtils.matches(dto.getPassword(), user.getPassword())) {
            throw BusinessException.unauthorized("密码错误");
        }

        // 密码验证后检查封禁，避免在验证密码前泄露账号状态。
        accountStatusService.requireActive(user);

        // 3. 生成 Token
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion());

        // 4. 返回结果
        log.info("用户登录成功: {}", dto.getUsername());
        return new LoginVO(user.getId(), user.getUsername(), user.getRole(), token,
                com.catgal.server.storage.FileAccessPaths.avatar(user));
    }

    @Override
    // 主页公共信息与 tab 分页分开组装，查询策略负责对应业务的公开范围。
    public UserHomeVO getUserHomeById(UserHomeQueryDTO dto) {
        boolean selfRequested = dto.getUserId() == null;
        Long currentUserId = currentUserProvider.getOptionalUserId();
        if (selfRequested && currentUserId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }

        Long userId = selfRequested ? currentUserId : dto.getUserId();
        boolean ownProfile = currentUserId != null && Objects.equals(currentUserId, userId);
        User user = getById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        UserHomeVO vo = BeanUtils.copyProperties(user, UserHomeVO.class);
        vo.setAvatarUrl(com.catgal.server.storage.FileAccessPaths.avatar(user));
        vo.setUserId(userId);


        // 访客计数与评论标签页共用公开口径，本人保留自己的全部历史。
        Long commentCount = commentService.lambdaQuery()
                .eq(Comment::getUserId, userId)
                .apply(!ownProfile, CommentMapper.VISIBLE_ON_PUBLIC_PROFILE)
                .count();
        vo.setCommentCount(commentCount.intValue());

// 评价数量
        Long reviewCount = reviewService.lambdaQuery()
                .eq(GameReview::getUserId, userId)
                .count();
        vo.setReviewCount(reviewCount.intValue());

// 收藏夹数量
        Long folderCount = favoriteFolderService.lambdaQuery()
                .eq(FavoriteFolder::getUserId, userId)
                .count();
        vo.setFolderCount(folderCount.intValue());

// 发布资源数量
        Long resourceCount = resourceService.lambdaQuery()
                .eq(Resource::getUserId, userId)
                .count();
        vo.setResourceCount(resourceCount.intValue());

        vo.setFollowerCount(followService.userFansCount(userId));
        vo.setFollowingCount(followService.userFollowCount(userId));
        if (!ownProfile && currentUserId != null) {
            vo.setIsFollowing(followService.isMyFollow(currentUserId, userId));
        }

        if (ownProfile && Objects.equals(user.getRole(), USER)) {
            vo.setShowApplyCreator(true);
        }

        Integer userCutePointsCache = userCutePointsService.getUserCutePointsCache(userId);
        if (userCutePointsCache != null) {
            vo.setCutePoints(userCutePointsCache);
        }

        Integer tab = dto.getTab();
        if (tab == null) {
            return vo;
        }

        PageDTO<?> page = userHomeTabQueryRegistry.query(dto, userId, ownProfile);
        vo.setPageData(page);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 当前用户身份由认证上下文读取，先验证本人权限再保存允许修改的资料字段。
    public void updateProfile(Long id, UserUpdateDTO dto) {
        Long currentUserId = currentUserProvider.requireSelf(id);

        boolean changingPassword = StringUtils.isNotBlank(dto.getNewPassword());
        // 改密码时先锁用户再验旧密码和认证版本，拒绝已经进入请求队列的旧凭据。
        User user = changingPassword ? baseMapper.selectForUpdate(currentUserId) : getById(currentUserId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }

        // 3. 更新对象
        User updateUser = new User();
        updateUser.setId(currentUserId);

        // 4. 修改用户名（需检查唯一性）
        if (StringUtils.isNotBlank(dto.getUsername())) {
            // 检查用户名是否已被其他用户使用
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, dto.getUsername())
                    .ne(User::getId, currentUserId);
            Long count = baseMapper.selectCount(wrapper);
            if (count > 0) {
                throw BusinessException.conflict("用户名已存在");
            }
            updateUser.setUsername(dto.getUsername());
        }

        // 5. 修改签名
        if (StringUtils.isNotBlank(dto.getSignature())) {
            updateUser.setSignature(dto.getSignature());
        }

        // 7. 修改密码（需要验证原密码）
        if (changingPassword) {
            accountStatusService.requireActive(user);
            if (!Objects.equals(UserContext.getUser().getAuthVersion(), user.getAuthVersion())) {
                throw BusinessException.unauthorized("登录凭证已失效，请重新登录");
            }
            // 验证原密码
            if (StringUtils.isBlank(dto.getOldPassword())) {
                throw BusinessException.badRequest("请输入原密码");
            }
            if (!PasswordUtils.matches(dto.getOldPassword(), user.getPassword())) {
                throw BusinessException.badRequest("原密码错误");
            }
            // 新密码加密
            updateUser.setPassword(PasswordUtils.encode(dto.getNewPassword()));
            // 版本和口令散列由同一次 UPDATE 写入，失败时共同回滚。
            updateUser.setAuthVersion(Math.incrementExact(user.getAuthVersion()));
        }

        // 8. 执行更新
        boolean success = updateById(updateUser);
        if (!success) {
            throw BusinessException.internalError("修改失败");
        }

        log.info("用户信息修改成功: userId={}", currentUserId);
    }

    @Override
    public Integer getMyPoints() {
        return getMyPoints(false);
    }

    @Override
    public Integer getMyPoints(boolean fresh) {
        Long userId = currentUserProvider.requireUserId();
        return fresh ? userCutePointsService.getUserCutePointsCache(userId, true)
                : userCutePointsService.getUserCutePointsCache(userId);

    }

    @Override
    // 头像上传委托托管文件服务，文件元数据和用户引用由专用存储事务维护。
    public String updateAvatar(MultipartFile file) {
        return storageFileService.updateAvatar(file);
    }
}
