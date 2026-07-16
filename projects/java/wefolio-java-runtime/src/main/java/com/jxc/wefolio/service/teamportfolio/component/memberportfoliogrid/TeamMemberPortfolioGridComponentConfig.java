package com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid;

import lombok.Data;

import java.util.List;

/**
 * 双列成员作品集组件配置。
 */
@Data
public class TeamMemberPortfolioGridComponentConfig {

    /** 有序的成员作品集条目。 */
    private List<Item> items;

    /** 是否显示成员姓名，缺省时开启。 */
    private Boolean showMemberName;

    /**
     * 成员与个人作品集的关联条目。
     */
    @Data
    public static class Item {

        /** 成员用户 ID。 */
        private Long memberUserId;

        /** 个人作品集 ID。 */
        private Long portfolioId;
    }
}
