/*
 * 阅读说明
 * 后台用户管理的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminUserStatusUpdateDTO;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminUserVO;

/** 管理员用户维护入口，Controller 和跨业务调用通过此接口使用统一权限与事务规则。 */
public interface IAdminUserService extends IService<User> {
    /** 按有效账号状态筛选，批量返回每个用户当前的状态校验版本。 */
    PageDTO<AdminUserVO> pageUsers(AdminPageQuery query);
    /** 表单提交前或发生 409 后读取最新状态，stateVersion 随响应返回。 */
    AdminUserVO getUser(Long userId);
    /**
     * 在事务中调整角色并追加成功审计。
     * stateVersion 可为空以兼容历史调用，新客户端应提交详情返回的版本。
     */
    AdminUserVO updateRole(Long userId, Integer role, String stateVersion);
    /** 封禁或解封必须提交原因与当前状态版本，业务状态和审计共同提交或回滚。 */
    AdminUserVO updateStatus(Long userId, AdminUserStatusUpdateDTO request);
}
