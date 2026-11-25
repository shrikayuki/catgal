package com.catgal.server.domain;

/** 能修改 Redis 或触发统计落库、需要留存管理员记录的操作。 */
public enum AdminRedisOperation {
    GAME_STATS_REPAIR("game"),
    RESOURCE_DOWNLOAD_REPAIR("resource"),
    COUNTERS_SYNC("global");

    private final String targetType;

    AdminRedisOperation(String targetType) {
        this.targetType = targetType;
    }

    public String targetType() {
        return targetType;
    }

    /** 描述这次请求的执行结果。计数是否一致仍由运维接口提供。 */
    public enum Outcome { COMPLETED, FAILED, UNCONFIRMED }
}
