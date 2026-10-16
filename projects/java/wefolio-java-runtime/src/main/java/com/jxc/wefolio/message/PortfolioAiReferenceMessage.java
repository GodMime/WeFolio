package com.jxc.wefolio.message;

/** 高级 AI 参考附件的受控错误，不包含签名、对象键或供应商原文。 */
public interface PortfolioAiReferenceMessage {
    String INVALID = "参考附件参数不正确";
    String UNAVAILABLE = "参考附件不可用，请重新选择文件或移除后重试";
    String EXPIRED = "参考附件暂存已过期，请重新选择文件或移除后重试";
    String DISABLED = "参考附件功能暂未开放";
    String BUSY = "参考附件正在更新，请稍后重试";
    String STORAGE = "参考附件暂时无法读取，请稍后重试";
    String MEDIA = "参考附件格式、大小或时长不符合要求，请转换后重新选择";
    String PROBE = "参考附件媒体校验暂不可用，请稍后重试";
}
