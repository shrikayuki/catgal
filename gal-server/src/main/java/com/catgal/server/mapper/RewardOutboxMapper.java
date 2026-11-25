package com.catgal.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.catgal.server.domain.po.RewardOutbox;
import org.apache.ibatis.annotations.Select;

public interface RewardOutboxMapper extends BaseMapper<RewardOutbox> {
    /** 一次领取一个任务，避免一批发送耗时超过末尾任务的租约。已发送但未消费的事件也会重投。 */
    @Select("SELECT * FROM reward_outbox WHERE state IN ('PENDING','IN_FLIGHT','SENT','FAILED') "
            + "AND projected_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6) "
            + "AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    RewardOutbox claimOne();
}
