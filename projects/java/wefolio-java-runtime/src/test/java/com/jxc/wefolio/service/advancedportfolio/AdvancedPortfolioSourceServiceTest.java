package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.*;
import com.jxc.wefolio.entity.*;
import com.jxc.wefolio.mapper.*;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 团队高级来源展示当前真实标签并沿普通关键字搜索语义过滤。 */
class AdvancedPortfolioSourceServiceTest {
    /** 公开资源地址。 */
    private final CosService cos = mock(CosService.class);
    /** 当前团队成员。 */
    private final TeamMemberEntityMapper members = mock(TeamMemberEntityMapper.class);
    /** 成员账号。 */
    private final UserEntityMapper users = mock(UserEntityMapper.class);
    /** 可见成员作品。 */
    private final WorkEntityMapper works = mock(WorkEntityMapper.class);
    /** 已发布个人作品集。 */
    private final PortfolioEntityMapper portfolios = mock(PortfolioEntityMapper.class);
    /** 用户自定义标签。 */
    private final WfTagEntityMapper tags = mock(WfTagEntityMapper.class);
    /** 当前作品标签关联。 */
    private final WorkTagEntityMapper links = mock(WorkTagEntityMapper.class);
    /** 当前混合来源查询。 */
    private final AdvancedPortfolioSourceService service = new AdvancedPortfolioSourceService(cos, members, users, works, portfolios, tags, links);

    /** 标签名称与原文件名均可命中，不会因为 SQL 只查标题而丢失作品。 */
    @Test void matchesRealUserTagsAndOriginalFilename() {
        arrangeWorks();
        JSONObject byTag = service.listSources(31L, "WORK", null, "婚礼", 1, 20);
        assertThat(byTag.getJSONArray("items")).hasSize(1);
        JSONObject item = byTag.getJSONArray("items").getJSONObject(0);
        assertThat(item.getString("sourceId")).isEqualTo("10");
        assertThat(item.getJSONArray("tags").getJSONObject(0).getString("name")).isEqualTo("婚礼主持");
        assertThat(item).containsEntry("available", true).containsEntry("thumbnailUrl", "https://assets.example/work.jpg");
        assertThat(service.listSources(31L, "WORK", null, "原文件", 1, 20).getJSONArray("items")).hasSize(1);
        assertThat(service.listSources(31L, "WORK", null, "无匹配", 1, 20).getJSONArray("items")).isEmpty();
    }

    /** 关联或标签作者与作品作者不一致时不输出跨用户标签。 */
    @Test void ignoresWrongUserAndInactiveTagLinks() {
        arrangeWorks(); WfTagEntity tag = tag(); tag.setUserId(99L); when(tags.selectList(any())).thenReturn(List.of(tag));
        JSONObject item = service.listSources(31L, "WORK", null, null, 1, 20).getJSONArray("items").getJSONObject(0);
        assertThat(item.getJSONArray("tags")).isEmpty();
        tag.setUserId(7L); tag.setStatus(WfTagStatusDict.DISABLED.getCode());
        assertThat(service.listSources(31L, "WORK", null, null, 1, 20).getJSONArray("tags")).isEmpty();
    }

    /** 只授权作品集的成员不会借混合列表暴露个人作品。 */
    @Test void keepsWorkAndPortfolioAuthorizationIndependent() {
        arrangeWorks(); TeamMemberEntity member = member(); member.setAllowWorks(0); member.setAllowPortfolio(1);
        when(members.selectList(any())).thenReturn(List.of(member)); when(portfolios.selectList(any())).thenReturn(List.of());
        assertThat(service.listSources(31L, null, null, null, 1, 20).getJSONArray("items")).isEmpty();
    }

    /** 安排可用作品及其真实用户自定义标签。 */
    private void arrangeWorks() {
        when(members.selectList(any())).thenReturn(List.of(member()));
        UserEntity user = new UserEntity(); user.setId(7L); user.setStatus(UserStatusDict.ACTIVE.getCode()); user.setNickname("主持人");
        when(users.selectById(7L)).thenReturn(user);
        WorkEntity work = new WorkEntity(); work.setId(10L); work.setUserId(7L); work.setDeleted(0L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        work.setTitle("作品甲"); work.setOriginalFileName("原文件.jpg"); work.setMediaType(MediaTypeDict.IMAGE.getCode()); work.setMediaObjectKey("work.jpg");
        when(works.selectList(any())).thenReturn(List.of(work)); when(cos.publicUrl(anyString())).thenAnswer(call -> "https://assets.example/" + call.getArgument(0));
        WorkTagEntity link = new WorkTagEntity(); link.setWorkId(10L); link.setUserId(7L); link.setTagId(51L); link.setDeleted(0L);
        when(links.selectList(any())).thenReturn(List.of(link)); when(tags.selectList(any())).thenReturn(List.of(tag()));
    }
    /** 当前允许作品引用的团队成员。 */
    private TeamMemberEntity member() {
        TeamMemberEntity member = new TeamMemberEntity(); member.setTeamId(31L); member.setUserId(7L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(1); member.setAllowPortfolio(0); return member;
    }
    /** 当前作品作者的真实标签。 */
    private WfTagEntity tag() {
        WfTagEntity tag = new WfTagEntity(); tag.setId(51L); tag.setUserId(7L); tag.setDeleted(0L);
        tag.setStatus(WfTagStatusDict.ACTIVE.getCode()); tag.setName("婚礼主持"); tag.setColor("#ABCDEF"); return tag;
    }
}
