package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.TeamMemberChangeStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * wf_team_member_change_request — 团队成员信息变更确认请求表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_team_member_change_request")
public class TeamMemberChangeRequestEntity extends BaseEntity {

    /** 团队 ID */
    private Long teamId;

    /** 团队成员关系 ID */
    private Long memberId;

    /** 被修改成员用户 ID */
    private Long targetUserId;

    /** 发起修改的用户 ID */
    private Long requestedByUserId;

    /** 发起时成员关系版本号 */
    private Integer memberVersionBefore;

    /**
     * 修改前团队角色。
     *
     * @see TeamRoleDict
     */
    private String roleBefore;

    /**
     * 修改后团队角色。
     *
     * @see TeamRoleDict
     */
    private String roleAfter;

    /** 修改前团队身份 */
    private String professionBefore;

    /** 修改后团队身份 */
    private String professionAfter;

    /** 修改前是否允许引用个人作品集：0 否 / 1 是 */
    private Integer allowPortfolioBefore;

    /** 修改后是否允许引用个人作品集：0 否 / 1 是 */
    private Integer allowPortfolioAfter;

    /** 修改前是否允许引用头像资料：0 否 / 1 是 */
    private Integer allowProfileBefore;

    /** 修改后是否允许引用头像资料：0 否 / 1 是 */
    private Integer allowProfileAfter;

    /** 修改前是否允许引用个人作品素材：0 否 / 1 是 */
    private Integer allowWorksBefore;

    /** 修改后是否允许引用个人作品素材：0 否 / 1 是 */
    private Integer allowWorksAfter;

    /**
     * 确认状态。
     *
     * @see TeamMemberChangeStatusDict
     */
    private String status;

    /** 生成列：待确认时为 1，否则 NULL */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer pendingMarker;

    /** 发起时间 */
    private LocalDateTime requestedAt;

    /** 响应时间 */
    private LocalDateTime respondedAt;
}
