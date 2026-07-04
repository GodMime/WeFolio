package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 作品集渲染服务测试 — 覆盖配置展开、作品字段输出和维护中状态。
 */
@ExtendWith(MockitoExtension.class)
class PortfolioRenderServiceTest {

    /** 作品 Mapper 模拟 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** COS 服务模拟 */
    @Mock
    private CosService cosService;

    /**
     * 渲染时应展开作品数据，并按配置排序输出组件。
     */
    @Test
    void renderShouldExpandWorksAndKeepConfiguredComponentOrder() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, MediaTypeDict.IMAGE.getCode(), "gallery/11.jpg", "cover/11.jpg", null),
                work(12L, MediaTypeDict.IMAGE.getCode(), "gallery/12.jpg", "cover/12.jpg", null)
        ));
        when(cosService.publicUrl("gallery/11.jpg")).thenReturn("https://cdn.example.com/gallery/11.jpg");
        when(cosService.publicUrl("cover/11.jpg")).thenReturn("https://cdn.example.com/cover/11.jpg");
        when(cosService.publicUrl("gallery/12.jpg")).thenReturn("https://cdn.example.com/gallery/12.jpg");
        when(cosService.publicUrl("cover/12.jpg")).thenReturn("https://cdn.example.com/cover/12.jpg");
        PortfolioConfigDto config = config(
                component("c_carousel", PortfolioComponentTypeDict.CAROUSEL.getCode(), 3000, Map.of(
                        "workIds", List.of(12L)
                )),
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of(
                        "profile", Map.of("displayName", "林安", "bio", "温暖沉稳"),
                        "visibleFields", Map.of("displayName", true, "bio", true)
                )),
                component("c_grid", PortfolioComponentTypeDict.WORK_GRID.getCode(), 2000, Map.of(
                        "title", "作品",
                        "groups", List.of(group("g_all", "全部案例", 1000, List.of(11L)))
                ))
        );

        PortfolioRenderDto render = service().render(portfolio(), config, true, false, null, 33L);

        assertThat(render.isPreview()).isTrue();
        assertThat(render.getVisitRecordId()).isEqualTo(33L);
        assertThat(render.getTitle()).isEqualTo("林安婚礼司仪");
        assertThat(render.getComponents()).extracting(PortfolioRenderDto.Component::getComponentKey)
                .containsExactly("c_profile", "c_grid", "c_carousel");
        assertThat(JSON.toJSONString(render.getShare())).doesNotContain("\"intro\"");
        PortfolioRenderDto.Component grid = render.getComponents().get(1);
        assertThat(grid.getGroups()).hasSize(1);
        assertThat(grid.getGroups().get(0).getName()).isEqualTo("全部案例");
        assertThat(grid.getGroups().get(0).getWorks().get(0).getCoverUrl())
                .isEqualTo("https://cdn.example.com/cover/11.jpg");
        PortfolioRenderDto.Component carousel = render.getComponents().get(2);
        assertThat(carousel.getWorks().get(0).getWorkId()).isEqualTo(12L);
    }

    /**
     * 维护中状态应清空组件并保留维护提示文案。
     */
    @Test
    void renderShouldClearComponentsWhenUnderMaintenance() {
        VisitorPortfolioResponse.MaintenanceText text = new VisitorPortfolioResponse.MaintenanceText();
        text.setPrimary("UNDER MAINTENANCE");
        text.setSecondary("维护中");

        PortfolioRenderDto render = service().render(portfolio(), config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of())
        ), false, true, text, null);

        assertThat(render.isUnderMaintenance()).isTrue();
        assertThat(render.getMaintenanceText().getSecondary()).isEqualTo("维护中");
        assertThat(render.getComponents()).isEmpty();
    }

    /**
     * 二维码联系组件使用资料来源时应复制个人资料二维码地址。
     */
    @Test
    void renderShouldResolveQrContactFromProfileCopy() {
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of(
                        "profile", Map.of("displayName", "林安", "wechatQrUrl", "https://cdn.example.com/profile-qr.jpg")
                )),
                component("c_qr", PortfolioComponentTypeDict.QR_CONTACT.getCode(), 2000, Map.of(
                        "qrUrlSource", "PROFILE",
                        "title", "微信联系"
                ))
        );

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getComponents().get(1).getQrContact().getQrUrl())
                .isEqualTo("https://cdn.example.com/profile-qr.jpg");
    }

    private PortfolioRenderService service() {
        return new PortfolioRenderService(workEntityMapper, cosService);
    }

    private PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(88L);
        portfolio.setShareCode("PF001");
        portfolio.setOwnerId(7L);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        return portfolio;
    }

    private PortfolioConfigDto config(PortfolioConfigDto.Component... components) {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        PortfolioConfigDto.Share share = new PortfolioConfigDto.Share();
        share.setTitle("林安婚礼司仪");
        share.setCoverUrl("https://cdn.example.com/share.jpg");
        config.setShare(share);
        config.setComponents(List.of(components));
        return config;
    }

    private PortfolioConfigDto.Component component(String key, String type, int sortOrder, Map<String, Object> config) {
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setSortOrder(sortOrder);
        component.setEnabled(true);
        component.setConfig(new LinkedHashMap<>(config));
        return component;
    }

    private Map<String, Object> group(String key, String name, int sortOrder, List<Long> workIds) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("groupKey", key);
        group.put("name", name);
        group.put("sortOrder", sortOrder);
        group.put("workIds", workIds);
        return group;
    }

    private WorkEntity work(Long id, String mediaType, String mediaObjectKey, String coverObjectKey, Integer durationMs) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(7L);
        work.setMediaType(mediaType);
        work.setTitle("作品" + id);
        work.setMediaObjectKey(mediaObjectKey);
        work.setCoverObjectKey(coverObjectKey);
        work.setDurationMs(durationMs);
        work.setDescription("说明" + id);
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        return work;
    }
}
