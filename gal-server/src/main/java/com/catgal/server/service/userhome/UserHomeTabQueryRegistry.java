/*
 * 阅读说明
 * UserHomeTabQueryRegistry 属于用户主页按 tab 分发的查询策略链。
 * 注册表选择支持目标 tab 的策略，查询结果统一组装到用户主页响应。
 */
package com.catgal.server.service.userhome;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.enums.UserHomeTabEnum;
import com.catgal.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class UserHomeTabQueryRegistry {

    private final Map<UserHomeTabEnum, UserHomeTabQueryStrategy> strategies;

    public UserHomeTabQueryRegistry(List<UserHomeTabQueryStrategy> strategies) {
        EnumMap<UserHomeTabEnum, UserHomeTabQueryStrategy> registered = new EnumMap<>(UserHomeTabEnum.class);
        for (UserHomeTabQueryStrategy strategy : strategies) {
            UserHomeTabQueryStrategy previous = registered.put(strategy.supports(), strategy);
            if (previous != null) {
                throw new IllegalStateException("重复的用户主页查询策略: " + strategy.supports());
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    // 根据 tab 找到唯一支持的策略，非法 tab 统一返回参数错误。
    public PageDTO<?> query(UserHomeQueryDTO query, Long userId, boolean includePrivate) {
        Integer tabCode = query.getTab();
        if (!UserHomeTabEnum.isValid(tabCode)) {
            throw BusinessException.badRequest("不支持的用户主页 Tab");
        }
        UserHomeTabEnum tab = UserHomeTabEnum.fromCode(tabCode);
        UserHomeTabQueryStrategy strategy = strategies.get(tab);
        if (strategy == null) {
            throw BusinessException.internalError("用户主页查询策略未配置: " + tab);
        }
        return strategy.query(query, userId, includePrivate);
    }
}
