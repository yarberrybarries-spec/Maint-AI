-- 检修任务步骤局部修订增量迁移
USE `fix`;

SET @revision_state_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='task_step_record' AND column_name='revision_state')=0,
    'ALTER TABLE task_step_record ADD COLUMN revision_state VARCHAR(32) NOT NULL DEFAULT ''NONE'' COMMENT ''最近修订状态'' AFTER ai_reason', 'SELECT 1');
PREPARE revision_state_statement FROM @revision_state_ddl; EXECUTE revision_state_statement; DEALLOCATE PREPARE revision_state_statement;

SET @revision_notice_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='task_step_record' AND column_name='revision_notice')=0,
    'ALTER TABLE task_step_record ADD COLUMN revision_notice VARCHAR(255) NULL COMMENT ''最近修订提示'' AFTER revision_state', 'SELECT 1');
PREPARE revision_notice_statement FROM @revision_notice_ddl; EXECUTE revision_notice_statement; DEALLOCATE PREPARE revision_notice_statement;

SET @last_revision_id_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='task_step_record' AND column_name='last_revision_id')=0,
    'ALTER TABLE task_step_record ADD COLUMN last_revision_id BIGINT NULL COMMENT ''最近修订请求ID'' AFTER revision_notice', 'SELECT 1');
PREPARE last_revision_id_statement FROM @last_revision_id_ddl; EXECUTE last_revision_id_statement; DEALLOCATE PREPARE last_revision_id_statement;

SET @last_revision_at_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='task_step_record' AND column_name='last_revision_at')=0,
    'ALTER TABLE task_step_record ADD COLUMN last_revision_at DATETIME NULL COMMENT ''最近修订时间'' AFTER last_revision_id', 'SELECT 1');
PREPARE last_revision_at_statement FROM @last_revision_at_ddl; EXECUTE last_revision_at_statement; DEALLOCATE PREPARE last_revision_at_statement;

CREATE TABLE IF NOT EXISTS `task_step_revision` (
    `id` BIGINT NOT NULL,
    `task_id` BIGINT NOT NULL,
    `requester_id` BIGINT NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ANALYZING',
    `request_text` TEXT NOT NULL,
    `recognized_scope` JSON NULL,
    `agent_result` JSON NULL,
    `before_snapshot` JSON NULL,
    `after_snapshot` JSON NULL,
    `result_summary` TEXT NULL,
    `error_message` TEXT NULL,
    `expires_at` DATETIME NULL,
    `confirmed_at` DATETIME NULL,
    `applied_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_step_revision_task` (`task_id`, `created_at`),
    KEY `idx_step_revision_status` (`status`),
    KEY `idx_step_revision_task_status` (`task_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检修任务步骤局部修订记录';

-- 兼容已经执行过旧版建表语句的数据库：CREATE TABLE IF NOT EXISTS 不会补索引。
SET @revision_task_status_index_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='task_step_revision'
       AND index_name='idx_step_revision_task_status')=0,
    'ALTER TABLE task_step_revision ADD INDEX idx_step_revision_task_status (task_id, status)',
    'SELECT 1');
PREPARE revision_task_status_index_statement FROM @revision_task_status_index_ddl;
EXECUTE revision_task_status_index_statement;
DEALLOCATE PREPARE revision_task_status_index_statement;
