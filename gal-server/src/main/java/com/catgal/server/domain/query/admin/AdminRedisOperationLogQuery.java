package com.catgal.server.domain.query.admin;

import com.catgal.server.domain.AdminRedisOperation;
import com.catgal.server.domain.AdminRedisOperation.Outcome;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Redis 运维记录共用时间、管理员及追踪号筛选。全局同步没有目标编号。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminRedisOperationLogQuery extends AdminOperationLogFilter {
    private AdminRedisOperation operation;
    private Outcome outcome;
    private Long targetId;
}
