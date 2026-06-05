package com.catgal.server.controller;

import com.catgal.common.domain.R;
import com.catgal.common.domain.vo.SignVO;
import com.catgal.server.service.ISignService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/sign")
@RequiredArgsConstructor
public class SignController {
    private final ISignService signService;

    @PostMapping()
    @Operation(description = "用户签到")
    public R<Void> signIn(){
        signService.signIn();
        return R.ok();
    }

    @GetMapping()
    @Operation(description = "获取当月签到记录")
    public R<SignVO> getSignRecords(){
        SignVO vo = signService.getSignRecords();
        return R.ok(vo);
    }

}
