/*
 * 阅读说明
 * AI 用户会话的 HTTP 入口。
 * 路由前缀 /user-session，请求绑定后委托 相应业务服务。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
@RestController
@RequestMapping("/user-session")
public class UserSessionController {

}
