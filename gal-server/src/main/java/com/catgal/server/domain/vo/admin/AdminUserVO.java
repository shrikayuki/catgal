/*
 * 阅读说明
 * AdminUserVO的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.server.domain.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private String phone;
    private String signature;
    private String avatarUrl;
    private Integer role;
    /** 当前时间下的有效状态，定时封禁到期后为 1。 */
    private Integer status;
    /** 数据库原始状态，配合 banExpired 区分已到期的历史封禁。 */
    private Integer storedStatus;
    /** 最近的封禁原因，仅通过管理员用户视图提供。 */
    private String banReason;
    /** 对外返回带北京时间偏移的期限，原始封禁状态下为空表示永久。 */
    private java.time.OffsetDateTime bannedUntil;
    private Boolean banExpired;
    /** 由状态快照和最新用户审计 ID 生成，修改角色或封禁状态时回传。 */
    private String stateVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
