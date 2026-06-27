ALTER TABLE `wf_slot_definition`
  DROP INDEX `idx_slot_user_status_sort`,
  DROP COLUMN `sort_order`,
  ADD KEY `idx_slot_user_status_start_time` (`user_id`, `status`, `start_time`, `id`);
