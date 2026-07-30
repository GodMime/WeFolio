package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentService;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队单个作品组件配置、渲染和引用测试。
 */
class TeamSingleWorkComponentTest {

    /** 团队上下文。 */
    private static final TeamPortfolioComponentContext CONTEXT =
            new TeamPortfolioComponentContext(11L, 22L, 3);

    /**
     * 配置必须严格白名单化，渲染保留图片或视频完整快照，并生成 WORK 引用。
     */
    @Test
    void componentShouldNormalizeRenderAndExtractWorkReference() {
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
        CosService cosService = mock(CosService.class);
        TeamMemberEntity member = member();
        UserEntity user = user();
        WorkEntity work = work();
        when(memberMapper.selectList(any())).thenReturn(List.of(member));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));
        when(workMapper.selectList(any())).thenReturn(List.of(work));
        when(cosService.publicUrl("media/video.mp4")).thenReturn("https://cdn/video.mp4");
        when(cosService.publicUrl("cover/video.jpg")).thenReturn("https://cdn/video.jpg");

        JSONObject raw = new JSONObject();
        raw.put("memberUserId", 7);
        raw.put("workId", 9);
        raw.put("showTitle", false);
        raw.put("showDescription", true);
        raw.put("unknown", "drop");
        TeamSingleWorkComponentValidator validator =
                new TeamSingleWorkComponentValidator(memberMapper, userMapper, workMapper);

        JSONObject normalized = validator.normalizeAndValidate(raw, CONTEXT);

        assertThat(normalized.keySet())
                .containsExactly("memberUserId", "workId", "showTitle", "showDescription");
        assertThat(normalized.getBooleanValue("showTitle")).isFalse();
        assertThat(normalized.getBooleanValue("showDescription")).isTrue();

        TeamSingleWorkComponentRenderer renderer =
                new TeamSingleWorkComponentRenderer(memberMapper, userMapper, workMapper, cosService);
        JSONObject rendered = renderer.render(normalized, CONTEXT);
        JSONObject renderedWork = rendered.getJSONObject("work");
        assertThat(rendered.getBooleanValue("showTitle")).isFalse();
        assertThat(rendered.getBooleanValue("showDescription")).isTrue();
        assertThat(renderedWork.getLongValue("memberUserId")).isEqualTo(7L);
        assertThat(renderedWork.getLongValue("workId")).isEqualTo(9L);
        assertThat(renderedWork.getString("description")).isEqualTo("作品说明");
        assertThat(renderedWork.getString("mediaType")).isEqualTo(MediaTypeDict.VIDEO.getCode());
        assertThat(renderedWork.getString("coverUrl")).isEqualTo("https://cdn/video.jpg");
        assertThat(renderedWork.getString("mediaUrl")).isEqualTo("https://cdn/video.mp4");
        assertThat(renderedWork.getInteger("durationMs")).isEqualTo(8000);

        TeamSingleWorkComponentReferenceExtractor extractor =
                new TeamSingleWorkComponentReferenceExtractor(renderer);
        List<PortfolioReferenceEntity> references =
                extractor.extract("single-a", "components[2]", normalized, CONTEXT);

        assertThat(references).singleElement().satisfies(reference -> {
            assertThat(reference.getReferenceType()).isEqualTo(ReferenceTypeDict.WORK.getCode());
            assertThat(reference.getReferenceId()).isEqualTo(9L);
            assertThat(reference.getComponentKey()).isEqualTo("single-a");
            assertThat(reference.getComponentPath()).isEqualTo("components[2].config.workId");
            assertThat(reference.getSnapshotJson()).contains("\"memberUserId\":7", "\"workId\":9");
        });
    }

    /**
     * 候选接口必须先筛选已授权正常成员，再同时返回图片、视频和动图作品。
     */
    @Test
    void sourceServiceShouldListAuthorizedMembersAndImageVideoWorks() {
        TeamPortfolioAccessService accessService = mock(TeamPortfolioAccessService.class);
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
        CosService cosService = mock(CosService.class);
        TeamEntity team = new TeamEntity();
        team.setId(CONTEXT.teamId());
        TeamMemberEntity membership = member();
        UserEntity user = user();
        user.setNickname("甲");
        WorkEntity image = work();
        image.setId(8L);
        image.setMediaType(MediaTypeDict.IMAGE.getCode());
        image.setMediaObjectKey("media/image.jpg");
        image.setCoverObjectKey(null);
        WorkEntity animation = work();
        animation.setId(10L);
        animation.setMediaType(MediaTypeDict.ANIMATION.getCode());
        animation.setMediaObjectKey("media/animation.gif");
        animation.setCoverObjectKey("cover/animation.jpg");
        when(accessService.requireMaintainablePortfolio(22L, 99L))
                .thenReturn(new TeamPortfolioAccessService.TeamPortfolioAccess(
                        null, team, membership, true, true));
        when(accessService.requireTeamRole(eq(11L), eq(99L), any()))
                .thenReturn(new TeamPortfolioAccessService.TeamPortfolioAccess(
                        null, team, membership, true, true));
        when(memberMapper.selectList(any())).thenReturn(List.of(membership));
        when(memberMapper.selectOne(any())).thenReturn(membership);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));
        when(userMapper.selectById(7L)).thenReturn(user);
        when(workMapper.selectList(any())).thenReturn(List.of(image, work(), animation));
        when(cosService.publicUrl("media/image.jpg")).thenReturn("https://cdn/image.jpg");
        when(cosService.publicUrl("media/video.mp4")).thenReturn("https://cdn/video.mp4");
        when(cosService.publicUrl("cover/video.jpg")).thenReturn("https://cdn/video.jpg");
        when(cosService.publicUrl("media/animation.gif")).thenReturn("https://cdn/animation.gif");
        when(cosService.publicUrl("cover/animation.jpg")).thenReturn("https://cdn/animation.jpg");
        TeamSingleWorkComponentService service = new TeamSingleWorkComponentService(
                accessService, memberMapper, userMapper, workMapper, cosService);

        assertThat(service.listMembers(22L, 99L))
                .extracting(TeamSingleWorkComponentService.MemberOption::memberUserId)
                .containsExactly(7L);
        assertThat(service.listWorks(22L, 7L, 99L))
                .extracting(TeamSingleWorkComponentService.WorkOption::mediaType)
                .containsExactly(
                        MediaTypeDict.IMAGE.getCode(),
                        MediaTypeDict.VIDEO.getCode(),
                        MediaTypeDict.ANIMATION.getCode());
        assertThat(service.listTeamMembers(11L, 99L))
                .extracting(TeamSingleWorkComponentService.MemberOption::memberUserId)
                .containsExactly(7L);
        assertThat(service.listTeamWorks(11L, 7L, 99L))
                .extracting(TeamSingleWorkComponentService.WorkOption::mediaType)
                .containsExactly(
                        MediaTypeDict.IMAGE.getCode(),
                        MediaTypeDict.VIDEO.getCode(),
                        MediaTypeDict.ANIMATION.getCode());
        verify(accessService, times(2)).requireTeamRole(
                eq(11L),
                eq(99L),
                argThat(roles -> roles.equals(Set.of(
                        TeamRoleDict.OWNER.getCode(),
                        TeamRoleDict.MANAGER.getCode())))
        );
    }

    /**
     * 非布尔开关必须回落到标题开、说明关，字符串 ID 必须被拒绝。
     */
    @Test
    void validatorShouldDefaultInvalidSwitchesAndRejectStringIds() {
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user()));
        when(workMapper.selectList(any())).thenReturn(List.of(work()));
        TeamSingleWorkComponentValidator validator =
                new TeamSingleWorkComponentValidator(memberMapper, userMapper, workMapper);
        JSONObject raw = new JSONObject();
        raw.put("memberUserId", 7);
        raw.put("workId", 9);
        raw.put("showTitle", "false");
        raw.put("showDescription", 1);

        JSONObject normalized = validator.normalizeAndValidate(raw, CONTEXT);

        assertThat(normalized.getBooleanValue("showTitle")).isTrue();
        assertThat(normalized.getBooleanValue("showDescription")).isFalse();
        raw.put("workId", "9");
        assertThatThrownBy(() -> validator.normalizeAndValidate(raw, CONTEXT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
    }

    /**
     * 未授权成员、错误归属和不可展示状态都必须阻断保存。
     */
    @Test
    void validatorShouldRejectUnauthorizedMemberAndUnusableWorks() {
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
        TeamSingleWorkComponentValidator validator =
                new TeamSingleWorkComponentValidator(memberMapper, userMapper, workMapper);
        JSONObject config = new JSONObject();
        config.put("memberUserId", 7);
        config.put("workId", 9);
        when(memberMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> validator.normalizeAndValidate(config, CONTEXT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE);

        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user()));
        List<Consumer<WorkEntity>> invalidations = List.of(
                item -> item.setUserId(8L),
                item -> item.setStatus(WorkStatusDict.PROCESSING.getCode()),
                item -> item.setAuditStatus(WorkAuditStatusDict.PENDING.getCode()),
                item -> item.setMediaType("AUDIO")
        );
        for (Consumer<WorkEntity> invalidate : invalidations) {
            WorkEntity invalidWork = work();
            invalidate.accept(invalidWork);
            when(workMapper.selectList(any())).thenReturn(List.of(invalidWork));
            assertThatThrownBy(() -> validator.normalizeAndValidate(config, CONTEXT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE);
        }
    }

    @Test
    void validatorAndRendererShouldAcceptAnimationWork() {
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
        CosService cosService = mock(CosService.class);
        WorkEntity animation = work();
        animation.setMediaType(MediaTypeDict.ANIMATION.getCode());
        animation.setMediaObjectKey("media/animation.gif");
        animation.setCoverObjectKey("cover/animation.jpg");
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user()));
        when(workMapper.selectList(any())).thenReturn(List.of(animation));
        when(cosService.publicUrl("media/animation.gif")).thenReturn("https://cdn/animation.gif");
        when(cosService.publicUrl("cover/animation.jpg")).thenReturn("https://cdn/animation.jpg");
        JSONObject config = new JSONObject();
        config.put("memberUserId", 7L);
        config.put("workId", 9L);

        JSONObject normalized = new TeamSingleWorkComponentValidator(
                memberMapper, userMapper, workMapper).normalizeAndValidate(config, CONTEXT);
        JSONObject rendered = new TeamSingleWorkComponentRenderer(
                memberMapper, userMapper, workMapper, cosService).render(normalized, CONTEXT);

        assertThat(rendered.getJSONObject("work").getString("mediaType"))
                .isEqualTo(MediaTypeDict.ANIMATION.getCode());
        assertThat(rendered.getJSONObject("work").getString("mediaUrl"))
                .isEqualTo("https://cdn/animation.gif");
    }

    private TeamMemberEntity member() {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(CONTEXT.teamId());
        member.setUserId(7L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode());
        member.setAllowWorks(1);
        return member;
    }

    private UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }

    private WorkEntity work() {
        WorkEntity work = new WorkEntity();
        work.setId(9L);
        work.setUserId(7L);
        work.setTitle("视频作品");
        work.setDescription("作品说明");
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("media/video.mp4");
        work.setCoverObjectKey("cover/video.jpg");
        work.setDurationMs(8000);
        work.setWidth(1920);
        work.setHeight(1080);
        work.setAspectRatio("16:9");
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        return work;
    }
}
