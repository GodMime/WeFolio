package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.entity.VisitorEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 访客作品集真实入口的内部超链接目标安全集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class VisitorPortfolioHyperlinkIntegrationTest {

    /** 来源作品集。 */
    private static final long SOURCE_PORTFOLIO_ID = 88L;

    /** 内部跳转目标作品集。 */
    private static final long TARGET_PORTFOLIO_ID = 99L;

    /** 来源与合法目标的所属用户。 */
    private static final long OWNER_ID = 7L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VisitorPortfolioService visitorPortfolioService;

    @MockBean
    private VisitorService visitorService;

    @MockBean
    private VisitorAuthTokenService visitorAuthTokenService;

    @MockBean
    private OwnerSelfVisitService ownerSelfVisitService;

    @MockBean
    private CosService cosService;

    @BeforeEach
    void setUp() {
        createTables();
        insertSourcePortfolioAndDisplayWork();
        insertValidTargetPortfolio();

        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("owner-openid");
        visitor.setVisitorKey("visitor-key");
        when(visitorService.resolveForOpen(any(), any(), anyString(), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, false));
        when(ownerSelfVisitService.isOwnerSelfVisitor(any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(visitorAuthTokenService.issueToken(1024L, "visitor-key"))
                .thenReturn(new VisitorAuthTokenService.VisitorLoginToken(
                        "Bearer", "visitor-token", 3600L));
        when(cosService.publicUrl(anyString()))
                .thenAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0, String.class));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_work");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_portfolio");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targetCases")
    void visitorOpenShouldExposeShareCodeOnlyForAvailableInternalTarget(
            String caseName,
            TargetState targetState,
            boolean expectedAvailable
    ) {
        applyTargetState(targetState);

        VisitorPortfolioResponse response = visitorPortfolioService.openPortfolio(
                "PF001", new VisitorPortfolioOpenRequest());

        PortfolioRenderDto.Hyperlink hyperlink = response.getRenderData()
                .getComponents().getFirst().getHyperlink();
        assertThat(hyperlink.getTargetPortfolioId()).isEqualTo(TARGET_PORTFOLIO_ID);
        assertThat(hyperlink.isTargetAvailable()).isEqualTo(expectedAvailable);
        if (expectedAvailable) {
            assertThat(hyperlink.getTargetShareCode()).isEqualTo("PFTARGET");
            assertThat(hyperlink.getTargetTitle()).isEqualTo("目标作品集");
        } else {
            assertThat(hyperlink.getTargetShareCode()).isNull();
            assertThat(hyperlink.getTargetTitle()).isNull();
            assertThat(JSON.toJSONString(response))
                    .doesNotContain("targetShareCode")
                    .doesNotContain("PFTARGET");
        }
    }

    /** 访客态目标安全矩阵。 */
    private static Stream<Arguments> targetCases() {
        return Stream.of(
                Arguments.of("同用户标准个人作品集已发布", TargetState.VALID, true),
                Arguments.of("目标属于其他用户", TargetState.OTHER_OWNER, false),
                Arguments.of("目标属于团队", TargetState.TEAM_OWNER, false),
                Arguments.of("目标已停用", TargetState.DISABLED, false),
                Arguments.of("目标已逻辑删除", TargetState.LOGICALLY_DELETED, false),
                Arguments.of("目标已下线", TargetState.OFFLINE, false),
                Arguments.of("目标正式配置为空", TargetState.EMPTY_PUBLISHED_CONFIG, false),
                Arguments.of("目标正式配置损坏", TargetState.CORRUPT_PUBLISHED_CONFIG, false),
                Arguments.of("目标为高级模板", TargetState.ADVANCED_TEMPLATE, false)
        );
    }

    /** 应用目标作品集异常状态。 */
    private void applyTargetState(TargetState state) {
        switch (state) {
            case VALID -> {
                return;
            }
            case OTHER_OWNER -> updateTarget("owner_id", 8L);
            case TEAM_OWNER -> updateTarget("owner_type", "TEAM");
            case DISABLED -> updateTarget("status", "DISABLED");
            case LOGICALLY_DELETED -> updateTarget("deleted", TARGET_PORTFOLIO_ID);
            case OFFLINE -> updateTarget("publication_status", "OFFLINE");
            case EMPTY_PUBLISHED_CONFIG -> updateTarget("published_config_json", null);
            case CORRUPT_PUBLISHED_CONFIG -> updateTarget("published_config_json", "{invalid-json");
            case ADVANCED_TEMPLATE -> updateTarget("template_type", "ADVANCED");
        }
    }

    /** 更新测试目标的单个固定字段。 */
    private void updateTarget(String column, Object value) {
        jdbcTemplate.update(
                "UPDATE wf_portfolio SET " + column + " = ? WHERE id = ?",
                value,
                TARGET_PORTFOLIO_ID
        );
    }

    /** 创建真实 Mapper 所需的最小完整测试表。 */
    private void createTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_work");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_portfolio");
        jdbcTemplate.execute("""
                CREATE TABLE wf_portfolio (
                  id BIGINT PRIMARY KEY,
                  share_code VARCHAR(32),
                  owner_type VARCHAR(16) NOT NULL,
                  owner_id BIGINT NOT NULL,
                  template_type VARCHAR(16) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  schema_version VARCHAR(64),
                  draft_config_json CLOB,
                  draft_revision INT NOT NULL DEFAULT 0,
                  draft_content_hash VARCHAR(64),
                  draft_saved_by BIGINT,
                  draft_saved_at TIMESTAMP,
                  published_config_json CLOB,
                  published_revision INT NOT NULL DEFAULT 0,
                  published_content_hash VARCHAR(64),
                  published_by BIGINT,
                  published_at TIMESTAMP,
                  publication_status VARCHAR(32),
                  ai_prompt CLOB,
                  source_type VARCHAR(32),
                  content_hash VARCHAR(64),
                  current_revision INT NOT NULL DEFAULT 0,
                  previewed_at TIMESTAMP,
                  last_saved_by BIGINT,
                  last_saved_at TIMESTAMP,
                  deleted_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_work (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  media_type VARCHAR(32) NOT NULL,
                  title VARCHAR(30) NOT NULL,
                  original_file_name VARCHAR(255),
                  media_object_key VARCHAR(512) NOT NULL,
                  media_sha256 CHAR(64),
                  cover_object_key VARCHAR(512),
                  cover_sha256 CHAR(64),
                  mime_type VARCHAR(100),
                  file_size BIGINT,
                  duration_ms INT,
                  frame_count INT,
                  cover_frame_number INT,
                  width INT,
                  height INT,
                  aspect_ratio VARCHAR(32),
                  description VARCHAR(1000),
                  service_date DATE,
                  sort_order INT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL,
                  audit_status VARCHAR(32),
                  audit_round INT NOT NULL DEFAULT 0,
                  audit_reason_code VARCHAR(64),
                  audit_reason_codes CLOB,
                  audit_reject_reason VARCHAR(1000),
                  manual_audit_no CHAR(34),
                  manual_audit_result_at TIMESTAMP(3),
                  deleted_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0
                )
                """);
    }

    /** 写入来源作品集和超链接展示图片。 */
    private void insertSourcePortfolioAndDisplayWork() {
        insertPortfolio(
                SOURCE_PORTFOLIO_ID,
                "PF001",
                "USER",
                OWNER_ID,
                "STANDARD",
                "ACTIVE",
                "PUBLISHED",
                sourceConfigJson(),
                0L
        );
        jdbcTemplate.update("""
                        INSERT INTO wf_work (
                          id, user_id, media_type, title, media_object_key, cover_object_key,
                          status, audit_status, audit_round, created_at, updated_at, deleted, version
                        ) VALUES (?, ?, 'IMAGE', '展示图片', 'work/11.jpg', 'work/11-cover.jpg',
                                  'ACTIVE', 'PASSED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
                        """,
                11L,
                OWNER_ID
        );
    }

    /** 写入默认合法目标。 */
    private void insertValidTargetPortfolio() {
        insertPortfolio(
                TARGET_PORTFOLIO_ID,
                "PFTARGET",
                "USER",
                OWNER_ID,
                "STANDARD",
                "ACTIVE",
                "PUBLISHED",
                targetConfigJson(),
                0L
        );
    }

    /** 写入一个作品集测试行。 */
    private void insertPortfolio(
            long id,
            String shareCode,
            String ownerType,
            long ownerId,
            String templateType,
            String status,
            String publicationStatus,
            String publishedConfigJson,
            long deleted
    ) {
        jdbcTemplate.update("""
                        INSERT INTO wf_portfolio (
                          id, share_code, owner_type, owner_id, template_type, status,
                          schema_version, published_config_json, published_revision,
                          publication_status, current_revision, created_at, updated_at, deleted, version
                        ) VALUES (?, ?, ?, ?, ?, ?, 'standard-personal-v1', ?, 1, ?, 1,
                                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0)
                        """,
                id,
                shareCode,
                ownerType,
                ownerId,
                templateType,
                status,
                publishedConfigJson,
                publicationStatus,
                deleted
        );
    }

    /** 来源作品集配置。 */
    private String sourceConfigJson() {
        PortfolioConfigDto config = standardConfig("来源作品集");
        PortfolioConfigDto.Component hyperlink = new PortfolioConfigDto.Component();
        hyperlink.setComponentKey("c_link");
        hyperlink.setComponentType("HYPERLINK");
        hyperlink.setSortOrder(1000);
        hyperlink.setEnabled(true);
        Map<String, Object> hyperlinkConfig = new LinkedHashMap<>();
        hyperlinkConfig.put("workId", 11L);
        hyperlinkConfig.put("actionType", "INTERNAL_PORTFOLIO");
        hyperlinkConfig.put("targetPortfolioId", TARGET_PORTFOLIO_ID);
        hyperlinkConfig.put("showClickIcon", true);
        hyperlinkConfig.put("iconPosition", "OVERLAY");
        hyperlink.setConfig(hyperlinkConfig);
        config.setComponents(List.of(hyperlink));
        return JSON.toJSONString(config);
    }

    /** 目标作品集配置。 */
    private String targetConfigJson() {
        PortfolioConfigDto config = standardConfig("目标作品集");
        PortfolioConfigDto.Component profile = new PortfolioConfigDto.Component();
        profile.setComponentKey("c_profile");
        profile.setComponentType("PROFILE");
        profile.setSortOrder(1000);
        profile.setEnabled(true);
        profile.setConfig(Map.of());
        config.setComponents(List.of(profile));
        return JSON.toJSONString(config);
    }

    /** 构造标准个人作品集配置。 */
    private PortfolioConfigDto standardConfig(String title) {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        PortfolioConfigDto.Share share = new PortfolioConfigDto.Share();
        share.setTitle(title);
        config.setShare(share);
        return config;
    }

    /** 目标作品集状态。 */
    private enum TargetState {
        VALID,
        OTHER_OWNER,
        TEAM_OWNER,
        DISABLED,
        LOGICALLY_DELETED,
        OFFLINE,
        EMPTY_PUBLISHED_CONFIG,
        CORRUPT_PUBLISHED_CONFIG,
        ADVANCED_TEMPLATE
    }
}
