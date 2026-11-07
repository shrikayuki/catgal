package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminStatusUpdateDTO;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminContentVO;
import com.catgal.server.service.admin.AdminContentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/content")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 内容治理")
@SecurityRequirement(name = "bearerAuth")
public class AdminContentController {

    private final AdminContentService adminContentService;

    @GetMapping("/comments")
    @Operation(summary = "分页查询评论")
    public R<PageDTO<AdminContentVO>> pageComments(@Valid AdminPageQuery query) {
        return R.ok(adminContentService.pageComments(query));
    }

    @PatchMapping("/comments/{commentId}/status")
    @Operation(summary = "隐藏或恢复评论", description = "状态：0隐藏、1正常")
    public R<AdminContentVO> updateCommentStatus(@PathVariable Long commentId,
                                                 @Valid @RequestBody AdminStatusUpdateDTO dto) {
        return R.ok(adminContentService.updateCommentStatus(commentId, dto.getStatus()));
    }

    @GetMapping("/reviews")
    @Operation(summary = "分页查询游戏评价")
    public R<PageDTO<AdminContentVO>> pageReviews(@Valid AdminPageQuery query) {
        return R.ok(adminContentService.pageReviews(query));
    }

    @PatchMapping("/reviews/{reviewId}/status")
    @Operation(summary = "隐藏或恢复游戏评价", description = "状态：0隐藏、1正常")
    public R<AdminContentVO> updateReviewStatus(@PathVariable Long reviewId,
                                                @Valid @RequestBody AdminStatusUpdateDTO dto) {
        return R.ok(adminContentService.updateReviewStatus(reviewId, dto.getStatus()));
    }
}
