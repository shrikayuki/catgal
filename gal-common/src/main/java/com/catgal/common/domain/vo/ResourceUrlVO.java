package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "游戏链接")
public class ResourceUrlVO {
    @Schema(description = "资源id")
    Long id;

    @Schema(description = "资源链接", example = "https://pan.baidu.com/s/xxxxx")
    private String downloadUrl;

    @Schema(description = "提取码", example = "abcd")
    private String extractCode;

    @Schema(description = "解压密码", example = "123456")
    private String password;

    @Schema(description = "文件大小", example = "4.2GB")
    private String fileSize;
}
