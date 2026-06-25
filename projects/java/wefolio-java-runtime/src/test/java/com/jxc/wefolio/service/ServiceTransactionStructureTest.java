package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务事务边界结构校验，避免依赖自注入触发 AOP 代理。
 */
class ServiceTransactionStructureTest {

    /** 后端源码根目录 */
    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void miniappAuthServiceShouldNotUseSelfInjectionForTransactionalRegistration() throws IOException {
        String source = readSource("com/jxc/wefolio/service/MiniappAuthService.java");

        assertThat(source)
                .doesNotContain("@Autowired")
                .doesNotContain("@Lazy")
                .doesNotContain("MiniappAuthService self")
                .doesNotContain("doCreateWechatUser(");
    }

    @Test
    void userRegistrationServiceShouldOwnTransactionalWechatUserCreation() throws IOException {
        Path sourcePath = MAIN_SOURCE_ROOT.resolve("com/jxc/wefolio/service/UserRegistrationService.java");
        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);
        assertThat(source)
                .contains("class UserRegistrationService")
                .contains("@Transactional(rollbackFor = Exception.class)")
                .contains("createWechatMaintainerUser(");
    }

    private String readSource(String relativePath) throws IOException {
        return Files.readString(MAIN_SOURCE_ROOT.resolve(relativePath));
    }
}
