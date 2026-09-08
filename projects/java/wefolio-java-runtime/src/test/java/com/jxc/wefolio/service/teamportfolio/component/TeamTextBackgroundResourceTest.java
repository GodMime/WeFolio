package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentReferenceExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 两类团队文字背景的权限矩阵、独立引用与失效显示测试。 */
class TeamTextBackgroundResourceTest {
    /** 成员持久化边界。 */
    private final TeamMemberEntityMapper members = mock(TeamMemberEntityMapper.class);
    /** 账号持久化边界。 */
    private final UserEntityMapper users = mock(UserEntityMapper.class);
    /** 作品持久化边界。 */
    private final WorkEntityMapper works = mock(WorkEntityMapper.class);
    /** URL 服务边界。 */
    private final CosService cos = mock(CosService.class);
    /** 实际共用背景支持。 */
    private final TeamTextBackgroundSupport support = new TeamTextBackgroundSupport(members,users,works,cos);
    /** 当前团队作品集上下文。 */
    private final TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(1,2,3);
    /** 可修改的真实成员记录。 */
    private TeamMemberEntity member;
    /** 可修改的真实账号记录。 */
    private UserEntity user;
    /** 可修改的真实作品记录。 */
    private WorkEntity work;

    /** 准备完整且合法的关联数据。 */
    @BeforeEach void setUp() {
        member = new TeamMemberEntity(); member.setTeamId(1L); member.setUserId(7L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(1);
        user = new UserEntity(); user.setId(7L); user.setStatus(UserStatusDict.ACTIVE.getCode());
        work = new WorkEntity(); work.setId(11L); work.setUserId(7L); work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        work.setMediaObjectKey("original.gif"); work.setCoverObjectKey("static-cover.jpg"); work.setWidth(600); work.setHeight(900);
        when(members.selectList(any())).thenReturn(List.of(member));
        when(users.selectBatchIds(anyCollection())).thenReturn(List.of(user));
        when(works.selectBatchIds(anyCollection())).thenReturn(List.of(work));
        when(cos.publicUrl("original.gif")).thenReturn("https://cdn.example/original.gif");
    }

    /** 图片和动图都可保存；背景快照包含真实作者授权的原资源和尺寸。 */
    @Test void bothComponentsAcceptImagesAnimationsAndCreateIndependentWorkReferences() {
        for (boolean structured : List.of(false,true)) {
            for (MediaTypeDict media : List.of(MediaTypeDict.IMAGE,MediaTypeDict.ANIMATION)) {
                work.setMediaType(media.getCode());
                JSONObject normalized = normalize(structured,input());
                assertThat(normalized).containsEntry("backgroundWorkId",11L).containsEntry("backgroundMemberUserId",7L)
                        .doesNotContainKeys("backgroundWork","backgroundInvalid");
                JSONObject data = render(structured,normalized,context);
                JSONObject snapshot = JSON.parseObject(JSON.toJSONString(data.get("backgroundWork")));
                assertThat(snapshot).containsEntry("url","https://cdn.example/original.gif")
                        .containsEntry("width",600).containsEntry("height",900).containsEntry("mediaType",media.getCode());
                assertThat(data).containsEntry("backgroundInvalid",false);
                if (structured) {
                    assertThat(data.getJSONArray("blocks").getJSONObject(0).getString("color")).isEqualTo("AUTO");
                    assertThat(data).doesNotContainKeys("verticalAlignment","content");
                }
                List<PortfolioReferenceEntity> references = extract(structured,normalized);
                assertThat(references).hasSize(1);
                assertThat(references.getFirst().getComponentPath()).isEqualTo("bottomNav.items[1].components[0].config.backgroundWorkId");
                assertThat(references.getFirst().getReferenceId()).isEqualTo(11L);
                assertThat(references.getFirst().getReferenceType()).isEqualTo("WORK");
            }
        }
        verify(cos,never()).publicUrl("static-cover.jpg");
    }

    /** 授权、入队、账号、作品状态、审核、实际拥有者、视频任一不合法均拒绝。 */
    @Test void rejectsEachPermissionAndMediaFailureAndHidesUnauthorizedUrl() {
        List<Runnable> invalidators = List.of(() -> member.setAllowWorks(0),
                () -> member.setJoinStatus(JoinStatusDict.REMOVED.getCode()), () -> member.setTeamId(9L),
                () -> user.setStatus(UserStatusDict.DISABLED.getCode()), () -> work.setUserId(8L),
                () -> work.setStatus(WorkStatusDict.PROCESSING.getCode()), () -> work.setAuditStatus(WorkAuditStatusDict.PENDING.getCode()),
                () -> work.setMediaType(MediaTypeDict.VIDEO.getCode()));
        for (Runnable invalidator : invalidators) {
            setUp(); invalidator.run();
            for (boolean structured : List.of(false,true)) {
                assertThatThrownBy(() -> normalize(structured,input())).isInstanceOf(BusinessException.class);
                assertThatThrownBy(() -> extract(structured,input())).isInstanceOf(BusinessException.class);
                JSONObject rendered = render(structured,input(),context);
                assertThat(rendered).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
                assertThat(rendered.get("backgroundWork")).isNull();
                assertThat(rendered.toJSONString()).doesNotContain("injected", "https://cdn.example");
                assertThat(rendered).containsKey(structured ? "blocks" : "content");
            }
        }
    }

    /** 声称属于别的授权成员也不能使用实际拥有者不匹配的作品。 */
    @Test void rejectsForgedMemberAndMissingResourceSelection() {
        for (boolean structured : List.of(false,true)) {
            JSONObject config = input(); config.put("backgroundMemberUserId",8L);
            assertThatThrownBy(() -> normalize(structured,config)).isInstanceOf(BusinessException.class);
            config.put("backgroundMemberUserId",7L); config.remove("backgroundWorkId");
            assertThatThrownBy(() -> normalize(structured,config)).isInstanceOf(BusinessException.class);
            JSONObject rendered = render(structured,config,context);
            assertThat(rendered).containsEntry("backgroundInvalid",true);
            assertThat(rendered).containsKey(structured ? "blocks" : "content");
        }
    }

    /** 关闭背景清除资源且不生成引用，视觉偏好仍恢复。 */
    @Test void closingBackgroundClearsIdsAndReferences() {
        for (boolean structured : List.of(false,true)) {
            JSONObject config = input(); config.put("backgroundEnabled",false);
            JSONObject normalized = normalize(structured,config);
            assertThat(normalized).doesNotContainKeys("backgroundWorkId","backgroundMemberUserId","backgroundWork");
            assertThat(extract(structured,normalized)).isEmpty();
            assertThat(render(structured,normalized,context)).containsEntry("backgroundInvalid",false);
        }
    }

    /** 同页两种组件共享一批授权结果，渲染阶段无逐组件数据库读取。 */
    @Test void batchContextReusesValidatedResources() {
        Map<Long,WorkEntity> loaded = support.load(List.of(input(),input()),context);
        clearInvocations(members,users,works);
        TeamPortfolioComponentContext batch = new TeamPortfolioComponentContext(1,2,3,loaded);
        assertThat(render(false,input(),batch).get("backgroundWork")).isNotNull();
        assertThat(render(true,input(),batch).get("backgroundWork")).isNotNull();
        verifyNoInteractions(members,users,works);
        member.setAllowWorks(0);
        assertThatThrownBy(() -> support.validate(input(),batch)).isInstanceOf(BusinessException.class);
    }

    /** 调用实际两类配置策略。 */
    private JSONObject normalize(boolean structured,JSONObject config) {
        return structured ? new TeamStructuredTextSectionComponentValidator(support).normalizeAndValidate(config,context)
                : new TeamTextSectionComponentValidator(support).normalizeAndValidate(config,context);
    }
    /** 调用实际两类展示策略。 */
    private JSONObject render(boolean structured,JSONObject config,TeamPortfolioComponentContext renderContext) {
        return structured ? new TeamStructuredTextSectionComponentRenderer(support).render(config,renderContext)
                : new TeamTextSectionComponentRenderer(support).render(config,renderContext);
    }
    /** 调用实际两类引用策略。 */
    private List<PortfolioReferenceEntity> extract(boolean structured,JSONObject config) {
        return structured ? new TeamStructuredTextSectionComponentReferenceExtractor(support)
                .extract("c_text","bottomNav.items[1].components[0]",config,context)
                : new TeamTextSectionComponentReferenceExtractor(support)
                .extract("c_text","bottomNav.items[1].components[0]",config,context);
    }
    /** 带注入展示字段的手工配置。 */
    private JSONObject input() {
        return JSON.parseObject("""
                {"content":"团队介绍","backgroundEnabled":true,"backgroundWorkId":11,"backgroundMemberUserId":7,
                 "backgroundTreatment":"GRADIENT","verticalAlignment":"BOTTOM","backgroundWork":{"url":"injected"},
                 "blocks":[{"blockKey":"title","type":"TITLE","content":"团队标题"}]}
                """);
    }
}
