package com.catgal.server.domain.query.admin;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

/** 管理员跨资源检索原有审核历史，时间以带偏移的 ISO 8601 接收。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminResourceReviewLogQuery extends AdminPageQuery {
    @Positive private Long adminUserId;
    @Positive private Long targetId;
    @Pattern(regexp = "APPROVE|REJECT|REQUEUE") private String operation;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private OffsetDateTime from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private OffsetDateTime to;
}
