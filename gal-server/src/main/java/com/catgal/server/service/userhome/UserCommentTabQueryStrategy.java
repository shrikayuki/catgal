/*
 * 阅读说明
 * UserCommentTabQueryStrategy 属于用户主页按 tab 分发的查询策略链。
 * 注册表选择支持目标 tab 的策略，查询结果统一组装到用户主页响应。
 */
package com.catgal.server.service.userhome;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.UserCommentVO;
import com.catgal.common.enums.UserHomeTabEnum;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.service.ICommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserCommentTabQueryStrategy implements UserHomeTabQueryStrategy {

    private final ICommentService commentService;
    private final UserHomeGameLookup gameLookup;

    @Override
    public UserHomeTabEnum supports() {
        return UserHomeTabEnum.COMMENT;
    }

    @Override
    public PageDTO<UserCommentVO> query(UserHomeQueryDTO query, Long userId, boolean includePrivate) {
        // 访客只能看可访问讨论中的评论，本人仍可查阅自己的记录。
        Page<Comment> page = commentService.lambdaQuery()
                .eq(Comment::getUserId, userId)
                .apply(!includePrivate, CommentMapper.VISIBLE_ON_PUBLIC_PROFILE)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Map<Long, String> gameNames = gameLookup.getGameNameMap(records.stream()
                .map(Comment::getGameId)
                .collect(Collectors.toSet()));
        List<UserCommentVO> values = records.stream().map(comment -> {
            UserCommentVO value = BeanUtils.copyProperties(comment, UserCommentVO.class);
            value.setGameName(gameNames.getOrDefault(comment.getGameId(), ""));
            return value;
        }).collect(Collectors.toList());
        return PageDTO.of(page, values);
    }
}
