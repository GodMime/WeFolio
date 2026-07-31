package com.jxc.wefolio.message;

/**
 * 团队作品集业务提示文案。
 */
public interface TeamPortfolioMessage {

    /** 团队作品集数量超限提示模板。 */
    String PORTFOLIO_COUNT_LIMIT_TEMPLATE = "当前团队作品集数量已达上限（%d个），请删除部分团队作品集后再新建";

    /** 团队作品集功能尚未开放 */
    String FEATURE_DISABLED = "团队作品集功能暂未开放";

    /** 团队不存在或当前用户没有访问权限 */
    String NO_ACCESS = "团队不存在或无访问权限";

    /** 当前用户没有团队作品集维护权限 */
    String NO_MAINTAIN_PERMISSION = "无团队作品集维护权限";

    /** 团队作品集不存在或当前用户没有访问权限 */
    String PORTFOLIO_NOT_FOUND = "团队作品集不存在或无访问权限";

    /** 当前 Schema 暂不支持访问 */
    String INVALID_SCHEMA = "当前作品集暂未开放访问";

    /** 团队作品集标题不能为空 */
    String TITLE_REQUIRED = "请填写团队作品集标题";

    /** 分享渠道不支持 */
    String SHARE_CHANNEL_UNSUPPORTED = "分享渠道不支持";

    /** 团队记录分页参数不合法提示 */
    String RECORD_PAGE_INVALID_MESSAGE = "团队记录分页参数不合法";

    /** 团队作品集草稿配置为空提示 */
    String DRAFT_CONFIG_REQUIRED_MESSAGE = "团队作品集草稿配置不能为空";

    /** 服务端不支持更高团队编辑器能力版本提示 */
    String EDITOR_SCHEMA_REVISION_UNSUPPORTED_MESSAGE =
            "当前服务暂不支持此团队作品集配置，请稍后重试";

    /** 底部导航菜单数量无效提示 */
    String BOTTOM_NAV_ITEM_COUNT_INVALID_MESSAGE = "底部导航菜单数量必须为2到4个";

    /** 菜单名称为空提示 */
    String BOTTOM_NAV_TITLE_REQUIRED_MESSAGE = "菜单名称不能为空";

    /** 菜单名称过长提示 */
    String BOTTOM_NAV_TITLE_TOO_LONG_MESSAGE = "菜单名称不能超过5个字";

    /** 菜单名称重复提示 */
    String BOTTOM_NAV_TITLE_DUPLICATE_MESSAGE = "菜单名称不能重复";

    /** 菜单标识格式无效提示 */
    String BOTTOM_NAV_KEY_INVALID_MESSAGE = "菜单标识格式不正确";

    /** 菜单标识重复提示 */
    String BOTTOM_NAV_KEY_DUPLICATE_MESSAGE = "菜单标识不能重复";

    /** 第一菜单重复携带组件提示 */
    String FIRST_BOTTOM_NAV_COMPONENTS_DUPLICATE_MESSAGE = "第一个菜单不能重复保存组件";

    /** 跨菜单团队组件标识重复提示 */
    String COMPONENT_KEY_CROSS_MENU_DUPLICATE_MESSAGE = "团队作品集组件标识不能重复";

    /** 团队资料组件数量超限提示 */
    String TEAM_PROFILE_COMPONENT_LIMIT_MESSAGE = "团队作品集最多只能包含一个团队资料组件";

    /** 菜单至少需要一个组件提示模板 */
    String MENU_COMPONENT_REQUIRED_TEMPLATE = "【%s】至少添加一个组件";

    /** 团队作品集草稿版本冲突提示 */
    String DRAFT_REVISION_CHANGED_MESSAGE = "团队作品集草稿已更新，请刷新后重试";

    /** 客户端草稿版本为空提示 */
    String CLIENT_REVISION_REQUIRED_MESSAGE = "客户端草稿版本不能为空";

    /** 团队作品集幂等键为空提示 */
    String IDEMPOTENCY_KEY_REQUIRED_MESSAGE = "团队作品集幂等键不能为空";

    /** 团队作品集幂等键内容冲突提示 */
    String IDEMPOTENCY_CONFLICT_MESSAGE = "团队作品集幂等键已用于其他内容";

    /** 团队作品集发布版本为空提示 */
    String PUBLISH_REVISION_REQUIRED_MESSAGE = "请选择要发布的团队作品集草稿版本";

    /** 团队作品集并发更新提示 */
    String CONCURRENT_UPDATE_MESSAGE = "团队作品集已被更新，请刷新后重试";

    /** 已发布团队作品集不可用提示 */
    String PORTFOLIO_UNAVAILABLE_MESSAGE = "已发布团队作品集不可用";

    /** 分享渠道为空提示 */
    String SHARE_CHANNEL_REQUIRED_MESSAGE = "分享渠道不能为空";

    /** 团队作品集删除失败提示 */
    String DELETE_FAILED_MESSAGE = "团队作品集删除失败";

    /** 团队作品集历史写入失败提示 */
    String HISTORY_INSERT_FAILED_MESSAGE = "团队作品集历史写入失败";

    /** SHA-256 算法不可用提示 */
    String SHA_256_UNAVAILABLE_MESSAGE = "SHA-256 算法不可用";

    /** 团队单个作品配置错误 */
    String SINGLE_WORK_CONFIG_INVALID = "单个作品配置不正确";

    /** 团队单个作品不可用 */
    String SINGLE_WORK_UNAVAILABLE = "单个作品不存在或不可用";

    /** 团队单个作品成员不可用 */
    String SINGLE_WORK_MEMBER_UNAVAILABLE = "团队成员不存在或不可用";
}
