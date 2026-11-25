package com.catgal.server.task;

import com.catgal.server.service.ILikeRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LikedTimesCheckTaskTest {
    @Test
    void oneUnroutableTypeDoesNotBlockTheOtherTypes() {
        var records = mock(ILikeRecordService.class);
        doThrow(new AmqpException("isolated unroutable message")).when(records).readLikedTimesAndSendMessage("comment", 40);

        new LikedTimesCheckTask(records).checkLikedTimes();

        verify(records).readLikedTimesAndSendMessage("review", 40);
        verify(records).readLikedTimesAndSendMessage("resource", 40);
    }
}
