package com.jxc.wefolio.message;

/** 高级作品集独立维护与来源边界的受控异常文案。 */
public interface AdvancedPortfolioMessage {
    /** 未部署或类型非法的客户端高级版本。 */
    String INVALID_VERSION = "高级作品集版本暂不支持，请更新客户端或稍后重试";
    /** 高级版本与普通结构能力声明矛盾。 */
    String INVALID_VERSION_CAPABILITY = "高级作品集版本与编辑器能力不匹配，请更新客户端";
    /** 旧客户端不能覆盖已保存的高版本草稿或采用高版本任务。 */
    String CLIENT_UPGRADE_REQUIRED = "作品集已使用更高版本功能，请升级客户端后重试";
    /** 请求存在未知字段或原始类型不正确。 */
    String INVALID_REQUEST = "高级作品集请求字段或类型无效";
    /** 普通展示配置存在未知字段或有损转换。 */
    String INVALID_CONFIG = "作品集配置字段或类型无效";
    /** 来源编号或高水位不符合稳定映射规则。 */
    String INVALID_CONTEXT = "AI 来源编号上下文无效，请重新加载作品集";
    /** 来源失效或当前授权不足。 */
    String SOURCE_UNAVAILABLE = "所选来源已移除或不可用";
    /** 高级团队引用的成员个人作品集失效。 */
    String MEMBER_PORTFOLIO_UNAVAILABLE = "成员个人作品集已移除或不可用";
}
