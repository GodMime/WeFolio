-- 原视频摘要仅新增一个可空字段，完整来源身份保存在版本化 JSON 中。
ALTER TABLE `wf_work`
    ADD COLUMN `video_summary_json` JSON DEFAULT NULL
        COMMENT '原视频理解摘要，版本化内容和源身份；NULL表示未生成或失效'
        AFTER `video_audio_text`,
    ADD CONSTRAINT `chk_work_video_summary_media`
        CHECK (`video_summary_json` IS NULL OR `media_type` = 'VIDEO');
