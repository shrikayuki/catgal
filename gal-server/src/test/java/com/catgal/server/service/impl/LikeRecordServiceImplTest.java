package com.catgal.server.service.impl;

import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.LikeTargetValidator;
import com.catgal.server.service.RedisDirtySetProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LikeRecordServiceImplTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RabbitMqHelper mqHelper = mock(RabbitMqHelper.class);
    private final RedisDirtySetProcessor dirtySetProcessor = mock(RedisDirtySetProcessor.class);
    private final LikeTargetValidator targetValidator = mock(LikeTargetValidator.class);
    private final LikeRecordServiceImpl service = new LikeRecordServiceImpl(
            redisTemplate,
            mqHelper,
            dirtySetProcessor,
            targetValidator,
            new CurrentUserProvider()
    );

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void anonymousLikeCannotWriteNullMemberToRedis() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.like(likeDto("comment", 1L)));

        assertEquals(401, exception.getHttpStatus());
        verifyNoInteractions(redisTemplate, targetValidator);
    }

    @Test
    void anonymousUnlikeCannotWriteNullMemberToRedis() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.unlike(likeDto("comment", 1L)));

        assertEquals(401, exception.getHttpStatus());
        verifyNoInteractions(redisTemplate, targetValidator);
    }

    @Test
    void unsupportedUnlikeTypeIsRejectedBeforeRedisMutation() {
        UserContext.setUser(UserInfo.builder().userId(9L).build());
        LikeDTO dto = likeDto("unsupported", 1L);
        org.mockito.Mockito.doThrow(BusinessException.badRequest("不支持的点赞类型"))
                .when(targetValidator).requireSupportedType("unsupported");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.unlike(dto));

        assertEquals(400, exception.getHttpStatus());
        verifyNoInteractions(redisTemplate);
    }

    private LikeDTO likeDto(String type, Long targetId) {
        LikeDTO dto = new LikeDTO();
        dto.setType(type);
        dto.setTargetId(targetId);
        return dto;
    }
}
