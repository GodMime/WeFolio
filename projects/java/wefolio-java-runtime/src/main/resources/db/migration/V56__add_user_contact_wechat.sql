-- 维护者联系微信；联系手机既有列保留 NULL / 空串 / 密文三态，不回填登录手机号。
ALTER TABLE wf_user
    ADD COLUMN contact_wechat_ciphertext VARCHAR(512) NULL COMMENT '联系微信密文；NULL未设置，空串明确清空' AFTER contact_phone_ciphertext;
