package com.jxc.wefolio.service.teamportfolio.component.contactform;

import lombok.Data;

import java.util.List;

/**
 * 团队预留联系信息组件配置。
 */
@Data
public class TeamContactFormComponentConfig {

    /** 组件标题。 */
    private String title;

    /** 组件描述。 */
    private String description;

    /** 表单展示方式。 */
    private String displayMode;

    /** 向访客展示的表单字段。 */
    private List<String> fields;
}
