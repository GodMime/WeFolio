package com.jxc.wefolio.message;

/** 高级作品集素材绑定校验的安全重试提示，不包含模型原文或业务值。 */
public interface PortfolioAiBindingMessage {
    /** 首次生成组件的可读位置。 */
    String CREATE_COMPONENT = "第 %d 个组件 %s";
    /** 页面修改中新组件的可读位置。 */
    String INSERT_COMPONENT = "第 %d 个操作 INSERT_COMPONENT 的新组件 %s";
    /** 页面修改中已有组件的可读位置。 */
    String BIND_COMPONENT = "第 %d 个操作 BIND_RESOURCES 的组件 %s";
    /** 首次生成的全局设置位置。 */
    String CREATE_SETTING = "全局设置 %s";
    /** 修改全局设置的操作位置。 */
    String UPDATE_SETTING = "第 %d 个操作 UPDATE_GLOBAL 的全局设置 %s";
    /** 错误位置、原安全分类及纠正方向的统一格式。 */
    String FIELD_CORRECTION = "%s，字段 %s：%s。%s";
    /** 没有主素材槽位时不能填写作品编号。 */
    String NO_PRIMARY_CORRECTION = "请设置 sourceNos=[]。";
    /** 文字背景通过独立槽位绑定，不作为组件主素材。 */
    String BACKGROUND_CORRECTION = "背景作品请通过 bindings 的 backgroundWorkId 槽位绑定。";
    /** 主素材必须满足可信目录中的来源、数量及媒体限制。 */
    String PRIMARY_CORRECTION = "sourceNos 仅接受已选 %s 来源的正整数编号，数量 %d～%d，不得重复；允许媒体类型：%s。";
    /** 只能使用当前节点确实声明的绑定槽位。 */
    String BINDING_CORRECTION = "可用 bindings 槽位：%s；请按各槽位规定的来源类型绑定，每个槽位只写一次，sourceNo 与 ref 二选一。";
    /** 空槽位目录不接受任何资源绑定。 */
    String NO_BINDING_CORRECTION = "该节点没有资源绑定槽位，请设置 bindings=[]。";
    /** 既有组件没有进入本次目录时不回显其不可信类型，也不改变原校验行为。 */
    String UNKNOWN_COMPONENT = "未声明组件";
}
