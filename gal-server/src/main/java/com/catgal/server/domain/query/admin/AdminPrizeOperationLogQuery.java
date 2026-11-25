package com.catgal.server.domain.query.admin;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 奖池审计按奖品和维护操作筛选，共用管理员、追踪号与时间筛选。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminPrizeOperationLogQuery extends AdminOperationLogFilter {
    @Positive private Long targetId;
    @Pattern(regexp = "PRIZE_CREATE|PRIZE_UPDATE|PRIZE_ENABLE|PRIZE_DISABLE") private String operation;
}
