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
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
