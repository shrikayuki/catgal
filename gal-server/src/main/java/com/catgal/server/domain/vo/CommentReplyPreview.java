package com.catgal.server.domain.vo;

/** 主评论下的预览回复和对应的可见回复总数。 */
public record CommentReplyPreview(Long id, Long parentId, Long replyCount) {
}
