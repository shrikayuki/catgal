package com.catgal.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.catgal.common.constants.ErrorInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum PlatformEnum implements CodeEnum {

    WIN("win", "Windows"),
    MAC("mac", "macOS"),
    LINUX("linux", "Linux"),
    ANDROID("android", "Android"),
    IOS("ios", "iOS"),
    OTHER("其他", "其他");

    @EnumValue
    @JsonValue
    private final String code;
    
    private final String desc;

    PlatformEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PlatformEnum fromCode(String code) {
        for (PlatformEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return OTHER;
    }
}