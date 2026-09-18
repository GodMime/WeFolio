package com.jxc.wefolio.service.miniappcode;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 头像仅是资料元数据，任何已有地址都不能触发服务端远端访问。 */
class MiniappCodeObjectStoreTest {
    /** 当前公开域名仅用于识别可附加图片处理参数的自有头像。 */
    private MiniappCodeObjectStore store(CosService cos) {
        var config = new CosProperties(); config.setPublicBaseUrl("https://cdn.test");
        return new MiniappCodeObjectStore(cos, config);
    }

    /** 构建来自所属个人资料的头像快照。 */
    private PortfolioMiniappCodeSnapshot snapshot(String url) {
        return new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.USER.getCode(), 1L, 2L, "r", "PF123", "WF1", "名", "", "标题", url);
    }

    /** 历史域名、微信头像、处理参数及跨目录地址都原样交给客户端，不替换身份图片。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://legacy.example/WF1/others/avatar.png",
            "https://thirdwx.qlogo.cn/mmopen/example/132",
            "https://cdn.test/WF1/others/avatar.png?v=old",
            "https://cdn.test/WF1/others/avatar.png?imageMogr2/thumbnail/200x200",
            "https://cdn.test/WF1/others/avatar.png#legacy",
            "https://cdn.test/WF2/others/avatar.png",
            "https://cdn.test/WF1/protfolio/avatar.png",
            "https://cdn.test/new.png",
            "http://legacy.example/avatar.png",
            "not a valid url"
    })
    void preservesExistingAvatarUrlsWithoutRemoteAccess(String url) {
        var cos = mock(CosService.class);
        assertThatCode(() -> {
            var result = store(cos).avatarResource(snapshot(url));
            assertThat(result.url()).isEqualTo(url);
            assertThat(result.version()).hasSize(64);
        }).doesNotThrowAnyException();
        verifyNoInteractions(cos);
    }

    /** 空头像与现有个人默认地址继续使用客户端默认资源，不改变默认身份语义。 */
    @Test
    void defaultsHaveNoRemoteCalls() {
        var cos = mock(CosService.class);
        var personal = store(cos).avatarResource(snapshot(null));
        assertThat(personal.url()).isEmpty();
        assertThat(store(cos).avatarResource(snapshot(" "))).isEqualTo(personal);
        assertThat(store(cos).avatarResource(snapshot("https://cdn2.we-folio.dingchenyong.top/system/wefolio-default-avatar-512.jpg"))).isEqualTo(personal);
        var team = new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.TEAM.getCode(), 1L, 2L, "r", "TPF123", "TM1", "团队", "", "标题", null);
        assertThat(store(cos).avatarResource(team).url()).isEmpty();
        assertThat(store(cos).avatarResource(team).version()).isNotEqualTo(personal.version());
        verifyNoInteractions(cos);
    }

    /** 当前自有无参数地址仍转换为固定首帧缩略 PNG，但无需检查原图 MIME 或版本头。 */
    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "gif", "webp"})
    void ownedAvatarTransformationDoesNotReadObjectMetadata(String extension) {
        var cos = mock(CosService.class);
        when(cos.headObjectVersion(anyString())).thenThrow(new IllegalStateException("头像存储暂不可用"));
        String url = "https://cdn.test/WF1/others/avatar." + extension;
        assertThatCode(() -> {
            var result = store(cos).avatarResource(snapshot(url));
            assertThat(result.url()).startsWith(url + "?imageMogr2/frame/1/thumbnail/512x512%3E/strip/format/png&v=");
            assertThat(result.version()).hasSize(64);
        }).doesNotThrowAnyException();
        verifyNoInteractions(cos);
    }

    /** 地址相同则版本稳定，新上传地址改变则版本变化，不依赖头像远端状态。 */
    @Test
    void avatarVersionDependsOnlyOnStoredUrl() {
        var cos = mock(CosService.class);
        var first = store(cos).avatarResource(snapshot("https://cdn.test/WF1/others/avatar.png"));
        var repeated = store(cos).avatarResource(snapshot("https://cdn.test/WF1/others/avatar.png"));
        var changed = store(cos).avatarResource(snapshot("https://cdn.test/WF1/others/new-avatar.png"));
        assertThat(repeated).isEqualTo(first);
        assertThat(changed.version()).isNotEqualTo(first.version());
        verifyNoInteractions(cos);
    }

    /** 团队历史地址不受新目录规范阻断，仍使用团队资料中的头像。 */
    @Test
    void teamHistoricalAvatarKeepsItsOriginalUrl() {
        var cos = mock(CosService.class);
        String url = "https://cdn.test/WF1/others/old-team-avatar.png";
        var team = new PortfolioMiniappCodeSnapshot(PortfolioOwnerTypeDict.TEAM.getCode(), 1L, 2L, "r", "TPF123", "TM1", "团队", "", "标题", url);
        assertThatCode(() -> assertThat(store(cos).avatarResource(team).url()).isEqualTo(url))
                .doesNotThrowAnyException();
        verifyNoInteractions(cos);
    }

    /** 公开域名未配置时头像元数据仍可返回，不能影响原码生成。 */
    @Test
    void missingPublicBaseKeepsStoredAvatarUrl() {
        var cos = mock(CosService.class);
        var store = new MiniappCodeObjectStore(cos, new CosProperties());
        String url = "https://legacy.example/avatar.png";
        assertThatCode(() -> assertThat(store.avatarResource(snapshot(url)).url()).isEqualTo(url))
                .doesNotThrowAnyException();
        verifyNoInteractions(cos);
    }
}
