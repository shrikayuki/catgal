package com.catgal.server.service.impl;

import Message.LikeCountMessage;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.LikeTargetValidator;
import com.catgal.server.service.RedisDirtySetProcessor;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static com.catgal.common.constants.MqConstants.Exchange.LIKE_COUNT_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.COMMENT_LIKED_TIMES_KEY;
import static com.catgal.common.constants.RedisConstant.LIKE_COUNT_CHANGE_SET_KEY;
import static com.catgal.common.utils.StringUtils.format;

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

    @Test
    void brokerConfirmationFailureEscapesTheDirtyBatchCallbackEvenForZeroCounts() {
        when(dirtySetProcessor.processBatch(eq(format(LIKE_COUNT_CHANGE_SET_KEY, "comment")), eq(40), eq(false), any()))
                .thenAnswer(call -> {
                    Consumer<List<String>> batch = call.getArgument(3);
                    batch.accept(List.of("20"));
                    return 1;
                });
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(List.of(0L, 0L)));
        var notification = LikeCountMessage.builder().bizType("comment").bizId(20L).likeCount(0L).build();
        var failure = new AmqpException("isolated publish failure");
        doThrow(failure).when(mqHelper).sendConfirmed(LIKE_COUNT_EXCHANGE, COMMENT_LIKED_TIMES_KEY, List.of(notification));

        assertSame(failure, assertThrows(AmqpException.class, () -> service.readLikedTimesAndSendMessage("comment", 40)));
    }

    private LikeDTO likeDto(String type, Long targetId) {
        LikeDTO dto = new LikeDTO();
        dto.setType(type);
        dto.setTargetId(targetId);
        return dto;
    }
}
