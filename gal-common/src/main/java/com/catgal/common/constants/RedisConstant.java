package com.catgal.common.constants;

public class RedisConstant {
    /**
     * 点赞业务相关key
     */
    // 点赞 key:like:comment:100
    public static final String LIKE_USER_KEY = "like:{}:{}";

    // 点赞业务ids key:like:bizIds:{bizType} ->set(bizId)
    public static final String LIKE_BIZ_IDS_KEY = "like:bizIds:{}";

    //收藏业务key key: user:favorite:{folderId} -> game:user  1-N-1
    public static final String USER_FAVORITE_KEY = "user:favorite:{}";

    // 变化的收藏夹ID集合（Set）
    public static final String FAVORITE_CHANGE_SET_KEY = "fav:folder:change";

    // 变化的游戏ID集合（收藏数变化）
    public static final String GAME_FAVORITE_COUNT_CHANGE_SET_KEY = "fav:game:count:change";
    public static final String GAME_LOOK_COUNT_CHANGE_SET_KEY = "look:game:count:change";
    public static final String GAME_COMMENT_COUNT_CHANGE_SET_KEY = "comment:game:count:change";
    public static final String GAME_REVIEW_COUNT_CHANGE_SET_KEY = "review:game:count:change";

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


}

