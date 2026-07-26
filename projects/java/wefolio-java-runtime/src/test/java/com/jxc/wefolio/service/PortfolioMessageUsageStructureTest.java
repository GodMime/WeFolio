package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品集服务报错文案归档约束测试。
 */
class PortfolioMessageUsageStructureTest {

    /** 团队作品集服务的报错文案必须统一引用 message 包。 */
    @Test
    void mineTeamPortfolioErrorsShouldUseTeamPortfolioMessage() throws Exception {
        String serviceSource = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/teamportfolio/MineTeamPortfolioService.java"));
        String messageSource = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/message/TeamPortfolioMessage.java"));
        List<String> errorMessages = List.of(
                "团队记录分页参数不合法",
                "团队作品集草稿配置不能为空",
                "团队作品集草稿已更新，请刷新后重试",
                "客户端草稿版本不能为空",
                "团队作品集幂等键不能为空",
                "团队作品集幂等键已用于其他内容",
                "请选择要发布的团队作品集草稿版本",
                "团队作品集已被更新，请刷新后重试",
                "已发布团队作品集不可用",
                "分享渠道不能为空",
                "团队作品集删除失败",
                "团队作品集历史写入失败",
                "SHA-256 算法不可用");

        assertThat(serviceSource)
                .contains("TeamPortfolioMessage.")
                .doesNotContain(errorMessages.toArray(String[]::new));
        assertThat(messageSource).contains(errorMessages.toArray(String[]::new));
    }

    /** 个人作品集服务的报错文案必须统一引用 message 包。 */
    @Test
    void minePortfolioErrorsShouldUsePortfolioMessage() throws Exception {
        String serviceSource = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/MinePortfolioService.java"));
        String messageSource = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/message/PortfolioMessage.java"));
        List<String> errorMessages = List.of(
                "作品集归属类型不正确",
                "SHA-256 算法不可用");

        assertThat(serviceSource)
                .contains("PortfolioMessage.")
                .doesNotContain(errorMessages.toArray(String[]::new));
        assertThat(messageSource).contains(errorMessages.toArray(String[]::new));
    }
}
