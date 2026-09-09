package ai.weixiu.scheduler;

import ai.weixiu.entity.MemoryFact;
import ai.weixiu.entity.MemoryIdempotent;
import ai.weixiu.mapper.MemoryIdempotentMapper;
import ai.weixiu.service.MemoryFactService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆归档调度器。
 *
 * 每天凌晨 3:00 执行：
 * 1. 归档 90 天未使用 + 重要度 ≤ 3 + 置信度 < 0.6 的 active 事实
 * 2. A2 衰减淘汰：≥180 天未命中、importance<8 的非偏好/非待办陈旧事实归档
 * 3. 清理 7 天前的幂等表记录
 * 4. 清理 180 天前的 superseded 事实（物理删除）
 */
@Component
@AllArgsConstructor
@Slf4j
public class MemoryArchiveScheduler {

    /** A2 衰减淘汰：≥此天数未命中(以 last_used_at 计，缺则按 created_at)的非关键事实归档。 */
    private static final int STALE_DAYS = 180;
    /** A2 豁免阈值：importance ≥ 此值视为关键事实，永不按时间衰减归档。 */
    private static final int DECAY_CRITICAL_IMPORTANCE = 8;

    private final MemoryFactService memoryFactService;
    private final MemoryIdempotentMapper idempotentMapper;

    @Scheduled(cron = "0 0 3 * * ?")
    public void archiveAndCleanup() {
        log.info("[归档调度] 开始执行记忆归档和清理...");

        int archived = archiveStaleActiveFacts();
        int decayed = archiveDecayedFacts();
        int idempotentCleaned = cleanIdempotentTable();
        int supersededCleaned = cleanOldSupersededFacts();

        log.info("[归档调度] 完成: 归档(低价值)={}, 衰减淘汰(陈旧)={}, 清理幂等记录={}, 清理过时事实={}",
                archived, decayed, idempotentCleaned, supersededCleaned);
    }

    /**
     * 归档条件：active + 90天未使用 + 重要度≤3 + 置信度<0.6
     * 同时满足才归档，避免误归档重要但低频的事实。
     */
    private int archiveStaleActiveFacts() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(90);

            LambdaUpdateWrapper<MemoryFact> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(MemoryFact::getStatus, "active")
                    .le(MemoryFact::getImportance, 3)
                    .lt(MemoryFact::getConfidence, 0.6)
                    .and(w -> w.isNull(MemoryFact::getLastUsedAt)
                            .or()
                            .lt(MemoryFact::getLastUsedAt, threshold))
                    .set(MemoryFact::getStatus, "archived");

            boolean updated = memoryFactService.update(wrapper);
            if (updated) {
                log.info("[归档] 已归档低价值过期事实");
            }
            return updated ? 1 : 0;
        } catch (Exception e) {
            log.error("[归档] 归档事实失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * A2 衰减淘汰：长期(≥{@link #STALE_DAYS} 天)未命中的"非关键、非偏好、非待办"事实归档。
     *
     * <p>补足 {@link #archiveStaleActiveFacts} 旧门(只淘 importance≤3 且 confidence&lt;0.6)漏掉的
     * "中等重要但早已冷却"的陈旧客观事实。归档 = 软状态(archived)、非物理删，可恢复。</p>
     *
     * <p>豁免：importance≥{@link #DECAY_CRITICAL_IMPORTANCE}(关键事实/结论)、type=user(画像/偏好)、
     * type=unresolved(待办)。陈旧判定用 COALESCE(last_used_at, created_at) &lt; 阈值——以 created_at 兜底，
     * 避免误伤"刚写入还没机会被读"的新事实。</p>
     */
    private int archiveDecayedFacts() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(STALE_DAYS);

            // 选出命中行（条件写一次），再按 id 批量归档；顺带拿到准确条数便于观测。
            LambdaQueryWrapper<MemoryFact> query = new LambdaQueryWrapper<>();
            query.eq(MemoryFact::getStatus, "active")
                    .lt(MemoryFact::getImportance, DECAY_CRITICAL_IMPORTANCE)
                    .notIn(MemoryFact::getType, "user", "unresolved")
                    .and(w -> w
                            .and(x -> x.isNotNull(MemoryFact::getLastUsedAt).lt(MemoryFact::getLastUsedAt, threshold))
                            .or(x -> x.isNull(MemoryFact::getLastUsedAt).lt(MemoryFact::getCreatedAt, threshold)));

            List<MemoryFact> matched = memoryFactService.list(query);
            if (matched.isEmpty()) {
                return 0;
            }
            List<Long> ids = matched.stream().map(MemoryFact::getId).toList();
            LambdaUpdateWrapper<MemoryFact> update = new LambdaUpdateWrapper<>();
            update.in(MemoryFact::getId, ids).set(MemoryFact::getStatus, "archived");
            memoryFactService.update(update);
            log.info("[归档] A2 衰减淘汰: 归档 {} 条长期(≥{}天)未命中的陈旧事实", ids.size(), STALE_DAYS);
            return ids.size();
        } catch (Exception e) {
            log.error("[归档] A2 衰减淘汰失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 清理 7 天前的幂等记录 */
    private int cleanIdempotentTable() {
        try {
            LambdaQueryWrapper<MemoryIdempotent> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(MemoryIdempotent::getCreatedAt, LocalDateTime.now().minusDays(7));
            List<MemoryIdempotent> old = idempotentMapper.selectList(wrapper);
            if (!old.isEmpty()) {
                List<String> ids = old.stream().map(MemoryIdempotent::getMessageId).toList();
                idempotentMapper.deleteBatchIds(ids);
                log.info("[归档] 清理过期幂等记录: {}条", ids.size());
                return ids.size();
            }
            return 0;
        } catch (Exception e) {
            log.error("[归档] 清理幂等表失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 物理删除 180 天前的 superseded 事实 */
    private int cleanOldSupersededFacts() {
        try {
            LambdaQueryWrapper<MemoryFact> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MemoryFact::getStatus, "superseded")
                    .lt(MemoryFact::getSupersededAt, LocalDateTime.now().minusDays(180));
            List<MemoryFact> old = memoryFactService.list(wrapper);
            if (!old.isEmpty()) {
                List<Long> ids = old.stream().map(MemoryFact::getId).toList();
                memoryFactService.removeByIds(ids);
                log.info("[归档] 物理删除过时事实: {}条", ids.size());
                return ids.size();
            }
            return 0;
        } catch (Exception e) {
            log.error("[归档] 清理过时事实失败: {}", e.getMessage());
            return 0;
        }
    }
}
