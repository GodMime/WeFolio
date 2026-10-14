package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiStoredVideoSummary;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import com.jxc.wefolio.service.portfolioai.PortfolioAiFrameSelection;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.exception.CosServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 当前权限SQL与COS HEAD决定可读性，冻结快照和缓存均不能授予访问。 */
class PortfolioAiMediaAccessServiceTest {
    /** 本地内存当前业务资料。 */ private JdbcTemplate jdbc;
    /** COS只读替身，禁止任何原媒体下载。 */ private VideoFrameStorageService storage;
    /** 单次模型调用期限，不读取真实环境配置。 */ private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 被测授权服务。 */ private PortfolioAiMediaAccessService service;
    /** 初始化当前账号、作品及团队权限，不连接生产数据库。 */
    @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:ai_access_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE wf_user(id BIGINT PRIMARY KEY,unique_code VARCHAR(16),status VARCHAR(32),deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_team(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_team_member(team_id BIGINT,user_id BIGINT,role VARCHAR(32),join_status VARCHAR(32),allow_works INT,deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_work(id BIGINT PRIMARY KEY,user_id BIGINT,media_type VARCHAR(32),media_object_key VARCHAR(512),media_sha256 VARCHAR(64),duration_ms INT,video_frames_json VARCHAR(16000),status VARCHAR(32),audit_status VARCHAR(32),deleted BIGINT,audit_round INT)");
        jdbc.update("INSERT INTO wf_user VALUES(2,'OWNER','ACTIVE',0),(3,'MEMBER','ACTIVE',0)");
        jdbc.update("INSERT INTO wf_work VALUES(9,2,'VIDEO','OWNER/work/video/test.mp4','sha',10000,NULL,'ACTIVE','PASSED',0,1)");
        storage = mock(VideoFrameStorageService.class); service = new PortfolioAiMediaAccessService(jdbc, storage, properties);
        var head = new ObjectMetadata(); head.setContentLength(1000); head.setHeader("ETag", "ETAG"); head.setContentType("video/mp4");
        when(storage.head("OWNER/work/video/test.mp4")).thenReturn(head);
        when(storage.signedGet(anyString(), any())).thenReturn("https://private.example/video?signature=fake");
    }
    /** 当前有效个人任务。 */
    private AiGenerationTaskEntity task() {
        var task = new AiGenerationTaskEntity(); task.setId(11L); task.setAttemptCount(1); task.setVersion(1);
        task.setOwnerType("USER"); task.setOwnerId(2L); task.setRequestedBy(2L);
        task.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))); return task;
    }
    /** HEAD匹配的冻结源身份。 */
    private ObjectNode entry() {
        return StrictAiJson.mapper().createObjectNode().put("workId", 9).put("userId", 2).put("sourceObjectKey", "OWNER/work/video/test.mp4")
                .put("sourceEtag", "ETAG").put("sourceBytes", 1000)
                .put("sourceVersion", PortfolioAiMediaAccessService.fingerprint("OWNER/work/video/test.mp4", "ETAG", 1000, "sha"))
                .put("route", "VIDEO");
    }
    /** 私有链接只从当前授权本桶对象键签出。 */
    @Test void privateVideoIsSignedAfterCurrentAuthorizationAndHeadMatch() {
        var signed = service.signed(task(), entry()); assertThat(signed).hasSize(1);
        assertThat(signed.getFirst().type()).isEqualTo("video_url");
        verify(storage).signedGet(eq("OWNER/work/video/test.mp4"), any());
    }

    /** 默认三分钟模型调用保留一分钟签名前置与读取余量。 */
    @Test void defaultThreeMinuteCallHasOneMinuteSignedLinkMargin() {
        assertThat(service.signed(task(), entry())).hasSize(1);
        verify(storage).signedGet(eq("OWNER/work/video/test.mp4"), eq(Duration.ofSeconds(240)));
    }

    /** 覆写四分钟调用期限后，持久帧链接同样增加一分钟读取余量。 */
    @Test void overriddenFourMinuteCallExtendsEveryFrameLinkToFiveMinutes() {
        properties.setCallTimeoutSeconds(240);
        ObjectNode entry = frameEntry(8);
        assertThat(service.signedAfterVerification(task(), entry)).hasSize(8);
        verify(storage, times(8)).signedGet(anyString(), eq(Duration.ofSeconds(300)));
    }

    /** 旧一分钟短调用仍保持既有三分钟链接有效期。 */
    @Test void shortCallRetainsExistingThreeMinuteSignedLinkMinimum() {
        properties.setCallTimeoutSeconds(60);
        assertThat(service.signed(task(), entry())).hasSize(1);
        verify(storage).signedGet(eq("OWNER/work/video/test.mp4"), eq(Duration.ofSeconds(180)));
    }

    /** 同次执行阶段仍实时校验授权和 digest，签名时不会重复读取 COS。 */
    @Test void repeatedDatabaseChecksAndSigningDoNotRepeatObjectHeads() {
        var task = task(); var entry = entry();
        service.verify(task, entry);
        service.verifyCurrent(task, entry);
        assertThat(service.signedAfterVerification(task, entry)).hasSize(1);
        verify(storage, times(1)).head("OWNER/work/video/test.mp4");
        jdbc.update("UPDATE wf_work SET media_sha256='replacement' WHERE id=9");
        assertThatThrownBy(() -> service.verifyCurrent(task, entry)).hasMessageContaining("失效");
        verify(storage, times(1)).head("OWNER/work/video/test.mp4");
    }
    /** 当前撤权或被删除/改审后，缓存命中也无法通过该门禁。 */
    @Test void inactiveOrUnpassedWorkCannotBeRead() {
        jdbc.update("UPDATE wf_work SET audit_status='FAILED' WHERE id=9");
        assertThatThrownBy(() -> service.signed(task(), entry())).hasMessageContaining("失效");
        verify(storage, never()).signedGet(anyString(), any());
    }
    /** HEAD对象替换即使同key也无法读取旧冻结请求。 */
    @Test void replacedCosObjectRejectsTheFrozenSourceIdentity() {
        var changed = new ObjectMetadata(); changed.setContentLength(1001); changed.setHeader("ETag", "ETAG");
        when(storage.head("OWNER/work/video/test.mp4")).thenReturn(changed);
        assertThatThrownBy(() -> service.signed(task(), entry())).hasMessageContaining("失效");
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** COS 短暂服务故障可重试，真实对象缺失则保持来源失效终态。 */
    @Test void transientCosFailureIsRetryableWhileMissingSourceIsTerminal() {
        var temporary = new CosServiceException("temporary"); temporary.setStatusCode(503);
        when(storage.head("OWNER/work/video/test.mp4")).thenThrow(temporary);
        assertThatThrownBy(() -> service.verify(task(), entry())).isInstanceOfSatisfying(PortfolioAiCallException.class,
                exception -> assertThat(exception.isRetryable()).isTrue());
        var missing = new CosServiceException("missing"); missing.setStatusCode(404);
        doThrow(missing).when(storage).head("OWNER/work/video/test.mp4");
        assertThatThrownBy(() -> service.verify(task(), entry())).isInstanceOfSatisfying(PortfolioAiCallException.class,
                exception -> assertThat(exception.isRetryable()).isFalse());
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 持久帧也仅首次完整读取对象，阶段检查与签名复用该次验证并保留实时数据库守卫。 */
    @Test void frameVerificationDoesNotRepeatHeadsAndTransientFrameFailureIsRetryable() {
        ObjectNode entry = frameEntry(8); var task = task();
        service.verify(task, entry); service.verifyCurrent(task, entry);
        assertThat(service.signedAfterVerification(task, entry)).hasSize(8);
        verify(storage, times(1)).head("OWNER/work/video/test.mp4");
        String first = entry.path("frames").get(0).path("objectKey").asText();
        verify(storage, times(1)).head(first);
        var temporary = new CosServiceException("temporary"); temporary.setStatusCode(503);
        doThrow(temporary).when(storage).head(first);
        assertThatThrownBy(() -> service.verify(task, entry)).isInstanceOfSatisfying(PortfolioAiCallException.class,
                exception -> assertThat(exception.isRetryable()).isTrue());
        var missing = new CosServiceException("missing"); missing.setStatusCode(404);
        doThrow(missing).when(storage).head(first);
        assertThatThrownBy(() -> service.verify(task, entry)).isInstanceOfSatisfying(PortfolioAiCallException.class,
                exception -> { assertThat(exception.isRetryable()).isFalse(); assertThat(exception.getCode()).isEqualTo("MEDIA_NOT_PREPARED"); });
    }
    /** 团队管理身份和素材作者allowWorks分别必要，撤销任一都拒绝。 */
    @Test void teamManagerCannotReadMembersWorkWithoutCurrentAllowWorks() {
        jdbc.update("INSERT INTO wf_team VALUES(7,'ACTIVE',0)");
        jdbc.update("INSERT INTO wf_team_member VALUES(7,3,'MANAGER','JOINED',0,0),(7,2,'MEMBER','JOINED',1,0)");
        var task = task(); task.setOwnerType("TEAM"); task.setOwnerId(7L); task.setRequestedBy(3L);
        service.verify(task, entry());
        jdbc.update("UPDATE wf_team_member SET allow_works=0 WHERE team_id=7 AND user_id=2");
        assertThatThrownBy(() -> service.signed(task, entry())).hasMessageContaining("失效");
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 生产者固定上限的完整清单应被 Job 消费，超合同数量拒绝签名。 */
    @Test void frameCountBoundariesFollowTheSharedProducerContract() {
        jdbc.update("UPDATE wf_work SET duration_ms=600000 WHERE id=9");
        for (int count : new int[]{PortfolioAiMediaContract.FRAME_MIN_COUNT, PortfolioAiMediaContract.FRAME_MAX_COUNT}) {
            ObjectNode entry = frameEntry(count);
            assertThat(service.signed(task(), entry)).hasSize(count);
        }
        clearInvocations(storage);
        for (int count : new int[]{PortfolioAiMediaContract.FRAME_MIN_COUNT - 1, PortfolioAiMediaContract.FRAME_MAX_COUNT + 1}) {
            ObjectNode entry = frameEntry(count);
            assertThatThrownBy(() -> service.signed(task(), entry)).hasMessageContaining("持久帧");
        }
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 新任务完整复核所有审核资产，只签名预算内的代表帧，数据库完整清单不减少。 */
    @Test void resolutionSelectionSignsEightOfTwentyFourWithoutDiscardingAuditFrames() {
        ObjectNode entry = selectedFrameEntry(24, 960, 540);
        // 模拟 Runtime 写入任务 JSON 后 Job 重新解析，不能依赖内存数字节点类型一致。
        entry = StrictAiJson.parseObject(entry.toString(), 128 * 1024, 12);
        String before = jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=9", String.class);

        var signed = service.signed(task(), entry);

        assertThat(signed).hasSize(8);
        assertThat(signed.stream().map(PortfolioAiModelClient.MediaInput::timeMs).toList())
                .containsExactlyElementsOf(StreamSupport.stream(
                        entry.path(PortfolioAiFrameSelection.FIELD).path("frames").spliterator(), false)
                        .map(frame -> frame.path("timeMs").asLong()).toList());
        assertThat(entry.path("frames")).hasSize(24);
        verify(storage, times(25)).head(anyString());
        verify(storage, times(8)).signedGet(anyString(), any());
        assertThat(jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=9", String.class)).isEqualTo(before);
    }

    /** 实际低清候选允许更密时间覆盖，横竖画面按同一长边限量。 */
    @Test void actualFrameDimensionsControlSigningCountForLandscapeAndPortrait() {
        for (int[] example : new int[][]{{512, 288, 24}, {768, 432, 12}, {540, 960, 8}}) {
            ObjectNode entry = selectedFrameEntry(24, example[0], example[1]);
            assertThat(service.signed(task(), entry)).hasSize(example[2]);
        }
    }

    /** 精选身份和尺寸必须从同一当前清单重算，不能使用看似合法的其它子集替代。 */
    @Test void changedSelectionIdentityDimensionsOrBudgetCannotBeSigned() {
        List<Consumer<ObjectNode>> changes = List.of(
                selection -> ((ObjectNode) selection.path("frames").get(0)).put("objectKey", "OTHER/frames/1.jpg"),
                selection -> ((ObjectNode) selection.path("frames").get(0)).put("timeMs", 99999),
                selection -> ((ObjectNode) selection.path("frames").get(0)).put("width", 512),
                selection -> ((ObjectNode) selection.path("frames").get(0)).put("height", 288),
                selection -> ((ObjectNode) selection.path("frames").get(0)).put("width", 960.0),
                selection -> selection.put("estimatedInputTokens", 1),
                selection -> selection.withArray("frames").remove(0));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode entry = selectedFrameEntry(24, 960, 540);
            change.accept((ObjectNode) entry.path(PortfolioAiFrameSelection.FIELD));
            clearInvocations(storage);
            assertThatThrownBy(() -> service.signed(task(), entry)).hasMessage("媒体选帧计划不合法")
                    .isInstanceOfSatisfying(PortfolioAiCallException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo("MEDIA_INVALID"));
            verify(storage, never()).signedGet(anyString(), any());
        }
    }

    /** 旧任务可以没有精选字段，但显式空字段和把精选放在直传路线均拒绝。 */
    @Test void explicitNullSelectionOrSelectionOnDirectVideoIsRejected() {
        ObjectNode entry = selectedFrameEntry(24, 960, 540);
        entry.putNull(PortfolioAiFrameSelection.FIELD);
        assertThatThrownBy(() -> service.signed(task(), entry)).hasMessage("媒体选帧计划不合法");
        ObjectNode video = entry();
        video.set(PortfolioAiFrameSelection.FIELD, StrictAiJson.mapper().createObjectNode());
        assertThatThrownBy(() -> service.signed(task(), video)).hasMessage("媒体选帧计划不合法");
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 真实执行链在缓存、签名和付费前统一区分非法选择、未知策略与缺失资产。 */
    @Test void realExecutionRejectsInvalidSelectionsWithProductionErrorBeforeAnyPaidBoundary() {
        ObjectNode forged = selectedFrameEntry(24, 960, 540);
        ((ObjectNode) forged.path(PortfolioAiFrameSelection.FIELD)).put("estimatedInputTokens", 1);
        assertExecutionRejected(forged, "MEDIA_INVALID", "媒体选帧计划不合法");

        ObjectNode unsupported = selectedFrameEntry(24, 960, 540);
        ((ObjectNode) unsupported.path(PortfolioAiFrameSelection.FIELD)).put("policyVersion", "resolution-budget-v999");
        assertExecutionRejected(unsupported, "CONTRACT_UNAVAILABLE", "媒体选帧策略版本暂不支持");

        ObjectNode malformed = selectedFrameEntry(24, 960, 540);
        malformed.putNull(PortfolioAiFrameSelection.FIELD);
        assertExecutionRejected(malformed, "MEDIA_INVALID", "媒体选帧计划不合法");

        ObjectNode missing = selectedFrameEntry(24, 960, 540);
        var absent = new CosServiceException("missing"); absent.setStatusCode(404);
        doThrow(absent).when(storage).head(missing.path("frames").get(0).path("objectKey").asText());
        assertExecutionRejected(missing, "MEDIA_NOT_PREPARED", "所选视频持久帧尚未准备完成");
    }

    /** 采用真实授权及 H2 当前清单，替身仅包围缓存账本和付费模型边界。 */
    private void assertExecutionRejected(ObjectNode entry, String code, String message) {
        var repository = mock(PortfolioAiMediaRepository.class);
        var client = mock(PortfolioAiModelClient.class);
        var execution = new PortfolioAiMediaExecutionService(repository, service, client, properties);
        assertThatThrownBy(() -> execution.generate(task(), request(entry), () -> {}))
                .hasMessage(message).isInstanceOfSatisfying(PortfolioAiCallException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.isRetryable()).isFalse();
                });
        verifyNoInteractions(repository, client);
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 冻结 v1 落库往返后沿真实链路签八帧并完成两个阶段，旧策略不是只通过孤立工具测试。 */
    @Test void persistedV1SelectionCanCompleteRealExecution() {
        ObjectNode entry = selectedFrameEntry(24, 960, 540);
        ObjectNode frozen = StrictAiJson.parseObject(request(entry).toString(), 128 * 1024, 16);
        var repository = mock(PortfolioAiMediaRepository.class);
        var client = mock(PortfolioAiModelClient.class);
        var execution = new PortfolioAiMediaExecutionService(repository, service, client, properties);
        when(client.summarize(any(), any(), anyList(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion(
                "{\"summary\":\"舞台\",\"observations\":[],\"limitations\":[\"采样帧有盲区\"]}", 100L, 10L));
        when(client.textRequestBytes(any(), any())).thenReturn(1000);
        when(client.generateWithUsage(any(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 100L, 10L));
        assertThat(execution.generate(task(), frozen, () -> {})).isEqualTo("final");
        verify(client).summarize(any(), any(), argThat(inputs -> inputs.size() == 8
                && inputs.getFirst().timeMs() == 1000L && inputs.getLast().timeMs() == 22000L), any(), any());
        verify(storage, times(8)).signedGet(anyString(), any());
        verify(repository).cache(eq(9L), any(), anyString(), any());
    }

    /** 选帧降级日志可关联任务与作品，并区分历史缺失和已存在但损坏的特征。 */
    @Test void fallbackLogsCoverageContextWithoutRepeatedStageLogs() {
        ObjectNode entry = selectedFrameEntry(24, 960, 540);
        ObjectNode manifest = StrictAiJson.parseObject(jdbc.queryForObject(
                "SELECT video_frames_json FROM wf_work WHERE id=9", String.class), 128 * 1024, 12);
        ((ObjectNode) manifest.path("frames").get(0)).putObject("visualFeatures").put("version", "unknown");
        ((ObjectNode) manifest.path("frames").get(1)).putObject("visualFeatures").put("version", "visual-features-v1")
                .put("dHash", "0000000000000000").put("luminance", 100).put("sharpness", 10);
        entry.put("manifestHash", PortfolioAiMediaAccessService.hash(manifest.toString()));
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=9", manifest.toString());
        Logger logger = (Logger) LoggerFactory.getLogger(PortfolioAiMediaAccessService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            service.verify(task(), entry);
            service.verifyCurrent(task(), entry);
            service.signedAfterVerification(task(), entry);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("taskId=11", "attempt=1", "workId=9",
                    "policyVersion=resolution-budget-v1", "totalFrames=24", "usableFeatures=1", "missingFeatures=22", "invalidFeatures=1")
                    .doesNotContain("signature", "https://", "OWNER/");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    /** 将真实冻结 entry 放入最小合法作品来源上下文。 */
    private ObjectNode request(ObjectNode entry) {
        ObjectNode request = StrictAiJson.parseObject("""
                {"modelConfig":{"model":"glm-5.3-flash","maxTokens":8192},
                 "facts":{"sources":[{"sourceNo":1,"sourceId":"9","kind":"WORK","mediaType":"VIDEO","canBind":true}],
                          "mediaPlan":{"version":"portfolio-media-v1","entries":[]}}}
                """, 10000, 16);
        request.withObject("facts").withObject("mediaPlan").withArray("entries").add(entry.deepCopy().put("sourceNo", 1));
        return request;
    }

    /** 由真实清单尺寸冻结新选择，未选帧仍在完整清单中参与源验证。 */
    private ObjectNode selectedFrameEntry(int count, int width, int height) {
        ObjectNode entry = frameEntry(count);
        ObjectNode manifest = StrictAiJson.parseObject(jdbc.queryForObject(
                "SELECT video_frames_json FROM wf_work WHERE id=9", String.class), 128 * 1024, 12);
        for (var frame : manifest.path("frames")) { ((ObjectNode) frame).put("width", width).put("height", height); }
        entry.put("manifestHash", PortfolioAiMediaAccessService.hash(manifest.toString()));
        entry.set(PortfolioAiFrameSelection.FIELD, PortfolioAiFrameSelection.select(manifest.path("frames")));
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=9", manifest.toString());
        return entry;
    }

    /** 构造当前清单及对应冻结列表，并使真实 HEAD 符合固定尺寸字节合同。 */
    private ObjectNode frameEntry(int count) {
        ObjectNode entry = entry().put("route", "FRAMES");
        ObjectNode manifest = StrictAiJson.mapper().createObjectNode().put("schemaVersion", PortfolioAiMediaContract.FRAME_SCHEMA)
                .put("sourceVersion", entry.path("sourceVersion").asText()).put("frameCount", count);
        var frames = manifest.putArray("frames"); var frozen = entry.putArray("frames");
        var imageHead = new ObjectMetadata(); imageHead.setContentLength(PortfolioAiMediaContract.FRAME_MAX_BYTES);
        imageHead.setContentType(PortfolioAiMediaContract.JPEG_CONTENT_TYPE);
        for (int i = 0; i < count; i++) {
            String key = "OWNER/others/video-frames/9/source/policy/task/" + i + ".jpg";
            frames.addObject().put("objectKey", key).put("timeMs", i * 1000).put("bytes", PortfolioAiMediaContract.FRAME_MAX_BYTES)
                    .put("width", PortfolioAiMediaContract.FRAME_MAX_EDGE).put("height", 540);
            frozen.addObject().put("objectKey", key).put("timeMs", i * 1000).put("bytes", PortfolioAiMediaContract.FRAME_MAX_BYTES);
            when(storage.head(key)).thenReturn(imageHead);
        }
        entry.put("manifestHash", PortfolioAiMediaAccessService.hash(manifest.toString()));
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=9", manifest.toString());
        return entry;
    }

    /** 冻结列表损坏不能把另一作品键混入签名，即使manifestHash仍正确。 */
    @Test void frozenFrameListMustMatchCurrentManifestBeforeSigning() {
        jdbc.update("UPDATE wf_work SET duration_ms=600000 WHERE id=9");
        ObjectNode entry = entry().put("route", "FRAMES");
        ObjectNode manifest = StrictAiJson.mapper().createObjectNode().put("schemaVersion", "video-frames-v1")
                .put("sourceVersion", entry.path("sourceVersion").asText()).put("frameCount", 8);
        var frames = manifest.putArray("frames"); var frozen = entry.putArray("frames");
        var imageHead = new ObjectMetadata(); imageHead.setContentLength(1000); imageHead.setContentType("image/jpeg");
        for (int i = 0; i < 8; i++) {
            String key = "OWNER/others/video-frames/9/source/policy/task/" + i + ".jpg";
            frames.addObject().put("objectKey", key).put("timeMs", i * 1000).put("bytes", 1000).put("width", 960).put("height", 540);
            frozen.addObject().put("objectKey", key).put("timeMs", i * 1000).put("bytes", 1000);
            when(storage.head(key)).thenReturn(imageHead);
        }
        entry.put("manifestHash", PortfolioAiMediaAccessService.hash(manifest.toString()));
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=9", manifest.toString());
        ((ObjectNode) frozen.get(0)).put("objectKey", "OTHER/others/video-frames/10/source/policy/task/0.jpg");
        assertThatThrownBy(() -> service.signed(task(), entry)).hasMessageContaining("持久帧");
        verify(storage, never()).signedGet(anyString(), any());
    }
    /** 原视频摘要可在持久帧缺失或损坏时使用，只检查源身份与实时权限。 */
    @Test void storedSummaryRequiresNoFramesOrSignedMedia() {
        ObjectNode entry = storedEntry();
        jdbc.update("UPDATE wf_work SET video_frames_json='broken',duration_ms=600000 WHERE id=9");
        service.verify(task(), entry);
        service.verifyCurrent(task(), entry);
        assertThat(service.signedAfterVerification(task(), entry)).isEmpty();
        verify(storage).head("OWNER/work/video/test.mp4");
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 旧摘要冻结后重新通过审核也不能重新授权同一在途任务。 */
    @Test void storedSummaryRejectsChangedAuditRoundAndSourceReplacement() {
        ObjectNode entry = storedEntry();
        jdbc.update("UPDATE wf_work SET audit_round=2 WHERE id=9");
        assertThatThrownBy(() -> service.verifyCurrent(task(), entry)).hasMessageContaining("失效");
        jdbc.update("UPDATE wf_work SET audit_round=1,media_sha256='changed' WHERE id=9");
        assertThatThrownBy(() -> service.verifyCurrent(task(), entry)).hasMessageContaining("失效");
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 摘要包装源身份不符时拒绝，不能用冻结文字代替权限或真实源校验。 */
    @Test void corruptFrozenStoredSummaryCannotGrantAccess() {
        ObjectNode entry = storedEntry();
        ((ObjectNode) entry.path(PortfolioAiStoredVideoSummary.FIELD)).put("sourceVersion", "b".repeat(64));
        assertThatThrownBy(() -> service.verify(task(), entry)).isInstanceOf(PortfolioAiCallException.class);
        verify(storage, never()).signedGet(anyString(), any());
    }

    /** 构造不带帧字段的 v3 持久摘要路线。 */
    private ObjectNode storedEntry() {
        ObjectNode entry = entry().put("route", PortfolioAiMediaContract.ROUTE_STORED_VIDEO_SUMMARY).put("auditRound", 1);
        ObjectNode content = StrictAiJson.mapper().createObjectNode().put("summary", "原视频婚礼画面");
        content.putArray("observations"); content.putArray("limitations");
        entry.set(PortfolioAiStoredVideoSummary.FIELD, PortfolioAiStoredVideoSummary.create(entry.path("sourceVersion").asText(),
                "a".repeat(64), "glm-5.3-flash", "direct-v1", Instant.parse("2020-01-01T00:00:00Z"), content));
        return entry;
    }

}
