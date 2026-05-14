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
}