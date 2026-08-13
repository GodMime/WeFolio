package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品审核任务结构测试 — 固定 job 工程内部模型、配置和跨工程依赖边界。
 */
class WorkAuditStructureTest {

    /** COS SDK Maven 坐标 */
    private static final String COS_API_ARTIFACT = "<artifactId>cos_api</artifactId>";

    /** Flyway Maven groupId */
    private static final String FLYWAY_GROUP_ID = "<groupId>org.flywaydb</groupId>";

    /** runtime 实体包导入前缀 */
    private static final String RUNTIME_ENTITY_IMPORT = "import com.jxc.wefolio.entity.";

    /** runtime Mapper 包导入前缀 */
    private static final String RUNTIME_MAPPER_IMPORT = "import com.jxc.wefolio.mapper.";

    /** runtime Dict 包导入前缀 */
    private static final String RUNTIME_DICT_IMPORT = "import com.jxc.wefolio.dict.";

    @Test
    void pomShouldUseCosSdkAndStillExcludeFlyway() throws IOException {
        String pomContent = Files.readString(Path.of("pom.xml"));

        assertThat(pomContent).contains(COS_API_ARTIFACT);
        assertThat(pomContent).doesNotContain(FLYWAY_GROUP_ID);
    }

    @Test
    void applicationYamlShouldDeclareDatabaseCosAndWorkAuditConfigurationWithChineseComments() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml)
                .contains("# 数据库连接地址，参考 wefolio-java-runtime。")
                .contains("# 腾讯云访问密钥 ID，参考 wefolio-java-runtime。")
                .contains("# 是否启用作品审核定时任务。")
                .contains("video-submit-max-attempts: ${WEFOLIO_WORK_AUDIT_VIDEO_SUBMIT_MAX_ATTEMPTS:3}")
                .contains("video-query-max-attempts: 120")
                .contains("video-snapshot-interval-seconds: 60")
                .doesNotContain("flyway:");
    }

    @Test
    void jobShouldDeclareInternalEntitiesRepositoriesAndProperties() {
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/entity/BaseEntity.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/entity/WorkAuditWorkEntity.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/entity/WorkAuditTaskEntity.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/repo/WorkAuditWorkRepository.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/repo/WorkAuditTaskRepository.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/config/CosProperties.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/config/CosConfig.java")).exists();
        assertThat(Path.of("src/main/java/com/jxc/wefolio/job/config/WorkAuditProperties.java")).exists();
    }

    @Test
    void jobShouldNotImportRuntimeEntityMapperOrDictClasses() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio/job");

        try (Stream<Path> sourcePaths = Files.walk(sourceRoot)) {
            String allSources = sourcePaths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::readSource)
                    .reduce("", String::concat);

            assertThat(allSources)
                    .doesNotContain(RUNTIME_ENTITY_IMPORT)
                    .doesNotContain(RUNTIME_MAPPER_IMPORT)
                    .doesNotContain(RUNTIME_DICT_IMPORT);
        }
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException("读取源码失败: " + path, ex);
        }
    }
}
