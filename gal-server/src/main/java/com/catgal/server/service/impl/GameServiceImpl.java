package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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


    @Override
    public PageDTO<GameVO> pageGames(GamePageQuery query) {



        Page<Game> page = lambdaQuery()
                .eq(Game::getStatus, 1)
                .like(StringUtils.isNotBlank(query.getName()), Game::getName, query.getName())
                .apply(query.getReleaseYear() != null, "YEAR(release_date) = {0}", query.getReleaseYear())
                // 直接传字符串
                .apply(StringUtils.isNotBlank(query.getResourceType()),
                        "FIND_IN_SET({0}, types)", query.getResourceType())
                .apply(StringUtils.isNotBlank(query.getLanguage()),
                        "FIND_IN_SET({0}, languages)", query.getLanguage())
                .apply(StringUtils.isNotBlank(query.getPlatform()),
                        "FIND_IN_SET({0}, platforms)", query.getPlatform())
                // 关联表筛选
                .apply(query.getTagId() != null,
                        "EXISTS (SELECT 1 FROM game_tag WHERE game_id = id AND tag_id = {0})", query.getTagId())
                .apply(query.getCompanyId() != null,
                        "EXISTS (SELECT 1 FROM game_company WHERE game_id = id AND company_id = {0})", query.getCompanyId())
                .page(query.toMpPage());
        List<Game> records = page.getRecords();
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }

        List<GameVO> vos = new ArrayList<>(records.size());
        for (Game g : records) {
            GameVO vo = getGameSimpleById(g.getId());
            vo.setIsFavorite(null);
            vos.add(vo);

        }

        return PageDTO.of(page, vos);
    }

    @Override
    public GameVO getGameById(Long id) {
        Long userId = UserContext.getUserId();
        //实现浏览人数缓存
        Game game = getById(id);
        if (game == null || !Integer.valueOf(1).equals(game.getStatus())) {
            throw BusinessException.notFound("游戏不存在或未发布");
        }
        GameVO vo = BeanUtils.copyBean(game, GameVO.class);
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
        //TODO 标签 会社待实现

        return vo;
    }
    @Override
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
        vo.setCoverUrl(game.getCoverUrl());
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
        return vo;
    }

    @Override
    public GameVO getRandomGame() {
        return getGameById(gameIdsCacheTask.getRandomGameId());
    }
}
