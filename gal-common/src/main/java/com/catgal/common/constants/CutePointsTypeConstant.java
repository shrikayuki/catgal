/*
 * 阅读说明
 * 积分消息路由使用的业务类型标识，需与 MQ 绑定和消费者对应。
 */
package com.catgal.common.constants;

import javax.print.DocFlavor;

public class CutePointsTypeConstant {
    public static final String SIGN_IN = "sign_in";
    public static final String DRAW_CONSUMED = "draw_consumed";
    public static final String DRAW_RECEIVED = "draw_received";
}
