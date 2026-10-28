package com.jxc.wefolio.message;

/** 时间轴配置与能力校验的稳定错误文案。 */
public interface PortfolioTimelineMessage {
    /** 字段路径只由服务端固定规则生成。 */
    String INVALID_CONFIG = "时间轴配置无效：";
    /** 声明与结构版本不一致。 */
    String INVALID_CAPABILITY = "作品集编辑能力声明无效";
    /** 当前客户端不能新增该组件。 */
    String UNSUPPORTED_COMPONENT = "请升级客户端后编辑时间轴";
}
