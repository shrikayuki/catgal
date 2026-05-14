package com.catgal.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum ResourceTypeEnum implements CodeEnum {

    PC("pc", "PC版"),
    RAW("生肉", "生肉"),
    CN("汉化", "汉化版"),
    MOBILE("手机", "手机版"),
    PATCH("补丁", "补丁"),
    EMULATOR("模拟器", "模拟器"),
    OTHER("其他", "其他");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    ResourceTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    @JsonCreator
    public static ResourceTypeEnum fromCode(String code) {
        for (ResourceTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return OTHER;
    }
}