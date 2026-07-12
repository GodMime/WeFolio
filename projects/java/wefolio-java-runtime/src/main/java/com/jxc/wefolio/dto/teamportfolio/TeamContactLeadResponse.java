package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 团队维护端预留联系信息分页响应。
 */
@Data
public class TeamContactLeadResponse {

    /** 当前页码。 */
    private int page;

    /** 当前页大小。 */
    private int pageSize;

    /** 是否还有后续分页数据。 */
    private boolean hasMore;

    /** 按提交时间倒序排列的线索列表。 */
    private List<Item> items;

    /**
     * 团队线索展示项。
     */
    @Data
    public static class Item {

        /** 线索 ID。 */
        private Long leadId;

        /** 团队 ID。 */
        private Long teamId;

        /** 团队名称。 */
        private String teamName;

        /** 来源作品集 ID。 */
        private Long portfolioId;

        /** 来源作品集标题。 */
        private String portfolioTitle;

        /** 来源作品集分享编码。 */
        private String portfolioShareCode;

        /** 来源作品集发布修订号。 */
        private Integer portfolioRevision;

        /** 关联访问记录 ID。 */
        private Long visitRecordId;

        /** 联系人姓名。 */
        private String contactName;

        /** 解密后的完整手机号。 */
        private String phone;

        /** 解密后的完整微信号。 */
        private String wechat;

        /** 意向档期。 */
        private String desiredSchedule;

        /** 需求描述。 */
        private String needs;

        /** 访问来源类型。 */
        private String sourceType;

        /** 访问来源文案。 */
        private String sourceText;

        /** 隐私同意版本。 */
        private String consentVersion;

        /** 跟进状态。 */
        private String followStatus;

        /** 跟进状态文案。 */
        private String followStatusText;

        /** 跟进备注。 */
        private String followNote;

        /** 提交时间。 */
        private LocalDateTime submittedAt;
    }
}
