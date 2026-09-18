package com.jxc.wefolio.service.miniappcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.common.lock.TestDistributedLockExecutor;
import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.config.PortfolioMiniappCodeProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import java.util.Arrays;
import com.jxc.wefolio.service.miniappcode.MiniappCodeObjectStore.StoredCode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实缓存、锁、资源服务及对象存储链路，只替换外部微信和 COS。 */
class PortfolioMiniappCodeResourceServiceTest {
    /** 远端 COS 边界。 */
    private final CosService cos = mock(CosService.class);
    /** 远端微信边界。 */
    private final WechatMiniappCodeClient client = mock(WechatMiniappCodeClient.class);
    /** 真实元数据缓存。 */
    private final LocalCacheService cache = spy(new LocalCacheService(new LocalCacheProperties()));
    /** 模拟 COS 持久对象。 */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    /** 可修改环境配置，验证缓存隔离。 */
    private final PortfolioMiniappCodeProperties properties = new PortfolioMiniappCodeProperties();
    /** 待验证完整资源链路。 */
    private PortfolioMiniappCodeResourceService service;

    /** 外部对象边界保存真实上传字节，不替换对象存储逻辑。 */
    @BeforeEach void setUp() throws Exception {
        var wechat = new WechatMiniappProperties(); wechat.setAppId("wx-test");
        var config = new CosProperties(); config.setPublicBaseUrl("https://cdn.test");
        when(cos.headObjectVersion(anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (key.contains("/others/")) return new CosService.VersionedObjectHead("image/png", 200, "etag-1", 0);
            byte[] bytes = objects.get(key);
            return bytes == null ? null : new CosService.VersionedObjectHead(key.endsWith(".jpg") ? "image/jpeg" : "image/png", bytes.length, Integer.toString(Arrays.hashCode(bytes)), 0);
        });
        when(cos.headObject(anyString())).thenAnswer(i -> {
            byte[] bytes = objects.get(i.getArgument(0));
            if (bytes == null) throw new IllegalStateException("不存在");
            return new CosService.ObjectHead("image/png", bytes.length);
        });
        when(cos.uploadToObjectKey(any(), anyString())).thenAnswer(i -> {
            objects.put(i.getArgument(1), ((MultipartFile)i.getArgument(0)).getBytes());
            return "https://cdn.test/" + i.getArgument(1);
        });
        when(cos.publicUrl(anyString())).thenAnswer(i -> "https://cdn.test/" + i.getArgument(0));
        when(client.generate(anyString(), anyString())).thenReturn(WechatMiniappCodeClientTest.png(800, 800));
        service = new PortfolioMiniappCodeResourceService(cache, new TestDistributedLockExecutor(),
                new MiniappCodeObjectStore(cos, config), client, wechat, properties, new ObjectMapper());
    }

    /** 重复请求不得下载头像或原码，也不能再次上传或调用微信。 */
    @Test void cachedResourcesNeverDownloadImages() {
        var first = service.generate(snapshot("r1"), 1L);
        clearInvocations(cos, client);
        var repeated = service.generate(snapshot("r1"), 2L);
        assertThat(repeated.getContentVersion()).isEqualTo(first.getContentVersion());
        assertThat(repeated.getCodeUrl()).isEqualTo(first.getCodeUrl());
        assertThat(objects).hasSize(1);
        verify(cos, never()).download(anyString());
        verify(cos, never()).uploadToObjectKey(any(), anyString());
        verify(cos).headObjectVersion("WF1/others/avatar.png");
        verifyNoInteractions(client);
    }

    /** 头像原址覆盖改变资源版本及下载 URL，但不重新生成官方原码。 */
    @Test void avatarOverwriteInvalidatesContentWithoutRegeneratingRawCode() {
        var first = service.generate(snapshot("r1"), 1L);
        when(cos.headObjectVersion("WF1/others/avatar.png")).thenReturn(new CosService.VersionedObjectHead("image/png", 201, "etag-2", 0));
        var next = service.generate(snapshot("r1"), 1L);
        assertThat(next.getContentVersion()).isNotEqualTo(first.getContentVersion());
        assertThat(next.getAvatarUrl()).isNotEqualTo(first.getAvatarUrl());
        assertThat(next.getCodeUrl()).isEqualTo(first.getCodeUrl());
        verify(client, times(1)).generate(anyString(), anyString());
        verify(cos, never()).download(anyString());
        assertThat(objects).hasSize(1);
    }

    /** 修改正式版本或公开文本只更新资源版本，不消耗生成额度。 */
    @Test void publishedMetadataReusesRawCodeAndDoesNotConsumeQuota() {
        var first = service.generate(snapshot("r0"), 1L);
        for (int n = 1; n < 8; n++) {
            var next = service.generate(snapshot("r" + n), 1L);
            assertThat(next.getCodeUrl()).isEqualTo(first.getCodeUrl());
            assertThat(next.getContentVersion()).isNotEqualTo(first.getContentVersion());
        }
        var text = new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.USER.getCode(), 1L, 2L, "r0", "PF1234567890", "WF1", "新名字", "摄影师 · 杭州", "新标题", snapshot("r0").avatarUrl());
        var changed = service.generate(text, 1L);
        assertThat(changed.getCodeUrl()).isEqualTo(first.getCodeUrl());
        assertThat(changed.getContentVersion()).isNotEqualTo(first.getContentVersion());
        assertThat(changed.getShareTitle()).isEqualTo("新标题");
        verify(client, times(1)).generate(anyString(), anyString());
        assertThat(objects).hasSize(1);
    }

    /** 真正原码未命中才受每用户五次额度限制，命中不受限制。 */
    @Test void limitsMissesButAllowsHits() {
        var first = service.generate(withShareCode("PF0"), 1L);
        for (int n = 1; n < 5; n++) service.generate(withShareCode("PF" + n), 1L);
        assertThatThrownBy(() -> service.generate(withShareCode("PF5"), 1L)).isInstanceOf(BusinessException.class);
        assertThat(service.generate(withShareCode("PF0"), 1L).getCodeUrl()).isEqualTo(first.getCodeUrl());
        assertThat(service.generate(withShareCode("PF5"), 2L).getWidth()).isEqualTo(1080);
    }

    /** 并发冷请求合并成一次微信请求和一次图片上传。 */
    @Test void coalescesConcurrentGeneration() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> service.generate(snapshot("r1"), (long)i + 1))).toList();
            var url = futures.getFirst().get().getCodeUrl();
            for (var future : futures) assertThat(future.get().getCodeUrl()).isEqualTo(url);
        }
        verify(client, times(1)).generate(anyString(), anyString());
        verify(cos, times(1)).uploadToObjectKey(any(), anyString());
        assertThat(objects).hasSize(1);
    }

    /** 同团队的不同分享成员共用原码，不同所属团队严格隔离。 */
    @Test void isolatesTeamsAndSharesCodeAcrossActors() {
        var a = new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.TEAM.getCode(), 3L, 7L, "r", "TPF123", "TM3", "甲团队", "上海", "标题", null);
        var b = new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.TEAM.getCode(), 4L, 8L, "r", "TPF123", "TM4", "乙团队", "北京", "标题", null);
        var first = service.generate(a, 1L);
        assertThat(service.generate(a, 2L).getCodeUrl()).isEqualTo(first.getCodeUrl());
        assertThat(service.generate(b, 1L).getCodeUrl()).contains("TM4/protfolio/").isNotEqualTo(first.getCodeUrl());
        verify(client, times(2)).generate("pages/team-portfolios/visitor-portfolio/team-visitor-portfolio", "TPF123");
        verify(cos, never()).headObjectVersion(contains("/others/"));
    }

    /** 发布环境不同必须使用不同原码缓存。 */
    @Test void isolatesWechatEnvironments() {
        var first = service.generate(snapshot("r"), 1L);
        properties.setEnvVersion("trial");
        assertThat(service.generate(snapshot("r"), 1L).getCodeUrl()).isNotEqualTo(first.getCodeUrl());
        verify(client, times(2)).generate(anyString(), anyString());
    }

    /** 写入失败不留下成功元数据，重试重新生成。 */
    @Test void doesNotCacheFailedUpload() {
        doThrow(new BusinessException("上传失败")).when(cos).uploadToObjectKey(any(), anyString());
        assertThatThrownBy(() -> service.generate(snapshot("r"), 1L)).isInstanceOf(BusinessException.class);
        assertThat(objects).isEmpty();
        doAnswer(i -> { objects.put(i.getArgument(1), ((MultipartFile)i.getArgument(0)).getBytes()); return "url"; }).when(cos).uploadToObjectKey(any(), anyString());
        assertThat(service.generate(snapshot("r"), 1L).getCodeUrl()).isNotBlank();
        verify(client, times(2)).generate(anyString(), anyString());
    }

    /** 原码消失后重建，字节变化使用新不可变 URL 与新的内容版本。 */
    @Test void rebuildsMissingRawObjectWithImmutableUrl() throws Exception {
        var first = service.generate(snapshot("r1"), 1L);
        objects.clear();
        byte[] changed = WechatMiniappCodeClientTest.png(800, 800);
        var image = MiniappCodeImages.decode(changed); image.setRGB(0, 0, 0xff00ff00);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        when(client.generate(anyString(), anyString())).thenReturn(bytes.toByteArray());
        var next = service.generate(snapshot("r1"), 1L);
        assertThat(next.getCodeUrl()).isNotEqualTo(first.getCodeUrl());
        assertThat(next.getContentVersion()).isNotEqualTo(first.getContentVersion());
        verify(client, times(2)).generate(anyString(), anyString());
    }

    /** 不可反序列化的 Redis 元数据被驱逐，允许重建。 */
    @Test void rebuildsCorruptedCacheMetadata() {
        doThrow(new IllegalStateException("损坏元数据")).when(cache).get(anyString(), eq(StoredCode.class));
        assertThat(service.generate(snapshot("r"), 1L).getCodeUrl()).isNotBlank();
        assertThat(objects).hasSize(1);
    }

    /** Redis 丢失时从确定性 COS 路径恢复，不重复调用微信或上传。 */
    @Test void recoversPersistentCodeWithoutRedis() {
        var first = service.generate(snapshot("r1"), 1L);
        doReturn(Optional.empty()).when(cache).get(anyString(), eq(StoredCode.class));
        clearInvocations(cos, client);
        assertThat(service.generate(snapshot("r1"), 1L).getCodeUrl()).isEqualTo(first.getCodeUrl());
        verifyNoInteractions(client);
        verify(cos, never()).download(anyString());
        verify(cos, never()).uploadToObjectKey(any(), anyString());
    }

    /** 固定个人/团队/环境目录与完整参数摘要，不依赖头像或标题。 */
    @Test void usesApprovedDeterministicObjectPaths() {
        service.generate(snapshot("r"), 1L);
        assertThat(objects).containsKey("WF1/protfolio/miniapp-code/2/release/304d872d43d053ae0fa3c33f47d222f29bbea49dc13b1e59abb0ce06c11da99d.png");
        properties.setEnvVersion("trial"); service.generate(snapshot("r"), 1L);
        assertThat(objects).containsKey("WF1/protfolio/miniapp-code/2/trial/8a4ca36da54dd97bce5cf48d219b57f2b7fdf08a5eb2a82dc55ac8db8a289f83.png");
        properties.setEnvVersion("release");
        service.generate(new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.TEAM.getCode(), 3L, 7L, "r", "TPF123", "TM3", "团队", "", "标题", null), 1L);
        assertThat(objects).containsKey("TM3/protfolio/miniapp-code/7/release/d8b20509eddef2d0021b0bdf3a2b3501d2204ca00f2c4e1c7a63b4d058901046.png");
    }

    /** JPEG 原始字节不重编码，扩展名及上传 MIME 与真实格式一致。 */
    @Test void preservesJpegBinaryAndRecoversItWithoutRedis() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB), "jpg", out);
        byte[] jpg = out.toByteArray();
        when(client.generate(anyString(), anyString())).thenReturn(jpg);
        var first = service.generate(snapshot("r"), 1L);
        assertThat(objects).hasSize(1);
        assertThat(objects.values().iterator().next()).isEqualTo(jpg);
        var upload = ArgumentCaptor.forClass(MultipartFile.class);
        verify(cos).uploadToObjectKey(upload.capture(), endsWith(".jpg"));
        assertThat(upload.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(first.getCodeUrl()).contains(".jpg?v=");
        doReturn(Optional.empty()).when(cache).get(anyString(), eq(StoredCode.class));
        clearInvocations(cos, client);
        assertThat(service.generate(snapshot("r"), 1L).getCodeUrl()).isEqualTo(first.getCodeUrl());
        verifyNoInteractions(client);
        verify(cos, never()).download(anyString());
        verify(cos, never()).uploadToObjectKey(any(), anyString());
    }

    /** 缓存版本或 MIME 与 COS 不一致时重新生成，不能返回被替换的文件。 */
    @Test void rebuildsChangedObjectVersionOrMime() {
        var first = service.generate(snapshot("r"), 1L);
        String key = objects.keySet().iterator().next();
        when(cos.headObjectVersion(key)).thenReturn(
                new CosService.VersionedObjectHead("text/html", 10, "changed", 0),
                new CosService.VersionedObjectHead("text/html", 10, "changed", 0),
                new CosService.VersionedObjectHead("image/png", 1940, "new-official-etag", 0));
        var changed = service.generate(snapshot("r"), 1L);
        assertThat(changed.getCodeUrl()).isNotEqualTo(first.getCodeUrl());
        verify(client, times(2)).generate(anyString(), anyString());
    }

    /** MIME 未变但 ETag 已变也不能继续返回被覆盖对象。 */
    @Test void rebuildsRawObjectWhenOnlyEtagChanges() {
        var first = service.generate(snapshot("r"), 1L);
        String key = objects.keySet().iterator().next();
        when(cos.headObjectVersion(key)).thenReturn(
                new CosService.VersionedObjectHead("image/png", 1940, "changed", 0),
                new CosService.VersionedObjectHead("image/png", 1940, "changed", 0),
                new CosService.VersionedObjectHead("image/png", 1940, "new-official-etag", 0));
        assertThat(service.generate(snapshot("r"), 1L).getCodeUrl()).isNotEqualTo(first.getCodeUrl());
        verify(client, times(2)).generate(anyString(), anyString());
    }

    /** 已知对象被覆盖后重建失败，重试仍须获取官方原码，不能恢复异常版本。 */
    @Test void failedRebuildKeepsTrustedVersionForNextRetry() throws Exception {
        var first = service.generate(snapshot("r"), 1L);
        String key = objects.keySet().iterator().next();
        byte[] official = objects.get(key);
        var changedImage = MiniappCodeImages.decode(official);
        changedImage.setRGB(0, 0, 0xff00ff00);
        var changedBytes = new ByteArrayOutputStream();
        ImageIO.write(changedImage, "png", changedBytes);
        objects.put(key, changedBytes.toByteArray());
        when(client.generate(anyString(), anyString()))
                .thenThrow(new BusinessException("微信暂时不可用"))
                .thenReturn(official);

        assertThatThrownBy(() -> service.generate(snapshot("r"), 1L)).isInstanceOf(BusinessException.class);
        assertThat(objects.get(key)).isEqualTo(changedBytes.toByteArray());
        var retried = service.generate(snapshot("r"), 1L);

        assertThat(retried.getCodeUrl()).isEqualTo(first.getCodeUrl());
        assertThat(objects.get(key)).isEqualTo(official);
        verify(client, times(3)).generate(anyString(), anyString());
        verify(cos, times(2)).uploadToObjectKey(any(), eq(key));
        verify(cos, never()).download(anyString());
    }

    /** COS 暂时不可用不能误当不存在，否则会放大微信调用和上传压力。 */
    @Test void storageOutageNeverTriggersGeneration() {
        when(cos.headObjectVersion(contains("/protfolio/"))).thenThrow(new IllegalStateException("存储超时"));
        assertThatThrownBy(() -> service.generate(snapshot("r"), 1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
        verify(cos, never()).uploadToObjectKey(any(), anyString());
    }

    /** 合法个人发布快照。 */
    static PortfolioMiniappCodeSnapshot snapshot(String revision) {
        return new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.USER.getCode(), 1L, 2L, revision, "PF1234567890", "WF1", "小明", "主持人 · 上海", "我的作品集", "https://cdn.test/WF1/others/avatar.png");
    }
    /** 不同分享码用于真实原码未命中测试。 */
    private PortfolioMiniappCodeSnapshot withShareCode(String shareCode) {
        var s = snapshot("r");
        return new PortfolioMiniappCodeSnapshot(s.ownerType(), s.ownerId(), s.portfolioId(), s.publishedRevision(), shareCode, s.uniqueCode(), s.displayName(), s.subtitle(), s.shareTitle(), s.avatarUrl());
    }
}
