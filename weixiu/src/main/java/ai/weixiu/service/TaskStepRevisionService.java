package ai.weixiu.service;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.entity.MaintenanceTask;
import ai.weixiu.entity.TaskStepRecord;
import ai.weixiu.entity.TaskStepRevision;
import ai.weixiu.exception.NotFoundException;
import ai.weixiu.exception.TaskStateException;
import ai.weixiu.mapper.MaintenanceTaskMapper;
import ai.weixiu.mapper.TaskStepRecordMapper;
import ai.weixiu.mapper.TaskStepRevisionMapper;
import ai.weixiu.pojo.dto.NotificationMessage;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskStepRevisionService {
    private static final Set<String> MUTABLE_STATUSES = Set.of("PENDING", "AI_REJECTED");
    private static final Set<String> LOCKED_STATUSES = Set.of("SUBMITTED", "AI_PASSED", "COMPLETED", "SKIPPED");
    private static final Set<String> CHANGE_ACTIONS = Set.of("MANUAL_CORRECTED", "FIELD_REGENERATED");
    private static final String REMOVE_EXTRA_ACTION = "REMOVE_EXTRA";

    private final TaskStepRevisionMapper revisionMapper;
    private final MaintenanceTaskMapper taskMapper;
    private final TaskStepRecordMapper stepMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @Transactional
    public TaskStepRevision create(Long taskId, Long requesterId, String requestText) {
        if (requestText == null || requestText.isBlank()) throw new IllegalArgumentException("请描述需要修改的步骤和原因");
        MaintenanceTask task = taskMapper.selectById(taskId);
        assertRevisionable(task);
        Long active = revisionMapper.selectCount(new LambdaQueryWrapper<TaskStepRevision>()
                .eq(TaskStepRevision::getTaskId, taskId).in(TaskStepRevision::getStatus, "ANALYZING", "PREVIEW_READY", "APPLYING"));
        if (active != null && active > 0) throw new TaskStateException("当前任务已有步骤修改正在处理，请先完成或取消");
        List<TaskStepRecord> steps = currentSteps(taskId);
        if (steps.isEmpty()) throw new TaskStateException("任务还没有可修改的步骤");

        TaskStepRevision revision = new TaskStepRevision()
                .setTaskId(taskId).setRequesterId(requesterId).setStatus("ANALYZING")
                .setRequestText(requestText.trim()).setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now())
                // 快照从请求创建时开始记录。确认时必须与这一份快照比对，
                // 防止分析期间步骤被提交/审核后，旧预览覆盖新数据。
                .setBeforeSnapshot(steps.stream().map(this::stepMap).toList())
                .setExpiresAt(LocalDateTime.now().plusMinutes(15));
        revisionMapper.insert(revision);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("revisionId", revision.getId());
        message.put("taskId", taskId);
        message.put("requestText", revision.getRequestText());
        message.put("faultDescription", task.getFaultDescription());
        message.put("deviceId", task.getDeviceId());
        message.put("deviceName", task.getDeviceName());
        message.put("steps", steps.stream().map(this::stepMap).toList());
        Runnable publish = () -> {
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.TASK_EXCHANGE, RabbitMQConfig.TASK_STEP_REVISION_KEY, message);
            } catch (Exception e) {
                log.error("[步骤修订] 发布失败 revisionId={}", revision.getId(), e);
                markFailed(revision.getId(), "修订请求发布失败：" + e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
        return revision;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handleResult(Map<String, Object> body) {
        Long revisionId = number(body.get("revisionId"));
        TaskStepRevision revision = revisionMapper.selectById(revisionId);
        if (revision == null || !"ANALYZING".equals(revision.getStatus())) return;
        Long taskId = revision.getTaskId();
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null || !isRevisionableStatus(task.getStatus())) {
            markFailed(revisionId, "任务状态已变化，无法生成步骤修订预览");
            return;
        }
        boolean success = Boolean.TRUE.equals(body.get("success"));
        if (!success) {
            String error = String.valueOf(body.getOrDefault("error", "步骤修订失败"));
            markFailed(revisionId, error);
            notify(task, revision, "TASK_STEP_REVISION_FAILED", "步骤修改失败", error);
            return;
        }
        if (Boolean.TRUE.equals(body.get("needsClarification"))) {
            String question = String.valueOf(body.getOrDefault("clarificationQuestion", "请明确需要修改的步骤范围"));
            revision.setRecognizedScope(body.get("scope"))
                    .setAgentResult(body)
                    .setStatus("FAILED")
                    .setErrorMessage(question)
                    .setResultSummary("需要补充修改范围")
                    .setUpdatedAt(LocalDateTime.now());
            revisionMapper.updateById(revision);
            notify(task, revision, "TASK_STEP_REVISION_FAILED", "请补充步骤修改范围", question);
            return;
        }
        revision.setRecognizedScope(body.get("scope"));
        revision.setAgentResult(body);
        revision.setStatus("PREVIEW_READY").setExpiresAt(LocalDateTime.now().plusMinutes(15))
                .setErrorMessage(null)
                .setResultSummary(summary(body));
        revisionMapper.updateById(revision);
        notify(task, revision, "TASK_STEP_REVISION_READY", "步骤修改预览已生成", revision.getErrorMessage());
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public TaskStepRevision confirm(Long taskId, Long revisionId, Long requesterId) {
        TaskStepRevision revision = revisionMapper.selectById(revisionId);
        if (revision == null || !Objects.equals(revision.getTaskId(), taskId)) throw new NotFoundException("修订请求不存在");
        if (!Objects.equals(revision.getRequesterId(), requesterId)) throw new IllegalStateException("无权确认该修订请求");
        if (!"PREVIEW_READY".equals(revision.getStatus())) throw new TaskStateException("修订预览已处理或不可用");
        if (revision.getExpiresAt() != null && revision.getExpiresAt().isBefore(LocalDateTime.now())) {
            revision.setStatus("EXPIRED").setUpdatedAt(LocalDateTime.now()); revisionMapper.updateById(revision);
            throw new TaskStateException("修订预览已过期，请重新发起");
        }
        MaintenanceTask task = taskMapper.selectByIdForUpdate(taskId);
        assertRevisionable(task);
        List<TaskStepRecord> steps = stepMapper.selectByTaskIdForUpdate(taskId);
        Map<Long, TaskStepRecord> byId = steps.stream().collect(Collectors.toMap(TaskStepRecord::getId, Function.identity()));
        Map<String, Object> result = asMap(revision.getAgentResult());
        List<Map<String, Object>> items = asList(result.get("items"));
        if (items.isEmpty()) throw new TaskStateException("修订预览没有可应用步骤");
        Map<String, Object> scope = asMap(result.get("scope"));
        if (scope.isEmpty()) throw new TaskStateException("修订预览没有明确的步骤范围");
        assertScopeAndCoverage(steps, scope, items);
        assertSnapshotMatches(revision, byId, items);

        revision.setStatus("APPLYING").setConfirmedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        revisionMapper.updateById(revision);
        int changed = 0;
        int annotated = 0;
        int removed = 0;
        for (Map<String, Object> item : items) {
            Long stepId = number(item.get("stepId"));
            TaskStepRecord step = byId.get(stepId);
            if (step == null) throw new TaskStateException("修订预览包含不存在的步骤");
            if (item.get("sortOrder") instanceof Number n && !Objects.equals(step.getSortOrder(), n.intValue())) {
                throw new TaskStateException("修订预览的步骤序号与当前任务不一致");
            }
            if (!withinScope(step, scope)) throw new TaskStateException("修订预览包含员工请求范围外的步骤");
            String action = String.valueOf(item.getOrDefault("action", ""));
            if ("COMPLETED_SKIPPED".equals(action)) {
                if (!LOCKED_STATUSES.contains(step.getStatus())) throw new TaskStateException("步骤锁定状态已变化，请重新生成预览");
                annotate(step, revisionId, "COMPLETED_SKIPPED", "已完成，未修改");
                annotated++;
                continue;
            }
            if ("MANUAL_LOCKED".equals(action)) {
                if (!MUTABLE_STATUSES.contains(step.getStatus())) throw new TaskStateException("步骤状态已变化，请重新生成预览");
                if (!hasVerifiedManualSource(item, result)) {
                    throw new TaskStateException("第 " + step.getSortOrder() + " 步缺少可验证的手册来源，请重新生成预览");
                }
                annotate(step, revisionId, "MANUAL_LOCKED", "步骤来源手册，无法修改");
                annotated++;
                continue;
            }
            if (REMOVE_EXTRA_ACTION.equals(action)) {
                if (!MUTABLE_STATUSES.contains(step.getStatus())) {
                    throw new TaskStateException("第 " + step.getSortOrder() + " 步已提交或完成，不能删除");
                }
                String reason = String.valueOf(item.getOrDefault("reason", "")).trim();
                if (reason.isEmpty()) throw new TaskStateException("删除多余步骤必须提供删除原因");
                int deleted = stepMapper.delete(new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getId, step.getId())
                        .eq(TaskStepRecord::getTaskId, taskId)
                        .in(TaskStepRecord::getStatus, MUTABLE_STATUSES));
                if (deleted != 1) throw new TaskStateException("第 " + step.getSortOrder() + " 步删除失败，请重新生成预览");
                steps.remove(step);
                removed++;
                continue;
            }
            if (!MUTABLE_STATUSES.contains(step.getStatus())) throw new TaskStateException("待修改步骤状态已变化，请重新生成预览");
            if (!CHANGE_ACTIONS.contains(action)) throw new TaskStateException("修订预览包含不支持的处理类型");
            String proposedContent = item.get("content") == null ? "" : String.valueOf(item.get("content")).trim();
            if (proposedContent.isEmpty()) throw new TaskStateException("第 " + step.getSortOrder() + " 步的修改内容为空，请重新生成预览");
            if ("MANUAL_CORRECTED".equals(action) && !hasVerifiedManualSource(item, result)) {
                throw new TaskStateException("第 " + step.getSortOrder() + " 步缺少可验证的手册来源，请重新生成预览");
            }
            if (!hasInstructionChange(step, item)) {
                throw new TaskStateException("第 " + step.getSortOrder() + " 步的新内容与当前步骤相同，请重新生成预览");
            }
            applyItem(step, item, revisionId, action);
            // updateById 负责带 JacksonTypeHandler 的 sources 等非空字段；下面的 wrapper
            // 专门负责显式清空执行证据（MyBatis-Plus 默认会忽略 null）。
            stepMapper.updateById(step);
            LambdaUpdateWrapper<TaskStepRecord> update = new LambdaUpdateWrapper<>();
            update.eq(TaskStepRecord::getId, step.getId()).eq(TaskStepRecord::getTaskId, taskId)
                    .set(TaskStepRecord::getTitle, step.getTitle()).set(TaskStepRecord::getContent, step.getContent())
                    .set(TaskStepRecord::getSafetyNote, step.getSafetyNote()).set(TaskStepRecord::getRequirePhoto, step.getRequirePhoto())
                    .set(TaskStepRecord::getRequireNote, step.getRequireNote()).set(TaskStepRecord::getEstimatedMinutes, step.getEstimatedMinutes())
                    .set(TaskStepRecord::getStatus, "PENDING")
                    .set(TaskStepRecord::getImages, null).set(TaskStepRecord::getNote, null)
                    .set(TaskStepRecord::getCheckpointConfirmed, false).set(TaskStepRecord::getAiPass, null)
                    .set(TaskStepRecord::getAiConfidence, null).set(TaskStepRecord::getAiReason, null)
                    .set(TaskStepRecord::getCompletedAt, null).set(TaskStepRecord::getRevisionState, step.getRevisionState())
                    .set(TaskStepRecord::getRevisionNotice, step.getRevisionNotice()).set(TaskStepRecord::getLastRevisionId, revisionId)
                    .set(TaskStepRecord::getLastRevisionAt, step.getLastRevisionAt());
            stepMapper.update(null, update);
            changed++;
        }
        if (removed > 0) {
            // 删除后重新连续编号，管理端、下一次范围识别和最终证据包都使用新序号。
            for (int index = 0; index < steps.size(); index++) {
                TaskStepRecord step = steps.get(index);
                int order = index + 1;
                if (!Objects.equals(step.getSortOrder(), order)) {
                    step.setSortOrder(order);
                    stepMapper.update(null, new LambdaUpdateWrapper<TaskStepRecord>()
                            .eq(TaskStepRecord::getId, step.getId())
                            .eq(TaskStepRecord::getTaskId, taskId)
                            .set(TaskStepRecord::getSortOrder, order));
                }
            }
            task.setStepCount(steps.size());
            taskMapper.updateById(new MaintenanceTask().setId(taskId).setStepCount(steps.size()));
        }
        if (changed == 0 && annotated == 0 && removed == 0) throw new TaskStateException("当前范围内没有可处理的步骤");
        // beforeSnapshot 保留 create() 时记录的完整原始快照；afterSnapshot 记录
        // 应用后的完整当前版本，审计时不会只看到被改动的局部片段。
        revision.setAfterSnapshot(steps.stream().map(this::stepMap).toList())
                .setStatus("APPLIED").setAppliedAt(LocalDateTime.now())
                .setResultSummary("已修改 " + changed + " 个步骤，删除 " + removed + " 个多余步骤，保留 " + annotated + " 个锁定步骤").setUpdatedAt(LocalDateTime.now());
        revisionMapper.updateById(revision);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(new MaintenanceTask().setId(taskId).setUpdatedAt(task.getUpdatedAt()));
        notify(task, revision, "TASK_STEP_REVISION_APPLIED", "任务步骤已更新", revision.getResultSummary());
        return revision;
    }

    @Transactional
    public TaskStepRevision cancel(Long taskId, Long revisionId, Long requesterId) {
        TaskStepRevision revision = revisionMapper.selectById(revisionId);
        if (revision == null || !Objects.equals(revision.getTaskId(), taskId)) throw new NotFoundException("修订请求不存在");
        if (!Objects.equals(revision.getRequesterId(), requesterId)) throw new IllegalStateException("无权取消该修订请求");
        if (Set.of("APPLIED", "CANCELLED", "EXPIRED").contains(revision.getStatus())) return revision;
        revision.setStatus("CANCELLED").setUpdatedAt(LocalDateTime.now());
        revisionMapper.updateById(revision);
        return revision;
    }

    public TaskStepRevision get(Long taskId, Long revisionId) {
        TaskStepRevision revision = revisionMapper.selectById(revisionId);
        if (revision == null || !Objects.equals(revision.getTaskId(), taskId)) throw new NotFoundException("修订请求不存在");
        if ("PREVIEW_READY".equals(revision.getStatus()) && revision.getExpiresAt() != null && revision.getExpiresAt().isBefore(LocalDateTime.now())) {
            revision.setStatus("EXPIRED").setUpdatedAt(LocalDateTime.now()); revisionMapper.updateById(revision);
        }
        return revision;
    }

    private void applyItem(TaskStepRecord step, Map<String, Object> item, Long revisionId, String action) {
        if (item.get("title") != null && !String.valueOf(item.get("title")).isBlank()) step.setTitle(String.valueOf(item.get("title")).trim());
        if (item.get("content") != null && !String.valueOf(item.get("content")).isBlank()) step.setContent(String.valueOf(item.get("content")).trim());
        if (item.get("safetyNote") != null && !String.valueOf(item.get("safetyNote")).isBlank()) step.setSafetyNote(String.valueOf(item.get("safetyNote")).trim());
        if (item.get("requirePhoto") != null) step.setRequirePhoto(Boolean.valueOf(String.valueOf(item.get("requirePhoto"))));
        if (item.get("requireNote") != null) step.setRequireNote(Boolean.valueOf(String.valueOf(item.get("requireNote"))));
        if (item.get("estimatedMinutes") instanceof Number n) step.setEstimatedMinutes(n.intValue());
        if (item.get("sources") != null) step.setSources(item.get("sources"));
        step.setGenerateConfidence("MANUAL_CORRECTED".equals(action)
                ? new java.math.BigDecimal("0.900") : new java.math.BigDecimal("0.200"));
        step.setStatus("PENDING").setImages(null).setNote(null).setCheckpointConfirmed(false)
                .setAiPass(null).setAiConfidence(null).setAiReason(null).setCompletedAt(null)
                .setRevisionState(action).setRevisionNotice("MANUAL_CORRECTED".equals(action) ? "已按手册纠正" : "已根据现场描述修改")
                .setLastRevisionId(revisionId).setLastRevisionAt(LocalDateTime.now());
    }

    private void annotate(TaskStepRecord step, Long revisionId, String state, String notice) {
        LocalDateTime now = LocalDateTime.now();
        stepMapper.update(null, new LambdaUpdateWrapper<TaskStepRecord>()
                .eq(TaskStepRecord::getId, step.getId()).eq(TaskStepRecord::getTaskId, step.getTaskId())
                .set(TaskStepRecord::getRevisionState, state).set(TaskStepRecord::getRevisionNotice, notice)
                .set(TaskStepRecord::getLastRevisionId, revisionId).set(TaskStepRecord::getLastRevisionAt, now));
        step.setRevisionState(state).setRevisionNotice(notice).setLastRevisionId(revisionId).setLastRevisionAt(now);
    }

    private boolean withinScope(TaskStepRecord step, Map<String, Object> scope) {
        Object rawIds = scope.get("stepIds");
        if (rawIds instanceof List<?> ids && !ids.isEmpty()) {
            return ids.stream().map(this::number).anyMatch(id -> Objects.equals(id, step.getId()));
        }
        Integer start = scope.get("startOrder") instanceof Number n ? n.intValue() : null;
        Integer end = scope.get("endOrder") instanceof Number n ? n.intValue() : null;
        if (start == null || end == null || step.getSortOrder() == null || start < 1 || end < start) return false;
        return step.getSortOrder() >= start && step.getSortOrder() <= end;
    }

    private void assertScopeAndCoverage(List<TaskStepRecord> steps, Map<String, Object> scope,
                                        List<Map<String, Object>> items) {
        Object rawIds = scope.get("stepIds");
        if (rawIds instanceof List<?> ids && !ids.isEmpty()) {
            Set<Long> requested = ids.stream().map(this::number).collect(Collectors.toSet());
            if (requested.size() != ids.size() || requested.stream().anyMatch(id -> steps.stream().noneMatch(s -> Objects.equals(s.getId(), id)))) {
                throw new TaskStateException("识别到的步骤范围无效，请重新描述修改范围");
            }
        } else {
            Integer start = scope.get("startOrder") instanceof Number n ? n.intValue() : null;
            Integer end = scope.get("endOrder") instanceof Number n ? n.intValue() : null;
            int maxOrder = steps.stream().map(TaskStepRecord::getSortOrder).filter(Objects::nonNull).max(Integer::compareTo).orElse(0);
            if (start == null || end == null || start < 1 || end < start || end > maxOrder) {
                throw new TaskStateException("识别到的步骤范围超出当前任务，请重新描述修改范围");
            }
        }
        Set<Long> expected = steps.stream().filter(step -> withinScope(step, scope)).map(TaskStepRecord::getId).collect(Collectors.toSet());
        List<Long> actualList = items.stream().map(item -> number(item.get("stepId"))).toList();
        Set<Long> actual = new HashSet<>(actualList);
        if (expected.isEmpty() || actual.size() != actualList.size() || !expected.equals(actual)) {
            throw new TaskStateException("修订预览未完整覆盖员工请求的步骤范围，请重新生成预览");
        }
    }

    private boolean hasVerifiedManualSource(Map<String, Object> item, Map<String, Object> result) {
        Set<String> knownIds = new HashSet<>();
        for (Map<String, Object> evidence : asList(result.get("manualEvidence"))) {
            String id = String.valueOf(evidence.getOrDefault("documentId", "")).trim();
            if (!id.isEmpty()) knownIds.add(id);
        }
        if (knownIds.isEmpty()) return false;
        Object rawSources = item.get("sources");
        if (!(rawSources instanceof List<?> sources)) return false;
        for (Object raw : sources) {
            if (!(raw instanceof Map<?, ?> source)) continue;
            if (!"manual".equalsIgnoreCase(String.valueOf(source.get("type")))) continue;
            String id = String.valueOf(source.get("documentId")).trim();
            if (knownIds.contains(id)) return true;
        }
        return false;
    }

    private boolean hasInstructionChange(TaskStepRecord step, Map<String, Object> item) {
        if (item.get("title") != null && !String.valueOf(item.get("title")).isBlank()
                && !Objects.equals(step.getTitle(), String.valueOf(item.get("title")).trim())) return true;
        if (item.get("content") != null && !Objects.equals(step.getContent(), String.valueOf(item.get("content")).trim())) return true;
        if (item.get("safetyNote") != null && !String.valueOf(item.get("safetyNote")).isBlank()
                && !Objects.equals(step.getSafetyNote(), String.valueOf(item.get("safetyNote")).trim())) return true;
        if (item.get("requirePhoto") != null && !Objects.equals(step.getRequirePhoto(), Boolean.valueOf(String.valueOf(item.get("requirePhoto"))))) return true;
        if (item.get("requireNote") != null && !Objects.equals(step.getRequireNote(), Boolean.valueOf(String.valueOf(item.get("requireNote"))))) return true;
        return item.get("estimatedMinutes") instanceof Number n && !Objects.equals(step.getEstimatedMinutes(), n.intValue());
    }

    private void assertSnapshotMatches(TaskStepRevision revision, Map<Long, TaskStepRecord> current,
                                       List<Map<String, Object>> items) {
        List<Map<String, Object>> snapshots = asList(revision.getBeforeSnapshot());
        Map<Long, Map<String, Object>> before = new HashMap<>();
        for (Map<String, Object> value : snapshots) before.put(number(value.get("stepId")), value);
        for (Map<String, Object> item : items) {
            Long id = number(item.get("stepId"));
            TaskStepRecord now = current.get(id);
            Map<String, Object> old = before.get(id);
            if (now == null || old == null
                    || !Objects.equals(String.valueOf(old.get("title")), String.valueOf(now.getTitle()))
                    || !Objects.equals(String.valueOf(old.get("content")), String.valueOf(now.getContent()))
                    || !Objects.equals(String.valueOf(old.get("safetyNote")), String.valueOf(now.getSafetyNote()))
                    || !Objects.equals(String.valueOf(old.get("status")), String.valueOf(now.getStatus()))) {
                throw new TaskStateException("步骤内容或状态已变化，请重新生成修改预览");
            }
        }
    }

    private void assertRevisionable(MaintenanceTask task) {
        if (task == null) throw new NotFoundException("任务不存在");
        if (!isRevisionableStatus(task.getStatus())) throw new TaskStateException("当前任务状态不允许修改步骤：" + task.getStatus());
    }

    private boolean isRevisionableStatus(String status) { return "GENERATED".equals(status) || "EXECUTING".equals(status); }
    private List<TaskStepRecord> currentSteps(Long taskId) { return stepMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskStepRecord>().eq(TaskStepRecord::getTaskId, taskId).orderByAsc(TaskStepRecord::getSortOrder)); }
    private Long number(Object value) { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
    private Map<String, Object> asMap(Object value) { return value instanceof Map<?, ?> m ? objectMapper.convertValue(m, new TypeReference<Map<String,Object>>() {}) : Map.of(); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> asList(Object value) { return value instanceof List<?> l ? (List<Map<String,Object>>) (List<?>) l : List.of(); }
    private Map<String, Object> stepMap(TaskStepRecord step) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("stepId", step.getId()); m.put("sortOrder", step.getSortOrder()); m.put("title", step.getTitle());
        m.put("content", step.getContent()); m.put("safetyNote", step.getSafetyNote()); m.put("requirePhoto", step.getRequirePhoto());
        m.put("requireNote", step.getRequireNote()); m.put("estimatedMinutes", step.getEstimatedMinutes()); m.put("status", step.getStatus());
        m.put("sources", step.getSources()); m.put("generateConfidence", step.getGenerateConfidence());
        return m;
    }
    private String summary(Map<String,Object> body) {
        List<Map<String,Object>> items = asList(body.get("items"));
        return "识别到 " + items.size() + " 个候选步骤";
    }
    private void markFailed(Long revisionId, String error) {
        TaskStepRevision current = revisionMapper.selectById(revisionId);
        if (current == null) return;
        current.setStatus("FAILED").setErrorMessage(error).setUpdatedAt(LocalDateTime.now()); revisionMapper.updateById(current);
    }
    private void notify(MaintenanceTask task, TaskStepRevision revision, String type, String title, String body) {
        if (task.getReporterId() == null) return;
        notificationService.send(task.getReporterId(), NotificationMessage.builder().type(type).title(title)
                .body(body == null ? "" : body).data(Map.of("taskId", task.getId(), "revisionId", revision.getId(), "status", revision.getStatus())).build());
    }
}
