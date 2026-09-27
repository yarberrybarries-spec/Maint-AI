package ai.weixiu.service;

import ai.weixiu.entity.MaintenanceTask;
import ai.weixiu.entity.TaskAutoReview;
import ai.weixiu.mapper.MaintenanceTaskMapper;
import ai.weixiu.mapper.TaskAutoReviewMapper;
import ai.weixiu.mq.TaskAutoReviewProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskAutoReviewService {
    private final TaskAutoReviewMapper reviewMapper;
    private final MaintenanceTaskMapper taskMapper;
    private final TaskAutoReviewProducer producer;
    private final TaskAutoReviewRuleEngine engine;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaskAutoReview schedule(MaintenanceTask task) {
        TaskAutoReview existing = reviewMapper.selectOne(new LambdaQueryWrapper<TaskAutoReview>()
                .eq(TaskAutoReview::getTaskId, task.getId()).eq(TaskAutoReview::getEvidenceVersion, task.getEvidenceVersion()));
        TaskAutoReview review = existing;
        if (review == null) {
            review = new TaskAutoReview()
                    .setTaskId(task.getId()).setEvidenceVersion(task.getEvidenceVersion())
                    .setRequestId("review-task-" + task.getId() + "-v" + task.getEvidenceVersion())
                    .setStatus("PENDING").setRuleVersion(TaskAutoReviewRuleEngine.RULE_VERSION)
                    .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
            reviewMapper.insert(review);
            taskMapper.updateById(new MaintenanceTask().setId(task.getId())
                    .setAutoReviewStatus("PENDING").setAutoReviewEvidenceStatus(null)
                    .setAutoReviewScore(null).setAutoReviewEvidenceScore(null)
                    .setAutoReviewReason(null).setAutoReviewUpdatedAt(LocalDateTime.now()));
        }
        if (!"PENDING".equals(review.getStatus())) return review;
        TaskAutoReview scheduledReview = review;
        Runnable publish = () -> {
            try {
                TaskAutoReview locked = reviewMapper.selectById(scheduledReview.getId());
                if (locked == null || !"PENDING".equals(locked.getStatus())) return;
                // 先用条件更新抢占发布权，再发送消息，避免并发确认请求重复投递。
                int claimed = reviewMapper.update(new TaskAutoReview().setStatus("PROCESSING").setUpdatedAt(LocalDateTime.now()),
                        new LambdaUpdateWrapper<TaskAutoReview>().eq(TaskAutoReview::getId, locked.getId()).eq(TaskAutoReview::getStatus, "PENDING"));
                if (claimed != 1) return;
                taskMapper.updateById(new MaintenanceTask().setId(task.getId()).setAutoReviewStatus("PROCESSING").setAutoReviewUpdatedAt(LocalDateTime.now()));
                producer.publish(task, locked);
            } catch (Exception e) {
                log.error("[自动审核] 发布失败 taskId={}", task.getId(), e);
                markManual(scheduledReview.getId(), "AUTO_REVIEW_PUBLISH_FAILED: " + e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { publish.run(); } });
        else publish.run();
        return review;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void processResult(Map<String,Object> body) {
        Long taskId = number(body.get("taskId"));
        Integer version = numberInt(body.get("evidenceVersion"));
        String requestId = String.valueOf(body.get("requestId"));
        TaskAutoReview review = reviewMapper.selectOne(new LambdaQueryWrapper<TaskAutoReview>().eq(TaskAutoReview::getTaskId, taskId).eq(TaskAutoReview::getEvidenceVersion, version));
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (review == null || task == null || !requestId.equals(review.getRequestId()) || !Objects.equals(task.getEvidenceVersion(), version)) throw new IllegalStateException("自动审核结果过期或身份不匹配");
        if (Set.of("AUTO_ARCHIVED", "MANUAL_REVIEW", "MANUAL_APPROVED", "MANUAL_REJECTED").contains(review.getStatus())) return;
        boolean success = Boolean.TRUE.equals(body.get("success"));
        Map<String,Object> facts = body.get("semanticFacts") instanceof Map<?,?> m ? cast(m) : Map.of();
        Map<String,Object> snapshot = snapshot(task.getEvidenceBundle());
        TaskAutoReviewRuleEngine.ReviewResult result = success ? engine.evaluate(snapshot, facts) : new TaskAutoReviewRuleEngine.ReviewResult(0, 0, "INSUFFICIENT", "MANUAL_REVIEW", Map.of(), List.of("语义Agent执行失败"));
        TaskAutoReview update = new TaskAutoReview().setStatus(result.decision()).setEvidenceStatus(result.evidenceStatus()).setEvidenceScore(result.evidenceScore()).setTotalScore(result.totalScore()).setDimensionScores(result.scores()).setAgentResult(body).setDecisionReasons(result.reasons()).setModelName(modelName(body)).setModelRequestId(modelRequestId(body)).setErrorMessage(success ? null : String.valueOf(body.get("error"))).setUpdatedAt(LocalDateTime.now());
        int accepted = reviewMapper.update(update, new LambdaUpdateWrapper<TaskAutoReview>()
                .eq(TaskAutoReview::getId, review.getId()).in(TaskAutoReview::getStatus, "PENDING", "PROCESSING"));
        if (accepted != 1) return;
        taskMapper.update(new MaintenanceTask().setAutoReviewStatus(result.decision())
                        .setAutoReviewEvidenceStatus(result.evidenceStatus()).setAutoReviewScore(result.totalScore())
                        .setAutoReviewEvidenceScore(result.evidenceScore()).setAutoReviewReason(String.join("；", result.reasons()))
                        .setAutoReviewUpdatedAt(LocalDateTime.now()),
                new LambdaUpdateWrapper<MaintenanceTask>()
                        .eq(MaintenanceTask::getId, taskId)
                        .eq(MaintenanceTask::getEvidenceVersion, version));
    }

    @Transactional
    public void markManualFromFailure(Map<String,Object> body, String error) { Object id = body.get("requestId"); if (id == null) return; TaskAutoReview review = reviewMapper.selectOne(new LambdaQueryWrapper<TaskAutoReview>().eq(TaskAutoReview::getRequestId, String.valueOf(id))); if (review != null) markManual(review.getId(), error); }
    @Transactional public void markManual(Long reviewId, String error) {
        TaskAutoReview r = reviewMapper.selectById(reviewId);
        if (r == null) return;
        int accepted = reviewMapper.update(new TaskAutoReview().setStatus("MANUAL_REVIEW").setEvidenceStatus("INSUFFICIENT").setErrorMessage(error).setDecisionReasons(List.of(error)).setUpdatedAt(LocalDateTime.now()), new LambdaUpdateWrapper<TaskAutoReview>().eq(TaskAutoReview::getId, reviewId).in(TaskAutoReview::getStatus, "PENDING", "PROCESSING"));
        if (accepted == 1) taskMapper.update(new MaintenanceTask().setAutoReviewStatus("MANUAL_REVIEW")
                        .setAutoReviewEvidenceStatus("INSUFFICIENT").setAutoReviewReason(error).setAutoReviewUpdatedAt(LocalDateTime.now()),
                new LambdaUpdateWrapper<MaintenanceTask>()
                        .eq(MaintenanceTask::getId, r.getTaskId())
                        .eq(MaintenanceTask::getEvidenceVersion, r.getEvidenceVersion()));
    }

    @Transactional
    public TaskAutoReview manualDecision(Long taskId, String decision, Long reviewer, String comment) {
        TaskAutoReview review = reviewMapper.selectOne(new LambdaQueryWrapper<TaskAutoReview>().eq(TaskAutoReview::getTaskId, taskId).orderByDesc(TaskAutoReview::getEvidenceVersion).last("LIMIT 1"));
        if (review == null) throw new IllegalArgumentException("任务没有自动审核记录");
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getEvidenceVersion(), review.getEvidenceVersion())) {
            throw new IllegalStateException("审核记录不是任务当前证据版本，请刷新后重试");
        }
        if (!Set.of("MANUAL_REVIEW", "FAILED").contains(review.getStatus())) throw new IllegalStateException("当前任务不在待人工审核状态");
        if (!Set.of("MANUAL_APPROVED", "MANUAL_REJECTED").contains(decision)) throw new IllegalArgumentException("decision必须为MANUAL_APPROVED或MANUAL_REJECTED");
        review.setStatus(decision).setReviewedBy(reviewer).setReviewComment(comment).setReviewedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        reviewMapper.updateById(review);
        taskMapper.updateById(new MaintenanceTask().setId(taskId).setAutoReviewStatus(decision).setAutoReviewReason(comment).setAutoReviewUpdatedAt(LocalDateTime.now()));
        return review;
    }

    public TaskAutoReview latest(Long taskId) { return reviewMapper.selectOne(new LambdaQueryWrapper<TaskAutoReview>().eq(TaskAutoReview::getTaskId, taskId).orderByDesc(TaskAutoReview::getEvidenceVersion).last("LIMIT 1")); }
    private Long number(Object x) { return x instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(x)); }
    private Integer numberInt(Object x) { return x instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(x)); }
    private String modelName(Map<String,Object> b) { return b.get("model") instanceof Map<?,?> m ? String.valueOf(m.get("name")) : null; }
    private String modelRequestId(Map<String,Object> b) { return b.get("model") instanceof Map<?,?> m ? String.valueOf(m.get("requestId")) : null; }
    private Map<String,Object> cast(Map<?,?> m) { Map<String,Object> out = new LinkedHashMap<>(); m.forEach((k,v)->out.put(String.valueOf(k),v)); return out; }
    private Map<String,Object> snapshot(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof Map<?,?> m) return cast(m);
        if (raw instanceof String s && !s.isBlank()) {
            try { return objectMapper.readValue(s, Map.class); }
            catch (Exception ignored) { return new LinkedHashMap<>(); }
        }
        try { Map<String,Object> converted = objectMapper.convertValue(raw, Map.class); return converted == null ? new LinkedHashMap<>() : converted; }
        catch (IllegalArgumentException ignored) { return new LinkedHashMap<>(); }
    }
}
