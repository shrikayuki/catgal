package com.catgal.server.service;

import java.math.BigDecimal;

public interface IGameStatsService {
    Integer getGameReviewCount(Long id);

    Integer getGameLookCount(Long gameId);
    Integer getGameFavoriteCount(Long gameId);
    Integer getGameResourceCount(Long gameId);
    Integer getResourceDownLoadCount(Long resourceId);


    Integer getGameDownLoadCount(Long id);

    Integer getGameCommentCount(Long id);

    BigDecimal getRating(Long id);

    void checkGameStats(String key, Integer batchSize);

    void checkResourceDownLoad(String resourceCountKey, Integer batchSize);
}
