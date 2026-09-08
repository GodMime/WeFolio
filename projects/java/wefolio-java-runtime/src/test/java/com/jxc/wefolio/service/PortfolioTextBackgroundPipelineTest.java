package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyCollection;

/** 文字背景个人保存、引用与访客资源授权测试。 */
class PortfolioTextBackgroundPipelineTest {
    /** 数据库边界模拟。 */
    private final WorkEntityMapper mapper = mock(WorkEntityMapper.class);
    /** COS 边界模拟。 */
    private final CosService cos = mock(CosService.class);

    /** 两类文字均接受图片动图背景并生成指向真实配置字段的引用。 */
    @Test void bothTextTypesAcceptImagesAnimationsAndCreateReferences() {
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            for (MediaTypeDict media : List.of(MediaTypeDict.IMAGE,MediaTypeDict.ANIMATION)) {
                when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(media,7L)));
                PortfolioConfigValidator validator = new PortfolioConfigValidator(mapper);
                PortfolioConfigDto normalized = validator.normalize(7L,config(type,true));
                JSONObject data = new JSONObject(normalized.getComponents().getFirst().getConfig());
                assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundWorkId",11L)
                        .containsEntry("backgroundTreatment","GRADIENT").doesNotContainKeys("backgroundWork","backgroundMemberUserId");
                var references = validator.buildReferences(90L,7L,"DRAFT",normalized);
                assertThat(references).hasSize(1);
                assertThat(references.getFirst().getReferenceId()).isEqualTo(11L);
                assertThat(references.getFirst().getComponentPath()).isEqualTo("components[0].config.backgroundWorkId");
            }
        }
    }

    /** 非法或无权背景不能通过保存，关闭后清除资源标识并释放引用。 */
    @Test void rejectsUnavailableBackgroundAndClearsDisabledReference() {
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            for (WorkEntity work : List.of(work(MediaTypeDict.VIDEO,7L),work(MediaTypeDict.IMAGE,8L))) {
                when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work));
                assertThatThrownBy(() -> new PortfolioConfigValidator(mapper).normalize(7L,config(type,true)))
                        .isInstanceOf(BusinessException.class);
            }
            PortfolioConfigDto missing = config(type,true);
            missing.getComponents().getFirst().getConfig().remove("backgroundWorkId");
            assertThatThrownBy(() -> new PortfolioConfigValidator(mapper).normalize(7L,missing))
                    .isInstanceOf(BusinessException.class);
            PortfolioConfigValidator validator = new PortfolioConfigValidator(mapper);
            PortfolioConfigDto disabled = validator.normalize(7L,config(type,false));
            assertThat(disabled.getComponents().getFirst().getConfig()).doesNotContainKeys("backgroundWorkId","backgroundMemberUserId","backgroundWork");
            assertThat(validator.buildReferences(90L,7L,"DRAFT",disabled)).isEmpty();
        }
    }

    /** 渲染背景保留原动图与宽高，失效时保留文字和开关且不下发URL。 */
    @Test void rendersOriginalBackgroundAndPreservesContentWhenUnauthorized() {
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L); portfolio.setId(90L);
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos);
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(MediaTypeDict.ANIMATION,7L)));
            when(cos.publicUrl("work/original.gif")).thenReturn("https://cdn.example/original.gif");
            String field = type.equals("TEXT_SECTION") ? "textSection" : "structuredTextSection";
            JSONObject data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config(type,true),true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).isNotNull().containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",false);
            assertThat(data.getJSONObject("backgroundWork")).containsEntry("url","https://cdn.example/original.gif")
                    .containsEntry("width",600).containsEntry("height",900);
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(MediaTypeDict.IMAGE,8L)));
            data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config(type,true),true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
            assertThat(data.get("backgroundWork")).isNull();
            assertThat(data.toJSONString()).doesNotContain("injected", "https://cdn.example/original.gif");
        }
    }

    /** 历史配置丢失作品标识时保留文字，发布重新验证资源。 */
    @Test void missingBackgroundDoesNotEraseTextAndPublishRevalidates() {
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L);
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos);
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            PortfolioConfigDto config = config(type,true);
            config.getComponents().getFirst().getConfig().remove("backgroundWorkId");
            String field = type.equals("TEXT_SECTION") ? "textSection" : "structuredTextSection";
            JSONObject data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config,true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
            assertThat(data.get("backgroundWork")).isNull();
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(MediaTypeDict.IMAGE,7L)));
            PortfolioConfigValidator validator = new PortfolioConfigValidator(mapper);
            PortfolioConfigDto saved = validator.normalize(7L,config(type,true));
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of());
            assertThatThrownBy(() -> validator.validateForPublish(7L,saved)).isInstanceOf(BusinessException.class);
        }
    }

    /** 手工配置同时含恶意展示字段，保证持久化白名单。 */
    private PortfolioConfigDto config(String type,boolean enabled) {
        PortfolioConfigDto config = JSON.parseObject("""
                {"schemaVersion":"standard-personal-v1","components":[{"componentKey":"c_text",
                 "componentType":"TEXT_SECTION","enabled":true,"sortOrder":1000,"config":{
                 "content":"文字内容","backgroundEnabled":true,"backgroundWorkId":11,"backgroundMemberUserId":8,
                 "backgroundWork":{"url":"injected"},"blocks":[{"blockKey":"b","type":"TITLE","content":"标题"}]}}]}
                """,PortfolioConfigDto.class);
        config.getComponents().getFirst().setComponentType(type);
        config.getComponents().getFirst().getConfig().put("backgroundEnabled",enabled);
        return config;
    }

    /** 构造完整可用作品。 */
    private WorkEntity work(MediaTypeDict media,Long owner) {
        WorkEntity work = new WorkEntity(); work.setId(11L); work.setUserId(owner);
        work.setMediaType(media.getCode()); work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode()); work.setMediaObjectKey("work/original.gif");
        work.setWidth(600); work.setHeight(900); return work;
    }
}
