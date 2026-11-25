package com.catgal.server.service.listener;

import Message.RewardCommittedMessage;
import com.catgal.server.service.IRewardOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.stereotype.Component;
import static com.catgal.server.service.impl.RewardOutboxServiceImpl.*;

@Component
@RequiredArgsConstructor
public class RewardCommittedListener {
    private final IRewardOutboxService outbox;

    /** 与旧积分队列隔离。失败沿用现有重试链，Outbox 仍会检测未完成并重新投递。 */
    @RabbitListener(containerFactory = "rewardListenerContainerFactory", bindings = @QueueBinding(value = @Queue(value = "reward.committed.v1.queue", durable = "true"),
            exchange = @Exchange(value = EXCHANGE, type = "direct"), key = ROUTING_KEY))
    public void consume(RewardCommittedMessage message) {
        if (message == null || message.version() != 1 || message.eventId() == null || message.eventId() <= 0)
            throw new AmqpRejectAndDontRequeueException("积分通知协议无效");
        outbox.project(message.eventId());
    }
}
