/*
 * 阅读说明
 * AdminCatalogService 负责管理端对应业务的查询与状态维护。
 * Controller 处理参数绑定，事务、校验、缓存更新和结果装配在本服务完成。
 */
package com.catgal.server.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.search.ResourceSearchService;
import com.catgal.server.catalog.GameCatalogService;
import com.catgal.server.storage.FileAccessPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private final GameMapper gameMapper;
    private final UserMapper userMapper;
    private final ResourceSearchService resourceSearchService;
    private final GameCatalogService gameCatalogService;

    public PageDTO<Game> pageGames(AdminPageQuery query) {
        LambdaQueryWrapper<Game> wrapper = new LambdaQueryWrapper<Game>()
                .eq(query.getStatus() != null, Game::getStatus, query.getStatus())
                .eq(query.getUserId() != null, Game::getPublisherId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), Game::getName, query.getKeyword());
        return PageDTO.of(gameMapper.selectPage(query.toSafePage(), wrapper), game -> {
            Game view = BeanUtils.copyProperties(game, Game.class);
            view.setCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
            return view;
        });
    }

    // 游戏状态维护委托资料服务，统一使用游戏行锁与发布候选缓存更新。
    public Game updateGameStatus(Long gameId, Integer status) {
        return gameCatalogService.setStatus(gameId, status);
    }

    public PageDTO<AdminResourceVO> pageResources(AdminPageQuery query) {
        Page<Resource> page = resourceSearchService.search(query);
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
            vo.setAvatarUrl(user == null ? null : FileAccessPaths.avatar(user));
            return vo;
        }).toList();
        return PageDTO.of(page, list);
    }

    private <T> Map<Long, T> toMap(List<T> values, Function<T, Long> idGetter) {
        if (CollUtils.isEmpty(values)) {
            return new HashMap<>();
        }
        return values.stream().collect(Collectors.toMap(idGetter, Function.identity(), (left, right) -> left));
    }
}
