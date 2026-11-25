/*
 * 阅读说明
 * 封装 RabbitTemplate 的普通、延迟和异步发送入口。
 * CorrelationData 用于跟踪发送，可靠恢复能力仍取决于调用路径与 Broker 确认处理。
 */
package com.catgal.common.autoconfigure.mq;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;

@Slf4j
public class   RabbitMqHelper {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitTemplate confirmedTemplate;
    private final MessagePostProcessor processor = new BasicIdMessageProcessor();
    private final ThreadPoolTaskExecutor executor;

    public RabbitMqHelper(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        // 确认发送使用独立模板，共用连接和序列化协议，避免改变普通发送的 mandatory 行为。
        confirmedTemplate = new RabbitTemplate(rabbitTemplate.getConnectionFactory());
        confirmedTemplate.setMessageConverter(rabbitTemplate.getMessageConverter());
        confirmedTemplate.setMandatory(true);
        executor = new ThreadPoolTaskExecutor();
        //配置核心线程数
        executor.setCorePoolSize(10);
        //配置最大线程数
        executor.setMaxPoolSize(15);
        //配置队列大小
        executor.setQueueCapacity(99999);
        //配置线程池中的线程的名称前缀
        executor.setThreadNamePrefix("mq-async-send-handler");

        // 设置拒绝策略：当pool已经达到max size的时候，如何处理新任务
        // CALLER_RUNS：不在新线程中执行任务，而是有调用者所在的线程来执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        //执行初始化
        executor.initialize();
    }

    /**
     * 根据exchange和routingKey发送消息
     */
    // 调用 convertAndSend 时传入消息标识和 CorrelationData，Broker 发布确认具有异步生命周期。
    public <T> void send(String exchange, String routingKey, T t) {
        log.debug("准备发送消息，exchange：{}， RoutingKey：{}， message：{}", exchange, routingKey, t);
        // 1.关联本次发送的异步发布确认，同步调用异常会直接传给调用者。
        String id = UUID.randomUUID().toString(true);
        CorrelationData correlationData = new CorrelationData(id);
        // 2.replyTimeout 配置 RPC 回复等待，本单向发送路径在 convertAndSend 返回后结束。
        // Broker 确认、不可路由返回与超时恢复需由可靠事件发布流程接入 correlationData 结果。
        rabbitTemplate.setReplyTimeout(500);
        // 3.发送消息，同时设置消息id
        rabbitTemplate.convertAndSend(exchange, routingKey, t, processor, correlationData);
    }

    /** 只有收到 Broker ACK 且没有不可路由返回才正常结束。调用方负责保留失败的业务任务。 */
    public void sendConfirmed(String exchange, String routingKey, Object payload) {
        var factory = confirmedTemplate.getConnectionFactory();
        if (!factory.isPublisherConfirms() || !factory.isPublisherReturns()) {
            throw new AmqpException("确认发送需要启用 correlated confirms 和 publisher returns");
        }
        CorrelationData publication = new CorrelationData(UUID.randomUUID().toString(true));
        confirmedTemplate.convertAndSend(exchange, routingKey, payload, message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return processor.postProcessMessage(message);
        }, publication);
        try {
            CorrelationData.Confirm confirm = publication.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // RabbitMQ 对无法路由的消息也可能发送 ACK，Spring 先填充该消息的 return 再交付确认结果。
            if (publication.getReturned() != null) throw new AmqpException("消息未路由到队列");
            if (!confirm.isAck()) throw new AmqpException("RabbitMQ 未确认消息发布");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待消息发布确认被中断", failure);
        } catch (ExecutionException | TimeoutException failure) {
            // 超时后消息可能已经到达，重试的调用方需要允许重复通知。
            throw new AmqpException("未能在时限内确认消息发布", failure);
        }
    }

    /**
     * 根据exchange和routingKey发送消息，并且可以设置延迟时间
     */
    public <T> void sendDelayMessage(String exchange, String routingKey, T t, Duration delay) {
        // 1.关联本次发送的异步发布确认，同步调用异常会直接传给调用者。
        String id = UUID.randomUUID().toString(true);
        CorrelationData correlationData = new CorrelationData(id);
        // 2.RPC 回复等待时间与 Broker 发布确认拥有独立生命周期。
        rabbitTemplate.setReplyTimeout(500);
        // 3.发送消息，同时设置消息id
        rabbitTemplate.convertAndSend(exchange, routingKey, t, new DelayedMessageProcessor(delay), correlationData);
    }


    /**
     * 根据exchange和routingKey 异步发送消息，并指定一个延迟时间
     *
     * @param exchange   交换机
     * @param routingKey 路由KEY
     * @param t          数据
     * @param <T>        数据类型
     */
    // 在指定线程池发起发送并传递请求追踪号，异常在异步路径记录。
    public <T> void sendAsync(String exchange, String routingKey, T t, Long time) {
        String requestId = MDC.get(REQUEST_ID_HEADER);
        CompletableFuture.runAsync(() -> {
            try {
                MDC.put(REQUEST_ID_HEADER, requestId);
                // 发送延迟消息
                if (time != null && time > 0) {
                    sendDelayMessage(exchange, routingKey, t, Duration.ofMillis(time));
                } else {
                    send(exchange, routingKey, t);
                }
            } catch (Exception e) {
                log.error("推送消息异常，t:{},", t, e);
            }
        }, executor);
    }


    /**
     * 根据exchange和routingKey 异步发送消息
     *
     * @param exchange   交换机
     * @param routingKey 路由KEY
     * @param t          数据
     * @param <T>        数据类型
     */
    // 在指定线程池发起发送并传递请求追踪号，异常在异步路径记录。
    public <T> void sendAsync(String exchange, String routingKey, T t) {
        sendAsync(exchange, routingKey, t, null);
    }

}
