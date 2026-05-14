package com.catgal.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum SpoilerLevelEnum {

    NONE(1, "无剧透", "🟢"),
    LIGHT(2, "轻微剧透", "🟡"),
    SEVERE(3, "严重剧透", "🔴");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;
    private final String tag;

    SpoilerLevelEnum(Integer code, String desc, String tag) {
        this.code = code;
        this.desc = desc;
        this.tag = tag;
    }
    @JsonCreator
    public static SpoilerLevelEnum fromCode(Integer code) {
        for (SpoilerLevelEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return NONE;
    }

    public static boolean isValid(Integer code) {
        for (SpoilerLevelEnum value : values()) {
            if (value.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}