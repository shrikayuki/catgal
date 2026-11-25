package com.catgal.server.service;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminContentVO;

/** 管理端评论和评价查询、隐藏与恢复。状态变更和成功审计须在同一事务提交。 */
public interface IAdminContentService {
    PageDTO<AdminContentVO> pageComments(AdminPageQuery query);

    AdminContentVO updateCommentStatus(Long commentId, Integer status);

    PageDTO<AdminContentVO> pageReviews(AdminPageQuery query);

    AdminContentVO updateReviewStatus(Long reviewId, Integer status);
}
