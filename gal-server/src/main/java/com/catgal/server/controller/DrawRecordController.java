package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.DrawRecordVO;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.service.IDrawRecordService;
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
public class DrawRecordController {

    private final IDrawRecordService drawRecordService;

    @GetMapping("/prizes")
    public R<PageDTO<DrawRecordVO>> queryMyPrizes(PageQuery query){
        PageDTO<DrawRecordVO> vos = drawRecordService.queryMyPrizes(query);
        return R.ok(vos);
    }
}
