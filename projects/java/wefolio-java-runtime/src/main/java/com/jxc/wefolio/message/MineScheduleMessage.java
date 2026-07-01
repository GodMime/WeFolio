package com.jxc.wefolio.message;

/**
 * 我的档期报错信息 — 统一维护档位定义和档期记录相关提示。
 */
public interface MineScheduleMessage {

    /** 重复档期提示 */
    String SCHEDULE_DUPLICATE_MESSAGE = "当天该档位已存在";
}
