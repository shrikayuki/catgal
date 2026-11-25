package com.catgal.common.autoconfigure.mq;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecovererWithConfirms;

/** 错误队列确认接收后才结束恢复，否则保留本次源队列投递。 */
public class ConfirmedErrorMessageRecoverer implements MessageRecoverer {
    private static final long CONFIRM_TIMEOUT_MS = 5000;
    private static final long REQUEUE_BACKOFF_MS = 3000;

    private final ConnectionFactory connectionFactory;
    private final RepublishMessageRecovererWithConfirms delegate;

    public ConfirmedErrorMessageRecoverer(ConnectionFactory connectionFactory, String exchange, String routingKey) {
        this.connectionFactory = connectionFactory;
        // 独立模板只负责转发原始消息，避免修改普通发送模板或重新序列化消息体。
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        delegate = new RepublishMessageRecovererWithConfirms(template, exchange, routingKey, ConfirmType.CORRELATED);
        delegate.setConfirmTimeout(CONFIRM_TIMEOUT_MS);
    }

    @Override
    public void recover(Message message, Throwable cause) {
        try {
            if (!connectionFactory.isPublisherConfirms() || !connectionFactory.isPublisherReturns()) {
                throw new AmqpException("错误队列转发需要启用 correlated confirms 和 publisher returns");
            }
            // 保留 Spring AMQP 写入的原交换机、路由键和异常头。正常返回后才由容器确认原消息。
            delegate.recover(message, cause);
        } catch (RuntimeException forwardingFailure) {
            // 错误路由持续不可用时暂停当前消费线程片刻，避免退回后立即循环重投。
            try {
                TimeUnit.MILLISECONDS.sleep(REQUEUE_BACKOFF_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // 使用转发失败作为原因。业务异常即使要求原投递不再重试，也必须等错误副本入队后才能确认。
            throw new ImmediateRequeueAmqpException("错误消息转发未确认，原消息重新排队", forwardingFailure);
        }
    }
}
