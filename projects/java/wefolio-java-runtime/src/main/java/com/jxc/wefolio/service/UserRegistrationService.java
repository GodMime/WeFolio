package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.RegistrationPointProperties;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.ReferralRelationEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ReferralRelationEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.service.point.GiftCommand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户注册服务，集中维护用户与登录身份绑定的事务写入。
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    /** 微信小程序登录类型 */
    private static final String WECHAT_AUTH_TYPE = AuthTypeDict.WECHAT_MINI_APP.getCode();

    /** 维护者新用户默认昵称 */
    private static final String DEFAULT_WECHAT_NICKNAME = "微信用户";

    /** 维护者新用户默认头像 */
    private static final String DEFAULT_WECHAT_AVATAR_URL =
            "https://cdn2.we-folio.dingchenyong.top/system/wefolio-default-avatar-512.jpg";

    /** 新用户注册赠送描述 */
    private static final String NEW_USER_REGISTRATION_GIFT_REMARK = "新用户注册赠送";

    /** 推荐用户注册赠送描述 */
    private static final String REFERRAL_USER_GIFT_REMARK = "推荐用户注册赠送";

    /** 新用户注册业务类型 */
    private static final String BUSINESS_TYPE_USER_REGISTRATION = "USER_REGISTRATION";

    /** 推荐注册业务类型 */
    private static final String BUSINESS_TYPE_REFERRAL_REGISTRATION = "REFERRAL_REGISTRATION";

    /** 新用户注册赠送幂等键前缀 */
    private static final String NEW_USER_REGISTRATION_GIFT_IDEMPOTENCY_PREFIX = "NEW_USER_REGISTRATION_GIFT:";

    /** 推荐用户注册赠送幂等键前缀 */
    private static final String REFERRAL_USER_GIFT_IDEMPOTENCY_PREFIX = "REFERRAL_USER_GIFT:";

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 用户登录身份 Mapper */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /** 推荐关系 Mapper */
    private final ReferralRelationEntityMapper referralRelationEntityMapper;

    /** 积分服务 */
    private final PointService pointService;

    /** 注册积分配置 */
    private final RegistrationPointProperties registrationPointProperties;

    /**
     * 创建维护者微信用户，并在同一事务中创建微信身份绑定。
     *
     * @param uniqueCode 已生成的个人唯一码
     * @param request 维护者微信登录请求
     * @param phoneInfo 微信手机号信息
     * @param openpid 插件用户 openpid
     * @param openidHash openid 摘要
     * @param openId 微信 openid 明文，仅服务端用于本人访问识别
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
            String openId,
            String unionidHash
    ) {
        LocalDateTime now = LocalDateTime.now();
        UserEntity user = new UserEntity();
        user.setUniqueCode(uniqueCode);
        user.setNickname(defaultString(request.getNickname(), DEFAULT_WECHAT_NICKNAME));
        user.setAvatarUrl(defaultString(request.getAvatarUrl(), DEFAULT_WECHAT_AVATAR_URL));
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

        createWechatAuth(user, openidHash, openId, unionidHash, now);
        pointService.ensureAccount(user.getId());
        List<GiftCommand> giftCommands = new ArrayList<>();
        giftCommands.add(newUserRegistrationGift(user.getId()));
        GiftCommand referralGift = bindReferralAndBuildGift(user, request.getReferralCode(), now);
        if (referralGift != null) {
            giftCommands.add(referralGift);
        }
        pointService.createGiftOrders(giftCommands);
        return user;
    }

    /**
     * 赠送新用户注册积分。
     *
     * @param userId 新用户 ID
     */
    private GiftCommand newUserRegistrationGift(Long userId) {
        return new GiftCommand(
                userId,
                PointSceneCodeDict.NEW_USER_REGISTRATION_GIFT.getCode(),
                registrationPointProperties.getNewUserGiftPoints(),
                BUSINESS_TYPE_USER_REGISTRATION,
                String.valueOf(userId),
                giftSnapshot(NEW_USER_REGISTRATION_GIFT_REMARK),
                NEW_USER_REGISTRATION_GIFT_IDEMPOTENCY_PREFIX + userId
        );
    }

    /**
     * 绑定推荐关系并给推荐人赠送积分，推荐码未命中时静默跳过。
     *
     * @param referredUser 新注册用户
     * @param referralCode 注册时填写的推荐码
     * @param now 当前时间
     */
    private GiftCommand bindReferralAndBuildGift(
            UserEntity referredUser,
            String referralCode,
            LocalDateTime now
    ) {
        String normalizedReferralCode = normalizeOptionalString(referralCode);
        if (normalizedReferralCode.isBlank()) {
            return null;
        }
        UserEntity referrer = findActiveReferrer(normalizedReferralCode, referredUser.getId());
        if (referrer == null) {
            return null;
        }

        ReferralRelationEntity relation = new ReferralRelationEntity();
        relation.setReferrerUserId(referrer.getId());
        relation.setReferredUserId(referredUser.getId());
        relation.setReferralCodeSnapshot(normalizedReferralCode);
        relation.setBoundAt(now);
        referralRelationEntityMapper.insert(relation);

        return new GiftCommand(
                referrer.getId(),
                PointSceneCodeDict.REFERRAL_USER_GIFT.getCode(),
                registrationPointProperties.getReferralGiftPoints(),
                BUSINESS_TYPE_REFERRAL_REGISTRATION,
                String.valueOf(referredUser.getId()),
                giftSnapshot(REFERRAL_USER_GIFT_REMARK),
                REFERRAL_USER_GIFT_IDEMPOTENCY_PREFIX + referredUser.getId()
        );
    }

    /** 构造不可变赠送来源快照。 */
    private String giftSnapshot(String remark) {
        return JSON.toJSONString(Map.of("remark", remark));
    }

    /**
     * 按个人唯一码查找启用推荐人。
     *
     * @param referralCode 推荐码
     * @param referredUserId 被推荐用户 ID
     * @return 推荐人，未命中时返回空
     */
    private UserEntity findActiveReferrer(String referralCode, Long referredUserId) {
        return userEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserEntity.class)
                        .eq(UserEntity::getUniqueCode, referralCode)
                        .eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .ne(UserEntity::getId, referredUserId)
                        .last("LIMIT 1")
        );
    }

    /**
     * 创建微信登录身份绑定。
     *
     * @param user 用户实体
     * @param openidHash openid 摘要
     * @param openId 微信 openid 明文，仅服务端用于本人访问识别
     * @param unionidHash unionid 摘要，可为空
     * @param now 绑定时间
     */
    private void createWechatAuth(UserEntity user, String openidHash, String openId, String unionidHash, LocalDateTime now) {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(user.getId());
        auth.setAuthType(WECHAT_AUTH_TYPE);
        auth.setIdentifierHash(openidHash);
        auth.setIdentifierCiphertext("WECHAT_OPENID_BOUND");
        auth.setOpenId(openId);
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
     * 标准化可选字符串。
     *
     * @param value 原始值
     * @return 去除首尾空白后的字符串
     */
    private String normalizeOptionalString(String value) {
        return value == null ? "" : value.strip();
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
