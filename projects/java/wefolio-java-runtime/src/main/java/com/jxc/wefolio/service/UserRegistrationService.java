package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户注册服务，集中维护用户与登录身份绑定的事务写入。
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    /** 微信小程序登录类型 */
    private static final String WECHAT_AUTH_TYPE = AuthTypeDict.WECHAT_MINI_APP.getCode();

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 用户登录身份 Mapper */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /**
     * 创建维护者微信用户，并在同一事务中创建微信身份绑定。
     *
     * @param uniqueCode 已生成的个人唯一码
     * @param request 维护者微信登录请求
     * @param phoneInfo 微信手机号信息
     * @param openpid 插件用户 openpid
     * @param openidHash openid 摘要
     * @param unionidHash unionid 摘要，可为空
     * @return 已创建的用户实体
     */
    @Transactional(rollbackFor = Exception.class)
    public UserEntity createWechatMaintainerUser(
            String uniqueCode,
            MaintainerWechatLoginRequest request,
            WechatPhoneNumberResponse.PhoneInfo phoneInfo,
            String openpid,
            String openidHash,
            String unionidHash
    ) {
        LocalDateTime now = LocalDateTime.now();
        UserEntity user = new UserEntity();
        user.setUniqueCode(uniqueCode);
        user.setNickname(defaultString(request.getNickname(), "微信用户"));
        user.setAvatarUrl(defaultString(request.getAvatarUrl(), ""));
        user.setPhoneNumber(phoneInfo.getPhoneNumber());
        user.setPhoneCountryCode(defaultString(phoneInfo.getCountryCode(), ""));
        user.setPhoneLast4(last4(phoneInfo.getPhoneNumber()));
        user.setPhoneBoundAt(now);
        user.setWechatOpenpid(openpid);
        user.setProfession("");
        user.setCity("");
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        user.setRegisteredAt(now);
        user.setLastLoginAt(now);
        userEntityMapper.insert(user);
        if (user.getId() == null) {
            throw new BusinessException("登录用户创建失败");
        }

        createWechatAuth(user, openidHash, unionidHash, now);
        return user;
    }

    /**
     * 创建微信登录身份绑定。
     *
     * @param user 用户实体
     * @param openidHash openid 摘要
     * @param unionidHash unionid 摘要，可为空
     * @param now 绑定时间
     */
    private void createWechatAuth(UserEntity user, String openidHash, String unionidHash, LocalDateTime now) {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(user.getId());
        auth.setAuthType(WECHAT_AUTH_TYPE);
        auth.setIdentifierHash(openidHash);
        auth.setIdentifierCiphertext("WECHAT_OPENID_BOUND");
        if (unionidHash != null && !unionidHash.isBlank()) {
            auth.setUnionIdentifierHash(unionidHash);
            auth.setUnionIdentifierCiphertext("WECHAT_UNIONID_BOUND");
        }
        auth.setStatus(UserStatusDict.ACTIVE.getCode());
        auth.setLastAuthenticatedAt(now);
        userAuthEntityMapper.insert(auth);
    }

    /**
     * 默认字符串。
     *
     * @param value 原字符串
     * @param fallback 兜底值
     * @return 非空字符串
     */
    private String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    /**
     * 获取手机号尾号。
     *
     * @param phoneNumber 手机号
     * @return 尾号
     */
    private String last4(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() <= 4) {
            return phoneNumber;
        }
        return phoneNumber.substring(phoneNumber.length() - 4);
    }
}
