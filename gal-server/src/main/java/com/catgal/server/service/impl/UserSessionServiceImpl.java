/*
 * 阅读说明
 * 旧 AI 会话实体的基础 MyBatis-Plus 服务。
 * 当前持久化会话和消息状态机的主要业务实现位于 Python persistence 模块。
 */
package com.catgal.server.service.impl;

import com.catgal.server.domain.po.UserSession;
import com.catgal.server.mapper.UserSessionMapper;
import com.catgal.server.service.IUserSessionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
@Service
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession> implements IUserSessionService {

}
