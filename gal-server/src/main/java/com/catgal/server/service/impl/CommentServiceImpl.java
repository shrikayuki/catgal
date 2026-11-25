/*
 * 阅读说明
 * 游戏评论、回复树和删除的业务实现。
 * 根评论协调回复与删除竞争，作者和互动信息按页批量组装。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.dto.ScrollPageDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.common.domain.vo.CommentVO;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.vo.CommentReplyPreview;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.ICommentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.ILikeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_COMMENT;

/**
 * <p>
 * 游戏评论表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    private static final int MAX_THREAD_PAGE_SIZE = 50;

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;
    private final GameMapper gameMapper;

    @Override
    @Transactional(readOnly = true)
    // 先核验所属游戏，再分页读取可见评论并批量装配作者和互动状态。
    public GameConnectVO<CommentVO> pageGameComment(GameQuery query, boolean withReplies) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        GameVO gameInfo = gameService.getGameSimpleById(gameId);
        // 先转换基本对象
        GameConnectVO<CommentVO> vo = new GameConnectVO<>();
        BeanUtils.copyProperties(gameInfo, vo);

        Page<Comment> page = lambdaQuery().eq(Comment::getGameId, gameId)
                .eq(Comment::getStatus, 1)
                .isNull(Comment::getParentId)
                .page(withReplies ? threadPage(query) : query.toMpPageDefaultSortByCreateTimeDesc());
        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            if (withReplies) vo.setPage(PageDTO.empty(page));
            return vo;
        }
        vo.setPage(PageDTO.of(page, withReplies ? assembleThreads(gameId, records) : getVOS(records)));
        return vo;
    }

    private Page<Comment> threadPage(PageQuery query) {
        // 同一秒的评论以主键作为第二排序键，避免翻页时顺序不确定。
        long number = Objects.requireNonNullElse(query.getPageNo(), PageQuery.DEFAULT_PAGE_NUM);
        Page<Comment> page = new Page<>(Math.max(number, 1), threadPageSize(query.getPageSize()));
        page.addOrder(OrderItem.desc("create_time"), OrderItem.desc("id"));
        return page;
    }

    private List<CommentVO> assembleThreads(Long gameId, List<Comment> roots) {
        List<CommentReplyPreview> previews = baseMapper.selectReplyPreviews(gameId,
                roots.stream().map(Comment::getId).toList());
        List<Comment> records = new ArrayList<>(roots);
        if (!previews.isEmpty()) {
            records.addAll(baseMapper.selectBatchIds(previews.stream().map(CommentReplyPreview::id).toList()));
        }
        Map<Long, CommentVO> views = getVOS(records).stream().collect(Collectors.toMap(CommentVO::getId, view -> view));
        List<CommentVO> result = roots.stream().map(root -> views.get(root.getId())).toList();
        for (CommentVO root : result) {
            root.setReplyPreview(new ArrayList<>(2));
            root.setReplyCount(0L);
        }
        // 按预览查询的顺序装配，不能依赖批量主键查询的返回顺序。
        for (CommentReplyPreview preview : previews) {
            CommentVO root = views.get(preview.parentId());
            CommentVO reply = views.get(preview.id());
            root.setReplyCount(preview.replyCount());
            if (reply != null) root.getReplyPreview().add(reply);
        }
        return result;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 先锁游戏，再保存评论，与治理和删除使用同一锁序，计数随评论提交。
    public void addComment(CommentAddDTO dto) {
        Long gameId = dto.getGameId();
        if (gameId == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        requirePublishedGame(gameMapper.selectForUpdate(gameId));
        Comment comment = new Comment();
        BeanUtils.copyProperties(dto, comment);
        comment.setContent(dto.getContent().strip());
        comment.setStatus(1);
        comment.setUserId(userId);
        boolean success = save(comment);
        if (!success) {
            throw BusinessException.internalError("评论保存失败");
        }
        gameMapper.refreshCommentCount(gameId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 锁序为游戏、主评论、回复目标。持锁核验使隐藏与新增回复有明确先后。
    public void replyComment(ReplyCommentDTO dto) {
        Long gameId = dto.getGameId();
        if (gameId == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        requirePublishedGame(gameMapper.selectForUpdate(gameId));
        Comment parent = baseMapper.selectForUpdate(dto.getParentId());
        if (parent == null || !Objects.equals(parent.getStatus(), 1)
                || parent.getParentId() != null || !Objects.equals(parent.getGameId(), gameId)) {
            throw BusinessException.notFound("父评论不存在、已隐藏或不属于该游戏");
        }
        Comment replyTarget = parent;
        if (dto.getReplyCommentId() != null) {
            replyTarget = baseMapper.selectForUpdate(dto.getReplyCommentId());
            if (replyTarget == null || !Objects.equals(replyTarget.getStatus(), 1)
                    || !Objects.equals(replyTarget.getGameId(), gameId)
                    || !(Objects.equals(replyTarget.getId(), parent.getId()) || Objects.equals(replyTarget.getParentId(), parent.getId()))) {
                throw BusinessException.notFound("被回复评论不存在、已隐藏或不属于该游戏");
            }
        }
        Comment comment = new Comment();
        BeanUtils.copyProperties(dto, comment);
        comment.setContent(dto.getContent().strip());
        comment.setStatus(1);
        comment.setReplyCommentId(replyTarget.getId());
        comment.setReplyUserId(replyTarget.getUserId());
        comment.setUserId(userId);
        boolean success = save(comment);
        if (!success) {
            throw BusinessException.internalError("保存失败");
        }
        gameMapper.refreshCommentCount(gameId);
    }

    @Override
    @Transactional(readOnly = true)
    // 与讨论页共用主评论可见性规则，目标与分页读取使用同一事务快照。
    public PageDTO<CommentVO> getChildComments(Long replyCommentId, PageQuery query) {
        Comment target = baseMapper.selectVisibleInThread(replyCommentId);
        if (target == null) {
            throw BusinessException.notFound("评论不存在或已隐藏");
        }
        requirePublishedGame(target.getGameId());
        Long rootId = target.getParentId() == null ? target.getId() : target.getParentId();
        Page<Comment> page = lambdaQuery()
                .eq(Comment::getReplyCommentId, replyCommentId)
                .eq(Comment::getGameId, target.getGameId())
                .eq(Comment::getParentId, rootId)
                .apply(CommentMapper.VISIBLE_IN_THREAD)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 转换 VO
        List<CommentVO> vos = getVOS(records);

        return PageDTO.of(page, vos);
    }

    @Override
    @Transactional(readOnly = true)
    public PageDTO<CommentVO> getThreadReplies(Long rootId, PageQuery query) {
        Page<Comment> page = visibleThreadReplies(requireVisibleThreadRoot(rootId)).page(threadPage(query));
        return PageDTO.of(page, getVOS(page.getRecords()));
    }

    @Override
    @Transactional(readOnly = true)
    public ScrollPageDTO<CommentVO> scrollThreadReplies(Long rootId, ScrollPageQuery query) {
        Long olderThan = parseReplyCursor(query.getCursor());
        int size = threadPageSize(query.getPageSize());
        // 多读一条判断下一页。只按唯一 ID 继续，翻页期间插入的新回复不会造成重复。
        List<Comment> records = visibleThreadReplies(requireVisibleThreadRoot(rootId))
                .lt(olderThan != null, Comment::getId, olderThan)
                .orderByDesc(Comment::getId)
                .page(new Page<>(1, size + 1, false)).getRecords();
        boolean hasMore = records.size() > size;
        List<Comment> batch = hasMore ? records.subList(0, size) : records;
        String next = hasMore ? Long.toString(batch.get(batch.size() - 1).getId()) : null;
        return new ScrollPageDTO<>(getVOS(batch), next, hasMore);
    }

    private Long parseReplyCursor(String cursor) {
        if (cursor == null) return null;
        if (cursor.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.valueOf(cursor);
            } catch (NumberFormatException ignored) {
                // 19 位十进制仍可能超过数据库 BIGINT 上限。
            }
        }
        throw BusinessException.badRequest("回复游标格式无效，请重新加载");
    }

    private int threadPageSize(Integer requested) {
        return Math.min(Math.max(Objects.requireNonNullElse(requested, PageQuery.DEFAULT_PAGE_SIZE), 1),
                MAX_THREAD_PAGE_SIZE);
    }

    private Comment requireVisibleThreadRoot(Long rootId) {
        Comment root = getById(rootId);
        if (root == null || root.getParentId() != null || !Objects.equals(root.getStatus(), 1)) {
            throw BusinessException.notFound("评论不存在或已隐藏");
        }
        Game game = gameService.getById(root.getGameId());
        if (game == null || !Objects.equals(game.getStatus(), 1)) {
            throw BusinessException.notFound("游戏不存在或未发布");
        }
        return root;
    }

    // 隐藏中间回复后，同楼其他正常回复仍可访问。标准分页与滚动接口共用此条件。
    private LambdaQueryChainWrapper<Comment> visibleThreadReplies(Comment root) {
        return lambdaQuery().eq(Comment::getGameId, root.getGameId())
                .eq(Comment::getParentId, root.getId())
                .eq(Comment::getStatus, 1);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 删除与回复、治理共用游戏行锁，再锁主评论和目标，保证重算发生在同一事务。
    public void deleteComment(Long id) {
        log.info("\u001B[31mdelete comment: {}\u001B[0m", id);
        Long userId = UserContext.getUserId();
        if (id == null || userId == null) {
            log.error("评论或用户不存在");
            throw BusinessException.notFound("评论或用户不存在");
        }
        Comment comment = getById(id);
        if (comment == null) {
            throw BusinessException.notFound("评论不存在");
        }
        Game game = gameMapper.selectForUpdate(comment.getGameId());
        if (comment.getParentId() != null) baseMapper.selectForUpdate(comment.getParentId());
        comment = baseMapper.selectForUpdate(id);
        if (comment == null) throw BusinessException.notFound("评论已被删除");
        if (!comment.getUserId().equals(userId)) {
            throw BusinessException.forbidden("不能删除他人的评论");
        }
        List<Comment> list = null;
        if (comment.getParentId() == null) {
            //根评论 需要同时删除子评论
            list = lambdaQuery().eq(Comment::getParentId, id).last("FOR UPDATE").list();
            if (CollUtils.isNotEmpty(list) && !removeBatchByIds(list)) {
                throw BusinessException.conflict("回复已发生变化，请刷新后重试");
            }
        } else {
            // 保留其他用户的下级回复，回挂根评论后仍可从对话入口访问。
            baseMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                    .eq(Comment::getParentId, comment.getParentId())
                    .eq(Comment::getReplyCommentId, id)
                    .set(Comment::getReplyCommentId, comment.getParentId()));
        }
        boolean del = removeById(comment);
        if (!del) {
            throw BusinessException.conflict("评论已发生变化，请刷新后重试");
        }
        // 历史孤儿评论仍允许作者删除。所属游戏存在时，才有统计行需要更新。
        if (game != null) gameMapper.refreshCommentCount(game.getId());
        likeService.clearLikeCache(LIKE_TYPE_COMMENT, id);
        if (CollUtils.isNotEmpty(list)) {
            for (Comment comm : list) {
                likeService.clearLikeCache(LIKE_TYPE_COMMENT, comm.getId());
            }
        }

    }

    // 先收集作者和目标 ID 批量查询，再逐条装配视图，避免每条评论重复访问数据库。
    private List<CommentVO> getVOS(List<Comment> records) {
        if (records.isEmpty()) return List.of();
        Set<Long> userIds = records.stream().map(Comment::getUserId).collect(Collectors.toSet());
        records.stream().map(Comment::getReplyUserId).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, User> userMap = null;
        if (CollUtils.isNotEmpty(userIds)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        }
        List<CommentVO> vos = new ArrayList<>(records.size());
        Long userId = UserContext.getUserId();
        Map<Long, Boolean> likedMap = new HashMap<>();
        List<Long> commentIds = records.stream().map(Comment::getId).toList();
        Map<Long, Long> likeCounts = likeService.getLikeCounts(commentIds, LIKE_TYPE_COMMENT);
        if (userId != null) {
            likedMap = likeService.batchIsLiked(userId, LIKE_TYPE_COMMENT, commentIds);
        }
        for (Comment c : records) {
            CommentVO commentVO = BeanUtils.copyProperties(c, CommentVO.class);
            // 设置用户信息
            User user = userMap == null ? null : userMap.get(c.getUserId());
            if (user != null) {
                commentVO.setUsername(user.getUsername());
                commentVO.setAvatarUrl(com.catgal.server.storage.FileAccessPaths.avatar(user));
            }
            User replyUser = userMap == null ? null : userMap.get(c.getReplyUserId());
            if (replyUser != null) commentVO.setReplyUsername(replyUser.getUsername());

            // 设置是否为当前用户评论
            if (commentVO != null) {
                commentVO.setIsMyComment(c.getUserId().equals(userId));
                //设置点赞状态
                commentVO.setIsLike(likedMap.getOrDefault(c.getId(), false));
                commentVO.setLikeCount(likeCounts.getOrDefault(c.getId(), 0L).intValue());
            }
            vos.add(commentVO);

        }
        return vos;
    }

    private void requirePublishedGame(Long gameId) {
        requirePublishedGame(gameService.getById(gameId));
    }

    private void requirePublishedGame(Game game) {
        if (game == null || !Objects.equals(game.getStatus(), 1)) {
            throw BusinessException.conflict("只能在已发布游戏下发表评论");
        }
    }
}
