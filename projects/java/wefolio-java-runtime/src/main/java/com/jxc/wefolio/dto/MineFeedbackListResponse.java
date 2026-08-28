package com.jxc.wefolio.dto;

import com.jxc.wefolio.dict.FeedbackStatusDict;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前用户问题反馈分页响应。
 */
@Data
public class MineFeedbackListResponse {

    /** 当前页码。 */
    private int pageNo;

    /** 当前页大小。 */
    private int pageSize;

    /** 符合条件的问题总数。 */
    private long total;

    /** 是否还有下一页。 */
    private boolean hasMore;

    /** 当前页问题列表。 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个历史问题摘要。
     */
    @Data
    public static class Item {

        /** 反馈主键。 */
        private Long id;

        /** 对外反馈编号。 */
        private String feedbackNo;

        /**
         * 当前处理状态。
         *
         * @see FeedbackStatusDict
         */
        private String status;

        /** 当前处理状态文案。 */
        private String statusText;

        /** 首轮描述摘要。 */
        private String descriptionSummary;

        /** 已提交轮次数量。 */
        private int roundCount;

        /** 已提交附件总数。 */
        private int attachmentCount;

        /** 问题创建时间。 */
        private LocalDateTime createdAt;

        /** 问题最近更新时间。 */
        private LocalDateTime updatedAt;
    }
}
