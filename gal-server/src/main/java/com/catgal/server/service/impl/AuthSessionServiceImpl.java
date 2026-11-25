package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.JwtUtils;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.RevokedAuthTokenMapper;
import com.catgal.server.service.IAuthSessionService;
import com.catgal.server.service.IUserAccountStatusService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import static com.catgal.common.constants.JwtClaimsConstant.AUTH_VERSION;
import static com.catgal.common.constants.JwtClaimsConstant.USER_ID;

@Service
@RequiredArgsConstructor
public class AuthSessionServiceImpl implements IAuthSessionService {
    private final JwtUtils jwt;
    private final IUserAccountStatusService accounts;
    private final RevokedAuthTokenMapper revokedTokens;

    @Override
    public UserInfo authenticate(String token) {
        Credential credential = readCredential(token);
        // 封禁及角色继续使用数据库现态。数据库异常向外传播，避免故障时误报用户凭据失效。
        User account = accounts.requireActiveUser(credential.userId());
        if (!Objects.equals(account.getAuthVersion(), credential.version())
                || revokedTokens.isRevoked(digest(token))) {
            throw BusinessException.unauthorized("登录凭证已失效，请重新登录");
        }
        return UserInfo.builder().userId(account.getId()).username(account.getUsername())
                .role(account.getRole()).authVersion(credential.version()).build();
    }

    @Override
    public void logout(String token) {
        Credential credential = readCredential(token);
        if (!Objects.equals(credential.userId(), UserContext.getUserId())) {
            throw BusinessException.unauthorized("登录凭证无效");
        }
        // 旧凭据也按签名字节的摘要撤销。并发退出的重复插入由主键收敛。
        revokedTokens.revoke(digest(token), LocalDateTime.ofInstant(credential.expiresAt(), ZoneOffset.UTC));
    }

    @Scheduled(fixedDelay = 60000)
    public void purgeExpiredTokens() {
        // 依赖到期索引，每轮最多清 1000 条，避免长事务扩大数据库锁范围。
        revokedTokens.deleteExpiredBatch();
    }

    private Credential readCredential(String token) {
        Claims claims = jwt.parseToken(token);
        if (claims != null) {
            try {
                Long userId = claims.get(USER_ID, Long.class);
                Long version = claims.get(AUTH_VERSION, Long.class);
                // V22 前签发的凭据没有版本，按初始版本 0 比较，到期时间保持原值。
                long effectiveVersion = version == null ? 0 : version;
                if (userId != null && userId > 0 && effectiveVersion >= 0 && claims.getExpiration() != null
                        && claims.getExpiration().toInstant().isAfter(Instant.now())) {
                    return new Credential(userId, effectiveVersion, claims.getExpiration().toInstant());
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // 只返回固定错误信息，日志不带 Token 或声明。
            }
        }
        throw BusinessException.unauthorized("登录凭证无效或已过期");
    }

    private String digest(String token) {
        try {
            // 使用已验证的签名字节生成固定摘要，文本编码差异不会改变撤销身份。
            byte[] signature = Base64.getUrlDecoder().decode(token.substring(token.lastIndexOf('.') + 1));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(signature));
        } catch (IllegalArgumentException failure) {
            throw BusinessException.unauthorized("登录凭证无效");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record Credential(Long userId, Long version, Instant expiresAt) {}
}
