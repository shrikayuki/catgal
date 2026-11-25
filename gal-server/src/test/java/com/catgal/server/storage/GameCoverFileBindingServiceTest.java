package com.catgal.server.storage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IFileBindingService.Reference;
import com.catgal.server.service.impl.GameCoverFileBindingServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class GameCoverFileBindingServiceTest {
    private final GameMapper games = mock(GameMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final StorageFileMapper files = mock(StorageFileMapper.class);
    private final GameCoverFileBindingServiceImpl binding = new GameCoverFileBindingServiceImpl(games, users, files, new CurrentUserProvider());

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Game.class);
        User user = new User();
        user.setId(6L);
        user.setRole(2);
        when(users.selectById(6L)).thenReturn(user);
    }

    @AfterEach void clear() { UserContext.clear(); }

    @Test
    void gameReferenceIncludesTheOptimisticRevision() {
        Game game = new Game().setId(10L).setRevision(7L).setCoverFileId("a".repeat(32));
        when(games.selectForUpdate(10L)).thenReturn(game);
        assertEquals(new Reference("a".repeat(32), 7L), binding.lockReference(10));
        assertEquals("v1/game-covers", "v1/" + binding.objectPrefix());
        assertEquals("/api/files/game-covers/" + "a".repeat(32), binding.publicPath("a".repeat(32)));
    }

    @Test
    void administratorAndRevisionAreRequiredAtEachCommitBoundary() {
        Reference reference = new Reference(null, 7L);
        assertDoesNotThrow(() -> binding.authorizeUpload(10, 6, 7L, reference));
        assertEquals(409, assertThrows(BusinessException.class, () -> binding.authorizeUpload(10, 6, 6L, reference)).getHttpStatus());
        assertEquals(400, assertThrows(BusinessException.class, () -> binding.authorizeUpload(10, 6, null, reference)).getHttpStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> binding.authorizeUpload(10, 6, 7L, null)).getHttpStatus());
        User downgraded = new User();
        downgraded.setRole(0);
        when(users.selectById(6L)).thenReturn(downgraded);
        assertEquals(403, assertThrows(BusinessException.class, () -> binding.authorizeUpload(10, 6, 7L, reference)).getHttpStatus());
    }

    @Test
    void bindingWritesTheFileIdAndClearsLegacyUrlUnderRevisionGuard() {
        when(games.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertEquals(8L, binding.replaceReference(10, "b".repeat(32), 7L));
        ArgumentCaptor<Wrapper<Game>> argument = ArgumentCaptor.forClass(Wrapper.class);
        verify(games).update(isNull(), argument.capture());
        var wrapper = (LambdaUpdateWrapper<Game>) argument.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("revision"));
        assertTrue(wrapper.getSqlSet().contains("cover_file_id"));
        assertTrue(wrapper.getSqlSet().contains("cover_url"));
        assertTrue(wrapper.getParamNameValuePairs().values().containsAll(java.util.List.of(10L, 7L, 8L, "b".repeat(32))));
        when(games.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class, () -> binding.replaceReference(10, null, 7L)).getHttpStatus());
    }

    @Test
    void draftVisibilityUsesTheDatabaseRoleInsteadOfTokenClaims() {
        StorageFile file = new StorageFile();
        String id = "c".repeat(32);
        when(files.selectVisibleGameCover(id, false)).thenReturn(null);
        assertNull(binding.visibleFile(id));
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
        when(files.selectVisibleGameCover(id, true)).thenReturn(file);
        assertSame(file, binding.visibleFile(id));
        User downgraded = new User();
        downgraded.setRole(0);
        when(users.selectById(6L)).thenReturn(downgraded);
        assertNull(binding.visibleFile(id));
        verify(files, times(2)).selectVisibleGameCover(id, false);
    }
}
