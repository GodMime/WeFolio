ALTER TABLE `wf_slot_definition`
  DROP INDEX `uk_slot_user_name`,
  ADD UNIQUE KEY `uk_slot_user_name_deleted` (`user_id`, `name`, `deleted`);

ALTER TABLE `wf_schedule`
  DROP INDEX `uk_schedule_user_date_slot`,
  ADD UNIQUE KEY `uk_schedule_user_date_slot_deleted`
    (`user_id`, `schedule_date`, `slot_definition_id`, `deleted`);
