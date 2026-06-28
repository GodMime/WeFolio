UPDATE `wf_schedule`
SET `status` = 'TENTATIVE',
    `updated_at` = `updated_at`
WHERE `status` = 'AVAILABLE';

ALTER TABLE `wf_schedule`
DROP CHECK `chk_schedule_status`;

ALTER TABLE `wf_schedule`
MODIFY COLUMN `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'TENTATIVE' COMMENT 'BOOKED TENTATIVE REST';

ALTER TABLE `wf_schedule`
ADD CONSTRAINT `chk_schedule_status` CHECK (
    `status` IN ('BOOKED', 'TENTATIVE', 'REST')
);
