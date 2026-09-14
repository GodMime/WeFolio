package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dto.VisitActivityTrackingDto;
import com.jxc.wefolio.dto.VisitActivityUpdateRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 采集协议解析、设备过滤和非法累计零写入契约。 */
class VisitActivitySessionApplicationServiceTest {
    /** 独立事务边界替身。 */
    private final VisitActivitySessionTransactionService transaction = mock(VisitActivitySessionTransactionService.class);
    /** 被测应用服务。 */
    private final VisitActivitySessionApplicationService service = new VisitActivitySessionApplicationService(transaction);

    /** 清理线程认证上下文。 */
    @AfterEach
    void clear() { VisitorContextHolder.clear(); }

    /** 小数、溢出、缺省及负数不得进入事务。 */
    @Test
    void rejectsInvalidCumulativeBeforeAnyWrite() {
        VisitorContextHolder.set(new VisitorContext(7L, "visitor", "token"));
        for (String value : new String[] {"-1", "1.5", "9223372036854775808"}) {
            VisitActivityUpdateRequest request = new VisitActivityUpdateRequest();
            request.setActiveDurationMs(new BigDecimal(value));
            assertThatThrownBy(() -> service.accept(new PortfolioEntity(), PortfolioTypeDict.PERSONAL.getCode(), 1L, request))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.accept(new PortfolioEntity(), PortfolioTypeDict.PERSONAL.getCode(), 1L, null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(transaction);
    }

    /** 设备控制字符过滤、空串转 NULL、超长按码点截断；输入对象不被修改。 */
    @Test
    void normalizesOptionalDeviceWithoutMutatingRequest() {
        VisitActivityTrackingDto request = tracking();
        VisitActivityTrackingDto.DeviceInfo device = new VisitActivityTrackingDto.DeviceInfo();
        device.setBrand(" A\nB\t ");
        device.setModel("型".repeat(140));
        device.setSystem("\n ");
        request.setDevice(device);
        service.open(new PortfolioEntity(), PortfolioTypeDict.PERSONAL.getCode(), 7L, "visitor", null,
                "open-key", request);
        verify(transaction).open(any(), anyString(), eq(7L), eq("visitor"), isNull(), eq("open-key"),
                argThat(value -> "AB".equals(value.getDevice().getBrand())
                        && value.getDevice().getModel().length() == 128
                        && value.getDevice().getSystem() == null));
        assertThat(device.getModel()).hasSize(140);
    }

    /** 四设备字段均按码点限制并过滤控制符；缺省设备仍允许创建仅计时会话。 */
    @Test
    void allDeviceFieldsUseCodePointLimitsAndDeviceRemainsOptional() {
        VisitActivityTrackingDto request = tracking();
        VisitActivityTrackingDto.DeviceInfo device = new VisitActivityTrackingDto.DeviceInfo();
        device.setBrand("😀".repeat(80));device.setModel("型".repeat(150));
        device.setSystem("\u0000\n" + "系".repeat(150));device.setPlatform("p".repeat(40) + "\t");request.setDevice(device);
        service.open(new PortfolioEntity(), PortfolioTypeDict.PERSONAL.getCode(),7L,"visitor",null,"open",request);
        ArgumentCaptor<VisitActivityTrackingDto> captor = ArgumentCaptor.forClass(VisitActivityTrackingDto.class);
        verify(transaction).open(any(),anyString(),anyLong(),anyString(),isNull(),eq("open"),captor.capture());
        VisitActivityTrackingDto.DeviceInfo actual = captor.getValue().getDevice();
        assertThat(actual.getBrand().codePointCount(0,actual.getBrand().length())).isEqualTo(64);
        assertThat(actual.getModel()).hasSize(128);
        assertThat(actual.getSystem()).hasSize(128).doesNotContain("\u0000","\n");
        assertThat(actual.getPlatform()).hasSize(32).doesNotContain("\t");
        service.open(new PortfolioEntity(), PortfolioTypeDict.PERSONAL.getCode(),7L,"visitor",null,"without-device",tracking());
        verify(transaction).open(any(),anyString(),anyLong(),anyString(),isNull(),eq("without-device"),
                argThat(value -> value.getDevice() == null));
    }

    /** 新协议必须带有效版本和双键。 */
    @Test
    void rejectsMissingOrMismatchedTrackingVersion() {
        VisitActivityTrackingDto request = tracking();
        request.setVersion(2);
        assertThatThrownBy(() -> service.open(new PortfolioEntity(), PortfolioTypeDict.PERSONAL.getCode(),
                7L, "visitor", null, "open", request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transaction);
    }

    /** 创建基础追踪请求。 */
    private VisitActivityTrackingDto tracking() {
        VisitActivityTrackingDto request = new VisitActivityTrackingDto();
        request.setVersion(1);
        request.setClientSessionKey("tracking-key");
        return request;
    }
}
