package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dto.MineMessageListResponse;
import com.jxc.wefolio.dto.MineMessageReadRequest;
import com.jxc.wefolio.dto.MineMessageUnreadCountResponse;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 我的消息服务 — 负责当前用户系统消息查询、统计和已读状态维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineMessageService {

    /** 默认分页大小 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大分页大小 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 最小有效 ID */
    private static final long MIN_VALID_ID = 1L;

    /** 全部筛选标识 */
    private static final String ALL_FILTER = "ALL";

    /** 用户 ID 列名 */
    private static final String COLUMN_USER_ID = "user_id";

    /** 主键列名 */
    private static final String COLUMN_ID = "id";

    /** 已读状态列名 */
    private static final String COLUMN_READ_STATUS = "read_status";

    /** 消息分类列名 */
    private static final String COLUMN_CATEGORY = "category";

    /** 创建时间列名 */
    private static final String COLUMN_CREATED_AT = "created_at";

    /** 已读时间列名 */
    private static final String COLUMN_READ_AT = "read_at";

    /** 消息时间展示格式 */
    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 系统消息 Mapper */
    private final SystemMessageEntityMapper systemMessageEntityMapper;

    /**
     * 查询当前用户系统消息列表。
     *
     * @param status 已读状态筛选
     * @param category 分类筛选
     * @param cursor ID 游标
     * @param size 分页大小
     * @return 消息列表响应
     */
    public MineMessageListResponse listMessages(String status, String category, Long cursor, Integer size) {
        Long userId = AuthContextHolder.requireUserId();
        int pageSize = normalizePageSize(size);
        int querySize = pageSize + 1;
        QueryWrapper<SystemMessageEntity> query = new QueryWrapper<SystemMessageEntity>()
                .eq(COLUMN_USER_ID, userId);
        String readStatus = normalizeReadStatus(status);
        if (!readStatus.isBlank()) {
            query.eq(COLUMN_READ_STATUS, readStatus);
        }
        String normalizedCategory = normalizeCategory(category);
        if (!normalizedCategory.isBlank()) {
            query.eq(COLUMN_CATEGORY, normalizedCategory);
        }
        if (cursor != null && cursor >= MIN_VALID_ID) {
            query.lt(COLUMN_ID, cursor);
        }
        query.orderByDesc(COLUMN_CREATED_AT)
                .orderByDesc(COLUMN_ID)
                .last("LIMIT " + querySize);

        List<SystemMessageEntity> rawMessages = safeList(systemMessageEntityMapper.selectList(query));
        boolean hasMore = rawMessages.size() > pageSize;
        List<SystemMessageEntity> pageMessages = rawMessages.stream().limit(pageSize).toList();

        MineMessageListResponse response = new MineMessageListResponse();
        MineMessageListResponse.Summary summary = new MineMessageListResponse.Summary();
        summary.setUnreadCount(countUnread(userId, ""));
        response.setSummary(summary);
        response.setMessages(pageMessages.stream().map(this::buildMessageItem).toList());
        response.setHasMore(hasMore);
        response.setNextCursor(pageMessages.isEmpty() ? null : pageMessages.get(pageMessages.size() - 1).getId());
        return response;
    }

    /**
     * 获取当前用户未读消息统计。
     *
     * @return 未读统计响应
     */
    public MineMessageUnreadCountResponse getUnreadCount() {
        Long userId = AuthContextHolder.requireUserId();
        MineMessageUnreadCountResponse response = new MineMessageUnreadCountResponse();
        response.setUnreadCount(countUnread(userId, ""));
        response.setTeamUnreadCount(countUnread(userId, MessageCategoryDict.TEAM.getCode()));
        response.setPointUnreadCount(countUnread(userId, MessageCategoryDict.POINT.getCode()));
        return response;
    }

    /**
     * 将指定消息标记为已读。
     *
     * @param request 已读请求
     * @return 更新后的未读统计
     */
    public MineMessageUnreadCountResponse markRead(MineMessageReadRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        List<Long> messageIds = normalizeMessageIds(request == null ? null : request.getMessageIds());
        if (!messageIds.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq(COLUMN_USER_ID, userId)
                    .in(COLUMN_ID, messageIds)
                    .eq(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                    .set(COLUMN_READ_STATUS, MessageReadStatusDict.READ.getCode())
                    .set(COLUMN_READ_AT, now);
            systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
        }
        return getUnreadCount();
    }

    /**
     * 将当前用户未读消息全量标记为已读。
     *
     * @param request 已读请求，可选分类过滤
     * @return 更新后的未读统计
     */
    public MineMessageUnreadCountResponse markAllRead(MineMessageReadRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SystemMessageEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode())
                .set(COLUMN_READ_STATUS, MessageReadStatusDict.READ.getCode())
                .set(COLUMN_READ_AT, now);
        String category = normalizeCategory(request == null ? null : request.getCategory());
        if (!category.isBlank()) {
            updateWrapper.eq(COLUMN_CATEGORY, category);
        }
        systemMessageEntityMapper.update(new SystemMessageEntity(), updateWrapper);
        return getUnreadCount();
    }

    /**
     * 构建消息明细项。
     *
     * @param message 消息实体
     * @return 消息明细项
     */
    private MineMessageListResponse.MessageItem buildMessageItem(SystemMessageEntity message) {
        String readStatus = defaultString(message.getReadStatus(), MessageReadStatusDict.UNREAD.getCode());
        MineMessageListResponse.MessageItem item = new MineMessageListResponse.MessageItem();
        item.setMessageId(message.getId());
        item.setMessageType(defaultString(message.getMessageType(), ""));
        item.setCategory(defaultString(message.getCategory(), MessageCategoryDict.SYSTEM.getCode()));
        item.setReadStatus(readStatus);
        item.setUnread(MessageReadStatusDict.UNREAD.getCode().equals(readStatus));
        item.setTitle(defaultString(message.getTitle(), ""));
        item.setContent(defaultString(message.getContent(), ""));
        item.setActionType(defaultString(message.getActionType(), MessageActionTypeDict.NONE.getCode()));
        item.setActionUrl(defaultString(message.getActionUrl(), ""));
        item.setBizType(defaultString(message.getBizType(), ""));
        item.setBizId(message.getBizId());
        item.setCreatedAtText(formatCreatedAt(message.getCreatedAt()));
        return item;
    }

    /**
     * 统计当前用户未读消息。
     *
     * @param userId 当前用户 ID
     * @param category 分类编码，可为空
     * @return 未读数量
     */
    private Long countUnread(Long userId, String category) {
        QueryWrapper<SystemMessageEntity> query = new QueryWrapper<SystemMessageEntity>()
                .eq(COLUMN_USER_ID, userId)
                .eq(COLUMN_READ_STATUS, MessageReadStatusDict.UNREAD.getCode());
        if (category != null && !category.isBlank()) {
            query.eq(COLUMN_CATEGORY, category);
        }
        Long count = systemMessageEntityMapper.selectCount(query);
        return count == null ? 0L : count;
    }

    /**
     * 规范分页大小。
     *
     * @param size 请求分页大小
     * @return 安全分页大小
     */
    private int normalizePageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 规范已读状态筛选。
     *
     * @param status 请求状态
     * @return 有效状态编码，无效时返回空字符串
     */
    private String normalizeReadStatus(String status) {
        String normalized = defaultString(status, "").trim();
        if (normalized.isBlank() || ALL_FILTER.equals(normalized)) {
            return "";
        }
        return MessageReadStatusDict.fromCode(normalized) == null ? "" : normalized;
    }

    /**
     * 规范分类筛选。
     *
     * @param category 请求分类
     * @return 有效分类编码，无效时返回空字符串
     */
    private String normalizeCategory(String category) {
        String normalized = defaultString(category, "").trim();
        if (normalized.isBlank() || ALL_FILTER.equals(normalized)) {
            return "";
        }
        return MessageCategoryDict.fromCode(normalized) == null ? "" : normalized;
    }

    /**
     * 规范消息 ID 列表。
     *
     * @param messageIds 原始 ID 列表
     * @return 去重后的有效 ID 列表
     */
    private List<Long> normalizeMessageIds(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> normalized = new ArrayList<>();
        for (Long messageId : messageIds) {
            if (messageId == null || messageId < MIN_VALID_ID || normalized.contains(messageId)) {
                continue;
            }
            normalized.add(messageId);
        }
        return normalized;
    }

    /**
     * 格式化消息创建时间。
     *
     * @param createdAt 创建时间
     * @return 展示文案
     */
    private String formatCreatedAt(LocalDateTime createdAt) {
        return createdAt == null ? "" : createdAt.format(MESSAGE_TIME_FORMATTER);
    }

    /**
     * 安全列表。
     *
     * @param list 原始列表
     * @return 非空列表
     */
    private List<SystemMessageEntity> safeList(List<SystemMessageEntity> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 默认字符串。
     *
     * @param value 原始值
     * @param defaultValue 默认值
     * @return 非空字符串
     */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
