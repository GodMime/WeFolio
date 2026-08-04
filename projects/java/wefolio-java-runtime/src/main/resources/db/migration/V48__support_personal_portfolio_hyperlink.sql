-- 扩展个人作品集之间的超链接引用类型。
ALTER TABLE `wf_portfolio_reference`
  DROP CHECK `chk_reference_type`,
  ADD CONSTRAINT `chk_reference_type` CHECK (
    `reference_type` IN (
      'WORK', 'MEMBER_PORTFOLIO', 'LINKED_PORTFOLIO', 'USER_PROFILE',
      'TEAM_PROFILE', 'SCHEDULE_COMPONENT', 'QR_CODE_ASSET'
    )
  );
