/*
 * 阅读说明
 * 游戏资源的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameResourceVO;
import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.vo.ResourceUrlVO;
import com.catgal.server.domain.po.Resource;
import jakarta.validation.Valid;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.server.domain.dto.ResourceEditDTO;
import com.catgal.server.domain.dto.admin.ResourceReviewDTO;
import com.catgal.server.domain.po.ResourceReviewHistory;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.query.admin.AdminResourceReviewLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;

/**
 * <p>
 * 游戏资源表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IResourceService extends IService<Resource> {

    /** 本人创作者/管理员读取完整编辑快照，包含版本和最近审核信息。 */
    AdminResourceVO getEditable(Long id);
    /** 固定锁序下校验归属和版本，创作者内容变更后重新进入待审。 */
    AdminResourceVO editResource(Long id, ResourceEditDTO dto);
    /** 失效资源重新送审；重复提交当前待审版本保持幂等。 */
    AdminResourceVO submitResource(Long id, Long revision);
    /** 管理员按版本审核，状态、审核信息、历史与统计任务同事务提交。 */
    AdminResourceVO reviewResource(Long id, ResourceReviewDTO dto);
    /** 按资源ID稳定分页，本人和管理员可以查看物理删除后保留的历史。 */
    PageDTO<ResourceReviewHistory> history(Long id, AdminPageQuery query);

    /** 管理员跨资源查询审核记录，历史通过、驳回和退回待审包含已删除资源。 */
    PageDTO<AdminOperationLogVO> pageAdminReviewOperations(AdminResourceReviewLogQuery query);

    /** 验证游戏已发布，再查询有效资源，作者和互动状态通过批量查询补充。 */
    GameConnectVO<GameResourceVO> pageGameResource(GameQuery query);

    /** 提取码和下载地址读取前复用资源及所属游戏可见性检查。 */
    ResourceUrlVO getResourceUrl(Long id);

    /** 解析外链并校验当前创作者权限，管理员资源直接有效，其他创作者资源进入待审状态。 */
    void addResource(@Valid ResourceAddDTO dto);

    /** 核对归属后删除资源及点赞关系，保留操作历史，由持久任务重算公开资源数。 */
    void deleteResource(Long id);

    /** 先完成资源及游戏可见性检查，再分别增加资源与游戏下载量并标脏。 */
    String download(Long id);
}
