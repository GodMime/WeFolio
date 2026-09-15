package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dto.VisitActivityTrackingDto;
import com.jxc.wefolio.dto.VisitActivityUpdateRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.VisitActivityMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** 活动协议规范化与用例编排；作品集资格由来源服务先校验。 */
@Service
@RequiredArgsConstructor
public class VisitActivitySessionApplicationService {
    /** 当前采集协议版本。 */
    private static final int TRACKING_VERSION = 1;
    /** 随机键允许兼容 UUID 及小程序既有时间随机键。 */
    private static final Pattern SESSION_KEY = Pattern.compile("[A-Za-z0-9_:-]{1,64}");
    /** 独立事务代理，保证首次打开、计费和会话原子提交。 */
    private final VisitActivitySessionTransactionService transactionService;

    /** 验证并规范化追踪请求，之后进入独立事务。 */
    public VisitActivitySessionTransactionService.OpenResult open(PortfolioEntity portfolio, String type,
            Long visitorId, String visitorKey, String sourceType, String openKey, VisitActivityTrackingDto tracking) {
        if (tracking == null || tracking.getVersion() == null || tracking.getVersion() != TRACKING_VERSION
                || !validKey(tracking.getClientSessionKey()) || !validKey(openKey)) {
            throw new BusinessException(VisitActivityMessage.INVALID_TRACKING);
        }
        VisitActivityTrackingDto normalized = new VisitActivityTrackingDto();
        normalized.setVersion(TRACKING_VERSION);
        normalized.setClientSessionKey(tracking.getClientSessionKey());
        if (tracking.getDevice() != null) {
            VisitActivityTrackingDto.DeviceInfo device = new VisitActivityTrackingDto.DeviceInfo();
            device.setBrand(normalizeDevice(tracking.getDevice().getBrand(), 64));
            device.setModel(normalizeDevice(tracking.getDevice().getModel(), 128));
            device.setSystem(normalizeDevice(tracking.getDevice().getSystem(), 128));
            device.setPlatform(normalizeDevice(tracking.getDevice().getPlatform(), 32));
            normalized.setDevice(device);
        }
        return transactionService.open(portfolio, type, visitorId, visitorKey, sourceType, openKey, normalized);
    }

    /** 解析非负整数累计，禁止小数截断和整数溢出，然后由事务核验会话归属。 */
    public long accept(PortfolioEntity portfolio, String type, Long sessionId, VisitActivityUpdateRequest request) {
        Long visitorId = VisitorContextHolder.requireVisitorId();
        BigDecimal value = request == null ? null : request.getActiveDurationMs();
        if (sessionId == null || sessionId <= 0 || value == null) {
            throw new BusinessException(VisitActivityMessage.INVALID_DURATION);
        }
        long incoming;
        try {
            incoming = value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new BusinessException(VisitActivityMessage.INVALID_DURATION);
        }
        if (incoming < 0) {
            throw new BusinessException(VisitActivityMessage.INVALID_DURATION);
        }
        return transactionService.accept(portfolio.getId(), type, visitorId, sessionId, incoming);
    }

    /** 判断双键是否符合受限 ASCII 格式。 */
    private boolean validKey(String value) {
        return value != null && SESSION_KEY.matcher(value).matches();
    }

    /** 过滤控制字符，按 Unicode 码点限制长度，空文本转 NULL。 */
    private String normalizeDevice(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        raw.codePoints().filter(code -> !Character.isISOControl(code))
                .limit(maxLength).forEach(builder::appendCodePoint);
        String value = builder.toString().strip();
        return value.isEmpty() ? null : value;
    }
}
