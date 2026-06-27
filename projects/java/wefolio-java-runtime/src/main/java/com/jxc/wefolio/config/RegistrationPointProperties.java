package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 注册积分配置 — 控制新用户注册和推荐用户注册的赠送积分。
 */
@Data
@Component
@ConfigurationProperties(prefix = "registration.point")
public class RegistrationPointProperties {

    /** 新用户注册赠送积分 */
    private Long newUserGiftPoints = 500L;

    /** 推荐用户注册时推荐人赠送积分 */
    private Long referralGiftPoints = 500L;
}
