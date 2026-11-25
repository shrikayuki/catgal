/*
 * 阅读说明
 * AdminOperationLogQuery 封装查询筛选条件，默认值与校验注解共同确定请求边界。
 * 分页继承关系和转换方法决定使用标准页码或业务游标，具体 SQL 由调用服务构造。
 */
package com.catgal.server.domain.query.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminOperationLogQuery extends AdminOperationLogFilter {
    @Min(1) private Long targetUserId;
    @Pattern(regexp = "USER_BAN|USER_UNBAN|USER_ROLE_CHANGE") private String operation;
}
