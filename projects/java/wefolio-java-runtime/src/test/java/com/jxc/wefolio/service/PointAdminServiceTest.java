package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AdminPointProperties;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台积分服务测试 — 覆盖密钥校验、用户唯一码查询和人工加分委托。
 */
@ExtendWith(MockitoExtension.class)
class PointAdminServiceTest {

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 用户 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    @Test
    void grantPointsFindsActiveUserByUniqueCodeAndDelegatesToPointService() {
        AdminPointGrantRequest request = grantRequest();
        UserEntity user = activeUser(7L, "WFA3B1E7A2");
        PointMutationResponse mutationResponse = new PointMutationResponse();
        mutationResponse.setBalanceAfter(1000L);
        when(userEntityMapper.selectOne(any())).thenReturn(user);
        when(pointService.grantPoints(7L, 1000L, "manual-20260626-WFA3B1E7A2", "一期初始化积分"))
                .thenReturn(mutationResponse);

        PointMutationResponse response = service("admin-secret").grantPoints("admin-secret", request);

        assertThat(response).isSameAs(mutationResponse);
        verify(userEntityMapper).selectOne(any());
        verify(pointService).grantPoints(7L, 1000L, "manual-20260626-WFA3B1E7A2", "一期初始化积分");
    }

    @Test
    void grantPointsRejectsWrongSecretBeforeQueryingUser() {
        AdminPointGrantRequest request = grantRequest();

        assertThatThrownBy(() -> service("admin-secret").grantPoints("wrong", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("后台积分密钥无效");
        verify(userEntityMapper, never()).selectOne(any());
        verify(pointService, never()).grantPoints(any(), any(), any(), any());
    }

    @Test
    void grantPointsRejectsBlankUniqueCode() {
        AdminPointGrantRequest request = grantRequest();
        request.setUniqueCode(" ");

        assertThatThrownBy(() -> service("admin-secret").grantPoints("admin-secret", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户唯一码不能为空");
        verify(userEntityMapper, never()).selectOne(any());
        verify(pointService, never()).grantPoints(any(), any(), any(), any());
    }

    @Test
    void grantPointsRejectsMissingUser() {
        AdminPointGrantRequest request = grantRequest();
        when(userEntityMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service("admin-secret").grantPoints("admin-secret", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户不存在或已停用");
        verify(pointService, never()).grantPoints(any(), any(), any(), any());
    }

    /**
     * 构造后台积分服务。
     *
     * @param secret 配置密钥
     * @return 后台积分服务
     */
    private PointAdminService service(String secret) {
        AdminPointProperties properties = new AdminPointProperties();
        properties.setSecret(secret);
        return new PointAdminService(pointService, userEntityMapper, properties);
    }

    /**
     * 构造人工加分请求。
     *
     * @return 人工加分请求
     */
    private AdminPointGrantRequest grantRequest() {
        AdminPointGrantRequest request = new AdminPointGrantRequest();
        request.setUniqueCode("WFA3B1E7A2");
        request.setPoints(1000L);
        request.setIdempotencyKey("manual-20260626-WFA3B1E7A2");
        request.setRemark("一期初始化积分");
        return request;
    }

    /**
     * 构造启用用户。
     *
     * @param userId 用户 ID
     * @param uniqueCode 用户唯一码
     * @return 用户实体
     */
    private UserEntity activeUser(Long userId, String uniqueCode) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUniqueCode(uniqueCode);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }
}
