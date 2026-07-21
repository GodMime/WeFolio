package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的积分概览响应 — 展示余额、累计值、分类消耗、积分获取和当前启用规则。
 */
@Data
public class MinePointOverviewResponse {

    /** 积分账户 ID */
    private Long accountId;

    /** 当前用户 ID */
    private Long userId;

    /** 当前可用积分 */
    private Long balance;

    /** 微信总代币余额快照。 */
    private Long wechatBalance;

    /** 微信有价充值代币余额。 */
    private Long wechatPaidBalance;

    /** 微信赠送代币余额快照。 */
    private Long wechatPresentBalance;

    /** 本地尚未同步至微信的待扣总额。 */
    private Long pendingDebit;

    /** 最近微信权威余额同步时间。 */
    private String wechatBalanceSyncedAt;

    /** 累计充值积分 */
    private Long totalRecharged;

    /** 累计赠送积分 */
    private Long totalGifted;

    /** 累计消耗积分 */
    private Long totalConsumed;

    /** 今日已消耗积分 */
    private Long todayConsumed;

    /** 访客相关消耗积分 */
    private Long visitorConsumed;

    /** 维护相关消耗积分 */
    private Long maintenanceConsumed;

    /** 是否低余额 */
    private boolean lowBalance;

    /** 低余额阈值 */
    private Long lowBalanceThreshold;

    /** 当前启用积分规则 */
    private List<RuleItem> rules = new ArrayList<>();

    /** 当前积分获取规则 */
    private List<AcquisitionRuleItem> acquisitionRules = new ArrayList<>();

    /**
     * 积分获取规则展示项。
     */
    @Data
    public static class AcquisitionRuleItem {

        /** 稳定规则编码 */
        private String code;

        /** 规则标题 */
        private String title;

        /** 规则说明 */
        private String description;

        /** 赠送积分值 */
        private Long pointsValue;
    }

    /**
     * 积分规则展示项。
     */
    @Data
    public static class RuleItem {

        /** 规则 ID */
        private Long ruleId;

        /** 规则编码 */
        private String ruleCode;

        /** 规则名称 */
        private String ruleName;

        /** 场景编码 */
        private String sceneCode;

        /** 场景文案 */
        private String sceneText;

        /** 规则分组编码 */
        private String groupCode;

        /** 规则分组文案 */
        private String groupText;

        /** 计算模式 */
        private String calcMode;

        /** 流水类型 */
        private String transactionType;

        /** 单位次数 */
        private Integer unitCount;

        /** 单位积分值 */
        private Long pointsValue;

        /** 同一访客与作用域免重复扣费窗口小时数 */
        private Integer dedupeWindowHours;

        /**
         * 免重复扣费作用域编码。
         *
         * @see com.jxc.wefolio.dict.BillingWindowScopeDict
         */
        private String dedupeScope;
    }
}
