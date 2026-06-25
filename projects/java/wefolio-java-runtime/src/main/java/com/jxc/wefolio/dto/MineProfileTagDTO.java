package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 基础信息个人标签项 — 同时保存标签内容和展示颜色。
 */
@Data
public class MineProfileTagDTO {

    /** 标签内容 */
    private String content;

    /** 标签颜色，使用固定色板中的十六进制颜色 */
    private String color;
}
