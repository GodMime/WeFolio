package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_team_member — 团队成员表 — 成员角色、邀请状态与内容引用权限
 */
@Data
@TableName("wf_team_member")
public class TeamMemberEntity extends BaseEntity {

    /** 团队 ID */
    private Long teamId;

    /** 成员用户 ID */
    private Long userId;

    /** 角色：OWNER 拥有者 / MANAGER 管理者 / MEMBER 普通成员 */
    private String role;

    /** 在该团队展示的团队身份 */
    private String profession;

    /** 加入状态：PENDING_CONFIRMATION 待确认 / JOINED 已加入 / REJECTED 已拒绝 / REMOVED 已移除 */
    private String joinStatus;

    /** 是否允许引用个人作品集：0 否 / 1 是 */
    private Integer allowPortfolio;

    /** 是否允许引用头像资料：0 否 / 1 是 */
    private Integer allowProfile;

    /** 是否允许引用个人作品素材：0 否 / 1 是 */
    private Integer allowWorks;

    /** 邀请人用户 ID */
    private Long invitedBy;

    /** 邀请时间 */
    private LocalDateTime invitedAt;

    /** 接受或拒绝时间 */
    private LocalDateTime respondedAt;

    /** 正式加入时间 */
    private LocalDateTime joinedAt;

    /** 移除时间 */
    private LocalDateTime removedAt;

    /** 移除原因 */
    private String removalReason;

    /** 生成列：已加入拥有者时为 1，否则 NULL（MySQL STORED GENERATED COLUMN） */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeOwnerMarker;

}
