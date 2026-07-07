ALTER TABLE `wf_work`
  ADD COLUMN `aspect_ratio` VARCHAR(32) NULL COMMENT '长宽比' AFTER `height`;
