package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AdminPointProperties;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.payment.PointDebitTaskTransactionService;
import com.jxc.wefolio.service.payment.PointGiftOrderTransactionService;
import com.jxc.wefolio.service.point.GiftOrderResult;
import com.jxc.wefolio.service.point.PointCommandService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 后台人工赠送测试。 */
class PointAdminServiceTest {

    /** 正确密钥应创建赠送订单，而不是直接修改积分账户。 */
    @Test
    void grantShouldCreateGiftOrder() {
        PointCommandService commandService = mock(PointCommandService.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        UserEntity user = new UserEntity();
        user.setId(7L);
        when(userMapper.selectOne(any())).thenReturn(user);
        GiftOrderResult expected = new GiftOrderResult(List.of(
                new GiftOrderResult.GiftOrderItem(1L, "WFG001", 7L, "READY", false)));
        when(commandService.createGiftOrders(any())).thenReturn(expected);
        AdminPointProperties properties = new AdminPointProperties();
        properties.setSecret("admin-secret");
        PointAdminService service = new PointAdminService(
                commandService,
                userMapper,
                mock(PointGiftOrderEntityMapper.class),
                mock(PointGiftOrderTransactionService.class),
                mock(PointDebitTaskTransactionService.class),
                new AdminPointSecretValidator(properties));
        AdminPointGrantRequest request = new AdminPointGrantRequest();
        request.setUniqueCode("WFA3B1E7A2");
        request.setPoints(1000L);
        request.setIdempotencyKey("manual-1");
        request.setRemark("人工赠送");

        assertThat(service.grantPoints("admin-secret", request)).isSameAs(expected);
    }
}
