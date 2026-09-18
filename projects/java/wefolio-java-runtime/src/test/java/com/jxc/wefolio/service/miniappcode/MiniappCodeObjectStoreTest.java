package com.jxc.wefolio.service.miniappcode;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.qcloud.cos.exception.CosServiceException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 头像元数据边界：拒绝任意 URL，默认头像零远端访问。 */
class MiniappCodeObjectStoreTest {
    /** 受信任配置。 */
    private MiniappCodeObjectStore store(CosService cos) {
        var config = new CosProperties(); config.setPublicBaseUrl("https://cdn.test");
        return new MiniappCodeObjectStore(cos, config);
    }
    /** 构建待检查的所属个人头像。 */
    private PortfolioMiniappCodeSnapshot snapshot(String url) {
        return new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.USER.getCode(), 1L, 2L, "r", "PF123", "WF1", "名", "", "标题", url);
    }
    /** 所属目录、HTTPS、精确域名和无处理参数必须同时满足。 */
    @Test void rejectsUntrustedAvatarUrlsWithoutRemoteAccess() {
        var cos = mock(CosService.class);
        for (String url : new String[]{"http://127.0.0.1/a", "https://cdn.test.evil/WF1/others/a.png", "https://cdn.test@evil/WF1/others/a.png", "https://cdn.test/WF2/others/a.png", "https://cdn.test/WF1/others/../a.png", "https://cdn.test/WF1/others/%2e%2e/a.png", "https://cdn.test/WF1/others/a.png?redirect=evil"}) {
            assertThatThrownBy(() -> store(cos).avatarResource(snapshot(url))).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(cos);
    }
    /** 个人默认与团队默认均返回本地资源标记，不访问 COS。 */
    @Test void defaultsHaveNoRemoteCalls() {
        var cos = mock(CosService.class);
        var personal = store(cos).avatarResource(snapshot(null));
        assertThat(personal.url()).isEmpty();
        assertThat(store(cos).avatarResource(snapshot("https://cdn2.we-folio.dingchenyong.top/system/wefolio-default-avatar-512.jpg"))).isEqualTo(personal);
        var team = new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.TEAM.getCode(), 1L, 2L, "r", "TPF123", "TM1", "团队", "", "标题", null);
        assertThat(store(cos).avatarResource(team).url()).isEmpty();
        assertThat(store(cos).avatarResource(team).version()).isNotEqualTo(personal.version());
        verifyNoInteractions(cos);
    }
    /** 所有支持格式都由固定首帧缩略 PNG URL 交给手机处理。 */
    @Test void fixedAvatarTransformationUsesHeadOnly() {
        for (String type : new String[]{"image/png", "image/jpeg", "image/gif", "image/webp"}) {
            var cos = mock(CosService.class);
            when(cos.headObjectVersion("WF1/others/a.webp")).thenReturn(new CosService.VersionedObjectHead(type, 100, "etag", 0));
            var result = store(cos).avatarResource(snapshot("https://cdn.test/WF1/others/a.webp"));
            assertThat(result.url()).startsWith("https://cdn.test/WF1/others/a.webp?imageMogr2/frame/1/thumbnail/512x512%3E/strip/format/png&v=");
            verify(cos).headObjectVersion("WF1/others/a.webp");
            verifyNoMoreInteractions(cos);
        }
    }
    /** 既有资料保存接受 JPEG 别名，历史上传保留的合法 MIME 参数不能阻断头像资源。 */
    @Test void acceptsLegacyJpegMimeAliasAndParametersWithoutDownloadingAvatar() {
        for (String type : new String[]{"image/jpg", " Image/JPEG ", "image/jpeg; charset=UTF-8", "image/jpg; charset=UTF-8"}) {
            var cos = mock(CosService.class);
            when(cos.headObjectVersion("WF1/others/avatar.jpg"))
                    .thenReturn(new CosService.VersionedObjectHead(type, 100, "legacy-etag", 0));

            assertThatCode(() -> {
                var result = store(cos).avatarResource(snapshot("https://cdn.test/WF1/others/avatar.jpg"));
                assertThat(result.url()).startsWith("https://cdn.test/WF1/others/avatar.jpg?imageMogr2/frame/1/thumbnail/512x512%3E/strip/format/png&v=");
                assertThat(result.version()).hasSize(64);
            }).doesNotThrowAnyException();
            verify(cos).headObjectVersion("WF1/others/avatar.jpg");
            verifyNoMoreInteractions(cos);
        }
    }

    /** 诊断准确区分失败阶段，绝不记录完整地址、对象键或异常中的敏感信息。 */
    @ParameterizedTest
    @ValueSource(strings = {"URL_INVALID", "HEAD_FAILED", "HEAD_MISSING", "MIME_INVALID", "SIZE_INVALID", "ETAG_MISSING"})
    void reportsSafeAvatarFailureStage(String stage) {
        var cos = mock(CosService.class);
        String url = "https://cdn.test/WF1/others/private-avatar.jpg";
        var normal = new CosService.VersionedObjectHead("image/jpeg", 100, "private-etag", 0);
        when(cos.headObjectVersion(anyString())).thenReturn(normal);
        switch (stage) {
            case "URL_INVALID" -> url += "?token=private-token";
            case "HEAD_FAILED" -> {
                var failure = new CosServiceException("private-token " + url);
                failure.setStatusCode(503);
                when(cos.headObjectVersion(anyString())).thenThrow(failure);
            }
            case "HEAD_MISSING" -> when(cos.headObjectVersion(anyString())).thenReturn(null);
            case "MIME_INVALID" -> when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("text/html; token=private-token", 100, "private-etag", 0));
            case "SIZE_INVALID" -> when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("image/jpeg", 6 * 1024 * 1024, "private-etag", 0));
            case "ETAG_MISSING" -> when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("image/jpeg", 100, "", 0));
            default -> throw new AssertionError(stage);
        }
        Logger logger = (Logger) LoggerFactory.getLogger(MiniappCodeObjectStore.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var input = snapshot(url);
            assertThatThrownBy(() -> store(cos).avatarResource(input)).isInstanceOf(BusinessException.class);
            assertThat(appender.list).hasSize(1);
            String log = appender.list.getFirst().getFormattedMessage();
            assertThat(log).contains("stage=" + stage).doesNotContain("private", "WF1", "https://", "token=");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            if ("HEAD_FAILED".equals(stage)) assertThat(log).contains("status=503");
            verify(cos, never()).download(anyString());
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }

    /** 缺失对象、无版本、无效类型与过大头像均失败，不悄悄更换身份头像。 */
    @Test void rejectsInvalidAvatarMetadata() {
        for (var head : new CosService.VersionedObjectHead[]{null,
                new CosService.VersionedObjectHead("image/png", 1, "", 0),
                new CosService.VersionedObjectHead("text/html", 100, "tag", 0),
                new CosService.VersionedObjectHead("image/png", 0, "tag", 0),
                new CosService.VersionedObjectHead("image/png", 6 * 1024 * 1024, "tag", 0)}) {
            var cos = mock(CosService.class);
            when(cos.headObjectVersion(anyString())).thenReturn(head);
            assertThatThrownBy(() -> store(cos).avatarResource(snapshot("https://cdn.test/WF1/others/a.png"))).isInstanceOf(BusinessException.class);
            verify(cos, never()).download(anyString());
        }
    }
}
