package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioHyperlinkTargetResponse;
import com.jxc.wefolio.dto.PortfolioReferenceFailureResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.PortfolioValidationException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人作品集超链接引用图服务测试。
 */
@ExtendWith(MockitoExtension.class)
class PortfolioHyperlinkGraphServiceTest {

    /** 作品集 Mapper 模拟。 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 引用 Mapper 模拟。 */
    @Mock
    private PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 初始化 Lambda 查询列缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PortfolioReferenceEntity.class
        );
    }

    @Test
    void graphLockShouldUseAscendingRowsFiveSecondTimeoutAndTranslateTimeout() throws NoSuchMethodException {
        Method lockMethod = PortfolioEntityMapper.class.getMethod(
                "lockActiveStandardPersonalByOwnerId", Long.class);
        assertThat(lockMethod.getAnnotation(Options.class).timeout()).isEqualTo(5);
        assertThat(PortfolioEntityMapper.LOCK_ACTIVE_STANDARD_PERSONAL_SQL)
                .contains("ORDER BY id ASC", "FOR UPDATE");
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> service().lockUserGraph(7L, 88L))
                .isInstanceOf(PortfolioValidationException.class)
                .hasMessage("作品集正在更新，请稍后重试");
    }

    @Test
    void graphLockShouldTranslateDeadlockToRetryableValidationError() {
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L))
                .thenThrow(new PessimisticLockingFailureException("deadlock"));

        assertThatThrownBy(() -> service().lockUserGraph(7L, 88L))
                .isInstanceOf(PortfolioValidationException.class)
                .hasMessage("作品集正在更新，请稍后重试")
                .satisfies(error -> assertThat(((PortfolioValidationException) error).getData())
                        .extracting("errorCode")
                        .isEqualTo("PORTFOLIO_GRAPH_LOCK_TIMEOUT"));
    }

    @Test
    void validateReferencesShouldRejectSelfAndCycleWithComponentDetails() {
        PortfolioEntity source = portfolio(88L, "来源", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity target = portfolio(99L, "目标", LocalDateTime.parse("2026-08-02T20:00:00"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L)).thenReturn(List.of(source, target));
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 88L);

        PortfolioValidationException selfError = catchThrowableOfType(
                () -> service().validateReferences(graph, PortfolioConfigScopeDict.DRAFT.getCode(),
                        List.of(link(88L, 88L, "c_self", PortfolioConfigScopeDict.DRAFT.getCode())), null),
                PortfolioValidationException.class
        );
        assertThat(selfError.getMessage()).isEqualTo("不能跳转到当前作品集");
        assertThat(selfError.getData())
                .extracting("errorCode", "componentKey", "targetPortfolioId")
                .containsExactly("PORTFOLIO_HYPERLINK_SELF", "c_self", 88L);

        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                link(99L, 88L, "c_back", PortfolioConfigScopeDict.DRAFT.getCode())
        ));
        PortfolioValidationException cycleError = catchThrowableOfType(
                () -> service().validateReferences(graph, PortfolioConfigScopeDict.DRAFT.getCode(),
                        List.of(link(88L, 99L, "c_cycle", PortfolioConfigScopeDict.DRAFT.getCode())), null),
                PortfolioValidationException.class
        );
        assertThat(cycleError.getMessage()).isEqualTo("作品集之间不能循环跳转");
        assertThat(cycleError.getData())
                .extracting("errorCode", "componentKey", "targetPortfolioId")
                .containsExactly("PORTFOLIO_HYPERLINK_CYCLE", "c_cycle", 99L);
    }

    @Test
    void validateReferencesShouldRejectThreeNodeDraftCycle() {
        PortfolioEntity portfolioA = portfolio(88L, "作品集 A", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity portfolioB = portfolio(99L, "作品集 B", LocalDateTime.parse("2026-08-02T20:00:00"));
        PortfolioEntity portfolioC = portfolio(100L, "作品集 C", LocalDateTime.parse("2026-08-01T20:00:00"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L))
                .thenReturn(List.of(portfolioA, portfolioB, portfolioC));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                link(88L, 99L, "c_a_to_b", PortfolioConfigScopeDict.DRAFT.getCode()),
                link(99L, 100L, "c_b_to_c", PortfolioConfigScopeDict.DRAFT.getCode())
        ));
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 100L);

        assertThatThrownBy(() -> service().validateReferences(
                graph,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                List.of(link(100L, 88L, "c_c_to_a", PortfolioConfigScopeDict.DRAFT.getCode())),
                null
        )).isInstanceOf(PortfolioValidationException.class)
                .hasMessage("作品集之间不能循环跳转")
                .satisfies(error -> assertThat(((PortfolioValidationException) error).getData())
                        .extracting("componentKey", "targetPortfolioId")
                        .containsExactly("c_c_to_a", 88L));
    }

    @Test
    void validateReferencesShouldReplaceTheSourcesOldOutgoingEdges() {
        PortfolioEntity portfolioA = portfolio(88L, "作品集 A", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity portfolioB = portfolio(99L, "作品集 B", LocalDateTime.parse("2026-08-02T20:00:00"));
        PortfolioEntity portfolioC = portfolio(100L, "作品集 C", LocalDateTime.parse("2026-08-01T20:00:00"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L))
                .thenReturn(List.of(portfolioA, portfolioB, portfolioC));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                link(88L, 100L, "c_a_to_c", PortfolioConfigScopeDict.DRAFT.getCode()),
                link(100L, 88L, "c_old_c_to_a", PortfolioConfigScopeDict.DRAFT.getCode())
        ));
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 100L);

        service().validateReferences(
                graph,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                List.of(link(100L, 99L, "c_new_c_to_b", PortfolioConfigScopeDict.DRAFT.getCode())),
                null
        );
    }

    @Test
    void validateReferencesShouldUsePublishedEdgesForPublishedScope() {
        PortfolioEntity source = portfolio(88L, "来源", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity target = portfolio(99L, "目标", LocalDateTime.parse("2026-08-02T20:00:00"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L)).thenReturn(List.of(source, target));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                link(99L, 88L, "c_published_back", PortfolioConfigScopeDict.PUBLISHED.getCode())
        ));
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 88L);

        assertThatThrownBy(() -> service().validateReferences(
                graph,
                PortfolioConfigScopeDict.PUBLISHED.getCode(),
                List.of(link(88L, 99L, "c_published_cycle", PortfolioConfigScopeDict.PUBLISHED.getCode())),
                null
        )).isInstanceOf(PortfolioValidationException.class)
                .hasMessage("作品集之间不能循环跳转");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(portfolioReferenceEntityMapper).selectList(queryCaptor.capture());
        AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) queryCaptor.getValue();
        assertThat(query.getSqlSegment()).contains("config_scope =");
        assertThat(query.getParamNameValuePairs().values())
                .contains(PortfolioConfigScopeDict.PUBLISHED.getCode());
    }

    @Test
    void validateReferencesShouldLoadOnlyEdgesWhoseSourceIsInTheLockedUserGraph() {
        PortfolioEntity source = portfolio(88L, "来源", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity target = portfolio(99L, "目标", LocalDateTime.parse("2026-08-02T20:00:00"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L)).thenReturn(List.of(source, target));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 88L);

        service().validateReferences(
                graph,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                List.of(link(88L, 99L, "c_link", PortfolioConfigScopeDict.DRAFT.getCode())),
                null
        );

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(portfolioReferenceEntityMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("portfolio_id IN")
                .doesNotContain("owner_id");
    }

    @Test
    void validateReferencesShouldPrefixMenuTitleForGraphErrors() {
        PortfolioEntity source = portfolio(88L, "来源", LocalDateTime.parse("2026-08-03T20:00:00"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L)).thenReturn(List.of(source));
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 88L);
        PortfolioConfigDto config = menuConfig("作品", "c_secondary_link");
        PortfolioReferenceEntity reference = link(
                88L,
                777L,
                "c_secondary_link",
                PortfolioConfigScopeDict.DRAFT.getCode()
        );

        assertThatThrownBy(() -> service().validateReferences(
                graph,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                List.of(reference),
                config
        )).isInstanceOf(PortfolioValidationException.class)
                .hasMessage("【作品】请选择已发布的个人作品集");
    }

    @Test
    void targetListShouldExcludeTargetsWithoutShareCodeOrEnabledComponents() {
        PortfolioEntity source = portfolio(88L, "来源", LocalDateTime.parse("2026-08-01T20:00:00"));
        PortfolioEntity noShareCode = portfolio(99L, "没有分享码", LocalDateTime.parse("2026-08-03T20:00:00"));
        noShareCode.setShareCode(null);
        PortfolioEntity noEnabledComponents = portfolio(
                100L,
                "没有启用组件",
                LocalDateTime.parse("2026-08-02T20:00:00")
        );
        noEnabledComponents.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","components":[
                  {"componentKey":"c_disabled","componentType":"PROFILE","enabled":false,"config":{}}
                ]}
                """);
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(source, noShareCode, noEnabledComponents));

        PortfolioHyperlinkTargetResponse response = service().listTargets(7L, 88L, null);

        assertThat(response.getPortfolios()).isEmpty();
    }

    @Test
    void targetListShouldExcludeSourceDisableCycleAndReturnGenericInvalidSelection() {
        PortfolioEntity source = portfolio(88L, "来源", LocalDateTime.parse("2026-08-01T20:00:00"));
        PortfolioEntity latest = portfolio(99L, "最新", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity cycle = portfolio(100L, "会循环", LocalDateTime.parse("2026-08-02T20:00:00"));
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(source, latest, cycle));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                link(100L, 88L, "c_back", PortfolioConfigScopeDict.DRAFT.getCode())
        ));

        PortfolioHyperlinkTargetResponse response = service().listTargets(7L, 88L, 777L);

        assertThat(response.getPortfolios()).extracting("portfolioId")
                .containsExactly(99L, 100L);
        assertThat(response.getPortfolios().get(0).isSelectable()).isTrue();
        assertThat(response.getPortfolios().get(1).isSelectable()).isFalse();
        assertThat(response.getPortfolios().get(1).getDisabledReason()).isEqualTo("选择后会形成循环引用");
        assertThat(response.getCurrentSelection().getTitle()).isEqualTo("当前选择已不可用");
    }

    @Test
    void targetListWithoutSourceShouldReturnOwnedPublishedTargetsWithoutLeakingCrossOwnerSelection() {
        PortfolioEntity owned = portfolio(99L, "本人作品集", LocalDateTime.parse("2026-08-03T20:00:00"));
        PortfolioEntity crossOwner = portfolio(777L, "其他用户私密标题", LocalDateTime.parse("2026-08-04T20:00:00"));
        crossOwner.setOwnerId(8L);
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(owned, crossOwner));

        PortfolioHyperlinkTargetResponse response = service().listTargets(7L, null, 777L);

        assertThat(response.getPortfolios()).extracting("portfolioId").containsExactly(99L);
        assertThat(response.getCurrentSelection().getPortfolioId()).isEqualTo(777L);
        assertThat(response.getCurrentSelection().getTitle()).isEqualTo("当前选择已不可用");
        assertThat(response.getCurrentSelection().getCoverUrl()).isEmpty();
    }

    @Test
    void deleteGuardShouldReturnSourceTitleAndBothScopes() {
        PortfolioEntity target = portfolio(88L, "目标", LocalDateTime.now());
        PortfolioEntity source = portfolio(99L, "草稿标题", LocalDateTime.now());
        source.setPublishedConfigJson(configJson("正式标题"));
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L)).thenReturn(List.of(target, source));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                link(99L, 88L, "c_draft", PortfolioConfigScopeDict.DRAFT.getCode()),
                link(99L, 88L, "c_published", PortfolioConfigScopeDict.PUBLISHED.getCode())
        ));
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 88L);

        PortfolioValidationException error = catchThrowableOfType(
                () -> service().assertNoIncomingLinks(graph, 88L),
                PortfolioValidationException.class
        );

        assertThat(error.getMessage()).contains("《草稿标题》（草稿版本）", "《正式标题》（已发布版本）");
        PortfolioReferenceFailureResponse data = (PortfolioReferenceFailureResponse) error.getData();
        assertThat(data.getErrorCode()).isEqualTo("PERSONAL_PORTFOLIO_REFERENCED");
        assertThat(data.getReferences()).extracting("sourcePortfolioId", "sourceTitle", "configScope")
                .containsExactly(
                        Tuple.tuple(99L, "草稿标题", "DRAFT"),
                        Tuple.tuple(99L, "正式标题", "PUBLISHED")
                );
    }

    @Test
    void deleteGuardShouldReportOnlyTheRemainingReferenceCountAfterThreeVisibleItems() {
        PortfolioEntity target = portfolio(88L, "目标", LocalDateTime.now());
        List<PortfolioEntity> portfolios = new ArrayList<>();
        portfolios.add(target);
        List<PortfolioReferenceEntity> references = new ArrayList<>();
        for (long sourceId = 90L; sourceId <= 94L; sourceId++) {
            portfolios.add(portfolio(sourceId, "来源" + sourceId, LocalDateTime.now()));
            references.add(link(
                    sourceId,
                    88L,
                    "c_" + sourceId,
                    PortfolioConfigScopeDict.DRAFT.getCode()
            ));
        }
        when(portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(7L)).thenReturn(portfolios);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(references);
        PortfolioHyperlinkGraphService.LockedGraph graph = service().lockUserGraph(7L, 88L);

        assertThatThrownBy(() -> service().assertNoIncomingLinks(graph, 88L))
                .isInstanceOf(PortfolioValidationException.class)
                .hasMessageContaining("等 2 处引用")
                .hasMessageNotContaining("等 5 处引用");
    }

    private PortfolioHyperlinkGraphService service() {
        return new PortfolioHyperlinkGraphService(portfolioEntityMapper, portfolioReferenceEntityMapper);
    }

    private PortfolioEntity portfolio(Long id, String title, LocalDateTime updatedAt) {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(id);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(7L);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setShareCode("PF" + id);
        portfolio.setDraftConfigJson(configJson(title));
        portfolio.setPublishedConfigJson(configJson(title));
        portfolio.setUpdatedAt(updatedAt);
        return portfolio;
    }

    private PortfolioConfigDto menuConfig(String menuTitle, String componentKey) {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        config.setComponents(List.of());
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey(componentKey);
        component.setComponentType("HYPERLINK");
        component.setSortOrder(1000);
        component.setEnabled(true);
        component.setConfig(Map.of());
        PortfolioConfigDto.BottomNavItem first = new PortfolioConfigDto.BottomNavItem();
        first.setKey("home");
        first.setTitle("主页");
        PortfolioConfigDto.BottomNavItem secondary = new PortfolioConfigDto.BottomNavItem();
        secondary.setKey("works");
        secondary.setTitle(menuTitle);
        secondary.setComponents(List.of(component));
        PortfolioConfigDto.BottomNav bottomNav = new PortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(first, secondary));
        config.setBottomNav(bottomNav);
        return config;
    }

    private String configJson(String title) {
        return "{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"" + title
                + "\",\"coverUrl\":\"https://cdn.example/cover.jpg\"},\"components\":[{}]}";
    }

    private PortfolioReferenceEntity link(Long sourceId, Long targetId, String componentKey, String scope) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(sourceId);
        reference.setReferenceType(ReferenceTypeDict.LINKED_PORTFOLIO.getCode());
        reference.setReferenceId(targetId);
        reference.setComponentKey(componentKey);
        reference.setConfigScope(scope);
        reference.setIsValid(1);
        return reference;
    }
}
