package ai.weixiu.service;

import ai.weixiu.entity.TaskStepRecord;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/** 可解释的 v1 任务自动审核规则。所有分数均为整数，避免浮点边界不稳定。 */
@Component
public class TaskAutoReviewRuleEngine {
    public static final String RULE_VERSION = "task-auto-review.v1";
    public static final int TOTAL_THRESHOLD = 70;
    public static final int EVIDENCE_THRESHOLD = 12;

    public ReviewResult evaluate(Map<String, Object> snapshot, Map<String, Object> agent) {
        List<Map<String, Object>> steps = maps(snapshot.get("steps"));
        int evidence = evidenceScore(snapshot, steps, agent);
        int process = processScore(steps, agent);
        int result = resultScore(snapshot, agent);
        int manual = manualScore(steps, agent);
        int semantic = semanticScore(snapshot, steps, agent);
        int trace = traceScore(snapshot, steps);
        int total = evidence + process + result + manual + semantic + trace;
        String evidenceStatus = evidence >= EVIDENCE_THRESHOLD ? "SUFFICIENT" : "INSUFFICIENT";
        String decision = evidence >= EVIDENCE_THRESHOLD && total >= TOTAL_THRESHOLD ? "AUTO_ARCHIVED" : "MANUAL_REVIEW";
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("evidence", evidence); scores.put("process", process); scores.put("result", result);
        scores.put("manual", manual); scores.put("semantic", semantic); scores.put("traceability", trace);
        List<String> reasons = new ArrayList<>();
        if (evidence < EVIDENCE_THRESHOLD) reasons.add("证据完整度低于12分");
        if (total < TOTAL_THRESHOLD) reasons.add("综合得分低于70分");
        if (reasons.isEmpty()) reasons.add("证据充足且综合得分达到自动归档阈值");
        return new ReviewResult(evidence, total, evidenceStatus, decision, scores, reasons);
    }

    private int evidenceScore(Map<String, Object> s, List<Map<String, Object>> steps, Map<String, Object> a) {
        int process = 0, before = 0, after = 0;
        int completed = 0;
        for (Map<String, Object> step : steps) {
            boolean hasImage = !strings(step.get("images")).isEmpty();
            boolean hasNote = StringUtils.hasText(text(step.get("note")));
            boolean done = Set.of("COMPLETED", "AI_PASSED", "SKIPPED").contains(text(step.get("status")));
            if (done) completed++;
            if (hasImage || hasNote || done) process++;
        }
        if (!steps.isEmpty()) process = Math.min(9, Math.round(process * 9f / steps.size()));
        if (StringUtils.hasText(text(s.get("faultDescription"))) || !strings(s.get("reportImages")).isEmpty()) before = 3;
        if (StringUtils.hasText(text(s.get("completionSummary"))) || StringUtils.hasText(text(s.get("effectiveMeasure"))) || completed > 0) after = 3;
        int clarity = 0;
        for (Map<String, Object> step : steps) {
            if (StringUtils.hasText(text(step.get("note"))) || !strings(step.get("images")).isEmpty()) clarity++;
        }
        int clear = steps.isEmpty() ? 0 : Math.min(3, Math.round(clearanceRatio(clarity, steps.size()) * 3));
        int complete = steps.isEmpty() ? 0 : Math.min(3, Math.round(clearanceRatio(completed, steps.size()) * 3));
        int readable = (!strings(s.get("reportImages")).isEmpty() || steps.stream().anyMatch(x -> !strings(x.get("images")).isEmpty()) || StringUtils.hasText(text(s.get("completionSummary")))) ? 2 : 1;
        int mappedStep = agentInt(a, "stepMappingScore", steps.isEmpty() ? 0 : completed * 4 / steps.size(), 4);
        int mappedResult = agentInt(a, "resultMappingScore", StringUtils.hasText(text(s.get("completionSummary"))) ? 2 : 1, 2);
        int chain = agentInt(a, "evidenceChainScore", completed > 0 ? 1 : 0, 1);
        return Math.min(30, process + before + after + clear + complete + readable + mappedStep + mappedResult + chain);
    }

    private int processScore(List<Map<String, Object>> steps, Map<String, Object> a) {
        int done = (int) steps.stream().filter(x -> Set.of("COMPLETED", "AI_PASSED", "SKIPPED").contains(text(x.get("status")))).count();
        int complete = agentInt(a, "processRecordComplete", steps.isEmpty() ? 0 : done * 8 / steps.size(), 8);
        int specific = agentInt(a, "operationSpecificityScore", steps.stream().anyMatch(x -> StringUtils.hasText(text(x.get("note")))) ? 3 : 1, 5);
        int timeline = steps.isEmpty() ? 0 : 4;
        int abnormal = agentInt(a, "exceptionHandlingScore", 1, 3);
        return Math.min(20, complete + specific + timeline + abnormal);
    }

    private int resultScore(Map<String, Object> s, Map<String, Object> a) {
        int description = StringUtils.hasText(text(s.get("completionSummary"))) || StringUtils.hasText(text(s.get("effectiveMeasure"))) ? 7 : 3;
        int test = agentInt(a, "functionalTestScore", StringUtils.hasText(text(s.get("completionSummary"))) ? 4 : 1, 6);
        int change = StringUtils.hasText(text(s.get("faultDescription"))) && StringUtils.hasText(text(s.get("completionSummary"))) ? 4 : 1;
        int follow = agentInt(a, "followupScore", 1, 3);
        return Math.min(20, description + test + change + follow);
    }

    private int manualScore(List<Map<String, Object>> steps, Map<String, Object> a) {
        int sourced = (int) steps.stream().filter(x -> x.get("sources") != null).count();
        int step = agentInt(a, "manualStepMatchScore", steps.isEmpty() ? 0 : sourced * 7 / steps.size(), 7);
        return Math.min(15, step + agentInt(a, "parameterSafetyScore", 2, 5) + agentInt(a, "exceptionNormScore", 1, 3));
    }

    private int semanticScore(Map<String, Object> s, List<Map<String, Object>> steps, Map<String, Object> a) {
        int task = agentInt(a, "taskOperationConsistencyScore", StringUtils.hasText(text(s.get("deviceName"))) ? 4 : 2, 4);
        int conflict = Boolean.TRUE.equals(a.get("operationResultConflict")) ? 0 : 3;
        int entity = agentInt(a, "entityTerminologyScore", 2, 3);
        return Math.min(10, task + conflict + entity);
    }

    private int traceScore(Map<String, Object> s, List<Map<String, Object>> steps) {
        int identity = StringUtils.hasText(text(s.get("taskId"))) && StringUtils.hasText(text(s.get("deviceName"))) ? 2 : 1;
        int time = StringUtils.hasText(text(s.get("snapshotGeneratedAt"))) ? 2 : 1;
        return Math.min(5, identity + time + (steps.stream().anyMatch(x -> StringUtils.hasText(text(x.get("completedAt")))) ? 1 : 0));
    }

    private int agentInt(Map<String, Object> a, String key, int fallback, int max) {
        Object x = a == null ? null : a.get(key);
        int value = x instanceof Number n ? n.intValue() : fallback;
        return Math.max(0, Math.min(max, value));
    }
    private String text(Object x) { return x == null ? "" : String.valueOf(x); }
    private List<String> strings(Object x) { if (x instanceof Iterable<?> it) { List<String> out = new ArrayList<>(); it.forEach(v -> { if (v != null && !String.valueOf(v).isBlank()) out.add(String.valueOf(v)); }); return out; } return List.of(); }
    private List<Map<String,Object>> maps(Object x) { if (x instanceof Iterable<?> it) { List<Map<String,Object>> out = new ArrayList<>(); it.forEach(v -> { if (v instanceof Map<?,?> m) { Map<String,Object> n = new LinkedHashMap<>(); m.forEach((k,val)->n.put(String.valueOf(k), val)); out.add(n); } }); return out; } return List.of(); }
    private float clearanceRatio(int n, int total) { return total == 0 ? 0 : Math.min(1f, n / (float) total); }

    public record ReviewResult(int evidenceScore, int totalScore, String evidenceStatus, String decision, Map<String,Object> scores, List<String> reasons) {}
}
