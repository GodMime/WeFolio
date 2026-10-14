package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.PortfolioAiMediaBudget;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import com.jxc.wefolio.job.model.PortfolioAiMediaSummaryIdentity;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiVideoAudioContext;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实预算事务与真实 TokenHub 请求估价验证五十来源容量和首次付费前总预算准入。 */
class PortfolioAiMediaCapacityIntegrationTest {
    /** 默认预算接受五十个未缓存图片，或五十个已有摘要的视频/最大帧来源，缺少 usage 不退款。 */
    @ParameterizedTest
    @CsvSource({"IMAGE,false", "VIDEO,true", "FRAMES,true"})
    void fiftySourcesAndFinalGenerationFitDefaultLimitsWithMissingUsage(String route, boolean cached) {
        Fixture fixture = fixture(route, false);
        String summary = maximumSummary("汉").toString();
        if (cached) {
            for (var entry : fixture.request().path("facts").path("mediaPlan").path("entries")) {
                var identity = new PortfolioAiMediaSummaryIdentity(PortfolioAiMediaExecutionService.cacheKey(entry, fixture.request()),
                        entry.path("sourceVersion").asText(), "glm-5.3-flash", PortfolioAiMediaSummaryPrompt.version(), route,
                        entry.path("manifestHash").asText(), entry.path("sourceObjectKey").asText(), "etag", 1000);
                fixture.repository().cache(entry.path("workId").asLong(), identity, summary, fixture.task().getCreatedAt());
            }
        }
        doReturn(new PortfolioAiModelClient.Completion(summary, null, null)).when(fixture.client()).summarize(any(), any(), anyList(), any(), any());
        assertThat(fixture.service().generate(fixture.task(), fixture.request(), () -> {})).isEqualTo("final");
        verify(fixture.client(), times(cached ? 0 : 50)).summarize(any(), any(), anyList(), any(), any());
        verify(fixture.client()).generateWithUsage(any(), any(), any());
        PortfolioAiMediaBudget budget = budget(fixture);
        assertThat(budget.callCount()).isEqualTo(cached ? 1 : 51);
        assertThat(budget.unknownCount()).isEqualTo(budget.callCount());
        assertThat(budget.total()).isLessThanOrEqualTo(300);
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM wf_work WHERE ai_media_summary_json IS NOT NULL", Integer.class)).isEqualTo(50);
        System.out.printf("真实媒体容量 route=%s cached=%s calls=%d reservedCents=%d%n", route, cached, budget.callCount(), budget.total());
    }

    /** 最大合法摘要含双重转义，五十个未缓存最贵来源超过默认总预算时须零模型调用拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"VIDEO", "FRAMES"})
    void expensiveFiftySourceRequestIsRejectedBeforeAnyPaidModelCall(String route) {
        Fixture fixture = fixture(route, true);
        var maximum = fixture.request().deepCopy();
        var chinese = fixture.request().deepCopy();
        for (var source : maximum.path("facts").path("sources")) { ((ObjectNode) source).set("mediaSummary", maximumSummary(String.valueOf((char) 1))); }
        for (var source : chinese.path("facts").path("sources")) { ((ObjectNode) source).set("mediaSummary", maximumSummary("汉")); }
        int maximumBytes = fixture.client().textRequestBytes(fixture.task(), maximum);
        int chineseBytes = fixture.client().textRequestBytes(fixture.task(), chinese);
        long maximumRequired = 250 + PortfolioAiMediaRepository.costCents(maximumBytes, 8192);
        long chineseRequired = 250 + PortfolioAiMediaRepository.costCents(chineseBytes, 8192);
        assertThat(maximumRequired).isGreaterThan(300);
        assertThatThrownBy(() -> fixture.service().generate(fixture.task(), fixture.request(), () -> {}))
                .isInstanceOfSatisfying(PortfolioAiCallException.class, failure -> assertThat(failure.getCode()).isEqualTo("MEDIA_BUDGET_EXHAUSTED"));
        verify(fixture.client(), never()).summarize(any(), any(), anyList(), any(), any());
        verify(fixture.client(), never()).generateWithUsage(any(), any(), any());
        assertThat(budget(fixture).callCount()).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM wf_work WHERE ai_media_summary_json IS NOT NULL", Integer.class)).isZero();
        System.out.printf("真实媒体准入 route=%s chineseBytes=%d chineseRequiredCents=%d maximumBytes=%d maximumRequiredCents=%d paidCalls=0%n",
                route, chineseBytes, chineseRequired, maximumBytes, maximumRequired);
    }

    /** 读回真实持久账本，不依赖模型替身断言费用更新。 */
    private PortfolioAiMediaBudget budget(Fixture fixture) {
        return PortfolioAiMediaBudget.parse(fixture.jdbc().queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=1", String.class));
    }

    /** 按真实摘要协议构造最大字符和条目数，不修改文字规范。 */
    private ObjectNode maximumSummary(String character) {
        ObjectNode result = StrictAiJson.mapper().createObjectNode().put("summary", character.repeat(600));
        var observations = result.putArray("observations"); var limitations = result.putArray("limitations");
        for (int item = 0; item < 8; item++) { observations.add(character.repeat(120)); }
        for (int item = 0; item < 4; item++) { limitations.add(character.repeat(120)); }
        return PortfolioAiMediaExecutionService.validateSummary(result.toString());
    }

    /** 所有数据库连接使用 UTC，会话时区不能放宽旧执行权限；HTTP 请求构造和字节估价不替换。 */
    private Fixture fixture(String route, boolean largeConfig) {
        var data = new DriverManagerDataSource("jdbc:h2:mem:ai_capacity_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'", "sa", "");
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.jxc.wefolio.job.repo.PortfolioAiSqlClockTestSupport.utcTimestamp'");
        jdbc.execute("CREATE TABLE wf_user(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_ai_generation_task(id BIGINT PRIMARY KEY,requested_by BIGINT,status VARCHAR(32),version INT,deleted BIGINT,created_at TIMESTAMP,attempt_count INT,media_budget_json CLOB,media_budget_unknown_count INT DEFAULT 0,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE wf_work(id BIGINT PRIMARY KEY,media_object_key VARCHAR(512),media_sha256 VARCHAR(64),video_frames_json CLOB,video_audio_text CLOB,status VARCHAR(32),audit_status VARCHAR(32),deleted BIGINT,updated_at TIMESTAMP,ai_media_summary_json CLOB,ai_media_summary_expires_at TIMESTAMP)");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO wf_user VALUES(2,'ACTIVE',0)");
        jdbc.update("INSERT INTO wf_ai_generation_task(id,requested_by,status,version,deleted,created_at,attempt_count) VALUES(1,2,'RUNNING',1,0,?,1)", now);
        var task = new AiGenerationTaskEntity(); task.setId(1L); task.setRequestedBy(2L); task.setOwnerType("USER"); task.setOwnerId(2L); task.setVersion(1); task.setAttemptCount(1); task.setCreatedAt(now);
        ObjectNode request = StrictAiJson.parseObject("""
                {"contractVersion":"ai-portfolio-v1","promptVersion":"portfolio-ai-2026-10-10.2",
                 "input":{"ownerType":"USER","mode":"MODIFY_PAGE","targetMenuKey":"__root__","prompt":"调整作品介绍"},
                 "facts":{"safePrompt":"调整作品介绍","baseConfig":{"components":[]},"sources":[],"bindings":[]},
                 "modelConfig":{"provider":"TOKENHUB","model":"glm-5.3-flash","baseUrl":"https://tokenhub.tencentmaas.com/v1","maxTokens":8192}}
                """, 10000, 16);
        ObjectNode facts = request.withObject("facts");
        if (largeConfig) {
            for (int item = 0; item < 40; item++) {
                ObjectNode component = facts.withObject("baseConfig").withArray("components").addObject()
                        .put("componentKey", "text-" + item).put("componentType", "STRUCTURED_TEXT_SECTION").put("enabled", true);
                component.putObject("config").putArray("blocks").addObject().put("key", "block-main")
                        .put("type", "PARAGRAPH").put("content", "汉".repeat(2000));
            }
            assertThat(facts.path("baseConfig").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(256 * 1024);
        }
        var sources = facts.withArray("sources");
        var entries = facts.putObject("mediaPlan").put("version", "portfolio-media-v1").putArray("entries");
        for (int number = 1; number <= 50; number++) {
            String key = "OWNER/work/" + ("IMAGE".equals(route) ? "image/" : "video/") + number;
            ObjectNode source = sources.addObject().put("sourceNo", number).put("sourceId", Integer.toString(number)).put("kind", "WORK")
                    .put("mediaType", "IMAGE".equals(route) ? "IMAGE" : "VIDEO").put("canBind", true).put("title", "作品".repeat(15))
                    .put("mediaUrl", "https://cdn.example.test/" + key).put("coverUrl", "https://cdn.example.test/cover/" + number);
            var tags = source.putArray("tags");
            for (int tag = 0; tag < 10; tag++) { tags.add("标签".repeat(4) + String.format(java.util.Locale.ROOT, "%02d", tag)); }
            ObjectNode entry = entries.addObject().put("sourceNo", number).put("workId", number).put("userId", 2)
                    .put("sourceObjectKey", key).put("sourceEtag", "etag").put("sourceBytes", 1000)
                    .put("sourceVersion", PortfolioAiMediaAccessService.fingerprint(key, "etag", 1000, "sha"))
                    .put("route", route).put("manifestHash", "").put("durationMs", 15000);
            String manifestJson = null;
            if ("FRAMES".equals(route)) {
                ObjectNode manifest = StrictAiJson.mapper().createObjectNode().put("schemaVersion", "video-frames-v1")
                        .put("sourceVersion", entry.path("sourceVersion").asText()).put("frameCount", 24);
                var frames = manifest.putArray("frames"); var frozenFrames = entry.putArray("frames");
                for (int frame = 0; frame < 24; frame++) {
                    ObjectNode item = frames.addObject().put("objectKey", "OWNER/others/video-frames/" + number + "/source/policy/task/" + frame + ".jpg")
                            .put("timeMs", frame * 1000).put("bytes", 1000).put("width", 960).put("height", 540); frozenFrames.add(item.deepCopy());
                }
                manifestJson = manifest.toString(); entry.put("manifestHash", PortfolioAiMediaAccessService.hash(manifestJson));
            }
            jdbc.update("INSERT INTO wf_work VALUES(?,?,'sha',?,NULL,'ACTIVE','PASSED',0,?,NULL,NULL)", number, key, manifestJson, now);
        }
        var properties = new PortfolioAiProperties(); properties.setApiKey("test-fake-key");
        var proxy = new ProxyFactory(new PortfolioAiMediaRepository(jdbc, properties));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data), new AnnotationTransactionAttributeSource()));
        var repository = (PortfolioAiMediaRepository) proxy.getProxy();
        var access = mock(PortfolioAiMediaAccessService.class);
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/image", null)));
        var client = spy(new TokenHubPortfolioAiClient(properties));
        // 回归失效时也只使用本地模型返回替身，绝不因为准入缺失而意外访问真实供应商。
        doReturn(new PortfolioAiModelClient.Completion(maximumSummary("汉").toString(), null, null))
                .when(client).summarize(any(), any(), anyList(), any(), any());
        doReturn(new PortfolioAiModelClient.Completion("final", null, null)).when(client).generateWithUsage(any(), any(), any());
        var service = new PortfolioAiMediaExecutionService(repository, access, client, properties);
        return new Fixture(jdbc, task, request, repository, client, service, properties);
    }

    /** 单场隔离数据库、真实估价客户端与模型传输替身。 */
    private record Fixture(JdbcTemplate jdbc, AiGenerationTaskEntity task, ObjectNode request,
            PortfolioAiMediaRepository repository, TokenHubPortfolioAiClient client, PortfolioAiMediaExecutionService service, PortfolioAiProperties properties) { }

    /** 五十来源的极端转义与 emoji 在真实请求构造、真实账本准入和缓存写入中完整走通。 */
    @Test void fiftyAudioSourcesUseRealSerializedBytesInBothStages() {
        Fixture fixture = fixture("FRAMES", false);
        fixture.properties().setMediaTaskBudgetCents(1000);
        fixture.properties().setMediaDailyBudgetCents(2000);
        ObjectNode request = fixture.request();
        request.withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.PLAN_VERSION);
        List<PortfolioAiVideoAudioContext.Source> audio = new ArrayList<>();
        String original = "😀\u0001\"\\".repeat(6000);
        for (var entry : request.path("facts").path("mediaPlan").path("entries")) {
            audio.add(new PortfolioAiVideoAudioContext.Source((ObjectNode) entry, original));
            fixture.jdbc().update("UPDATE wf_work SET video_audio_text=? WHERE id=?", original, entry.path("workId").asLong());
        }
        PortfolioAiVideoAudioContext.freeze(audio);
        var sent = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        assertThat(fixture.service().generate(fixture.task(), request, () -> {})).isEqualTo("final");
        verify(fixture.client()).generateWithUsage(any(), sent.capture(), any());
        int actualBytes = fixture.client().payload(fixture.task(), sent.getValue()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(fixture.client().textRequestBytes(fixture.task(), sent.getValue())).isEqualTo(actualBytes);
        var recorded = budget(fixture);
        long textCost = PortfolioAiMediaRepository.costCents(actualBytes, 8192);
        assertThat(recorded.find(1, 51).reserved()).isEqualTo(textCost);
        // 五十件均分后，每次摘要仍按帧与文字总输入向上取整。
        assertThat(recorded.find(1, 1).reserved()).isEqualTo(5);
        assertThat(recorded.callCount()).isEqualTo(51);
        assertThat(sent.getValue().path("facts").path("sources").findValuesAsText(PortfolioAiVideoAudioContext.TEXT)).hasSize(50);
    }

    /** 音频新增预算能让原本足够的额度拒绝整任务，且拒绝发生于第一次付费前。 */
    @Test void transcriptCostIsAdmittedBeforeFirstPaidCall() {
        Fixture fixture = fixture("FRAMES", false);
        fixture.properties().setMediaTaskBudgetCents(300);
        fixture.request().withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.PLAN_VERSION);
        List<PortfolioAiVideoAudioContext.Source> audio = new ArrayList<>();
        for (var entry : fixture.request().path("facts").path("mediaPlan").path("entries")) {
            audio.add(new PortfolioAiVideoAudioContext.Source((ObjectNode) entry, "\u0001".repeat(7000)));
        }
        PortfolioAiVideoAudioContext.freeze(audio);
        assertThatThrownBy(() -> fixture.service().generate(fixture.task(), fixture.request(), () -> {}))
                .isInstanceOfSatisfying(PortfolioAiCallException.class, failure -> assertThat(failure.getCode()).isEqualTo("MEDIA_BUDGET_EXHAUSTED"));
        verify(fixture.client(), never()).summarize(any(), any(), anyList(), any(), any());
        verify(fixture.client(), never()).generateWithUsage(any(), any(), any());
        assertThat(budget(fixture).callCount()).isZero();
    }

    /** 单件六千控制字符必须计入摘要请求，不能仅在最终生成预算中补记。 */
    @Test void fullPerWorkTranscriptIncreasesSummaryReservation() {
        Fixture fixture = fixture("FRAMES", false);
        var plan = fixture.request().withObject("facts").withObject("mediaPlan");
        plan.put("version", PortfolioAiMediaContract.PLAN_VERSION);
        ObjectNode entry = (ObjectNode) plan.withArray("entries").get(0);
        plan.putArray("entries").add(entry);
        var facts = fixture.request().withObject("facts");
        var source = facts.path("sources").get(0);
        facts.putArray("sources").add(source);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "\u0001".repeat(7000))));
        assertThat(fixture.service().generate(fixture.task(), fixture.request(), () -> {})).isEqualTo("final");
        assertThat(budget(fixture).find(1, 1).reserved()).isEqualTo(8);
    }
}
