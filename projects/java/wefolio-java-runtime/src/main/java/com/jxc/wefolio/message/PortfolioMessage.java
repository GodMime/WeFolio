package com.jxc.wefolio.message;

/**
 * 作品集报错信息 — 统一维护作品集维护端、访客端和联系线索相关提示。
 */
public interface PortfolioMessage {

    /** 个人作品集数量超限提示模板 */
    String PERSONAL_PORTFOLIO_COUNT_LIMIT_TEMPLATE = "个人作品集数量已达上限（%d个），请删除部分个人作品集后再新建";

    /** 作品集不可访问提示 */
    String PORTFOLIO_UNAVAILABLE_MESSAGE = "作品集暂不可访问";

    /** 作品集归属类型错误提示 */
    String PORTFOLIO_OWNER_TYPE_INVALID_MESSAGE = "作品集归属类型不正确";

    /** SHA-256 算法不可用提示 */
    String SHA_256_UNAVAILABLE_MESSAGE = "SHA-256 算法不可用";

    /** 微信登录凭证为空提示 */
    String WECHAT_LOGIN_CODE_REQUIRED_MESSAGE = "微信登录凭证不能为空";

    /** 微信 openid 缺失提示 */
    String WECHAT_OPENID_MISSING_MESSAGE = "微信登录未返回 openid";

    /** 联系人必填提示 */
    String CONTACT_NAME_REQUIRED_MESSAGE = "请填写联系人";

    /** 联系方式必填提示 */
    String CONTACT_METHOD_REQUIRED_MESSAGE = "请至少填写手机号或微信号";

    /** 联系线索提交幂等键为空提示 */
    String CONTACT_SUBMIT_IDEMPOTENCY_REQUIRED_MESSAGE = "提交幂等键不能为空";

    /** 草稿配置为空提示 */
    String DRAFT_CONFIG_REQUIRED_MESSAGE = "草稿配置不能为空";

    /** 保存草稿版本冲突提示 */
    String DRAFT_REVISION_CHANGED_SAVE_MESSAGE = "草稿已更新，请刷新后再保存";

    /** 发布草稿版本为空提示 */
    String PUBLISH_DRAFT_REVISION_REQUIRED_MESSAGE = "请选择要发布的草稿版本";

    /** 新版编辑器保存必须携带客户端草稿版本 */
    String DRAFT_CLIENT_REVISION_REQUIRED_MESSAGE = "请刷新作品集后再保存";

    /** 发布草稿版本冲突提示 */
    String DRAFT_REVISION_CHANGED_PUBLISH_MESSAGE = "草稿已更新，请刷新后再发布";

    /** 草稿未保存提示 */
    String DRAFT_SAVE_REQUIRED_MESSAGE = "请先保存草稿";

    /** 发布幂等键为空提示 */
    String PUBLISH_IDEMPOTENCY_REQUIRED_MESSAGE = "发布幂等键不能为空";

    /** 封面图片为空提示 */
    String COVER_REQUIRED_MESSAGE = "请选择封面图片";

    /** 封面图片大小超限提示 */
    String COVER_SIZE_LIMIT_MESSAGE = "封面图片不能超过 300KB";

    /** 封面图片格式不支持提示 */
    String COVER_FORMAT_UNSUPPORTED_MESSAGE = "封面图片仅支持 JPG 或 PNG";

    /** 作品集素材类型不支持提示 */
    String PORTFOLIO_ASSET_TYPE_UNSUPPORTED_MESSAGE = "作品集素材类型不支持";

    /** 头像图片为空提示 */
    String PROFILE_AVATAR_REQUIRED_MESSAGE = "请选择头像图片";

    /** 头像图片大小超限提示 */
    String PROFILE_AVATAR_SIZE_LIMIT_MESSAGE = "头像图片不能超过 300KB";

    /** 头像图片格式不支持提示 */
    String PROFILE_AVATAR_FORMAT_UNSUPPORTED_MESSAGE = "头像图片仅支持 JPG 或 PNG";

    /** 分享渠道为空提示 */
    String SHARE_CHANNEL_REQUIRED_MESSAGE = "分享渠道不能为空";

    /** 分享渠道不支持提示 */
    String SHARE_CHANNEL_UNSUPPORTED_MESSAGE = "分享渠道不支持";

    /** 作品集不存在提示 */
    String PORTFOLIO_NOT_FOUND_MESSAGE = "作品集不存在";

    /** 作品集并发更新冲突提示 */
    String PORTFOLIO_CONCURRENT_UPDATE_MESSAGE = "并发冲突，请刷新重试";

    /** 个人作品集图锁等待失败提示 */
    String PORTFOLIO_GRAPH_BUSY_MESSAGE = "作品集正在更新，请稍后重试";

    /** 超链接不能跳转当前作品集提示 */
    String HYPERLINK_SELF_TARGET_MESSAGE = "不能跳转到当前作品集";

    /** 超链接循环引用提示 */
    String HYPERLINK_CYCLE_MESSAGE = "作品集之间不能循环跳转";

    /** 作品集删除失败提示 */
    String PORTFOLIO_DELETE_FAILED_MESSAGE = "作品集删除失败，请刷新重试";

    /** 作品集维护能力不可用提示 */
    String PORTFOLIO_MAINTENANCE_UNAVAILABLE_MESSAGE = "当前作品集暂未开放维护";

    /** 作品集配置格式错误提示 */
    String PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE = "作品集配置格式不正确";

    /** 用户为空提示 */
    String USER_REQUIRED_MESSAGE = "用户不能为空";

    /** 作品集配置为空提示 */
    String PORTFOLIO_CONFIG_REQUIRED_MESSAGE = "作品集配置不能为空";

    /** 作品集配置版本不支持提示 */
    String PORTFOLIO_CONFIG_VERSION_UNSUPPORTED_MESSAGE = "作品集配置版本不支持";

    /** 服务端不支持更高编辑器能力版本提示 */
    String EDITOR_SCHEMA_REVISION_UNSUPPORTED_MESSAGE = "当前服务暂不支持此作品集配置，请稍后重试";

    /** 启用组件为空提示 */
    String ENABLED_COMPONENT_REQUIRED_MESSAGE = "作品集至少需要 1 个启用组件";

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

    /** 跨菜单组件标识重复提示 */
    String COMPONENT_KEY_CROSS_MENU_DUPLICATE_MESSAGE = "组件标识不能重复";

    /** 菜单至少需要一个组件提示模板 */
    String MENU_COMPONENT_REQUIRED_TEMPLATE = "【%s】至少添加一个组件";

    /** 菜单业务错误前缀模板 */
    String MENU_ERROR_PREFIX_TEMPLATE = "【%s】%s";

    /** 同一菜单个人资料组件数量超限提示 */
    String MENU_PROFILE_COMPONENT_LIMIT_MESSAGE = "同一菜单只能添加一个个人资料组件";

    /** 个人资料组件数量超限提示 */
    String PROFILE_COMPONENT_LIMIT_MESSAGE = "个人作品集最多只能包含一个个人资料组件";

    /** 组件标识重复提示 */
    String COMPONENT_KEY_DUPLICATE_MESSAGE = "作品集组件标识重复，请刷新后重试";

    /** 联系表单组件不存在提示 */
    String CONTACT_FORM_COMPONENT_NOT_FOUND_MESSAGE = "联系表单组件不存在";

    /** 联系线索访问记录无效提示 */
    String CONTACT_LEAD_VISIT_RECORD_INVALID_MESSAGE = "访问记录无效";

    /** 联系线索字段长度无效提示 */
    String CONTACT_LEAD_FIELD_LENGTH_INVALID_MESSAGE = "预留联系信息字段长度不合法";

    /** 同一访客同一作品集联系线索数量上限提示 */
    String CONTACT_LEAD_SUBMISSION_LIMIT_MESSAGE = "同一访客对同一作品集最多可提交3次联系方式";

    /** 暂不支持组件提示模板 */
    String COMPONENT_UNSUPPORTED_TEMPLATE = "暂不支持的作品集组件：%s";

    /** 作品引用不可用提示 */
    String WORK_REFERENCE_INVALID_MESSAGE = "作品集引用了不可用作品，请刷新作品列表后重试";

    /** 超链接展示作品为空提示 */
    String HYPERLINK_DISPLAY_WORK_REQUIRED_MESSAGE = "请选择图片或动图作品";

    /** 超链接展示作品不可用提示 */
    String HYPERLINK_DISPLAY_WORK_INVALID_MESSAGE = "超链接展示作品不可用，请重新选择";

    /** 超链接点击行为为空提示 */
    String HYPERLINK_ACTION_REQUIRED_MESSAGE = "请选择点击行为";

    /** 超链接内部目标为空提示 */
    String HYPERLINK_TARGET_REQUIRED_MESSAGE = "请选择已发布的个人作品集";

    /** 超链接外部内容长度提示 */
    String HYPERLINK_EXTERNAL_CONTENT_LENGTH_MESSAGE = "链接或分享内容长度必须为1至2048个字符";

    /** 超链接提示语长度提示 */
    String HYPERLINK_PROMPT_TEXT_LENGTH_MESSAGE = "提示语长度必须为1至30个字符";

    /** 超链接图标位置提示 */
    String HYPERLINK_ICON_POSITION_UNSUPPORTED_MESSAGE = "点击图标位置不支持";

    /** 轮播图只能选图片提示 */
    String CAROUSEL_IMAGE_ONLY_MESSAGE = "轮播图只能选择图片作品";

    /** 视频轮播标题过长提示 */
    String VIDEO_CAROUSEL_TITLE_LENGTH_MESSAGE = "视频轮播标题不能超过10个字符";

    /** 视频轮播作品数量提示 */
    String VIDEO_CAROUSEL_WORK_COUNT_MESSAGE = "视频轮播需选择3至8个视频作品";

    /** 视频轮播作品重复提示 */
    String VIDEO_CAROUSEL_WORK_DUPLICATE_MESSAGE = "视频轮播不能重复选择同一视频作品";

    /** 二维码来源不支持提示 */
    String QR_SOURCE_UNSUPPORTED_MESSAGE = "二维码来源不支持";

    /** 自定义二维码为空提示 */
    String CUSTOM_QR_REQUIRED_MESSAGE = "请上传自定义联系二维码";

    /** 联系表单联系人字段缺失提示 */
    String CONTACT_FORM_NAME_FIELD_REQUIRED_MESSAGE = "预留联系信息必须包含联系人字段";

    /** 联系表单联系方式字段缺失提示 */
    String CONTACT_FORM_CONTACT_FIELD_REQUIRED_MESSAGE = "预留联系信息必须包含手机号或微信号字段";

    /** 联系表单展示方式不支持提示 */
    String CONTACT_FORM_DISPLAY_MODE_UNSUPPORTED_MESSAGE = "预留联系信息展示方式不支持";

    /** 文字说明内容为空提示 */
    String TEXT_SECTION_CONTENT_REQUIRED_MESSAGE = "文字说明内容不能为空";

    /** 文字说明内容超长提示 */
    String TEXT_SECTION_CONTENT_LENGTH_MESSAGE = "文字说明不能超过 200 字";

    /** 文字说明对齐方式不支持提示 */
    String TEXT_SECTION_ALIGNMENT_UNSUPPORTED_MESSAGE = "文字说明对齐方式不支持";

    /** 文字说明字体不支持提示 */
    String TEXT_SECTION_FONT_UNSUPPORTED_MESSAGE = "文字说明字体不支持";

    /** 文字说明字号无效提示 */
    String TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE = "文字说明字号必须为20至48之间的整数";

    /** 分割线颜色不支持提示 */
    String DIVIDER_COLOR_UNSUPPORTED_MESSAGE = "分割线颜色不支持";

    /** 分割线高度无效提示 */
    String DIVIDER_HEIGHT_INVALID_MESSAGE = "分割线高度必须大于 0";

    /** 作品集展示标签名称为空提示 */
    String DISPLAY_TAG_NAME_REQUIRED_MESSAGE = "作品集展示标签名称不能为空";

    /** 作品集展示标签名称重复提示 */
    String DISPLAY_TAG_NAME_DUPLICATE_MESSAGE = "作品集展示标签名称不能重复";

    /** 作品集展示标签标识重复提示 */
    String DISPLAY_TAG_KEY_DUPLICATE_MESSAGE = "作品集展示标签标识不能重复";

    /** 档期查询范围类型不支持提示 */
    String SCHEDULE_QUERY_RANGE_UNSUPPORTED_MESSAGE = "档期查询范围不支持";

    /** 档期查询展示方式不支持提示 */
    String SCHEDULE_QUERY_DISPLAY_MODE_UNSUPPORTED_MESSAGE = "档期查询展示方式不支持";

    /** 档期查询未来天数无效提示 */
    String SCHEDULE_QUERY_FUTURE_DAYS_INVALID_MESSAGE = "档期查询未来天数必须大于 0";

    /** 档期查询日期格式无效提示 */
    String SCHEDULE_QUERY_DATE_INVALID_MESSAGE = "档期查询日期格式不正确";

    /** 档期查询日期范围无效提示 */
    String SCHEDULE_QUERY_DATE_RANGE_INVALID_MESSAGE = "档期查询开始日期不能晚于结束日期";

    /** 档期查询组件不存在提示 */
    String SCHEDULE_QUERY_COMPONENT_NOT_FOUND_MESSAGE = "档期查询组件不存在";

    /** 档期查询请求体为空提示 */
    String SCHEDULE_QUERY_REQUEST_REQUIRED_MESSAGE = "档期查询请求不能为空";

    /** 档期查询日期为空提示 */
    String SCHEDULE_QUERY_DATE_REQUIRED_MESSAGE = "请选择查询日期";

    /** 档期查询档位为空提示 */
    String SCHEDULE_QUERY_SLOT_REQUIRED_MESSAGE = "请选择查询档位";

    /** 档期查询档位不可用提示 */
    String SCHEDULE_QUERY_SLOT_UNAVAILABLE_MESSAGE = "档位不可用";

    /** 展示作品为空提示 */
    String DISPLAY_WORK_REQUIRED_MESSAGE = "请选择要展示的作品";

    /** 展示作品数量超限提示 */
    String DISPLAY_WORK_COUNT_LIMIT_MESSAGE = "选择的作品数量过多";

    /** 组件为空提示 */
    String PORTFOLIO_COMPONENT_EMPTY_MESSAGE = "作品集组件不能为空";
}
