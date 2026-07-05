package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务事务边界结构校验，避免依赖自注入触发 AOP 代理。
 */
class ServiceTransactionStructureTest {

    /** 后端源码根目录 */
    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    /** 服务层源码目录 */
    private static final Path SERVICE_SOURCE_ROOT = MAIN_SOURCE_ROOT.resolve("com/jxc/wefolio/service");

    /** 业务层手动填充 BaseEntity 时间字段的调用模式 */
    private static final Pattern BUSINESS_BASE_TIME_SETTER_PATTERN = Pattern.compile(
            "\\.set(?:CreatedAt|UpdatedAt)\\((?:LocalDateTime\\.now\\(\\)|now|updatedAt)\\)"
    );

    /** 服务层局部更新手动写入 updated_at 的调用模式 */
    private static final Pattern BUSINESS_UPDATED_AT_WRAPPER_PATTERN = Pattern.compile(
            "\\.set\\((?:COLUMN_UPDATED_AT|COL_UPDATED_AT|WORK_COLUMN_UPDATED_AT|\"updated_at\")\\s*,"
    );

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

    @Test
    void teamRegistrationServiceShouldOwnTransactionalTeamCreation() throws IOException {
        Path sourcePath = MAIN_SOURCE_ROOT.resolve("com/jxc/wefolio/service/TeamRegistrationService.java");
        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);
        assertThat(source)
                .contains("class TeamRegistrationService")
                .contains("@Transactional(rollbackFor = Exception.class)")
                .contains("createTeamWithOwner(");
    }

    @Test
    void visitorSubmissionServicesShouldOwnTransactionalWrites() throws IOException {
        String visitSource = readSource("com/jxc/wefolio/service/PortfolioVisitService.java");
        String leadSource = readSource("com/jxc/wefolio/service/ContactLeadService.java");

        assertThat(visitSource)
                .contains("@Transactional(rollbackFor = Exception.class)\n    public VisitRecordEntity recordOpen(")
                .contains("@Transactional(rollbackFor = Exception.class)\n    public void recordEvent(")
                .contains("@Transactional(rollbackFor = Exception.class)\n    public ScheduleQueryRecordResult recordScheduleQuery(")
                .contains("@Transactional(rollbackFor = Exception.class)\n    public void recordContactLeadSubmitted(");
        assertThat(leadSource)
                .contains("@Transactional(rollbackFor = Exception.class)\n    public ContactLeadSubmitResponse submit(String shareCode")
                .contains("private ContactLeadSubmitResponse submitInternal(PortfolioEntity portfolio")
                .contains("return submitInternal(portfolio, request);")
                .doesNotContain("@Transactional(rollbackFor = Exception.class)\n    public ContactLeadSubmitResponse submit(PortfolioEntity portfolio")
                .doesNotContain("return submit(portfolio, request);");
    }

    @Test
    void mineTeamServiceShouldInitializeCosBeforeTransactionalTeamCreation() throws IOException {
        String source = readSource("com/jxc/wefolio/service/MineTeamService.java");
        int initStorageIndex = source.indexOf("cosService.initTeamStorage(uniqueCode)");
        int createTeamIndex = source.indexOf("teamRegistrationService.createTeamWithOwner(");

        assertThat(initStorageIndex).isNotNegative();
        assertThat(createTeamIndex).isNotNegative();
        assertThat(initStorageIndex).isLessThan(createTeamIndex);
        assertThat(source)
                .doesNotContain("@Transactional(rollbackFor = Exception.class)\n    public MineTeamDetailResponse createTeam(");
    }

    @Test
    void serviceLayerShouldNotManuallyFillBaseEntityTimestamps() throws IOException {
        try (Stream<Path> serviceFiles = Files.list(SERVICE_SOURCE_ROOT)) {
            List<String> violations = serviceFiles
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .flatMap(ServiceTransactionStructureTest::baseTimeSetterViolations)
                    .toList();

            assertThat(violations).isEmpty();
        }
    }

    private String readSource(String relativePath) throws IOException {
        return Files.readString(MAIN_SOURCE_ROOT.resolve(relativePath));
    }

    /**
     * 提取服务类中手动填充基础实体时间字段的位置。
     *
     * @param path 源码路径
     * @return 违规位置列表
     */
    private static Stream<String> baseTimeSetterViolations(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            return Stream.iterate(0, index -> index + 1)
                    .limit(lines.size())
                    .filter(index -> BUSINESS_BASE_TIME_SETTER_PATTERN.matcher(lines.get(index)).find()
                            || BUSINESS_UPDATED_AT_WRAPPER_PATTERN.matcher(lines.get(index)).find())
                    .map(index -> path.getFileName() + ":" + (index + 1) + " " + lines.get(index).trim());
        } catch (IOException e) {
            throw new IllegalStateException("读取服务源码失败: " + path, e);
        }
    }
}
