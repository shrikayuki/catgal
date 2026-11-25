/*
 * 阅读说明
 * 游戏资源发布、删除、列表和外链下载入口。
 * 按角色决定初始审核状态，读取外链前核验资源和所属游戏均可见。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.ResourceReviewHistoryMapper;
import com.catgal.server.domain.po.ResourceReviewHistory;
import com.catgal.server.domain.dto.ResourceEditDTO;
import com.catgal.server.domain.dto.admin.ResourceReviewDTO;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.query.admin.AdminResourceReviewLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.server.service.IUserAccountStatusService;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.IResourceService;
import com.catgal.server.service.RedisCounterService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_RESOURCE;
import static com.catgal.common.constants.RedisConstant.*;
import static com.catgal.common.constants.ResourceConstant.OFFICIAL_RESOURCE;
import static com.catgal.common.constants.ResourceConstant.USER_RESOURCE;
import static com.catgal.common.constants.UserRoleConstant.*;
import static com.catgal.server.service.IUserAccountStatusService.TIME_ZONE;

/**
 * <p>
 * 游戏资源表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements IResourceService {

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;
    private final IGameStatsService statsService;
    private final RedisCounterService redisCounterService;
    private final GameMapper gameMapper;
    private final ResourceReviewHistoryMapper historyMapper;
    private final IUserAccountStatusService accountStatusService;

    @Override
    // 验证游戏已发布，再查询有效资源，作者和互动状态通过批量查询补充。
    public GameConnectVO<GameResourceVO> pageGameResource(GameQuery query) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        GameVO gameInfo = gameService.getGameSimpleById(gameId);
        // 先转换基本对象
        GameConnectVO<GameResourceVO> vo = new GameConnectVO<>();
        BeanUtils.copyProperties(gameInfo, vo);

        Page<Resource> page = lambdaQuery().eq(Resource::getGameId, gameId)
                .eq(Resource::getStatus, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Resource> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return vo;
        }
        List<GameResourceVO> vos = getVOS(records);

        vo.setPage(PageDTO.of(page, vos));
        return vo;
    }

    @Override
    // 提取码和下载地址读取前复用资源及所属游戏可见性检查。
    public ResourceUrlVO getResourceUrl(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("参数不能为空");
        }
        Resource resource = requireAccessibleResource(id);
        return BeanUtils.copyProperties(resource, ResourceUrlVO.class);
    }

    @Override
    @Transactional
    // 管理员沿用直接有效发布，创作者进入待审；历史失败会使资源和触发器任务一起回滚。
    public void addResource(ResourceAddDTO dto) {
        validateLink(dto.getDownloadUrl());
        User actor = lockActor();
        requireCreator(actor);
        requirePublishedGame(gameMapper.selectForUpdate(dto.getGameId()));
        Resource resource = BeanUtils.copyProperties(dto, Resource.class);
        resource.setUserId(actor.getId()).setRevision(0L);
        boolean official = Objects.equals(actor.getRole(), ADMIN);
        resource.setIsOfficial(official ? OFFICIAL_RESOURCE : USER_RESOURCE).setStatus(official ? 1 : 2);
        if (!save(resource)) throw BusinessException.internalError("资源发布失败");
        // 插入/状态触发器同事务生成统计任务；发布事务不依赖 Redis 可用性。
        appendHistory(resource, actor, "CREATE", null, 0L, null, List.of("content"));
    }

    @Override
    @Transactional
    public void deleteResource(Long id) {
        User actor = lockActor();
        Resource resource = lockResource(id);
        requireOwnerOrAdmin(actor, resource);
        if (!removeById(id)) throw BusinessException.conflict("资源已发生变化，请刷新后重试");
        appendHistory(resource, actor, "DELETE", resource.getStatus(), resource.getRevision(), null, List.of());
        // 保留原有点赞关系清理；其缓存删除在提交后执行，失败进入既有恢复流程。
        likeService.clearLikeCache(LIKE_TYPE_RESOURCE, id);
    }

    @Override
    public AdminResourceVO getEditable(Long id) {
        User actor = accountStatusService.requireActiveUser(UserContext.getUserId());
        Resource resource = getById(id);
        if (resource == null) throw BusinessException.notFound("资源不存在");
        requireOwnerOrAdmin(actor, resource);
        return view(resource);
    }

    @Override
    @Transactional
    // 编辑表单的 revision 只覆盖内容版本，后台下载/点赞统计更新不会制造编辑冲突。
    public AdminResourceVO editResource(Long id, ResourceEditDTO dto) {
        validateLink(dto.getDownloadUrl());
        User actor = lockActor();
        requireCreator(actor);
        Resource resource = lockResource(id);
        requireOwnerOrAdmin(actor, resource);
        requireRevision(resource, dto.getRevision());
        if (!Objects.equals(resource.getGameId(), dto.getGameId())) {
            throw BusinessException.badRequest("编辑时不能转移资源所属游戏");
        }
        // 锁定读使用当前版本，避免事务前面的普通查询建立RR快照后看到旧的游戏发布状态。
        requirePublishedGame(gameMapper.selectForUpdate(resource.getGameId()));
        List<String> changed = changedFields(resource, dto);
        if (changed.isEmpty()) return view(resource);
        Integer previousStatus = resource.getStatus();
        Long previousRevision = resource.getRevision();
        // 仅复制可编辑内容，禁止覆盖作者、计数、官方标识、状态及审核字段。
        var update = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Resource>lambdaUpdate()
                .eq(Resource::getId, id).eq(Resource::getRevision, dto.getRevision())
                .set(Resource::getName, dto.getName()).set(Resource::getRemark, dto.getRemark())
                .set(Resource::getDownloadUrl, dto.getDownloadUrl()).set(Resource::getExtractCode, dto.getExtractCode())
                .set(Resource::getPassword, dto.getPassword()).set(Resource::getFileSize, dto.getFileSize())
                .set(Resource::getTypes, dto.getTypes()).set(Resource::getLanguages, dto.getLanguages())
                .set(Resource::getPlatforms, dto.getPlatforms())
                .set(Resource::getStatus, Objects.equals(actor.getRole(), ADMIN) ? previousStatus : 2)
                .set(Resource::getReviewedBy, null).set(Resource::getReviewedAt, null)
                .set(Resource::getReviewReason, null).set(Resource::getUpdateTime, accountStatusService.now());
        if (baseMapper.update(null, update) != 1) throw BusinessException.conflict("资源已更新，请刷新后重试");
        Resource current = getById(id);
        appendHistory(current, actor, "EDIT", previousStatus, previousRevision, null, changed);
        return view(current);
    }

    @Override
    @Transactional
    public AdminResourceVO submitResource(Long id, Long revision) {
        User actor = lockActor();
        requireCreator(actor);
        Resource resource = lockResource(id);
        requireOwnerOrAdmin(actor, resource);
        requireRevision(resource, revision);
        requirePublishedGame(gameMapper.selectForUpdate(resource.getGameId()));
        if (Objects.equals(resource.getStatus(), 2)) return view(resource);
        if (!Objects.equals(resource.getStatus(), 0)) throw BusinessException.conflict("仅失效资源需要重新送审");
        writeReviewState(resource, 2, null, null, null);
        Resource current = getById(id);
        appendHistory(current, actor, "SUBMIT", resource.getStatus(), revision, null, List.of("status"));
        return view(current);
    }

    @Override
    @Transactional
    public AdminResourceVO reviewResource(Long id, ResourceReviewDTO dto) {
        User actor = lockActor();
        if (!Objects.equals(actor.getRole(), ADMIN)) throw BusinessException.forbidden("仅管理员可以审核资源");
        Integer status = dto.getStatus();
        if (status == null || status < 0 || status > 2) throw BusinessException.badRequest("资源状态无效");
        String reason = dto.getReason() == null ? null : dto.getReason().strip();
        if (reason != null && reason.length() > 500) throw BusinessException.badRequest("审核原因不能超过500字");
        if (status == 0 && (reason == null || reason.isBlank())) throw BusinessException.badRequest("驳回或下架请填写原因");
        Resource resource = lockResource(id);
        requireRevision(resource, dto.getRevision());
        if (status == 1) requirePublishedGame(gameMapper.selectForUpdate(resource.getGameId()));
        if (Objects.equals(resource.getStatus(), status) && Objects.equals(resource.getReviewReason(), reason)) return view(resource);
        // 版本在持锁后复核，旧审核页面不能批准创作者刚编辑的新版本。
        writeReviewState(resource, status, actor.getId(), accountStatusService.now(), reason);
        Resource current = getById(id);
        appendHistory(current, actor, status == 1 ? "APPROVE" : status == 0 ? "REJECT" : "REQUEUE",
                resource.getStatus(), resource.getRevision(), reason, List.of("status", "review"));
        return view(current);
    }

    @Override
    public PageDTO<ResourceReviewHistory> history(Long id, AdminPageQuery query) {
        User actor = accountStatusService.requireActiveUser(UserContext.getUserId());
        // 历史保留到资源删除之后，权限按历史 owner_id 判断；只返回调用者可见的操作。
        Resource resource = getById(id);
        boolean admin = Objects.equals(actor.getRole(), ADMIN);
        if (resource != null && !admin && !Objects.equals(resource.getUserId(), actor.getId())) {
            throw BusinessException.forbidden("只能查看本人资源的审核历史");
        }
        var filter = Wrappers.<ResourceReviewHistory>lambdaQuery()
                .eq(ResourceReviewHistory::getResourceId, id)
                .eq(!admin, ResourceReviewHistory::getOwnerId, actor.getId());
        return PageDTO.of(historyMapper.selectPage(query.toSafePage(), filter));
    }

    @Override
    public PageDTO<AdminOperationLogVO> pageAdminReviewOperations(AdminResourceReviewLogQuery query) {
        User actor = accountStatusService.requireActiveUser(UserContext.getUserId());
        if (!Objects.equals(actor.getRole(), ADMIN)) {
            throw BusinessException.forbidden("仅管理员可以查询全部资源审核记录");
        }
        if (query.getFrom() != null && query.getTo() != null && query.getFrom().isAfter(query.getTo())) {
            throw BusinessException.badRequest("开始时间不能晚于结束时间");
        }
        // 独立审核历史是唯一来源。无需关联当前资源，删除后的历史也可按原编号查询。
        var filter = Wrappers.<ResourceReviewHistory>lambdaQuery()
                .select(ResourceReviewHistory::getId, ResourceReviewHistory::getActorId,
                        ResourceReviewHistory::getResourceId, ResourceReviewHistory::getGameId,
                        ResourceReviewHistory::getAction, ResourceReviewHistory::getOldStatus,
                        ResourceReviewHistory::getNewStatus, ResourceReviewHistory::getBeforeRevision,
                        ResourceReviewHistory::getAfterRevision, ResourceReviewHistory::getReason,
                        ResourceReviewHistory::getCreateTime)
                .in(ResourceReviewHistory::getAction, "APPROVE", "REJECT", "REQUEUE")
                .eq(query.getAdminUserId() != null, ResourceReviewHistory::getActorId, query.getAdminUserId())
                .eq(query.getTargetId() != null, ResourceReviewHistory::getResourceId, query.getTargetId())
                .eq(query.getOperation() != null, ResourceReviewHistory::getAction, query.getOperation());
        // 审核历史保存站点本地时间，范围边界统一转换到同一时区再下推 SQL。
        if (query.getFrom() != null) {
            filter.ge(ResourceReviewHistory::getCreateTime, query.getFrom().atZoneSameInstant(TIME_ZONE).toLocalDateTime());
        }
        if (query.getTo() != null) {
            filter.le(ResourceReviewHistory::getCreateTime, query.getTo().atZoneSameInstant(TIME_ZONE).toLocalDateTime());
        }
        return PageDTO.of(historyMapper.selectPage(query.toSafePage(), filter), this::reviewOperation);
    }

    private AdminOperationLogVO reviewOperation(ResourceReviewHistory row) {
        AdminOperationLogVO view = new AdminOperationLogVO();
        view.setId(row.getId());
        view.setAdminUserId(row.getActorId());
        view.setOperation(row.getAction());
        view.setTargetType("resource");
        view.setTargetId(row.getResourceId().toString());
        view.setBeforeData(reviewSnapshot(row.getGameId(), row.getOldStatus(), row.getBeforeRevision()));
        view.setAfterData(reviewSnapshot(row.getGameId(), row.getNewStatus(), row.getAfterRevision()));
        view.setReason(row.getReason());
        view.setCreateTime(row.getCreateTime().atZone(TIME_ZONE).toOffsetDateTime());
        return view;
    }

    private Map<String, Object> reviewSnapshot(Long gameId, Integer status, Long revision) {
        // 状态允许为空，游戏编号和版本以字符串返回，保留前端传递的完整精度。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gameId", gameId.toString());
        result.put("status", status);
        result.put("revision", revision.toString());
        return result;
    }

    /** 操作者 -> 游戏 -> 资源，所有生命周期写入保持同一锁序。 */
    private User lockActor() {
        Long id = UserContext.getUserId();
        if (id == null) throw BusinessException.unauthorized("用户未登录");
        User actor = userMapper.selectForUpdate(id);
        accountStatusService.requireActive(actor);
        return actor;
    }

    private Resource lockResource(Long id) {
        Resource initial = getById(id);
        if (initial == null) throw BusinessException.notFound("资源不存在");
        if (gameMapper.selectForUpdate(initial.getGameId()) == null) throw BusinessException.notFound("所属游戏不存在");
        Resource locked = baseMapper.selectForUpdate(id);
        if (locked == null) throw BusinessException.notFound("资源不存在");
        if (!Objects.equals(initial.getGameId(), locked.getGameId())) throw BusinessException.conflict("资源归属已变化，请刷新");
        return locked;
    }

    private void requireCreator(User actor) {
        if (!Objects.equals(actor.getRole(), CREATOR) && !Objects.equals(actor.getRole(), ADMIN)) {
            throw BusinessException.forbidden("请申请为创作者后操作资源");
        }
    }

    private void requireOwnerOrAdmin(User actor, Resource resource) {
        if (!Objects.equals(actor.getRole(), ADMIN)
                && !(Objects.equals(actor.getRole(), CREATOR) && Objects.equals(actor.getId(), resource.getUserId()))) {
            throw BusinessException.forbidden("您无权修改该资源");
        }
    }

    private void requireRevision(Resource resource, Long revision) {
        if (revision == null || revision < 0) throw BusinessException.badRequest("请携带资源版本");
        if (!Objects.equals(resource.getRevision(), revision)) throw BusinessException.conflict("资源已被修改，请刷新后重试");
    }

    private void requirePublishedGame(Game game) {
        if (game == null || !Objects.equals(game.getStatus(), 1)) throw BusinessException.conflict("只能向已发布游戏提交资源");
    }

    private void writeReviewState(Resource resource, int status, Long reviewer, LocalDateTime at, String reason) {
        int changed = baseMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<Resource>lambdaUpdate()
                .eq(Resource::getId, resource.getId()).eq(Resource::getRevision, resource.getRevision())
                .set(Resource::getStatus, status).set(Resource::getReviewedBy, reviewer)
                .set(Resource::getReviewedAt, at).set(Resource::getReviewReason, reason)
                .set(Resource::getUpdateTime, accountStatusService.now()));
        if (changed != 1) throw BusinessException.conflict("资源已更新，请刷新后重试");
    }

    private AdminResourceVO view(Resource resource) {
        return BeanUtils.copyProperties(resource, AdminResourceVO.class);
    }

    private void appendHistory(Resource resource, User actor, String action, Integer oldStatus,
                               Long oldRevision, String reason, List<String> fields) {
        ResourceReviewHistory history = new ResourceReviewHistory();
        history.setResourceId(resource.getId());
        history.setGameId(resource.getGameId());
        history.setOwnerId(resource.getUserId());
        history.setActorId(actor.getId());
        history.setActorName(actor.getUsername());
        history.setAction(action);
        history.setOldStatus(oldStatus);
        history.setNewStatus(action.equals("DELETE") ? null : resource.getStatus());
        history.setBeforeRevision(oldRevision);
        history.setAfterRevision(resource.getRevision());
        history.setResourceName(resource.getName());
        history.setReason(reason);
        history.setCreateTime(accountStatusService.now());
        // 字段名来自固定白名单，不能写入链接、口令及其前后值。
        history.setChangedFields(fields.stream().map(field -> "\"" + field + "\"").collect(Collectors.joining(",", "[", "]")));
        if (historyMapper.insert(history) != 1) throw BusinessException.internalError("资源操作历史保存失败");
    }

    private List<String> changedFields(Resource resource, ResourceAddDTO dto) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(resource.getName(), dto.getName())) fields.add("name");
        if (!sameOptionalText(resource.getRemark(), dto.getRemark())) fields.add("remark");
        if (!Objects.equals(resource.getDownloadUrl(), dto.getDownloadUrl())) fields.add("downloadUrl");
        if (!sameOptionalText(resource.getExtractCode(), dto.getExtractCode())) fields.add("extractCode");
        if (!sameOptionalText(resource.getPassword(), dto.getPassword())) fields.add("password");
        if (!Objects.equals(resource.getFileSize(), dto.getFileSize())) fields.add("fileSize");
        if (!Objects.equals(resource.getTypes(), dto.getTypes())) fields.add("types");
        if (!Objects.equals(resource.getLanguages(), dto.getLanguages())) fields.add("languages");
        if (!Objects.equals(resource.getPlatforms(), dto.getPlatforms())) fields.add("platforms");
        return fields;
    }

    // HTML空输入通常提交空串，数据库旧记录可能为NULL。两种空值不应单独触发重新审核。
    private boolean sameOptionalText(String stored, String submitted) {
        return Objects.toString(stored, "").equals(Objects.toString(submitted, ""));
    }

    private void validateLink(String link) {
        try {
            if (link == null) throw new IllegalArgumentException();
            URI uri = URI.create(link.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("资源链接须为有效的HTTP或HTTPS地址");
        }
    }

    @Override
    // 先完成资源及游戏可见性检查，再分别增加资源与游戏下载量并标脏。
    public String download(Long id) {
        Resource resource = requireAccessibleResource(id);
        Long gameId = resource.getGameId();
        int initialGameDownloads = statsService.getGameDownLoadCount(gameId);
        int initialResourceDownloads = statsService.getResourceDownLoadCount(id);
        redisCounterService.changeAndMark(StringUtils.format(GAME_DOWNLOAD_COUNT_KEY, gameId),
                initialGameDownloads, 1L, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);
        redisCounterService.changeAndMark(StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, id),
                initialResourceDownloads, 1L, RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY, id);


        return resource.getDownloadUrl();
    }

    // 资源本身和所属游戏都须为公开有效状态，隐藏资源的外链入口同步受限。
    private Resource requireAccessibleResource(Long id) {
        if (id == null || id <= 0) throw BusinessException.badRequest("资源ID必须为正整数");
        Resource resource = getById(id);
        if (resource == null || resource.getGameId() == null || !Objects.equals(resource.getStatus(), 1)) throw BusinessException.notFound("资源不存在");
        Game game = gameService.getById(resource.getGameId());
        if (game == null || !Objects.equals(game.getStatus(), 1)) throw BusinessException.notFound("资源不存在或所属游戏未发布");
        return resource;
    }

    // 批量装配发布者、托管头像路径与当前用户互动状态，响应结构与存储实体分离。
    private List<GameResourceVO> getVOS(List<Resource> records) {
        Set<Long> userIds = records.stream().map(Resource::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = null;
        if (CollUtils.isNotEmpty(userIds)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        }
        List<GameResourceVO> vos = new ArrayList<>(records.size());
        Long userId = UserContext.getUserId();
        Map<Long, Boolean> likedMap = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();
        if (userId != null && CollUtils.isNotEmpty(records)) {
            List<Long> ResourceIds = records.stream().map(Resource::getId).collect(Collectors.toList());
            likedMap = likeService.batchIsLiked(userId, LIKE_TYPE_RESOURCE, ResourceIds);
            likeCounts = likeService.getLikeCounts(ResourceIds, LIKE_TYPE_RESOURCE);
        }
        for (Resource r : records) {
            GameResourceVO vo = BeanUtils.copyBean(r, GameResourceVO.class);

            // 设置用户信息
            User user = userMap == null ? null : userMap.get(r.getUserId());
            if (user != null) {

                vo.setUsername(user.getUsername());
                vo.setAvatarUrl(com.catgal.server.storage.FileAccessPaths.avatar(user));
            }

            // 设置是否为当前用户评论
            if (vo != null) {
                vo.setIsMyResource(r.getUserId().equals(userId));
                // 设置点赞状态
                vo.setIsLike(likedMap.getOrDefault(r.getId(),false));
                // ✅ 简化：都设置，没有的取 0
                vo.setLikeCount(likeCounts.getOrDefault(r.getId(), 0L).intValue());

            }

            vos.add(vo);

        }
        return vos;
    }
}
