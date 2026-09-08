package com.jxc.wefolio.message;

/** 普通及结构化文字新增规则提示。 */
public interface PortfolioTextMessage {
    /** 普通文字颜色配置非法。 */
    String COLOR_INVALID = "文字颜色仅支持自动或六位十六进制颜色";
    /** 结构化配置非法。 */
    String CONFIG_INVALID = "结构化文字配置不正确，请检查区块内容与样式";
    /** 区块数量非法。 */
    String BLOCK_COUNT_INVALID = "结构化文字需要1至20个区块，并至少包含一个文字或列表区块";
    /** 文字总量非法。 */
    String CONTENT_TOO_LONG = "结构化文字最多2000个字符";
    /** 背景配置非法。 */
    String BACKGROUND_CONFIG_INVALID = "文字背景配置不正确";
    /** 未选择背景作品。 */
    String BACKGROUND_REQUIRED = "请选择背景作品";
    /** 背景资源失效。 */
    String BACKGROUND_UNAVAILABLE = "背景作品不可用，请重新选择或关闭背景";
}
