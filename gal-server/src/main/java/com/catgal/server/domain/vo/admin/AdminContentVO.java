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
    private String content;
    private Integer score;
    private Integer spoilerLevel;
    private Integer likeCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
