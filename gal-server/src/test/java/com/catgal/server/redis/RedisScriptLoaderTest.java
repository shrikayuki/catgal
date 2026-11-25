package com.catgal.server.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RedisScriptLoaderTest {

    @Test
    void allLuaScriptsArePackagedAndReadableFromClasspath() {
        List<String> longResultScripts = List.of(
                "lua/common/release-lock.lua",
                "lua/counter/change-and-mark.lua",
                "lua/cute-points/change-once.lua",
                "lua/dirty-set/requeue-all.lua",
                "lua/dirty-set/requeue.lua",
                "lua/dirty-set/ack.lua",
                "lua/follow/add.lua",
                "lua/follow/remove.lua",
                "lua/game-stats/ack-if-unchanged.lua",
                "lua/like/add.lua",
                "lua/like/remove.lua",
                "lua/prize/decrement-stock.lua",
                "lua/favorite/change.lua",
                "lua/favorite/count.lua",
                "lua/favorite/hydrate.lua",
                "lua/favorite/prepare-delete.lua",
                "lua/favorite/restore-delete.lua",
                "lua/favorite/finish-delete.lua"
        );

        for (String location : longResultScripts) {
            DefaultRedisScript<Long> script = RedisScriptLoader.load(location, Long.class);
            assertFalse(script.getScriptAsString().isBlank(), location);
        }

        DefaultRedisScript<List> claimScript =
                RedisScriptLoader.load("lua/dirty-set/claim.lua", List.class);
        assertFalse(claimScript.getScriptAsString().isBlank(), "lua/dirty-set/claim.lua");
        assertFalse(RedisScriptLoader.load("lua/favorite/snapshot.lua", List.class).getScriptAsString().isBlank());
    }
}
