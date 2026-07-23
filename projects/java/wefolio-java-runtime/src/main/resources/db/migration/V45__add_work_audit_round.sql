-- 作品当前审核轮次和面向应用的稳定风险类型。
ALTER TABLE `wf_work`
    ADD COLUMN `audit_round` INT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '当前已经进入或即将进入的审核轮次' AFTER `audit_status`,
    ADD COLUMN `audit_reason_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '与审核供应商解耦的稳定风险类型' AFTER `audit_round`,
    ADD COLUMN `audit_reason_codes` JSON NULL
        COMMENT '当前轮次全部稳定风险类型，最多20个' AFTER `audit_reason_code`,
    ADD CONSTRAINT `chk_work_audit_round` CHECK (`audit_round` >= 1),
    ADD CONSTRAINT `chk_work_audit_reason_codes` CHECK (
        `audit_reason_codes` IS NULL OR (
            JSON_TYPE(`audit_reason_codes`) = 'ARRAY'
            AND JSON_LENGTH(`audit_reason_codes`) <= 20
        )
    );

-- 任务继承作品轮次；历史空摘要必须在执行本迁移前由独立运维 SQL 可靠修复。
ALTER TABLE `wf_work_audit_task`
    ADD COLUMN `audit_round` INT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '任务所属作品审核轮次' AFTER `media_sha256`,
    MODIFY COLUMN `media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '媒体文件 SHA-256',
    ADD CONSTRAINT `chk_work_audit_task_round` CHECK (`audit_round` >= 1),
    DROP INDEX `uk_work_audit_task_media`,
    ADD UNIQUE KEY `uk_work_audit_task_media_round` (`work_id`, `media_sha256`, `audit_round`, `deleted`);
