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
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
