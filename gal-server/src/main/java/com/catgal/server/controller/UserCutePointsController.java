/*
 * 阅读说明
 * 用户积分余额的 HTTP 入口。
 * 路由前缀 /user-cute-points，请求绑定后委托 相应业务服务。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 用户萌萌点汇总表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
@RestController
@RequestMapping("/user-cute-points")
public class UserCutePointsController {

}
