package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.domain.dto.LoginDTO;
import com.catgal.common.domain.dto.UserUpdateDTO;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.*;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.*;
import com.catgal.server.service.userhome.UserHomeTabQueryRegistry;
import java.time.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserLoginAccountTest {
    private static final String PASSWORD = "isolated-login-9";
    private static final String HASH = PasswordUtils.encode(PASSWORD);
    private final UserMapper users = mock(UserMapper.class);
    private final JwtUtils jwt = mock(JwtUtils.class);
    private final UserAccountStatusServiceImpl status = spy(new UserAccountStatusServiceImpl(users,
            Clock.fixed(Instant.parse("2026-11-16T13:00:00Z"), ZoneOffset.UTC)));
    private final UserServiceImpl service = new UserServiceImpl(mock(ICommentService.class), mock(IGameReviewService.class),
            mock(IFavoriteFolderService.class), mock(IResourceService.class), mock(IUserCutePointsService.class),
            mock(IStorageFileService.class), jwt, status, mock(IFollowRecordService.class), new CurrentUserProvider(), mock(UserHomeTabQueryRegistry.class));
    private User user;
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        ReflectionTestUtils.setField(service, "baseMapper", users);
        user = new User(); user.setId(2L); user.setUsername("isolated-user"); user.setPassword(HASH); user.setRole(0); user.setStatus(1);
        user.setAuthVersion(0L);
        when(users.selectOne(any())).thenReturn(user); when(jwt.generateToken(2L, "isolated-user", 0, 0L)).thenReturn("isolated-token");
    }
    private LoginDTO request(String password) { var request = new LoginDTO(); request.setUsername("isolated-user"); request.setPassword(password); return request; }
    @AfterEach void clearContext() { UserContext.clear(); }
    @Test void activeAccountReceivesAToken() { assertEquals("isolated-token", service.login(request(PASSWORD)).getToken()); }

    @Test void loginReturnsPublicAvatarPathForExistingAndManagedProfileImages() {
        user.setAvatarUrl("https://example.invalid/avatar.png");
        assertEquals("https://example.invalid/avatar.png", service.login(request(PASSWORD)).getAvatarUrl());
        user.setAvatarFileId("0123456789abcdef0123456789abcdef");
        assertEquals("/api/files/avatars/0123456789abcdef0123456789abcdef",
                service.login(request(PASSWORD)).getAvatarUrl());
    }
    @Test void correctPasswordDoesNotBypassAPermanentBan() {
        user.setStatus(0);
        assertEquals(40301, assertThrows(BusinessException.class, () -> service.login(request(PASSWORD))).getCode());
        verify(jwt, never()).generateToken(anyLong(), anyString(), anyInt(), anyLong());
    }
    @Test void wrongPasswordDoesNotDiscloseTheAccountBan() {
        user.setStatus(0);
        assertEquals(401, assertThrows(BusinessException.class, () -> service.login(request("wrong-password"))).getHttpStatus());
        verify(status, never()).requireActive(any()); verify(jwt, never()).generateToken(anyLong(), anyString(), anyInt(), anyLong());
    }
    @Test void expiredBanAllowsLoginWithoutLosingItsRecordedMetadata() {
        user.setStatus(0); user.setBannedUntil(status.now().minusSeconds(1));
        assertEquals("isolated-token", service.login(request(PASSWORD)).getToken()); assertEquals(0, user.getStatus());
    }
    @Test void missingAccountCannotReceiveAToken() {
        when(users.selectOne(any())).thenReturn(null);
        assertEquals(401, assertThrows(BusinessException.class, () -> service.login(request(PASSWORD))).getHttpStatus());
        verify(jwt, never()).generateToken(anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test void changingPasswordWritesHashAndNextVersionTogether() {
        UserContext.setUser(UserInfo.builder().userId(2L).authVersion(0L).build());
        when(users.selectForUpdate(2L)).thenReturn(user);
        when(users.updateById(any(User.class))).thenReturn(1);
        var change = new UserUpdateDTO();
        change.setOldPassword(PASSWORD);
        change.setNewPassword("fresh-password-9");
        service.updateProfile(2L, change);
        var update = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).updateById(update.capture());
        assertEquals(1L, update.getValue().getAuthVersion());
        assertTrue(PasswordUtils.matches("fresh-password-9", update.getValue().getPassword()));
    }

    @Test void passwordChangeRejectsARequestAuthenticatedBeforeAnotherChange() {
        UserContext.setUser(UserInfo.builder().userId(2L).authVersion(0L).build());
        user.setAuthVersion(1L);
        when(users.selectForUpdate(2L)).thenReturn(user);
        var change = new UserUpdateDTO();
        change.setOldPassword(PASSWORD);
        change.setNewPassword("fresh-password-9");
        assertEquals(401, assertThrows(BusinessException.class, () -> service.updateProfile(2L, change)).getHttpStatus());
        verify(users, never()).updateById(any(User.class));
    }
}
