/*
 * 阅读说明
 * 认证后放入 UserContext 的最小身份对象，供 Service 读取当前用户与角色。
 */
package com.catgal.common.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    private Long userId;      // 用户ID
    private String username;  // 用户名
    private Integer role;     // 用户角色：0-普通，1-创作者，2-管理员
    private Long authVersion; // 本次请求认证时的账号版本，改密持锁后复核。
}
