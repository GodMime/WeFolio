package com.jxc.wefolio.dto;

import com.jxc.wefolio.dict.LoginTabDict;
import lombok.Data;

/** 登录页公开配置响应，仅包含允许未登录客户端读取的展示配置。 */
@Data
public class LoginPageConfigResponse {

    /** 默认标签标识，取值见 {@link LoginTabDict}。 */
    private String defaultTab;
}
