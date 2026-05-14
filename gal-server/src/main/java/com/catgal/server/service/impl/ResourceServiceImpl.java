package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.*;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.IResourceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_RESOURCE;
import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_REVIEW;
import static com.catgal.common.constants.RedisConstant.RESOURCE_DOWNLOAD_COUNT_KEY;
import static com.catgal.common.constants.ResourceConstant.OFFICIAL_RESOURCE;
import static com.catgal.common.constants.UserRoleConstant.*;

/**
 * <p>
 * 游戏资源表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements IResourceService {

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;
    private final IGameStatsService statsService;
    private final RedisTemplate<Object, Object> redisTemplate;

    @Override
    public GameConnectVO<GameResourceVO> pageGameResource(GameQuery query) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw new RuntimeException("游戏Id不能为空");
        }
        GameVO gameInfo = gameService.getGameSimpleById(gameId);
        // 先转换基本对象
        GameConnectVO<GameResourceVO> vo = new GameConnectVO<>();
        BeanUtils.copyProperties(gameInfo, vo);

        Page<Resource> page = lambdaQuery().eq(Resource::getGameId, gameId)
                .eq(Resource::getStatus, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Resource> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return vo;
        }
        List<GameResourceVO> vos = getVOS(records);

        vo.setPage(PageDTO.of(page, vos));
        return vo;
    }

    @Override
    public ResourceUrlVO getResourceUrl(Long id) {
        if (id == null) {
            throw new RuntimeException("参数不能为空");
        }
        Resource resource = getById(id);
        if (resource == null) {
            log.error("资源不存在");
            throw new RuntimeException("404");
        }
        return BeanUtils.copyProperties(resource, ResourceUrlVO.class);
    }

    @Override
    @Transactional
    public void addResource(ResourceAddDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }
        User user = userMapper.selectById(userId);
        if (user.getRole().equals(USER)){
            throw new RuntimeException("请申请为创作者才能发布作品");
        }
        Resource resource = BeanUtils.copyProperties(dto, Resource.class);
        resource.setUserId(userId);
        if (user.getRole().equals(ADMIN)){
            resource.setIsOfficial(OFFICIAL_RESOURCE);
        }
        save(resource);


    }

    @Override
    @Transactional
    public void deleteResource(Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }
        User user = userMapper.selectById(userId);
        Resource resource = getById(id);
        boolean canDelete = user.getRole().equals(ADMIN) || (resource.getUserId().equals(userId) && user.getRole().equals(CREATOR));
        if (!canDelete) {
            throw new RuntimeException("您无权删除该资源");
        }
        removeById(resource);

    }

    @Override
    public String download(Long id) {
        Resource resource = getById(id);
        if (resource == null) {
            throw new RuntimeException("404");
        }
        redisTemplate.opsForValue().increment(StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, id));
        return resource.getDownloadUrl();
    }

    private List<GameResourceVO> getVOS(List<Resource> records) {
        Set<Long> userIds = records.stream().map(Resource::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = null;
        if (CollUtils.isNotEmpty(userIds)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        }
        List<GameResourceVO> vos = new ArrayList<>(records.size());
        Long userId = UserContext.getUserId();
        Map<Long, Boolean> likedMap = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();
        if (userId != null && CollUtils.isNotEmpty(records)) {
            List<Long> ResourceIds = records.stream().map(Resource::getId).collect(Collectors.toList());
            likedMap = likeService.batchIsLiked(userId, LIKE_TYPE_RESOURCE, ResourceIds);
            likeCounts = likeService.getLikeCounts(ResourceIds, LIKE_TYPE_RESOURCE);
        }
        for (Resource r : records) {
            GameResourceVO vo = BeanUtils.copyBean(r, GameResourceVO.class);

            // 设置用户信息
            User user = userMap == null ? null : userMap.get(r.getUserId());
            if (user != null) {
                vo.setUsername(user.getUsername());
                vo.setAvatarUrl(user.getAvatarUrl());
            }

            // 设置是否为当前用户评论
            if (vo != null) {
                vo.setIsMyResource(r.getUserId().equals(userId));
                // 设置点赞状态
                vo.setIsLike(likedMap.getOrDefault(r.getId(),false));
                // ✅ 简化：都设置，没有的取 0
                vo.setLikeCount(likeCounts.getOrDefault(r.getId(), 0L).intValue());

            }

            vos.add(vo);

        }
        return vos;
    }
}
