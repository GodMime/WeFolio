package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dict.MessageTypeDict;
import com.jxc.wefolio.dto.MineMessageListResponse;
import com.jxc.wefolio.dto.MineMessageReadRequest;
import com.jxc.wefolio.dto.MineMessageUnreadCountResponse;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的消息服务测试 — 覆盖当前用户系统消息列表、未读统计和已读操作。
 */
@ExtendWith(MockitoExtension.class)
class MineMessageServiceTest {

    /** 系统消息 Mapper 模拟 */
    @Mock
    private SystemMessageEntityMapper systemMessageEntityMapper;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void listMessagesReturnsCurrentUserMessagesWithFiltersAndUnreadSummary() {
        SystemMessageEntity invitation = message(
                201L,
                MessageTypeDict.TEAM_INVITATION.getCode(),
                MessageCategoryDict.TEAM.getCode(),
                MessageReadStatusDict.UNREAD.getCode(),
                "团队邀请",
                "星曜司仪团邀请你加入团队",
                MessageActionTypeDict.TEAM_INVITATION.getCode(),
                "/pages/team-invitations/team-invitations?id=88",
                LocalDateTime.of(2026, 6, 25, 10, 30)
        );
        SystemMessageEntity pointWarning = message(
                200L,
                MessageTypeDict.POINT_LOW_BALANCE.getCode(),
                MessageCategoryDict.POINT.getCode(),
                MessageReadStatusDict.READ.getCode(),
                "积分不足",
                "当前积分余额偏低",
                MessageActionTypeDict.POINT_RECHARGE.getCode(),
                "/pages/points/points",
                LocalDateTime.of(2026, 6, 24, 9, 5)
        );
        when(systemMessageEntityMapper.selectList(any())).thenReturn(List.of(invitation, pointWarning));
        when(systemMessageEntityMapper.selectCount(any())).thenReturn(3L);
        MineMessageService service = new MineMessageService(systemMessageEntityMapper);

        MineMessageListResponse response = service.listMessages(
                MessageReadStatusDict.UNREAD.getCode(),
                MessageCategoryDict.TEAM.getCode(),
                300L,
                20
        );

        assertThat(response.getSummary().getUnreadCount()).isEqualTo(3L);
        assertThat(response.getMessages()).hasSize(2);
        MineMessageListResponse.MessageItem first = response.getMessages().get(0);
        assertThat(first.getMessageId()).isEqualTo(201L);
        assertThat(first.getMessageType()).isEqualTo(MessageTypeDict.TEAM_INVITATION.getCode());
        assertThat(first.getCategory()).isEqualTo(MessageCategoryDict.TEAM.getCode());
        assertThat(first.getReadStatus()).isEqualTo(MessageReadStatusDict.UNREAD.getCode());
        assertThat(first.isUnread()).isTrue();
        assertThat(first.getTitle()).isEqualTo("团队邀请");
        assertThat(first.getContent()).isEqualTo("星曜司仪团邀请你加入团队");
        assertThat(first.getActionType()).isEqualTo(MessageActionTypeDict.TEAM_INVITATION.getCode());
        assertThat(first.getActionUrl()).isEqualTo("/pages/team-invitations/team-invitations?id=88");
        assertThat(first.getCreatedAtText()).isEqualTo("06-25 10:30");

        ArgumentCaptor<QueryWrapper<SystemMessageEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(systemMessageEntityMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id", "read_status", "category", "id");
    }

    @Test
    void listMessagesTreatsBlankReadStatusAsUnreadDefault() {
        SystemMessageEntity blankStatusMessage = message(
                201L,
                MessageTypeDict.TEAM_INVITATION.getCode(),
                MessageCategoryDict.TEAM.getCode(),
                "",
                "团队邀请",
                "星曜司仪团邀请你加入团队",
                MessageActionTypeDict.TEAM_INVITATION.getCode(),
                "/pages/team-invitations/team-invitations?id=88",
                LocalDateTime.of(2026, 6, 25, 10, 30)
        );
        when(systemMessageEntityMapper.selectList(any())).thenReturn(List.of(blankStatusMessage));
        when(systemMessageEntityMapper.selectCount(any())).thenReturn(1L);
        MineMessageService service = new MineMessageService(systemMessageEntityMapper);

        MineMessageListResponse response = service.listMessages(null, null, null, 20);

        MineMessageListResponse.MessageItem item = response.getMessages().get(0);
        assertThat(item.getReadStatus()).isEqualTo(MessageReadStatusDict.UNREAD.getCode());
        assertThat(item.isUnread()).isTrue();
    }

    @Test
    void unreadCountReturnsTotalTeamAndPointCountsForCurrentUser() {
        when(systemMessageEntityMapper.selectCount(any()))
                .thenReturn(5L)
                .thenReturn(2L)
                .thenReturn(3L);
        MineMessageService service = new MineMessageService(systemMessageEntityMapper);

        MineMessageUnreadCountResponse response = service.getUnreadCount();

        assertThat(response.getUnreadCount()).isEqualTo(5L);
        assertThat(response.getTeamUnreadCount()).isEqualTo(2L);
        assertThat(response.getPointUnreadCount()).isEqualTo(3L);
    }

    @Test
    void markReadUpdatesOnlyCurrentUserUnreadMessagesAndReturnsUnreadSummary() {
        MineMessageReadRequest request = new MineMessageReadRequest();
        request.setMessageIds(List.of(101L, 102L, 101L, -1L));
        when(systemMessageEntityMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);
        when(systemMessageEntityMapper.selectCount(any()))
                .thenReturn(1L)
                .thenReturn(0L)
                .thenReturn(1L);
        MineMessageService service = new MineMessageService(systemMessageEntityMapper);

        MineMessageUnreadCountResponse response = service.markRead(request);

        assertThat(response.getUnreadCount()).isEqualTo(1L);
        ArgumentCaptor<UpdateWrapper<SystemMessageEntity>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(systemMessageEntityMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSet).contains("read_status", "read_at", "updated_at");
        assertThat(sqlSegment).contains("user_id", "id", "read_status");
    }

    @Test
    void markAllReadCanLimitByCategoryForCurrentUser() {
        MineMessageReadRequest request = new MineMessageReadRequest();
        request.setCategory(MessageCategoryDict.POINT.getCode());
        when(systemMessageEntityMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(4);
        when(systemMessageEntityMapper.selectCount(any()))
                .thenReturn(0L)
                .thenReturn(0L)
                .thenReturn(0L);
        MineMessageService service = new MineMessageService(systemMessageEntityMapper);

        MineMessageUnreadCountResponse response = service.markAllRead(request);

        assertThat(response.getUnreadCount()).isZero();
        ArgumentCaptor<UpdateWrapper<SystemMessageEntity>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(systemMessageEntityMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id", "read_status", "category");
    }

    private SystemMessageEntity message(
            Long id,
            String messageType,
            String category,
            String readStatus,
            String title,
            String content,
            String actionType,
            String actionUrl,
            LocalDateTime createdAt
    ) {
        SystemMessageEntity message = new SystemMessageEntity();
        message.setId(id);
        message.setUserId(7L);
        message.setMessageType(messageType);
        message.setCategory(category);
        message.setReadStatus(readStatus);
        message.setTitle(title);
        message.setContent(content);
        message.setActionType(actionType);
        message.setActionUrl(actionUrl);
        message.setCreatedAt(createdAt);
        return message;
    }
}
