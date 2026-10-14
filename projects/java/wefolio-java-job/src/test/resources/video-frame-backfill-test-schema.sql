CREATE TABLE wf_user(id bigint PRIMARY KEY,unique_code varchar(32),status varchar(32),deleted bigint DEFAULT 0);
CREATE TABLE wf_work(id bigint PRIMARY KEY,user_id bigint,media_type varchar(32),status varchar(32),
 media_object_key varchar(512),media_sha256 varchar(64),duration_ms int,video_audio_text CLOB,video_summary_json CLOB,ai_media_summary_json CLOB,ai_media_summary_expires_at timestamp,video_frames_json varchar(16000),
 version int DEFAULT 0,deleted bigint DEFAULT 0,updated_at timestamp,audit_status varchar(32));
