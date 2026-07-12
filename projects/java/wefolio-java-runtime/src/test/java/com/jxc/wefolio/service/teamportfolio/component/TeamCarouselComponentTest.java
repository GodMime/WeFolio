package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentService;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队轮播图组件的独立契约测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamCarouselComponentTest {

    /** 组件生产源码目录。 */
    private static final Path CAROUSEL_SOURCE = Path.of("service/teamportfolio/component/carousel");

    /** 轮播图组件禁止依赖的个人作品集类。 */
    private static final List<String> FORBIDDEN_CAROUSEL_DEPENDENCIES = List.of(
            "com.jxc.wefolio.dto.PortfolioConfigDto",
            "com.jxc.wefolio.dto.PortfolioRenderDto",
            "com.jxc.wefolio.service.MinePortfolioService",
            "com.jxc.wefolio.service.VisitorPortfolioService",
            "com.jxc.wefolio.service.PortfolioConfigValidator",
            "com.jxc.wefolio.service.PortfolioRenderService",
            "com.jxc.wefolio.service.PortfolioVisitService",
            "com.jxc.wefolio.service.ContactLeadService",
            "com.jxc.wefolio.service.MineWorkService",
            "dto.portfolio",
            "dto.mine.portfolio"
    );

    /** 组件键。 */
    private static final String COMPONENT_KEY = "team-carousel-1";

    /** 组件路径。 */
    private static final String COMPONENT_PATH = "components[2]";

    /** 团队 ID。 */
    private static final long TEAM_ID = 11L;

    /** 作品集 ID。 */
    private static final long PORTFOLIO_ID = 21L;

    /** 成员 Mapper。 */
    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 作品 Mapper。 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** 团队作品集访问服务。 */
    @Mock
    private TeamPortfolioAccessService teamPortfolioAccessService;

    /** COS 服务。 */
    @Mock
    private CosService cosService;

    /** 初始化 LambdaQueryWrapper 所需的实体表信息。 */
    @BeforeAll
    static void initializeTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamMemberEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkEntity.class);
    }

    /**
     * 配置模型只应表达有序的成员和作品 ID。
     */
    @Test
    void configShouldOwnOnlyItemsAndItemIds() {
        assertFields(TeamCarouselComponentConfig.class, "items");
        assertFields(TeamCarouselComponentConfig.Item.class, "memberUserId", "workId");
    }

    /**
     * 校验器必须拒绝所有非对象数组和非严格 Long 标识，且不得发起数据库查询。
     */
    @Test
    void validatorShouldRejectMalformedConfigAndLossyIdsBeforeQueries() {
        TeamCarouselComponentValidator validator = validator();
        List<JSONObject> malformedConfigs = List.of(
                new JSONObject(),
                JSON.parseObject("{\"items\":{}}"),
                JSON.parseObject("{\"items\":[1]}"),
                JSON.parseObject("{\"items\":[{}]}"),
                configWithRawIds("1", 101L),
                configWithRawIds(1L, "101"),
                configWithRawIds(1L, new BigDecimal("101.5")),
                configWithRawIds(new BigDecimal("1.5"), 101L),
                configWithRawIds(new BigDecimal("9223372036854775808"), 101L),
                configWithRawIds(new BigInteger("9223372036854775808"), 101L),
                configWithRawIds(1L, 0L),
                configWithRawIds(-1L, 101L),
                configWithRawIds(new JSONArray(), 101L)
        );

        for (JSONObject config : malformedConfigs) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(config, context()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("轮播图作品配置不正确");
        }

        verify(teamMemberEntityMapper, never()).selectList(any());
        verify(userEntityMapper, never()).selectBatchIds(any());
        verify(workEntityMapper, never()).selectList(any());
    }

    /**
     * 校验器必须接受无损小数标识，并对成员和作品批量查询应用完整谓词。
     */
    @Test
    void validatorShouldAcceptIntegralBigDecimalAndUseExactBatchPredicates() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L, "摄影师")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲")));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(work(101L, 1L)));

        JSONObject normalized = validator().normalizeAndValidate(
                configWithRawIds(new BigDecimal("1.0"), new BigDecimal("101.0")), context());

        assertThat(normalized.toJSONString()).isEqualTo("{\"items\":[{\"memberUserId\":1,\"workId\":101}]}");
        LambdaQueryWrapper<TeamMemberEntity> memberQuery = captureTeamMemberListQuery();
        assertSqlAndValues(memberQuery, List.of("team_id", "user_id", "join_status", "allow_works", "IN"),
                TEAM_ID, 1L, JoinStatusDict.JOINED.getCode(), 1);
        assertThat(memberQuery.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                TEAM_ID, 1L, JoinStatusDict.JOINED.getCode(), 1);
        verify(userEntityMapper).selectBatchIds(Set.of(1L));
        LambdaQueryWrapper<WorkEntity> workQuery = captureWorkListQuery();
        assertSqlAndValues(workQuery, List.of("id", "IN"), 101L);
        assertThat(workQuery.getParamNameValuePairs().values()).containsExactly(101L);
    }

    /**
     * 渲染器在任何查询前必须独立拒绝畸形、数量越界和重复作品配置。
     */
    @Test
    void rendererShouldRevalidateMalformedBoundaryAndDuplicateConfigsBeforeQueries() {
        List<JSONObject> invalidConfigs = List.of(
                new JSONObject(),
                itemsConfig(),
                itemsConfig(item(1L, 101L), item(1L, 102L), item(1L, 103L), item(1L, 104L), item(1L, 105L),
                        item(1L, 106L), item(1L, 107L), item(1L, 108L), item(1L, 109L), item(1L, 110L)),
                itemsConfig(item(1L, 101L), item(2L, 101L)),
                configWithRawIds(new BigDecimal("1.5"), 101L),
                configWithRawIds(1L, new BigDecimal("9223372036854775808")),
                configWithRawIds("1", 101L)
        );

        for (JSONObject invalidConfig : invalidConfigs) {
            assertThatThrownBy(() -> renderer().render(invalidConfig, context()))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * 渲染器必须接受无损小数标识，并使用与校验器一致的批量查询谓词。
     */
    @Test
    void rendererShouldAcceptIntegralBigDecimalAndUseExactBatchPredicates() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L, "摄影师")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲")));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(work(101L, 1L)));

        JSONObject rendered = renderer().render(
                configWithRawIds(new BigDecimal("1.0"), new BigDecimal("101.0")), context());

        assertThat(rendered.getJSONArray("items").getJSONObject(0)).containsEntry("memberUserId", 1L)
                .containsEntry("workId", 101L);
        LambdaQueryWrapper<TeamMemberEntity> memberQuery = captureTeamMemberListQuery();
        assertSqlAndValues(memberQuery, List.of("team_id", "user_id", "join_status", "allow_works", "IN"),
                TEAM_ID, 1L, JoinStatusDict.JOINED.getCode(), 1);
        LambdaQueryWrapper<WorkEntity> workQuery = captureWorkListQuery();
        assertSqlAndValues(workQuery, List.of("id", "IN"), 101L);
    }

    /**
     * 校验器必须分别处理数量边界和重复作品，并保持输入顺序。
     */
    @Test
    void validatorShouldEnforceLimitsRejectDuplicateAndPreserveOrder() {
        TeamCarouselComponentValidator validator = validator();

        assertThatThrownBy(() -> validator.normalizeAndValidate(itemsConfig(), context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("轮播图至少选择一张图片");
        assertThatThrownBy(() -> validator.normalizeAndValidate(itemsConfig(
                item(1L, 101L), item(1L, 102L), item(1L, 103L), item(1L, 104L), item(1L, 105L),
                item(1L, 106L), item(1L, 107L), item(1L, 108L), item(1L, 109L), item(1L, 110L)
        ), context())).isInstanceOf(BusinessException.class).hasMessage("轮播图最多选择9张图片");
        assertThatThrownBy(() -> validator.normalizeAndValidate(itemsConfig(item(1L, 101L), item(2L, 101L)), context()))
                .isInstanceOf(BusinessException.class).hasMessage("轮播图不能重复选择同一作品");

        TeamMemberEntity firstMember = member(1L, 1L, "摄影师");
        TeamMemberEntity secondMember = member(2L, 2L, "摄像师");
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(firstMember, secondMember));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L))).thenReturn(List.of(user(1L, "甲"), user(2L, "乙")));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(work(102L, 2L), work(101L, 1L)));

        JSONObject normalized = validator.normalizeAndValidate(itemsConfig(item(2L, 102L), item(1L, 101L)), context());

        assertThat(normalized.toJSONString()).isEqualTo("{\"items\":[{\"memberUserId\":2,\"workId\":102},{\"memberUserId\":1,\"workId\":101}]}" );
        verify(teamMemberEntityMapper, times(1)).selectList(any());
        verify(userEntityMapper, times(1)).selectBatchIds(Set.of(1L, 2L));
        verify(workEntityMapper, times(1)).selectList(any());
    }

    /**
     * 校验器必须在每一个成员、用户和作品谓词不满足时拒绝配置，并为视频保留专属提示。
     */
    @Test
    void validatorShouldRequireMembershipActiveUsersAndAvailableImageWorks() {
        TeamCarouselComponentValidator validator = validator();
        JSONObject config = itemsConfig(item(1L, 101L));

        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        assertInvalidCarousel(() -> validator.normalizeAndValidate(config, context()), "轮播图作品不存在或不可用");

        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L, "摄影师")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(disabledUser(1L)));
        assertInvalidCarousel(() -> validator.normalizeAndValidate(config, context()), "轮播图作品不存在或不可用");

        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲")));
        WorkEntity mismatchedOwner = work(101L, 2L);
        when(workEntityMapper.selectList(any())).thenReturn(List.of(mismatchedOwner));
        assertInvalidCarousel(() -> validator.normalizeAndValidate(config, context()), "轮播图作品不存在或不可用");

        WorkEntity inactive = work(101L, 1L);
        inactive.setStatus("PROCESSING");
        when(workEntityMapper.selectList(any())).thenReturn(List.of(inactive));
        assertInvalidCarousel(() -> validator.normalizeAndValidate(config, context()), "轮播图作品不存在或不可用");

        WorkEntity unaudited = work(101L, 1L);
        unaudited.setAuditStatus("PENDING");
        when(workEntityMapper.selectList(any())).thenReturn(List.of(unaudited));
        assertInvalidCarousel(() -> validator.normalizeAndValidate(config, context()), "轮播图作品不存在或不可用");

        WorkEntity video = work(101L, 1L);
        video.setMediaType("VIDEO");
        when(workEntityMapper.selectList(any())).thenReturn(List.of(video));
        assertInvalidCarousel(() -> validator.normalizeAndValidate(config, context()), "轮播图仅支持图片作品");

        verify(teamMemberEntityMapper, times(6)).selectList(any());
        verify(userEntityMapper, times(5)).selectBatchIds(Set.of(1L));
        verify(workEntityMapper, times(4)).selectList(any());
    }

    /**
     * 渲染必须以批量数据重建有序、脱离输入的完整快照，并实现 COS 地址回退。
     */
    @Test
    void rendererShouldBatchLoadPairsAndRenderOrderedDetachedSnapshotWithUrlFallback() {
        TeamMemberEntity firstMember = member(1L, 1L, "摄影师");
        TeamMemberEntity secondMember = member(2L, 2L, "摄像师");
        UserEntity firstUser = user(1L, "甲");
        firstUser.setAvatarUrl("avatar-1");
        UserEntity secondUser = user(2L, "乙");
        secondUser.setAvatarUrl("avatar-2");
        WorkEntity firstWork = work(101L, 1L);
        firstWork.setMediaObjectKey("media/101.jpg");
        firstWork.setCoverObjectKey(" ");
        firstWork.setTitle("首图");
        firstWork.setWidth(1200);
        firstWork.setHeight(800);
        firstWork.setAspectRatio("3:2");
        WorkEntity secondWork = work(102L, 2L);
        secondWork.setMediaObjectKey("media/102.jpg");
        secondWork.setCoverObjectKey("cover/102.jpg");

        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(secondMember, firstMember));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L))).thenReturn(List.of(secondUser, firstUser));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(secondWork, firstWork));
        when(cosService.publicUrl("media/101.jpg")).thenReturn("https://cdn/media/101.jpg");
        when(cosService.publicUrl("media/102.jpg")).thenReturn("https://cdn/media/102.jpg");
        when(cosService.publicUrl("cover/102.jpg")).thenReturn("https://cdn/cover/102.jpg");

        JSONObject normalized = itemsConfig(item(2L, 102L), item(1L, 101L));
        JSONObject rendered = renderer().render(normalized, context());
        normalized.getJSONArray("items").getJSONObject(0).put("workId", 999L);

        assertThat(rendered.getJSONArray("items")).hasSize(2);
        JSONObject first = rendered.getJSONArray("items").getJSONObject(0);
        assertThat(first.keySet()).containsExactlyInAnyOrder(
                "memberUserId", "memberDisplayName", "memberAvatarUrl", "workId", "title", "coverUrl", "mediaUrl",
                "width", "height", "aspectRatio");
        assertThat(first).containsEntry("memberUserId", 2L).containsEntry("memberDisplayName", "乙")
                .containsEntry("memberAvatarUrl", "avatar-2").containsEntry("workId", 102L)
                .containsEntry("coverUrl", "https://cdn/cover/102.jpg").containsEntry("mediaUrl", "https://cdn/media/102.jpg");
        JSONObject second = rendered.getJSONArray("items").getJSONObject(1);
        assertThat(second).containsEntry("memberUserId", 1L).containsEntry("workId", 101L)
                .containsEntry("coverUrl", "https://cdn/media/101.jpg").containsEntry("mediaUrl", "https://cdn/media/101.jpg")
                .containsEntry("width", 1200).containsEntry("height", 800).containsEntry("aspectRatio", "3:2");
        verify(teamMemberEntityMapper, times(1)).selectList(any());
        verify(userEntityMapper, times(1)).selectBatchIds(Set.of(1L, 2L));
        verify(workEntityMapper, times(1)).selectList(any());
        verify(cosService, never()).publicUrl(" ");
    }

    /**
     * 引用必须使用渲染的单项精确快照，并保留未设置的配置作用域。
     */
    @Test
    void extractorShouldEmitExactWorkReferencesWithRenderedItemSnapshotsAndNoScope() {
        TeamMemberEntity membership = member(1L, 1L, "摄影师");
        UserEntity account = user(1L, "甲");
        account.setAvatarUrl("avatar");
        WorkEntity image = work(101L, 1L);
        image.setMediaObjectKey("media/101.jpg");
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(membership));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(account));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(image));
        when(cosService.publicUrl("media/101.jpg")).thenReturn("https://cdn/media/101.jpg");

        TeamCarouselComponentRenderer renderer = renderer();
        JSONObject rendered = renderer.render(itemsConfig(item(1L, 101L)), context());
        List<PortfolioReferenceEntity> references = new TeamCarouselComponentReferenceExtractor(renderer)
                .extract(COMPONENT_KEY, COMPONENT_PATH, itemsConfig(item(1L, 101L)), context());

        assertThat(references).hasSize(1);
        PortfolioReferenceEntity reference = references.getFirst();
        assertThat(reference.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(reference.getReferenceType()).isEqualTo("WORK");
        assertThat(reference.getReferenceId()).isEqualTo(101L);
        assertThat(reference.getComponentKey()).isEqualTo(COMPONENT_KEY);
        assertThat(reference.getComponentPath()).isEqualTo("components[2].items[0]");
        assertThat(reference.getSortOrder()).isZero();
        assertThat(reference.getIsValid()).isEqualTo(1);
        assertThat(reference.getConfigScope()).isNull();
        assertThat(reference.getSnapshotJson()).isEqualTo(JSON.toJSONString(
                rendered.getJSONArray("items").getJSONObject(0), JSONWriter.Feature.WriteNulls));
        JSONObject snapshot = JSON.parseObject(reference.getSnapshotJson());
        assertThat(snapshot.keySet()).containsExactlyInAnyOrder(
                "memberUserId", "memberDisplayName", "memberAvatarUrl", "workId", "title", "coverUrl", "mediaUrl",
                "width", "height", "aspectRatio");
        assertThat(snapshot.getLong("memberUserId")).isEqualTo(1L);
        assertThat(snapshot.getLong("workId")).isEqualTo(101L);
        assertThat(snapshot.getString("mediaUrl")).isEqualTo("https://cdn/media/101.jpg");
    }

    /**
     * 来源服务必须从可维护团队开始，批量筛选活跃成员并保持成员关系的排序。
     */
    @Test
    void sourceServiceShouldListActiveMembersInMembershipOrderWithTwoQueries() {
        TeamEntity team = team();
        TeamMemberEntity first = member(3L, 3L, "策划");
        TeamMemberEntity second = member(4L, 4L, "摄影师");
        UserEntity active = user(4L, "乙");
        active.setAvatarUrl("avatar-4");
        when(teamPortfolioAccessService.requireMaintainablePortfolio(PORTFOLIO_ID, 99L)).thenReturn(access(team));
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(first, second));
        when(userEntityMapper.selectBatchIds(Set.of(3L, 4L))).thenReturn(List.of(active, disabledUser(3L)));

        List<TeamCarouselComponentService.MemberOption> members = service().listMembers(PORTFOLIO_ID, 99L);

        assertThat(members).containsExactly(new TeamCarouselComponentService.MemberOption(4L, "乙", "avatar-4", "摄影师"));
        verify(teamPortfolioAccessService, times(1)).requireMaintainablePortfolio(PORTFOLIO_ID, 99L);
        verify(teamMemberEntityMapper, times(1)).selectList(any());
        verify(userEntityMapper, times(1)).selectBatchIds(Set.of(3L, 4L));
        verify(workEntityMapper, never()).selectList(any());
        LambdaQueryWrapper<TeamMemberEntity> memberQuery = captureTeamMemberListQuery();
        assertSqlAndValues(memberQuery, List.of("team_id", "join_status", "allow_works", "ORDER BY", "id"),
                TEAM_ID, JoinStatusDict.JOINED.getCode(), 1);
        assertThat(memberQuery.getSqlSegment()).contains("ASC");
        assertThat(memberQuery.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                TEAM_ID, JoinStatusDict.JOINED.getCode(), 1);
    }

    /**
     * 来源服务必须先确认成员归属和活跃账号，再一次性按作品排序查询图片作品，并构造 URL 回退。
     */
    @Test
    void sourceServiceShouldListMemberWorksAfterMembershipAndActiveUserChecks() {
        TeamMemberEntity membership = member(1L, 1L, "摄影师");
        WorkEntity first = work(101L, 1L);
        first.setSortOrder(10);
        first.setTitle("首图");
        first.setMediaObjectKey("media/101.jpg");
        first.setCoverObjectKey("cover/101.jpg");
        WorkEntity second = work(102L, 1L);
        second.setSortOrder(20);
        second.setMediaObjectKey("media/102.jpg");
        second.setCoverObjectKey(" ");
        when(teamPortfolioAccessService.requireMaintainablePortfolio(PORTFOLIO_ID, 99L)).thenReturn(access(team()));
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(membership);
        when(userEntityMapper.selectById(1L)).thenReturn(user(1L, "甲"));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(first, second));
        when(cosService.publicUrl("media/101.jpg")).thenReturn("https://cdn/media/101.jpg");
        when(cosService.publicUrl("cover/101.jpg")).thenReturn("https://cdn/cover/101.jpg");
        when(cosService.publicUrl("media/102.jpg")).thenReturn("https://cdn/media/102.jpg");

        List<TeamCarouselComponentService.WorkOption> works = service().listWorks(PORTFOLIO_ID, 1L, 99L);

        assertThat(works).containsExactly(
                new TeamCarouselComponentService.WorkOption(101L, "首图", "https://cdn/cover/101.jpg", "https://cdn/media/101.jpg",
                        null, null, null),
                new TeamCarouselComponentService.WorkOption(102L, null, "https://cdn/media/102.jpg", "https://cdn/media/102.jpg",
                        null, null, null)
        );
        verify(teamPortfolioAccessService, times(1)).requireMaintainablePortfolio(PORTFOLIO_ID, 99L);
        verify(teamMemberEntityMapper, times(1)).selectOne(any());
        verify(userEntityMapper, times(1)).selectById(1L);
        verify(workEntityMapper, times(1)).selectList(any());
        verify(cosService, never()).publicUrl(" ");
        LambdaQueryWrapper<TeamMemberEntity> memberQuery = captureTeamMemberOneQuery();
        assertSqlAndValues(memberQuery, List.of("team_id", "user_id", "join_status", "allow_works", "LIMIT 1"),
                TEAM_ID, 1L, JoinStatusDict.JOINED.getCode(), 1);
        assertThat(memberQuery.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                TEAM_ID, 1L, JoinStatusDict.JOINED.getCode(), 1);
        LambdaQueryWrapper<WorkEntity> workQuery = captureWorkListQuery();
        assertSqlAndValues(workQuery, List.of("user_id", "status", "audit_status", "media_type", "ORDER BY",
                "sort_order", "id"), 1L, WorkStatusDict.ACTIVE.getCode(), WorkAuditStatusDict.PASSED.getCode(),
                MediaTypeDict.IMAGE.getCode());
        assertThat(workQuery.getSqlSegment()).contains("ASC");
        assertThat(workQuery.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                1L, WorkStatusDict.ACTIVE.getCode(), WorkAuditStatusDict.PASSED.getCode(), MediaTypeDict.IMAGE.getCode());
    }

    /**
     * 成员关系缺失时，作品来源服务必须在查询用户或作品前立即失败。
     */
    @Test
    void sourceServiceShouldShortCircuitWhenRequestedMembershipIsMissing() {
        when(teamPortfolioAccessService.requireMaintainablePortfolio(PORTFOLIO_ID, 99L)).thenReturn(access(team()));
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service().listWorks(PORTFOLIO_ID, 1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队成员不存在或不可用");

        verify(teamMemberEntityMapper).selectOne(any());
        verifyNoInteractions(userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * 访问控制失败时，两个来源接口均不得访问任何来源 Mapper。
     */
    @Test
    void sourceServiceShouldNotQueryMappersWhenAccessValidationFails() {
        when(teamPortfolioAccessService.requireMaintainablePortfolio(PORTFOLIO_ID, 99L))
                .thenThrow(new BusinessException("无维护权限"));

        assertThatThrownBy(() -> service().listMembers(PORTFOLIO_ID, 99L))
                .isInstanceOf(BusinessException.class).hasMessage("无维护权限");
        assertThatThrownBy(() -> service().listWorks(PORTFOLIO_ID, 1L, 99L))
                .isInstanceOf(BusinessException.class).hasMessage("无维护权限");

        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * Mapper 返回空结果时，校验、渲染和来源接口必须保持失败或空集语义，并停止后续查询。
     */
    @Test
    void carouselShouldHandleNullMapperResultsAndStopLaterQueries() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(null);
        assertInvalidCarousel(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()),
                "轮播图作品不存在或不可用");
        assertInvalidCarousel(() -> renderer().render(itemsConfig(item(1L, 101L)), context()),
                "轮播图作品不存在或不可用");
        verify(userEntityMapper, never()).selectBatchIds(any());
        verify(workEntityMapper, never()).selectList(any());

        resetSourceMocks();
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L, "摄影师")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(null);
        assertInvalidCarousel(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()),
                "轮播图作品不存在或不可用");
        assertInvalidCarousel(() -> renderer().render(itemsConfig(item(1L, 101L)), context()),
                "轮播图作品不存在或不可用");
        verify(workEntityMapper, never()).selectList(any());

        resetSourceMocks();
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L, "摄影师")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲")));
        when(workEntityMapper.selectList(any())).thenReturn(null);
        assertInvalidCarousel(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()),
                "轮播图作品不存在或不可用");
        assertInvalidCarousel(() -> renderer().render(itemsConfig(item(1L, 101L)), context()),
                "轮播图作品不存在或不可用");

        resetSourceMocks();
        when(teamPortfolioAccessService.requireMaintainablePortfolio(PORTFOLIO_ID, 99L)).thenReturn(access(team()));
        when(teamMemberEntityMapper.selectList(any())).thenReturn(null);
        assertThat(service().listMembers(PORTFOLIO_ID, 99L)).isEmpty();
        verify(userEntityMapper, never()).selectBatchIds(any());

        resetSourceMocks();
        when(teamPortfolioAccessService.requireMaintainablePortfolio(PORTFOLIO_ID, 99L)).thenReturn(access(team()));
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(member(1L, 1L, "摄影师"));
        when(userEntityMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service().listWorks(PORTFOLIO_ID, 1L, 99L))
                .isInstanceOf(BusinessException.class).hasMessage("团队成员不存在或不可用");
        verify(workEntityMapper, never()).selectList(any());
    }

    /**
     * 渲染器必须在 Mapper 返回的数据状态失效时保持作品可用性校验。
     */
    @Test
    void rendererShouldKeepStatusAndMediaValidationForMapperResults() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L, "摄影师")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲")));
        WorkEntity inactive = work(101L, 1L);
        inactive.setStatus(WorkStatusDict.PROCESSING.getCode());
        WorkEntity unaudited = work(101L, 1L);
        unaudited.setAuditStatus(WorkAuditStatusDict.PENDING.getCode());
        WorkEntity video = work(101L, 1L);
        video.setMediaType(MediaTypeDict.VIDEO.getCode());
        when(workEntityMapper.selectList(any())).thenReturn(List.of(inactive), List.of(unaudited), List.of(video));

        assertInvalidCarousel(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "轮播图作品不存在或不可用");
        assertInvalidCarousel(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "轮播图作品不存在或不可用");
        assertInvalidCarousel(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "轮播图仅支持图片作品");
    }

    /**
     * 引用提取器必须通过渲染器继承空、超限、重复和畸形配置的零查询重校验。
     */
    @Test
    void extractorShouldInheritRendererRevalidationBeforeQueries() {
        TeamCarouselComponentReferenceExtractor extractor = new TeamCarouselComponentReferenceExtractor(renderer());
        List<JSONObject> invalidConfigs = List.of(
                itemsConfig(),
                itemsConfig(item(1L, 101L), item(1L, 102L), item(1L, 103L), item(1L, 104L), item(1L, 105L),
                        item(1L, 106L), item(1L, 107L), item(1L, 108L), item(1L, 109L), item(1L, 110L)),
                itemsConfig(item(1L, 101L), item(2L, 101L)),
                configWithRawIds("1", 101L)
        );

        for (JSONObject invalidConfig : invalidConfigs) {
            assertThatThrownBy(() -> extractor.extract(COMPONENT_KEY, COMPONENT_PATH, invalidConfig, context()))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * 五个生产文件必须保持轮播图包的完全独立性，不依赖个人作品集或其他团队组件。
     */
    @Test
    void carouselProductionSourcesShouldBeCompleteAndIndependent() {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio");
        List<Path> sources = listJavaFiles(sourceRoot.resolve(CAROUSEL_SOURCE));

        assertThat(sources).containsExactlyInAnyOrder(
                sourceRoot.resolve(CAROUSEL_SOURCE).resolve("TeamCarouselComponentConfig.java"),
                sourceRoot.resolve(CAROUSEL_SOURCE).resolve("TeamCarouselComponentValidator.java"),
                sourceRoot.resolve(CAROUSEL_SOURCE).resolve("TeamCarouselComponentRenderer.java"),
                sourceRoot.resolve(CAROUSEL_SOURCE).resolve("TeamCarouselComponentReferenceExtractor.java"),
                sourceRoot.resolve(CAROUSEL_SOURCE).resolve("TeamCarouselComponentService.java")
        );

        for (Path source : sources) {
            String content = readSource(source);
            assertThat(hasForbiddenCarouselDependency(content)).isFalse();
            assertThat(Pattern.compile("service\\.teamportfolio\\.component\\.(?!carousel(?:\\.|;))")
                    .matcher(content).find()).isFalse();
        }
        String validatorSource = readSource(sourceRoot.resolve(CAROUSEL_SOURCE).resolve("TeamCarouselComponentValidator.java"));
        assertThat(validatorSource).contains("toJavaObject(TeamCarouselComponentConfig.class)", "selectBatchIds", "selectList");
    }

    /**
     * 禁止依赖扫描必须识别个人作品集 DTO 的完整导入，避免因包名前缀拼写偏差失效。
     */
    @Test
    void forbiddenDependencyScanShouldRejectPortfolioConfigDtoImport() {
        assertThat(hasForbiddenCarouselDependency("import com.jxc.wefolio.dto.PortfolioConfigDto;"))
                .isTrue();
    }

    /**
     * 创建校验器。
     */
    private TeamCarouselComponentValidator validator() {
        return new TeamCarouselComponentValidator(teamMemberEntityMapper, userEntityMapper, workEntityMapper);
    }

    /**
     * 创建渲染器。
     */
    private TeamCarouselComponentRenderer renderer() {
        return new TeamCarouselComponentRenderer(teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * 创建来源服务。
     */
    private TeamCarouselComponentService service() {
        return new TeamCarouselComponentService(
                teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * 返回固定组件上下文。
     */
    private TeamPortfolioComponentContext context() {
        return new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3);
    }

    /**
     * 构造空或给定条目的配置。
     *
     * @param items 条目
     * @return JSON 配置
     */
    private JSONObject itemsConfig(JSONObject... items) {
        JSONObject config = new JSONObject();
        config.put("items", new JSONArray(List.of(items)));
        return config;
    }

    /**
     * 构造单个配置条目。
     *
     * @param memberUserId 成员用户 ID
     * @param workId 作品 ID
     * @return 条目
     */
    private JSONObject item(Object memberUserId, Object workId) {
        JSONObject item = new JSONObject();
        item.put("memberUserId", memberUserId);
        item.put("workId", workId);
        return item;
    }

    /**
     * 构造指定原始 ID 的配置。
     *
     * @param memberUserId 成员用户 ID
     * @param workId 作品 ID
     * @return 配置
     */
    private JSONObject configWithRawIds(Object memberUserId, Object workId) {
        return itemsConfig(item(memberUserId, workId));
    }

    /**
     * 构造有效成员关系。
     */
    private TeamMemberEntity member(Long id, Long userId, String profession) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setId(id);
        member.setTeamId(TEAM_ID);
        member.setUserId(userId);
        member.setProfession(profession);
        member.setJoinStatus("JOINED");
        member.setAllowWorks(1);
        return member;
    }

    /**
     * 构造正常用户。
     */
    private UserEntity user(Long id, String nickname) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        return user;
    }

    /**
     * 构造禁用用户。
     */
    private UserEntity disabledUser(Long id) {
        UserEntity user = user(id, "禁用");
        user.setStatus("DISABLED");
        return user;
    }

    /**
     * 构造通过审核的图片作品。
     */
    private WorkEntity work(Long id, Long userId) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(userId);
        work.setMediaType("IMAGE");
        work.setStatus("ACTIVE");
        work.setAuditStatus("PASSED");
        return work;
    }

    /**
     * 构造当前团队。
     */
    private TeamEntity team() {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        return team;
    }

    /**
     * 构造已校验的维护访问结果。
     */
    private TeamPortfolioAccessService.TeamPortfolioAccess access(TeamEntity team) {
        return new TeamPortfolioAccessService.TeamPortfolioAccess(null, team, null, true, true);
    }

    /**
     * 断言轮播图业务异常。
     */
    private void assertInvalidCarousel(Runnable action, String message) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).hasMessage(message);
    }

    /**
     * 捕获成员列表查询包装器。
     *
     * @return 成员列表查询包装器
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<TeamMemberEntity> captureTeamMemberListQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberEntityMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /**
     * 捕获单条成员查询包装器。
     *
     * @return 单条成员查询包装器
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<TeamMemberEntity> captureTeamMemberOneQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberEntityMapper).selectOne(captor.capture());
        return captor.getValue();
    }

    /**
     * 捕获作品列表查询包装器。
     *
     * @return 作品列表查询包装器
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<WorkEntity> captureWorkListQuery() {
        ArgumentCaptor<LambdaQueryWrapper<WorkEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workEntityMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /**
     * 断言查询 SQL 片段和绑定参数均包含预期值。
     *
     * @param wrapper 查询包装器
     * @param sqlFragments 预期 SQL 片段
     * @param values 预期参数值
     */
    private void assertSqlAndValues(LambdaQueryWrapper<?> wrapper, List<String> sqlFragments, Object... values) {
        assertThat(wrapper.getSqlSegment()).contains(sqlFragments.toArray(String[]::new));
        assertThat(wrapper.getParamNameValuePairs().values()).contains(values);
    }

    /**
     * 重置来源服务场景需要的 Mock。
     */
    private void resetSourceMocks() {
        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    /**
     * 断言模型实例字段。
     */
    private void assertFields(Class<?> type, String... expectedNames) {
        assertThat(Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
                .containsExactlyInAnyOrder(expectedNames);
    }

    /**
     * 判断源码是否包含轮播图组件禁止依赖。
     *
     * @param sourceContent 源码文本
     * @return 是否包含禁止依赖
     */
    private boolean hasForbiddenCarouselDependency(String sourceContent) {
        return FORBIDDEN_CAROUSEL_DEPENDENCIES.stream().anyMatch(sourceContent::contains);
    }

    /**
     * 列出目录下的 Java 源码。
     */
    private List<Path> listJavaFiles(Path packagePath) {
        try (var files = Files.list(packagePath)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 读取源码文本。
     */
    private String readSource(Path source) {
        try {
            return Files.readString(source);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
