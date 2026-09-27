package ai.weixiu.mq;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.service.TaskStepRevisionService;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class TaskStepRevisionResultListener {
    private final ObjectMapper objectMapper;
    private final TaskStepRevisionService service;

    @RabbitListener(queues = RabbitMQConfig.TASK_STEP_REVISION_RESULT_QUEUE)
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            Map<String, Object> body = objectMapper.readValue(message.getBody(), new TypeReference<>() {});
            service.handleResult(body);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("[MQ] 步骤修订结果处理失败", e);
            channel.basicNack(tag, false, false);
        }
    }
}
