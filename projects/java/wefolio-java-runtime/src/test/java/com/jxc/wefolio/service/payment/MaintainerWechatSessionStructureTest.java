package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护者微信会话结构测试 — 固定 AES-GCM、版本条件失效和刷新接口契约。
 */
class MaintainerWechatSessionStructureTest {

    /** runtime Java 源码根目录。 */
    private static final Path ROOT = Path.of("src/main/java/com/jxc/wefolio");

    @Test
    void cipherShouldUseAesGcmWithRandomNonce() throws IOException {
        String source = read("service/payment/SessionKeyCipher.java");

        assertThat(source)
                .contains("AES/GCM/NoPadding")
                .contains("new SecureRandom()")
                .contains("GCMParameterSpec")
                .contains("Base64.getEncoder()")
                .contains("Base64.getDecoder()");
    }

    @Test
    void sessionServiceShouldIncrementVersionAndInvalidateOnlyUsedVersion() throws IOException {
        String source = read("service/payment/MaintainerWechatSessionService.java");

        assertThat(source)
                .contains("saveAvailableSession(")
                .contains("findAvailableSession(")
                .contains("invalidateVersion(")
                .contains("maintainerWechatSessionEntityMapper.invalidateVersion(")
                .contains("MaintainerWechatSessionStatusDict.AVAILABLE.getCode()")
                .contains("MaintainerWechatSessionStatusDict.INVALID.getCode()");
    }

    @Test
    void authControllerShouldExposeMaintainerSessionRefreshWithoutReturningSessionKey() throws IOException {
        String source = read("controller/MiniappAuthController.java");

        assertThat(source)
                .contains("@PostMapping(\"/maintainer/wechat-session/refresh\")")
                .contains("@MaintainerAccess")
                .contains("refreshMaintainerWechatSession")
                .doesNotContain("response.setSessionKey");
    }

    /** 读取并确认指定源文件存在。 */
    private String read(String relativePath) throws IOException {
        Path path = ROOT.resolve(relativePath);
        assertThat(path).exists();
        return Files.readString(path);
    }
}
