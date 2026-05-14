package com.catgal.common.domain.vo;

import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
@Schema(description = "游戏信息响应")
public class GameVO {

    // ========== 游戏基本信息（来自 game 表） ==========
    @Schema(description = "游戏ID", example = "1")
    private Long id;

    @Schema(description = "游戏名称", example = "Summer Pockets")
    private String name;

    @Schema(description = "封面URL", example = "https://example.com/cover.jpg")
    private String coverUrl;

    @Schema(description = "游戏介绍", example = "夏日回忆的故事...")
    private String introduction;

    @Schema(description = "发售时间", example = "2018-06-29")
    private LocalDate releaseDate;

    @Schema(description = "评分", example = "9.5")
    private BigDecimal rating;

    @Schema(description = "下载人数", example = "6789")
    private Integer downloadCount;

    @Schema(description = "收藏人数", example = "3456")
    private Integer favoriteCount;

    @Schema(description = "资源数量", example = "5")
    private Integer resourceCount;

    @Schema(description = "评论数", example = "128")
    private Integer commentCount;

    @Schema(description = "评价数", example = "45")
    private Integer reviewCount;

    @Schema(description = "浏览数", example = "12345")
    private Integer viewCount;

    @Schema(description = "游戏入库时间", example = "2024-01-01 12:00:00")
    private LocalDateTime createTime;

    // ========== 关联数据（需要额外查询） ==========
    @Schema(description = "是否收藏", example = "false")
    private Boolean isFavorite;

    @Schema(description = "游戏标签集合", example = "[\"治愈\", \"催泪\"]")
    private List<String> tags;

    @Schema(description = "所属会社集合", example = "[\"Key社\", \"Visual Arts\"]")
    private List<String> companies;

    // ========== 从资源表聚合的数据 ==========
    @Schema(description = "平台Set", example = "[\"win\", \"mac\"]")
    private List<PlatformEnum> platforms;

    @Schema(description = "语言Set", example = "[\"中文\", \"日语\"]")
    private List<LanguageEnum> languages;

    @Schema(description = "资源类型Set", example = "[\"pc\", \"汉化\"]")
    private List<ResourceTypeEnum> resourceTypes;
}