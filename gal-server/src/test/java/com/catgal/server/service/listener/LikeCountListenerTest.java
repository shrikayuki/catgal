package com.catgal.server.service.listener;

import Message.LikeCountMessage;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.service.ILikeRecordService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeCountListenerTest {
    private final ILikeRecordService service = mock(ILikeRecordService.class);
    private final LikeCountListener listener = new LikeCountListener(service);

    private LikeCountMessage message(String type, Long id, Long count) {
        return LikeCountMessage.builder().bizType(type).bizId(id).likeCount(count).build();
    }

    @Test void threeBindingsForwardOnlyTargetIdsToTheCurrentStateService() {
        listener.listenCommentLikeTimesChange(List.of(message("comment", 20L, 999L)));
        listener.listenReviewLikeTimesChange(List.of(message("review", 21L, 0L)));
        listener.listenResourceLikeTimesChange(List.of(message("resource", 22L, 3L)));
        verify(service).syncLikeCounts("comment", List.of(20L));
        verify(service).syncLikeCounts("review", List.of(21L));
        verify(service).syncLikeCounts("resource", List.of(22L));
        verifyNoMoreInteractions(service);
    }

    @Test void emptyPayloadRetainsTheExistingNoOpBehavior() {
        listener.listenCommentLikeTimesChange(null);
        listener.listenReviewLikeTimesChange(List.of());
        verifyNoInteractions(service);
    }

    @Test void invalidFieldsAreRejectedBeforeAnyBusinessWork() {
        List<Consumer<LikeCountMessage>> changes = List.of(value -> value.setBizId(null),
                value -> value.setBizId(0L), value -> value.setBizId(-1L), value -> value.setLikeCount(null),
                value -> value.setLikeCount(-1L), value -> value.setLikeCount((long) Integer.MAX_VALUE + 1),
                value -> value.setBizType(null), value -> value.setBizType("resource"));
        for (var change : changes) {
            LikeCountMessage message = message("comment", 20L, 3L);
            change.accept(message);
            assertThrows(AmqpRejectAndDontRequeueException.class,
                    () -> listener.listenCommentLikeTimesChange(List.of(message)));
        }
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> listener.listenCommentLikeTimesChange(Arrays.asList((LikeCountMessage) null)));
        verifyNoInteractions(service);
    }

    @Test void aMixedInvalidBatchCannotPartiallyReachTheService() {
        List<LikeCountMessage> batch = new ArrayList<>();
        batch.add(message("comment", 20L, 1L));
        batch.add(message("review", 21L, 1L));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.listenCommentLikeTimesChange(batch));
        verifyNoInteractions(service);
    }

    @Test void repeatedTargetsWithinOneBatchAreReconciledOnce() {
        listener.listenCommentLikeTimesChange(List.of(message("comment", 20L, 1L),
                message("comment", 20L, 8L), message("comment", 21L, 0L)));
        verify(service).syncLikeCounts("comment", List.of(20L, 21L));
    }

    @Test void databaseAndIncompleteCacheFailuresPropagateToRetry() {
        var databaseFailure = new DataAccessResourceFailureException("isolated database failure");
        doThrow(databaseFailure).when(service).syncLikeCounts(anyString(), anyList());
        assertSame(databaseFailure, assertThrows(DataAccessResourceFailureException.class,
                () -> listener.listenCommentLikeTimesChange(List.of(message("comment", 20L, 1L)))));
        var cacheFailure = new BusinessException(503, 503, "点赞缓存待恢复，请稍后重试");
        doThrow(cacheFailure).when(service).syncLikeCounts(anyString(), anyList());
        assertSame(cacheFailure, assertThrows(BusinessException.class,
                () -> listener.listenCommentLikeTimesChange(List.of(message("comment", 20L, 1L)))));
    }

    @Test void everyBindingUsesTheDedicatedRetryFactory() throws Exception {
        for (String kind : List.of("Comment", "Review", "Resource")) {
            var method = LikeCountListener.class.getMethod("listen" + kind + "LikeTimesChange", List.class);
            assertEquals("likeCountListenerContainerFactory", method.getAnnotation(RabbitListener.class).containerFactory());
        }
    }
}
