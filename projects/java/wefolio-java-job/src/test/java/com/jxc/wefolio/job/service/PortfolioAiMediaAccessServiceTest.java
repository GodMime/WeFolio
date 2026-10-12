package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.exception.CosServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 当前权限SQL与COS HEAD决定可读性，冻结快照和缓存均不能授予访问。 */
class PortfolioAiMediaAccessServiceTest {
    /** 本地内存当前业务资料。 */ private JdbcTemplate jdbc;
    /** COS只读替身，禁止任何原媒体下载。 */ private VideoFrameStorageService storage;
    /** 被测授权服务。 */ private PortfolioAiMediaAccessService service;
    /** 初始化当前账号、作品及团队权限，不连接生产数据库。 */
    @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:ai_access_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE wf_user(id BIGINT PRIMARY KEY,unique_code VARCHAR(16),status VARCHAR(32),deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_team(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_team_member(team_id BIGINT,user_id BIGINT,role VARCHAR(32),join_status VARCHAR(32),allow_works INT,deleted BIGINT)");
        jdbc.execute("CREATE TABLE wf_work(id BIGINT PRIMARY KEY,user_id BIGINT,media_type VARCHAR(32),media_object_key VARCHAR(512),media_sha256 VARCHAR(64),duration_ms INT,video_frames_json VARCHAR(16000),status VARCHAR(32),audit_status VARCHAR(32),deleted BIGINT)");
        jdbc.update("INSERT INTO wf_user VALUES(2,'OWNER','ACTIVE',0),(3,'MEMBER','ACTIVE',0)");
        jdbc.update("INSERT INTO wf_work VALUES(9,2,'VIDEO','OWNER/work/video/test.mp4','sha',10000,NULL,'ACTIVE','PASSED',0)");
        storage = mock(VideoFrameStorageService.class); service = new PortfolioAiMediaAccessService(jdbc, storage);
        var head = new ObjectMetadata(); head.setContentLength(1000); head.setHeader("ETag", "ETAG"); head.setContentType("video/mp4");
        when(storage.head("OWNER/work/video/test.mp4")).thenReturn(head);
        when(storage.signedGet(anyString(), any())).thenReturn("https://private.example/video?signature=fake");
    }
    /** 当前有效个人任务。 */
    private AiGenerationTaskEntity task() { var task = new AiGenerationTaskEntity(); task.setOwnerType("USER"); task.setOwnerId(2L); task.setRequestedBy(2L); return task; }
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
}
