/*
 * 阅读说明
 * 账号有效状态的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 接口收敛业务能力，具体持久化、缓存或外部访问由实现类承担。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.User;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 登录、JWT 请求与后台共用账号状态规则。所有封禁期限按北京时间解释。 */
public interface IUserAccountStatusService {
    ZoneId TIME_ZONE = ZoneId.of("Asia/Shanghai");
    /**
     * 从数据库读取账号当前状态并验证可用性，供 JWT 请求和内部工具入口调用。
     * 账号不存在返回 401，仍处于封禁期返回 HTTP 403 和业务码 40301。
     */
    User requireActiveUser(Long userId);
    /** 校验调用方已读取的账号，登录流程与持锁后的管理员复核共用此方法。 */
    void requireActive(User user);
    /** 返回当前有效状态，1 为正常，0 为封禁。定时封禁到期后即时视为正常。 */
    int effectiveStatus(User user);
    /** 仅数据库状态为封禁且具有已到期截止时间时返回 true。 */
    boolean isBanExpired(User user);
    /** 返回站点北京时间，与数据库不携带时区的封禁期限进行比较。 */
    LocalDateTime now();
}
