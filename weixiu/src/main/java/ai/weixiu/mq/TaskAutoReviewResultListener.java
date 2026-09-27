package ai.weixiu.mq;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.service.TaskAutoReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskAutoReviewResultListener {
    private final ObjectMapper objectMapper;
    private final TaskAutoReviewService service;

    @RabbitListener(queues = RabbitMQConfig.TASK_AUTO_REVIEW_RESULT_QUEUE)
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            Map<String,Object> body = objectMapper.readValue(message.getBody(), Map.class);
            service.processResult(body);
            channel.basicAck(tag, false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[MQ] 自动审核结果契约或状态错误，转人工并确认消息: {}", e.getMessage());
            try {
                Map<String,Object> body = objectMapper.readValue(message.getBody(), Map.class);
                service.markManualFromFailure(body, e.getMessage());
            } catch (Exception ignored) { }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("[MQ] 处理自动审核结果失败，重新入队", e);
            channel.basicNack(tag, false, true);
        }
    }
}
