package com.jxc.wefolio.message;

/**
 * 我的访问记录报错信息 — 统一维护访问记录读取与跟进相关提示。
 */
public interface MineVisitMessage {

    /** 访问记录并发状态变化提示。 */
    String VISIT_RECORD_STATE_CHANGED_MESSAGE = "访问记录状态已变化，请刷新后重试";
}
