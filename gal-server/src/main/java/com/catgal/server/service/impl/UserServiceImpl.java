package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.*;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.*;
import com.catgal.common.utils.*;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.UserRoleConstant.USER;
import static com.catgal.common.domain.query.UserHomeQueryDTO.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final JwtUtils jwtUtils;
    private final ICommentService commentService;
    private final IGameReviewService reviewService;
    private final IFavoriteFolderService favoriteFolderService;
    private final IResourceService resourceService;
    private final GameMapper gameMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterDTO dto) {
        // 1. 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        Long count = baseMapper.selectCount(wrapper);
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
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
            throw new RuntimeException("用户不存在");
        }

        // 2. 验证密码
        if (!PasswordUtils.matches(dto.getPassword(), user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 3. 生成 Token
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());

        // 4. 返回结果
        log.info("用户登录成功: {}", dto.getUsername());
        return new LoginVO(user.getId(), user.getUsername(), user.getRole(), token);
    }

    @Override
    public UserHomeVO getUserHomeById(UserHomeQueryDTO dto) {
        boolean isMyself = dto.getUserId() == null;

        Long userId = isMyself ? UserContext.getUserId() : dto.getUserId();
        User user = getById(userId);
        UserHomeVO vo = BeanUtils.copyProperties(user, UserHomeVO.class);
        vo.setUserId(userId);

        // TODO 关注 萌萌点功能待实现 默认0 false 评论数量 收藏数量 评价数量 发布资源数量
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
        vo.setCutePoints(0);
        vo.setFollowerCount(0);
        vo.setFollowingCount(0);
        if (!isMyself) {
            vo.setIsFollowing(false);
        }

        if (isMyself && Objects.equals(user.getRole(), USER)) {
            vo.setShowApplyCreator(true);
        }

        Integer tab = dto.getTab();
        if (tab == null) {
            return vo;
        }

        PageDTO<?> page = getPageByType(dto, userId);
        vo.setPageData(page);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long id, UserUpdateDTO dto) {

        if (id == null) {
            throw new RuntimeException("未登录");
        }

        // 2. 查询用户是否存在
        User user = getById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 3. 更新对象
        User updateUser = new User();
        updateUser.setId(id);

        // 4. 修改用户名（需检查唯一性）
        if (StringUtils.isNotBlank(dto.getUsername())) {
            // 检查用户名是否已被其他用户使用
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, dto.getUsername())
                    .ne(User::getId, id);
            Long count = baseMapper.selectCount(wrapper);
            if (count > 0) {
                throw new RuntimeException("用户名已存在");
            }
            updateUser.setUsername(dto.getUsername());
        }

        // 5. 修改签名
        if (StringUtils.isNotBlank(dto.getSignature())) {
            updateUser.setSignature(dto.getSignature());
        }

        // 6. 修改头像
        if (StringUtils.isNotBlank(dto.getAvatarUrl())) {
            updateUser.setAvatarUrl(dto.getAvatarUrl());
        }

        // 7. 修改密码（需要验证原密码）
        if (StringUtils.isNotBlank(dto.getNewPassword())) {
            // 验证原密码
            if (StringUtils.isBlank(dto.getOldPassword())) {
                throw new RuntimeException("请输入原密码");
            }
            if (!PasswordUtils.matches(dto.getOldPassword(), user.getPassword())) {
                throw new RuntimeException("原密码错误");
            }
            // 新密码加密
            updateUser.setPassword(PasswordUtils.encode(dto.getNewPassword()));
        }

        // 8. 执行更新
        boolean success = updateById(updateUser);
        if (!success) {
            throw new RuntimeException("修改失败");
        }

        log.info("用户信息修改成功: userId={}", id);
    }

    private PageDTO<?> getPageByType(UserHomeQueryDTO dto, Long userId) {
        Integer tab = dto.getTab();
        if (Objects.equals(tab, COMMENT)) {
            return getCommentPage(dto, userId);
        } else if (Objects.equals(tab, REVIEW)) {
            return getReviewPage(dto, userId);
        } else if (Objects.equals(tab, FAVORITE)) {
            return getFavoritePage(dto, userId);
        } else {
            return getResourcePage(dto, userId);
        }
    }

    /**
     * 评论分页
     */
    private PageDTO<UserCommentVO> getCommentPage(UserHomeQueryDTO dto, Long userId) {
        Page<Comment> page = commentService.lambdaQuery()
                .eq(Comment::getUserId, userId)
                .page(dto.toMpPageDefaultSortByCreateTimeDesc());

        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 批量查询游戏
        Map<Long, String> gameNameMap = getGameNameMap(records.stream()
                .map(Comment::getGameId).collect(Collectors.toSet()));

        List<UserCommentVO> vos = records.stream()
                .map(comment -> {
                    UserCommentVO vo = BeanUtils.copyProperties(comment, UserCommentVO.class);
                    vo.setGameName(gameNameMap.getOrDefault(comment.getGameId(), ""));
                    return vo;
                })
                .collect(Collectors.toList());

        return PageDTO.of(page, vos);
    }

    /**
     * 评价分页
     */
    private PageDTO<UserReviewVO> getReviewPage(UserHomeQueryDTO dto, Long userId) {
        Page<GameReview> page = reviewService.lambdaQuery()
                .eq(GameReview::getUserId, userId)
                .page(dto.toMpPageDefaultSortByCreateTimeDesc());

        List<GameReview> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 批量查询游戏
        Map<Long, String> gameNameMap = getGameNameMap(records.stream()
                .map(GameReview::getGameId).collect(Collectors.toSet()));

        List<UserReviewVO> vos = records.stream()
                .map(r -> {
                    UserReviewVO vo = BeanUtils.copyProperties(r, UserReviewVO.class);
                    vo.setGameName(gameNameMap.getOrDefault(r.getGameId(), ""));
                    return vo;
                })
                .collect(Collectors.toList());

        return PageDTO.of(page, vos);
    }

    /**
     * 收藏夹分页
     */
    private PageDTO<UserFavoriteFolderVO> getFavoritePage(UserHomeQueryDTO dto, Long userId) {
        Page<FavoriteFolder> page = favoriteFolderService.lambdaQuery()
                .eq(FavoriteFolder::getUserId, userId)
                .page(dto.toMpPageDefaultSortByCreateTimeDesc());

        List<FavoriteFolder> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        List<UserFavoriteFolderVO> vos = records.stream()
                .map(f -> BeanUtils.copyProperties(f, UserFavoriteFolderVO.class))
                .collect(Collectors.toList());

        return PageDTO.of(page, vos);
    }

    /**
     * 资源分页
     */
    private PageDTO<UserResourceVO> getResourcePage(UserHomeQueryDTO dto, Long userId) {
        Page<Resource> page = resourceService.lambdaQuery()
                .eq(Resource::getUserId, userId)
                .page(dto.toMpPageDefaultSortByCreateTimeDesc());

        List<Resource> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 批量查询游戏
        Set<Long> gameIds = records.stream().map(Resource::getGameId).collect(Collectors.toSet());
        Map<Long, Game> gameMap = getGameMap(gameIds);

        List<UserResourceVO> vos = records.stream()
                .map(r -> {
                    UserResourceVO vo = new UserResourceVO();
                    BeanUtils.copyProperties(r, vo);
                    vo.setTypes(r.getTypeList());
                    vo.setLanguages(r.getLanguageList());
                    vo.setPlatforms(r.getPlatformList());

                    Game game = gameMap.get(r.getGameId());
                    if (game != null) {
                        vo.setGameName(game.getName());
                        vo.setCoverUrl(game.getCoverUrl());
                    }
                    return vo;
                })
                .collect(Collectors.toList());

        return PageDTO.of(page, vos);
    }

// ========== 辅助方法 ==========

    private Map<Long, String> getGameNameMap(Set<Long> gameIds) {
        if (CollUtils.isEmpty(gameIds)) {
            return new HashMap<>();
        }
        List<Game> games = gameMapper.selectBatchIds(gameIds);
        return games.stream().collect(Collectors.toMap(Game::getId, Game::getName, (v1, v2) -> v1));
    }

    private Map<Long, Game> getGameMap(Set<Long> gameIds) {
        if (CollUtils.isEmpty(gameIds)) {
            return new HashMap<>();
        }
        List<Game> games = gameMapper.selectBatchIds(gameIds);
        return games.stream().collect(Collectors.toMap(Game::getId, Function.identity(), (v1, v2) -> v1));
    }
}