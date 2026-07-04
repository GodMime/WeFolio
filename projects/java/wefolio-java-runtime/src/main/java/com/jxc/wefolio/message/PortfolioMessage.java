package com.jxc.wefolio.message;

/**
 * 作品集报错信息 — 统一维护作品集维护端、访客端和联系线索相关提示。
 */
public interface PortfolioMessage {

    /** 作品集不可访问提示 */
    String PORTFOLIO_UNAVAILABLE_MESSAGE = "作品集暂不可访问";

    /** 微信登录凭证为空提示 */
    String WECHAT_LOGIN_CODE_REQUIRED_MESSAGE = "微信登录凭证不能为空";

    /** 微信 openid 缺失提示 */
    String WECHAT_OPENID_MISSING_MESSAGE = "微信登录未返回 openid";

    /** 微信身份摘要生成失败提示 */
    String OPENID_DIGEST_FAILED_MESSAGE = "微信身份摘要生成失败";

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

    /** 作品集不存在提示 */
    String PORTFOLIO_NOT_FOUND_MESSAGE = "作品集不存在";

    /** 作品集并发更新冲突提示 */
    String PORTFOLIO_CONCURRENT_UPDATE_MESSAGE = "并发冲突，请刷新重试";

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

    /** 启用组件为空提示 */
    String ENABLED_COMPONENT_REQUIRED_MESSAGE = "作品集至少需要 1 个启用组件";

    /** 组件标识重复提示 */
    String COMPONENT_KEY_DUPLICATE_MESSAGE = "作品集组件标识重复，请刷新后重试";

    /** 联系表单组件不存在提示 */
    String CONTACT_FORM_COMPONENT_NOT_FOUND_MESSAGE = "联系表单组件不存在";

    /** 暂不支持组件提示模板 */
    String COMPONENT_UNSUPPORTED_TEMPLATE = "暂不支持的作品集组件：%s";

    /** 作品引用不可用提示 */
    String WORK_REFERENCE_INVALID_MESSAGE = "作品集引用了不可用作品，请刷新作品列表后重试";

    /** 轮播图只能选图片提示 */
    String CAROUSEL_IMAGE_ONLY_MESSAGE = "轮播图只能选择图片作品";

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
