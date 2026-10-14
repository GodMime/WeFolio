-- 视频音频只保存转写纯文本；未生成与已处理无文本通过 NULL/空字符串区分。
ALTER TABLE `wf_work`
    ADD COLUMN `video_audio_text` MEDIUMTEXT
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        DEFAULT NULL COMMENT '视频音频转写纯文本；NULL未生成，空字符串表示已处理无文本'
        AFTER `duration_ms`,
    ADD CONSTRAINT `chk_work_video_audio_text_media`
        CHECK (`video_audio_text` IS NULL OR `media_type` = 'VIDEO');
