/*
 * 阅读说明
 * 游戏统计的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 接口收敛业务能力，具体持久化、缓存或外部访问由实现类承担。
 */
package com.catgal.server.service;

import java.math.BigDecimal;

public interface IGameStatsService {
    /** 读取游戏公开评价数量。 */
    Integer getGameReviewCount(Long id);

    /** 读取游戏浏览计数。 */
    Integer getGameLookCount(Long gameId);
    /** 读取游戏收藏关系计数。 */
    Integer getGameFavoriteCount(Long gameId);
    /** 读取游戏有效资源数量。 */
    Integer getGameResourceCount(Long gameId);
    /** 读取指定资源下载计数。 */
    Integer getResourceDownLoadCount(Long resourceId);


    /** 读取游戏汇总下载计数。 */
    Integer getGameDownLoadCount(Long id);

    /** 读取游戏公开评论数量。 */
    Integer getGameCommentCount(Long id);

    /** 读取游戏当前评分，聚合更新由评价写入事务承担。 */
    BigDecimal getRating(Long id);

    /** 对指定游戏脏集合执行有界统计同步并保留期间的新变更。 */
    void checkGameStats(String key, Integer batchSize);

    /** 将指定资源下载量脏集合的快照同步到数据库。 */
    void checkResourceDownLoad(String resourceCountKey, Integer batchSize);
}
