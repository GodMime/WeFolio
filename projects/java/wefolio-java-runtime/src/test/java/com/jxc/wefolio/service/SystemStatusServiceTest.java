package com.jxc.wefolio.service;

import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 字体降级不改变旧探活状态、时间格式与版本字段。 */
class SystemStatusServiceTest {
    /** 明确故障仍保留 UP，诊断新增字段不能改变旧客户端可见契约。 */
    @Test void fontFailurePreservesExistingHealthContract() {
        var sources = new PortfolioFontSources(new PortfolioFontProperties());
        sources.initialize(); sources.fail(PortfolioFontSources.Failure.TOOL_UNAVAILABLE);
        var service = new SystemStatusService(sources);
        Map<String, Object> health = service.health();
        assertThat(health).containsEntry("status", "UP");
        assertThat(LocalDateTime.parse((String) health.get("timestamp"))).isNotNull();
        assertThat(health.get("portfolioFonts")).isEqualTo(sources.diagnostics());
        assertThat(service.version()).containsKeys("version", "buildTime");
    }
}
