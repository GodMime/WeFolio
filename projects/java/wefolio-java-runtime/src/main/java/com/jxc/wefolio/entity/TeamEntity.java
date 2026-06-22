package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_team — 团队表 — 团队主数据与当前拥有者
 */
@Data
@TableName("wf_team")
public class TeamEntity extends BaseEntity {

    /** 团队唯一码，创建后不可重复 */
    private String uniqueCode;

    /** 团队名称 */
    private String name;

    /** 团队头像地址 */
    private String avatarUrl;

    /** 团队简介 */
    private String intro;

    /** 所在城市或服务区域 */
    private String city;

    /** 团队或客服微信二维码 */
    private String contactQrUrl;

    /** 当前拥有者用户 ID，团队权限判断的主指针 */
    private Long ownerUserId;

    /** 状态：ACTIVE 正常 / DISSOLVED 已解散 */
    private String status;

    /** 逻辑删除时间 */
    private LocalDateTime deletedAt;

}
