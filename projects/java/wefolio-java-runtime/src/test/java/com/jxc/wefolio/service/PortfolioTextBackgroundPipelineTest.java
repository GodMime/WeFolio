package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.message.PortfolioTextMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyCollection;

/** 文字背景个人保存、引用与访客资源授权测试。 */
class PortfolioTextBackgroundPipelineTest {
    /** 两种支持背景的文字组件。 */
    private static final List<String> TEXT_TYPES = List.of(PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
            PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION.getCode());
    /** 可播放的视频资源键及地址。 */
    private static final String VIDEO_KEY = "work/background.mp4";
    /** 视频原资源地址。 */
    private static final String VIDEO_URL = "https://cdn.example/background.mp4";
    /** 独立视频封面资源键。 */
    private static final String POSTER_KEY = "work/poster.jpg";
    /** 视频封面地址。 */
    private static final String POSTER_URL = "https://cdn.example/poster.jpg";
    /** 可选视频封面展示字段。 */
    private static final String POSTER_FIELD = "posterUrl";
    /** 数据库边界模拟。 */
    private final WorkEntityMapper mapper = mock(WorkEntityMapper.class);
    /** COS 边界模拟。 */
    private final CosService cos = mock(CosService.class);

    /** 两类文字均接受图片、动图和视频背景，发布并生成指向真实配置字段的引用。 */
    @Test void bothTextTypesAcceptImagesAnimationsVideosAndCreateReferences() {
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            for (MediaTypeDict media : List.of(MediaTypeDict.IMAGE,MediaTypeDict.ANIMATION,MediaTypeDict.VIDEO)) {
                when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(media,7L)));
                PortfolioConfigValidator validator = new PortfolioConfigValidator(mapper);
                PortfolioConfigDto normalized = validator.normalize(7L,config(type,true));
                validator.validateForPublish(7L,normalized);
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
            for (WorkEntity work : List.of(work(null,7L),work(MediaTypeDict.IMAGE,8L),work(MediaTypeDict.VIDEO,8L))) {
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
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos,
                mock(PortfolioBackgroundAudioService.class));
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(MediaTypeDict.ANIMATION,7L)));
            when(cos.publicUrl("work/original.gif")).thenReturn("https://cdn.example/original.gif");
            String field = type.equals("TEXT_SECTION") ? "textSection" : "structuredTextSection";
            JSONObject data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config(type,true),true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).isNotNull().containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",false);
            assertThat(data.getJSONObject("backgroundWork")).containsEntry("url","https://cdn.example/original.gif")
                    .containsEntry("width",600).containsEntry("height",900).doesNotContainKey(POSTER_FIELD);
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(MediaTypeDict.IMAGE,8L)));
            data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config(type,true),true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
            assertThat(data.get("backgroundWork")).isNull();
            assertThat(data.toJSONString()).doesNotContain("injected", "https://cdn.example/original.gif");
        }
        verify(cos,never()).publicUrl(POSTER_KEY);
    }

    /** 视频返回可播放原地址和独立可选封面；缺失封面不影响背景可用。 */
    @Test void rendersVideoWithOptionalPoster() throws Exception {
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L);
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos,
                mock(PortfolioBackgroundAudioService.class));
        when(cos.publicUrl(VIDEO_KEY)).thenReturn(VIDEO_URL);
        when(cos.publicUrl(POSTER_KEY)).thenReturn(POSTER_URL);
        for (String type : TEXT_TYPES) {
            for (String poster : new String[]{POSTER_KEY,null," "}) {
                WorkEntity video = work(MediaTypeDict.VIDEO,7L); video.setCoverObjectKey(poster);
                when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(video));
                JSONObject data = JSON.parseObject(new ObjectMapper().writeValueAsString(
                        service.render(portfolio,config(type,true),true,false,null,null)))
                        .getJSONArray("components").getJSONObject(0).getJSONObject(renderField(type));
                assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",false);
                JSONObject background = data.getJSONObject("backgroundWork");
                assertThat(background).containsEntry("workId",11).containsEntry("mediaType",MediaTypeDict.VIDEO.getCode())
                        .containsEntry("url",VIDEO_URL).containsEntry("width",600).containsEntry("height",900);
                if (POSTER_KEY.equals(poster)) {
                    assertThat(background).containsEntry(POSTER_FIELD,POSTER_URL);
                } else {
                    assertThat(background).doesNotContainKey(POSTER_FIELD);
                }
            }
        }
    }

    /** 视频失去归属、处理成功或审核通过条件后，拒绝保存发布且不下发视频与封面。 */
    @Test void unavailableVideosCannotSavePublishOrLeakMedia() {
        List<Consumer<WorkEntity>> invalidators = List.of(video -> video.setUserId(8L),
                video -> video.setStatus(WorkStatusDict.PROCESSING.getCode()),
                video -> video.setStatus(WorkStatusDict.PROCESSING_FAILED.getCode()),
                video -> video.setAuditStatus(WorkAuditStatusDict.PENDING.getCode()),
                video -> video.setAuditStatus(WorkAuditStatusDict.REJECTED.getCode()),
                video -> video.setAuditStatus(null));
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L);
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos,
                mock(PortfolioBackgroundAudioService.class));
        for (String type : TEXT_TYPES) {
            for (Consumer<WorkEntity> invalidator : invalidators) {
                WorkEntity video = work(MediaTypeDict.VIDEO,7L);
                when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(video));
                PortfolioConfigValidator validator = new PortfolioConfigValidator(mapper);
                PortfolioConfigDto saved = validator.normalize(7L,config(type,true));
                invalidator.accept(video);
                assertThatThrownBy(() -> validator.normalize(7L,config(type,true)))
                        .isInstanceOf(BusinessException.class).hasMessage(PortfolioTextMessage.BACKGROUND_UNAVAILABLE);
                assertThatThrownBy(() -> validator.validateForPublish(7L,saved))
                        .isInstanceOf(BusinessException.class).hasMessage(PortfolioTextMessage.BACKGROUND_UNAVAILABLE);
                JSONObject data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,saved,true,false,null,null)))
                        .getJSONArray("components").getJSONObject(0).getJSONObject(renderField(type));
                assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
                assertThat(data.get("backgroundWork")).isNull();
                assertThat(data).containsKey(PortfolioComponentTypeDict.TEXT_SECTION.getCode().equals(type) ? "content" : "blocks");
                assertThat(data.toJSONString()).doesNotContain("injected",VIDEO_URL,POSTER_URL);
            }
        }
        verifyNoInteractions(cos);
    }

    /** 缺失视频原资源时封面不能冒充可播放背景，继续保留正文。 */
    @Test void missingVideoSourceRendersInvalidBackgroundWithoutPoster() {
        WorkEntity video = work(MediaTypeDict.VIDEO,7L); video.setMediaObjectKey(" ");
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(video));
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L);
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos,
                mock(PortfolioBackgroundAudioService.class));
        for (String type : TEXT_TYPES) {
            JSONObject data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config(type,true),true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(renderField(type));
            assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
            assertThat(data.get("backgroundWork")).isNull();
            assertThat(data).containsKey(PortfolioComponentTypeDict.TEXT_SECTION.getCode().equals(type) ? "content" : "blocks");
        }
        verifyNoInteractions(cos);
    }

    /** 历史配置丢失作品标识时保留文字，发布重新验证资源。 */
    @Test void missingBackgroundDoesNotEraseTextAndPublishRevalidates() {
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L);
        PortfolioRenderService service = new PortfolioRenderService(mapper,mock(PortfolioEntityMapper.class),cos,
                mock(PortfolioBackgroundAudioService.class));
        for (String type : List.of("TEXT_SECTION","STRUCTURED_TEXT_SECTION")) {
            PortfolioConfigDto config = config(type,true);
            config.getComponents().getFirst().getConfig().remove("backgroundWorkId");
            String field = type.equals("TEXT_SECTION") ? "textSection" : "structuredTextSection";
            JSONObject data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,config,true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).containsEntry("backgroundEnabled",true).containsEntry("backgroundInvalid",true);
            assertThat(data.get("backgroundWork")).isNull();
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(work(MediaTypeDict.VIDEO,7L)));
            PortfolioConfigValidator validator = new PortfolioConfigValidator(mapper);
            PortfolioConfigDto saved = validator.normalize(7L,config(type,true));
            when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of());
            assertThatThrownBy(() -> validator.validateForPublish(7L,saved)).isInstanceOf(BusinessException.class);
            data = JSON.parseObject(JSON.toJSONString(service.render(portfolio,saved,true,false,null,null)))
                    .getJSONArray("components").getJSONObject(0).getJSONObject(field);
            assertThat(data).containsEntry("backgroundInvalid",true);
            assertThat(data.get("backgroundWork")).isNull();
        }
    }

    /** 将文字组件类型映射到对应展示字段。 */
    private String renderField(String type) {
        return PortfolioComponentTypeDict.TEXT_SECTION.getCode().equals(type) ? "textSection" : "structuredTextSection";
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
        work.setMediaType(media == null ? null : media.getCode()); work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        work.setMediaObjectKey(media == MediaTypeDict.VIDEO ? VIDEO_KEY : "work/original.gif");
        work.setCoverObjectKey(POSTER_KEY);
        work.setWidth(600); work.setHeight(900); return work;
    }
}
