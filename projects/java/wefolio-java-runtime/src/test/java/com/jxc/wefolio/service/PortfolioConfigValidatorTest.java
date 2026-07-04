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
                Map.of("title", "更多案例", "workIds", List.of(11L, 12L), "columns", 2)
        ));

        PortfolioConfigDto normalized = validator().normalize(7L, config);

        assertThat(normalized.getComponents().get(0).getConfig().get("workIds")).isEqualTo(List.of(11L, 12L));
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
                Map.of("title", " ", "content", "报价以沟通确认为准")
        ));
        PortfolioConfigDto invalidConfig = config(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                1000,
                true,
                Map.of("title", "服务说明", "content", " ")
        ));

        assertThat(validator().normalize(7L, validConfig).getComponents()).hasSize(1);
        assertThatThrownBy(() -> validator().normalize(7L, invalidConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明内容不能为空");
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
                        "components[2].groups[0].workIds[0]",
                        "components[2].groups[0].workIds[1]",
                        "components[3].groups[0].workIds[0]",
                        "components[3].groups[1].workIds[0]"
                );
        assertThat(workReferences.stream()
                .map(PortfolioReferenceEntity::getReferenceId))
                .containsExactly(11L, 12L, 11L, 11L);
    }

    private PortfolioConfigValidator validator() {
        return new PortfolioConfigValidator(workEntityMapper, userEntityMapper);
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
