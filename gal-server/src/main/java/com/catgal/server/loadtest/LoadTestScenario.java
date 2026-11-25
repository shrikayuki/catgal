/*
 * 阅读说明
 * 固定只读场景目录，将场景标识映射为允许请求的接口。
 */
package com.catgal.server.loadtest;

import com.catgal.common.exception.BusinessException;
import java.util.Arrays;

public enum LoadTestScenario {
    GAMES("games", "游戏列表", "/game/page", false, false),
    SEARCH("search", "游戏关键词检索", "/game/page", false, true),
    RESOURCES("resources", "游戏资源列表", "/resource/page", true, false),
    COMMENTS("comments", "游戏评论列表", "/comment/page", true, false),
    REVIEWS("reviews", "游戏评价列表", "/review/page", true, false);

    public final String id;
    public final String label;
    public final String path;
    public final boolean requiresGame;
    public final boolean requiresKeyword;

    LoadTestScenario(String id, String label, String path, boolean requiresGame, boolean requiresKeyword) {
        this.id = id;
        this.label = label;
        this.path = path;
        this.requiresGame = requiresGame;
        this.requiresKeyword = requiresKeyword;
    }

    public static LoadTestScenario find(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst()
                .orElseThrow(() -> BusinessException.badRequest("请选择有效测试场景"));
    }
}
