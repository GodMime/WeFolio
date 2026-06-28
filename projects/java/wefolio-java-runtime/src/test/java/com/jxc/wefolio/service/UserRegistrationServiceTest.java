package com.jxc.wefolio.service;

import com.jxc.wefolio.config.RegistrationPointProperties;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.entity.ReferralRelationEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.ReferralRelationEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户注册服务测试 — 覆盖注册积分赠送和推荐关系绑定。
 */
@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    /** 用户资料 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 用户登录身份 Mapper 模拟 */
    @Mock
    private UserAuthEntityMapper userAuthEntityMapper;

    /** 推荐关系 Mapper 模拟 */
    @Mock
    private ReferralRelationEntityMapper referralRelationEntityMapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    @Test
    void createWechatMaintainerUserGrantsConfiguredNewUserGift() {
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(21L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));

        MaintainerWechatLoginRequest request = request(null);

        UserEntity user = service(500L, 500L).createWechatMaintainerUser(
                "WFNEW0001",
                request,
                phoneInfo(),
                "openpid-abc",
                "openid-hash",
                null
        );

        assertThat(user.getId()).isEqualTo(21L);
        verify(pointService).ensureAccount(21L);
        verify(pointService).grantGift(
                eq(21L),
                eq(500L),
                eq(PointSceneCodeDict.NEW_USER_REGISTRATION_GIFT.getCode()),
                eq("USER_REGISTRATION"),
                eq("21"),
                eq("NEW_USER_REGISTRATION_GIFT:21"),
                eq("新用户注册赠送")
        );
        verify(referralRelationEntityMapper, never()).insert(any(ReferralRelationEntity.class));
    }

    @Test
    void createWechatMaintainerUserBindsReferralAndRewardsReferrerWhenCodeMatchesActiveUser() {
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(21L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));
        UserEntity referrer = new UserEntity();
        referrer.setId(7L);
        referrer.setUniqueCode("WFREF0001");
        referrer.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userEntityMapper.selectOne(any())).thenReturn(referrer);

        service(500L, 500L).createWechatMaintainerUser(
                "WFNEW0001",
                request(" WFREF0001 "),
                phoneInfo(),
                "openpid-abc",
                "openid-hash",
                null
        );

        ArgumentCaptor<ReferralRelationEntity> relationCaptor =
                ArgumentCaptor.forClass(ReferralRelationEntity.class);
        verify(referralRelationEntityMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getReferrerUserId()).isEqualTo(7L);
        assertThat(relationCaptor.getValue().getReferredUserId()).isEqualTo(21L);
        assertThat(relationCaptor.getValue().getReferralCodeSnapshot()).isEqualTo("WFREF0001");
        assertThat(relationCaptor.getValue().getBoundAt()).isNotNull();
        verify(pointService).grantGift(
                eq(7L),
                eq(500L),
                eq(PointSceneCodeDict.REFERRAL_USER_GIFT.getCode()),
                eq("REFERRAL_REGISTRATION"),
                eq("21"),
                eq("REFERRAL_USER_GIFT:21"),
                eq("推荐用户注册赠送")
        );
    }

    @Test
    void createWechatMaintainerUserSkipsUnknownReferralCodeWithoutError() {
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(21L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));
        when(userEntityMapper.selectOne(any())).thenReturn(null);

        service(500L, 500L).createWechatMaintainerUser(
                "WFNEW0001",
                request("UNKNOWN"),
                phoneInfo(),
                "openpid-abc",
                "openid-hash",
                null
        );

        verify(referralRelationEntityMapper, never()).insert(any(ReferralRelationEntity.class));
        verify(pointService, never()).grantGift(
                eq(7L),
                any(),
                eq(PointSceneCodeDict.REFERRAL_USER_GIFT.getCode()),
                any(),
                any(),
                any(),
                any()
        );
    }

    /**
     * 构造被测服务。
     *
     * @param newUserGiftPoints 新用户赠送积分
     * @param referralGiftPoints 推荐人赠送积分
     * @return 被测服务
     */
    private UserRegistrationService service(Long newUserGiftPoints, Long referralGiftPoints) {
        RegistrationPointProperties properties = new RegistrationPointProperties();
        properties.setNewUserGiftPoints(newUserGiftPoints);
        properties.setReferralGiftPoints(referralGiftPoints);
        return new UserRegistrationService(
                userEntityMapper,
                userAuthEntityMapper,
                referralRelationEntityMapper,
                pointService,
                properties
        );
    }

    /**
     * 构造注册请求。
     *
     * @param referralCode 推荐码
     * @return 注册请求
     */
    private MaintainerWechatLoginRequest request(String referralCode) {
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setNickname("林安");
        request.setAvatarUrl("https://example.com/avatar.jpg");
        request.setReferralCode(referralCode);
        return request;
    }

    /**
     * 构造微信手机号信息。
     *
     * @return 手机号信息
     */
    private WechatPhoneNumberResponse.PhoneInfo phoneInfo() {
        WechatPhoneNumberResponse.PhoneInfo phoneInfo = new WechatPhoneNumberResponse.PhoneInfo();
        phoneInfo.setPhoneNumber("+8613812348000");
        phoneInfo.setCountryCode("86");
        return phoneInfo;
    }
}
