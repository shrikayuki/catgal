/*
 * 阅读说明
 * 统一账号有效状态判定，登录、JWT 请求和内部工具共用。
 * 期限统一北京时间，到期直接恢复有效访问，原始封禁字段保留供后台核对。
 */
package com.catgal.server.service.impl;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IUserAccountStatusService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.catgal.common.constants.ErrorInfo.Code.ACCOUNT_BANNED;

/**
 * 账号有效状态的统一判定入口，供登录、携带 JWT 的请求和内部业务工具共用。
 * 判定只读取数据库，定时到期直接影响结果，原始封禁信息保留供后台核对。
 */
@Service
public class UserAccountStatusServiceImpl implements IUserAccountStatusService {
    private final UserMapper userMapper;
    private final Clock clock;

    @Autowired
    public UserAccountStatusServiceImpl(UserMapper userMapper) {
        this(userMapper, Clock.systemUTC());
    }

    // 固定 Clock 用于精确验证到期边界，生产统一以即时 UTC 时间转换到站点时区。
    UserAccountStatusServiceImpl(UserMapper userMapper, Clock clock) {
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Override
    public User requireActiveUser(Long userId) {
        if (userId == null || userId <= 0) throw BusinessException.unauthorized("登录凭证缺少用户信息");
        // 每次读取精简状态字段，管理员的封禁或解封会在下一次请求认证时生效。
        User user = userMapper.selectAccountState(userId);
        requireActive(user);
        return user;
    }

    @Override
    public void requireActive(User user) {
        if (user == null) throw BusinessException.unauthorized("账号已不存在，请重新登录");
        if (effectiveStatus(user) != 1) {
            // 固定错误文本避免把管理员填写的私人原因写入普通请求日志和响应。
            throw new BusinessException(ACCOUNT_BANNED, 403, "账号已被封禁，请联系管理员");
        }
    }

    @Override
    public int effectiveStatus(User user) {
        if (user == null) return 0;
        // 允许正常账号及已到期封禁账号，其余状态值按受限处理。
        return Objects.equals(user.getStatus(), 1) || isBanExpired(user) ? 1 : 0;
    }

    @Override
    public boolean isBanExpired(User user) {
        // 到期即生效，不依赖定时任务。保留数据库最近封禁信息用于后台核对与历史审计。
        return user != null && Objects.equals(user.getStatus(), 0) && user.getBannedUntil() != null
                && !user.getBannedUntil().isAfter(now());
    }

    // Linux 与 Windows 进程默认时区可不同，统一使用同一 Clock 和显式站点时区。
    @Override
    public LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), TIME_ZONE); }
}
