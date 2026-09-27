-- 已完成检修任务自动审核 v1
USE `fix`;

-- MySQL 8.0 versions used by local deployments do not all support
-- ALTER TABLE ... ADD COLUMN IF NOT EXISTS. Keep this migration rerunnable
-- by checking information_schema and executing only missing DDL.
SET @auto_review_status_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND column_name = 'auto_review_status') = 0,
    'ALTER TABLE maintenance_task ADD COLUMN auto_review_status VARCHAR(24) NULL COMMENT ''自动审核状态'' AFTER updated_at',
    'SELECT 1'
);
PREPARE auto_review_status_statement FROM @auto_review_status_ddl;
EXECUTE auto_review_status_statement;
DEALLOCATE PREPARE auto_review_status_statement;

SET @auto_review_evidence_status_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND column_name = 'auto_review_evidence_status') = 0,
    'ALTER TABLE maintenance_task ADD COLUMN auto_review_evidence_status VARCHAR(20) NULL COMMENT ''证据状态: SUFFICIENT/INSUFFICIENT'' AFTER auto_review_status',
    'SELECT 1'
);
PREPARE auto_review_evidence_status_statement FROM @auto_review_evidence_status_ddl;
EXECUTE auto_review_evidence_status_statement;
DEALLOCATE PREPARE auto_review_evidence_status_statement;

SET @auto_review_score_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND column_name = 'auto_review_score') = 0,
    'ALTER TABLE maintenance_task ADD COLUMN auto_review_score INT NULL COMMENT ''自动审核综合分'' AFTER auto_review_evidence_status',
    'SELECT 1'
);
PREPARE auto_review_score_statement FROM @auto_review_score_ddl;
EXECUTE auto_review_score_statement;
DEALLOCATE PREPARE auto_review_score_statement;

SET @auto_review_evidence_score_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND column_name = 'auto_review_evidence_score') = 0,
    'ALTER TABLE maintenance_task ADD COLUMN auto_review_evidence_score INT NULL COMMENT ''证据完整度分'' AFTER auto_review_score',
    'SELECT 1'
);
PREPARE auto_review_evidence_score_statement FROM @auto_review_evidence_score_ddl;
EXECUTE auto_review_evidence_score_statement;
DEALLOCATE PREPARE auto_review_evidence_score_statement;

SET @auto_review_reason_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND column_name = 'auto_review_reason') = 0,
    'ALTER TABLE maintenance_task ADD COLUMN auto_review_reason TEXT NULL COMMENT ''自动审核说明'' AFTER auto_review_evidence_score',
    'SELECT 1'
);
PREPARE auto_review_reason_statement FROM @auto_review_reason_ddl;
EXECUTE auto_review_reason_statement;
DEALLOCATE PREPARE auto_review_reason_statement;

SET @auto_review_updated_at_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND column_name = 'auto_review_updated_at') = 0,
    'ALTER TABLE maintenance_task ADD COLUMN auto_review_updated_at DATETIME NULL COMMENT ''自动审核更新时间'' AFTER auto_review_reason',
    'SELECT 1'
);
PREPARE auto_review_updated_at_statement FROM @auto_review_updated_at_ddl;
EXECUTE auto_review_updated_at_statement;
DEALLOCATE PREPARE auto_review_updated_at_statement;

CREATE TABLE IF NOT EXISTS `task_auto_review` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `evidence_version` INT NOT NULL COMMENT '证据快照版本',
    `request_id` VARCHAR(128) NOT NULL COMMENT '幂等请求号',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/AUTO_ARCHIVED/MANUAL_REVIEW/FAILED/MANUAL_APPROVED/MANUAL_REJECTED',
    `evidence_status` VARCHAR(20) NULL COMMENT 'SUFFICIENT/INSUFFICIENT',
    `evidence_score` INT NULL COMMENT '证据完整度分',
    `total_score` INT NULL COMMENT '综合分',
    `dimension_scores` JSON NULL COMMENT '六项分数',
    `agent_result` JSON NULL COMMENT 'Agent结构化结果',
    `decision_reasons` JSON NULL COMMENT '决策原因',
    `rule_version` VARCHAR(64) NOT NULL,
    `model_name` VARCHAR(128) NULL,
    `model_request_id` VARCHAR(128) NULL,
    `error_message` TEXT NULL,
    `reviewed_by` BIGINT NULL,
    `review_comment` TEXT NULL,
    `reviewed_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_review_version` (`task_id`, `evidence_version`),
    UNIQUE KEY `uk_task_review_request` (`request_id`),
    KEY `idx_task_review_status` (`status`),
    KEY `idx_task_review_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检修任务自动审核记录';

-- 管理端按自动审核状态筛选任务，给已有库补查询索引。
SET @task_auto_review_status_index_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'maintenance_task'
       AND index_name = 'idx_task_auto_review_status') = 0,
    'ALTER TABLE maintenance_task ADD INDEX idx_task_auto_review_status (auto_review_status)',
    'SELECT 1'
);
PREPARE task_auto_review_status_index_statement FROM @task_auto_review_status_index_ddl;
EXECUTE task_auto_review_status_index_statement;
DEALLOCATE PREPARE task_auto_review_status_index_statement;

-- 兼容已经执行过旧版建表语句的数据库。
SET @task_review_created_index_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'task_auto_review'
       AND index_name = 'idx_task_review_created') = 0,
    'ALTER TABLE task_auto_review ADD INDEX idx_task_review_created (created_at)',
    'SELECT 1'
);
PREPARE task_review_created_index_statement FROM @task_review_created_index_ddl;
EXECUTE task_review_created_index_statement;
DEALLOCATE PREPARE task_review_created_index_statement;
