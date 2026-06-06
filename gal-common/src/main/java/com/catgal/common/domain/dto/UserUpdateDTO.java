package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "修改个人信息请求")
public class UserUpdateDTO {

    @Size(max = 17, message = "用户名最多17个字符")
    @Schema(description = "用户名", example = "新用户名")
    private String username;

    @Size(max = 107, message = "签名最多107个字符")
    @Schema(description = "个人签名", example = "新的签名")
    private String signature;

    @Schema(description = "原密码", example = "old123456")
    private String oldPassword;

    @Schema(description = "新密码", example = "new123456")
    private String newPassword;
}
