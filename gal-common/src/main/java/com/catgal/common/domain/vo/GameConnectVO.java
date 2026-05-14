package com.catgal.common.domain.vo;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "游戏下的关联信息")
public class GameConnectVO<T> {
    // ========== 游戏基本信息（来自 game 表） ==========
    @Schema(description = "游戏ID", example = "1")
    private Long gameId;

    @Schema(description = "游戏名称", example = "Summer Pockets")
    private String name;

    @Schema(description = "封面URL", example = "https://example.com/cover.jpg")
    private String coverUrl;

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

    @Schema(description = "当前用户是否收藏")
    private Boolean isMyFavorite;

    // ========== 从资源表聚合的数据 ==========
    @Schema(description = "平台Set", example = "[\"win\", \"mac\"]")
    private List<PlatformEnum> platforms;

    @Schema(description = "语言Set", example = "[\"中文\", \"日语\"]")
    private List<LanguageEnum> languages;

    @Schema(description = "资源类型Set", example = "[\"pc\", \"汉化\"]")
    private List<ResourceTypeEnum> resourceTypes;

    @Schema(description = "关联业务分页数据")
    private PageDTO<T> page;
}
