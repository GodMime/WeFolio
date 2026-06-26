package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineMessageListResponse;
import com.jxc.wefolio.dto.MineMessageReadRequest;
import com.jxc.wefolio.dto.MineMessageUnreadCountResponse;
import com.jxc.wefolio.service.MineMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的消息控制器 — 提供系统消息列表、未读统计和已读操作接口。
 */
@Slf4j
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineMessageController {

    /** 我的消息服务 */
    private final MineMessageService mineMessageService;

    /**
     * 查询当前用户系统消息列表。
     *
     * @param status 已读状态筛选
     * @param category 分类筛选
     * @param cursor ID 游标
     * @param size 分页大小
     * @return 消息列表响应
     */
    @GetMapping("/api/mine/messages")
    public Response<MineMessageListResponse> messages(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return Response.success(mineMessageService.listMessages(status, category, cursor, size));
    }

    /**
     * 查询当前用户未读消息统计。
     *
     * @return 未读统计响应
     */
    @GetMapping("/api/mine/messages/unread-count")
    public Response<MineMessageUnreadCountResponse> unreadCount() {
        return Response.success(mineMessageService.getUnreadCount());
    }

    /**
     * 批量标记指定消息已读。
     *
     * @param request 已读请求
     * @return 更新后的未读统计
     */
    @PutMapping("/api/mine/messages/read")
    public Response<MineMessageUnreadCountResponse> markRead(@RequestBody MineMessageReadRequest request) {
        log.info("批量标记消息已读: messageIds={}", request == null ? null : request.getMessageIds());
        return Response.success(mineMessageService.markRead(request));
    }

    /**
     * 全量标记未读消息已读。
     *
     * @param request 已读请求，可选分类过滤
     * @return 更新后的未读统计
     */
    @PutMapping("/api/mine/messages/read-all")
    public Response<MineMessageUnreadCountResponse> markAllRead(@RequestBody MineMessageReadRequest request) {
        log.info("全量标记消息已读: category={}", request == null ? null : request.getCategory());
        return Response.success(mineMessageService.markAllRead(request));
    }
}
