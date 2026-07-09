package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 维护者本人访问识别服务测试。
 */
@ExtendWith(MockitoExtension.class)
class OwnerSelfVisitServiceTest {

    /** 用户登录身份 Mapper 模拟 */
    @Mock
    private UserAuthEntityMapper userAuthEntityMapper;

    /**
     * 访客 openid 为空时不查询数据库，历史空值不视为本人访问。
     */
    @Test
    void shouldReturnFalseWithoutQueryWhenVisitorOpenIdIsBlank() {
        boolean result = service().isOwnerSelfVisitor(7L, " ", 88L, 1024L, "PORTFOLIO_OPENED");

        assertThat(result).isFalse();
        verify(userAuthEntityMapper, never()).selectOne(any());
    }

    /**
     * ownerId 为空时直接返回 false，避免后续比较触发空值问题。
     */
    @Test
    void shouldReturnFalseWithoutQueryWhenOwnerIdIsNull() {
        boolean result = service().isOwnerSelfVisitor(null, "openid-123", 88L, 1024L, "PORTFOLIO_OPENED");

        assertThat(result).isFalse();
        verify(userAuthEntityMapper, never()).selectOne(any());
    }

    /**
     * 查询登录身份时只使用 open_id，不使用摘要或状态兜底。
     */
    @Test
    void shouldQueryOnlyByOpenIdAndReturnFalseWhenNoAuthMatched() {
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);

        boolean result = service().isOwnerSelfVisitor(7L, "openid-123", 88L, 1024L, "WORK_VIEWED");

        assertThat(result).isFalse();
        ArgumentCaptor<Wrapper<UserAuthEntity>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userAuthEntityMapper).selectOne(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("open_id")
                .doesNotContain("identifier_hash")
                .doesNotContain("status");
    }

    /**
     * 查到其他维护者身份时不能认定为当前作品集维护者本人。
     */
    @Test
    void shouldReturnFalseWhenOpenIdBelongsToAnotherOwner() {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(9L);
        auth.setOpenId("openid-123");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);

        boolean result = service().isOwnerSelfVisitor(7L, "openid-123", 88L, 1024L, "VIDEO_PLAYED");

        assertThat(result).isFalse();
    }

    /**
     * 查到当前作品集 owner 的微信身份时认定为本人访问。
     */
    @Test
    void shouldReturnTrueWhenOpenIdBelongsToPortfolioOwner() {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(7L);
        auth.setOpenId("openid-123");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);

        boolean result = service().isOwnerSelfVisitor(7L, "openid-123", 88L, 1024L, "QR_CODE_INTERACTED");

        assertThat(result).isTrue();
    }

    /**
     * 即便异常测试桩返回空 openId，也应安全返回 false 而不是触发 NPE。
     */
    @Test
    void shouldReturnFalseWhenMatchedAuthOpenIdIsNull() {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(7L);
        auth.setOpenId(null);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);

        boolean result = service().isOwnerSelfVisitor(7L, "openid-123", 88L, 1024L, "WORK_VIEWED");

        assertThat(result).isFalse();
    }

    /**
     * 构造被测服务。
     *
     * @return 被测服务
     */
    private OwnerSelfVisitService service() {
        return new OwnerSelfVisitService(userAuthEntityMapper);
    }
}
