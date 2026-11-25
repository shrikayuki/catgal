/*
 * 阅读说明
 * 滚动查询本人萌萌点流水的 HTTP 入口。
 * 路由前缀 /cute-points-record，请求绑定后委托 ICutePointsRecordService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.ScrollPageDTO;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.server.domain.vo.CutePointsRecordVO;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.catgal.server.service.ICutePointsRecordService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 萌萌点流水表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
@RestController
@RequestMapping("/cute-points-record")
@RequiredArgsConstructor
@LoginRequired
public class CutePointsRecordController {
    private final ICutePointsRecordService cutePointsRecordService;

    @GetMapping("/scroll")
    @Operation(summary = "滚动查询本人萌萌点流水",
            description = "按createTime、id固定倒序。pageSize为1至100，首次省略cursor，后续传上次nextCursor。")
    public R<ScrollPageDTO<CutePointsRecordVO>> queryMyRecords(@Valid ScrollPageQuery query) {
        return R.ok(cutePointsRecordService.queryMyRecords(query));
    }
}
