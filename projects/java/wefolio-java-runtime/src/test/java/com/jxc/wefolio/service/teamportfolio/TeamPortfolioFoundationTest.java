package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dict.TeamScheduleMemberStatusDict;
import com.jxc.wefolio.dict.TeamScheduleResultStatusDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioMaintainableTeamResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioSummaryResponse;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队作品集基础模型测试。
 */
class TeamPortfolioFoundationTest {

    /** 团队作品集基础模型任务涉及的生产源码。 */
    private static final Set<Path> TEAM_PORTFOLIO_SOURCE_FILES = Set.of(
            Path.of("config/TeamPortfolioProperties.java"),
            Path.of("constant/TeamPortfolioConstants.java"),
            Path.of("dict/TeamPortfolioComponentTypeDict.java"),
            Path.of("dict/TeamScheduleMemberStatusDict.java"),
            Path.of("dict/TeamScheduleResultStatusDict.java"),
            Path.of("dto/teamportfolio/TeamPortfolioConfigDto.java"),
            Path.of("dto/teamportfolio/TeamPortfolioMaintainableTeamResponse.java"),
            Path.of("dto/teamportfolio/TeamPortfolioRenderDto.java"),
            Path.of("dto/teamportfolio/TeamPortfolioSummaryResponse.java"),
            Path.of("message/TeamPortfolioMessage.java"),
            Path.of("service/teamportfolio/TeamPortfolioComponentContext.java"));

    /**
     * 组件字典应保持约定的编码、名称和顺序。
     */
    @Test
    void componentTypesShouldMatchStandardTeamV1Contract() {
        assertThat(TeamPortfolioComponentTypeDict.values())
                .extracting(TeamPortfolioComponentTypeDict::getCode)
                .containsExactly("TEAM_PROFILE", "CAROUSEL", "DIVIDER", "MEMBER_PORTFOLIO_GRID",
                        "MEMBER_PORTFOLIO_LIST", "TEXT_SECTION", "SCHEDULE_QUERY", "CONTACT_FORM", "QR_CONTACT");
        assertThat(TeamPortfolioComponentTypeDict.values())
                .extracting(TeamPortfolioComponentTypeDict::getDisplayName)
                .containsExactly("团队资料", "轮播图", "分割线", "双列作品集", "单列作品集", "文字说明", "档期查询", "预留联系信息", "二维码联系");
        assertThat(TeamPortfolioComponentTypeDict.fromCode("TEAM_PROFILE"))
                .isEqualTo(TeamPortfolioComponentTypeDict.TEAM_PROFILE);
    }

    /**
     * 团队档期字典应提供状态语义和查询能力。
     */
    @Test
    void scheduleStatusesShouldMatchTeamContract() {
        assertThat(TeamScheduleMemberStatusDict.values())
                .extracting(TeamScheduleMemberStatusDict::getCode)
                .containsExactly("AVAILABLE", "PARTIAL_AVAILABLE", "FULL");
        assertThat(TeamScheduleMemberStatusDict.values())
                .extracting(TeamScheduleMemberStatusDict::getDisplayName)
                .containsExactly("空闲", "部分档期空闲", "已满");
        assertThat(TeamScheduleResultStatusDict.values())
                .extracting(TeamScheduleResultStatusDict::getCode)
                .containsExactly("TEAM_AVAILABLE", "TEAM_PARTIAL_AVAILABLE", "TEAM_FULL");
        assertThat(TeamScheduleResultStatusDict.values())
                .extracting(TeamScheduleResultStatusDict::getDisplayName)
                .containsExactly("全部空闲", "部分成员可约", "已满");
        assertThat(TeamScheduleResultStatusDict.values())
                .extracting(TeamScheduleResultStatusDict::getAvailable)
                .containsExactly(true, true, false);
        assertThat(TeamScheduleResultStatusDict.fromCode("TEAM_FULL"))
                .isEqualTo(TeamScheduleResultStatusDict.TEAM_FULL);
    }

    /**
     * 开关配置应保持预期的类型与前缀。
     */
    @Test
    void featurePropertiesShouldExposeExpectedConfigurationPrefix() {
        assertThat(new TeamPortfolioProperties().isEnabled()).isFalse();
        assertThat(TeamPortfolioProperties.class.getAnnotation(Component.class)).isNotNull();
        ConfigurationProperties configurationProperties =
                TeamPortfolioProperties.class.getAnnotation(ConfigurationProperties.class);
        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("wefolio.team-portfolio");
    }

    /**
     * DTO 字段和类型应符合团队作品集边界。
     */
    @Test
    void dtosShouldExposeOnlyTeamPortfolioFields() {
        assertFields(TeamPortfolioConfigDto.class, "schemaVersion", "share", "components");
        assertFieldType(TeamPortfolioConfigDto.class, "schemaVersion", String.class);
        assertFieldType(TeamPortfolioConfigDto.class, "share", TeamPortfolioConfigDto.Share.class);
        assertListElementType(TeamPortfolioConfigDto.class, "components", TeamPortfolioConfigDto.ComponentEnvelope.class);
        assertFields(TeamPortfolioConfigDto.Share.class, "title", "description", "coverUrl");
        assertFieldType(TeamPortfolioConfigDto.Share.class, "title", String.class);
        assertFieldType(TeamPortfolioConfigDto.Share.class, "description", String.class);
        assertFieldType(TeamPortfolioConfigDto.Share.class, "coverUrl", String.class);
        assertFields(TeamPortfolioConfigDto.ComponentEnvelope.class,
                "componentKey", "componentType", "sortOrder", "enabled", "config");
        assertFieldType(TeamPortfolioConfigDto.ComponentEnvelope.class, "componentKey", String.class);
        assertFieldType(TeamPortfolioConfigDto.ComponentEnvelope.class, "componentType", String.class);
        assertFieldType(TeamPortfolioConfigDto.ComponentEnvelope.class, "sortOrder", Integer.class);
        assertFieldType(TeamPortfolioConfigDto.ComponentEnvelope.class, "enabled", Boolean.class);
        assertFieldType(TeamPortfolioConfigDto.ComponentEnvelope.class, "config", JSONObject.class);

        assertFields(TeamPortfolioRenderDto.class, "shareCode", "portfolioId", "teamId", "teamName", "title",
                "share", "preview", "underMaintenance", "visitRecordId", "components");
        assertFieldType(TeamPortfolioRenderDto.class, "shareCode", String.class);
        assertFieldType(TeamPortfolioRenderDto.class, "portfolioId", Long.class);
        assertFieldType(TeamPortfolioRenderDto.class, "teamId", Long.class);
        assertFieldType(TeamPortfolioRenderDto.class, "teamName", String.class);
        assertFieldType(TeamPortfolioRenderDto.class, "title", String.class);
        assertFieldType(TeamPortfolioRenderDto.class, "share", TeamPortfolioConfigDto.Share.class);
        assertFieldType(TeamPortfolioRenderDto.class, "preview", boolean.class);
        assertFieldType(TeamPortfolioRenderDto.class, "underMaintenance", boolean.class);
        assertFieldType(TeamPortfolioRenderDto.class, "visitRecordId", Long.class);
        assertListElementType(TeamPortfolioRenderDto.class, "components", TeamPortfolioRenderDto.Component.class);
        assertFields(TeamPortfolioRenderDto.Component.class,
                "componentKey", "componentType", "name", "sortOrder", "data");
        assertFieldType(TeamPortfolioRenderDto.Component.class, "componentKey", String.class);
        assertFieldType(TeamPortfolioRenderDto.Component.class, "componentType", String.class);
        assertFieldType(TeamPortfolioRenderDto.Component.class, "name", String.class);
        assertFieldType(TeamPortfolioRenderDto.Component.class, "sortOrder", Integer.class);
        assertFieldType(TeamPortfolioRenderDto.Component.class, "data", JSONObject.class);
        assertThat(new TeamPortfolioRenderDto().getComponents()).isEmpty();

        assertFields(TeamPortfolioSummaryResponse.class, "portfolioId", "shareCode", "title", "coverUrl", "teamId",
                "teamName", "currentRole", "canMaintain", "canShare", "publicationStatus", "draftRevision",
                "publishedRevision", "updatedAt");
        assertFieldType(TeamPortfolioSummaryResponse.class, "portfolioId", Long.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "shareCode", String.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "title", String.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "coverUrl", String.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "teamId", Long.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "teamName", String.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "currentRole", String.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "canMaintain", boolean.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "canShare", boolean.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "publicationStatus", String.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "draftRevision", Integer.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "publishedRevision", Integer.class);
        assertFieldType(TeamPortfolioSummaryResponse.class, "updatedAt", java.time.LocalDateTime.class);
        assertFields(TeamPortfolioMaintainableTeamResponse.class, "teamId", "teamName", "avatarUrl", "currentRole");
        assertFieldType(TeamPortfolioMaintainableTeamResponse.class, "teamId", Long.class);
        assertFieldType(TeamPortfolioMaintainableTeamResponse.class, "teamName", String.class);
        assertFieldType(TeamPortfolioMaintainableTeamResponse.class, "avatarUrl", String.class);
        assertFieldType(TeamPortfolioMaintainableTeamResponse.class, "currentRole", String.class);
    }

    /**
     * 上下文记录和消息常量应保持稳定。
     */
    @Test
    void contextAndMessagesShouldMatchContract() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(1L, 2L, 3);
        assertThat(context.teamId()).isEqualTo(1L);
        assertThat(context.portfolioId()).isEqualTo(2L);
        assertThat(context.revision()).isEqualTo(3);
        assertThat(TeamPortfolioComponentContext.class.isRecord()).isTrue();
        assertThat(TeamPortfolioComponentContext.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("teamId", "portfolioId", "revision");
        assertThat(TeamPortfolioComponentContext.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(long.class, long.class, int.class);
        assertThat(TeamPortfolioMessage.class.isInterface()).isTrue();
        assertThat(TeamPortfolioMessage.FEATURE_DISABLED).isEqualTo("团队作品集功能暂未开放");
        assertThat(TeamPortfolioMessage.NO_ACCESS).isEqualTo("团队不存在或无访问权限");
        assertThat(TeamPortfolioMessage.NO_MAINTAIN_PERMISSION).isEqualTo("无团队作品集维护权限");
        assertThat(TeamPortfolioMessage.PORTFOLIO_NOT_FOUND).isEqualTo("团队作品集不存在或无访问权限");
        assertThat(TeamPortfolioMessage.INVALID_SCHEMA).isEqualTo("当前作品集暂未开放访问");
    }

    /**
     * 标准团队作品集 Schema 版本应只由领域常量类维护。
     *
     * @throws Exception 读取源码失败
     */
    @Test
    void schemaVersionShouldHaveSingleConstantSource() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio");
        Path constantPath = sourceRoot.resolve("constant/TeamPortfolioConstants.java");

        assertThat(constantPath).exists();
        assertThat(Files.readString(constantPath))
                .contains("public static final String SCHEMA_VERSION_STANDARD_TEAM_V1 = \"standard-team-v1\";");

        try (var sources = Files.walk(sourceRoot)) {
            assertThat(sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> readSource(path).contains("\"standard-team-v1\""))
                    .map(sourceRoot::relativize)
                    .toList())
                    .containsExactly(Path.of("constant/TeamPortfolioConstants.java"));
        }
    }

    /**
     * 团队基础类型不应依赖个人作品集实现。
     *
     * @throws Exception 读取源码失败
     */
    @Test
    void teamPortfolioSourcesShouldNotDependOnPersonalPortfolioTypes() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio");
        List<String> forbidden = List.of("PortfolioConfigDto", "PortfolioRenderDto", "MinePortfolioService",
                "VisitorPortfolioService", "PortfolioConfigValidator", "PortfolioRenderService",
                "PortfolioVisitService", "ContactLeadService");
        assertThat(TEAM_PORTFOLIO_SOURCE_FILES).isNotEmpty().hasSize(11);
        assertThat(TEAM_PORTFOLIO_SOURCE_FILES)
                .allSatisfy(path -> assertThat(Files.isRegularFile(sourceRoot.resolve(path))).isTrue());
        List<Path> scannedFiles = TEAM_PORTFOLIO_SOURCE_FILES.stream().sorted().toList();
        assertThat(scannedFiles).isNotEmpty().hasSize(11)
                .containsExactlyInAnyOrderElementsOf(TEAM_PORTFOLIO_SOURCE_FILES);
        List<String> contents = scannedFiles.stream().map(sourceRoot::resolve).map(this::readSource).toList();
        assertThat(contents).isNotEmpty().hasSize(11);
        assertThat(contents).allSatisfy(source -> assertThat(importsOf(source))
                .noneMatch(importedType -> forbidden.stream().anyMatch(importedType::contains)));
    }

    /**
     * 断言类型声明的字段集合。
     *
     * @param type 类型
     * @param names 字段名
     */
    private void assertFields(Class<?> type, String... names) {
        assertThat(Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
                .containsExactlyInAnyOrder(names);
    }

    /**
     * 按名称获取字段。
     *
     * @param type 类型
     * @param name 字段名
     * @return 字段
     */
    private Field field(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 断言字段的原始 Java 类型。
     *
     * @param type 类型
     * @param name 字段名
     * @param expectedType 预期类型
     */
    private void assertFieldType(Class<?> type, String name, Class<?> expectedType) {
        assertThat(field(type, name).getType()).isEqualTo(expectedType);
    }

    /**
     * 断言 List 字段及其泛型元素类型。
     *
     * @param type 类型
     * @param name 字段名
     * @param expectedElementType 预期元素类型
     */
    private void assertListElementType(Class<?> type, String name, Class<?> expectedElementType) {
        Field listField = field(type, name);
        assertThat(listField.getType()).isEqualTo(List.class);
        assertThat(listField.getGenericType()).isInstanceOf(ParameterizedType.class);
        ParameterizedType parameterizedType = (ParameterizedType) listField.getGenericType();
        assertThat(parameterizedType.getActualTypeArguments()).containsExactly(expectedElementType);
    }

    /**
     * 提取源码中的 import 声明，避免类名自身的前缀造成误判。
     *
     * @param source 源码内容
     * @return import 声明
     */
    private List<String> importsOf(String source) {
        return source.lines().map(String::trim).filter(line -> line.startsWith("import ")).toList();
    }

    /**
     * 读取源码内容。
     *
     * @param path 源码路径
     * @return 源码内容
     */
    private String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("读取源码失败", exception);
        }
    }
}
