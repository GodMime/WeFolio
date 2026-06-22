package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_tag — 作品标签表 — 用户自定义标签，支持颜色标记
 */
@Data
@TableName("wf_tag")
public class WfTagEntity extends BaseEntity {

    /** 标签所属用户 ID */
    private Long userId;

    /** 标签名称，同一用户内唯一 */
    private String name;

    /** 可选十六进制颜色，如 #FF0000 */
    private String color;

    /** 状态：ACTIVE 启用 / DISABLED 停用 */
    private String status;

}
