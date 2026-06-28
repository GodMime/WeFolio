package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.TeamMemberChangeStatusDict;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.entity.TeamMemberChangeRequestEntity;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberChangeRequestEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 团队成员信息变更事务辅助服务 — 承接需要独立提交的变更请求状态修正。
 */
@Service
@RequiredArgsConstructor
public class TeamMemberChangeRequestTransactionService {

    /** 主键列 */
    private static final String COLUMN_ID = "id";

    /** 变更请求状态列 */
    private static final String COLUMN_STATUS = "status";

    /** 响应时间列 */
    private static final String COLUMN_RESPONDED_AT = "responded_at";

    /** 更新时间列 */
    private static final String COLUMN_UPDATED_AT = "updated_at";

    /** 用户 ID 列 */
    private static final String COLUMN_USER_ID = "user_id";

    /** 消息幂等键列 */
    private static final String COLUMN_IDEMPOTENCY_KEY = "idempotency_key";

    /** 消息动作类型列 */
    private static final String COLUMN_ACTION_TYPE = "action_type";

    /** 消息动作地址列 */
    private static final String COLUMN_ACTION_URL = "action_url";

    /** 团队成员信息变更消息幂等键前缀 */
    private static final String MEMBER_CHANGE_IDEMPOTENCY_PREFIX = "team_member_change:";

    /** 空动作地址 */
    private static final String EMPTY_ACTION_URL = "";

    /** 团队成员信息变更请求 Mapper */
    private final TeamMemberChangeRequestEntityMapper teamMemberChangeRequestEntityMapper;

    /** 系统消息 Mapper */
    private final SystemMessageEntityMapper systemMessageEntityMapper;

    /**
     * 将冲突的成员信息变更置为失效并清理消息动作。
     *
     * <p>调用方随后会抛出业务异常回滚主事务，因此这里使用独立事务，避免变更请求继续卡在待确认状态。</p>
     *
     * @param changeRequestId 变更请求 ID
     * @param targetUserId 接收消息的成员用户 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void invalidateMemberChangeAndClearAction(Long changeRequestId, Long targetUserId) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<TeamMemberChangeRequestEntity> requestUpdate = new UpdateWrapper<>();
        requestUpdate.eq(COLUMN_ID, changeRequestId)
                .eq(COLUMN_STATUS, TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode())
                .set(COLUMN_STATUS, TeamMemberChangeStatusDict.INVALIDATED.getCode())
                .set(COLUMN_RESPONDED_AT, now)
                .set(COLUMN_UPDATED_AT, now);
        teamMemberChangeRequestEntityMapper.update(null, requestUpdate);

        UpdateWrapper<SystemMessageEntity> messageUpdate = new UpdateWrapper<>();
        messageUpdate.eq(COLUMN_USER_ID, targetUserId)
                .eq(COLUMN_IDEMPOTENCY_KEY, MEMBER_CHANGE_IDEMPOTENCY_PREFIX + changeRequestId)
                .set(COLUMN_ACTION_TYPE, MessageActionTypeDict.NONE.getCode())
                .set(COLUMN_ACTION_URL, EMPTY_ACTION_URL)
                .set(COLUMN_UPDATED_AT, now);
        systemMessageEntityMapper.update(null, messageUpdate);
    }
}
