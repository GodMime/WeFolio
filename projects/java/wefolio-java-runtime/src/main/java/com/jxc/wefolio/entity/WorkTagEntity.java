package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_work_tag — 作品标签关联表 — 多对多关联
 */
@Data
@TableName("wf_work_tag")
public class WorkTagEntity extends BaseEntity {

    /** 作品 ID */
    private Long workId;

    /** 标签 ID */
    private Long tagId;

}
