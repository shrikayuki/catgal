package com.catgal.server.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.controller.CutePointsRecordController;
import com.catgal.server.domain.po.CutePointsRecord;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.CutePointsRecordMapper;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.service.impl.CutePointsRecordServiceImpl;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CutePointsRecordSecurityTest {
    private final CutePointsRecordMapper mapper = mock(CutePointsRecordMapper.class);
    private final CurrentUserProvider users = new CurrentUserProvider();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CutePointsRecordController(
                    new CutePointsRecordServiceImpl(mapper, users)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new LoginRequiredInterceptor(users)).build();

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void anonymousReadIsRejected() throws Exception {
        UserContext.clear();
        mvc.perform(get("/cute-points-record/scroll")).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
    }

    @Test
    void suppliedUserIdCannotOverrideAuthenticatedOwner() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CutePointsRecord.class);
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        mvc.perform(get("/cute-points-record/scroll?userId=999&pageSize=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(false));
        ArgumentCaptor<Wrapper<CutePointsRecord>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(wrapper.capture());
        assertTrue(wrapper.getValue().getCustomSqlSegment().contains("user_id ="));
        var params = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CutePointsRecord>) wrapper.getValue())
                .getParamNameValuePairs().values();
        assertTrue(params.contains(6L));
        assertFalse(params.contains(999L));
    }

    @Test
    void invalidPageSizeIsRejectedBeforeQuerying() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        mvc.perform(get("/cute-points-record/scroll?pageSize=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/cute-points-record/scroll?pageSize=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }
}
