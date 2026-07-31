ALTER TABLE `wf_slot_definition`
  ADD UNIQUE KEY `uk_slot_user_color_deleted` (`user_id`, `color`, `deleted`);
