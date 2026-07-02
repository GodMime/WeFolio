package com.jxc.wefolio.service;

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
        assertThat(references.stream()
                .filter(reference -> ReferenceTypeDict.WORK.getCode().equals(reference.getReferenceType()))
                .map(PortfolioReferenceEntity::getReferenceId))
                .containsExactly(11L, 12L);
    }

    private PortfolioConfigValidator validator() {
        return new PortfolioConfigValidator(workEntityMapper, userEntityMapper);
    }

    private PortfolioConfigDto config(PortfolioConfigDto.Component... components) {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        PortfolioConfigDto.Share share = new PortfolioConfigDto.Share();
        share.setTitle("林安婚礼司仪");
        share.setIntro("沉稳、温暖、节奏清晰");
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
