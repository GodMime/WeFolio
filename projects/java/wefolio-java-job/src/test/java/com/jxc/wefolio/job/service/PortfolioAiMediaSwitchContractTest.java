package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 新能力冻结与两端开关不一致的诊断测试，旧文字任务仍按原契约执行。 */
class PortfolioAiMediaSwitchContractTest {
    /** 唯一允许的模型配置，媒体能力快照按场景追加。 */
    private ObjectNode request() {
        ObjectNode request = StrictAiJson.mapper().createObjectNode();
        request.putObject("modelConfig").put("provider", PortfolioAiProperties.TOKENHUB_PROVIDER)
                .put("model", PortfolioAiProperties.TOKENHUB_MODEL).put("baseUrl", PortfolioAiProperties.TOKENHUB_BASE_URL);
        request.putObject("facts");
        return request;
    }

    /** 单端开关差异有具体配置告警，且不会否决服务端已经接收的合法模型任务。 */
    @Test void mismatchedMediaSwitchWarnsWithoutInvalidatingFrozenContract() {
        Logger logger = (Logger) LoggerFactory.getLogger(PortfolioAiModelContract.class);
        var events = new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events);
        try {
            PortfolioAiProperties properties = new PortfolioAiProperties();
            ObjectNode media = request(); media.withObject("modelConfig").put("mediaEnabled", true);
            media.withObject("facts").putObject("mediaPlan");
            assertThatCode(() -> PortfolioAiModelContract.validate(media, properties)).doesNotThrowAnyException();
            AiGenerationTaskEntity task = new AiGenerationTaskEntity(); task.setId(7L); task.setRequestedByUniqueCode("WFREQUEST1");
            PortfolioAiModelContract.warnMediaSwitchMismatch(task, media, properties);
            properties.setMediaEnabled(true);
            ObjectNode text = request(); text.withObject("modelConfig").put("mediaEnabled", false);
            task.setId(8L); PortfolioAiModelContract.warnMediaSwitchMismatch(task, text, properties);
            // 上个部署的文字任务没有能力快照，不能把历史合法任务当成部署错误。
            task.setId(9L); PortfolioAiModelContract.warnMediaSwitchMismatch(task, request(), properties);
            assertThat(events.list).hasSize(2);
            assertThat(events.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("WEFOLIO_AI_MEDIA_ENABLED", "requestedByUniqueCode=WFREQUEST1").doesNotContain("apiKey", "request_json"));
        } finally { logger.detachAppender(events); events.stop(); }
    }
}
