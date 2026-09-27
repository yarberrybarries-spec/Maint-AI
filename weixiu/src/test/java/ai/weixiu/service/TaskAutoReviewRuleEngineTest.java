package ai.weixiu.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskAutoReviewRuleEngineTest {
    private final TaskAutoReviewRuleEngine engine = new TaskAutoReviewRuleEngine();

    @Test
    void completeEvidenceAndSemanticFactsCanReachAutoArchive() {
        Map<String,Object> step = new LinkedHashMap<>();
        step.put("status", "COMPLETED");
        step.put("note", "关闭电源后拆卸滤芯并更换同规格部件，通电测试正常");
        step.put("images", List.of("https://example.test/step.jpg"));
        step.put("completedAt", "2026-09-21T10:00:00");
        step.put("sources", List.of(Map.of("type", "manual", "documentId", "manual-1")));

        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", 1L);
        snapshot.put("deviceName", "液压泵");
        snapshot.put("faultDescription", "滤芯堵塞");
        snapshot.put("reportImages", List.of("https://example.test/before.jpg"));
        snapshot.put("completionSummary", "更换滤芯后运行测试正常");
        snapshot.put("effectiveMeasure", "更换同规格滤芯");
        snapshot.put("snapshotGeneratedAt", "2026-09-21T10:01:00");
        snapshot.put("steps", List.of(step));

        Map<String,Object> facts = new LinkedHashMap<>();
        facts.put("stepMappingScore", 4);
        facts.put("resultMappingScore", 2);
        facts.put("evidenceChainScore", 1);
        facts.put("operationSpecificityScore", 5);
        facts.put("exceptionHandlingScore", 3);
        facts.put("functionalTestScore", 6);
        facts.put("followupScore", 3);
        facts.put("manualStepMatchScore", 7);
        facts.put("parameterSafetyScore", 5);
        facts.put("exceptionNormScore", 3);
        facts.put("taskOperationConsistencyScore", 4);
        facts.put("entityTerminologyScore", 3);

        TaskAutoReviewRuleEngine.ReviewResult result = engine.evaluate(snapshot, facts);
        assertEquals("SUFFICIENT", result.evidenceStatus());
        assertEquals("AUTO_ARCHIVED", result.decision());
        assertTrue(result.evidenceScore() >= 12);
        assertTrue(result.totalScore() >= 70);
    }

    @Test
    void emptyEvidenceCannotAutoArchiveEvenWhenAgentFactsAreOptimistic() {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", 2L);
        snapshot.put("deviceName", "设备");
        snapshot.put("steps", List.of());
        Map<String,Object> facts = Map.of("taskOperationConsistencyScore", 4, "entityTerminologyScore", 3);

        TaskAutoReviewRuleEngine.ReviewResult result = engine.evaluate(snapshot, facts);
        assertEquals("INSUFFICIENT", result.evidenceStatus());
        assertEquals("MANUAL_REVIEW", result.decision());
    }
}
