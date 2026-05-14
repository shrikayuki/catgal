package com.catgal.common.domain.vo;

import com.catgal.common.domain.dto.PageDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户主页响应")
public class UserHomeVO {

    // ========== 用户基本信息 ==========
    @Schema(description = "用户ID", example = "187970")
    private Long userId;

    @Schema(description = "用户名", example = "爱哭的蓝")
    private String username;

    @Schema(description = "头像URL")
    private String avatarUrl;

    @Schema(description = "个人签名", example = "不错")
    private String signature;

    @Schema(description = "用户角色：0-普通，1-创作者，2-管理员", example = "0")
    private Integer role;


    // ========== 统计信息 ==========
    @Schema(description = "关注数", example = "0")
    private Integer followingCount;

    @Schema(description = "粉丝数", example = "0")
    private Integer followerCount;

    @Schema(description = "评论数", example = "0")
    private Integer commentCount;

    @Schema(description = "评价数", example = "0")
    private Integer reviewCount;

    @Schema(description = "收藏数", example = "0")
    private Integer folderCount;

    @Schema(description = "发布资源数", example = "0")
    private Integer resourceCount;

    @Schema(description = "加入时间", example = "2024-01-01 12:00:00")
    private LocalDateTime createTime;

    @Schema(description = "萌萌点", example = "0")
    private Integer cutePoints;

    @Schema(description = "当前用户是否已关注TA")
    private Boolean isFollowing;

    @Schema(description = "是否显示申请创作者按钮")
    private Boolean showApplyCreator;

    // ========== Tab 内容 ==========
    @Schema(description = "分页数据")
    private PageDTO<?> pageData;
}