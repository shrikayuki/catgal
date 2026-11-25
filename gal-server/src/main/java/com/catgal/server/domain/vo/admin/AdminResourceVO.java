/*
 * 阅读说明
 * AdminResourceVO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminResourceVO {
    private Long id;
    private Long gameId;
    private String gameName;
    private Long userId;
    private String username;
    private String avatarUrl;
    private String name;
    private String downloadUrl;
    private String extractCode;
    private String password;
    private String remark;
    private String fileSize;
    private String types;
    private String languages;
    private String platforms;
    private Integer downloadCount;
    private Integer likeCount;
    private Integer status;
    private Integer isOfficial;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long revision;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
