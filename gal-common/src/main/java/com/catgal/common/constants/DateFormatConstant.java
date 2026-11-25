/*
 * 阅读说明
 * 日期格式约定，月度奖池和签到缓存的分组依赖一致的格式。
 */
package com.catgal.common.constants;

import java.time.format.DateTimeFormatter;

public class DateFormatConstant {
    public static final DateTimeFormatter YM_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
}
