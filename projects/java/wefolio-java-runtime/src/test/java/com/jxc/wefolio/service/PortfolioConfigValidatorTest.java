package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 作品集配置校验器测试 — 覆盖标准个人作品集组件规则。
 */
@ExtendWith(MockitoExtension.class)
class PortfolioConfigValidatorTest {

    /** 作品 Mapper 模拟 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** 用户 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    @Test
    void normalizeShouldSortEnabledComponentsAndKeepStableKeys() {
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 2000, true, Map.of()),
                component("c_text", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 1000, true,
                        Map.of("title", "服务说明", "content", "适合婚礼、年会和发布会"))
        );

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        assertThat(normalized.getComponents()).extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("c_text", "c_profile");
        assertThat(normalized.getComponents()).extracting(PortfolioConfigDto.Component::getSortOrder)
                .containsExactly(1000, 2000);
    }

    @Test
    void normalizeShouldDropLegacyShareIntro() {
        String legacyConfigJson = """
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪","intro":"温暖沉稳"},"components":[
                  {"componentKey":"c_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,"config":{}}
                ]}
                """;
        PortfolioConfigDto config = JSON.parseObject(legacyConfigJson, PortfolioConfigDto.class);

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        assertThat(JSON.toJSONString(normalized)).doesNotContain("\"intro\"");
    }

    /**
     * 标准个人作品集不允许同时启用多个个人资料组件。
     */
    @Test
    void normalizeShouldRejectMultipleEnabledProfileComponents() {
        PortfolioConfigDto config = config(
                component("c_profile_1", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                component("c_profile_2", PortfolioComponentTypeDict.PROFILE.getCode(), 2000, true, Map.of())
        );

        assertThatThrownBy(() -> validator().normalize(7L, config))
                .isInstanceOf(BusinessException.class)
                .hasMessage("个人作品集最多只能包含一个个人资料组件");
    }

    /**
     * 禁用的个人资料组件不计入单例限制，并在归一化时被过滤。
     */
    @Test
    void normalizeShouldIgnoreDisabledProfileForSingletonLimit() {
        PortfolioConfigDto config = config(
                component("c_profile_enabled", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                component("c_profile_disabled", PortfolioComponentTypeDict.PROFILE.getCode(), 2000, false, Map.of())
        );

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        assertThat(normalized.getComponents()).extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("c_profile_enabled");
    }

    @Test
    void carouselShouldRejectVideoWorks() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 7L, MediaTypeDict.VIDEO.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));
        PortfolioConfigDto config = config(component(
                "c_carousel",
                PortfolioComponentTypeDict.CAROUSEL.getCode(),
                1000,
                true,
                Map.of("title", "代表作品", "workIds", List.of(11L, 12L), "autoplay", true)
        ));

        assertThatThrownBy(() -> validator().normalize(7L, config))
                .isInstanceOf(BusinessException.class)
                .hasMessage("轮播图只能选择图片作品");
    }

    @Test
    void workGridShouldAcceptImageAndVideoWorks() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 7L, MediaTypeDict.VIDEO.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));
        PortfolioConfigDto config = config(component(
                "c_grid",
                PortfolioComponentTypeDict.WORK_GRID.getCode(),
                1000,
                true,
                Map.of("title", "更多案例", "workIds", List.of(11L, 12L), "columns", 2,
                        "showTitle", false, "showDescription", true)
        ));

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        assertThat(normalized.getComponents().get(0).getConfig().get("workIds")).isEqualTo(List.of(11L, 12L));
        assertThat(normalized.getComponents().get(0).getConfig())
                .containsEntry("showTitle", false)
                .containsEntry("showDescription", true);
    }

    /**
     * 单个作品组件应同时支持图片和视频，并只保留规范化后的单作品配置。
     */
    @Test
    void singleWorkShouldAcceptImageAndVideoAndNormalizeSupportedConfig() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 7L, MediaTypeDict.VIDEO.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));
        PortfolioConfigDto imageConfig = config(component(
                "c_image", "SINGLE_WORK", 1000, true,
                Map.of("workId", "11", "unsupported", "discard")
        ));
        PortfolioConfigDto videoConfig = config(component(
                "c_video", "SINGLE_WORK", 1000, true,
                Map.of("workId", 12L, "showTitle", false, "showDescription", true)
        ));

        PortfolioConfigDto normalizedImage = validator().normalize(7L, imageConfig);
        PortfolioConfigDto normalizedVideo = validator().normalize(7L, videoConfig);

        assertThat(normalizedImage.getComponents().get(0).getConfig())
                .containsExactly(
                        Map.entry("workId", 11L),
                        Map.entry("showTitle", true),
                        Map.entry("showDescription", false)
                );
        assertThat(normalizedVideo.getComponents().get(0).getConfig())
                .containsExactly(
                        Map.entry("workId", 12L),
                        Map.entry("showTitle", false),
                        Map.entry("showDescription", true)
                );
    }

    /**
     * 单个作品组件必须提供可用的正整数单作品 ID，不能把复数作品 ID 当作有效配置。
     */
    @Test
    void singleWorkShouldRejectMissingInvalidOrUnavailableWork() {
        PortfolioConfigDto missingConfig = config(component(
                "c_missing", "SINGLE_WORK", 1000, true, Map.of("workIds", List.of(11L))
        ));
        PortfolioConfigDto invalidConfig = config(component(
                "c_invalid", "SINGLE_WORK", 1000, true, Map.of("workId", 0)
        ));
        PortfolioConfigDto fractionalConfig = config(component(
                "c_fractional", "SINGLE_WORK", 1000, true, Map.of("workId", 11.9D)
        ));
        PortfolioConfigDto unavailableConfig = config(component(
                "c_unavailable", "SINGLE_WORK", 1000, true, Map.of("workId", 12L)
        ));
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 8L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));

        assertThatThrownBy(() -> validator().normalize(7L, missingConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集引用了不可用作品，请刷新作品列表后重试");
        assertThatThrownBy(() -> validator().normalize(7L, invalidConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集引用了不可用作品，请刷新作品列表后重试");
        assertThatThrownBy(() -> validator().normalize(7L, fractionalConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集引用了不可用作品，请刷新作品列表后重试");
        assertThatThrownBy(() -> validator().normalize(7L, unavailableConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集引用了不可用作品，请刷新作品列表后重试");
    }

    @Test
    void workListShouldNormalizeDisplayGroupsWithWorks() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 7L, MediaTypeDict.VIDEO.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));
        PortfolioConfigDto config = config(component(
                "c_list",
                PortfolioComponentTypeDict.WORK_LIST.getCode(),
                1000,
                true,
                Map.of("title", "精选案例", "groups", List.of(
                        group("g_featured", "精选", 3000, List.of(11L, "12", 11L))
                ))
        ));

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        List<?> groups = (List<?>) normalized.getComponents().get(0).getConfig().get("groups");
        Map<?, ?> firstGroup = (Map<?, ?>) groups.get(0);
        assertThat(firstGroup.get("groupKey")).isEqualTo("g_featured");
        assertThat(firstGroup.get("name")).isEqualTo("精选");
        assertThat(firstGroup.get("sortOrder")).isEqualTo(1000);
        assertThat(firstGroup.get("workIds")).isEqualTo(List.of(11L, 12L));
    }

    @Test
    void displayGroupsShouldRejectBlankOrDuplicatedNames() {
        PortfolioConfigDto blankNameConfig = config(component(
                "c_grid",
                PortfolioComponentTypeDict.WORK_GRID.getCode(),
                1000,
                true,
                Map.of("groups", List.of(group("g_blank", " ", 1000, List.of(11L))))
        ));
        PortfolioConfigDto duplicateNameConfig = config(component(
                "c_grid",
                PortfolioComponentTypeDict.WORK_GRID.getCode(),
                1000,
                true,
                Map.of("groups", List.of(
                        group("g_a", "户外案例", 1000, List.of(11L)),
                        group("g_b", "户外案例", 2000, List.of(12L))
                ))
        ));

        assertThatThrownBy(() -> validator().normalize(7L, blankNameConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集展示标签名称不能为空");
        assertThatThrownBy(() -> validator().normalize(7L, duplicateNameConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集展示标签名称不能重复");
    }

    @Test
    void displayGroupsShouldRejectDuplicatedGroupKeys() {
        PortfolioConfigDto config = config(component(
                "c_grid",
                PortfolioComponentTypeDict.WORK_GRID.getCode(),
                1000,
                true,
                Map.of("groups", List.of(
                        group("g_same", "户外案例", 1000, List.of(11L)),
                        group("g_same", "室内案例", 2000, List.of(12L))
                ))
        ));

        assertThatThrownBy(() -> validator().normalize(7L, config))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集展示标签标识不能重复");
    }

    @Test
    void textSectionShouldAllowBlankTitleButRequireContent() {
        PortfolioConfigDto validConfig = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("title", " ", "content", "报价以沟通确认为准", "alignment", "CENTER")
        ));
        PortfolioConfigDto invalidConfig = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("title", "服务说明", "content", " ")
        ));

        PortfolioConfigDto normalized = validator().normalize(7L, validConfig);

        assertThat(normalized.getComponents()).hasSize(1);
        assertThat(normalized.getComponents().get(0).getConfig().get("alignment")).isEqualTo("CENTER");
        assertThatThrownBy(() -> validator().normalize(7L, invalidConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明内容不能为空");
    }

    /**
     * 文字说明组件应限制正文长度，并拒绝不支持的对齐方式。
     */
    @Test
    void textSectionShouldLimitContentLengthAndValidateAlignment() {
        PortfolioConfigDto tooLongConfig = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("content", "字".repeat(201), "alignment", "LEFT")
        ));
        PortfolioConfigDto invalidAlignmentConfig = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("content", "报价以沟通确认为准", "alignment", "JUSTIFY")
        ));
        PortfolioConfigDto defaultAlignmentConfig = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("content", "报价以沟通确认为准")
        ));

        assertThatThrownBy(() -> validator().normalize(7L, tooLongConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明不能超过 200 字");
        assertThatThrownBy(() -> validator().normalize(7L, invalidAlignmentConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明对齐方式不支持");
        assertThat(validator().normalize(7L, defaultAlignmentConfig)
                .getComponents().get(0).getConfig().get("alignment"))
                .isEqualTo("LEFT");
    }

    /**
     * 分割线组件应补齐默认颜色和默认高度，并规范化显式配置。
     */
    @Test
    void dividerShouldNormalizeColorAndHeight() {
        PortfolioConfigDto explicitConfig = config(component(
                "c_divider",
                PortfolioComponentTypeDict.DIVIDER.getCode(),
                1000,
                true,
                Map.of("color", "BLACK", "heightPx", "24")
        ));
        PortfolioConfigDto defaultConfig = config(component(
                "c_divider",
                PortfolioComponentTypeDict.DIVIDER.getCode(),
                1000,
                true,
                Map.of()
        ));

        PortfolioConfigDto explicitNormalized = validator().normalize(7L, explicitConfig);
        PortfolioConfigDto defaultNormalized = validator().normalize(7L, defaultConfig);

        assertThat(explicitNormalized.getComponents().get(0).getConfig())
                .containsEntry("color", "BLACK")
                .containsEntry("heightPx", 24);
        assertThat(defaultNormalized.getComponents().get(0).getConfig())
                .containsEntry("color", "GRAY")
                .containsEntry("heightPx", 16);
    }

    /**
     * 分割线组件应拒绝不支持的颜色或非正数高度。
     */
    @Test
    void dividerShouldRejectUnsupportedColorOrHeight() {
        PortfolioConfigDto invalidColorConfig = config(component(
                "c_divider",
                PortfolioComponentTypeDict.DIVIDER.getCode(),
                1000,
                true,
                Map.of("color", "BLUE", "heightPx", 16)
        ));
        PortfolioConfigDto invalidHeightConfig = config(component(
                "c_divider",
                PortfolioComponentTypeDict.DIVIDER.getCode(),
                1000,
                true,
                Map.of("color", "GRAY", "heightPx", 0)
        ));

        assertThatThrownBy(() -> validator().normalize(7L, invalidColorConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线颜色不支持");
        assertThatThrownBy(() -> validator().normalize(7L, invalidHeightConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线高度必须大于 0");
    }

    @Test
    void scheduleQueryShouldValidateQueryRange() {
        PortfolioConfigDto unlimitedConfig = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                true,
                Map.of("queryRange", Map.of("type", "UNLIMITED"))
        ));
        PortfolioConfigDto futureDaysConfig = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                true,
                Map.of("queryRange", Map.of("type", "FUTURE_DAYS", "futureDays", 0))
        ));
        PortfolioConfigDto dateRangeConfig = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                true,
                Map.of("queryRange", Map.of("type", "DATE_RANGE", "startDate", "2026-08-01", "endDate", "2026-07-01"))
        ));

        assertThat(validator().normalize(7L, unlimitedConfig).getComponents()).hasSize(1);
        assertThatThrownBy(() -> validator().normalize(7L, futureDaysConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期查询未来天数必须大于 0");
        assertThatThrownBy(() -> validator().normalize(7L, dateRangeConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期查询开始日期不能晚于结束日期");
    }

    @Test
    void scheduleQueryShouldNormalizeDisplayMode() {
        PortfolioConfigDto defaultConfig = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                true,
                Map.of()
        ));
        PortfolioConfigDto inlineConfig = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                true,
                Map.of("displayMode", "INLINE_CALENDAR")
        ));
        PortfolioConfigDto invalidConfig = config(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                1000,
                true,
                Map.of("displayMode", "SIDE_PANEL")
        ));

        PortfolioConfigDto defaultNormalized = validator().normalize(7L, defaultConfig);
        PortfolioConfigDto inlineNormalized = validator().normalize(7L, inlineConfig);

        assertThat(defaultNormalized.getComponents().get(0).getConfig().get("displayMode"))
                .isEqualTo("MODAL_CALENDAR");
        assertThat(inlineNormalized.getComponents().get(0).getConfig().get("displayMode"))
                .isEqualTo("INLINE_CALENDAR");
        assertThatThrownBy(() -> validator().normalize(7L, invalidConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期查询展示方式不支持");
    }

    @Test
    void unknownComponentTypeShouldBeRejected() {
        PortfolioConfigDto config = config(component("c_unknown", "PRICE_TABLE", 1000, true, Map.of()));

        assertThatThrownBy(() -> validator().normalize(7L, config))
                .isInstanceOf(BusinessException.class)
                .hasMessage("暂不支持的作品集组件：PRICE_TABLE");
    }

    @Test
    void invalidOrOtherUserWorkShouldBlockPublishing() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 8L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.PROCESSING.getCode())
        ));
        PortfolioConfigDto config = config(component(
                "c_grid",
                PortfolioComponentTypeDict.WORK_GRID.getCode(),
                1000,
                true,
                Map.of("title", "更多案例", "workIds", List.of(11L, 12L), "columns", 2)
        ));

        assertThatThrownBy(() -> validator().normalize(7L, config))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集引用了不可用作品，请刷新作品列表后重试");
    }

    @Test
    void contactFormShouldRequireContactNameAndAtLeastOneContactMethod() {
        PortfolioConfigDto.Component component = component(
                "c_form",
                PortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                1000,
                true,
                Map.of("fields", List.of("contactName", "phone", "wechat", "needs"))
        );

        assertThatThrownBy(() -> validator().validateContactFormSubmission(component, Map.of("contactName", "林安")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请至少填写手机号或微信号");

        assertThatThrownBy(() -> validator().validateContactFormSubmission(component, Map.of("phone", "13800138000")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请填写联系人");

        validator().validateContactFormSubmission(component, Map.of("contactName", "林安", "wechat", "wefolio"));
    }

    /**
     * 维护端只添加联系表单组件时，应默认启用访客输入字段。
     */
    @Test
    void contactFormShouldDefaultVisitorInputFieldsWhenMaintainerOnlyAddsComponent() {
        PortfolioConfigDto config = config(component(
                "c_form",
                PortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                1000,
                true,
                Map.of()
        ));

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        assertThat(normalized.getComponents().get(0).getConfig().get("fields"))
                .isEqualTo(List.of("contactName", "phone", "wechat", "needs"));
    }

    @Test
    void buildReferencesShouldIncludeWorksProfileScheduleAndQrAssets() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode()),
                work(12L, 7L, MediaTypeDict.VIDEO.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));
        PortfolioConfigDto config = config(
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                component("c_schedule", PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(), 2000, true, Map.of()),
                component("c_grid", PortfolioComponentTypeDict.WORK_GRID.getCode(), 3000, true,
                        Map.of("workIds", List.of(11L, 12L), "columns", 2)),
                component("c_list", PortfolioComponentTypeDict.WORK_LIST.getCode(), 3500, true,
                        Map.of("groups", List.of(
                                group("g_a", "全部案例", 1000, List.of(11L)),
                                group("g_b", "精选案例", 2000, List.of(11L))
                        ))),
                component("c_single", "SINGLE_WORK", 3750, true,
                        Map.of("workId", 12L, "showTitle", true)),
                component("c_qr", PortfolioComponentTypeDict.QR_CONTACT.getCode(), 4000, true,
                        Map.of("qrUrlSource", "PROFILE"))
        );

        PortfolioConfigDto normalized = validator().normalize(7L, config);
        List<PortfolioReferenceEntity> references = validator().buildReferences(
                99L,
                7L,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                normalized
        );

        assertThat(references).extracting(PortfolioReferenceEntity::getReferenceType)
                .contains(
                        ReferenceTypeDict.USER_PROFILE.getCode(),
                        ReferenceTypeDict.SCHEDULE_COMPONENT.getCode(),
                        ReferenceTypeDict.WORK.getCode(),
                        ReferenceTypeDict.QR_CODE_ASSET.getCode()
                );
        assertThat(references).allSatisfy(reference -> {
            assertThat(reference.getPortfolioId()).isEqualTo(99L);
            assertThat(reference.getConfigScope()).isEqualTo(PortfolioConfigScopeDict.DRAFT.getCode());
            assertThat(reference.getIsValid()).isEqualTo(1);
        });
        List<PortfolioReferenceEntity> workReferences = references.stream()
                .filter(reference -> ReferenceTypeDict.WORK.getCode().equals(reference.getReferenceType()))
                .toList();
        assertThat(workReferences.stream()
                .map(PortfolioReferenceEntity::getComponentPath))
                .containsExactly(
                        "components[2].config.groups[0].workIds[0]",
                        "components[2].config.groups[0].workIds[1]",
                        "components[3].config.groups[0].workIds[0]",
                        "components[3].config.groups[1].workIds[0]",
                        "components[4].config.workId"
                );
        assertThat(workReferences.stream()
                .map(PortfolioReferenceEntity::getReferenceId))
                .containsExactly(11L, 12L, 11L, 11L, 12L);
        assertThat(workReferences.get(4).getComponentKey()).isEqualTo("c_single");
        assertThat(workReferences.get(4).getSortOrder()).isZero();
    }

    /**
     * 新编辑器草稿归一化必须保留完整能力字段，并允许空次级菜单。
     */
    @Test
    void normalizeForDraftShouldKeepStyleNavigationAndEmptySecondaryMenu() {
        PortfolioConfigDto config = navigationConfig(
                " #151515 ",
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of()
        );

        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, config, null);

        assertThat(normalized.getEditorSchemaRevision())
                .isEqualTo(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(normalized.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(normalized.getBottomNav().getEnabled()).isTrue();
        assertThat(normalized.getBottomNav().getItems()).hasSize(2);
        assertThat(normalized.getBottomNav().getItems().getFirst().getComponents()).isNull();
        assertThat(normalized.getBottomNav().getItems().get(1).getComponents()).isEmpty();
    }

    /**
     * 非法背景色必须统一回退为白色。
     */
    @Test
    void normalizeForDraftShouldFallbackInvalidBackgroundColorToWhite() {
        PortfolioConfigDto config = navigationConfig(
                "#fff",
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_text",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "旅行记录")
                ))
        );

        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, config, null);

        assertThat(normalized.getStyle().getBackgroundColor())
                .isEqualTo(PortfolioConfigDto.DEFAULT_BACKGROUND_COLOR);
    }

    /**
     * 旧编辑器保存必须保留服务端草稿中的新能力字段，但顶层组件以旧请求为准。
     */
    @Test
    void normalizeForDraftShouldMergeLegacyEditorWithExistingNewDraft() {
        PortfolioConfigDto existingDraft = navigationConfig(
                "#151515",
                component("c_old", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_secondary",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "次级菜单内容")
                ))
        );
        PortfolioConfigDto legacyIncoming = config(component(
                "c_new",
                PortfolioComponentTypeDict.PROFILE.getCode(),
                1000,
                true,
                Map.of()
        ));

        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, legacyIncoming, existingDraft);

        assertThat(normalized.getEditorSchemaRevision())
                .isEqualTo(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(normalized.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(normalized.getComponents())
                .extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("c_new");
        assertThat(normalized.getBottomNav().getItems().get(1).getComponents())
                .extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("c_secondary");
    }

    /**
     * 服务端必须拒绝高于当前支持值的编辑器能力版本。
     */
    @Test
    void normalizeForDraftShouldRejectFutureEditorSchemaRevision() {
        PortfolioConfigDto config = config(component(
                "c_profile",
                PortfolioComponentTypeDict.PROFILE.getCode(),
                1000,
                true,
                Map.of()
        ));
        config.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT + 1);

        assertThatThrownBy(() -> validator().normalizeForDraft(7L, config, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前服务暂不支持此作品集配置，请稍后重试");
    }

    /**
     * 发布时每个导航菜单都必须包含至少一个启用组件。
     */
    @Test
    void validateForPublishShouldRejectEmptySecondaryMenuWithMenuPrefix() {
        PortfolioConfigDto draft = navigationConfig(
                "#FFFFFF",
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of()
        );
        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, draft, null);

        assertThatThrownBy(() -> validator().validateForPublish(7L, normalized))
                .isInstanceOf(BusinessException.class)
                .hasMessage("【作品】至少添加一个组件");
    }

    /**
     * 个人资料单例限制按菜单独立计算，不同菜单可各有一个。
     */
    @Test
    void normalizeForDraftShouldAllowOneProfilePerMenu() {
        PortfolioConfigDto config = navigationConfig(
                "#FFFFFF",
                component("c_profile_home", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_profile_works",
                        PortfolioComponentTypeDict.PROFILE.getCode(),
                        1000,
                        true,
                        Map.of()
                ))
        );

        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, config, null);

        assertThat(PortfolioComponentTraversal.listComponentLocations(normalized))
                .extracting(location -> location.component().getComponentKey())
                .containsExactly("c_profile_home", "c_profile_works");
    }

    /**
     * 组件实例键必须在所有菜单之间保持唯一。
     */
    @Test
    void normalizeForDraftShouldRejectDuplicateComponentKeyAcrossMenus() {
        PortfolioConfigDto config = navigationConfig(
                "#FFFFFF",
                component("c_same", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_same",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "重复键")
                ))
        );

        assertThatThrownBy(() -> validator().normalizeForDraft(7L, config, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("组件标识不能重复");
    }

    /**
     * 第一菜单不得在导航项中重复携带组件。
     */
    @Test
    void normalizeForDraftShouldRejectComponentsInsideFirstNavigationItem() {
        PortfolioConfigDto config = navigationConfig(
                "#FFFFFF",
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_text",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "次级菜单")
                ))
        );
        config.getBottomNav().getItems().getFirst().setComponents(List.of(component(
                "c_duplicate_source",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("content", "不应出现")
        )));

        assertThatThrownBy(() -> validator().normalizeForDraft(7L, config, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("第一个菜单不能重复保存组件");
    }

    /**
     * 导航名称在校验长度与重复前必须先去除首尾空白。
     */
    @Test
    void normalizeForDraftShouldTrimNavigationTitlesBeforeValidation() {
        PortfolioConfigDto config = navigationConfig(
                "#FFFFFF",
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_text",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "次级菜单")
                ))
        );
        config.getBottomNav().getItems().getFirst().setTitle(" 主页 ");
        config.getBottomNav().getItems().get(1).setTitle(" 作品 ");

        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, config, null);

        assertThat(normalized.getBottomNav().getItems())
                .extracting(PortfolioConfigDto.BottomNavItem::getTitle)
                .containsExactly("主页", "作品");
    }

    /**
     * 引用构建必须覆盖次级菜单并使用真实 config 属性路径。
     */
    @Test
    void buildReferencesShouldIncludeSecondaryMenuWithCanonicalConfigPaths() {
        when(workEntityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                work(11L, 7L, MediaTypeDict.IMAGE.getCode(), WorkStatusDict.ACTIVE.getCode())
        ));
        PortfolioConfigDto config = navigationConfig(
                "#FFFFFF",
                component(
                        "c_carousel",
                        PortfolioComponentTypeDict.CAROUSEL.getCode(),
                        1000,
                        true,
                        Map.of("workIds", List.of(11L))
                ),
                List.of(component(
                        "c_single",
                        PortfolioComponentTypeDict.SINGLE_WORK.getCode(),
                        1000,
                        true,
                        Map.of("workId", 11L)
                ))
        );
        PortfolioConfigDto normalized = validator().normalizeForDraft(7L, config, null);

        List<PortfolioReferenceEntity> references = validator().buildReferences(
                99L,
                7L,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                normalized
        );

        assertThat(references).extracting(PortfolioReferenceEntity::getComponentPath)
                .containsExactly(
                        "components[0].config.workIds[0]",
                        "bottomNav.items[1].components[0].config.workId"
                );
    }

    /**
     * 新编辑器请求携带背景色和底部导航时，合并结果必须使用 incoming 的字段。
     */
    @Test
    void mergeCompatibleConfigShouldUseIncomingFieldsForNewEditor() {
        PortfolioConfigDto incoming = navigationConfig(
                "#151515",
                component("c_profile", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of()
        );

        PortfolioConfigDto merged = validator().normalizeForDraft(7L, incoming, null);

        assertThat(merged.getEditorSchemaRevision())
                .isEqualTo(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(merged.getBottomNav().getEnabled()).isTrue();
        assertThat(merged.getBottomNav().getItems()).hasSize(2);
    }

    /**
     * 旧编辑器保存新编辑器创建的配置时，必须从草稿中保留 style、bottomNav 和 editorSchemaRevision。
     */
    @Test
    void mergeCompatibleConfigShouldPreserveNewFieldsForLegacyEditor() {
        PortfolioConfigDto existingDraft = navigationConfig(
                "#151515",
                component("c_old", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_secondary",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "次级菜单内容")
                ))
        );
        PortfolioConfigDto legacyIncoming = config(component(
                "c_new",
                PortfolioComponentTypeDict.PROFILE.getCode(),
                1000,
                true,
                Map.of()
        ));

        PortfolioConfigDto merged = validator().normalizeForDraft(7L, legacyIncoming, existingDraft);

        assertThat(merged.getEditorSchemaRevision())
                .isEqualTo(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(merged.getBottomNav().getItems().get(1).getComponents())
                .extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("c_secondary");
    }

    /**
     * 显式携带旧能力版本的编辑器保存新草稿时，也不得把草稿能力版本降级。
     */
    @Test
    void mergeCompatibleConfigShouldPreserveNewFieldsForExplicitLegacyRevision() {
        PortfolioConfigDto existingDraft = navigationConfig(
                "#151515",
                component("c_old", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_secondary",
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        1000,
                        true,
                        Map.of("content", "次级菜单内容")
                ))
        );
        PortfolioConfigDto legacyIncoming = config(component(
                "c_new",
                PortfolioComponentTypeDict.PROFILE.getCode(),
                1000,
                true,
                Map.of()
        ));
        legacyIncoming.setEditorSchemaRevision(1);

        PortfolioConfigDto merged = validator().normalizeForDraft(7L, legacyIncoming, existingDraft);
        PortfolioConfigDto normalizedAgain = validator().normalizeForDraft(7L, merged, null);

        assertThat(merged.getEditorSchemaRevision())
                .isEqualTo(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(normalizedAgain.getEditorSchemaRevision())
                .isEqualTo(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(normalizedAgain.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(normalizedAgain.getBottomNav().getItems().get(1).getComponents())
                .extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("c_secondary");
    }

    /**
     * 旧编辑器保存不存在新字段的旧草稿时，合并后 style 和 bottomNav 为默认值。
     */
    @Test
    void mergeCompatibleConfigShouldApplyDefaultsWhenNoNewFieldsExist() {
        PortfolioConfigDto legacyIncoming = config(component(
                "c_profile",
                PortfolioComponentTypeDict.PROFILE.getCode(),
                1000,
                true,
                Map.of()
        ));

        PortfolioConfigDto merged = validator().normalizeForDraft(7L, legacyIncoming, null);

        assertThat(merged.getEditorSchemaRevision()).isNull();
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo(
                PortfolioConfigDto.DEFAULT_BACKGROUND_COLOR);
        assertThat(merged.getBottomNav().getEnabled()).isFalse();
        assertThat(merged.getBottomNav().getItems()).isNull();
    }

    /**
     * 菜单模式下组件业务错误必须附加【菜单名】前缀。
     */
    @Test
    void normalizeForDraftShouldWrapComponentErrorsWithMenuPrefix() {
        PortfolioConfigDto config = navigationConfig(
                "#FFFFFF",
                component("c_home", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, true, Map.of()),
                List.of(component(
                        "c_bad",
                        PortfolioComponentTypeDict.CAROUSEL.getCode(),
                        1000,
                        true,
                        Map.of()  // 空作品列表 → 抛出业务错误
                ))
        );

        assertThatThrownBy(() -> validator().normalizeForDraft(7L, config, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageStartingWith("【作品】");
    }

    /**
     * 旧配置无导航时组件错误不得附加菜单前缀。
     */
    @Test
    void normalizeShouldNotWrapComponentErrorsWithMenuPrefix() {
        PortfolioConfigDto config = config(component(
                "c_bad",
                PortfolioComponentTypeDict.CAROUSEL.getCode(),
                1000,
                true,
                Map.of()  // 空作品列表 → 抛出业务错误
        ));

        assertThatThrownBy(() -> validator().normalize(7L, config))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择要展示的作品");  // 无菜单前缀
    }

    private PortfolioConfigValidator validator() {
        return new PortfolioConfigValidator(workEntityMapper, userEntityMapper);
    }

    /**
     * 构造启用两项底部导航的新编辑器配置。
     */
    private PortfolioConfigDto navigationConfig(
            String backgroundColor,
            PortfolioConfigDto.Component firstMenuComponent,
            List<PortfolioConfigDto.Component> secondMenuComponents
    ) {
        PortfolioConfigDto config = config(firstMenuComponent);
        config.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);

        PortfolioConfigDto.Style style = new PortfolioConfigDto.Style();
        style.setBackgroundColor(backgroundColor);
        config.setStyle(style);

        PortfolioConfigDto.BottomNavItem firstItem = new PortfolioConfigDto.BottomNavItem();
        firstItem.setKey("nav_home");
        firstItem.setTitle("主页");

        PortfolioConfigDto.BottomNavItem secondItem = new PortfolioConfigDto.BottomNavItem();
        secondItem.setKey("nav_works");
        secondItem.setTitle("作品");
        secondItem.setComponents(secondMenuComponents);

        PortfolioConfigDto.BottomNav bottomNav = new PortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(firstItem, secondItem));
        config.setBottomNav(bottomNav);
        return config;
    }

    private PortfolioConfigDto config(PortfolioConfigDto.Component... components) {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        PortfolioConfigDto.Share share = new PortfolioConfigDto.Share();
        share.setTitle("林安婚礼司仪");
        config.setShare(share);
        config.setComponents(List.of(components));
        return config;
    }

    private PortfolioConfigDto.Component component(
            String key,
            String type,
            Integer sortOrder,
            boolean enabled,
            Map<String, Object> config
    ) {
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setSortOrder(sortOrder);
        component.setEnabled(enabled);
        component.setConfig(new LinkedHashMap<>(config));
        return component;
    }

    private Map<String, Object> group(String key, String name, Integer sortOrder, List<?> workIds) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("groupKey", key);
        group.put("name", name);
        group.put("sortOrder", sortOrder);
        group.put("workIds", workIds);
        return group;
    }

    private WorkEntity work(Long id, Long userId, String mediaType, String status) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(userId);
        work.setMediaType(mediaType);
        work.setTitle("作品" + id);
        work.setStatus(status);
        return work;
    }
}
