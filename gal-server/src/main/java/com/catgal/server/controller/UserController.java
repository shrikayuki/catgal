package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.LoginDTO;
import com.catgal.common.domain.dto.RegisterDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.dto.UserUpdateDTO;
import com.catgal.common.domain.vo.LoginVO;
import com.catgal.common.domain.vo.UserHomeVO;
import com.catgal.server.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 * 用户表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-04-30
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "用户管理", description = "用户注册、登录接口")
public class UserController {


    private final IUserService userService;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public R<String> register(@RequestBody RegisterDTO dto) {
        userService.register(dto);
        return R.ok("注册成功");
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public R<LoginVO> login(@RequestBody LoginDTO dto) {
        LoginVO vo = userService.login(dto);
        return R.ok(vo);
    }

    @GetMapping("/home")
    @Operation(summary = "查看用户主页(也可以看别人的 id为空就是自己)")
    public R<UserHomeVO> getUserHomeById(@Valid UserHomeQueryDTO dto) {
        return R.ok(userService.getUserHomeById(dto));
    }

    @PutMapping("/profile/{userId}")
    @Operation(summary = "修改个人信息")
    public R<Void> updateProfile(@PathVariable("userId") Long id,@Valid @RequestBody UserUpdateDTO dto) {
        userService.updateProfile(id, dto);
        return R.ok();
    }

    @PostMapping("/avatar")
    @Operation(description = "修改个人头像")
    public R<String> updateAvatar(@RequestParam("file") MultipartFile file) {
        String url = userService.updateAvatar(file);
        return R.ok(url);
    }

    @GetMapping("/points")
    @Operation(description = "获得个人积分")
    public R<Integer> getMyPoints() {
        return R.ok(userService.getMyPoints());
    }



}

