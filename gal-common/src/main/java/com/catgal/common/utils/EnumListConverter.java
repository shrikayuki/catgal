package com.catgal.common.utils;

import com.catgal.common.enums.CodeEnum;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EnumListConverter {

    /**
     * 枚举列表 → 逗号分隔字符串
     */
    public static <E extends CodeEnum> String toString(List<E> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.stream()
                .map(CodeEnum::getCode)
                .collect(Collectors.joining(","));
    }

    /**
     * 逗号分隔字符串 → 枚举列表
     */
    public static <E extends CodeEnum> List<E> toList(String str, Function<String, E> fromCode) {
        if (str == null || str.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(str.split(","))
                .map(fromCode)
                .collect(Collectors.toList());
    }
}