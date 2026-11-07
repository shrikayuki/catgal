package com.catgal.server.domain.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "单个资源下载计数一致性")
public class ResourceDownloadConsistencyVO {
    private Long resourceId;
    private Integer databaseCount;
    private Integer redisCount;
    private List<String> issues;
    private boolean consistent;
}
