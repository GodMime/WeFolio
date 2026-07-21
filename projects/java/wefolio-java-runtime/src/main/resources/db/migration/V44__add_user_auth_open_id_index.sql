ALTER TABLE `wf_user_auth`
  ADD INDEX `idx_user_auth_open_id_deleted` (`open_id`, `deleted`);
