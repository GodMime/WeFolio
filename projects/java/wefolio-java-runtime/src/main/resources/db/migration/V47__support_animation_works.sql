-- ============================================================
-- WeFolio V47 — 支持独立动图作品
-- ============================================================

-- 若未来重算 frame_count，必须在同一条 UPDATE 中同步校正 cover_frame_number。
ALTER TABLE `wf_work`
  DROP CHECK `chk_work_media_type`,
  ADD COLUMN `frame_count` INT UNSIGNED NULL
    COMMENT '动图权威帧数，非动图为空' AFTER `duration_ms`,
  ADD COLUMN `cover_frame_number` INT UNSIGNED NULL
    COMMENT '动图当前封面帧序号，非动图为空' AFTER `frame_count`,
  ADD CONSTRAINT `chk_work_media_type`
    CHECK (`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION')),
  ADD CONSTRAINT `chk_work_animation_frames` CHECK (
    (
      `media_type` = 'ANIMATION'
      AND `frame_count` IS NOT NULL
      AND `cover_frame_number` IS NOT NULL
      AND `frame_count` BETWEEN 2 AND 300
      AND `cover_frame_number` BETWEEN 1 AND `frame_count`
    )
    OR (
      `media_type` <> 'ANIMATION'
      AND `frame_count` IS NULL
      AND `cover_frame_number` IS NULL
    )
  );

ALTER TABLE `wf_work_upload_task`
  DROP CHECK `chk_work_upload_task_media_type`,
  ADD COLUMN `frame_count` INT UNSIGNED NULL
    COMMENT '动图权威帧数，确认前允许为空' AFTER `duration_ms`,
  ADD COLUMN `cover_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '后端生成封面 SHA-256' AFTER `frame_count`,
  ADD CONSTRAINT `chk_work_upload_task_media_type`
    CHECK (`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION')),
  ADD CONSTRAINT `chk_work_upload_task_animation_frames` CHECK (
    (
      `media_type` = 'ANIMATION'
      AND (`frame_count` IS NULL OR `frame_count` BETWEEN 2 AND 300)
    )
    OR (
      `media_type` <> 'ANIMATION'
      AND `frame_count` IS NULL
    )
  );

ALTER TABLE `wf_work_audit_task`
  DROP CHECK `chk_work_audit_task_media_type`,
  ADD COLUMN `sampled_frame_numbers` JSON NULL
    COMMENT '动图审核抽样帧序号，固定为两个升序整数' AFTER `snapshot_count`,
  ADD CONSTRAINT `chk_work_audit_task_media_type`
    CHECK (`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION')),
  ADD CONSTRAINT `chk_work_audit_task_animation_frames` CHECK (
    (
      `media_type` = 'ANIMATION'
      AND `sampled_frame_numbers` IS NOT NULL
      AND JSON_TYPE(`sampled_frame_numbers`) = 'ARRAY'
      AND JSON_LENGTH(`sampled_frame_numbers`) = 2
      AND JSON_TYPE(JSON_EXTRACT(`sampled_frame_numbers`, '$[0]')) = 'INTEGER'
      AND JSON_TYPE(JSON_EXTRACT(`sampled_frame_numbers`, '$[1]')) = 'INTEGER'
      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(`sampled_frame_numbers`, '$[0]')) AS SIGNED)
        BETWEEN 1 AND 300
      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(`sampled_frame_numbers`, '$[1]')) AS SIGNED)
        BETWEEN 1 AND 300
      AND CAST(JSON_UNQUOTE(JSON_EXTRACT(`sampled_frame_numbers`, '$[1]')) AS SIGNED)
        > CAST(JSON_UNQUOTE(JSON_EXTRACT(`sampled_frame_numbers`, '$[0]')) AS SIGNED)
    )
    OR (
      `media_type` <> 'ANIMATION'
      AND `sampled_frame_numbers` IS NULL
    )
  );

SET @animation_rule_effective_from = CURRENT_TIMESTAMP(3);

INSERT INTO `wf_point_rule` (
  `rule_code`, `rule_version`, `rule_name`,
  `transaction_type`, `scene_code`, `group_code`,
  `calc_mode`, `unit_count`, `points_value`, `config_json`,
  `effective_from`, `effective_to`, `status`, `version`,
  `created_at`, `updated_at`, `deleted`
) VALUES (
  'UPLOAD_ANIMATION', 1, '上传动图作品',
  'CONSUMPTION', 'UPLOAD_ANIMATION', 'MAINTENANCE',
  'FIXED_PER_ACTION', 1, 5,
  JSON_OBJECT('source', 'ANIMATION_WORK_SUPPORT_2026_07_30'),
  @animation_rule_effective_from, NULL, 'ACTIVE', 0,
  @animation_rule_effective_from, @animation_rule_effective_from, 0
);
