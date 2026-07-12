package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.service.EncryptedAuthTokenService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队预留联系信息组件安全契约测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamContactFormComponentServiceTest {

    /** 团队 ID。 */
    private static final long TEAM_ID = 201L;

    /** 作品集 ID。 */
    private static final long PORTFOLIO_ID = 101L;

    /** 维护者 ID。 */
    private static final long USER_ID = 301L;

    /** 访问记录 ID。 */
    private static final long VISIT_RECORD_ID = 401L;

    /** 线索 ID。 */
    private static final long LEAD_ID = 501L;

    /** 团队作品集分享编码。 */
    private static final String SHARE_CODE = "TPF001";

    /** 测试手机号。 */
    private static final String PHONE = "13800138000";

    /** 测试微信号。 */
    private static final String WECHAT = "wefolio";

    /** 手机号外层令牌前缀。 */
    private static final String PHONE_TOKEN_PREFIX = "wf-team-contact-phone:";

    /** 微信号外层令牌前缀。 */
    private static final String WECHAT_TOKEN_PREFIX = "wf-team-contact-wechat:";

    /** 手机号认证 payload 前缀。 */
    private static final String PHONE_PAYLOAD_PREFIX = "v1|PHONE|";

    /** 微信号认证 payload 前缀。 */
    private static final String WECHAT_PAYLOAD_PREFIX = "v1|WECHAT|";

    /** 解密失败固定文案。 */
    private static final String DECRYPTION_FAILED_MESSAGE = "团队预留联系信息解密失败";

    /** 可读取角色。 */
    private static final Set<String> READ_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode(), TeamRoleDict.MEMBER.getCode());

    /** 可更新跟进角色。 */
    private static final Set<String> UPDATE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());

    /** 联系线索 Mapper。 */
    @Mock
    private ContactLeadEntityMapper contactLeadEntityMapper;

    /** 作品集 Mapper。 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 访问记录 Mapper。 */
    @Mock
    private VisitRecordEntityMapper visitRecordEntityMapper;

    /** 团队访问控制服务。 */
    @Mock
    private TeamPortfolioAccessService teamPortfolioAccessService;

    /** 初始化 MyBatis-Plus Lambda 查询元数据。 */
    @BeforeAll
    static void initializeTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PortfolioEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ContactLeadEntity.class);
    }

    /**
     * 缺失、非集合、空集合和全空白集合都应回落默认字段。
     *
     * @param caseName 用例名称
     * @param config 原始配置
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyFieldCases")
    void validatorShouldDefaultEveryEffectivelyEmptyFieldsCase(String caseName, JSONObject config) {
        JSONObject normalized = validator().normalizeAndValidate(config, componentContext());

        assertThat(normalized.getList("fields", String.class))
                .containsExactly("contactName", "phone", "wechat", "needs");
    }

    /**
     * 非空字段集合仍必须同时满足联系人和联系方式约束。
     *
     * @param caseName 用例名称
     * @param fields 非空字段集合
     * @param message 预期错误文案
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNonEmptyFieldCases")
    void validatorShouldRejectNonEmptyFieldsMissingRequiredSemantics(
            String caseName,
            List<Object> fields,
            String message
    ) {
        assertThatThrownBy(() -> validator().normalizeAndValidate(
                JSONObject.of("fields", fields), componentContext()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(message);
    }

    /**
     * 字段应去空白、去重、保序，并按字符串转换语义接收非字符串项。
     */
    @Test
    void validatorShouldNormalizeFieldsAndDisplayText() {
        JSONObject config = JSONObject.of(
                "title", " 联系我们 ",
                "description", " 请留下方式 ",
                "displayMode", " INLINE_FORM ",
                "fields", List.of(" phone ", "contactName", "phone", 88, "wechat", " "),
                "ignored", "drop-me");

        JSONObject normalized = validator().normalizeAndValidate(config, componentContext());

        assertThat(normalized.keySet()).containsExactlyInAnyOrder("title", "description", "displayMode", "fields");
        assertThat(normalized.getString("title")).isEqualTo("联系我们");
        assertThat(normalized.getString("description")).isEqualTo("请留下方式");
        assertThat(normalized.getString("displayMode")).isEqualTo("INLINE_FORM");
        assertThat(normalized.getList("fields", String.class))
                .containsExactly("phone", "contactName", "88", "wechat");
    }

    /**
     * Validator 应统一拒绝非法组件上下文。
     *
     * @param caseName 用例名称
     * @param context 非法上下文
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidComponentContexts")
    void validatorShouldRejectEveryInvalidContext(String caseName, TeamPortfolioComponentContext context) {
        assertThatThrownBy(() -> validator().normalizeAndValidate(new JSONObject(), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息组件上下文不正确");
    }

    /**
     * Renderer 应通过 Validator 重校验上下文和配置，并返回脱离输入的快照。
     */
    @Test
    void rendererShouldReuseValidatorAndDetachSnapshot() {
        TeamContactFormComponentValidator validator = org.mockito.Mockito.spy(validator());
        TeamContactFormComponentRenderer renderer = new TeamContactFormComponentRenderer(validator);
        JSONObject input = JSONObject.of("title", " 标题 ", "fields", List.of("contactName", "phone"));

        JSONObject rendered = renderer.render(input, componentContext());
        verify(validator).normalizeAndValidate(inputWithOriginalTitle(), componentContext());
        input.put("title", "已修改");

        assertThat(rendered.getString("title")).isEqualTo("标题");
        assertThatThrownBy(() -> renderer.render(rendered, new TeamPortfolioComponentContext(0, PORTFOLIO_ID, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息组件上下文不正确");
    }

    /**
     * Extractor 应校验键和路径，通过 Renderer/Validator 重校验，并返回不可变空列表。
     */
    @Test
    void extractorShouldReuseValidationAndReturnImmutableEmptyReferences() {
        TeamContactFormComponentRenderer renderer = new TeamContactFormComponentRenderer(validator());
        TeamContactFormComponentReferenceExtractor extractor = new TeamContactFormComponentReferenceExtractor(renderer);
        JSONObject config = JSONObject.of("fields", List.of("contactName", "wechat"));

        List<PortfolioReferenceEntity> references = extractor.extract(
                "contact-1", "components[0]", config, componentContext());

        assertThat(references).isEmpty();
        assertThatThrownBy(() -> references.add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> extractor.extract("", "components[0]", config, componentContext()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息组件标识不能为空");
        assertThatThrownBy(() -> extractor.extract("contact-1", " ", config, componentContext()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息组件路径不能为空");
        assertThatThrownBy(() -> extractor.extract(
                "contact-1", "components[0]", config, new TeamPortfolioComponentContext(TEAM_ID, 0, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息组件上下文不正确");
    }

    /**
     * 提交查询必须包含全部团队公开身份谓词及参数。
     */
    @Test
    void submitShouldConstrainPortfolioQueryWithEveryIdentityPredicate() {
        stubSuccessfulSubmit(LEAD_ID);

        service().submit(SHARE_CODE, request(), visitor());

        ArgumentCaptor<Wrapper<PortfolioEntity>> captor = wrapperCaptor();
        verify(portfolioEntityMapper).selectOne(captor.capture());
        LambdaQueryWrapper<PortfolioEntity> wrapper = castQuery(captor.getValue());
        String sql = normalizeSql(wrapper.getSqlSegment());
        assertThat(sql).contains("share_code", "owner_type", "template_type", "status",
                "schema_version", "publication_status");
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                SHARE_CODE,
                PortfolioOwnerTypeDict.TEAM.getCode(),
                PortfolioTemplateTypeDict.STANDARD.getCode(),
                PortfolioStatusDict.ACTIVE.getCode(),
                "standard-team-v1",
                PortfolioPublicationStatusDict.PUBLISHED.getCode());
    }

    /**
     * Java 复验必须拒绝每一个无效作品集身份谓词。
     *
     * @param invalidCase 无效作品集用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPortfolioCases")
    void submitShouldRejectEveryInvalidPortfolioIdentity(InvalidPortfolioCase invalidCase) {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(invalidCase.mutator().apply(teamPortfolio()));

        assertThatThrownBy(() -> service(crypto).submit(SHARE_CODE, request(), visitor()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前团队作品集暂未开放访问");

        verifyNoInteractions(visitRecordEntityMapper, contactLeadEntityMapper, crypto);
    }

    /**
     * 发布 JSON 的 schema、启用组件及组件配置必须全部有效。
     *
     * @param caseName 用例名称
     * @param publishedConfig 非法发布 JSON
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPublishedConfigCases")
    void submitShouldRejectInvalidPublishedConfiguration(String caseName, String publishedConfig) {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        PortfolioEntity portfolio = teamPortfolio();
        portfolio.setPublishedConfigJson(publishedConfig);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);

        assertThatThrownBy(() -> service(crypto).submit(SHARE_CODE, request(), visitor()))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(visitRecordEntityMapper, contactLeadEntityMapper, crypto);
    }

    /**
     * 联系表单曝光校验必须由 contactform 包按精确 componentKey、enabled、type 和 config 负责且零写入。
     */
    @Test
    void validatePublishedComponentOwnsExactEnabledContactLookupWithoutWrites() {
        TeamContactFormComponentService service = service();

        service.validatePublishedComponent(teamPortfolio(), "contact-1");

        verifyNoInteractions(portfolioEntityMapper, visitRecordEntityMapper,
                contactLeadEntityMapper, teamPortfolioAccessService);
    }

    /**
     * 联系表单曝光的错 key、停用、错类型和非法配置必须在任何业务写入前拒绝。
     */
    @Test
    void validatePublishedComponentRejectsInvalidExactEnvelopeBeforeWrites() {
        PortfolioEntity disabled = teamPortfolio();
        disabled.setPublishedConfigJson(disabled.getPublishedConfigJson().replace(
                "\"enabled\":true", "\"enabled\":false"));
        PortfolioEntity wrongType = teamPortfolio();
        wrongType.setPublishedConfigJson(wrongType.getPublishedConfigJson().replace(
                "\"componentType\":\"CONTACT_FORM\"", "\"componentType\":\"SCHEDULE_QUERY\""));
        PortfolioEntity invalidConfig = teamPortfolio();
        invalidConfig.setPublishedConfigJson(invalidConfig.getPublishedConfigJson().replace(
                "[\"contactName\",\"phone\",\"wechat\",\"needs\"]", "[\"needs\"]"));

        assertThatThrownBy(() -> service().validatePublishedComponent(teamPortfolio(), "other-key"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().validatePublishedComponent(disabled, "contact-1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().validatePublishedComponent(wrongType, "contact-1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().validatePublishedComponent(invalidConfig, "contact-1"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(portfolioEntityMapper, visitRecordEntityMapper,
                contactLeadEntityMapper, teamPortfolioAccessService);
    }

    /**
     * 访问记录任一归属字段不匹配都必须在加密和线索 Mapper 前短路。
     *
     * @param invalidCase 非法访问记录用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVisitRecordCases")
    void submitShouldRejectEveryVisitOwnershipMismatch(InvalidVisitCase invalidCase) {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID))
                .thenReturn(invalidCase.mutator().apply(visitRecord()));

        assertThatThrownBy(() -> service(crypto).submit(SHARE_CODE, request(), visitor()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访问记录无效");

        verifyNoInteractions(contactLeadEntityMapper, crypto);
    }

    /**
     * VisitorContext 和访问记录任一 visitorId 为空时应允许通过。
     *
     * @param contextVisitorId 上下文访客 ID
     * @param recordVisitorId 记录访客 ID
     */
    @ParameterizedTest
    @MethodSource("nullableMatchingVisitorIds")
    void submitShouldAllowEitherVisitorIdToBeNull(Long contextVisitorId, Long recordVisitorId) {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        VisitRecordEntity record = visitRecord();
        record.setVisitorId(recordVisitorId);
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID)).thenReturn(record);
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(LEAD_ID);
            return 1;
        });

        TeamContactFormComponentService.SubmitResult result = service().submit(
                SHARE_CODE, request(), new VisitorContext(contextVisitorId, "visitor-key", "Bearer visitor"));

        assertThat(result.leadId()).isEqualTo(LEAD_ID);
    }

    /**
     * 真实 AES-GCM 密文应绑定 v1 和用途，且不超过数据库长度。
     */
    @Test
    void submitShouldEncryptVersionedPurposeBoundPayloads() {
        EncryptedAuthTokenService crypto = encryption();
        stubSuccessfulSubmit(LEAD_ID);

        service(crypto).submit(SHARE_CODE, request(), visitor());

        ArgumentCaptor<ContactLeadEntity> captor = ArgumentCaptor.forClass(ContactLeadEntity.class);
        verify(contactLeadEntityMapper).insert(captor.capture());
        ContactLeadEntity lead = captor.getValue();
        assertThat(lead.getPhoneCiphertext()).isNotEqualTo(PHONE).hasSizeLessThanOrEqualTo(512);
        assertThat(lead.getWechatCiphertext()).isNotEqualTo(WECHAT).hasSizeLessThanOrEqualTo(512);
        assertThat(crypto.decryptPayload(PHONE_TOKEN_PREFIX, lead.getPhoneCiphertext()))
                .isEqualTo(PHONE_PAYLOAD_PREFIX + PHONE);
        assertThat(crypto.decryptPayload(WECHAT_TOKEN_PREFIX, lead.getWechatCiphertext()))
                .isEqualTo(WECHAT_PAYLOAD_PREFIX + WECHAT);
        assertThat(lead.getPhoneLast4()).isNull();
        assertThat(lead.getWechatMaskHint()).isNull();
    }

    /**
     * 插入成功必须回填有效主键和提交时间。
     */
    @Test
    void submitShouldRejectSuccessfulInsertWithoutGeneratedId() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID)).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenReturn(1);

        assertThatThrownBy(() -> service().submit(SHARE_CODE, request(), visitor()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息保存失败");
    }

    /**
     * 插入零行和加密失败都不能返回成功。
     */
    @Test
    void submitShouldRejectInsertZeroAndEncryptionFailure() {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID)).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service().submit(SHARE_CODE, request(), visitor()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息保存失败");

        EncryptedAuthTokenService failingCrypto = mock(EncryptedAuthTokenService.class);
        when(failingCrypto.encryptPayload(anyString(), anyString()))
                .thenThrow(new BusinessException("共享加密失败"));
        assertThatThrownBy(() -> service(failingCrypto).submit(SHARE_CODE, request(), visitor()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("共享加密失败");
    }

    /**
     * 相同团队、作品集和幂等键的重复提交应复用已有线索并约束查询 wrapper。
     */
    @Test
    void submitShouldReuseSameScopedIdempotencyLeadAndConstrainDuplicateQuery() {
        DuplicateKeyException conflict = new DuplicateKeyException("duplicate");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID)).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenThrow(conflict);
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(existingDuplicate());

        TeamContactFormComponentService.SubmitResult result = service().submit(SHARE_CODE, request(), visitor());

        assertThat(result.leadId()).isEqualTo(LEAD_ID);
        ArgumentCaptor<Wrapper<ContactLeadEntity>> captor = wrapperCaptor();
        verify(contactLeadEntityMapper).selectOne(captor.capture());
        LambdaQueryWrapper<ContactLeadEntity> wrapper = castQuery(captor.getValue());
        assertThat(normalizeSql(wrapper.getSqlSegment()))
                .contains("idempotency_key", "owner_type", "owner_id", "portfolio_id", "limit 1 for update");
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                "lead-1", PortfolioOwnerTypeDict.TEAM.getCode(), TEAM_ID, PORTFOLIO_ID);
    }

    /**
     * Mapper 返回跨团队、跨作品集或跨幂等键实体时必须重抛原始冲突。
     *
     * @param mismatchCase 不匹配实体用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicateMismatchCases")
    void submitShouldRethrowOriginalDuplicateForMismatchedExistingLead(DuplicateMismatchCase mismatchCase) {
        DuplicateKeyException conflict = new DuplicateKeyException("duplicate");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID)).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenThrow(conflict);
        when(contactLeadEntityMapper.selectOne(any()))
                .thenReturn(mismatchCase.mutator().apply(existingDuplicate()));

        assertThatThrownBy(() -> service().submit(SHARE_CODE, request(), visitor()))
                .isSameAs(conflict);
    }

    /**
     * list 必须精确传递 OWNER、MANAGER、MEMBER 读取角色集合。
     */
    @Test
    void listShouldRequireExactReadableRoleSet() {
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(TeamRoleDict.MEMBER.getCode()));
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service().list(TEAM_ID, 2, 25, USER_ID);

        ArgumentCaptor<Set<String>> roles = roleSetCaptor();
        verify(teamPortfolioAccessService).requireTeamRole(eq(TEAM_ID), eq(USER_ID), roles.capture());
        assertThat(roles.getValue()).containsExactlyInAnyOrderElementsOf(READ_ROLES);
    }

    /**
     * list 应捕获正确分页、团队归属条件和稳定倒序。
     */
    @Test
    void listShouldUseBoundedPageAndExactOwnerSortWrapper() {
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(TeamRoleDict.OWNER.getCode()));
        when(contactLeadEntityMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ContactLeadEntity> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(100);
            return page;
        });

        TeamContactLeadResponse response = service().list(TEAM_ID, 2, 25, USER_ID);

        ArgumentCaptor<Page<ContactLeadEntity>> pageCaptor = pageCaptor();
        ArgumentCaptor<Wrapper<ContactLeadEntity>> wrapperCaptor = wrapperCaptor();
        verify(contactLeadEntityMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(25);
        assertThat(response.isHasMore()).isTrue();
        LambdaQueryWrapper<ContactLeadEntity> wrapper = castQuery(wrapperCaptor.getValue());
        String sql = normalizeSql(wrapper.getSqlSegment());
        assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(PortfolioOwnerTypeDict.TEAM.getCode(), TEAM_ID);
        assertThat(sql).endsWith("order by submitted_at desc,id desc");
    }

    /**
     * 三个有效角色都应得到完整未脱敏信息和完整团队作品集来源。
     *
     * @param role 团队角色
     */
    @ParameterizedTest
    @MethodSource("readableRoles")
    void listShouldReturnCompletePlaintextForEveryReadableRole(String role) {
        EncryptedAuthTokenService crypto = encryption();
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(role));
        Page<ContactLeadEntity> selected = new Page<>(1, 20);
        selected.setRecords(List.of(encryptedLead(crypto, LEAD_ID)));
        selected.setTotal(1);
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(selected);

        TeamContactLeadResponse response = service(crypto).list(TEAM_ID, 1, 20, USER_ID);

        assertThat(response.getItems()).hasSize(1);
        TeamContactLeadResponse.Item item = response.getItems().getFirst();
        assertThat(item.getLeadId()).isEqualTo(LEAD_ID);
        assertThat(item.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(item.getTeamName()).isEqualTo("映期团队");
        assertThat(item.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(item.getPortfolioTitle()).isEqualTo("团队婚礼作品集");
        assertThat(item.getPortfolioShareCode()).isEqualTo(SHARE_CODE);
        assertThat(item.getPhone()).isEqualTo(PHONE);
        assertThat(item.getWechat()).isEqualTo(WECHAT);
        assertThat(item.getSourceType()).isEqualTo("QR_CODE");
        assertThat(item.getSourceText()).isEqualTo("二维码");
        assertThat(item.getSubmittedAt()).isEqualTo(LocalDateTime.of(2026, 7, 11, 12, 0));
    }

    /**
     * AccessService 拒绝时线索 Mapper 与解密器必须零调用。
     */
    @Test
    void listShouldShortCircuitMapperAndCryptoWhenAccessDenied() {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenThrow(new BusinessException("拒绝访问"));

        assertThatThrownBy(() -> service(crypto).list(TEAM_ID, 1, 20, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("拒绝访问");

        verifyNoInteractions(contactLeadEntityMapper, crypto);
    }

    /**
     * Mapper 返回 null 或 records 为 null 时应安全返回空列表。
     */
    @Test
    void listShouldHandleNullPageAndNullRecords() {
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(TeamRoleDict.OWNER.getCode()));
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(null);

        assertThat(service().list(TEAM_ID, 1, 20, USER_ID).getItems()).isEmpty();

        Page<ContactLeadEntity> selected = new Page<>(1, 20);
        selected.setRecords(null);
        selected.setTotal(0);
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(selected);
        assertThat(service().list(TEAM_ID, 1, 20, USER_ID).getItems()).isEmpty();
    }

    /**
     * null 行和错误归属行必须在解密前过滤。
     */
    @Test
    void listShouldFilterNullAndWrongOwnerRowsBeforeDecrypt() {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(TeamRoleDict.MEMBER.getCode()));
        ContactLeadEntity wrongOwner = encryptedLead(encryption(), LEAD_ID);
        wrongOwner.setOwnerId(999L);
        Page<ContactLeadEntity> selected = new Page<>(1, 20);
        selected.setRecords(Arrays.asList(null, wrongOwner));
        selected.setTotal(2);
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(selected);

        TeamContactLeadResponse response = service(crypto).list(TEAM_ID, 1, 20, USER_ID);

        assertThat(response.getItems()).isEmpty();
        verifyNoInteractions(crypto);
    }

    /**
     * 共享解密异常应在组件边界转换为固定业务异常。
     */
    @Test
    void listShouldTranslateDecryptRuntimeFailureToBusinessException() {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(TeamRoleDict.OWNER.getCode()));
        Page<ContactLeadEntity> selected = new Page<>(1, 20);
        selected.setRecords(List.of(encryptedLead(encryption(), LEAD_ID)));
        selected.setTotal(1);
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(selected);
        when(crypto.decryptPayload(anyString(), anyString())).thenThrow(new IllegalStateException("cipher-sensitive"));

        assertThatThrownBy(() -> service(crypto).list(TEAM_ID, 1, 20, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DECRYPTION_FAILED_MESSAGE)
                .hasMessageNotContaining("cipher-sensitive");
    }

    /**
     * 替换外部前缀后，PHONE payload 仍不能作为 WECHAT 解密。
     */
    @Test
    void listShouldRejectCrossPurposeCiphertextAfterOuterPrefixReplacement() {
        EncryptedAuthTokenService crypto = encryption();
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(READ_ROLES)))
                .thenReturn(access(TeamRoleDict.OWNER.getCode()));
        ContactLeadEntity lead = encryptedLead(crypto, LEAD_ID);
        String phoneAsWechat = WECHAT_TOKEN_PREFIX
                + lead.getPhoneCiphertext().substring(PHONE_TOKEN_PREFIX.length());
        lead.setWechatCiphertext(phoneAsWechat);
        Page<ContactLeadEntity> selected = new Page<>(1, 20);
        selected.setRecords(List.of(lead));
        selected.setTotal(1);
        when(contactLeadEntityMapper.selectPage(any(), any())).thenReturn(selected);

        assertThatThrownBy(() -> service(crypto).list(TEAM_ID, 1, 20, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DECRYPTION_FAILED_MESSAGE);
    }

    /**
     * updateFollowStatus 必须声明异常回滚事务。
     *
     * @throws Exception 反射读取方法失败
     */
    @Test
    void updateFollowStatusShouldDeclareRollbackTransaction() throws Exception {
        Method method = TeamContactFormComponentService.class.getDeclaredMethod(
                "updateFollowStatus", long.class, long.class, String.class, String.class, long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    /**
     * MEMBER 不在更新角色集合中，且必须在查询线索前被拒绝。
     */
    @Test
    void updateFollowStatusShouldExcludeMemberAndShortCircuitLeadMapper() {
        ArgumentCaptor<Set<String>> roles = roleSetCaptor();
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), roles.capture()))
                .thenAnswer(invocation -> {
                    Set<String> requestedRoles = invocation.getArgument(2);
                    if (!requestedRoles.contains(TeamRoleDict.MEMBER.getCode())) {
                        throw new BusinessException("拒绝访问");
                    }
                    return access(TeamRoleDict.MEMBER.getCode());
                });

        assertThatThrownBy(() -> service().updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), "已联系", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("拒绝访问");

        assertThat(roles.getValue()).containsExactlyInAnyOrderElementsOf(UPDATE_ROLES)
                .doesNotContain(TeamRoleDict.MEMBER.getCode());
        verifyNoInteractions(contactLeadEntityMapper);
    }

    /**
     * OWNER 和 MANAGER 均应使用受限 patch update 并返回完整明文。
     *
     * @param role 可更新角色
     */
    @ParameterizedTest
    @MethodSource("updatableRoles")
    void updateFollowStatusShouldUseScopedPatchUpdateForOwnerAndManager(String role) {
        EncryptedAuthTokenService crypto = encryption();
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(UPDATE_ROLES)))
                .thenReturn(access(role));
        ContactLeadEntity lead = encryptedLead(crypto, LEAD_ID);
        lead.setVersion(7);
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);
        when(contactLeadEntityMapper.update(any(ContactLeadEntity.class), any())).thenReturn(1);

        TeamContactLeadResponse.Item item = service(crypto).updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), " 已联系 ", USER_ID);

        ArgumentCaptor<Wrapper<ContactLeadEntity>> queryCaptor = wrapperCaptor();
        verify(contactLeadEntityMapper).selectOne(queryCaptor.capture());
        LambdaQueryWrapper<ContactLeadEntity> query = castQuery(queryCaptor.getValue());
        normalizeSql(query.getSqlSegment());
        assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                LEAD_ID, PortfolioOwnerTypeDict.TEAM.getCode(), TEAM_ID);

        ArgumentCaptor<ContactLeadEntity> patchCaptor = ArgumentCaptor.forClass(ContactLeadEntity.class);
        ArgumentCaptor<Wrapper<ContactLeadEntity>> updateCaptor = wrapperCaptor();
        verify(contactLeadEntityMapper).update(patchCaptor.capture(), updateCaptor.capture());
        ContactLeadEntity patch = patchCaptor.getValue();
        assertThat(patch.getFollowStatus()).isEqualTo(FollowStatusDict.CONTACTED.getCode());
        assertThat(patch.getFollowNote()).isEqualTo("已联系");
        assertThat(patch.getVersion()).isNull();
        assertThat(patch.getId()).isNull();
        assertThat(patch.getOwnerType()).isNull();
        assertThat(patch.getOwnerId()).isNull();
        assertThat(patch.getPhoneCiphertext()).isNull();
        assertThat(patch.getWechatCiphertext()).isNull();

        LambdaUpdateWrapper<ContactLeadEntity> update = castUpdate(updateCaptor.getValue());
        assertThat(normalizeSql(update.getSqlSet())).contains("version");
        assertThat(normalizeSql(update.getSqlSegment())).contains("id", "owner_type", "owner_id", "version");
        assertThat(update.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                LEAD_ID, PortfolioOwnerTypeDict.TEAM.getCode(), TEAM_ID, 7, 8);
        verify(contactLeadEntityMapper, never()).updateById(any(ContactLeadEntity.class));

        assertThat(item.getPhone()).isEqualTo(PHONE);
        assertThat(item.getWechat()).isEqualTo(WECHAT);
        assertThat(item.getFollowStatus()).isEqualTo(FollowStatusDict.CONTACTED.getCode());
        assertThat(item.getFollowNote()).isEqualTo("已联系");
    }

    /**
     * 旧 version 为空时 update wrapper 不应制造 version 条件或 patch 值。
     */
    @Test
    void updateFollowStatusShouldOmitVersionWhenExistingVersionIsNull() {
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(UPDATE_ROLES)))
                .thenReturn(access(TeamRoleDict.MANAGER.getCode()));
        ContactLeadEntity lead = encryptedLead(encryption(), LEAD_ID);
        lead.setVersion(null);
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);
        when(contactLeadEntityMapper.update(any(ContactLeadEntity.class), any())).thenReturn(1);

        service().updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), "已联系", USER_ID);

        ArgumentCaptor<ContactLeadEntity> patchCaptor = ArgumentCaptor.forClass(ContactLeadEntity.class);
        ArgumentCaptor<Wrapper<ContactLeadEntity>> wrapperCaptor = wrapperCaptor();
        verify(contactLeadEntityMapper).update(patchCaptor.capture(), wrapperCaptor.capture());
        assertThat(patchCaptor.getValue().getVersion()).isNull();
        LambdaUpdateWrapper<ContactLeadEntity> update = castUpdate(wrapperCaptor.getValue());
        normalizeSql(update.getSqlSegment());
        assertThat(update.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(LEAD_ID, PortfolioOwnerTypeDict.TEAM.getCode(), TEAM_ID);
    }

    /**
     * 非法状态和超长备注应在读取线索前拒绝。
     */
    @Test
    void updateFollowStatusShouldRejectInvalidStatusAndOversizedNoteBeforeLeadQuery() {
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(UPDATE_ROLES)))
                .thenReturn(access(TeamRoleDict.OWNER.getCode()));

        assertThatThrownBy(() -> service().updateFollowStatus(TEAM_ID, LEAD_ID, "BAD", "", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("跟进状态不支持");
        assertThatThrownBy(() -> service().updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), "x".repeat(1001), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("跟进备注长度不合法");

        verifyNoInteractions(contactLeadEntityMapper);
    }

    /**
     * 错误归属实体和 update 零行都必须失败。
     */
    @Test
    void updateFollowStatusShouldRejectWrongOwnerAndUpdateZero() {
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(UPDATE_ROLES)))
                .thenReturn(access(TeamRoleDict.OWNER.getCode()));
        ContactLeadEntity wrongOwner = encryptedLead(encryption(), LEAD_ID);
        wrongOwner.setOwnerId(999L);
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(wrongOwner);

        assertThatThrownBy(() -> service().updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), "", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队预留信息不存在");
        verify(contactLeadEntityMapper, never()).update(any(ContactLeadEntity.class), any());

        ContactLeadEntity valid = encryptedLead(encryption(), LEAD_ID);
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(valid);
        when(contactLeadEntityMapper.update(any(ContactLeadEntity.class), any())).thenReturn(0);
        assertThatThrownBy(() -> service().updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), "", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留联系信息保存失败");
    }

    /**
     * update 成功后的解密异常必须转业务异常，以触发同一事务回滚。
     */
    @Test
    void updateFollowStatusShouldTranslatePostUpdateDecryptFailure() {
        EncryptedAuthTokenService crypto = mock(EncryptedAuthTokenService.class);
        when(teamPortfolioAccessService.requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(UPDATE_ROLES)))
                .thenReturn(access(TeamRoleDict.MANAGER.getCode()));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(encryptedLead(encryption(), LEAD_ID));
        when(contactLeadEntityMapper.update(any(ContactLeadEntity.class), any())).thenReturn(1);
        when(crypto.decryptPayload(anyString(), anyString())).thenThrow(new IllegalArgumentException("secret-cipher"));

        assertThatThrownBy(() -> service(crypto).updateFollowStatus(
                TEAM_ID, LEAD_ID, FollowStatusDict.CONTACTED.getCode(), "", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DECRYPTION_FAILED_MESSAGE)
                .hasMessageNotContaining("secret-cipher");

        verify(contactLeadEntityMapper).update(any(ContactLeadEntity.class), any());
        verify(crypto).decryptPayload(anyString(), anyString());
    }

    /**
     * 请求和响应 DTO 字段必须精确，且不含密文、尾号或脱敏提示。
     */
    @Test
    void dtoFieldsShouldMatchExactPlaintextContract() {
        assertThat(instanceFieldNames(TeamContactLeadSubmitRequest.class)).containsExactlyInAnyOrder(
                "visitRecordId", "contactName", "phone", "wechat", "desiredSchedule", "needs",
                "sourceType", "consentVersion", "idempotencyKey");
        assertThat(instanceFieldNames(TeamContactLeadResponse.class)).containsExactlyInAnyOrder(
                "page", "pageSize", "hasMore", "items");
        assertThat(instanceFieldNames(TeamContactLeadResponse.Item.class)).containsExactlyInAnyOrder(
                "leadId", "teamId", "teamName", "portfolioId", "portfolioTitle", "portfolioShareCode",
                "portfolioRevision", "visitRecordId", "contactName", "phone", "wechat", "desiredSchedule",
                "needs", "sourceType", "sourceText", "consentVersion", "followStatus", "followStatusText",
                "followNote", "submittedAt");
        assertThat(String.join(" ", instanceFieldNames(TeamContactLeadResponse.Item.class)))
                .doesNotContain("Ciphertext", "ciphertext", "Last4", "last4", "Mask", "mask");
    }

    /**
     * 组件生产目录必须恰好包含五个 Java 文件。
     *
     * @throws Exception 读取目录失败
     */
    @Test
    void componentProductionDirectoryShouldContainExactlyFiveJavaFiles() throws Exception {
        try (Stream<Path> paths = Files.list(componentSourceDirectory())) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .sorted())
                    .containsExactly(
                            "TeamContactFormComponentConfig.java",
                            "TeamContactFormComponentReferenceExtractor.java",
                            "TeamContactFormComponentRenderer.java",
                            "TeamContactFormComponentService.java",
                            "TeamContactFormComponentValidator.java");
        }
    }

    /**
     * 生产源码不得通过普通 import、static import 或全限定名依赖禁止边界。
     *
     * @throws Exception 读取源码失败
     */
    @Test
    void productionSourcesShouldRejectForbiddenDependenciesAndDirectSpringConstruction() throws Exception {
        for (Path source : componentSourceFiles()) {
            String content = Files.readString(source);
            assertThat(findForbiddenDependencyReferences(content)).as(source.toString()).isEmpty();
            assertThat(findDirectSpringConstruction(content)).as(source.toString()).isEmpty();
        }
    }

    /**
     * 依赖扫描规则自身必须识别普通、static 和全限定名反例，同时允许白名单。
     */
    @Test
    void dependencyScannerShouldCatchCounterexamplesWithoutRejectingAllowlist() {
        String unsafe = """
                import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
                import com.jxc.wefolio.dto.*;
                import static com.jxc.wefolio.service.ContactLeadService.helper;
                class Unsafe {
                    com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentRenderer value;
                    void create() { new TeamContactFormComponentRenderer(null); }
                    void createQualified() {
                        new com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentValidator();
                    }
                }
                """;
        String safe = """
                import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
                import com.jxc.wefolio.service.EncryptedAuthTokenService;
                import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
                import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentConfig;
                class Safe { TeamContactFormComponentConfig value = new TeamContactFormComponentConfig(); }
                """;

        assertThat(findForbiddenDependencyReferences(unsafe)).hasSize(4);
        assertThat(findDirectSpringConstruction(unsafe)).containsExactly(
                "TeamContactFormComponentRenderer", "TeamContactFormComponentValidator");
        assertThat(findForbiddenDependencyReferences(safe)).isEmpty();
        assertThat(findDirectSpringConstruction(safe)).isEmpty();
    }

    /**
     * 生产源码不得包含日志框架、标准输出或 print 系列调用。
     *
     * @throws Exception 读取源码失败
     */
    @Test
    void productionSourcesShouldContainNoSensitiveLoggingPatterns() throws Exception {
        for (Path source : componentSourceFiles()) {
            assertThat(findLoggingUsages(Files.readString(source))).as(source.toString()).isEmpty();
        }
    }

    /**
     * 日志扫描规则应覆盖全部反例，且不误报注释或普通字符串。
     */
    @Test
    void loggingScannerShouldCatchCounterexamplesAndIgnorePlainStrings() {
        Map<String, String> unsafeCases = Map.ofEntries(
                Map.entry("Slf4j", "@Slf4j class X {}"),
                Map.entry("Logger", "class X { Logger value; }"),
                Map.entry("LoggerFactory", "class X { Object value = LoggerFactory.getLogger(X.class); }"),
                Map.entry("LOGGER", "class X { void a() { LOGGER.info(\"x\"); } }"),
                Map.entry("log.", "class X { void a() { log.info(\"x\"); } }"),
                Map.entry("System.out", "class X { void a() { System.out.write(1); } }"),
                Map.entry("System.err", "class X { void a() { System.err.write(1); } }"),
                Map.entry("print", "class X { void a() { print(\"x\"); } }"),
                Map.entry("println", "class X { void a() { println(\"x\"); } }"),
                Map.entry("printf", "class X { void a() { printf(\"x\"); } }"),
                Map.entry("maskPhone", "class X { void a() { maskPhone(value); } }"),
                Map.entry("maskWechat", "class X { void a() { maskWechat(value); } }"),
                Map.entry("masking", "class X { Object masking; }"));
        unsafeCases.forEach((name, source) -> assertThat(findLoggingUsages(source)).as(name).isNotEmpty());

        String safe = """
                // @Slf4j Logger log.info System.out print
                class Safe {
                    String text = "Slf4j Logger LoggerFactory LOGGER log. System.out System.err print println printf";
                }
                """;
        assertThat(findLoggingUsages(safe)).isEmpty();
    }

    /**
     * Spring 注解和不可变提交结果应保持组件契约。
     */
    @Test
    void springComponentsAndSubmitResultShouldKeepContract() {
        assertThat(TeamContactFormComponentValidator.class.getAnnotation(org.springframework.stereotype.Component.class))
                .isNotNull();
        assertThat(TeamContactFormComponentRenderer.class.getAnnotation(org.springframework.stereotype.Component.class))
                .isNotNull();
        assertThat(TeamContactFormComponentReferenceExtractor.class.getAnnotation(org.springframework.stereotype.Component.class))
                .isNotNull();
        assertThat(TeamContactFormComponentService.class.getAnnotation(org.springframework.stereotype.Service.class))
                .isNotNull();
        assertThat(Modifier.isFinal(TeamContactFormComponentService.SubmitResult.class.getModifiers())).isTrue();
    }

    /** 提供有效空字段输入。 */
    private static Stream<Arguments> emptyFieldCases() {
        return Stream.of(
                Arguments.of("缺失 fields", new JSONObject()),
                Arguments.of("非集合 fields", JSONObject.of("fields", "phone")),
                Arguments.of("空集合 fields", JSONObject.of("fields", List.of())),
                Arguments.of("全空白 fields", JSONObject.of("fields", List.of(" ", "\t", "\n"))));
    }

    /** 提供非空但缺必填语义的字段输入。 */
    private static Stream<Arguments> invalidNonEmptyFieldCases() {
        return Stream.of(
                Arguments.of("缺联系人", List.of("phone", "wechat"), "预留联系信息必须包含联系人字段"),
                Arguments.of("缺联系方式", List.of("contactName", "needs"), "预留联系信息必须包含手机号或微信号字段"));
    }

    /** 提供非法组件上下文。 */
    private static Stream<Arguments> invalidComponentContexts() {
        return Stream.of(
                Arguments.of("null context", null),
                Arguments.of("teamId zero", new TeamPortfolioComponentContext(0, PORTFOLIO_ID, 1)),
                Arguments.of("portfolioId zero", new TeamPortfolioComponentContext(TEAM_ID, 0, 1)),
                Arguments.of("negative revision", new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, -1)));
    }

    /** 提供每一种无效作品集身份。 */
    private static Stream<InvalidPortfolioCase> invalidPortfolioCases() {
        return Stream.of(
                invalidPortfolio("Mapper null", ignored -> null),
                invalidPortfolio("ID null", portfolio -> setPortfolioId(portfolio, null)),
                invalidPortfolio("ID zero", portfolio -> setPortfolioId(portfolio, 0L)),
                invalidPortfolio("owner ID null", portfolio -> setOwnerId(portfolio, null)),
                invalidPortfolio("owner ID zero", portfolio -> setOwnerId(portfolio, 0L)),
                invalidPortfolio("personal owner", portfolio -> setOwnerType(portfolio, PortfolioOwnerTypeDict.USER.getCode())),
                invalidPortfolio("advanced template", portfolio -> setTemplateType(portfolio, PortfolioTemplateTypeDict.ADVANCED.getCode())),
                invalidPortfolio("disabled status", portfolio -> setStatus(portfolio, PortfolioStatusDict.DISABLED.getCode())),
                invalidPortfolio("wrong schema", portfolio -> setSchemaVersion(portfolio, "standard-personal-v1")),
                invalidPortfolio("draft publication", portfolio -> setPublicationStatus(
                        portfolio, PortfolioPublicationStatusDict.DRAFT_ONLY.getCode())),
                invalidPortfolio("revision null", portfolio -> setPublishedRevision(portfolio, null)),
                invalidPortfolio("revision zero", portfolio -> setPublishedRevision(portfolio, 0)),
                invalidPortfolio("share mismatch", portfolio -> setShareCode(portfolio, "TPF999")),
                invalidPortfolio("config null", portfolio -> setPublishedConfig(portfolio, null)),
                invalidPortfolio("config blank", portfolio -> setPublishedConfig(portfolio, " ")));
    }

    /** 提供非法发布配置。 */
    private static Stream<Arguments> invalidPublishedConfigCases() {
        return Stream.of(
                Arguments.of("malformed JSON", "{bad"),
                Arguments.of("missing schema", "{\"components\":[]}"),
                Arguments.of("wrong JSON schema", "{\"schemaVersion\":\"standard-personal-v1\",\"components\":[]}"),
                Arguments.of("missing components", "{\"schemaVersion\":\"standard-team-v1\"}"),
                Arguments.of("no enabled CONTACT_FORM", """
                        {"schemaVersion":"standard-team-v1","components":[
                          {"componentType":"CONTACT_FORM","enabled":false,"config":{}}
                        ]}
                        """),
                Arguments.of("bad CONTACT_FORM config", """
                        {"schemaVersion":"standard-team-v1","components":[
                          {"componentType":"CONTACT_FORM","enabled":true,"config":{"fields":["phone"]}}
                        ]}
                        """));
    }

    /** 提供访问记录全部错归属情况。 */
    private static Stream<InvalidVisitCase> invalidVisitRecordCases() {
        return Stream.of(
                invalidVisit("visitor key", record -> setVisitorKey(record, "other-key")),
                invalidVisit("visitor ID", record -> setVisitorId(record, 100L)),
                invalidVisit("portfolio", record -> setVisitPortfolioId(record, 999L)),
                invalidVisit("portfolio type", record -> setPortfolioType(record, PortfolioTypeDict.PERSONAL.getCode())),
                invalidVisit("owner type", record -> setVisitOwnerType(record, PortfolioOwnerTypeDict.USER.getCode())),
                invalidVisit("owner ID", record -> setVisitOwnerId(record, 999L)));
    }

    /** 提供任一访客 ID 为空的有效组合。 */
    private static Stream<Arguments> nullableMatchingVisitorIds() {
        return Stream.of(Arguments.of(null, 99L), Arguments.of(99L, null), Arguments.of(null, null));
    }

    /** 提供已有幂等线索错归属情况。 */
    private static Stream<DuplicateMismatchCase> duplicateMismatchCases() {
        return Stream.of(
                duplicateMismatch("cross team", lead -> setLeadOwnerId(lead, 999L)),
                duplicateMismatch("cross portfolio", lead -> setLeadPortfolioId(lead, 999L)),
                duplicateMismatch("cross key", lead -> setLeadIdempotencyKey(lead, "other-key")));
    }

    /** 提供可读取角色。 */
    private static Stream<String> readableRoles() {
        return READ_ROLES.stream();
    }

    /** 提供可更新角色。 */
    private static Stream<String> updatableRoles() {
        return UPDATE_ROLES.stream();
    }

    /** 创建待测 Validator。 */
    private static TeamContactFormComponentValidator validator() {
        return new TeamContactFormComponentValidator();
    }

    /** 创建待测服务。 */
    private TeamContactFormComponentService service() {
        return service(encryption());
    }

    /** 使用指定加密器创建待测服务。 */
    private TeamContactFormComponentService service(EncryptedAuthTokenService crypto) {
        return new TeamContactFormComponentService(
                portfolioEntityMapper,
                visitRecordEntityMapper,
                contactLeadEntityMapper,
                teamPortfolioAccessService,
                crypto,
                validator());
    }

    /** 创建真实 AES-GCM 服务。 */
    private static EncryptedAuthTokenService encryption() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("team-contact-test-secret");
        return new EncryptedAuthTokenService(properties);
    }

    /** 设置成功提交依赖。 */
    private void stubSuccessfulSubmit(long leadId) {
        when(portfolioEntityMapper.selectOne(any())).thenReturn(teamPortfolio());
        when(visitRecordEntityMapper.selectById(VISIT_RECORD_ID)).thenReturn(visitRecord());
        when(contactLeadEntityMapper.insert(any(ContactLeadEntity.class))).thenAnswer(invocation -> {
            ContactLeadEntity lead = invocation.getArgument(0);
            lead.setId(leadId);
            return 1;
        });
    }

    /** 创建有效团队作品集。 */
    private static PortfolioEntity teamPortfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(PORTFOLIO_ID);
        portfolio.setShareCode(SHARE_CODE);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(TEAM_ID);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion("standard-team-v1");
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setDeleted(0L);
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-team-v1","share":{"title":"团队婚礼作品集"},"components":[
                  {"componentKey":"contact-1","componentType":"CONTACT_FORM","enabled":true,
                   "config":{"title":"联系团队","description":"","displayMode":"MODAL_FORM",
                   "fields":["contactName","phone","wechat","needs"]}}
                ]}
                """);
        return portfolio;
    }

    /** 创建有效访问记录。 */
    private static VisitRecordEntity visitRecord() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(VISIT_RECORD_ID);
        record.setVisitorId(99L);
        record.setVisitorKey("visitor-key");
        record.setPortfolioId(PORTFOLIO_ID);
        record.setPortfolioType(PortfolioTypeDict.TEAM.getCode());
        record.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        record.setOwnerId(TEAM_ID);
        record.setSourceType("QR_CODE");
        return record;
    }

    /** 创建提交请求。 */
    private static TeamContactLeadSubmitRequest request() {
        TeamContactLeadSubmitRequest request = new TeamContactLeadSubmitRequest();
        request.setVisitRecordId(VISIT_RECORD_ID);
        request.setContactName(" 林安 ");
        request.setPhone(" " + PHONE + " ");
        request.setWechat(" " + WECHAT + " ");
        request.setDesiredSchedule(" 2026-08-01 ");
        request.setNeeds(" 想了解报价 ");
        request.setSourceType("FORGED");
        request.setConsentVersion(" privacy-v1 ");
        request.setIdempotencyKey(" lead-1 ");
        return request;
    }

    /** 创建访客上下文。 */
    private static VisitorContext visitor() {
        return new VisitorContext(99L, "visitor-key", "Bearer team-contact");
    }

    /** 创建有效组件上下文。 */
    private static TeamPortfolioComponentContext componentContext() {
        return new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3);
    }

    /** 创建 Renderer 验证输入的原始快照。 */
    private static JSONObject inputWithOriginalTitle() {
        return JSONObject.of("title", " 标题 ", "fields", List.of("contactName", "phone"));
    }

    /** 创建团队角色访问上下文。 */
    private static TeamPortfolioAccessService.TeamPortfolioAccess access(String role) {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        team.setName("映期团队");
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setRole(role);
        return new TeamPortfolioAccessService.TeamPortfolioAccess(null, team, membership,
                !TeamRoleDict.MEMBER.getCode().equals(role), true);
    }

    /** 创建已用途绑定加密的团队线索。 */
    private static ContactLeadEntity encryptedLead(EncryptedAuthTokenService crypto, long id) {
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setId(id);
        lead.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        lead.setOwnerId(TEAM_ID);
        lead.setPortfolioId(PORTFOLIO_ID);
        lead.setPortfolioTitleSnapshot("团队婚礼作品集");
        lead.setPortfolioShareCodeSnapshot(SHARE_CODE);
        lead.setPortfolioRevision(3);
        lead.setVisitRecordId(VISIT_RECORD_ID);
        lead.setContactName("林安");
        lead.setPhoneCiphertext(crypto.encryptPayload(PHONE_TOKEN_PREFIX, PHONE_PAYLOAD_PREFIX + PHONE));
        lead.setWechatCiphertext(crypto.encryptPayload(WECHAT_TOKEN_PREFIX, WECHAT_PAYLOAD_PREFIX + WECHAT));
        lead.setDesiredSchedule("2026-08-01");
        lead.setNeeds("想了解报价");
        lead.setSourceType("QR_CODE");
        lead.setConsentVersion("privacy-v1");
        lead.setFollowStatus(FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        lead.setFollowNote("待跟进");
        lead.setIdempotencyKey("lead-1");
        lead.setSubmittedAt(LocalDateTime.of(2026, 7, 11, 12, 0));
        lead.setVersion(0);
        return lead;
    }

    /** 创建有效重复线索。 */
    private static ContactLeadEntity existingDuplicate() {
        ContactLeadEntity lead = encryptedLead(encryption(), LEAD_ID);
        lead.setIdempotencyKey("lead-1");
        return lead;
    }

    /** 获取 DTO 非静态字段名。 */
    private static List<String> instanceFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    /** 获取组件生产目录。 */
    private static Path componentSourceDirectory() {
        return Path.of("src/main/java/com/jxc/wefolio/service/teamportfolio/component/contactform");
    }

    /** 获取组件五个生产源码文件。 */
    private static List<Path> componentSourceFiles() throws Exception {
        try (Stream<Path> paths = Files.list(componentSourceDirectory())) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /** 扫描禁止的 DTO、Service 和其他团队组件依赖。 */
    private static List<String> findForbiddenDependencyReferences(String source) {
        String code = codeOnly(source);
        Pattern pattern = Pattern.compile(
                "com\\.jxc\\.wefolio\\.(?:dto|service)"
                        + "(?:\\.(?:[A-Za-z_$][A-Za-z0-9_$]*|\\*))+");
        Matcher matcher = pattern.matcher(code);
        Stream.Builder<String> forbidden = Stream.builder();
        while (matcher.find()) {
            String reference = matcher.group();
            if (reference.startsWith("com.jxc.wefolio.dto.")
                    && !reference.startsWith("com.jxc.wefolio.dto.teamportfolio.")) {
                forbidden.add(reference);
                continue;
            }
            if (!reference.startsWith("com.jxc.wefolio.service.")) {
                continue;
            }
            if (reference.startsWith("com.jxc.wefolio.service.EncryptedAuthTokenService")
                    || reference.equals("com.jxc.wefolio.service.teamportfolio.component.contactform")
                    || reference.startsWith("com.jxc.wefolio.service.teamportfolio.component.contactform.")
                    || reference.startsWith("com.jxc.wefolio.service.teamportfolio.")
                    && !reference.startsWith("com.jxc.wefolio.service.teamportfolio.component.")) {
                continue;
            }
            forbidden.add(reference);
        }
        return forbidden.build().distinct().toList();
    }

    /** 扫描直接实例化的 Spring 组件。 */
    private static List<String> findDirectSpringConstruction(String source) {
        Pattern pattern = Pattern.compile(
                "\\bnew\\s+(?:com\\.jxc\\.wefolio\\.service\\.teamportfolio\\.component\\.contactform\\.)?"
                        + "(TeamContactFormComponent(?:Validator|Renderer|ReferenceExtractor|Service))\\s*\\(");
        Matcher matcher = pattern.matcher(codeOnly(source));
        Stream.Builder<String> matches = Stream.builder();
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }
        return matches.build().toList();
    }

    /** 扫描日志框架、标准输出和 print 系列调用。 */
    private static List<String> findLoggingUsages(String source) {
        String code = codeOnly(source);
        List<Pattern> patterns = List.of(
                Pattern.compile("@\\s*Slf4j\\b"),
                Pattern.compile("\\bLogger\\b"),
                Pattern.compile("\\bLoggerFactory\\b"),
                Pattern.compile("\\bLOGGER\\b"),
                Pattern.compile("\\blog\\s*\\."),
                Pattern.compile("\\bSystem\\s*\\.\\s*out\\b"),
                Pattern.compile("\\bSystem\\s*\\.\\s*err\\b"),
                Pattern.compile("\\b(?:print|println|printf)\\s*\\("),
                Pattern.compile("\\bmaskPhone\\s*\\("),
                Pattern.compile("\\bmaskWechat\\s*\\("),
                Pattern.compile("\\bmasking\\b"));
        return patterns.stream()
                .filter(pattern -> pattern.matcher(code).find())
                .map(Pattern::pattern)
                .toList();
    }

    /**
     * 去除注释、普通字符串、字符和文本块，仅保留可执行源码结构。
     */
    private static String codeOnly(String source) {
        StringBuilder result = new StringBuilder(source.length());
        ScanState state = ScanState.CODE;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (state == ScanState.CODE && current == '/' && next == '/') {
                state = ScanState.LINE_COMMENT;
                result.append(' ');
                index++;
            } else if (state == ScanState.CODE && current == '/' && next == '*') {
                state = ScanState.BLOCK_COMMENT;
                result.append(' ');
                index++;
            } else if (state == ScanState.CODE && current == '"' && next == '"'
                    && index + 2 < source.length() && source.charAt(index + 2) == '"') {
                state = ScanState.TEXT_BLOCK;
                result.append(' ');
                index += 2;
            } else if (state == ScanState.CODE && current == '"') {
                state = ScanState.STRING;
                result.append(' ');
            } else if (state == ScanState.CODE && current == '\'') {
                state = ScanState.CHARACTER;
                result.append(' ');
            } else if (state == ScanState.LINE_COMMENT && (current == '\n' || current == '\r')) {
                state = ScanState.CODE;
                result.append(current);
            } else if (state == ScanState.BLOCK_COMMENT && current == '*' && next == '/') {
                state = ScanState.CODE;
                result.append(' ');
                index++;
            } else if (state == ScanState.TEXT_BLOCK && current == '"' && next == '"'
                    && index + 2 < source.length() && source.charAt(index + 2) == '"') {
                state = ScanState.CODE;
                result.append(' ');
                index += 2;
            } else if ((state == ScanState.STRING || state == ScanState.CHARACTER) && current == '\\') {
                result.append(' ');
                index++;
            } else if (state == ScanState.STRING && current == '"') {
                state = ScanState.CODE;
                result.append(' ');
            } else if (state == ScanState.CHARACTER && current == '\'') {
                state = ScanState.CODE;
                result.append(' ');
            } else if (state == ScanState.CODE) {
                result.append(current);
            } else {
                result.append(current == '\n' || current == '\r' ? current : ' ');
            }
        }
        return result.toString();
    }

    /** 规范化 SQL 空白和大小写。 */
    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").strip().toLowerCase();
    }

    /** 创建无效作品集用例。 */
    private static InvalidPortfolioCase invalidPortfolio(String name, UnaryOperator<PortfolioEntity> mutator) {
        return new InvalidPortfolioCase(name, mutator);
    }

    /** 创建无效访问记录用例。 */
    private static InvalidVisitCase invalidVisit(String name, UnaryOperator<VisitRecordEntity> mutator) {
        return new InvalidVisitCase(name, mutator);
    }

    /** 创建幂等不匹配用例。 */
    private static DuplicateMismatchCase duplicateMismatch(
            String name,
            UnaryOperator<ContactLeadEntity> mutator
    ) {
        return new DuplicateMismatchCase(name, mutator);
    }

    /** 以下 setter helper 让参数化用例保持单一变化。 */
    private static PortfolioEntity setPortfolioId(PortfolioEntity value, Long id) { value.setId(id); return value; }
    private static PortfolioEntity setOwnerId(PortfolioEntity value, Long id) { value.setOwnerId(id); return value; }
    private static PortfolioEntity setOwnerType(PortfolioEntity value, String type) { value.setOwnerType(type); return value; }
    private static PortfolioEntity setTemplateType(PortfolioEntity value, String type) { value.setTemplateType(type); return value; }
    private static PortfolioEntity setStatus(PortfolioEntity value, String status) { value.setStatus(status); return value; }
    private static PortfolioEntity setSchemaVersion(PortfolioEntity value, String schema) { value.setSchemaVersion(schema); return value; }
    private static PortfolioEntity setPublicationStatus(PortfolioEntity value, String status) { value.setPublicationStatus(status); return value; }
    private static PortfolioEntity setPublishedRevision(PortfolioEntity value, Integer revision) { value.setPublishedRevision(revision); return value; }
    private static PortfolioEntity setShareCode(PortfolioEntity value, String code) { value.setShareCode(code); return value; }
    private static PortfolioEntity setPublishedConfig(PortfolioEntity value, String config) { value.setPublishedConfigJson(config); return value; }
    private static VisitRecordEntity setVisitorKey(VisitRecordEntity value, String key) { value.setVisitorKey(key); return value; }
    private static VisitRecordEntity setVisitorId(VisitRecordEntity value, Long id) { value.setVisitorId(id); return value; }
    private static VisitRecordEntity setVisitPortfolioId(VisitRecordEntity value, Long id) { value.setPortfolioId(id); return value; }
    private static VisitRecordEntity setPortfolioType(VisitRecordEntity value, String type) { value.setPortfolioType(type); return value; }
    private static VisitRecordEntity setVisitOwnerType(VisitRecordEntity value, String type) { value.setOwnerType(type); return value; }
    private static VisitRecordEntity setVisitOwnerId(VisitRecordEntity value, Long id) { value.setOwnerId(id); return value; }
    private static ContactLeadEntity setLeadOwnerId(ContactLeadEntity value, Long id) { value.setOwnerId(id); return value; }
    private static ContactLeadEntity setLeadPortfolioId(ContactLeadEntity value, Long id) { value.setPortfolioId(id); return value; }
    private static ContactLeadEntity setLeadIdempotencyKey(ContactLeadEntity value, String key) { value.setIdempotencyKey(key); return value; }

    /** 创建通用 Wrapper 捕获器。 */
    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    /** 创建 Page 捕获器。 */
    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Page<T>> pageCaptor() {
        return ArgumentCaptor.forClass(Page.class);
    }

    /** 创建角色集合捕获器。 */
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Set<String>> roleSetCaptor() {
        return ArgumentCaptor.forClass(Set.class);
    }

    /** 转换查询 wrapper。 */
    @SuppressWarnings("unchecked")
    private static <T> LambdaQueryWrapper<T> castQuery(Wrapper<T> wrapper) {
        return (LambdaQueryWrapper<T>) wrapper;
    }

    /** 转换更新 wrapper。 */
    @SuppressWarnings("unchecked")
    private static <T> LambdaUpdateWrapper<T> castUpdate(Wrapper<T> wrapper) {
        return (LambdaUpdateWrapper<T>) wrapper;
    }

    /** 无效作品集用例。 */
    private record InvalidPortfolioCase(String name, UnaryOperator<PortfolioEntity> mutator) {
        @Override
        public String toString() { return name; }
    }

    /** 无效访问记录用例。 */
    private record InvalidVisitCase(String name, UnaryOperator<VisitRecordEntity> mutator) {
        @Override
        public String toString() { return name; }
    }

    /** 幂等不匹配用例。 */
    private record DuplicateMismatchCase(String name, UnaryOperator<ContactLeadEntity> mutator) {
        @Override
        public String toString() { return name; }
    }

    /** 源码扫描状态。 */
    private enum ScanState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER,
        TEXT_BLOCK
    }
}
