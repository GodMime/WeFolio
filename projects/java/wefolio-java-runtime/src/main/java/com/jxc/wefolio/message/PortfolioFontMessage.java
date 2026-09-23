package com.jxc.wefolio.message;

/** 字体配置校验、生成及资源处理异常提示。 */
public interface PortfolioFontMessage {
    /** 取证未在本次预算内完成，不据此断言节点损坏。 */
    String ENVIRONMENT_EVIDENCE_INCOMPLETE = "字体环境取证未完成";
    /** 节点故障已锁存，本请求尚未交付的字体组停止。 */
    String NODE_NOT_READY = "字体节点未就绪";

    /** 字体标识或版本表结构错误。 */
    String INVALID_CONFIG = "字体配置格式无效";

    /** 同步处理超过请求预算。 */
    String BUDGET_EXPIRED = "字体处理预算已到期";

    /** 拒绝重复使用已消费的准备凭据。 */
    String PREPARED_ALREADY_CONSUMED = "字体准备凭据已消费";

    /** 同作品集已有同步准备请求。 */
    String PREPARE_ALREADY_RUNNING = "同作品集已有字体准备请求";

    /** 字体外部操作不得持有数据库事务。 */
    String IO_REQUIRES_NO_TRANSACTION = "字体 I/O 必须在事务外执行";

    /** 字体存储归属或作品集标识不合法。 */
    String STORAGE_OWNER_MISSING = "作品集字体存储归属缺失";

    /** 启动生成自检未产出完整字体组。 */
    String STARTUP_SELF_TEST_FAILED = "字体启动生成自检失败";

    /** 子集生成或最终字形校验失败。 */
    String SUBSET_VALIDATION_FAILED = "字体子集生成或最终校验失败";

    /** 生成产物的独立许可复核失败。 */
    String FINAL_LICENSE_VALIDATION_FAILED = "字体最终许可复核失败";

    /** 许可复核结果未覆盖全部产物。 */
    String LICENSE_RESULTS_INCOMPLETE = "字体许可复核结果不完整";

    /** 字形校验结果未覆盖全部产物。 */
    String VALIDATION_RESULTS_INCOMPLETE = "字体校验结果不完整";

    /** COS 请求失败状态码前缀，保留末尾空格。 */
    String COS_REQUEST_FAILED_PREFIX = "字体 COS 请求失败，HTTP ";

    /** 离线制品验证入口的参数用法提示。 */
    String INVALID_OFFLINE_ARGUMENTS = "参数应为 Python 与 hb-subset 路径；验证模式追加 --verify 工作目录 期望工具链摘要 期望构建摘要";
}
