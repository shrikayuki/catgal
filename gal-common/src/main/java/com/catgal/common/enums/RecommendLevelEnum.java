package com.catgal.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter

public enum RecommendLevelEnum {

    STRONG_NOT_RECOMMEND(1, "强烈不推荐", "💀"),
    NOT_RECOMMEND(2, "不推荐", "👎"),
    NEUTRAL(3, "中立", "🤔"),
    RECOMMEND(4, "推荐", "👍"),
    STRONG_RECOMMEND(5, "强烈推荐", "❤️");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;
    private final String emoji;

    RecommendLevelEnum(Integer code, String desc, String emoji) {
        this.code = code;
        this.desc = desc;
        this.emoji = emoji;
    }

    @JsonCreator
    public static RecommendLevelEnum fromCode(Integer code) {
        for (RecommendLevelEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return NEUTRAL;
    }

    public static String getDesc(Integer code) {
        RecommendLevelEnum e = fromCode(code);
        return e != null ? e.getDesc() : "";
    }

    public static String getEmoji(Integer code) {
        RecommendLevelEnum e = fromCode(code);
        return e != null ? e.getEmoji() : "";
    }
}