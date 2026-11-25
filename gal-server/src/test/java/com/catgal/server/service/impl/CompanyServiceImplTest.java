package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Company;
import com.catgal.server.domain.po.GameCompany;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.mapper.CompanyMapper;
import com.catgal.server.mapper.GameCompanyMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanyServiceImplTest {
    private final CompanyMapper companies = mock(CompanyMapper.class);
    private final GameCompanyMapper relations = mock(GameCompanyMapper.class);
    private final CompanyServiceImpl service = new CompanyServiceImpl(companies, relations);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Company.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), GameCompany.class);
    }

    private Company row() {
        Company row = new Company();
        row.setId(1L);
        row.setName("Key");
        row.setRevision(0L);
        return row;
    }

    @Test
    void createsNormalizedNameAndReturnsDatabaseDefaults() {
        when(companies.insert(any(Company.class))).thenAnswer(invocation -> {
            Company row = invocation.getArgument(0);
            assertEquals("Key", row.getName());
            row.setId(1L);
            return 1;
        });
        when(companies.selectById(1L)).thenReturn(row());
        assertEquals(1L, service.createCatalog("  Key  ").id());
    }

    @Test
    void duplicateCreatesAndUpdatesReturnConflict() {
        when(companies.insert(any(Company.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.createCatalog("Key")).getHttpStatus());
        when(companies.update(isNull(), any(Wrapper.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.updateCatalog(1L, "Key", 0L)).getHttpStatus());
    }

    @Test
    void staleUpdateReturnsConflictAndMissingRecordReturnsNotFound() {
        when(companies.selectById(1L)).thenReturn(row());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.updateCatalog(1L, "Changed", 0L)).getHttpStatus());
        when(companies.selectById(1L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.updateCatalog(1L, "Changed", 0L)).getHttpStatus());
    }

    @Test
    void referencedEntryCannotBeDeleted() {
        when(companies.selectRevisionForUpdate(1L)).thenReturn(0L);
        when(relations.selectCount(any(Wrapper.class))).thenReturn(2L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deleteCatalog(1L, 0L)).getHttpStatus());
        verify(companies, never()).delete(any(Wrapper.class));
    }

    @Test
    void staleDeleteAndForeignKeyConflictReturnConflict() {
        when(companies.selectRevisionForUpdate(1L)).thenReturn(1L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deleteCatalog(1L, 0L)).getHttpStatus());
        verifyNoInteractions(relations);
        when(companies.selectRevisionForUpdate(1L)).thenReturn(0L);
        when(relations.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(companies.delete(any(Wrapper.class)))
                .thenThrow(new DataIntegrityViolationException("reference"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deleteCatalog(1L, 0L)).getHttpStatus());
    }

    @Test
    void sharedLocksUseStableIdOrderAndRejectMissingEntries() {
        when(companies.selectIdsForShare(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        service.lockEntries(List.of(2L, 1L));
        verify(companies).selectIdsForShare(List.of(1L, 2L));
        when(companies.selectIdsForShare(List.of(1L, 2L))).thenReturn(List.of(1L));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.lockEntries(List.of(2L, 1L))).getHttpStatus());
    }

    @Test
    void rejectsInvalidIdsBeforeQuerying() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.getCatalog(0L)).getHttpStatus());
        verifyNoInteractions(companies);
    }

    @Test
    void paginationKeepsIdOrderingAndEnforcesPageSize() {
        CatalogPageQuery query = new CatalogPageQuery();
        query.setPageSize(1000);
        query.setSortBy("name");
        when(companies.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Company> page = invocation.getArgument(0);
            Wrapper<Company> wrapper = invocation.getArgument(1);
            assertEquals(100, page.getSize());
            assertTrue(wrapper.getSqlSegment().contains("ORDER BY id DESC"));
            page.setTotal(1);
            page.setRecords(List.of(row()));
            return page;
        });
        assertEquals(1L, service.pageCatalog(query).getList().get(0).id());
    }
}
