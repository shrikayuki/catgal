/*
 * 阅读说明
 * 用户发布的资源的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import com.catgal.common.enums.LanguageEnum;
import com.catgal.common.enums.PlatformEnum;
import com.catgal.common.enums.ResourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户发布的资源")
public class UserResourceVO {

    @Schema(description = "资源ID", example = "1")
    private Long id;
    private String name;
    private Integer status;

    /** 仅本人主页装配审核信息，公开主页序列化时省略这些字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long revision;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime reviewedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reviewReason;

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
