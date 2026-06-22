-- ============================================================
-- WeFolio V2 — 全量表结构
-- 数据库: MySQL 8.0+
-- 引擎: InnoDB, 字符集: utf8mb4, 排序: utf8mb4_unicode_ci
-- 枚举字段使用 ascii_bin 排序规则保证大小写敏感
-- ============================================================

SET NAMES utf8mb4;

-- 1. 用户表
CREATE TABLE `wf_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `unique_code` VARCHAR(16) NOT NULL COMMENT '个人唯一码',
  `nickname` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '昵称/姓名/艺名',
  `avatar_url` VARCHAR(512) NULL COMMENT '头像地址',
  `profession` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '职业身份',
  `city` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '城市或服务区域',
  `intro` VARCHAR(500) NULL COMMENT '个人简介',
  `wechat_qr_url` VARCHAR(512) NULL COMMENT '微信二维码地址',
  `contact_phone_ciphertext` VARCHAR(512) NULL COMMENT '资料联系电话密文',
  `profile_tags` JSON NULL COMMENT '展示标签数组',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE正常 DISABLED禁用',
  `phone_bound_at` DATETIME(3) NULL COMMENT '手机号绑定时间',
  `registered_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '注册时间',
  `last_login_at` DATETIME(3) NULL COMMENT '最近登录时间',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted_at` DATETIME(3) NULL COMMENT '逻辑删除时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_unique_code` (`unique_code`),
  KEY `idx_user_status_created` (`status`, `created_at`),
  CONSTRAINT `chk_user_status` CHECK (`status` IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='用户';

-- 2. 用户登录身份表
CREATE TABLE `wf_user_auth` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `auth_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'WECHAT_MINI_APP PHONE_OTP PHONE_PASSWORD',
  `identifier_hash` CHAR(64) NOT NULL COMMENT '身份标识HMAC摘要',
  `identifier_ciphertext` VARCHAR(512) NOT NULL COMMENT '身份标识密文',
  `union_identifier_hash` CHAR(64) NULL COMMENT 'unionid HMAC摘要',
  `union_identifier_ciphertext` VARCHAR(512) NULL COMMENT 'unionid密文',
  `credential_hash` VARCHAR(255) NULL COMMENT '密码摘要',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE正常 DISABLED禁用',
  `last_authenticated_at` DATETIME(3) NULL COMMENT '最近认证时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_type_identifier` (`auth_type`, `identifier_hash`),
  UNIQUE KEY `uk_auth_union_identifier` (`union_identifier_hash`),
  KEY `idx_auth_user` (`user_id`, `status`),
  CONSTRAINT `chk_auth_type` CHECK (
    `auth_type` IN ('WECHAT_MINI_APP', 'PHONE_OTP', 'PHONE_PASSWORD')
  ),
  CONSTRAINT `chk_auth_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT `chk_auth_credential` CHECK (
    (`auth_type` <> 'PHONE_PASSWORD') OR (`credential_hash` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='用户登录身份';

-- 3. 推荐关系表
CREATE TABLE `wf_referral_relation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `referrer_user_id` BIGINT UNSIGNED NOT NULL COMMENT '推荐人用户ID',
  `referred_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被推荐用户ID',
  `referral_code_snapshot` VARCHAR(16) NOT NULL COMMENT '推荐码快照',
  `bound_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '绑定时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_referral_referred_user` (`referred_user_id`),
  KEY `idx_referral_referrer_time` (`referrer_user_id`, `bound_at`),
  CONSTRAINT `chk_referral_not_self` CHECK (`referrer_user_id` <> `referred_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='首次注册推荐关系';

-- 4. 作品表
CREATE TABLE `wf_work` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',
  `media_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'IMAGE图片 VIDEO视频',
  `title` VARCHAR(30) NOT NULL COMMENT '作品标题',
  `original_file_name` VARCHAR(255) NULL COMMENT '原始文件名',
  `media_object_key` VARCHAR(512) NOT NULL COMMENT 'COS媒体对象键',
  `cover_object_key` VARCHAR(512) NULL COMMENT 'COS封面对象键',
  `mime_type` VARCHAR(100) NULL COMMENT 'MIME类型',
  `file_size` BIGINT UNSIGNED NULL COMMENT '文件字节数',
  `duration_ms` INT UNSIGNED NULL COMMENT '视频时长毫秒',
  `width` INT UNSIGNED NULL COMMENT '像素宽度',
  `height` INT UNSIGNED NULL COMMENT '像素高度',
  `description` VARCHAR(1000) NULL COMMENT '作品说明',
  `service_date` DATE NULL COMMENT '拍摄或服务日期',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE PROCESSING PROCESSING_FAILED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted_at` DATETIME(3) NULL COMMENT '逻辑删除时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_work_user_list` (`user_id`, `deleted`, `sort_order`, `id`),
  KEY `idx_work_user_title` (`user_id`, `title`),
  KEY `idx_work_user_media` (`user_id`, `media_type`, `deleted`),
  CONSTRAINT `chk_work_media_type` CHECK (`media_type` IN ('IMAGE', 'VIDEO')),
  CONSTRAINT `chk_work_status` CHECK (
    `status` IN ('ACTIVE', 'PROCESSING', 'PROCESSING_FAILED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='图片和视频作品';

-- 5. 标签表
CREATE TABLE `wf_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',
  `name` VARCHAR(30) NOT NULL COMMENT '标签名称',
  `color` CHAR(7) NULL COMMENT '十六进制颜色',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE启用 DISABLED停用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_user_name` (`user_id`, `name`),
  KEY `idx_tag_user_status` (`user_id`, `status`, `id`),
  CONSTRAINT `chk_tag_status` CHECK (`status` IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品标签';

-- 6. 作品标签关联表
CREATE TABLE `wf_work_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `work_id` BIGINT UNSIGNED NOT NULL COMMENT '作品ID',
  `tag_id` BIGINT UNSIGNED NOT NULL COMMENT '标签ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_work_tag` (`work_id`, `tag_id`),
  KEY `idx_work_tag_tag` (`tag_id`, `work_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品标签关联';

-- 7. 档位定义表
CREATE TABLE `wf_slot_definition` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',
  `name` VARCHAR(30) NOT NULL COMMENT '档位名称',
  `start_time` TIME NOT NULL COMMENT '开始时间',
  `end_time` TIME NOT NULL COMMENT '结束时间',
  `color` CHAR(7) NOT NULL COMMENT '展示颜色',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值',
  `is_system_default` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否系统默认',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE启用 DISABLED停用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slot_user_name` (`user_id`, `name`),
  KEY `idx_slot_user_status_sort` (`user_id`, `status`, `sort_order`, `id`),
  CONSTRAINT `chk_slot_time` CHECK (`start_time` < `end_time`),
  CONSTRAINT `chk_slot_default` CHECK (`is_system_default` IN (0, 1)),
  CONSTRAINT `chk_slot_status` CHECK (`status` IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='可复用档位定义';

-- 8. 档期表
CREATE TABLE `wf_schedule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',
  `schedule_date` DATE NOT NULL COMMENT '档期日期',
  `slot_definition_id` BIGINT UNSIGNED NOT NULL COMMENT '档位定义ID',
  `slot_name_snapshot` VARCHAR(30) NOT NULL COMMENT '档位名称快照',
  `start_time_snapshot` TIME NOT NULL COMMENT '开始时间快照',
  `end_time_snapshot` TIME NOT NULL COMMENT '结束时间快照',
  `color_snapshot` CHAR(7) NOT NULL COMMENT '颜色快照',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE BOOKED TENTATIVE REST',
  `contact_name_ciphertext` VARCHAR(512) NULL COMMENT '联系人姓名密文',
  `contact_phone_ciphertext` VARCHAR(512) NULL COMMENT '联系电话密文',
  `note` VARCHAR(1000) NULL COMMENT '内部备注',
  `locked_snapshot` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否锁定快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_user_date_slot`
    (`user_id`, `schedule_date`, `slot_definition_id`),
  KEY `idx_schedule_user_date_status` (`user_id`, `schedule_date`, `status`),
  KEY `idx_schedule_slot_date` (`slot_definition_id`, `schedule_date`),
  CONSTRAINT `chk_schedule_time` CHECK (`start_time_snapshot` < `end_time_snapshot`),
  CONSTRAINT `chk_schedule_status` CHECK (
    `status` IN ('AVAILABLE', 'BOOKED', 'TENTATIVE', 'REST')
  ),
  CONSTRAINT `chk_schedule_locked` CHECK (`locked_snapshot` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='具体日期档期';

-- 9. 团队表
CREATE TABLE `wf_team` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `unique_code` VARCHAR(16) NOT NULL COMMENT '团队唯一码',
  `name` VARCHAR(100) NOT NULL COMMENT '团队名称',
  `avatar_url` VARCHAR(512) NULL COMMENT '团队头像',
  `intro` VARCHAR(1000) NULL COMMENT '团队简介',
  `city` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '城市或服务区域',
  `contact_qr_url` VARCHAR(512) NULL COMMENT '团队联系二维码',
  `owner_user_id` BIGINT UNSIGNED NOT NULL COMMENT '当前拥有者用户ID',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE正常 DISSOLVED已解散',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted_at` DATETIME(3) NULL COMMENT '逻辑删除时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_unique_code` (`unique_code`),
  KEY `idx_team_owner_status` (`owner_user_id`, `status`),
  CONSTRAINT `chk_team_status` CHECK (`status` IN ('ACTIVE', 'DISSOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='团队';

-- 10. 团队成员表
CREATE TABLE `wf_team_member` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_id` BIGINT UNSIGNED NOT NULL COMMENT '团队ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '成员用户ID',
  `role` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER MANAGER MEMBER',
  `profession` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '团队内职业',
  `join_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING_CONFIRMATION'
    COMMENT 'PENDING_CONFIRMATION JOINED REJECTED REMOVED',
  `allow_portfolio` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '允许引用个人作品集',
  `allow_profile` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '允许引用头像资料',
  `allow_works` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '允许引用个人作品',
  `invited_by` BIGINT UNSIGNED NULL COMMENT '邀请人用户ID',
  `invited_at` DATETIME(3) NULL COMMENT '邀请时间',
  `responded_at` DATETIME(3) NULL COMMENT '响应时间',
  `joined_at` DATETIME(3) NULL COMMENT '加入时间',
  `removed_at` DATETIME(3) NULL COMMENT '移除时间',
  `removal_reason` VARCHAR(255) NULL COMMENT '移除原因',
  `active_owner_marker` TINYINT UNSIGNED GENERATED ALWAYS AS (
    CASE WHEN (`role` = 'OWNER' AND `join_status` = 'JOINED') THEN 1 ELSE NULL END
  ) STORED COMMENT '有效拥有者唯一标记',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_member` (`team_id`, `user_id`),
  UNIQUE KEY `uk_team_active_owner` (`team_id`, `active_owner_marker`),
  KEY `idx_team_member_user` (`user_id`, `join_status`, `team_id`),
  KEY `idx_team_member_manage` (`team_id`, `join_status`, `role`, `user_id`),
  CONSTRAINT `chk_team_member_role` CHECK (`role` IN ('OWNER', 'MANAGER', 'MEMBER')),
  CONSTRAINT `chk_team_member_status` CHECK (
    `join_status` IN ('PENDING_CONFIRMATION', 'JOINED', 'REJECTED', 'REMOVED')
  ),
  CONSTRAINT `chk_team_member_permissions` CHECK (
    `allow_portfolio` IN (0, 1)
    AND `allow_profile` IN (0, 1)
    AND `allow_works` IN (0, 1)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='团队成员关系';

-- 11. 作品集表
CREATE TABLE `wf_portfolio` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `share_code` VARCHAR(32) NOT NULL COMMENT '分享路径编码',
  `owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'USER用户 TEAM团队',
  `owner_id` BIGINT UNSIGNED NOT NULL COMMENT '归属用户或团队ID',
  `template_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'STANDARD标准 ADVANCED高级',
  `title` VARCHAR(100) NOT NULL COMMENT '作品集标题',
  `intro` VARCHAR(500) NULL COMMENT '作品集简介',
  `share_cover_url` VARCHAR(512) NULL COMMENT '分享封面',
  `share_avatar_url` VARCHAR(512) NULL COMMENT '分享头像',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE生效 DISABLED停用',
  `schema_version` VARCHAR(20) NOT NULL DEFAULT '1.0' COMMENT '当前Schema版本',
  `schema_json` JSON NOT NULL COMMENT '当前生效页面配置',
  `ai_prompt` TEXT NULL COMMENT '当前AI自然语言描述',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL AI_GENERATED RESTORED_FROM_HISTORY',
  `content_hash` CHAR(64) NOT NULL COMMENT '当前配置SHA256',
  `current_revision` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前保存修订号',
  `previewed_at` DATETIME(3) NULL COMMENT '最近预览时间',
  `last_saved_by` BIGINT UNSIGNED NOT NULL COMMENT '最近保存人用户ID',
  `last_saved_at` DATETIME(3) NOT NULL COMMENT '最近保存时间',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted_at` DATETIME(3) NULL COMMENT '逻辑删除时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_portfolio_share_code` (`share_code`),
  KEY `idx_portfolio_owner_list`
    (`owner_type`, `owner_id`, `deleted`, `status`, `updated_at`),
  KEY `idx_portfolio_status_saved` (`status`, `last_saved_at`),
  CONSTRAINT `chk_portfolio_owner_type` CHECK (`owner_type` IN ('USER', 'TEAM')),
  CONSTRAINT `chk_portfolio_template_type` CHECK (
    `template_type` IN ('STANDARD', 'ADVANCED')
  ),
  CONSTRAINT `chk_portfolio_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT `chk_portfolio_source` CHECK (
    `source_type` IN ('MANUAL', 'AI_GENERATED', 'RESTORED_FROM_HISTORY')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品集当前生效配置';

-- 12. 作品集历史保存表
CREATE TABLE `wf_portfolio_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '作品集ID',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '保存修订号',
  `schema_version` VARCHAR(20) NOT NULL DEFAULT '1.0' COMMENT 'Schema版本',
  `snapshot_json` JSON NOT NULL COMMENT '完整保存快照',
  `ai_prompt` TEXT NULL COMMENT '本次AI自然语言描述',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL AI_GENERATED RESTORED_FROM_HISTORY',
  `content_hash` CHAR(64) NOT NULL COMMENT '快照内容SHA256',
  `saved_by` BIGINT UNSIGNED NOT NULL COMMENT '保存人用户ID',
  `saved_at` DATETIME(3) NOT NULL COMMENT '保存时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_portfolio_history_revision` (`portfolio_id`, `revision_no`),
  KEY `idx_portfolio_history_saver` (`saved_by`, `saved_at`),
  CONSTRAINT `chk_portfolio_history_source` CHECK (
    `source_type` IN ('MANUAL', 'AI_GENERATED', 'RESTORED_FROM_HISTORY')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品集历史保存快照';

-- 13. 作品集当前引用表
CREATE TABLE `wf_portfolio_reference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '当前生效作品集ID',
  `reference_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'WORK MEMBER_PORTFOLIO USER_PROFILE TEAM_PROFILE SCHEDULE_COMPONENT QR_CODE_ASSET',
  `reference_id` BIGINT UNSIGNED NOT NULL COMMENT '被引用业务记录ID',
  `component_key` VARCHAR(64) NOT NULL COMMENT '组件实例键',
  `component_path` VARCHAR(255) NOT NULL COMMENT 'Schema内引用位置',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '组件内排序',
  `snapshot_json` JSON NULL COMMENT '必要展示快照',
  `is_valid` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '引用是否有效',
  `invalid_reason` VARCHAR(255) NULL COMMENT '失效原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_portfolio_reference`
    (`portfolio_id`, `component_path`, `reference_type`, `reference_id`),
  KEY `idx_reference_target`
    (`reference_type`, `reference_id`, `is_valid`, `portfolio_id`),
  KEY `idx_reference_portfolio` (`portfolio_id`, `sort_order`, `id`),
  CONSTRAINT `chk_reference_type` CHECK (
    `reference_type` IN (
      'WORK', 'MEMBER_PORTFOLIO', 'USER_PROFILE',
      'TEAM_PROFILE', 'SCHEDULE_COMPONENT', 'QR_CODE_ASSET'
    )
  ),
  CONSTRAINT `chk_reference_valid` CHECK (`is_valid` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品集当前资源引用';

-- 14. AI生成任务表
CREATE TABLE `wf_ai_generation_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '目标作品集ID',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '发起人用户ID',
  `applied_revision` INT UNSIGNED NULL COMMENT '保存生效后的修订号',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '请求幂等键',
  `prompt` TEXT NOT NULL COMMENT '自然语言描述',
  `selected_sources_json` JSON NOT NULL COMMENT '素材选择快照',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING RUNNING SUCCEEDED FAILED TIMED_OUT',
  `result_schema_json` JSON NULL COMMENT '校验通过的生成结果',
  `error_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '错误码',
  `error_message` VARCHAR(500) NULL COMMENT '脱敏错误摘要',
  `cost_points` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际扣除积分',
  `started_at` DATETIME(3) NULL COMMENT '开始时间',
  `completed_at` DATETIME(3) NULL COMMENT '完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_task_idempotency` (`idempotency_key`),
  KEY `idx_ai_task_portfolio` (`portfolio_id`, `status`, `created_at`),
  KEY `idx_ai_task_requester` (`requested_by`, `created_at`),
  CONSTRAINT `chk_ai_task_status` CHECK (
    `status` IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')
  ),
  CONSTRAINT `chk_ai_task_cost` CHECK (`status` = 'SUCCEEDED' OR `cost_points` = 0),
  CONSTRAINT `chk_ai_error_code_format` CHECK (
    `error_code` IS NULL OR `error_code` REGEXP '^[A-Z][A-Z0-9_]*$'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='高级作品集AI生成任务';

-- 15. 分享记录表
CREATE TABLE `wf_portfolio_share_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '作品集ID',
  `portfolio_revision` INT UNSIGNED NOT NULL COMMENT '分享时生效修订号',
  `shared_by_user_id` BIGINT UNSIGNED NOT NULL COMMENT '分享人用户ID',
  `share_channel` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'WECHAT_CARD QR_CODE COPIED_PATH',
  `share_scene` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '分享场景编码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '分享时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_share_portfolio_time` (`portfolio_id`, `created_at`),
  KEY `idx_share_user_time` (`shared_by_user_id`, `created_at`),
  CONSTRAINT `chk_share_channel` CHECK (
    `share_channel` IN ('WECHAT_CARD', 'QR_CODE', 'COPIED_PATH')
  ),
  CONSTRAINT `chk_share_scene_format` CHECK (
    `share_scene` IS NULL OR `share_scene` REGEXP '^[A-Z][A-Z0-9_]*$'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品集分享记录';

-- 16. 访问汇总表
CREATE TABLE `wf_visit_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visitor_key` CHAR(64) NOT NULL COMMENT '匿名访客稳定摘要',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '被访问作品集ID',
  `last_portfolio_revision` INT UNSIGNED NOT NULL COMMENT '最近访问生效修订号',
  `portfolio_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'PERSONAL个人 TEAM团队',
  `owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'USER用户 TEAM团队',
  `owner_id` BIGINT UNSIGNED NOT NULL COMMENT '记录归属用户或团队ID',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'WECHAT_SHARE_CARD QR_CODE TEAM_PORTFOLIO PERSONAL_PORTFOLIO UNKNOWN',
  `source_portfolio_id` BIGINT UNSIGNED NULL COMMENT '最近来源作品集ID',
  `source_portfolio_title_snapshot` VARCHAR(100) NULL COMMENT '最近来源标题快照',
  `source_portfolio_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NULL COMMENT 'PERSONAL个人 TEAM团队',
  `visit_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计打开次数',
  `view_work_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计查看作品次数',
  `play_video_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计播放视频次数',
  `schedule_query_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计档期查询次数',
  `qr_action_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计二维码操作次数',
  `contact_submit_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计提交线索次数',
  `total_duration_seconds` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计停留秒数',
  `queried_schedule_dates` JSON NULL COMMENT '查询日期去重缓存',
  `follow_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'NOT_FOLLOWED_UP'
    COMMENT 'NOT_FOLLOWED_UP CONTACTED DEAL_WON INVALID',
  `follow_note` VARCHAR(1000) NULL COMMENT '跟进备注',
  `first_visited_at` DATETIME(3) NOT NULL COMMENT '首次访问时间',
  `last_visited_at` DATETIME(3) NOT NULL COMMENT '最近访问时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_visit_visitor_portfolio` (`visitor_key`, `portfolio_id`),
  KEY `idx_visit_owner_time` (`owner_type`, `owner_id`, `last_visited_at`),
  KEY `idx_visit_owner_follow`
    (`owner_type`, `owner_id`, `follow_status`, `last_visited_at`),
  KEY `idx_visit_portfolio_time` (`portfolio_id`, `last_visited_at`),
  CONSTRAINT `chk_visit_portfolio_type` CHECK (`portfolio_type` IN ('PERSONAL', 'TEAM')),
  CONSTRAINT `chk_visit_owner_type` CHECK (`owner_type` IN ('USER', 'TEAM')),
  CONSTRAINT `chk_visit_source_type` CHECK (
    `source_type` IN (
      'WECHAT_SHARE_CARD', 'QR_CODE', 'TEAM_PORTFOLIO',
      'PERSONAL_PORTFOLIO', 'UNKNOWN'
    )
  ),
  CONSTRAINT `chk_visit_source_portfolio_type` CHECK (
    `source_portfolio_type` IS NULL OR `source_portfolio_type` IN ('PERSONAL', 'TEAM')
  ),
  CONSTRAINT `chk_visit_follow_status` CHECK (
    `follow_status` IN ('NOT_FOLLOWED_UP', 'CONTACTED', 'DEAL_WON', 'INVALID')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='访客作品集访问汇总';

-- 17. 访问行为事件表
CREATE TABLE `wf_visit_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_record_id` BIGINT UNSIGNED NOT NULL COMMENT '访问汇总记录ID',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '作品集ID',
  `portfolio_revision` INT UNSIGNED NOT NULL COMMENT '事件发生时生效修订号',
  `visitor_key` CHAR(64) NOT NULL COMMENT '匿名访客摘要',
  `event_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'PORTFOLIO_OPENED WORK_VIEWED VIDEO_PLAYED SCHEDULE_QUERIED QR_CODE_INTERACTED MEMBER_PORTFOLIO_OPENED CONTACT_FORM_EXPOSED CONTACT_LEAD_SUBMITTED',
  `work_id` BIGINT UNSIGNED NULL COMMENT '相关作品ID',
  `queried_date` DATE NULL COMMENT '查询档期日期',
  `duration_seconds` INT UNSIGNED NULL COMMENT '本次停留或播放秒数',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '事件幂等键',
  `metadata` JSON NULL COMMENT '扩展元数据',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '业务发生时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_visit_event_idempotency` (`idempotency_key`),
  KEY `idx_event_visit_time` (`visit_record_id`, `occurred_at`),
  KEY `idx_event_portfolio_type_time` (`portfolio_id`, `event_type`, `occurred_at`),
  KEY `idx_event_work_time` (`work_id`, `occurred_at`),
  CONSTRAINT `chk_visit_event_type` CHECK (
    `event_type` IN (
      'PORTFOLIO_OPENED', 'WORK_VIEWED', 'VIDEO_PLAYED', 'SCHEDULE_QUERIED',
      'QR_CODE_INTERACTED', 'MEMBER_PORTFOLIO_OPENED',
      'CONTACT_FORM_EXPOSED', 'CONTACT_LEAD_SUBMITTED'
    )
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='访客行为事件';

-- 18. 联系线索表
CREATE TABLE `wf_contact_lead` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '来源作品集ID',
  `portfolio_revision` INT UNSIGNED NOT NULL COMMENT '提交时生效修订号',
  `visit_record_id` BIGINT UNSIGNED NULL COMMENT '访问汇总记录ID',
  `owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'USER个人用户 TEAM团队',
  `owner_id` BIGINT UNSIGNED NOT NULL COMMENT '线索归属用户或团队ID',
  `contact_name` VARCHAR(50) NOT NULL COMMENT '联系人',
  `phone_ciphertext` VARCHAR(512) NULL COMMENT '手机号密文',
  `phone_last4` CHAR(4) NULL COMMENT '手机号尾号',
  `wechat_ciphertext` VARCHAR(512) NULL COMMENT '微信号密文',
  `wechat_mask_hint` VARCHAR(32) NULL COMMENT '微信号脱敏提示',
  `desired_schedule` VARCHAR(100) NULL COMMENT '意向档期',
  `needs` VARCHAR(1000) NULL COMMENT '需求描述',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'WECHAT_SHARE_CARD QR_CODE TEAM_PORTFOLIO PERSONAL_PORTFOLIO UNKNOWN',
  `consent_version` VARCHAR(32) NOT NULL COMMENT '隐私说明版本',
  `consent_at` DATETIME(3) NOT NULL COMMENT '同意时间',
  `follow_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'NOT_FOLLOWED_UP'
    COMMENT 'NOT_FOLLOWED_UP CONTACTED DEAL_WON INVALID',
  `follow_note` VARCHAR(1000) NULL COMMENT '跟进备注',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '提交幂等键',
  `submitted_at` DATETIME(3) NOT NULL COMMENT '提交时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contact_lead_idempotency` (`idempotency_key`),
  KEY `idx_lead_owner_follow`
    (`owner_type`, `owner_id`, `follow_status`, `submitted_at`),
  KEY `idx_lead_portfolio_time` (`portfolio_id`, `submitted_at`),
  KEY `idx_lead_visit` (`visit_record_id`, `submitted_at`),
  CONSTRAINT `chk_lead_owner_type` CHECK (`owner_type` IN ('USER', 'TEAM')),
  CONSTRAINT `chk_lead_source_type` CHECK (
    `source_type` IN (
      'WECHAT_SHARE_CARD', 'QR_CODE', 'TEAM_PORTFOLIO',
      'PERSONAL_PORTFOLIO', 'UNKNOWN'
    )
  ),
  CONSTRAINT `chk_lead_follow_status` CHECK (
    `follow_status` IN ('NOT_FOLLOWED_UP', 'CONTACTED', 'DEAL_WON', 'INVALID')
  ),
  CONSTRAINT `chk_lead_contact_method` CHECK (
    `phone_ciphertext` IS NOT NULL OR `wechat_ciphertext` IS NOT NULL
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='访客联系线索';

-- 19. 积分账户表
CREATE TABLE `wf_point_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `balance` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前积分余额',
  `total_recharged` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计充值积分',
  `total_gifted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计赠送积分',
  `total_consumed` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计消耗积分',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_account_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='用户积分账户';

-- 20. 积分规则表
CREATE TABLE `wf_point_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `rule_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '稳定规则编码',
  `rule_version` INT UNSIGNED NOT NULL COMMENT '规则版本',
  `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
  `transaction_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'RECHARGE CONSUMPTION REFUND GIFT',
  `scene_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '业务场景编码',
  `calc_mode` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'FIXED_PER_ACTION ACCUMULATED_THRESHOLD RECHARGE_PACKAGE MANUAL_ADJUSTMENT',
  `unit_count` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '一个计费单位的业务次数',
  `points_value` BIGINT UNSIGNED NOT NULL COMMENT '每单位积分绝对值，0表示免费',
  `config_json` JSON NULL COMMENT '扩展计算参数',
  `effective_from` DATETIME(3) NOT NULL COMMENT '生效开始时间',
  `effective_to` DATETIME(3) NULL COMMENT '生效结束时间',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE启用 DISABLED停用',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_rule_version` (`rule_code`, `rule_version`),
  KEY `idx_point_rule_scene`
    (`scene_code`, `status`, `effective_from`, `effective_to`),
  CONSTRAINT `chk_point_rule_tx_type` CHECK (
    `transaction_type` IN ('RECHARGE', 'CONSUMPTION', 'REFUND', 'GIFT')
  ),
  CONSTRAINT `chk_point_rule_calc_mode` CHECK (
    `calc_mode` IN (
      'FIXED_PER_ACTION', 'ACCUMULATED_THRESHOLD',
      'RECHARGE_PACKAGE', 'MANUAL_ADJUSTMENT'
    )
  ),
  CONSTRAINT `chk_point_rule_unit` CHECK (`unit_count` > 0),
  CONSTRAINT `chk_point_rule_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT `chk_point_rule_code_format` CHECK (
    `rule_code` REGEXP '^[A-Z][A-Z0-9_]*$'
  ),
  CONSTRAINT `chk_point_rule_scene_format` CHECK (
    `scene_code` REGEXP '^[A-Z][A-Z0-9_]*$'
  ),
  CONSTRAINT `chk_point_rule_time` CHECK (
    `effective_to` IS NULL OR `effective_to` > `effective_from`
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='可配置积分规则';

-- 21. 积分计量器表
CREATE TABLE `wf_point_meter` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '被扣费积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '被扣费用户ID',
  `rule_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '稳定规则编码',
  `applied_rule_id` BIGINT UNSIGNED NOT NULL COMMENT '最近采用的规则ID',
  `business_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '计量业务类型',
  `business_id` VARCHAR(64) NOT NULL COMMENT '计量业务ID',
  `pending_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '尚未扣费的计量余数',
  `total_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计计量次数',
  `total_billed_units` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已扣费单位数',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_meter_business`
    (`account_id`, `rule_code`, `business_type`, `business_id`),
  KEY `idx_point_meter_user` (`user_id`, `rule_code`, `updated_at`),
  CONSTRAINT `chk_point_meter_count` CHECK (`total_count` >= `pending_count`),
  CONSTRAINT `chk_point_meter_rule_format` CHECK (
    `rule_code` REGEXP '^[A-Z][A-Z0-9_]*$'
  ),
  CONSTRAINT `chk_point_meter_business_format` CHECK (
    `business_type` REGEXP '^[A-Z][A-Z0-9_]*$'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='积分阶梯扣费计量器';

-- 22. 积分流水表
CREATE TABLE `wf_point_transaction` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `rule_id` BIGINT UNSIGNED NULL COMMENT '本次采用的积分规则ID',
  `transaction_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'RECHARGE CONSUMPTION REFUND GIFT',
  `scene_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '可扩展业务场景编码',
  `points_change` BIGINT NOT NULL COMMENT '积分变动值',
  `balance_before` BIGINT UNSIGNED NOT NULL COMMENT '变动前余额',
  `balance_after` BIGINT UNSIGNED NOT NULL COMMENT '变动后余额',
  `business_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '关联业务类型',
  `business_id` VARCHAR(64) NOT NULL COMMENT '关联业务ID',
  `calculation_snapshot` JSON NOT NULL COMMENT '规则与计算输入快照',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '业务幂等键',
  `remark` VARCHAR(255) NULL COMMENT '脱敏备注',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '业务发生时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_tx_idempotency` (`idempotency_key`),
  KEY `idx_point_tx_account_time` (`account_id`, `created_at`),
  KEY `idx_point_tx_user_scene` (`user_id`, `scene_code`, `created_at`),
  KEY `idx_point_tx_rule` (`rule_id`, `created_at`),
  KEY `idx_point_tx_business` (`business_type`, `business_id`),
  CONSTRAINT `chk_point_tx_type` CHECK (
    `transaction_type` IN ('RECHARGE', 'CONSUMPTION', 'REFUND', 'GIFT')
  ),
  CONSTRAINT `chk_point_tx_nonzero` CHECK (`points_change` <> 0),
  CONSTRAINT `chk_point_tx_scene_format` CHECK (
    `scene_code` REGEXP '^[A-Z][A-Z0-9_]*$'
  ),
  CONSTRAINT `chk_point_tx_business_format` CHECK (
    `business_type` REGEXP '^[A-Z][A-Z0-9_]*$'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='不可变积分流水';

-- 23. 充值档位表
CREATE TABLE `wf_recharge_package` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `package_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '稳定档位编码',
  `package_version` INT UNSIGNED NOT NULL COMMENT '档位版本',
  `package_name` VARCHAR(100) NOT NULL COMMENT '档位名称',
  `amount_fen` INT UNSIGNED NOT NULL COMMENT '支付金额分',
  `base_points` INT UNSIGNED NOT NULL COMMENT '基础到账积分',
  `bonus_points` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '赠送积分',
  `total_points` INT UNSIGNED NOT NULL COMMENT '总到账积分',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `effective_from` DATETIME(3) NOT NULL COMMENT '生效开始时间',
  `effective_to` DATETIME(3) NULL COMMENT '生效结束时间',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE启用 DISABLED停用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_package_version` (`package_code`, `package_version`),
  KEY `idx_recharge_package_available`
    (`status`, `sort_order`, `effective_from`, `effective_to`),
  CONSTRAINT `chk_recharge_package_amount` CHECK (`amount_fen` > 0),
  CONSTRAINT `chk_recharge_package_points` CHECK (
    `total_points` = `base_points` + `bonus_points`
  ),
  CONSTRAINT `chk_recharge_package_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT `chk_recharge_package_code_format` CHECK (
    `package_code` REGEXP '^[A-Z][A-Z0-9_]*$'
  ),
  CONSTRAINT `chk_recharge_package_time` CHECK (
    `effective_to` IS NULL OR `effective_to` > `effective_from`
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='可配置充值档位';

-- 24. 充值订单表
CREATE TABLE `wf_recharge_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_order_no` VARCHAR(32) NOT NULL COMMENT '商户订单号',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '下单用户ID',
  `package_id` BIGINT UNSIGNED NULL COMMENT '充值档位ID',
  `package_snapshot` JSON NOT NULL COMMENT '充值档位计算快照',
  `amount_fen` INT UNSIGNED NOT NULL COMMENT '支付金额分',
  `base_points` INT UNSIGNED NOT NULL COMMENT '基础积分',
  `bonus_points` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '赠送积分',
  `total_points` INT UNSIGNED NOT NULL COMMENT '总到账积分',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING_PAYMENT'
    COMMENT 'PENDING_PAYMENT PAID PAYMENT_FAILED CLOSED',
  `pay_channel` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WECHAT_PAY' COMMENT 'WECHAT_PAY微信支付',
  `prepay_id` VARCHAR(128) NULL COMMENT '微信预支付标识',
  `payment_transaction_id` VARCHAR(64) NULL COMMENT '微信支付交易号',
  `paid_at` DATETIME(3) NULL COMMENT '支付完成时间',
  `closed_at` DATETIME(3) NULL COMMENT '关闭时间',
  `expire_at` DATETIME(3) NOT NULL COMMENT '支付过期时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_merchant_order` (`merchant_order_no`),
  UNIQUE KEY `uk_recharge_payment_transaction` (`payment_transaction_id`),
  KEY `idx_recharge_user_time` (`user_id`, `created_at`),
  KEY `idx_recharge_package` (`package_id`, `created_at`),
  KEY `idx_recharge_status_expire` (`status`, `expire_at`),
  CONSTRAINT `chk_recharge_status` CHECK (
    `status` IN ('PENDING_PAYMENT', 'PAID', 'PAYMENT_FAILED', 'CLOSED')
  ),
  CONSTRAINT `chk_recharge_channel` CHECK (`pay_channel` = 'WECHAT_PAY'),
  CONSTRAINT `chk_recharge_amount` CHECK (`amount_fen` > 0),
  CONSTRAINT `chk_recharge_points` CHECK (
    `total_points` = `base_points` + `bonus_points`
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='积分充值订单';
