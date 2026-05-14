package com.catgal.server.service;

import java.math.BigDecimal;

public interface IGameStatsService {
    Integer getGameLookCount(Long gameId);
    Integer getGameFavoriteCount(Long gameId);
    Integer getGameResourceCount(Long gameId);
    Integer getResourceDownLoadCount(Long resourceId);

    void checkFavoriteTimes(String key, Integer batchSize);

    Integer getGameDownLoadCount(Long id);

    Integer getGameCommentCount(Long id);

    BigDecimal getRating(Long id);
}
