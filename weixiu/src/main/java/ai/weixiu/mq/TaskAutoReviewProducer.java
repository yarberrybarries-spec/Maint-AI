package ai.weixiu.mq;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.entity.TaskAutoReview;
import ai.weixiu.entity.MaintenanceTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskAutoReviewProducer {
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public Map<String,Object> envelope(MaintenanceTask task, TaskAutoReview review) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "task-auto-review.v1");
        body.put("promptVersion", "task-auto-review.v1");
        body.put("requestId", review.getRequestId());
        body.put("taskId", task.getId());
        body.put("evidenceVersion", task.getEvidenceVersion());
        body.put("snapshot", task.getEvidenceBundle());
        return body;
    }

    public void publish(MaintenanceTask task, TaskAutoReview review) {
        Map<String,Object> body = envelope(task, review);
        CorrelationData correlation = new CorrelationData(review.getRequestId());
        rabbitTemplate.convertAndSend(RabbitMQConfig.TASK_EXCHANGE, RabbitMQConfig.TASK_AUTO_REVIEW_KEY, body, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ NACK: " + confirm.getReason());
            if (correlation.getReturned() != null) throw new IllegalStateException("RabbitMQ returned unroutable review request");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待自动审核消息确认被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("发布自动审核消息失败", e);
        }
        log.info("[MQ] 发布任务自动审核 taskId={} version={} requestId={}", task.getId(), task.getEvidenceVersion(), review.getRequestId());
    }
}
