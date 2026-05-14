package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "发布资源")
public class ResourceAddDTO {

    @Schema(description = "所属游戏ID", example = "1")
    @NotNull
    @Min(1)
    private Long gameId;

    @Schema(description = "资源名称", example = "Summer Pockets 本体")
    private String name;

    @Schema(description = "备注", example = "解压后运行setup.exe")
    private String remark;

    @Schema(description = "资源链接", example = "https://pan.baidu.com/s/xxxxx")
    @NotBlank
    private String downloadUrl;

    @Schema(description = "提取码", example = "abcd")
    private String extractCode;

    @Schema(description = "解压密码", example = "123456")
    private String password;

    @Schema(description = "文件大小", example = "4.2GB")
    @NotBlank
    private String fileSize;

    @Schema(description = "资源类型（存储字段）", hidden = true)
    @NotBlank
    private String types;

    @Schema(description = "语言（存储字段）", hidden = true)
    @NotBlank
    private String languages;

    @Schema(description = "平台（存储字段）", hidden = true)
    @NotBlank
    private String platforms;

}
