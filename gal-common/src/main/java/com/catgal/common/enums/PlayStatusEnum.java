package com.catgal.common.enums;

import lombok.Getter;

@Getter
public enum PlayStatusEnum {

    NOT_STARTED(1, "未开始", "📭"),
    PLAYING(2, "正在通关", "🎮"),
    SINGLE(3, "单线", "📖"),
    MAIN(4, "主线", "🌟"),
    ALL(5, "全线", "✨"),
    ABANDON(6, "弃坑", "💔");

    private final Integer code;
    private final String desc;
    private final String emoji;

    PlayStatusEnum(Integer code, String desc, String emoji) {
        this.code = code;
        this.desc = desc;
        this.emoji = emoji;
    }

    public static PlayStatusEnum fromCode(Integer code) {
        for (PlayStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return NOT_STARTED;
    }

    public static boolean isValid(Integer code) {
        for (PlayStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}