package com.jxc.wefolio.service;

import com.jxc.wefolio.config.LoginPageProperties;
import com.jxc.wefolio.dict.LoginTabDict;
import com.jxc.wefolio.dto.LoginPageConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 登录页配置服务，将服务端配置转换为小程序可识别的公开展示配置。 */
@Service
@RequiredArgsConstructor
public class LoginPageConfigService {

    /** 登录页环境配置。 */
    private final LoginPageProperties loginPageProperties;

    /** 获取默认标签；空值或未知配置统一回退体验，避免影响登录页可用性。 */
    public LoginPageConfigResponse getConfig() {
        LoginTabDict defaultTab = LoginTabDict.fromCode(loginPageProperties.getDefaultTab());
        LoginPageConfigResponse response = new LoginPageConfigResponse();
        response.setDefaultTab((defaultTab != null ? defaultTab : LoginTabDict.EXPERIENCE).getCode());
        return response;
    }
}
