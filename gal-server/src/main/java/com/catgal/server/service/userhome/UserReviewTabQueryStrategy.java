package com.catgal.server.service.userhome;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.UserReviewVO;
import com.catgal.common.enums.UserHomeTabEnum;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.service.IGameReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserReviewTabQueryStrategy implements UserHomeTabQueryStrategy {

    private final IGameReviewService reviewService;
    private final UserHomeGameLookup gameLookup;

    @Override
    public UserHomeTabEnum supports() {
        return UserHomeTabEnum.REVIEW;
    }

    @Override
    public PageDTO<UserReviewVO> query(UserHomeQueryDTO query, Long userId, boolean includePrivate) {
        Page<GameReview> page = reviewService.lambdaQuery()
                .eq(GameReview::getUserId, userId)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<GameReview> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Map<Long, String> gameNames = gameLookup.getGameNameMap(records.stream()
                .map(GameReview::getGameId)
                .collect(Collectors.toSet()));
        List<UserReviewVO> values = records.stream().map(review -> {
            UserReviewVO value = BeanUtils.copyProperties(review, UserReviewVO.class);
            value.setGameName(gameNames.getOrDefault(review.getGameId(), ""));
            return value;
        }).collect(Collectors.toList());
        return PageDTO.of(page, values);
    }
}
