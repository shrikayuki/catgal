package com.catgal.server.domain.query.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

/** 各模块共享的审计筛选条件，时间参数包含客户端时区偏移。 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AdminOperationLogFilter extends AdminPageQuery {
    @Min(1) private Long adminUserId;
    @Size(max = 64) private String requestId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private OffsetDateTime from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private OffsetDateTime to;
}
