package com.jxc.wefolio.message;

/**
 * 我的档期报错信息 — 统一维护档位定义和档期记录相关提示。
 */
public interface MineScheduleMessage {

    /** 重复档位名称提示 */
    String SLOT_NAME_DUPLICATE_MESSAGE = "档位名称已存在";

    /** 重复档位颜色提示 */
    String SLOT_COLOR_DUPLICATE_MESSAGE = "档位颜色已被使用，请选择其他颜色";

    /** 档位颜色唯一索引名 */
    String SLOT_COLOR_UNIQUE_INDEX = "uk_slot_user_color_deleted";

    /** 重复档期提示 */
    String SCHEDULE_DUPLICATE_MESSAGE = "当天该档位已存在";
}
