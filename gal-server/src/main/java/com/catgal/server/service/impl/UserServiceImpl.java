package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.domain.dto.*;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.*;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.*;
import com.catgal.server.service.userhome.UserHomeTabQueryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    private final AliOssUtil aliOssUtil;
    private final JwtUtils jwtUtils;

    private final IFollowRecordService followService;
    private final CurrentUserProvider currentUserProvider;
    private final UserHomeTabQueryRegistry userHomeTabQueryRegistry;



    @Override
    @Transactional(rollbackFor = Exception.class)
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
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        // 3. 保存
        baseMapper.insert(user);
        log.info("用户注册成功: {}", dto.getUsername());
    }

    @Override
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

        // 3. 生成 Token
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());

        // 4. 返回结果
        log.info("用户登录成功: {}", dto.getUsername());
        return new LoginVO(user.getId(), user.getUsername(), user.getRole(), token);
    }

    @Override
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
        vo.setUserId(userId);


        // 评论数量
        Long commentCount = commentService.lambdaQuery()
                .eq(Comment::getUserId, userId)
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
    public void updateProfile(Long id, UserUpdateDTO dto) {
        Long currentUserId = currentUserProvider.requireSelf(id);

        // 2. 查询用户是否存在
        User user = getById(currentUserId);
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
        if (StringUtils.isNotBlank(dto.getNewPassword())) {
            // 验证原密码
            if (StringUtils.isBlank(dto.getOldPassword())) {
                throw BusinessException.badRequest("请输入原密码");
            }
            if (!PasswordUtils.matches(dto.getOldPassword(), user.getPassword())) {
                throw BusinessException.badRequest("原密码错误");
            }
            // 新密码加密
            updateUser.setPassword(PasswordUtils.encode(dto.getNewPassword()));
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
        Long userId = currentUserProvider.requireUserId();
        return userCutePointsService.getUserCutePointsCache(userId);

    }

    @Override
    public String updateAvatar(MultipartFile file) {
        Long userId = currentUserProvider.requireUserId();

        try {
            // 1. 校验文件
            if (file.isEmpty()) {
                throw BusinessException.badRequest("文件不能为空");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isEmpty()) {
                throw BusinessException.badRequest("文件名不能为空");
            }
            log.info(originalFilename);
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
                throw BusinessException.badRequest("文件扩展名无效");
            }
            String ext = originalFilename.substring(dotIndex);

            // 2. 校验文件类型
            List<String> allowedTypes = Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".webp");
            if (!allowedTypes.contains(ext.toLowerCase())) {
                throw BusinessException.badRequest("只支持 JPG、PNG、GIF、WEBP 格式");
            }
            Set<String> allowedContentTypes = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
            if (file.getContentType() == null || !allowedContentTypes.contains(file.getContentType().toLowerCase())) {
                throw BusinessException.badRequest("文件 MIME 类型不受支持");
            }

            // 3. 校验文件大小（2MB）
            if (file.getSize() > 2 * 1024 * 1024) {
                throw BusinessException.badRequest("图片大小不能超过 2MB");
            }

            // 4. 上传到 OSS
            String objectName = "avatars/" +
                    userId +
                    "_" +
                    System.currentTimeMillis() +
                    ext;
            String url = aliOssUtil.upload(file.getBytes(), objectName);

            // 5. 更新数据库
            LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(User::getId, userId)
                    .set(User::getAvatarUrl, url);
            update(wrapper);

            log.info("头像上传成功: userId={}, url={}", userId, url);
            return url;

        } catch (IOException e) {
            log.error("上传头像失败", e);
            throw BusinessException.internalError("上传失败", e);
        }
    }


}
