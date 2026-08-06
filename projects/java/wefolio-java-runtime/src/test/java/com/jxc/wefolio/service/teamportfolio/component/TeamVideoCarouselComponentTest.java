package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队视频轮播组件独立契约测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamVideoCarouselComponentTest {

    /** 团队 ID */
    private static final long TEAM_ID = 11L;

    /** 作品集 ID */
    private static final long PORTFOLIO_ID = 21L;

    /** 组件键 */
    private static final String COMPONENT_KEY = "team-video-carousel-1";

    /** 组件路径 */
    private static final String COMPONENT_PATH = "bottomNav.items[1].components[2]";

    /** 成员 Mapper */
    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 作品 Mapper */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** COS 服务 */
    @Mock
    private CosService cosService;

    /** 初始化 Lambda 查询元数据。 */
    @BeforeAll
    static void initializeTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamMemberEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkEntity.class);
    }

    /**
     * 配置模型只能保存展示开关与成员、作品标识。
     */
    @Test
    void configShouldContainOnlyDisplayOptionsAndIdentifiers() {
        assertFields(TeamVideoCarouselComponentConfig.class,
                "title", "items", "showTitle", "showSwipeHint");
        assertFields(TeamVideoCarouselComponentConfig.Item.class, "memberUserId", "workId");
    }

    /**
     * 校验器应规范化标题和开关，且不保存快照、URL 或未支持字段。
     */
    @Test
    void validatorShouldNormalizeIdentifierOnlyConfigAndDefaults() {
        stubValidResources();
        JSONObject raw = config("  团队影像  ",
                item(2L, 102L), item(1L, 101L), item(3L, 103L));
        raw.put("snapshot", "discard");

        JSONObject normalized = validator().normalizeAndValidate(raw, context());

        assertThat(normalized.toJSONString()).isEqualTo(
                "{\"title\":\"团队影像\",\"items\":[{\"memberUserId\":2,\"workId\":102},"
                        + "{\"memberUserId\":1,\"workId\":101},{\"memberUserId\":3,\"workId\":103}],"
                        + "\"showTitle\":true,\"showSwipeHint\":true}");
    }

    /**
     * 非字符串标题必须归一化为默认标题，不得把 JSON 数字转为文案。
     */
    @Test
    void validatorShouldDefaultNonStringTitle() {
        stubValidResources();
        JSONObject raw = config("", item(1L, 101L), item(2L, 102L), item(3L, 103L));
        raw.put("title", 123);

        JSONObject normalized = validator().normalizeAndValidate(raw, context());

        assertThat(normalized.getString("title")).isEqualTo("视频作品");
    }

    /**
     * 标题、数量、重复作品和标识类型必须在查库前拦截。
     */
    @Test
    void validatorShouldRejectInvalidTitleCountDuplicateAndIdentifiersBeforeQueries() {
        TeamVideoCarouselComponentValidator validator = validator();

        assertThatThrownBy(() -> validator.normalizeAndValidate(config("", item(1L, 101L), item(2L, 102L)), context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播需选择3至8个视频作品");
        assertThatThrownBy(() -> validator.normalizeAndValidate(config("", nineItems()), context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播需选择3至8个视频作品");
        assertThatThrownBy(() -> validator.normalizeAndValidate(config("", item(1L, 101L), item(2L, 101L), item(3L, 103L)), context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播不能重复选择同一视频作品");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                config("一二三四五六七八九十一", item(1L, 101L), item(2L, 102L), item(3L, 103L)),
                context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播标题不能超过10个字符");
        JSONObject fractional = config("", item(1L, 101L), item(2L, 102L), item(3L, 103L));
        fractional.getJSONArray("items").getJSONObject(0).put("workId", new BigDecimal("101.5"));
        assertThatThrownBy(() -> validator.normalizeAndValidate(fractional, context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播作品配置不正确");

        verify(teamMemberEntityMapper, never()).selectList(any());
        verify(userEntityMapper, never()).selectBatchIds(any());
        verify(workEntityMapper, never()).selectList(any());
    }

    /**
     * 保存与发布每次都必须重读成员授权、账号和作品状态。
     */
    @Test
    void validatorShouldStrictlyReReadMembershipAccountWorkAndOwner() {
        when(teamMemberEntityMapper.selectList(any()))
                .thenReturn(List.of(member(1L), member(2L), member(3L)))
                .thenReturn(List.of(member(1L), member(2L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(user(1L), user(2L), user(3L)));
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(101L, 1L), video(102L, 2L), video(103L, 3L)));
        JSONObject raw = config("", item(1L, 101L), item(2L, 102L), item(3L, 103L));
        JSONObject normalized = validator().normalizeAndValidate(raw, context());

        assertThatThrownBy(() -> validator().normalizeAndValidate(normalized, context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播作品不存在或不可用");

        reset(teamMemberEntityMapper, userEntityMapper, workEntityMapper);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L), member(2L), member(3L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(user(1L), user(2L), user(3L)));
        WorkEntity wrongOwner = video(103L, 9L);
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(101L, 1L), video(102L, 2L), wrongOwner));

        assertThatThrownBy(() -> validator().normalizeAndValidate(raw, context()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频轮播作品不存在或不可用");

        WorkEntity inactive = video(103L, 3L);
        inactive.setStatus(WorkStatusDict.PROCESSING.getCode());
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(101L, 1L), video(102L, 2L), inactive));
        assertThatThrownBy(() -> validator().normalizeAndValidate(raw, context()))
                .isInstanceOf(BusinessException.class);

        WorkEntity auditing = video(103L, 3L);
        auditing.setAuditStatus(WorkAuditStatusDict.AUDITING.getCode());
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(101L, 1L), video(102L, 2L), auditing));
        assertThatThrownBy(() -> validator().normalizeAndValidate(raw, context()))
                .isInstanceOf(BusinessException.class);

        WorkEntity image = video(103L, 3L);
        image.setMediaType(MediaTypeDict.IMAGE.getCode());
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(101L, 1L), video(102L, 2L), image));
        assertThatThrownBy(() -> validator().normalizeAndValidate(raw, context()))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 已发布配置渲染时应跳过已失效条目，剩一条仍输出并使用当前快照。
     */
    @Test
    void rendererShouldTolerantlySkipInvalidItemsAndAvoidCosHeadChecks() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L), member(2L), member(3L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(user(1L), user(2L), user(3L)));
        WorkEntity passed = video(101L, 1L);
        passed.setTitle("婚礼纪录");
        passed.setDescription("草坪仪式");
        passed.setMediaObjectKey("video/101.mp4");
        passed.setCoverObjectKey("cover/101.jpg");
        passed.setDurationMs(18000);
        passed.setWidth(1080);
        passed.setHeight(1920);
        passed.setAspectRatio("9:16");
        WorkEntity auditing = video(102L, 2L);
        auditing.setAuditStatus(WorkAuditStatusDict.AUDITING.getCode());
        WorkEntity image = video(103L, 3L);
        image.setMediaType(MediaTypeDict.IMAGE.getCode());
        when(workEntityMapper.selectList(any())).thenReturn(List.of(image, auditing, passed));
        when(cosService.publicUrl("video/101.mp4")).thenReturn("https://cdn/video/101.mp4");
        when(cosService.publicUrl("cover/101.jpg")).thenReturn("https://cdn/cover/101.jpg");

        JSONObject rendered = renderer().render(
                config("", item(3L, 103L), item(1L, 101L), item(2L, 102L)), context());

        assertThat(rendered).isNotNull();
        assertThat(rendered.getString("title")).isEqualTo("视频作品");
        assertThat(rendered.getBooleanValue("showTitle")).isTrue();
        assertThat(rendered.getBooleanValue("showSwipeHint")).isTrue();
        assertThat(rendered.getJSONArray("items")).singleElement().satisfies(value -> {
            JSONObject item = (JSONObject) value;
            assertThat(item).containsEntry("memberUserId", 1L)
                    .containsEntry("memberDisplayName", "成员1")
                    .containsEntry("workId", 101L)
                    .containsEntry("title", "婚礼纪录")
                    .containsEntry("mediaType", MediaTypeDict.VIDEO.getCode())
                    .containsEntry("coverUrl", "https://cdn/cover/101.jpg")
                    .containsEntry("mediaUrl", "https://cdn/video/101.mp4")
                    .containsEntry("durationMs", 18000)
                    .containsEntry("description", "草坪仪式")
                    .containsEntry("aspectRatio", "9:16");
        });
        verify(cosService, never()).headObject(any());
    }

    /**
     * 渲染快照中的成员展示字段必须归一化为空字符串，避免向前端输出 null。
     */
    @Test
    void rendererShouldNormalizeNullMemberDisplayFields() {
        UserEntity unnamed = user(1L);
        unnamed.setNickname(null);
        unnamed.setAvatarUrl(null);
        when(teamMemberEntityMapper.selectList(any()))
                .thenReturn(List.of(member(1L), member(2L), member(3L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(unnamed, user(2L), user(3L)));
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(101L, 1L), video(102L, 2L), video(103L, 3L)));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));

        JSONObject rendered = renderer().render(
                config("", item(1L, 101L), item(2L, 102L), item(3L, 103L)), context());

        JSONObject first = rendered.getJSONArray("items").getJSONObject(0);
        assertThat(first).containsEntry("memberDisplayName", "")
                .containsEntry("memberAvatarUrl", "");
    }

    /**
     * 全部条目失效时渲染器应返回空信号，供顶层省略整个组件。
     */
    @Test
    void rendererShouldReturnNullWhenNoItemsSurvive() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());

        JSONObject rendered = renderer().render(
                config("", item(1L, 101L), item(2L, 102L), item(3L, 103L)), context());

        assertThat(rendered).isNull();
        verify(userEntityMapper, times(1)).selectBatchIds(Set.of(1L, 2L, 3L));
        verify(workEntityMapper, times(1)).selectList(any());
    }

    /**
     * 引用应使用原配置项下标的路径，并保留视频顺序与当前快照。
     */
    @Test
    void extractorShouldPreserveItemOrderAndCanonicalPaths() {
        stubValidResources();
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cdn/" + invocation.getArgument(0));
        JSONObject config = config("精选视频", item(2L, 102L), item(1L, 101L), item(3L, 103L));

        List<PortfolioReferenceEntity> references = new TeamVideoCarouselComponentReferenceExtractor(renderer())
                .extract(COMPONENT_KEY, COMPONENT_PATH, config, context());

        assertThat(references).extracting(
                        PortfolioReferenceEntity::getReferenceId,
                        PortfolioReferenceEntity::getComponentPath,
                        PortfolioReferenceEntity::getSortOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(102L,
                                COMPONENT_PATH + ".config.items[0]", 0),
                        org.assertj.core.groups.Tuple.tuple(101L,
                                COMPONENT_PATH + ".config.items[1]", 1),
                        org.assertj.core.groups.Tuple.tuple(103L,
                                COMPONENT_PATH + ".config.items[2]", 2)
                );
        assertThat(references).allSatisfy(reference -> {
            assertThat(reference.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
            assertThat(reference.getReferenceType()).isEqualTo("WORK");
            assertThat(reference.getComponentKey()).isEqualTo(COMPONENT_KEY);
            assertThat(JSON.parseObject(reference.getSnapshotJson()).getString("mediaType"))
                    .isEqualTo(MediaTypeDict.VIDEO.getCode());
        });
    }

    private TeamVideoCarouselComponentValidator validator() {
        return new TeamVideoCarouselComponentValidator(
                teamMemberEntityMapper, userEntityMapper, workEntityMapper);
    }

    private TeamVideoCarouselComponentRenderer renderer() {
        return new TeamVideoCarouselComponentRenderer(
                teamMemberEntityMapper, userEntityMapper, workEntityMapper, cosService);
    }

    private TeamPortfolioComponentContext context() {
        return new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3);
    }

    private void stubValidResources() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L), member(2L), member(3L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(user(1L), user(2L), user(3L)));
        when(workEntityMapper.selectList(any()))
                .thenReturn(List.of(video(103L, 3L), video(101L, 1L), video(102L, 2L)));
    }

    private JSONObject config(String title, JSONObject... items) {
        JSONObject config = new JSONObject();
        config.put("title", title);
        config.put("items", new JSONArray(List.of(items)));
        return config;
    }

    private JSONObject[] nineItems() {
        JSONObject[] items = new JSONObject[9];
        for (int index = 0; index < items.length; index++) {
            items[index] = item(index + 1L, 101L + index);
        }
        return items;
    }

    private JSONObject item(Long memberUserId, Long workId) {
        JSONObject item = new JSONObject();
        item.put("memberUserId", memberUserId);
        item.put("workId", workId);
        return item;
    }

    private TeamMemberEntity member(Long userId) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(TEAM_ID);
        member.setUserId(userId);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode());
        member.setAllowWorks(1);
        return member;
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setNickname("成员" + id);
        user.setAvatarUrl("avatar-" + id);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }

    private WorkEntity video(Long id, Long userId) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(userId);
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        work.setTitle("视频" + id);
        work.setMediaObjectKey("video/" + id + ".mp4");
        work.setCoverObjectKey("cover/" + id + ".jpg");
        return work;
    }

    private void assertFields(Class<?> type, String... expected) {
        assertThat(Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
                .containsExactlyInAnyOrder(expected);
    }
}
