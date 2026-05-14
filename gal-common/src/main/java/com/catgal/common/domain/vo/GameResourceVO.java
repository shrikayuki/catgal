package com.catgal.common.domain.vo;

import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "资源信息VO")
public class GameResourceVO {
    @Schema(description = "资源ID", example = "1")
    private Long id;

    @Schema(description = "发布资源用户ID", example = "1001")
    private Long userId;

    @Schema(description = "用户昵称", example = "张三")
    private String username;

    @Schema(description = "用户头像url")
    private String avatarUrl;

    @Schema(description = "用户发布资源数", example = "0")
    private Integer resourceCount;

    @Schema(description = "资源名称", example = "Summer Pockets 本体")
    private String name;

    @Schema(description = "备注", example = "解压后运行setup.exe")
    private String remark;

    @Schema(description = "资源类型列表", example = "[\"pc\",\"汉化\"]")
    private List<ResourceTypeEnum> types;

    @Schema(description = "语言列表", example = "[\"中文\",\"日语\"]")
    private List<LanguageEnum> languages;

    @Schema(description = "平台列表", example = "[\"win\",\"mac\"]")
    private List<PlatformEnum> platforms;

    @Schema(description = "点赞数", example = "1")
    private Integer likeCount;

    @Schema(description = "用户是否点赞", example = "false")
    private Boolean isLike;

    @Schema(description = "是否是我发布的资源", example = "false")
    private Boolean isMyResource;

    @Schema(description = "资源发布时间")
    private LocalDateTime createTime;

}
