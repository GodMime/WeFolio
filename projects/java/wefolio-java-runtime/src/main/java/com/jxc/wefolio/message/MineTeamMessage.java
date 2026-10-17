package com.jxc.wefolio.message;

/**
 * 我的团队报错信息 — 统一维护团队、成员邀请和成员信息变更相关提示。
 */
public interface MineTeamMessage {

    /** 转让接收者停用、删除或不存在时的提示 */
    String OWNER_TRANSFER_TARGET_UNAVAILABLE_MESSAGE = "接收者账号不可用，无法转让团队";

    /** 成员信息变更保存失败提示 */
    String MEMBER_CHANGE_SAVE_FAILED_MESSAGE = "成员信息变更保存失败，请重试";

    /** 并发邀请已存在时的提示 */
    String PENDING_INVITATION_EXISTS_MESSAGE = "已有待确认邀请，请刷新后查看";

    /** 团队邀请保存失败提示 */
    String TEAM_INVITATION_SAVE_FAILED_MESSAGE = "团队邀请保存失败，请重试";
}
