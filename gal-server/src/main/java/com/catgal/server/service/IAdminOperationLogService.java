/*
 * 阅读说明
 * 管理员操作审计的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.AdminRedisOperation;
import com.catgal.server.domain.po.AdminOperationLog;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.query.admin.AdminContentOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminPrizeOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminRedisOperationLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;
import java.util.Map;
import java.util.Collection;
import java.util.function.Supplier;

/** 管理成功操作的审计能力，保存范围由操作类型和快照字段白名单约束。 */
public interface IAdminOperationLogService extends IService<AdminOperationLog> {
    /**
     * 加入调用方的业务事务写入审计，调用时必须已有事务。
     * before 和 after 由用户管理服务构造，写入异常应向外传播以回滚对应状态变更。
     */
    void appendUserChange(Long actorId, String operation, Long targetId,
                          Map<String, Object> before, Map<String, Object> after, String reason);

    /** 加入内容状态变更事务，只记录游戏编号和状态，不读取或保存正文。 */
    void appendContentStatusChange(Long actorId, String targetType, Long targetId,
                                   Long gameId, Integer oldStatus, int newStatus);

    /** 加入奖品维护事务。新增时 before 为空，更新时传入持锁读取的原快照。 */
    void appendPrizeChange(Long actorId, String operation, PrizePool before, PrizePool after);
    /** 批量读取目标用户最新审计 ID，未产生过审计的用户由调用方使用版本锚点 0。 */
    Map<Long, Long> userRevisions(Collection<Long> ids);
    /** 仅查询用户模块的操作记录，响应快照再次经过字段白名单过滤。 */
    PageDTO<AdminOperationLogVO> pageUserOperations(AdminOperationLogQuery query);

    /** 单独分页查询评论和评价的隐藏、恢复记录。 */
    PageDTO<AdminOperationLogVO> pageContentOperations(AdminContentOperationLogQuery query);

    PageDTO<AdminOperationLogVO> pagePrizeOperations(AdminPrizeOperationLogQuery query);

    /** 写入独立提交的运维请求记录，再执行现有流程并记录完成结果。 */
    <T> T auditRedisMaintenance(Long actorId, AdminRedisOperation operation, Long targetId, Supplier<T> action);

    /** 查询手动修复和同步的执行记录。 */
    PageDTO<AdminOperationLogVO> pageRedisOperations(AdminRedisOperationLogQuery query);
}
