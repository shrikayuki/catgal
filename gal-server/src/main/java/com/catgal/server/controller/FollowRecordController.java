package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.UserFansOrFollowsVO;
import com.catgal.server.domain.po.FollowRecord;
import com.catgal.server.service.IFollowRecordService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 关注记录表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-06-05
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/follow")
public class FollowRecordController {

    private final IFollowRecordService followService;

    @PostMapping("/{followedId}")
    @Operation(description = "关注, 路径参数是被关注的用户的id")
    public R<Boolean> follow(@PathVariable("followedId") Long followedId) {
        return R.ok(followService.follow(followedId));
    }

    @DeleteMapping("/{followedId}")
    @Operation(description = "取消关注")
    public R<Boolean> unfollow(@PathVariable("followedId") Long followedId) {
        return R.ok(followService.unfollow(followedId));
    }

    @GetMapping("/fans/page/{userId}")
    @Operation(description = "查询用户粉丝, id为空代表是当前用户id")
    public R<PageDTO<UserFansOrFollowsVO>> queryUserFansPage(@PathVariable("userId") Long userId, PageQuery query){
        return R.ok(followService.queryUserFansPage(userId, query));
    }

    @GetMapping("/followings/page/{userId}")
    @Operation(description = "查询用户关注, id为空表示是当前用户id")
    public R<PageDTO<UserFansOrFollowsVO>> queryUserFollowsPage(@PathVariable("userId") Long userId, PageQuery query){
        return R.ok(followService.queryUserFollowsPage(userId, query));
    }
}
