package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 组件字段独立版本演进、旧请求防覆盖及两端非法展示配置契约。 */
class PortfolioComponentDisplayOptionsSupportTest {
    /** 未来不同版本字段与当前字段同时存在时，只保护客户端尚不认识的字段。 */
    @Test
    void protectsEachFieldUsingItsOwnPersonalAndTeamRevision() {
        var rules = List.of(
                new PortfolioComponentDisplayOptionsSupport.FieldRule("SINGLE_WORK", "openMode", 6, 5),
                new PortfolioComponentDisplayOptionsSupport.FieldRule("SINGLE_WORK", "futureOption", 8, 7));
        var existing = Map.<String, Object>of("openMode", "DETAIL_PAGE", "futureOption", Map.of("enabled", true));
        for (var editor : PortfolioComponentDisplayOptionsSupport.EditorType.values()) {
            int current = editor == PortfolioComponentDisplayOptionsSupport.EditorType.PERSONAL ? 6 : 5;
            Map<String, Object> target = new LinkedHashMap<>(Map.of("openMode", "INLINE", "futureOption", false));
            PortfolioComponentDisplayOptionsSupport.protect(target, existing, "SINGLE_WORK", editor, current, rules);
            assertThat(target).containsEntry("openMode", "INLINE").containsEntry("futureOption", Map.of("enabled", true));
            ((Map<String, Object>) target.get("futureOption")).put("enabled", false);
            assertThat(existing.get("futureOption")).isEqualTo(Map.of("enabled", true));
            target.put("futureOption", false);
            PortfolioComponentDisplayOptionsSupport.protect(target, existing, "SINGLE_WORK", editor, current + 2, rules);
            assertThat(target).containsEntry("openMode", "INLINE").containsEntry("futureOption", false);
            target.put("futureOption", true);
            PortfolioComponentDisplayOptionsSupport.protect(target, Map.of(), "SINGLE_WORK", editor, current, rules);
            assertThat(target).containsEntry("openMode", "INLINE").doesNotContainKey("futureOption");
        }
    }

    /** 显式非法样式、打开方式和详情选项必须在两端保存入口被拒绝。 */
    @Test
    void bothValidatorsRejectInvalidDisplayOptions() {
        WorkEntityMapper works = mock(WorkEntityMapper.class);
        List<WorkEntity> videos = List.of(video(11L), video(12L), video(13L));
        when(works.selectBatchIds(anyCollection())).thenReturn(videos);
        PortfolioConfigValidator personal = new PortfolioConfigValidator(works);
        var members = mock(TeamMemberEntityMapper.class);
        var users = mock(UserEntityMapper.class);
        var teamSingle = new TeamSingleWorkComponentValidator(members, users, works);
        var teamVideo = new TeamVideoCarouselComponentValidator(members, users, works);
        var context = new TeamPortfolioComponentContext(1L, 2L, 1);
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("displayStyle", "UNKNOWN"), Map.<String, Object>of("displayStyle", 1),
                Map.<String, Object>of("showDescription", "false"),
                Map.<String, Object>of(PortfolioComponentDisplayOptionsSupport.SHOW_COMPONENT_TITLE, "false"),
                Map.<String, Object>of(PortfolioComponentDisplayOptionsSupport.SHOW_COMPONENT_TITLE, 0))) {
            assertThatThrownBy(() -> personal.normalize(7L, personalConfig("VIDEO_CAROUSEL", invalid)))
                    .isInstanceOf(BusinessException.class).hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
            JSONObject raw = new JSONObject(invalid);
            raw.put("items", JSONArray.of(JSONObject.of("memberUserId", 7L, "workId", 11L),
                    JSONObject.of("memberUserId", 7L, "workId", 12L), JSONObject.of("memberUserId", 7L, "workId", 13L)));
            assertThatThrownBy(() -> teamVideo.normalizeAndValidate(raw, context))
                    .isInstanceOf(BusinessException.class).hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
        }
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("openMode", "UNKNOWN"), Map.<String, Object>of("openMode", 1),
                Map.<String, Object>of("detailOptions", "bad"),
                Map.<String, Object>of("detailOptions", Map.of("showTitle", "false")),
                Map.<String, Object>of("detailOptions", Map.of("showDescription", 0)))) {
            assertThatThrownBy(() -> personal.normalize(7L, personalConfig("SINGLE_WORK", invalid)))
                    .isInstanceOf(BusinessException.class).hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
            JSONObject raw = new JSONObject(invalid); raw.put("memberUserId", 7L); raw.put("workId", 11L);
            assertThatThrownBy(() -> teamSingle.normalizeAndValidate(raw, context))
                    .isInstanceOf(BusinessException.class).hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
        }
    }

    /** 创建真实个人校验入口所需配置。 */
    private PortfolioConfigDto personalConfig(String type, Map<String, Object> options) {
        var config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        config.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        var component = new PortfolioConfigDto.Component();
        component.setComponentKey("work"); component.setComponentType(type); component.setEnabled(true);
        Map<String, Object> values = new LinkedHashMap<>(options);
        if (PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode().equals(type)) { values.put("workIds", List.of(11L, 12L, 13L)); }
        else { values.put("workId", 11L); }
        component.setConfig(values); config.setComponents(List.of(component));
        return config;
    }

    /** 测试作品为同一维护者当前可用且审核通过的视频。 */
    private WorkEntity video(long id) {
        WorkEntity work = new WorkEntity(); work.setId(id); work.setUserId(7L);
        work.setMediaType("VIDEO"); work.setStatus("ACTIVE"); work.setAuditStatus("PASSED");
        return work;
    }
}
