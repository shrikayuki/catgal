/*
 * 阅读说明
 * AdminContentVO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminContentVO {
    private String type;
    private Long id;
    private Long gameId;
    private String gameName;
    private Long userId;
    private String username;
    private String avatarUrl;
    private String content;
    private Integer score;
    private Integer spoilerLevel;
    private Integer likeCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
