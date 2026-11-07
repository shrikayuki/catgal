package com.catgal.server.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.DrawRecord;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.vo.admin.AdminDashboardVO;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.DrawRecordMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.PrizePoolMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.RedisConsistencyAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

import static com.catgal.common.constants.DateFormatConstant.YM_FORMATTER;
import static com.catgal.common.constants.UserRoleConstant.ADMIN;
import static com.catgal.common.constants.UserRoleConstant.CREATOR;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardService {

    private final UserMapper userMapper;
    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;
    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;
    private final PrizePoolMapper prizePoolMapper;
    private final DrawRecordMapper drawRecordMapper;
    private final RedisConsistencyAdminService redisConsistencyAdminService;

    public AdminDashboardVO overview() {
        AdminDashboardVO.AdminDashboardVOBuilder builder = AdminDashboardVO.builder()
                .userCount(userMapper.selectCount(null))
                .creatorCount(userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, CREATOR)))
                .adminCount(userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, ADMIN)))
                .gameCount(gameMapper.selectCount(null))
                .publishedGameCount(gameMapper.selectCount(new LambdaQueryWrapper<Game>().eq(Game::getStatus, 1)))
                .offlineGameCount(gameMapper.selectCount(new LambdaQueryWrapper<Game>().eq(Game::getStatus, 2)))
                .pendingResourceCount(resourceMapper.selectCount(new LambdaQueryWrapper<Resource>().eq(Resource::getStatus, 2)))
                .activeResourceCount(resourceMapper.selectCount(new LambdaQueryWrapper<Resource>().eq(Resource::getStatus, 1)))
                .hiddenCommentCount(commentMapper.selectCount(new LambdaQueryWrapper<Comment>().eq(Comment::getStatus, 0)))
                .hiddenReviewCount(gameReviewMapper.selectCount(new LambdaQueryWrapper<GameReview>().eq(GameReview::getStatus, 0)))
                .currentMonthPrizeCount(prizePoolMapper.selectCount(new LambdaQueryWrapper<PrizePool>()
                        .eq(PrizePool::getMonth, LocalDate.now().format(YM_FORMATTER))))
                .drawRecordCount(drawRecordMapper.selectCount(null))
                .todayDrawCount(drawRecordMapper.selectCount(new LambdaQueryWrapper<DrawRecord>()
                        .ge(DrawRecord::getCreateTime, LocalDate.now().atStartOfDay())
                        .lt(DrawRecord::getCreateTime, LocalDate.now().plusDays(1).atStartOfDay())));
        try {
            var redisOverview = redisConsistencyAdminService.overview();
            builder.redisAvailable(true).pendingRedisOperations(redisOverview.getTotalPending());
        } catch (RuntimeException exception) {
            log.warn("后台仪表盘读取 Redis 状态失败", exception);
            builder.redisAvailable(false).pendingRedisOperations(null);
        }
        return builder.build();
    }
}
