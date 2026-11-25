/*
 * 阅读说明
 * 从 classpath 加载 Lua 资源并声明返回类型，业务类持有脚本引用执行原子操作。
 */
package com.catgal.server.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 从 classpath 加载 Redis Lua 脚本，避免在 Java 业务代码中维护脚本文本。
 */
public final class RedisScriptLoader {

    private RedisScriptLoader() {
    }

    public static <T> DefaultRedisScript<T> load(String classpathLocation, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(resultType);
        return script;
    }
}
