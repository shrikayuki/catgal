/*
 * 阅读说明
 * UserHomeTabQueryStrategy 属于用户主页按 tab 分发的查询策略链。
 * 注册表选择支持目标 tab 的策略，查询结果统一组装到用户主页响应。
 */
package com.catgal.server.service.userhome;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.enums.UserHomeTabEnum;

public interface UserHomeTabQueryStrategy {

    UserHomeTabEnum supports();

    PageDTO<?> query(UserHomeQueryDTO query, Long userId, boolean includePrivate);
}
