/*
 * 阅读说明
 * 抽奖记录的 HTTP 入口。
 * 路由前缀 /draw-record，请求绑定后委托 IDrawRecordService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.DrawRecordVO;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.service.IDrawRecordService;
import com.catgal.server.mvc.annotation.LoginRequired;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.swing.*;
import java.util.List;

/**
 * <p>
 * 抽奖记录表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-27
 */
@RestController
@RequestMapping("/draw-record")
@RequiredArgsConstructor
@LoginRequired
public class DrawRecordController {

    private final IDrawRecordService drawRecordService;

    @GetMapping("/prizes")
    public R<PageDTO<DrawRecordVO>> queryMyPrizes(PageQuery query){
        PageDTO<DrawRecordVO> vos = drawRecordService.queryMyPrizes(query);
        return R.ok(vos);
    }
}
