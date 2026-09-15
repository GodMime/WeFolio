package com.jxc.wefolio.config;

import com.jxc.wefolio.dict.LoginTabDict;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 登录页展示配置，通过环境变量控制普通入口的默认标签。 */
@Data
@Component
@ConfigurationProperties(prefix = LoginPageProperties.CONFIG_PREFIX)
public class LoginPageProperties {

    /** 登录页配置前缀。 */
    public static final String CONFIG_PREFIX = "wefolio.login";

    /** 默认标签标识，取值见 {@link LoginTabDict}；未配置时展示体验。 */
    private String defaultTab = LoginTabDict.EXPERIENCE.getCode();
}
