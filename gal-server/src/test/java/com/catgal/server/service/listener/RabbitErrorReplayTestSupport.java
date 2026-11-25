package com.catgal.server.service.listener;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer.*;

/** 隔离队列中的运维重放复现。调用方必须先声明本次测试专用的目标和错误队列。 */
final class RabbitErrorReplayTestSupport {
    private RabbitErrorReplayTestSupport() {}

    static boolean replayOne(RabbitTemplate template, String errorQueue, String exchange, String routingKey,
                             boolean redelivered, Consumer<Message> inspectOriginal) {
        return template.execute(channel -> {
            // 保留错误原件的投递，直到重放副本已被 Broker 确认并成功路由。
            var failed = channel.basicGet(errorQueue, false);
            assertNotNull(failed);
            long deliveryTag = failed.getEnvelope().getDeliveryTag();
            boolean accepted = false;
            try {
                assertEquals(redelivered, failed.getEnvelope().isRedeliver());
                var properties = new DefaultMessagePropertiesConverter().toMessageProperties(
                        failed.getProps(), failed.getEnvelope(), StandardCharsets.UTF_8.name());
                assertEquals(exchange, properties.getHeader(X_ORIGINAL_EXCHANGE));
                assertEquals(routingKey, properties.getHeader(X_ORIGINAL_ROUTING_KEY));
                var original = new Message(failed.getBody(), properties);
                inspectOriginal.accept(original);

                // 只移除失败诊断头。再次失败时恢复器生成新的诊断，业务数据和追踪号原样保留。
                properties.getHeaders().keySet().removeAll(List.of(X_ORIGINAL_EXCHANGE,
                        X_ORIGINAL_ROUTING_KEY, X_EXCEPTION_MESSAGE, X_EXCEPTION_STACKTRACE));
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                var publication = new CorrelationData();
                template.send(exchange, routingKey, original, publication);
                var confirmation = publication.getFuture().get(5, TimeUnit.SECONDS);
                accepted = confirmation.isAck() && publication.getReturned() == null;
                return accepted;
            } finally {
                if (accepted) channel.basicAck(deliveryTag, false);
                else channel.basicNack(deliveryTag, false, true);
            }
        });
    }

    static void awaitRequeued(RabbitAdmin admin, String errorQueue) throws InterruptedException {
        // basicNack 没有应答帧。另一个信道可能先读到旧计数，等待原件重新可投递。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int count = queued(admin, errorQueue);
        while (count == 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(25);
            count = queued(admin, errorQueue);
        }
        assertEquals(1, count);
    }

    static int queued(RabbitAdmin admin, String queue) {
        var properties = admin.getQueueProperties(queue);
        assertNotNull(properties);
        return ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }
}
