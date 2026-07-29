package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentValidator;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Fail.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队二维码联系组件的独立契约测试。
 */
class TeamQrContactComponentTest {

    /** 二维码联系组件生产源码目录。 */
    private static final Path QR_CONTACT_SOURCE = Path.of(
            "src/main/java/com/jxc/wefolio/service/teamportfolio/component/qrcontact");

    /** 二维码联系配置模型全限定类名。 */
    private static final String CONFIG_CLASS_NAME =
            "com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentConfig";

    /** 二维码联系校验器全限定类名。 */
    private static final String VALIDATOR_CLASS_NAME =
            "com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentValidator";

    /** 二维码联系渲染器全限定类名。 */
    private static final String RENDERER_CLASS_NAME =
            "com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentRenderer";

    /** 二维码联系引用提取器全限定类名。 */
    private static final String EXTRACTOR_CLASS_NAME =
            "com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentReferenceExtractor";

    /** 组件键。 */
    private static final String COMPONENT_KEY = "team-qr-contact-1";

    /** 组件路径。 */
    private static final String COMPONENT_PATH = "components[4]";

    /** 团队 ID。 */
    private static final long TEAM_ID = 11L;

    /** 作品集 ID。 */
    private static final long PORTFOLIO_ID = 21L;

    /** 自定义二维码来源。 */
    private static final String CUSTOM_QR_SOURCE = "CUSTOM";

    /** 个人资料二维码来源，仅用于验证团队组件拒绝该值。 */
    private static final String PROFILE_QR_SOURCE = "PROFILE";

    /** 二维码来源键。 */
    private static final String QR_URL_SOURCE_KEY = "qrUrlSource";

    /** 二维码地址键。 */
    private static final String QR_URL_KEY = "qrUrl";

    /** 配置字段顺序。 */
    private static final List<String> CONFIG_KEYS = List.of(QR_URL_SOURCE_KEY, QR_URL_KEY);

    /** 配置模型只能拥有的字段。 */
    private static final Set<String> CONFIG_FIELDS = Set.copyOf(CONFIG_KEYS);

    /** 有效二维码地址。 */
    private static final String QR_URL = "https://cdn.example.com/team-qr.png";

    /** 统一上下文异常提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "二维码联系上下文无效";

    /** 二维码来源异常提示。 */
    private static final String QR_URL_SOURCE_INVALID_MESSAGE = "二维码联系仅支持自定义二维码";

    /** 二维码地址异常提示。 */
    private static final String QR_URL_REQUIRED_MESSAGE = "二维码联系二维码不能为空";

    /** 当前组件生产包。 */
    private static final String OWN_COMPONENT_PACKAGE =
            "com.jxc.wefolio.service.teamportfolio.component.qrcontact";

    /** 唯一允许引用的团队组件上下文。 */
    private static final String COMPONENT_CONTEXT_CLASS =
            "com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext";

    /** 二维码组件唯一允许依赖的团队素材基础服务。 */
    private static final String TEAM_ASSET_SERVICE_CLASS =
            "com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService";

    /** Java 导入语句解析规则。 */
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)+)\\s*;");

    /** WeFolio 全限定名解析规则。 */
    private static final Pattern WEFOLIO_FQN_PATTERN = Pattern.compile(
            "\\bcom\\.jxc\\.wefolio(?:\\.[A-Za-z_$][\\w$]*|\\.\\*)+");

    /** 禁止直接实例化 Spring 渲染器的规则。 */
    private static final Pattern RENDERER_INSTANTIATION_PATTERN = Pattern.compile(
            "\\bnew\\s+(?:" + OWN_COMPONENT_PACKAGE.replace(".", "\\.")
                    + "\\.)?TeamQrContactComponentRenderer\\s*\\(");

    /** 禁止个人资料来源分支的规则。 */
    private static final Pattern PROFILE_BRANCH_PATTERN = Pattern.compile("\\bprofile\\b", Pattern.CASE_INSENSITIVE);

    /** 禁止读取个人或团队资料二维码字段的规则。 */
    private static final Pattern PROFILE_QR_ACCESS_PATTERN = Pattern.compile(
            "wechatqrurl|contactqrurl", Pattern.CASE_INSENSITIVE);

    /**
     * 配置模型仅表达二维码来源和地址，三个运行时类必须由 Spring 管理。
     */
    @Test
    void configShouldOwnOnlyQrFieldsAndRuntimeClassesShouldBeComponents() {
        assertThat(fieldNames(loadClass(CONFIG_CLASS_NAME))).containsExactlyInAnyOrderElementsOf(CONFIG_FIELDS);
        assertThat(loadClass(VALIDATOR_CLASS_NAME).isAnnotationPresent(Component.class)).isTrue();
        assertThat(loadClass(RENDERER_CLASS_NAME).isAnnotationPresent(Component.class)).isTrue();
        assertThat(loadClass(EXTRACTOR_CLASS_NAME).isAnnotationPresent(Component.class)).isTrue();
    }

    /**
     * 配置模型不得公开固定编码，并让新实例默认使用 CUSTOM 来源。
     */
    @Test
    void configShouldKeepFixedCodesPrivateAndDefaultNewInstanceSource() {
        Class<?> configClass = loadClass(CONFIG_CLASS_NAME);
        assertThat(Stream.of(configClass.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList())
                .as("配置模型不得扩展公开静态 API")
                .isEmpty();
        assertThat(invokeNoArgs(newInstance(configClass), "getQrUrlSource"))
                .isEqualTo(CUSTOM_QR_SOURCE);
    }

    /**
     * 有效配置应被清理、按固定顺序输出，并与原始输入脱离。
     */
    @Test
    void validatorShouldNormalizeCustomConfigInStableDetachedOrder() {
        JSONObject input = JSON.parseObject("{\"title\":\"  联系我们  \",\"description\":17,"
                + "\"qrUrlSource\":\" CUSTOM \",\"qrUrl\":\" " + QR_URL + " \"}");

        JSONObject normalized = normalize(input, context());
        input.put(QR_URL_KEY, "https://changed.example.com/qr.png");

        assertThat(normalized.toJSONString()).isEqualTo("{\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\""
                + QR_URL + "\"}");
        assertThat(normalized.keySet()).containsExactlyElementsOf(CONFIG_KEYS);
    }

    /**
     * 完整写入归一化必须且只能调用一次团队素材验证。
     */
    @Test
    void fullNormalizationShouldValidateUploadedAssetExactlyOnce() {
        TeamPortfolioAssetService assetService = mock(TeamPortfolioAssetService.class);

        JSONObject normalized = normalize(validConfig(), context(), assetService);

        assertThat(normalized.getString(QR_URL_KEY)).isEqualTo(QR_URL);
        verify(assetService).validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, QR_URL);
    }

    /**
     * 渲染与引用提取只能执行无 I/O 结构归一化，即使素材服务不可用也必须成功。
     */
    @Test
    void renderAndExtractShouldNeverAccessAssetService() {
        TeamPortfolioAssetService assetService = mock(TeamPortfolioAssetService.class);
        doThrow(new BusinessException("不应访问素材服务"))
                .when(assetService).validateUploadedImageUrl(anyLong(), anyLong(), any());

        JSONObject rendered = render(validConfig(), context(), assetService);
        List<PortfolioReferenceEntity> references = extract(
                COMPONENT_KEY, COMPONENT_PATH, validConfig(), context(), assetService);

        assertThat(rendered.getString(QR_URL_KEY)).isEqualTo(QR_URL);
        assertThat(references).hasSize(1);
        verifyNoInteractions(assetService);
    }

    /**
     * 二维码组件必须通过真实团队素材边界验证当前团队、作品集路径和 COS 对象头。
     */
    @Test
    void validatorShouldCollaborateWithRealTeamAssetBoundary() {
        TeamPortfolioAccessService accessService = mock(TeamPortfolioAccessService.class);
        TeamEntityMapper teamMapper = mock(TeamEntityMapper.class);
        CosService cosService = mock(CosService.class);
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        team.setUniqueCode("TM2048");
        String objectKey = "TM2048/protfolio/qr-contact-21-20260712153120-b2c3d4e5.png";
        String publicUrl = "https://cdn.example.com/" + objectKey;
        when(teamMapper.selectById(TEAM_ID)).thenReturn(team);
        when(cosService.publicUrl(objectKey)).thenReturn(publicUrl);
        when(cosService.headObject(objectKey)).thenReturn(new CosService.ObjectHead("image/png", 1024L));
        TeamPortfolioAssetService assetService = new TeamPortfolioAssetService(
                accessService, teamMapper, cosService);
        TeamQrContactComponentValidator validator = new TeamQrContactComponentValidator(assetService);
        JSONObject config = JSON.parseObject(
                "{\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\"" + publicUrl + "\"}");

        assertThat(validator.normalizeAndValidate(config, context()).getString(QR_URL_KEY)).isEqualTo(publicUrl);
        verify(cosService).headObject(objectKey);
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"qrUrlSource\":\"CUSTOM\","
                        + "\"qrUrl\":\"https://external.example.com/qr.png\"}"), context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集图片未完成上传或不可用");
    }

    /**
     * 二维码来源必须显式为字符串 CUSTOM，任何其他输入均不得默认回退。
     */
    @Test
    void validatorShouldRejectEveryInvalidQrUrlSource() {
        for (JSONObject invalidConfig : invalidSourceConfigs()) {
            assertBusinessException(() -> normalize(invalidConfig, context()), QR_URL_SOURCE_INVALID_MESSAGE);
        }
    }

    /**
     * 二维码地址必须显式为非空字符串，遗留标题和说明字段必须丢弃。
     */
    @Test
    void validatorShouldRequireQrUrlAndDiscardLegacyDisplayText() {
        for (JSONObject invalidConfig : invalidQrUrlConfigs()) {
            assertBusinessException(() -> normalize(invalidConfig, context()), QR_URL_REQUIRED_MESSAGE);
        }

        JSONObject normalized = normalize(JSON.parseObject("{\"title\":null,\"description\":\"  说明  \","
                + "\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\"" + QR_URL + "\"}"), context());
        assertThat(normalized.toJSONString()).isEqualTo("{\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\""
                + QR_URL + "\"}");
    }

    /**
     * 校验器必须统一拒绝空上下文、非正业务标识和负版本号。
     */
    @Test
    void validatorShouldRejectEveryInvalidComponentContext() {
        assertBusinessException(() -> normalize(validConfig(), null), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> normalize(validConfig(),
                new TeamPortfolioComponentContext(0L, PORTFOLIO_ID, 0)), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> normalize(validConfig(),
                new TeamPortfolioComponentContext(TEAM_ID, 0L, 0)), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> normalize(validConfig(),
                new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, -1)), CONTEXT_INVALID_MESSAGE);
    }

    /**
     * 渲染器必须对所有原始坏配置重新校验，而不是信任调用方已归一化。
     */
    @Test
    void rendererShouldRevalidateEveryInvalidInputAndReturnDetachedQrSnapshot() {
        for (JSONObject invalidConfig : invalidSourceConfigs()) {
            assertBusinessException(() -> render(invalidConfig, context()), QR_URL_SOURCE_INVALID_MESSAGE);
        }
        for (JSONObject invalidConfig : invalidQrUrlConfigs()) {
            assertBusinessException(() -> render(invalidConfig, context()), QR_URL_REQUIRED_MESSAGE);
        }

        JSONObject normalized = normalize(validConfig(), context());
        JSONObject rendered = render(normalized, context());
        normalized.put(QR_URL_KEY, "https://changed.example.com/qr.png");

        assertThat(rendered.toJSONString()).isEqualTo("{\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\""
                + QR_URL + "\"}");
        assertThat(rendered.keySet()).containsExactlyElementsOf(CONFIG_KEYS);
    }

    /**
     * 渲染器必须通过校验器复用完整上下文规则。
     */
    @Test
    void rendererShouldReuseValidatorContextRules() {
        assertBusinessException(() -> render(validConfig(), null), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> render(validConfig(),
                new TeamPortfolioComponentContext(0L, PORTFOLIO_ID, 0)), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> render(validConfig(),
                new TeamPortfolioComponentContext(TEAM_ID, 0L, 0)), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> render(validConfig(),
                new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, -1)), CONTEXT_INVALID_MESSAGE);
    }

    /**
     * 引用提取器应生成唯一二维码资产引用，且快照不包含个人资料数据。
     */
    @Test
    void extractorShouldCreateOneExactTeamQrReference() {
        List<PortfolioReferenceEntity> references = extract(COMPONENT_KEY, COMPONENT_PATH, validConfig(), context());

        assertThat(references).hasSize(1);
        PortfolioReferenceEntity reference = references.getFirst();
        assertThat(reference.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(reference.getReferenceType()).isEqualTo(ReferenceTypeDict.QR_CODE_ASSET.getCode());
        assertThat(reference.getReferenceId()).isEqualTo(TEAM_ID);
        assertThat(reference.getComponentKey()).isEqualTo(COMPONENT_KEY);
        assertThat(reference.getComponentPath()).isEqualTo(COMPONENT_PATH + ".config.qrUrl");
        assertThat(reference.getSortOrder()).isZero();
        assertThat(reference.getIsValid()).isEqualTo(1);
        assertThat(reference.getConfigScope()).isNull();
        assertThat(JSON.parseObject(reference.getSnapshotJson()).toJSONString())
                .isEqualTo("{\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\"" + QR_URL + "\"}");
    }

    /**
     * 引用提取器必须在渲染前拒绝空定位信息与无效团队作品集上下文。
     */
    @Test
    void extractorShouldRejectInvalidLocationAndContextBeforeCreatingReference() {
        assertBusinessException(() -> extract(" ", COMPONENT_PATH, null, null), "二维码联系组件键不能为空");
        assertBusinessException(() -> extract(COMPONENT_KEY, " ", null, null), "二维码联系组件路径不能为空");
        assertBusinessException(() -> extract(COMPONENT_KEY, COMPONENT_PATH, validConfig(), null),
                CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> extract(COMPONENT_KEY, COMPONENT_PATH, validConfig(),
                new TeamPortfolioComponentContext(0L, PORTFOLIO_ID, 1)), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> extract(COMPONENT_KEY, COMPONENT_PATH, validConfig(),
                new TeamPortfolioComponentContext(TEAM_ID, 0L, 1)), CONTEXT_INVALID_MESSAGE);
        assertBusinessException(() -> extract(COMPONENT_KEY, COMPONENT_PATH, validConfig(),
                new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, -1)), CONTEXT_INVALID_MESSAGE);
    }

    /**
     * 组件包只能拥有四个生产文件，且源码不得跨越独立组件边界。
     */
    @Test
    void productionPackageShouldContainExactlyFourIsolatedJavaFiles() throws Exception {
        try (Stream<Path> files = Files.list(QR_CONTACT_SOURCE)) {
            assertThat(files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .toList())
                    .containsExactlyInAnyOrder(
                            "TeamQrContactComponentConfig.java",
                            "TeamQrContactComponentValidator.java",
                            "TeamQrContactComponentRenderer.java",
                            "TeamQrContactComponentReferenceExtractor.java");
        }

        try (Stream<Path> files = Files.walk(QR_CONTACT_SOURCE)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertIsolatedSource(Files.readString(sourceFile));
            }
        }
    }

    /**
     * 源码隔离扫描必须拦截导入、全限定引用、直接构造和资料二维码访问的所有要求变体。
     */
    @Test
    void isolationScannerShouldRejectRepresentativeNonFixedCounterexamples() {
        assertThatThrownBy(() -> assertIsolatedSource("import com.jxc.wefolio.dto.*;"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource(
                "import static com.jxc.wefolio.service.OwnerSelfVisitService.*;"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource(
                "com.jxc.wefolio.service.OwnerSelfVisitService service;"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource(
                "com.jxc.wefolio.service.teamportfolio.component.carousel.AnyCarouselType value;"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource("new TeamQrContactComponentRenderer(null);"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource(
                "new com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentRenderer(null);"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource("if (source.equals(\"profile\")) { }"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource("record.getwechatQrUrl();"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertIsolatedSource("json.getString(\"contactQrUrl\");"))
                .isInstanceOf(AssertionError.class);

        assertThatCode(() -> assertIsolatedSource("new TeamQrContactComponentConfig();"))
                .doesNotThrowAnyException();
        assertThatCode(() -> assertIsolatedSource(
                "new com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentConfig();"))
                .doesNotThrowAnyException();
    }

    /**
     * 调用二维码配置归一化接口。
     *
     * @param config 原始配置
     * @param context 团队作品集上下文
     * @return 归一化配置
     */
    private JSONObject normalize(JSONObject config, TeamPortfolioComponentContext context) {
        return normalize(config, context, mock(TeamPortfolioAssetService.class));
    }

    /**
     * 使用指定素材服务调用二维码配置归一化接口。
     *
     * @param config 原始配置
     * @param context 团队作品集上下文
     * @param assetService 团队素材服务
     * @return 归一化配置
     */
    private JSONObject normalize(
            JSONObject config,
            TeamPortfolioComponentContext context,
            TeamPortfolioAssetService assetService
    ) {
        Object validator = newInstance(loadClass(VALIDATOR_CLASS_NAME), assetService);
        return invoke(validator, "normalizeAndValidate", JSONObject.class,
                TeamPortfolioComponentContext.class, config, context);
    }

    /**
     * 调用二维码渲染接口。
     *
     * @param config 原始或归一化配置
     * @param context 团队作品集上下文
     * @return 渲染快照
     */
    private JSONObject render(JSONObject config, TeamPortfolioComponentContext context) {
        return render(config, context, mock(TeamPortfolioAssetService.class));
    }

    /**
     * 使用指定素材服务调用二维码渲染接口。
     *
     * @param config 原始或归一化配置
     * @param context 团队作品集上下文
     * @param assetService 团队素材服务
     * @return 渲染快照
     */
    private JSONObject render(
            JSONObject config,
            TeamPortfolioComponentContext context,
            TeamPortfolioAssetService assetService
    ) {
        Object validator = newInstance(loadClass(VALIDATOR_CLASS_NAME), assetService);
        Object renderer = newInstance(loadClass(RENDERER_CLASS_NAME), validator);
        return invoke(renderer, "render", JSONObject.class, TeamPortfolioComponentContext.class, config, context);
    }

    /**
     * 调用二维码引用提取接口。
     *
     * @param componentKey 组件键
     * @param componentPath 组件路径
     * @param config 原始或归一化配置
     * @param context 团队作品集上下文
     * @return 二维码引用
     */
    @SuppressWarnings("unchecked")
    private List<PortfolioReferenceEntity> extract(String componentKey, String componentPath, JSONObject config,
                                                    TeamPortfolioComponentContext context) {
        return extract(componentKey, componentPath, config, context, mock(TeamPortfolioAssetService.class));
    }

    /**
     * 使用指定素材服务调用二维码引用提取接口。
     *
     * @param componentKey 组件键
     * @param componentPath 组件路径
     * @param config 原始或归一化配置
     * @param context 团队作品集上下文
     * @param assetService 团队素材服务
     * @return 二维码引用
     */
    @SuppressWarnings("unchecked")
    private List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject config,
            TeamPortfolioComponentContext context,
            TeamPortfolioAssetService assetService
    ) {
        Object validator = newInstance(loadClass(VALIDATOR_CLASS_NAME), assetService);
        Object renderer = newInstance(loadClass(RENDERER_CLASS_NAME), validator);
        Object extractor = newInstance(loadClass(EXTRACTOR_CLASS_NAME), renderer);
        return (List<PortfolioReferenceEntity>) invoke(extractor, "extract", String.class, String.class,
                JSONObject.class, TeamPortfolioComponentContext.class, componentKey, componentPath, config, context);
    }

    /**
     * 创建有效配置。
     *
     * @return 有效二维码配置
     */
    private JSONObject validConfig() {
        return JSON.parseObject("{\"qrUrlSource\":\"CUSTOM\",\"qrUrl\":\"" + QR_URL + "\"}");
    }

    /**
     * 创建完整的非法二维码来源矩阵。
     *
     * @return 非法来源配置
     */
    private List<JSONObject> invalidSourceConfigs() {
        return List.of(
                configWithout(QR_URL_SOURCE_KEY),
                configWith(QR_URL_SOURCE_KEY, null),
                configWith(QR_URL_SOURCE_KEY, " "),
                configWith(QR_URL_SOURCE_KEY, PROFILE_QR_SOURCE),
                configWith(QR_URL_SOURCE_KEY, "OTHER"),
                configWith(QR_URL_SOURCE_KEY, 1));
    }

    /**
     * 创建完整的非法二维码地址矩阵。
     *
     * @return 非法二维码地址配置
     */
    private List<JSONObject> invalidQrUrlConfigs() {
        return List.of(
                configWithout(QR_URL_KEY),
                configWith(QR_URL_KEY, null),
                configWith(QR_URL_KEY, " \t "),
                configWith(QR_URL_KEY, 1));
    }

    /**
     * 创建缺少一个键的有效配置。
     *
     * @param missingKey 缺少的键
     * @return 配置对象
     */
    private JSONObject configWithout(String missingKey) {
        JSONObject config = validConfig();
        config.remove(missingKey);
        return config;
    }

    /**
     * 创建替换一个键的有效配置。
     *
     * @param key 配置键
     * @param value 配置值
     * @return 配置对象
     */
    private JSONObject configWith(String key, Object value) {
        JSONObject config = validConfig();
        config.put(key, value);
        return config;
    }

    /**
     * 创建标准上下文。
     *
     * @return 团队作品集上下文
     */
    private TeamPortfolioComponentContext context() {
        return new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3);
    }

    /**
     * 断言业务异常消息。
     *
     * @param action 待执行操作
     * @param message 预期消息
     */
    private void assertBusinessException(ThrowingAction action, String message) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).hasMessage(message);
    }

    /**
     * 通过反射加载组件类，确保组件不存在时以测试失败而非编译错误体现 RED。
     *
     * @param className 全限定类名
     * @return 组件类
     */
    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            fail("二维码联系组件类未创建：" + className, exception);
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 调用无参数公开方法。
     *
     * @param target 目标实例
     * @param methodName 方法名
     * @return 方法结果
     */
    private Object invokeNoArgs(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("二维码联系配置方法不可用：" + methodName, exception);
        }
    }

    /**
     * 通过构造器创建组件，确保 Spring 组件之间使用构造器依赖。
     *
     * @param componentClass 组件类
     * @param dependencies 构造器依赖
     * @return 组件实例
     */
    private Object newInstance(Class<?> componentClass, Object... dependencies) {
        try {
            for (Constructor<?> constructor : componentClass.getConstructors()) {
                if (constructor.getParameterCount() == dependencies.length) {
                    return constructor.newInstance(dependencies);
                }
            }
            fail("二维码联系组件缺少匹配构造器：" + componentClass.getName());
            throw new IllegalStateException(componentClass.getName());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("二维码联系组件构造失败：" + componentClass.getName(), exception);
        }
    }

    /**
     * 通过反射执行固定接口，并抛出底层业务异常。
     *
     * @param target 组件实例
     * @param methodName 方法名
     * @param firstType 第一个参数类型
     * @param secondType 第二个参数类型
     * @param firstValue 第一个参数值
     * @param secondValue 第二个参数值
     * @return 方法结果
     */
    @SuppressWarnings("unchecked")
    private <T> T invoke(Object target, String methodName, Class<?> firstType, Class<?> secondType,
                         Object firstValue, Object secondValue) {
        return (T) invokeMethod(target, methodName, new Class<?>[]{firstType, secondType},
                new Object[]{firstValue, secondValue});
    }

    /**
     * 通过反射执行四参数固定接口，并抛出底层业务异常。
     *
     * @param target 组件实例
     * @param methodName 方法名
     * @param firstType 第一个参数类型
     * @param secondType 第二个参数类型
     * @param thirdType 第三个参数类型
     * @param fourthType 第四个参数类型
     * @param firstValue 第一个参数值
     * @param secondValue 第二个参数值
     * @param thirdValue 第三个参数值
     * @param fourthValue 第四个参数值
     * @return 方法结果
     */
    @SuppressWarnings("unchecked")
    private <T> T invoke(Object target, String methodName, Class<?> firstType, Class<?> secondType,
                         Class<?> thirdType, Class<?> fourthType, Object firstValue, Object secondValue,
                         Object thirdValue, Object fourthValue) {
        return (T) invokeMethod(target, methodName, new Class<?>[]{firstType, secondType, thirdType, fourthType},
                new Object[]{firstValue, secondValue, thirdValue, fourthValue});
    }

    /**
     * 执行反射方法并恢复目标业务异常。
     *
     * @param target 组件实例
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param arguments 参数值
     * @return 方法结果
     */
    private Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError("二维码联系组件调用失败", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("二维码联系组件接口不符合固定契约", exception);
        }
    }

    /**
     * 获取类的实例字段名称。
     *
     * @param type 类
     * @return 字段名集合
     */
    private Set<String> fieldNames(Class<?> type) {
        return Stream.of(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 扫描源码是否违规跨越二维码联系组件的独立边界。
     *
     * @param source 源码文本
     */
    private void assertIsolatedSource(String source) {
        Matcher importMatcher = IMPORT_PATTERN.matcher(source);
        while (importMatcher.find()) {
            assertAllowedProjectReference(importMatcher.group(1));
        }

        Matcher fqnMatcher = WEFOLIO_FQN_PATTERN.matcher(source);
        while (fqnMatcher.find()) {
            assertAllowedProjectReference(fqnMatcher.group());
        }

        assertThat(RENDERER_INSTANTIATION_PATTERN.matcher(source).find())
                .as("禁止直接实例化二维码联系渲染器")
                .isFalse();
        assertThat(PROFILE_BRANCH_PATTERN.matcher(source).find())
                .as("禁止个人资料二维码来源分支")
                .isFalse();
        assertThat(PROFILE_QR_ACCESS_PATTERN.matcher(source).find())
                .as("禁止读取个人或团队资料二维码字段")
                .isFalse();
    }

    /**
     * 校验 WeFolio 工程内引用是否在组件白名单内。
     *
     * @param reference 导入或全限定引用
     */
    private void assertAllowedProjectReference(String reference) {
        if (!reference.startsWith("com.jxc.wefolio.")) {
            return;
        }
        boolean allowed = reference.equals(COMPONENT_CONTEXT_CLASS)
                || reference.equals(TEAM_ASSET_SERVICE_CLASS)
                || reference.equals(OWN_COMPONENT_PACKAGE)
                || reference.startsWith(OWN_COMPONENT_PACKAGE + ".")
                || reference.startsWith("com.jxc.wefolio.dto.teamportfolio.")
                || reference.startsWith("com.jxc.wefolio.entity.")
                || reference.startsWith("com.jxc.wefolio.dict.")
                || reference.startsWith("com.jxc.wefolio.exception.");
        assertThat(allowed)
                .as("二维码联系组件引用越界：%s", reference)
                .isTrue();
    }

    /**
     * 可抛出异常的断言操作。
     */
    @FunctionalInterface
    private interface ThrowingAction {

        /** 执行操作。 */
        void run();
    }
}
