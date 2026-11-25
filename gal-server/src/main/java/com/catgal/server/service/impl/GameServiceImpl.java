/*
 * 阅读说明
 * 游戏列表、详情和随机作品的公共查询编排。
 * 列表检索委托 GameSearchService，详情关联、统计和封面路径在业务层组装。
 */
package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.IGameService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.RedisCounterService;
import com.catgal.server.task.GameIdsCacheTask;
import com.catgal.server.search.GameListAssembler;
import com.catgal.server.search.GameSearchService;
import com.catgal.server.catalog.GameRelationsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import static com.catgal.common.constants.RedisConstant.*;

/**
 * <p>
 * 游戏主表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameServiceImpl extends ServiceImpl<GameMapper, Game> implements IGameService {

    private final IFavoriteItemService favoriteItemService;
    private final IGameStatsService statsService;
    private final RedisCounterService redisCounterService;
    private final GameIdsCacheTask gameIdsCacheTask;
    private final GameSearchService gameSearchService;
    private final GameListAssembler gameListAssembler;
    private final GameRelationsService gameRelationsService;


    @Override
    // 检索服务先决定 MySQL 或 ES，再批量补充游戏展示数据，避免控制器承担查询分支。
    public PageDTO<GameVO> pageGames(GamePageQuery query) {
        GameSearchService.GamePage page = gameSearchService.search(query);
        long pages = (page.total() + query.getPageSize() - 1) / query.getPageSize();
        return new PageDTO<>(page.total(), pages, gameListAssembler.assemble(page.games()));
    }

    @Override
    // 验证游戏可见性后组装详情，浏览计数通过原子计数与标脏异步同步。
    public GameVO getGameById(Long id) {
        Long userId = UserContext.getUserId();
        //实现浏览人数缓存
        Game game = getById(id);
        if (game == null || !Integer.valueOf(1).equals(game.getStatus())) {
            throw BusinessException.notFound("游戏不存在或未发布");
        }
        GameVO vo = BeanUtils.copyBean(game, GameVO.class);
        vo.setCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
        if (BeanUtils.isEmpty(vo)){
            throw BusinessException.notFound("游戏不存在");
        }
        FavoriteItem one = favoriteItemService.lambdaQuery().eq(FavoriteItem::getGameId, id).eq(FavoriteItem::getUserId, userId).one();
        boolean isMyFavorite = one != null;
        vo.setIsFavorite(isMyFavorite);
        vo.setResourceTypes(game.getTypeList());
        vo.setLanguages(game.getLanguageList());
        vo.setPlatforms(game.getPlatformList());
        //统计数 未命中 查库
        vo.setReviewCount(statsService.getGameReviewCount(game.getId()));
        vo.setCommentCount(statsService.getGameCommentCount(id));
        vo.setResourceCount(statsService.getGameResourceCount(id));
        //不查库的
        Integer gameDownLoadCount = statsService.getGameDownLoadCount(id);
        if (gameDownLoadCount != -1){
            vo.setDownloadCount(gameDownLoadCount);
        }

        Integer gameFavoriteCount = statsService.getGameFavoriteCount(id);
        if (gameFavoriteCount != -1) {
            vo.setFavoriteCount(gameFavoriteCount);
        }

        Integer gameLookCount = statsService.getGameLookCount(id);
        if (gameLookCount != -1) {
            vo.setViewCount(gameLookCount);
        }

        vo.setRating(statsService.getRating(id));
        String lookKey = StringUtils.format(LOOK_COUNT_KEY, id);
        redisCounterService.changeAndMark(
                lookKey,
                game.getViewCount() == null ? 0L : game.getViewCount(),
                1L,
                GAME_STATS_COUNT_CHANGE_SET_KEY,
                id
        );
        gameRelationsService.attach(java.util.List.of(vo));

        return vo;
    }
    @Override
    // 为评论、评价和资源列表提供基础游戏信息，避免重复构造完整详情。
    public GameVO getGameSimpleById(Long id) {
        Long userId = UserContext.getUserId();
        FavoriteItem one = favoriteItemService.lambdaQuery().eq(FavoriteItem::getGameId, id).eq(FavoriteItem::getUserId, userId).one();
        Game game = getById(id);
        if (game == null || !Integer.valueOf(1).equals(game.getStatus())) {
            throw BusinessException.notFound("游戏不存在或未发布");
        }
        GameVO vo = new GameVO();
        vo.setId(game.getId());
        vo.setName(game.getName());
        vo.setCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
        //统计数
        vo.setCommentCount(statsService.getGameCommentCount(id));

        Integer gameDownLoadCount = statsService.getGameDownLoadCount(id);
        if (gameDownLoadCount != -1){
            vo.setDownloadCount(gameDownLoadCount);
        }
        vo.setResourceCount(null);
        Integer gameFavoriteCount = statsService.getGameFavoriteCount(id);
        if (gameFavoriteCount != -1) {
            vo.setFavoriteCount(gameFavoriteCount);
        }
        vo.setViewCount(statsService.getGameLookCount(id));
        vo.setRating(statsService.getRating(id));

        vo.setResourceTypes(game.getTypeList());
        boolean isMyFavorite = one != null;
        vo.setIsFavorite(isMyFavorite);
        gameRelationsService.attach(java.util.List.of(vo));
        return vo;
    }

    @Override
    // 从已发布候选集合抽取后仍核对当前游戏，缓存是派生数据，状态以数据库为准。
    public GameVO getRandomGame() {
        return getGameById(gameIdsCacheTask.getRandomGameId());
    }
}
