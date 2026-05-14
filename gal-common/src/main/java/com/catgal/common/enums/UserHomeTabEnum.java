package com.catgal.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum UserHomeTabEnum {

    COMMENT(1, "评论"),
    REVIEW(2, "评价"),
    FAVORITE(3, "收藏夹"),
    RESOURCE(4, "发布资源");

    private final Integer code;
    private final String desc;

    UserHomeTabEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonValue
    public static UserHomeTabEnum fromCode(Integer code) {
        for (UserHomeTabEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return COMMENT;
    }

    public static boolean isValid(Integer code) {
        for (UserHomeTabEnum value : values()) {
            if (value.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}