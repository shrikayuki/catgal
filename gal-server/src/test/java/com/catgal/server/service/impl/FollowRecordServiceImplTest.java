package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.domain.po.FollowRecord;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.FollowRecordMapper;
import com.catgal.server.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowRecordServiceImplTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final FollowRecordMapper followMapper = mock(FollowRecordMapper.class);
    private final FollowRecordServiceImpl service = new FollowRecordServiceImpl(redisTemplate, userMapper);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void duplicateDatabaseRowIsHandledAsIdempotentSuccessAndCacheIsRepaired() {
        ReflectionTestUtils.setField(service, "baseMapper", followMapper);
        UserContext.setUser(UserInfo.builder().userId(1L).build());
        User target = new User();
        target.setId(2L);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(followMapper.insert(any(FollowRecord.class)))
                .thenThrow(new DuplicateKeyException("duplicate follow"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.follow(2L));

        verify(followMapper).insert(any(FollowRecord.class));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
