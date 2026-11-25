package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.CutePointsRecord;
import com.catgal.server.mapper.CutePointsRecordMapper;
import com.catgal.server.security.CurrentUserProvider;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CutePointsRecordServiceImplTest {
    private final CutePointsRecordMapper mapper = mock(CutePointsRecordMapper.class);
    private final CutePointsRecordServiceImpl service = new CutePointsRecordServiceImpl(mapper, new CurrentUserProvider());

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CutePointsRecord.class);
        UserContext.setUser(UserInfo.builder().userId(6L).build());
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    private String cursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsOneExtraRowAndReturnsOpaqueStringCursor() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 15, 12, 0);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new CutePointsRecord().setId(9L).setCreateTime(time).setType(99).setBizId("old-biz"),
                new CutePointsRecord().setId(8L).setCreateTime(time),
                new CutePointsRecord().setId(7L).setCreateTime(time.minusSeconds(1))));
        ScrollPageQuery query = new ScrollPageQuery();
        query.setPageSize(2);
        query.setSortBy("unsafe_sort");
        var result = service.queryMyRecords(query);
        assertTrue(result.hasMore());
        assertEquals(List.of(9L, 8L), result.list().stream().map(row -> row.id()).toList());
        assertEquals(cursor(time + "|8"), result.nextCursor());
        assertEquals(99, result.list().get(0).type());
        assertEquals("old-biz", result.list().get(0).bizId());
        ArgumentCaptor<Wrapper<CutePointsRecord>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(wrapper.capture());
        assertTrue(wrapper.getValue().getCustomSqlSegment().contains("ORDER BY create_time DESC,id DESC"));
        assertTrue(wrapper.getValue().getCustomSqlSegment().endsWith("LIMIT 3"));
        assertFalse(wrapper.getValue().getSqlSelect().contains("user_id"));
    }

    @Test
    void timeCursorKeepsOwnerConstraintAndIncludesHistoricalNullTimes() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        ScrollPageQuery query = new ScrollPageQuery();
        query.setCursor(cursor("2026-11-15T12:00|9007199254740993"));
        var result = service.queryMyRecords(query);
        assertFalse(result.hasMore());
        assertNull(result.nextCursor());
        ArgumentCaptor<Wrapper<CutePointsRecord>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("user_id ="));
        assertTrue(sql.contains("create_time IS NULL"));
        assertTrue(sql.contains("create_time <"));
        var params = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CutePointsRecord>) wrapper.getValue())
                .getParamNameValuePairs().values();
        assertTrue(params.contains(6L));
        assertTrue(params.contains(9007199254740993L));
    }

    @Test
    void nullTimeCursorContinuesWithinNullTimeRows() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        ScrollPageQuery query = new ScrollPageQuery();
        query.setCursor(cursor("null|8"));
        service.queryMyRecords(query);
        ArgumentCaptor<Wrapper<CutePointsRecord>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("create_time IS NULL"));
        assertTrue(sql.contains("id <"));
        assertFalse(sql.contains(" OR "));
    }

    @Test
    void rejectsAnonymousAccessBeforeQuerying() {
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.queryMyRecords(new ScrollPageQuery())).getHttpStatus());
        verifyNoInteractions(mapper);
    }

    @Test
    void validatesCursorAndPageBoundsBeforeQuerying() {
        ScrollPageQuery query = new ScrollPageQuery();
        for (String value : List.of("bad!", cursor("invalid|1"), cursor("null|0"),
                cursor("2026-11-15T12:00|9223372036854775808"), cursor("0999-01-01T00:00|1"), "x".repeat(129))) {
            query.setCursor(value);
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.queryMyRecords(query)).getHttpStatus());
        }
        query.setCursor(null);
        for (Integer size : java.util.Arrays.asList(null, 0, 101)) {
            query.setPageSize(size);
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.queryMyRecords(query)).getHttpStatus());
        }
        verifyNoInteractions(mapper);
    }
}
