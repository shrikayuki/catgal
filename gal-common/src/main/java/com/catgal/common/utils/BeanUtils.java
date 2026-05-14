package com.catgal.common.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 继承自 hutool 的BeanUtil，增加了bean转换时自定义转换器的功能
 */
public class BeanUtils extends BeanUtil {

    /**
     * 将原对象转换成目标对象，对于字段不匹配的字段可以使用转换器处理
     *
     * @param source  原对象
     * @param clazz   目标对象的class
     * @param convert 转换器
     * @param <R>     原对象类型
     * @param <T>     目标对象类型
     * @return 目标对象
     */
    public static <R, T> T copyBean(R source, Class<T> clazz, Convert<R, T> convert) {
        T target = copyBean(source, clazz);
        if (convert != null) {
            convert.convert(source, target);
        }
        return target;
    }

    /**
     * 将原对象转换成目标对象
     *
     * @param source  原对象
     * @param clazz   目标对象的class
     * @param <R>     原对象类型
     * @param <T>     目标对象类型
     * @return 目标对象
     */
    public static <R, T> T copyBean(R source, Class<T> clazz) {
        if (ObjectUtil.isEmpty(source)) {
            return null;
        }
        return BeanUtil.toBean(source, clazz);
    }

    /**
     * 复制列表
     *
     * @param list  原对象列表
     * @param clazz 目标对象的class
     * @param <R>   原对象类型
     * @param <T>   目标对象类型
     * @return 目标对象列表
     */
    public static <R, T> List<T> copyList(List<R> list, Class<T> clazz) {
        if (CollUtil.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        return BeanUtil.copyToList(list, clazz);
    }

    /**
     * 复制列表，带自定义转换器
     *
     * @param list    原对象列表
     * @param clazz   目标对象的class
     * @param convert 转换器
     * @param <R>     原对象类型
     * @param <T>     目标对象类型
     * @return 目标对象列表
     */
    public static <R, T> List<T> copyList(List<R> list, Class<T> clazz, Convert<R, T> convert) {
        if (CollUtil.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        return list.stream()
                .map(r -> copyBean(r, clazz, convert))
                .collect(Collectors.toList());
    }
}