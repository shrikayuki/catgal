/*
 * 阅读说明
 * Redis 键前缀与同步任务集合约定。
 * 业务计数键保存值，change 集合保存待落库的业务 ID，processing 表示已领取任务。
 */
package com.catgal.common.constants;

public class RedisConstant {
    /**
     * 点赞业务相关key
     */
    // 点赞 key:like:comment:100
    public static final String LIKE_USER_KEY = "like:{}:{}";
    // Exact Set cardinality, including an explicitly empty state after the final unlike.
    public static final String LIKE_EXPECTED_KEY = "like:expected:{}:{}";

    // 点赞业务ids key:like:bizIds:{bizType} ->set(bizId)
    public static final String LIKE_BIZ_IDS_KEY = "like:bizIds:{}";
    // 点赞明细与点赞计数分别消费，不能复用业务ID索引集合。
    public static final String LIKE_RECORD_CHANGE_SET_KEY = "like:record:change:{}";
    public static final String LIKE_COUNT_CHANGE_SET_KEY = "like:count:change:{}";

    //收藏业务key key: user:favorite:{folderId} -> game:user  1-N-1
    public static final String USER_FAVORITE_KEY = "user:favorite:{}";

    // 与收藏 Hash 同步维护，0 表示已确认清空，正数用于检测关系缓存丢失。
    public static final String USER_FAVORITE_EXPECTED_KEY = "user:favorite:expected:{}";

    // 变化的收藏夹ID集合（Set）
    public static final String FAVORITE_CHANGE_SET_KEY = "fav:folder:change";

    // 变化的游戏ID集合（统计数变化）
    public static final String GAME_STATS_COUNT_CHANGE_SET_KEY = "game:stats:count:change";
    public static final String GAME_STATS_SYNC_LOCK_KEY = "lock:game:stats:sync";
    // 变化的资源Id集合 （resource下载数变化）
    public static final String RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY = "resource:download:count:change";
    public static final String RESOURCE_DOWNLOAD_SYNC_LOCK_KEY = "lock:resource:download:sync";



    //游戏收藏数key: favorite:count:gameId
    public static final String FAVORITE_COUNT_KEY = "favorite:count:{}";
    //游戏浏览数key: look:count:gameId
    public static final String LOOK_COUNT_KEY = "look:count:{}";
    // 资源数 key
    public static final String RESOURCE_COUNT_KEY = "game:resource:{}";
    //游戏评论数
    public static final String COMMENT_COUNT_KEY = "comment:count:{}";
    //游戏评价数
    public static final String REVIEW_COUNT_KEY = "review:count:{}";
    //资源下载量
    public static final String RESOURCE_DOWNLOAD_COUNT_KEY =  "resource:download:count:{}";
    //游戏下载量
    public static final String GAME_DOWNLOAD_COUNT_KEY = "game:download:count:{}";
    //游戏评分
    public static final String GAME_RATING_KEY = "game:rating:{}";

    //随机推荐游戏id缓存 key
    public static final String GAME_IDS_KEY = "game:ids:all";

    //签到redis key
    public static final String SIGN_DATE_USER_KEY = "sign:{}:{}";

    //积分相关key

    //用户当前积分 hash结构
    public static final String CUTE_POINTS_KEY = "cute:points:{}";
    public static final String CUTE_POINTS_HASH_KEY = "cute:hash";
    public static final String TOTAL_EARNED_HASH_KEY = "total:earned:hash";
    public static final String TOTAL_SPENT_HASH_KEY = "total:spent:hash";

    //存用户id集合
    public static final String CUTE_POINTS_CHANGE_SET_KEY = "cute:points:change";

    // 萌萌点消息幂等标记，参数为消息业务号 bizId
    public static final String CUTE_POINTS_MESSAGE_PROCESSED_KEY = "cute:points:message:{}";

    //抽奖相关key
    public static final String PRIZE_DATE_CACHE_KEY = "prize:{}:cache";

    // 管理员维护当月奖池时阻止新的库存扣减
    public static final String PRIZE_POOL_ADMIN_LOCK_KEY = "lock:prize:admin:{}";

    public static final String PRIZE_POOL_CHANGE_SET_KEY = "prize:pool:change";

    //ai_girl 相关key
    public static final String AI_GIRL_CONVERSATION_HISTORY_KEY = "ai:conversation:history:{}:{}";

    //关注功能相关key
    //用户的粉丝
    public static final String USER_FANS_KEY = "user:fans:{}";
    //用户的关注
    public static final String USER_FOLLOWS_KEY = "user:follows:{}";
    //关注set变化key 用于做增量同步
    public static final String FOLLOW_CHANGE_SET_KEY = "follow:change";

}

