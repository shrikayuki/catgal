package com.catgal.server.storage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.AiBot;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.mapper.AiBotMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IFileBindingService.Reference;
import com.catgal.server.service.impl.AiBotImageFileBindingServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class AiBotImageFileBindingServiceTest {
    private final AiBotMapper bots = mock(AiBotMapper.class);
    private final StorageFileMapper files = mock(StorageFileMapper.class);
    private final CurrentUserProvider user = new CurrentUserProvider();
    private final AiBotImageFileBindingServiceImpl avatar = binding(AiBotImageKind.AVATAR);
    private final AiBotImageFileBindingServiceImpl portrait = binding(AiBotImageKind.PORTRAIT);
    private final String id = "a".repeat(32);
    private AiBot bot;

    private AiBotImageFileBindingServiceImpl binding(AiBotImageKind kind) {
        return new AiBotImageFileBindingServiceImpl(kind, bots, files, user);
    }

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiBot.class);
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        bot = new AiBot();
        bot.setId(10L);
        bot.setUserId(6L);
        bot.setKind("custom");
        bot.setStatus(1);
        bot.setRevision(7L);
        bot.setAvatarUrl(AiBotImageKind.AVATAR.path(id));
        bot.setPortraitUrl("https://assets.example.com/legacy.webp");
        when(bots.selectById(10L)).thenReturn(bot);
        when(bots.selectForUpdate(10L)).thenReturn(bot);
    }

    @AfterEach void clear() { UserContext.clear(); }

    @Test void referencesDistinguishAvatarPortraitAndLegacyImages() {
        assertEquals(new Reference(id, 7L), avatar.lockReference(10));
        assertEquals(new Reference(null, 7L), portrait.lockReference(10));
        assertEquals(new Reference(id, 7L), avatar.currentReference(10));
        assertEquals("AI_BOT_AVATAR", avatar.purpose());
        assertEquals("ai-bot-portraits", portrait.objectPrefix());
    }

    @Test void uploadRequiresAnActiveOwnedCustomBotAndCurrentRevision() {
        var reference = new Reference(id, 7L);
        assertDoesNotThrow(() -> avatar.authorizeUpload(10, 6, 7L, reference));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> avatar.authorizeUpload(10, 8, 7L, reference)).getHttpStatus());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> avatar.authorizeUpload(10, 6, 6L, reference)).getHttpStatus());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> avatar.authorizeUpload(10, 6, null, reference)).getHttpStatus());
        bot.setKind("platform");
        assertEquals(403, assertThrows(BusinessException.class,
                () -> avatar.authorizeUpload(10, 6, 7L, reference)).getHttpStatus());
        bot.setKind("custom");
        bot.setStatus(0);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> avatar.authorizeUpload(10, 6, 7L, reference)).getHttpStatus());
    }

    @Test void replacementsGuardOwnerStatusAndRevisionAndChangeOnlyTheRequestedImage() {
        when(bots.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertEquals(8L, avatar.replaceReference(10, id, 7L));
        var argument = ArgumentCaptor.forClass(Wrapper.class);
        verify(bots).update(isNull(), argument.capture());
        var update = (LambdaUpdateWrapper<AiBot>) argument.getValue();
        assertTrue(update.getCustomSqlSegment().contains("user_id"));
        assertTrue(update.getCustomSqlSegment().contains("revision"));
        assertTrue(update.getCustomSqlSegment().contains("status"));
        assertTrue(update.getSqlSet().contains("avatar_url"));
        assertFalse(update.getSqlSet().contains("portrait_url"));
        assertTrue(update.getParamNameValuePairs().values().contains(AiBotImageKind.AVATAR.path(id)));
        when(bots.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> portrait.replaceReference(10, null, 7L)).getHttpStatus());
    }

    @Test void privateReadsUseTheAuthenticatedOwnerAndRejectAnonymousAccess() {
        StorageFile file = new StorageFile();
        when(files.selectOwnedAiBotImage(id, "AI_BOT_AVATAR", 6L)).thenReturn(file);
        assertSame(file, avatar.visibleFile(id));
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class, () -> avatar.visibleFile(id)).getHttpStatus());
    }

    @Test void managedPathsRejectOtherKindsTraversalAndSuffixes() {
        assertEquals(id, AiBotImageKind.AVATAR.fileId(AiBotImageKind.AVATAR.path(id)));
        assertNull(AiBotImageKind.AVATAR.fileId(AiBotImageKind.PORTRAIT.path(id)));
        assertNull(AiBotImageKind.AVATAR.fileId(AiBotImageKind.AVATAR.path(id) + "?x=1"));
        assertNull(AiBotImageKind.AVATAR.fileId("/api/files/ai-bot-avatars/../" + id));
        assertThrows(IllegalArgumentException.class, () -> AiBotImageKind.AVATAR.path("../file"));
    }
}
