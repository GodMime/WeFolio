package com.jxc.wefolio.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.controller.PortfolioAiController;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.exception.GlobalExceptionHandler;
import com.jxc.wefolio.service.portfolioai.PortfolioAiApplicationService;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import com.jxc.wefolio.service.portfolioai.PortfolioAiInput;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证真实 HTTP 转换链保留严格 JSON 原文，同时兼容普通 DTO、响应和 XML。 */
class FastJsonWebConfigTest {
    /** 模拟 Spring 默认转换器列表并应用生产配置。 */
    private List<HttpMessageConverter<?>> converters() {
        List<HttpMessageConverter<?>> result = new ArrayList<>(List.of(
                new StringHttpMessageConverter(StandardCharsets.UTF_8), new MappingJackson2HttpMessageConverter()));
        new FastJsonWebConfig().extendMessageConverters(result);
        return result;
    }

    /** 按与 Spring 相同的优先级选择可读取指定类型的转换器。 */
    @SuppressWarnings("unchecked")
    private <T> HttpMessageConverter<T> reader(List<HttpMessageConverter<?>> configured, Class<T> type, MediaType mediaType) {
        return (HttpMessageConverter<T>) configured.stream().filter(item -> item.canRead(type, mediaType)).findFirst().orElseThrow();
    }

    /** 使用失败请求的结构与测试身份，包含显式空配置、断号来源和中文换行。 */
    private String createRequest(String ownerType) {
        return """
                {"ownerType":"%s","ownerId":"101","portfolioId":null,"mode":"CREATE",
                 "targetMenuKey":null,"baseDraftRevision":0,"baseConfig":null,
                 "localEditRevision":6,"editorSchemaRevision":%d,"aiContractVersion":"ai-portfolio-v1",
                 "clientCapabilities":{"portfolioRemoteFont":1},"aiContext":{"version":1,"nextSourceNo":6,
                 "sources":[{"sourceNo":1,"kind":"WORK","sourceId":"201"},{"sourceNo":2,"kind":"WORK","sourceId":"202"},
                 {"sourceNo":4,"kind":"WORK","sourceId":"204"},{"sourceNo":5,"kind":"WORK","sourceId":"205"}]},
                 "prompt":"1号作品作为背景\\n4号作为背景音乐","idempotencyKey":"ai-mv0or2px-pz7httnx"}
                """.formatted(ownerType, "TEAM".equals(ownerType) ? 10 : 14);
    }

    /** 从真实路由读取请求，显式 baseConfig:null 不得在业务严格校验之前消失。 */
    @Test void createTaskKeepsExplicitNullThroughHttpBinding() throws Exception {
        PortfolioAiApplicationService service = mock(PortfolioAiApplicationService.class);
        when(service.submit(anyString())).thenAnswer(invocation -> {
            PortfolioAiInput.normalize(StrictAiJson.parseObject(invocation.getArgument(0), 262144, 16), new PortfolioAiProperties());
            return Map.of("taskId", "7001", "status", "PENDING");
        });
        var mvc = MockMvcBuilders.standaloneSetup(new PortfolioAiController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converters().toArray(HttpMessageConverter[]::new)).build();
        for (String ownerType : List.of("USER", "TEAM")) {
            String request = createRequest(ownerType);
            mvc.perform(post("/api/mine/portfolio-ai/tasks").contentType(MediaType.APPLICATION_JSON)
                    .content(request.getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
            verify(service).submit(request);
        }
    }

    /** 重复键、显式 null 与空白必须原样交给 Service，HTTP 层不能先合并或重写。 */
    @Test void rawJsonStringPreservesDuplicateKeysNullAndUnicode() throws Exception {
        String duplicate = "{\n  \"prompt\":\"第一段😀\", \"prompt\":\"第二段\", \"baseConfig\":null\n}";
        for (String raw : List.of("{\"baseConfig\":null}", duplicate)) {
            var input = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8));
            input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            HttpMessageConverter<String> converter = reader(converters(), String.class, MediaType.APPLICATION_JSON);
            assertThat(converter.read(String.class, input)).isEqualTo(raw);
        }
        assertThatThrownBy(() -> StrictAiJson.parseObject(duplicate, 4096, 16)).isInstanceOf(AiProtocolException.class);
    }

    /** 普通 DTO 继续由 Fastjson 读取，日期和数值类型保持现有语义。 */
    @Test void dtoRequestsStillUseFastJson() throws Exception {
        HttpMessageConverter<PortfolioScheduleQueryRequest> converter = reader(converters(), PortfolioScheduleQueryRequest.class, MediaType.APPLICATION_JSON);
        assertThat(converter).isInstanceOf(FastJsonHttpMessageConverter.class);
        var input = new MockHttpInputMessage("{\"componentKey\":\"schedule\",\"queriedDate\":\"2026-10-09\",\"slotDefinitionId\":1}".getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        PortfolioScheduleQueryRequest value = converter.read(PortfolioScheduleQueryRequest.class, input);
        assertThat(value.getComponentKey()).isEqualTo("schedule");
        assertThat(value.getQueriedDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(value.getSlotDefinitionId()).isEqualTo(1L);
    }

    /** JSON 包装响应以及字符串响应仍采用 Fastjson，不受原文读取转换器影响。 */
    @Test void jsonResponsesKeepFastJsonSerialization() throws Exception {
        List<HttpMessageConverter<?>> configured = converters();
        HttpMessageConverter<?> selected = configured.stream().filter(item -> item.canWrite(Response.class, MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        assertThat(selected).isInstanceOf(FastJsonHttpMessageConverter.class);
        assertThat(configured.stream().filter(item -> item.canWrite(String.class, MediaType.APPLICATION_JSON)).findFirst().orElseThrow())
                .isInstanceOf(FastJsonHttpMessageConverter.class);
        var output = new MockHttpOutputMessage();
        ((FastJsonHttpMessageConverter) selected).write(Response.success(Map.of("title", "中文作品集")), MediaType.APPLICATION_JSON, output);
        assertThat(JSON.parseObject(output.getBodyAsString(StandardCharsets.UTF_8)))
                .isEqualTo(JSON.parseObject("{\"success\":true,\"message\":\"ok\",\"data\":{\"title\":\"中文作品集\"}}"));
    }

    /** 微信支付 XML 回调沿默认字符串转换器读取和响应，不经过 JSON 专用边界。 */
    @Test void xmlRequestsAndResponsesKeepDefaultStringConverter() throws Exception {
        List<HttpMessageConverter<?>> configured = converters();
        HttpMessageConverter<String> converter = reader(configured, String.class, MediaType.TEXT_XML);
        assertThat(converter.getSupportedMediaTypes()).contains(MediaType.ALL);
        String raw = "<xml><return_code>SUCCESS</return_code></xml>";
        var input = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.TEXT_XML);
        assertThat(converter.read(String.class, input)).isEqualTo(raw);
        assertThat(configured.stream().filter(item -> item.canWrite(String.class, MediaType.TEXT_XML)).findFirst().orElseThrow()).isSameAs(converter);
    }
}
