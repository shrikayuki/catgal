/*
 * 阅读说明
 * 对象映射时的扩展回调接口，配合 BeanUtils 补充复制后的业务字段。
 */
package com.catgal.common.utils;

public interface Convert<R,T> {
    void convert(R origin, T target);
}
