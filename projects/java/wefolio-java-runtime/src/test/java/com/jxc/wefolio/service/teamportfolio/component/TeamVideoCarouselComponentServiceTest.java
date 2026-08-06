package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.teamportfolio.TeamVideoCarouselWorkPageResponse;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 团队视频轮播候选来源测试。 */
@ExtendWith(MockitoExtension.class)
class TeamVideoCarouselComponentServiceTest {

    private static final long PORTFOLIO_ID = 13L;
    private static final long TEAM_ID = 17L;
    private static final long MEMBER_USER_ID = 19L;
    private static final long USER_ID = 23L;

    @Mock private TeamPortfolioAccessService accessService;
    @Mock private TeamMemberEntityMapper memberMapper;
    @Mock private UserEntityMapper userMapper;
    @Mock private WorkEntityMapper workMapper;
    @Mock private WorkTagEntityMapper workTagMapper;
    @Mock private WfTagEntityMapper tagMapper;
    @Mock private CosService cosService;

    /** 初始化 Lambda 查询使用的实体字段缓存。 */
    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkEntity.class);
    }

    /**
     * 来源必须校验维护权限和成员授权，固定筛选有效审核通过视频，并完整映射分页、标签与媒体字段。
     */
    @Test
    void pageWorksShouldAuthorizeFilterSearchPageAndMapExactContract() {
        stubAvailableMember("一辰");
        WorkEntity first = work(101L, MEMBER_USER_ID, WorkStatusDict.ACTIVE.getCode(),
                WorkAuditStatusDict.PASSED.getCode(), MediaTypeDict.VIDEO.getCode());
        first.setTitle("三亚目的地婚礼");
        first.setMediaObjectKey("video/101.mp4");
        first.setCoverObjectKey("cover/101.jpg");
        first.setDurationMs(63_000);
        first.setWidth(1920);
        first.setHeight(1080);
        first.setAspectRatio("16:9");
        WorkEntity second = work(102L, MEMBER_USER_ID, WorkStatusDict.ACTIVE.getCode(),
                WorkAuditStatusDict.PASSED.getCode(), MediaTypeDict.VIDEO.getCode());
        WorkEntity wrongOwner = work(103L, 999L, WorkStatusDict.ACTIVE.getCode(),
                WorkAuditStatusDict.PASSED.getCode(), MediaTypeDict.VIDEO.getCode());
        WorkEntity wrongStatus = work(104L, MEMBER_USER_ID, WorkStatusDict.PROCESSING.getCode(),
                WorkAuditStatusDict.PASSED.getCode(), MediaTypeDict.VIDEO.getCode());
        WorkEntity wrongMedia = work(105L, MEMBER_USER_ID, WorkStatusDict.ACTIVE.getCode(),
                WorkAuditStatusDict.PASSED.getCode(), MediaTypeDict.IMAGE.getCode());
        when(workMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<WorkEntity> requested = invocation.getArgument(0);
            requested.setRecords(List.of(first, wrongOwner, wrongStatus, wrongMedia, second));
            requested.setTotal(21L);
            return requested;
        });
        WorkTagEntity activeRelation = relation(101L, 7L, 1000);
        WorkTagEntity disabledRelation = relation(101L, 8L, 2000);
        when(workTagMapper.selectList(any())).thenReturn(List.of(activeRelation, disabledRelation));
        WfTagEntity activeTag = tag(7L, MEMBER_USER_ID, "婚礼现场", "#0f766e", WfTagStatusDict.ACTIVE.getCode());
        WfTagEntity disabledTag = tag(8L, MEMBER_USER_ID, "停用标签", null, WfTagStatusDict.DISABLED.getCode());
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(activeTag, disabledTag));
        when(cosService.publicUrl("video/101.mp4")).thenReturn("https://cdn/video.mp4");
        when(cosService.publicUrl("cover/101.jpg")).thenReturn("https://cdn/cover.jpg");

        TeamVideoCarouselWorkPageResponse response = service().pageWorks(
                PORTFOLIO_ID, MEMBER_USER_ID, USER_ID, "  三亚  ", 1, 20);

        verify(accessService).requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID);
        ArgumentCaptor<LambdaQueryWrapper<WorkEntity>> workQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workMapper).selectPage(any(Page.class), workQuery.capture());
        assertThat(workQuery.getValue().getSqlSegment())
                .contains("user_id", "status", "audit_status", "media_type", "title", "LIKE")
                .contains("ORDER BY sort_order ASC,id ASC");
        assertThat(workQuery.getValue().getParamNameValuePairs().values()).contains(
                MEMBER_USER_ID,
                WorkStatusDict.ACTIVE.getCode(),
                WorkAuditStatusDict.PASSED.getCode(),
                MediaTypeDict.VIDEO.getCode(),
                "%三亚%");
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getTotal()).isEqualTo(21L);
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getWorks()).hasSize(2);
        assertThat(response.getWorks().getFirst()).satisfies(item -> {
            assertThat(item.getMemberUserId()).isEqualTo(MEMBER_USER_ID);
            assertThat(item.getMemberDisplayName()).isEqualTo("一辰");
            assertThat(item.getWorkId()).isEqualTo(101L);
            assertThat(item.getTitle()).isEqualTo("三亚目的地婚礼");
            assertThat(item.getMediaType()).isEqualTo(MediaTypeDict.VIDEO.getCode());
            assertThat(item.getCoverUrl()).isEqualTo("https://cdn/cover.jpg");
            assertThat(item.getMediaUrl()).isEqualTo("https://cdn/video.mp4");
            assertThat(item.getDurationMs()).isEqualTo(63_000);
            assertThat(item.getWidth()).isEqualTo(1920);
            assertThat(item.getHeight()).isEqualTo(1080);
            assertThat(item.getAspectRatio()).isEqualTo("16:9");
            assertThat(item.getTags()).singleElement().satisfies(tag -> {
                assertThat(tag.getTagId()).isEqualTo(7L);
                assertThat(tag.getName()).isEqualTo("婚礼现场");
                assertThat(tag.getColor()).isEqualTo("#0f766e");
            });
        });
        assertThat(response.getWorks().get(1)).satisfies(item -> {
            assertThat(item.getWorkId()).isEqualTo(102L);
            assertThat(item.getTitle()).isEmpty();
            assertThat(item.getCoverUrl()).isEmpty();
            assertThat(item.getMediaUrl()).isEmpty();
            assertThat(item.getAspectRatio()).isEmpty();
            assertThat(item.getDurationMs()).isNull();
            assertThat(item.getTags()).isEmpty();
        });
        JSONObject json = JSON.parseObject(JSON.toJSONString(response));
        assertThat(json.keySet()).containsExactlyInAnyOrder("page", "pageSize", "total", "hasMore", "works");
        assertThat(json).doesNotContainKey("filterTags");
    }

    /** 非法分页参数采用默认值并限制最大页大小，空页不触发标签或 COS 查询。 */
    @Test
    void pageWorksShouldNormalizePaginationAndReturnStableEmptyArrays() {
        stubAvailableMember(null);
        when(workMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<WorkEntity> requested = invocation.getArgument(0);
            requested.setRecords(List.of());
            requested.setTotal(0L);
            return requested;
        });

        TeamVideoCarouselWorkPageResponse defaults = service().pageWorks(
                PORTFOLIO_ID, MEMBER_USER_ID, USER_ID, null, 0, 0);
        TeamVideoCarouselWorkPageResponse capped = service().pageWorks(
                PORTFOLIO_ID, MEMBER_USER_ID, USER_ID, " ", 2, 101);

        assertThat(defaults.getPage()).isEqualTo(1);
        assertThat(defaults.getPageSize()).isEqualTo(20);
        assertThat(defaults.getWorks()).isEmpty();
        assertThat(capped.getPage()).isEqualTo(2);
        assertThat(capped.getPageSize()).isEqualTo(100);
        assertThat(capped.getWorks()).isEmpty();
        verify(workTagMapper, never()).selectList(any());
        verify(tagMapper, never()).selectBatchIds(any());
        verify(cosService, never()).publicUrl(any());
    }

    /** 成员未加入、未授权或账号停用时必须在作品查询前拒绝。 */
    @Test
    void pageWorksShouldRejectUnavailableMemberBeforeQueryingWorks() {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        when(accessService.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID)).thenReturn(
                new TeamPortfolioAccessService.TeamPortfolioAccess(null, team, null, true, true));
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service().pageWorks(
                PORTFOLIO_ID, MEMBER_USER_ID, USER_ID, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队成员不存在或不可用");

        verify(workMapper, never()).selectPage(any(Page.class), any());
    }

    private void stubAvailableMember(String nickname) {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        when(accessService.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID)).thenReturn(
                new TeamPortfolioAccessService.TeamPortfolioAccess(null, team, null, true, true));
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(TEAM_ID);
        membership.setUserId(MEMBER_USER_ID);
        membership.setJoinStatus(JoinStatusDict.JOINED.getCode());
        membership.setAllowWorks(1);
        when(memberMapper.selectOne(any())).thenReturn(membership);
        UserEntity user = new UserEntity();
        user.setId(MEMBER_USER_ID);
        user.setNickname(nickname);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userMapper.selectById(MEMBER_USER_ID)).thenReturn(user);
    }

    private TeamVideoCarouselComponentService service() {
        return new TeamVideoCarouselComponentService(
                accessService, memberMapper, userMapper, workMapper, workTagMapper, tagMapper, cosService);
    }

    private WorkEntity work(Long id, Long ownerId, String status, String auditStatus, String mediaType) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(ownerId);
        work.setStatus(status);
        work.setAuditStatus(auditStatus);
        work.setMediaType(mediaType);
        return work;
    }

    private WorkTagEntity relation(Long workId, Long tagId, int sortOrder) {
        WorkTagEntity relation = new WorkTagEntity();
        relation.setUserId(MEMBER_USER_ID);
        relation.setWorkId(workId);
        relation.setTagId(tagId);
        relation.setSortOrder(sortOrder);
        return relation;
    }

    private WfTagEntity tag(Long id, Long ownerId, String name, String color, String status) {
        WfTagEntity tag = new WfTagEntity();
        tag.setId(id);
        tag.setUserId(ownerId);
        tag.setName(name);
        tag.setColor(color);
        tag.setStatus(status);
        return tag;
    }
}
