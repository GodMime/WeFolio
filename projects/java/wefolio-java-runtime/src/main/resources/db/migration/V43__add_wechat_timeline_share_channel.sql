-- 扩展作品集分享渠道，支持记录微信朋友圈分享发起行为。
ALTER TABLE `wf_portfolio_share_record`
  DROP CHECK `chk_share_channel`;

ALTER TABLE `wf_portfolio_share_record`
  ADD CONSTRAINT `chk_share_channel` CHECK (
    `share_channel` IN ('WECHAT_CARD', 'WECHAT_TIMELINE', 'QR_CODE', 'COPIED_PATH')
  );
