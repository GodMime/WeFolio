package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_work_tag — 作品标签关联表 — 多对多关联，已补充 user_id 支持用户隔离
 */
@Data
@TableName("wf_work_tag")
public class WorkTagEntity extends BaseEntity {

    /** 所属用户 ID，冗余字段用于行级隔离查询 */
    private Long userId;

    /** 作品 ID */
    private Long workId;

    /** 标签 ID */
    private Long tagId;

}
