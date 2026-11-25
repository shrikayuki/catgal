package com.catgal.server.mapper;

import com.catgal.server.domain.po.RewardRuntime;
import org.apache.ibatis.annotations.Select;

/** 所有锁查询必须由调用方开启事务，不能把这里的行锁替换为 Redis 锁。 */
public interface RewardRuntimeMapper {
    @Select("SELECT * FROM reward_runtime WHERE id=1")
    RewardRuntime current();
    @Select("SELECT * FROM reward_runtime WHERE id=1 FOR SHARE")
    RewardRuntime lockShared();
    @Select("SELECT * FROM reward_runtime WHERE id=1 FOR UPDATE")
    RewardRuntime lockExclusive();
}
