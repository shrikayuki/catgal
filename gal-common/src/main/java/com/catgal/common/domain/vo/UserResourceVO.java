package com.catgal.common.domain.vo;

import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户发布的资源")
public class UserResourceVO {

    @Schema(description = "资源ID", example = "1")
    private Long id;

    @Schema(description = "游戏ID")
    private Long gameId;

    @Schema(description = "游戏名称")
    private String gameName;

    @Schema(description = "游戏封面")
    private String coverUrl;

    @Schema(description = "资源类型列表", example = "[\"pc\",\"汉化\"]")
    private List<ResourceTypeEnum> types;

    @Schema(description = "语言列表", example = "[\"中文\",\"日语\"]")
    private List<LanguageEnum> languages;

    @Schema(description = "平台列表", example = "[\"win\",\"mac\"]")
    private List<PlatformEnum> platforms;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}