package com.catgal.server.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.RedisCounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.GAME_IDS_KEY;
import static com.catgal.common.constants.RedisConstant.GAME_STATS_COUNT_CHANGE_SET_KEY;
import static com.catgal.common.constants.RedisConstant.RESOURCE_COUNT_KEY;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCatalogService {

    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;
    private final UserMapper userMapper;
    private final IGameStatsService gameStatsService;
    private final RedisCounterService redisCounterService;
    private final StringRedisTemplate redisTemplate;

    public PageDTO<Game> pageGames(AdminPageQuery query) {
        LambdaQueryWrapper<Game> wrapper = new LambdaQueryWrapper<Game>()
                .eq(query.getStatus() != null, Game::getStatus, query.getStatus())
                .eq(query.getUserId() != null, Game::getPublisherId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), Game::getName, query.getKeyword());
        return PageDTO.of(gameMapper.selectPage(query.toSafePage(), wrapper));
    }

    public Game updateGameStatus(Long gameId, Integer status) {
        requireStatus(status, 0, 2, "游戏状态只能是0草稿、1发布或2下架");
        Game game = gameMapper.selectById(gameId);
        if (game == null) {
            throw BusinessException.notFound("游戏不存在");
        }
        Integer oldStatus = game.getStatus();
        if (oldStatus != null && oldStatus.equals(status)) {
            updatePublishedGameCache(gameId, status);
            return game;
        }
        int updated = gameMapper.update(null, Wrappers.<Game>lambdaUpdate()
                .eq(Game::getId, gameId)
                .eq(oldStatus != null, Game::getStatus, oldStatus)
                .isNull(oldStatus == null, Game::getStatus)
                .set(Game::getStatus, status));
        if (updated != 1) {
            throw BusinessException.conflict("游戏状态已被并发修改，请刷新后重试");
        }
        updatePublishedGameCache(gameId, status);
        game.setStatus(status);
        log.info("管理员修改游戏状态, adminId={}, gameId={}, oldStatus={}, newStatus={}",
                UserContext.getUserId(), gameId, oldStatus, status);
        return game;
    }

    public PageDTO<AdminResourceVO> pageResources(AdminPageQuery query) {
        LambdaQueryWrapper<Resource> wrapper = new LambdaQueryWrapper<Resource>()
                .eq(query.getStatus() != null, Resource::getStatus, query.getStatus())
                .eq(query.getGameId() != null, Resource::getGameId, query.getGameId())
                .eq(query.getUserId() != null, Resource::getUserId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), Resource::getName, query.getKeyword());
        Page<Resource> page = resourceMapper.selectPage(query.toSafePage(), wrapper);
        if (CollUtils.isEmpty(page.getRecords())) {
            return PageDTO.empty(page);
        }

        Set<Long> gameIds = page.getRecords().stream().map(Resource::getGameId).collect(Collectors.toSet());
        Set<Long> userIds = page.getRecords().stream().map(Resource::getUserId).collect(Collectors.toSet());
        Map<Long, Game> games = toMap(gameMapper.selectBatchIds(gameIds), Game::getId);
        Map<Long, User> users = toMap(userMapper.selectBatchIds(userIds), User::getId);
        List<AdminResourceVO> list = page.getRecords().stream().map(resource -> {
            AdminResourceVO vo = BeanUtils.copyProperties(resource, AdminResourceVO.class);
            Game game = games.get(resource.getGameId());
            User user = users.get(resource.getUserId());
            vo.setGameName(game == null ? null : game.getName());
            vo.setUsername(user == null ? null : user.getUsername());
            return vo;
        }).toList();
        return PageDTO.of(page, list);
    }

    public AdminResourceVO updateResourceStatus(Long resourceId, Integer status) {
        requireStatus(status, 0, 2, "资源状态只能是0失效、1有效或2待审核");
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw BusinessException.notFound("资源不存在");
        }
        Integer oldStatus = resource.getStatus();
        if (oldStatus != null && oldStatus.equals(status)) {
            return BeanUtils.copyProperties(resource, AdminResourceVO.class);
        }
        int initialCount = gameStatsService.getGameResourceCount(resource.getGameId());
        int updated = resourceMapper.update(null, Wrappers.<Resource>lambdaUpdate()
                .eq(Resource::getId, resourceId)
                .eq(oldStatus != null, Resource::getStatus, oldStatus)
                .isNull(oldStatus == null, Resource::getStatus)
                .set(Resource::getStatus, status));
        if (updated != 1) {
            throw BusinessException.conflict("资源状态已被并发修改，请刷新后重试");
        }
        int delta = (status == 1 ? 1 : 0) - (oldStatus != null && oldStatus == 1 ? 1 : 0);
        if (delta != 0) {
            redisCounterService.changeAndMark(StringUtils.format(RESOURCE_COUNT_KEY, resource.getGameId()),
                    initialCount, delta, GAME_STATS_COUNT_CHANGE_SET_KEY, resource.getGameId());
        }
        resource.setStatus(status);
        log.info("管理员审核资源, adminId={}, resourceId={}, oldStatus={}, newStatus={}",
                UserContext.getUserId(), resourceId, oldStatus, status);
        return BeanUtils.copyProperties(resource, AdminResourceVO.class);
    }

    private void requireStatus(Integer status, int min, int max, String message) {
        if (status == null || status < min || status > max) {
            throw BusinessException.badRequest(message);
        }
    }

    private void updatePublishedGameCache(Long gameId, Integer status) {
        if (status == 1) {
            redisTemplate.opsForSet().add(GAME_IDS_KEY, gameId.toString());
        } else {
            redisTemplate.opsForSet().remove(GAME_IDS_KEY, gameId.toString());
        }
    }

    private <T> Map<Long, T> toMap(List<T> values, Function<T, Long> idGetter) {
        if (CollUtils.isEmpty(values)) {
            return new HashMap<>();
        }
        return values.stream().collect(Collectors.toMap(idGetter, Function.identity(), (left, right) -> left));
    }
}
