package com.jxc.wefolio.service.miniappcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.config.PortfolioMiniappCodeProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.WechatAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 微信码协议、错误与受限重试测试。 */
class WechatMiniappCodeClientTest {
    /** 仅凭证拒绝才刷新，且二进制必须保持原码尺寸。 */
    @Test void refreshesRejectedTokenOnceAndReturnsImage() throws Exception {
        var tokens = mock(WechatAccessTokenService.class);
        when(tokens.getAccessToken()).thenReturn("old");
        when(tokens.refreshAfterRejected("old")).thenReturn("new");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=old"))
                .andExpect(jsonPath("$.scene").value("PF1234567890"))
                .andExpect(jsonPath("$.width").value(800))
                .andExpect(jsonPath("$.env_version").value("release"))
                .andExpect(jsonPath("$.check_path").value(true))
                .andRespond(withSuccess("{\"errcode\":40001}", MediaType.APPLICATION_JSON));
        byte[] png = png(800, 800);
        server.expect(requestTo("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=new"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));
        var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), builder.build());
        assertThat(client.generate("pages/test", "PF1234567890")).isEqualTo(png);
        server.verify();
    }
    /** 微信业务错误不能被误判成图片或触发凭证刷新。 */
    @Test void rejectsJsonBusinessErrorWithoutRefreshing() {
        var tokens = mock(WechatAccessTokenService.class);
        when(tokens.getAccessToken()).thenReturn("old");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":41030}", MediaType.IMAGE_PNG));
        var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), builder.build());
        assertThatThrownBy(() -> client.generate("pages/test", "PF123")).isInstanceOf(BusinessException.class);
        verify(tokens, never()).refreshAfterRejected(any());
        server.verify();
    }
    /** 非法场景和页面在访问微信之前被拒绝。 */
    @Test void rejectsInvalidSceneAndPage() {
        var tokens = mock(WechatAccessTokenService.class);
        var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), RestClient.create());
        for (String scene : new String[]{"", "x".repeat(33), "中文", "a%20b"}) {
            assertThatThrownBy(() -> client.generate("pages/test", scene)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> client.generate("/pages/test?x=1", "PF123")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tokens);
    }
    /** 二次凭证拒绝必须终止，不能无限刷新。 */
    @Test void stopsAfterSecondTokenRejection() {
        var tokens = mock(WechatAccessTokenService.class);
        when(tokens.getAccessToken()).thenReturn("old"); when(tokens.refreshAfterRejected("old")).thenReturn("new");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":42001}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":40014}", MediaType.APPLICATION_JSON));
        var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), builder.build());
        assertThatThrownBy(() -> client.generate("pages/test", "PF123")).isInstanceOf(BusinessException.class);
        verify(tokens, times(1)).refreshAfterRejected(any()); server.verify();
    }
    /** 错误尺寸和伪图片不得进入存储。 */
    @Test void rejectsMalformedAndWrongSizedImages() throws Exception {
        for (byte[] bytes : new byte[][] {"garbage".getBytes(), png(799,800), new byte[0]}) {
            var tokens = mock(WechatAccessTokenService.class); when(tokens.getAccessToken()).thenReturn("old");
            var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess(bytes, MediaType.IMAGE_PNG));
            var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), builder.build());
            assertThatThrownBy(() -> client.generate("pages/test", "PF123")).isInstanceOf(BusinessException.class);
            verify(tokens, never()).refreshAfterRejected(any()); server.verify();
        }
    }
    /** JPEG 响应按实际格式验证，保持原始二进制，不进行服务器重编码。 */
    @Test void acceptsActualJpegAndPreservesOriginalBytes() throws Exception {
        var tokens = mock(WechatAccessTokenService.class); when(tokens.getAccessToken()).thenReturn("token");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB), "jpg", out);
        byte[] jpeg = out.toByteArray();
        server.expect(anything()).andExpect(jsonPath("$.auto_color").value(false))
                .andExpect(jsonPath("$.line_color.r").value(0))
                .andExpect(jsonPath("$.line_color.g").value(0))
                .andExpect(jsonPath("$.line_color.b").value(0))
                .andExpect(jsonPath("$.is_hyaline").value(false))
                .andRespond(withSuccess(jpeg, MediaType.IMAGE_JPEG));
        var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), builder.build());
        assertThat(client.generate("pages/test", "x".repeat(32))).isEqualTo(jpeg);
        server.verify();
    }

    /** 超限二进制和 HTTP 故障不得触发凭证重试或进入存储。 */
    @Test void rejectsOversizedBinaryWithoutTokenRefresh() {
        var tokens = mock(WechatAccessTokenService.class); when(tokens.getAccessToken()).thenReturn("token");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(new byte[5 * 1024 * 1024 + 1], MediaType.IMAGE_PNG));
        var client = new WechatMiniappCodeClient(tokens, new PortfolioMiniappCodeProperties(), new ObjectMapper(), builder.build());
        assertThatThrownBy(() -> client.generate("pages/test", "PF123")).isInstanceOf(BusinessException.class);
        verify(tokens, never()).refreshAfterRejected(any()); server.verify();
    }

    /** 构建独立的图片二进制夹具。 */
    static byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
