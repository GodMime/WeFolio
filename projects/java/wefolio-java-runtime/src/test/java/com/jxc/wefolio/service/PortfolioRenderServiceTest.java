package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
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
                        "showTitle", false,
                        "showDescription", true,
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
        assertThat(grid.getShowTitle()).isFalse();
        assertThat(grid.getShowDescription()).isTrue();
        assertThat(grid.getGroups()).hasSize(1);
        assertThat(grid.getGroups().get(0).getName()).isEqualTo("全部案例");
        assertThat(grid.getGroups().get(0).getWorks().get(0).getCoverUrl())
                .isEqualTo("https://cdn.example.com/cover/11.jpg");
        PortfolioRenderDto.Component carousel = render.getComponents().get(2);
        assertThat(carousel.getWorks().get(0).getWorkId()).isEqualTo(12L);
    }

    /**
     * 单个作品组件应输出单数作品、标题说明开关和作品原始比例。
     */
    @Test
    void renderShouldExposeSingleWorkWithTitleSwitchAndAspectRatio() {
        WorkEntity video = work(12L, MediaTypeDict.VIDEO.getCode(), "video/12.mp4", "cover/12.jpg", 6800);
        video.setAspectRatio("9:16");
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(video));
        when(cosService.publicUrl("video/12.mp4")).thenReturn("https://cdn.example.com/video/12.mp4");
        when(cosService.publicUrl("cover/12.jpg")).thenReturn("https://cdn.example.com/cover/12.jpg");
        PortfolioConfigDto config = config(component(
                "c_single",
                "SINGLE_WORK",
                1000,
                Map.of("workId", 12L, "showTitle", false, "showDescription", true)
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        PortfolioRenderDto.Component component = render.getComponents().get(0);
        assertThat(component.getShowTitle()).isFalse();
        assertThat(component.getShowDescription()).isTrue();
        assertThat(component.getWork()).isNotNull();
        assertThat(component.getWork().getWorkId()).isEqualTo(12L);
        assertThat(component.getWork().getMediaUrl()).isEqualTo("https://cdn.example.com/video/12.mp4");
        assertThat(component.getWork().getCoverUrl()).isEqualTo("https://cdn.example.com/cover/12.jpg");
        assertThat(component.getWork().getAspectRatio()).isEqualTo("9:16");
        assertThat(component.getWorks()).isEmpty();
    }

    @Test
    void renderShouldExposeAnimationOnlyForSingleWorkAndFilterBadBulkConfig() {
        WorkEntity animation = work(
                13L,
                MediaTypeDict.ANIMATION.getCode(),
                "animation/13.gif",
                "animation/13-thumb.jpg",
                null);
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(animation));
        when(cosService.publicUrl("animation/13.gif")).thenReturn("https://cdn.example.com/animation/13.gif");
        when(cosService.publicUrl("animation/13-thumb.jpg"))
                .thenReturn("https://cdn.example.com/animation/13-thumb.jpg");
        PortfolioConfigDto config = config(
                component("c_single", PortfolioComponentTypeDict.SINGLE_WORK.getCode(), 1000,
                        Map.of("workId", 13L)),
                component("c_grid", PortfolioComponentTypeDict.WORK_GRID.getCode(), 2000,
                        Map.of("workIds", List.of(13L))));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getComponents().get(0).getWork().getMediaType())
                .isEqualTo(MediaTypeDict.ANIMATION.getCode());
        assertThat(render.getComponents().get(1).getGroups())
                .singleElement()
                .satisfies(group -> assertThat(group.getWorks()).isEmpty());
    }

    @Test
    void renderShouldHideAnimationBeforeAuditPasses() {
        WorkEntity animation = work(
                13L,
                MediaTypeDict.ANIMATION.getCode(),
                "animation/13.gif",
                "animation/13-thumb.jpg",
                null);
        animation.setAuditStatus(WorkAuditStatusDict.AUDITING.getCode());
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(animation));
        PortfolioConfigDto config = config(component(
                "c_single",
                PortfolioComponentTypeDict.SINGLE_WORK.getCode(),
                1000,
                Map.of("workId", 13L)));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getComponents().get(0).getWork()).isNull();
    }

    /**
     * 历史异常配置找不到作品时应保留组件信封并返回空单作品。
     */
    @Test
    void renderShouldKeepSingleWorkEnvelopeWhenWorkIsMissing() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        PortfolioConfigDto config = config(component(
                "c_single",
                "SINGLE_WORK",
                1000,
                Map.of("workId", 99L)
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, true, false, null, null);

        assertThat(render.getComponents()).singleElement().satisfies(component -> {
            assertThat(component.getComponentKey()).isEqualTo("c_single");
            assertThat(component.getShowTitle()).isTrue();
            assertThat(component.getShowDescription()).isFalse();
            assertThat(component.getWork()).isNull();
        });
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
        assertThat(render.getStyle().getBackgroundColor()).isEqualTo("#FFFFFF");
        assertThat(render.getStyle().getThemeMode()).isEqualTo("light");
        assertThat(render.getBottomNav().isEnabled()).isFalse();
        assertThat(render.getBottomNav().getItems()).isEmpty();
    }

    /**
     * 渲染时应输出背景主题和二级菜单组件，首页菜单不重复输出组件。
     */
    @Test
    void renderShouldExposeStyleAndSecondaryMenuComponents() {
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of())
        );
        PortfolioConfigDto.Style style = new PortfolioConfigDto.Style();
        style.setBackgroundColor("#102030");
        config.setStyle(style);
        PortfolioConfigDto.BottomNav bottomNav = new PortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        PortfolioConfigDto.BottomNavItem home = new PortfolioConfigDto.BottomNavItem();
        home.setKey("home");
        home.setTitle("主页");
        PortfolioConfigDto.BottomNavItem contact = new PortfolioConfigDto.BottomNavItem();
        contact.setKey("contact");
        contact.setTitle("联系");
        contact.setComponents(List.of(component(
                "c_contact",
                PortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                1000,
                Map.of("title", "留下联系方式")
        )));
        bottomNav.setItems(List.of(home, contact));
        config.setBottomNav(bottomNav);

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getStyle().getBackgroundColor()).isEqualTo("#102030");
        assertThat(render.getStyle().getThemeMode()).isEqualTo("dark");
        assertThat(render.getComponents()).extracting(PortfolioRenderDto.Component::getComponentKey)
                .containsExactly("c_profile");
        assertThat(render.getBottomNav().isEnabled()).isTrue();
        assertThat(render.getBottomNav().getItems()).hasSize(2);
        assertThat(render.getBottomNav().getItems().get(0).getComponents()).isNull();
        assertThat(render.getBottomNav().getItems().get(1).getComponents())
                .extracting(PortfolioRenderDto.Component::getComponentKey)
                .containsExactly("c_contact");
    }

    /**
     * 第一菜单复用顶层组件，渲染 JSON 中不得重复输出 components 字段。
     */
    @Test
    void renderJsonShouldOmitComponentsFromFirstNavigationItem() throws Exception {
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of())
        );
        PortfolioConfigDto.BottomNav bottomNav = new PortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        PortfolioConfigDto.BottomNavItem home = new PortfolioConfigDto.BottomNavItem();
        home.setKey("home");
        home.setTitle("主页");
        PortfolioConfigDto.BottomNavItem contact = new PortfolioConfigDto.BottomNavItem();
        contact.setKey("contact");
        contact.setTitle("联系");
        contact.setComponents(List.of(component(
                "c_contact",
                PortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                1000,
                Map.of()
        )));
        bottomNav.setItems(List.of(home, contact));
        config.setBottomNav(bottomNav);

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode items = objectMapper.readTree(objectMapper.writeValueAsString(render))
                .path("bottomNav")
                .path("items");

        assertThat(items.get(0).has("components")).isFalse();
        assertThat(items.get(1).path("components").isArray()).isTrue();
    }

    /**
     * YIQ 临界值上方的浅色背景应使用深色文字主题。
     */
    @Test
    void renderShouldDeriveLightThemeModeFromBackgroundColor() {
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of())
        );
        PortfolioConfigDto.Style style = new PortfolioConfigDto.Style();
        style.setBackgroundColor("#808080");
        config.setStyle(style);

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getStyle().getThemeMode()).isEqualTo("light");
    }

    /**
     * 二维码联系组件使用资料来源时应直接读取配置中的二维码地址。
     */
    @Test
    void renderShouldReadProfileQrContactUrlFromConfigWithoutUserQuery() {
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of(
                        "profile", Map.of("displayName", "林安", "wechatQrUrl", "https://cdn.example.com/stale-component-qr.jpg")
                )),
                component("c_qr", PortfolioComponentTypeDict.QR_CONTACT.getCode(), 2000, Map.of(
                        "qrUrlSource", "PROFILE",
                        "qrUrl", "https://cdn.example.com/saved-profile-qr.jpg",
                        "title", "微信联系"
                ))
        );

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getComponents().get(1).getQrContact().getQrUrl())
                .isEqualTo("https://cdn.example.com/saved-profile-qr.jpg");
    }

    /**
     * 档期查询组件应透出展示模式，供访客页决定弹层或内联月历。
     */
    @Test
    void renderShouldExposeScheduleQueryDisplayMode() {
        PortfolioConfigDto config = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                Map.of(
                        "title", "档期查询",
                        "description", "请选择日期和档位",
                        "displayMode", "INLINE_CALENDAR",
                        "queryRange", Map.of("type", "UNLIMITED")
                )
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        PortfolioRenderDto.ScheduleQuery scheduleQuery = render.getComponents().get(0).getScheduleQuery();
        assertThat(scheduleQuery.getTitle()).isEqualTo("档期查询");
        assertThat(scheduleQuery.getDescription()).isEqualTo("请选择日期和档位");
        assertThat(scheduleQuery.getDisplayMode()).isEqualTo("INLINE_CALENDAR");
        assertThat(scheduleQuery.getQueryRange()).containsEntry("type", "UNLIMITED");
    }

    /**
     * 联系信息组件应透出展示模式，供访客页决定按钮弹层或直接表单。
     */
    @Test
    void renderShouldExposeContactFormDisplayMode() {
        PortfolioConfigDto config = config(component(
                "c_contact",
                PortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                1000,
                Map.of(
                        "title", "留下联系方式",
                        "description", "稍后联系你",
                        "displayMode", "INLINE_FORM",
                        "fields", List.of("contactName", "phone")
                )
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        PortfolioRenderDto.ContactForm contactForm = render.getComponents().get(0).getContactForm();
        assertThat(contactForm.getTitle()).isEqualTo("留下联系方式");
        assertThat(contactForm.getDescription()).isEqualTo("稍后联系你");
        assertThat(contactForm.getDisplayMode()).isEqualTo("INLINE_FORM");
        assertThat(contactForm.getFields()).containsExactly("contactName", "phone");
    }

    /**
     * 文字说明组件应透出正文和对齐方式，供预览页和访客页一致展示。
     */
    @Test
    void renderShouldExposeTextSectionContentAndAlignment() {
        PortfolioConfigDto config = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                Map.of(
                        "content", "第一行\n第二行",
                        "alignment", "RIGHT"
                )
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        PortfolioRenderDto.TextSection textSection = render.getComponents().get(0).getTextSection();
        assertThat(textSection.getContent()).isEqualTo("第一行\n第二行");
        assertThat(textSection.getAlignment()).isEqualTo("RIGHT");
    }

    /**
     * 文字说明组件应透出受支持的字体和精确整数字号。
     */
    @Test
    void renderShouldExposeTextSectionTypography() {
        PortfolioConfigDto config = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                Map.of(
                        "content", "第一行\n第二行",
                        "alignment", "RIGHT",
                        "fontFamily", "WECHAT_SANS_SS",
                        "fontSizeRpx", 36
                )
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        PortfolioRenderDto.TextSection textSection = render.getComponents().get(0).getTextSection();
        assertThat(textSection.getFontFamily()).isEqualTo("WECHAT_SANS_SS");
        assertThat(textSection.getFontSizeRpx()).isEqualTo(36);
    }

    /**
     * 历史或异常文字说明配置应回退到安全的个人排版默认值。
     */
    @Test
    void renderShouldFallbackInvalidTextSectionTypography() {
        Map<String, Object> invalidTypography = new LinkedHashMap<>();
        invalidTypography.put("content", "异常存量");
        invalidTypography.put("fontFamily", "UNKNOWN");
        invalidTypography.put("fontSizeRpx", 28.5D);
        PortfolioConfigDto config = config(
                component(
                        "c_legacy",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        Map.of("content", "旧配置")
                ),
                component(
                        "c_invalid",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        2000,
                        invalidTypography
                )
        );

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        assertThat(render.getComponents())
                .extracting(component -> component.getTextSection().getFontFamily())
                .containsExactly("SYSTEM", "SYSTEM");
        assertThat(render.getComponents())
                .extracting(component -> component.getTextSection().getFontSizeRpx())
                .containsExactly(26, 26);
    }

    /**
     * 分割线组件应透出颜色和高度，供预览页和访客页一致展示。
     */
    @Test
    void renderShouldExposeDividerColorAndHeight() {
        PortfolioConfigDto config = config(component(
                "c_divider",
                PortfolioComponentTypeDict.DIVIDER.getCode(),
                1000,
                Map.of(
                        "color", "WHITE",
                        "heightPx", 28
                )
        ));

        PortfolioRenderDto render = service().render(portfolio(), config, false, false, null, null);

        PortfolioRenderDto.Divider divider = render.getComponents().get(0).getDivider();
        assertThat(divider.getColor()).isEqualTo("WHITE");
        assertThat(divider.getHeightPx()).isEqualTo(28);
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
        if (MediaTypeDict.ANIMATION.getCode().equals(mediaType)) {
            work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        }
        return work;
    }
}
