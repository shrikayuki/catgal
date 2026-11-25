package com.catgal.server.domain.query.admin;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 内容审计按评论或评价筛选，目标编号来自各自的业务表。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminContentOperationLogQuery extends AdminOperationLogFilter {
    @Pattern(regexp = "comment|review") private String targetType;
    @Positive private Long targetId;
    @Pattern(regexp = "COMMENT_HIDE|COMMENT_RESTORE|REVIEW_HIDE|REVIEW_RESTORE") private String operation;
}
