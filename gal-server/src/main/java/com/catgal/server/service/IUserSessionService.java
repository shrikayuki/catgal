/*
 * 阅读说明
 * AI 用户会话的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.UserSession;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
public interface IUserSessionService extends IService<UserSession> {

}
