-- 检修任务编号字段长度迁移
-- 生成格式：MT-yyyyMMdd-<IdWorker ID>，当前实际长度可达 31 位。
USE `fix`;

SET @task_number_length_ddl = IF(
    COALESCE((SELECT CHARACTER_MAXIMUM_LENGTH
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'maintenance_task'
        AND column_name = 'task_number'), 0) <> 40,
    'ALTER TABLE maintenance_task MODIFY COLUMN task_number VARCHAR(40) NOT NULL COMMENT ''任务编号 MT-yyyyMMdd-ID''',
    'SELECT 1'
);
PREPARE task_number_length_statement FROM @task_number_length_ddl;
EXECUTE task_number_length_statement;
DEALLOCATE PREPARE task_number_length_statement;
