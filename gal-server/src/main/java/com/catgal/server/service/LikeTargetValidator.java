/*
 * 阅读说明
 * 按点赞业务类型验证目标存在和公开可见性，避免无效目标进入缓存关系。
 */
package com.catgal.server.service;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_COMMENT;
import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_RESOURCE;
import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_REVIEW;

@Service
@RequiredArgsConstructor
public class LikeTargetValidator {

    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;
    private final ResourceMapper resourceMapper;

    public void requireLikeable(String type, Long targetId) {
        requireSupportedType(type);
        if (targetId == null) {
            throw BusinessException.badRequest("点赞目标不能为空");
        }
        boolean active = switch (type) {
            case LIKE_TYPE_COMMENT -> active(commentMapper.selectById(targetId));
            case LIKE_TYPE_REVIEW -> active(gameReviewMapper.selectById(targetId));
            case LIKE_TYPE_RESOURCE -> active(resourceMapper.selectById(targetId));
            default -> false;
        };
        if (!active) {
            throw BusinessException.notFound("点赞目标不存在或已隐藏");
        }
    }

    public void requireSupportedType(String type) {
        if (!LIKE_TYPE_COMMENT.equals(type)
                && !LIKE_TYPE_REVIEW.equals(type)
                && !LIKE_TYPE_RESOURCE.equals(type)) {
            throw BusinessException.badRequest("不支持的点赞类型");
        }
    }

    private boolean active(Comment comment) {
        return comment != null && Objects.equals(comment.getStatus(), 1);
    }

    private boolean active(GameReview review) {
        return review != null && Objects.equals(review.getStatus(), 1);
    }

    private boolean active(Resource resource) {
        return resource != null && Objects.equals(resource.getStatus(), 1);
    }
}
