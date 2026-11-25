package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Tag;
import com.catgal.server.domain.po.GameTag;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.mapper.TagMapper;
import com.catgal.server.mapper.GameTagMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagServiceImplTest {
    private final TagMapper tags = mock(TagMapper.class);
    private final GameTagMapper relations = mock(GameTagMapper.class);
    private final TagServiceImpl service = new TagServiceImpl(tags, relations);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Tag.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), GameTag.class);
    }

    private Tag row() {
        Tag row = new Tag();
        row.setId(1);
        row.setName("Key");
        row.setRevision(0L);
        return row;
    }

    @Test
    void createsNormalizedNameAndReturnsDatabaseDefaults() {
        when(tags.insert(any(Tag.class))).thenAnswer(invocation -> {
            Tag row = invocation.getArgument(0);
            assertEquals("Key", row.getName());
            row.setId(1);
            return 1;
        });
        when(tags.selectById(1L)).thenReturn(row());
        assertEquals(1L, service.createCatalog("  Key  ").id());
    }

    @Test
    void duplicateCreatesAndUpdatesReturnConflict() {
        when(tags.insert(any(Tag.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.createCatalog("Key")).getHttpStatus());
        when(tags.update(isNull(), any(Wrapper.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.updateCatalog(1L, "Key", 0L)).getHttpStatus());
    }

    @Test
    void staleUpdateReturnsConflictAndMissingRecordReturnsNotFound() {
        when(tags.selectById(1L)).thenReturn(row());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.updateCatalog(1L, "Changed", 0L)).getHttpStatus());
        when(tags.selectById(1L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.updateCatalog(1L, "Changed", 0L)).getHttpStatus());
    }

    @Test
    void referencedEntryCannotBeDeleted() {
        when(tags.selectRevisionForUpdate(1L)).thenReturn(0L);
        when(relations.selectCount(any(Wrapper.class))).thenReturn(2L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deleteCatalog(1L, 0L)).getHttpStatus());
        verify(tags, never()).delete(any(Wrapper.class));
    }

    @Test
    void staleDeleteAndForeignKeyConflictReturnConflict() {
        when(tags.selectRevisionForUpdate(1L)).thenReturn(1L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deleteCatalog(1L, 0L)).getHttpStatus());
        verifyNoInteractions(relations);
        when(tags.selectRevisionForUpdate(1L)).thenReturn(0L);
        when(relations.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(tags.delete(any(Wrapper.class)))
                .thenThrow(new DataIntegrityViolationException("reference"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deleteCatalog(1L, 0L)).getHttpStatus());
    }

    @Test
    void sharedLocksUseStableIdOrderAndRejectMissingEntries() {
        when(tags.selectIdsForShare(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        service.lockEntries(List.of(2L, 1L));
        verify(tags).selectIdsForShare(List.of(1L, 2L));
        when(tags.selectIdsForShare(List.of(1L, 2L))).thenReturn(List.of(1L));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.lockEntries(List.of(2L, 1L))).getHttpStatus());
    }

    @Test
    void rejectsInvalidIdsBeforeQuerying() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.getCatalog(2147483648L)).getHttpStatus());
        verifyNoInteractions(tags);
    }

    @Test
    void paginationKeepsIdOrderingAndEnforcesPageSize() {
        CatalogPageQuery query = new CatalogPageQuery();
        query.setPageSize(1000);
        query.setSortBy("name");
        when(tags.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Tag> page = invocation.getArgument(0);
            Wrapper<Tag> wrapper = invocation.getArgument(1);
            assertEquals(100, page.getSize());
            assertTrue(wrapper.getSqlSegment().contains("ORDER BY id DESC"));
            page.setTotal(1);
            page.setRecords(List.of(row()));
            return page;
        });
        assertEquals(1L, service.pageCatalog(query).getList().get(0).id());
    }
}
