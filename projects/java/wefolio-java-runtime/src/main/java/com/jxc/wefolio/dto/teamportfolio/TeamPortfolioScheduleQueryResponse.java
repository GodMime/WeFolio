package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 团队作品集档期查询响应。
 */
@Data
public class TeamPortfolioScheduleQueryResponse {

    /** 作品集类型。 */
    private String portfolioType;

    /** 查询日期。 */
    private LocalDate queriedDate;

    /** 团队查档状态。 */
    private String status;

    /** 团队查档状态文案。 */
    private String statusText;

    /** 是否至少有一名成员可约。 */
    private boolean available;

    /** 成员档期结果。 */
    private List<Member> members;

    /**
     * 成员档期结果。
     */
    @Data
    public static class Member {

        /** 成员用户 ID。 */
        private Long memberUserId;

        /** 成员展示名称。 */
        private String displayName;

        /** 成员头像地址。 */
        private String avatarUrl;

        /** 成员档期状态。 */
        private String status;

        /** 成员档期状态文案。 */
        private String statusText;

        /** 生效档位数量。 */
        private Integer totalSlotCount;

        /** 可约档位数量。 */
        private Integer availableSlotCount;

        /** 是否不存在生效档位。 */
        private boolean emptySlotDefinition;
    }
}
