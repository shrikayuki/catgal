package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReviewAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.*;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IGameReviewService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.ILikeRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_COMMENT;
import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_REVIEW;

/**
 * <p>
 * 游戏评价表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
public class GameReviewServiceImpl extends ServiceImpl<GameReviewMapper, GameReview> implements IGameReviewService {

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;

    @Override
    public GameConnectVO<GameReviewVO> pageGameReview(GameQuery query) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw new RuntimeException("游戏Id不能为空");
        }
        GameVO gameInfo = gameService.getGameSimpleById(gameId);
        // 先转换基本对象
        GameConnectVO<GameReviewVO> vo = new GameConnectVO<>();
        BeanUtils.copyProperties(gameInfo, vo);

        Page<GameReview> page = lambdaQuery().eq(GameReview::getGameId, gameId)
                .eq(GameReview::getStatus, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<GameReview> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return vo;
        }
        List<GameReviewVO> vos = getVOS(records);

        vo.setPage(PageDTO.of(page, vos));
        return vo;
    }

    @Override
    public void addGameReview(ReviewAddDTO dto) {
        Long userId = UserContext.getUserId();
        Long gameId = dto.getGameId();
        if (userId == null || gameId == null) {
            log.error("游戏id或用户id为空");
            throw new RuntimeException("用户未登录或是游戏不存在");
        }
        GameReview gameReview = BeanUtils.copyProperties(dto, GameReview.class);
        gameReview.setUserId(userId);
        boolean success = save(gameReview);
        if (!success) {
            log.error("保存失败");
        }

    }

    @Override
    public void deleteGameReview(Long id) {
        Long userId = UserContext.getUserId();
        GameReview review = getById(id);
        if (review == null) {
            log.error("评价不存在");
            throw new RuntimeException("该评价不存在或是被删除");
        }
        if (!review.getUserId().equals(userId)) {
            throw new RuntimeException("只能删除自己的评价");
        }
        boolean success = removeById(review);
        if (!success) {
            log.error("删除失败");
        }
        likeService.clearLikeCache(LIKE_TYPE_REVIEW, id);
    }

    private List<GameReviewVO> getVOS(List<GameReview> records) {
        Set<Long> userIds = records.stream().map(GameReview::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = null;
        if (CollUtils.isNotEmpty(userIds)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        }
        List<GameReviewVO> vos = new ArrayList<>(records.size());
        Long userId = UserContext.getUserId();
        Map<Long, Boolean> likedMap = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();
        if (userId != null && CollUtils.isNotEmpty(records)) {
            List<Long> ReviewIds = records.stream().map(GameReview::getId).collect(Collectors.toList());
            likedMap = likeService.batchIsLiked(userId, LIKE_TYPE_REVIEW, ReviewIds);
            likeCounts = likeService.getLikeCounts(ReviewIds, LIKE_TYPE_REVIEW);
        }


        for (GameReview r : records) {
            GameReviewVO vo = BeanUtils.copyBean(r, GameReviewVO.class);

            // 设置用户信息
            User user = userMap == null ? null : userMap.get(r.getUserId());
            if (user != null) {
                vo.setUsername(user.getUsername());
                vo.setAvatarUrl(user.getAvatarUrl());
            }

            // 设置是否为当前用户评论
            if (vo != null) {
                vo.setIsMyReview(r.getUserId().equals(userId));
                //  设置点赞状态
                vo.setIsLike(likedMap.getOrDefault(r.getId(), false));
                vo.setLikeCount(likeCounts.getOrDefault(r.getId(), 0L).intValue());
            }
            vos.add(vo);

        }
        return vos;
    }
}
