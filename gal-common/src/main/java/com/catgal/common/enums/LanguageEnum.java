package com.catgal.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum LanguageEnum implements CodeEnum {

    CN("中文", "中文"),
    JP("日语", "日本語"),
    EN("英语", "English"),
    OTHER("其他", "其他");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    LanguageEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static LanguageEnum fromCode(String code) {
        for (LanguageEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return OTHER;
    }
}