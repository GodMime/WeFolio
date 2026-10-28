package com.jxc.wefolio.dto;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.config.FastJsonWebConfig;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioCreateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.message.PortfolioMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 普通请求的实际 JSON 转换边界不得吞掉高级元数据，历史宽松字段规则保持不变。 */
class PortfolioRequestConfigBoundaryTest {
    /** 创建、保存与复用保存请求的字体准备均经过这四类 DTO。 */
    private static final List<Class<?>> REQUEST_TYPES = List.of(MinePortfolioCreateRequest.class,
            MinePortfolioDraftSaveRequest.class, TeamPortfolioCreateRequest.class, TeamPortfolioDraftSaveRequest.class);

    /** 原始对象必须先抵达请求 setter，合法版本和所有非法显式值均拒绝。 */
    @Test void fastJsonRejectsAdvancedMetadataBeforeOrdinaryConfigConversion() {
        for (Class<?> type : REQUEST_TYPES) {
            for (String value : List.of("1", "2", "null", "\"2\"", "true", "2.5", "{}")) {
                String raw = "{\"config\":{\"advancedPortfolioVersion\":" + value + ",\"components\":[]}}";
                assertThatThrownBy(() -> JSON.parseObject(raw, type))
                        .as(type.getSimpleName() + " / " + value)
                        .hasStackTraceContaining(PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE);
            }
        }
    }

    /** 生产 HTTP converter 同样走原始 setter，不会先把配置转为普通 DTO。 */
    @Test void actualHttpConverterRejectsAdvancedMetadata() {
        for (Class<?> type : REQUEST_TYPES) {
            assertThatThrownBy(() -> readHttp(type, "{\"config\":{\"advancedPortfolioVersion\":2}}"))
                    .as(type.getSimpleName())
                    .hasStackTraceContaining(PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE);
        }
    }

    /** 未知请求键、未知配置键及普通字段类型强转继续沿原来的绑定规则。 */
    @Test void unknownFieldsAndLegacyCoercionsRemainUnchanged() throws Exception {
        String raw = """
                {"unknownRequest":"retained-old-contract","clientRevision":"3","config":{
                  "schemaVersion":"standard-personal-v1","editorSchemaRevision":"14","unknownConfig":true,
                  "components":[{"componentKey":"text","componentType":"TEXT_SECTION","enabled":"true",
                    "sortOrder":"1000","config":{"content":"正文","advancedPortfolioVersion":"ordinary-component-key"}}]}}
                """;
        for (Class<?> type : REQUEST_TYPES) {
            Class<?> legacy = type.getPackageName().endsWith("teamportfolio") ? LegacyTeamRequest.class : LegacyPersonalRequest.class;
            String expected = configJson(JSON.parseObject(raw, legacy));
            assertThat(configJson(JSON.parseObject(raw, type))).isEqualTo(expected);
            assertThat(configJson(readHttp(type, raw))).isEqualTo(expected);
        }
    }

    /** 缺省和显式空配置保留，内部传入的普通 DTO 无需重新构建。 */
    @Test void absentNullAndDirectConfigAssignmentRemainCompatible() throws Exception {
        for (Class<?> type : REQUEST_TYPES) {
            assertThat(configJson(JSON.parseObject("{}", type))).isEqualTo("null");
            assertThat(configJson(JSON.parseObject("{\"config\":null}", type))).isEqualTo("null");
        }
        PortfolioConfigDto personal = new PortfolioConfigDto();
        MinePortfolioDraftSaveRequest personalRequest = new MinePortfolioDraftSaveRequest();
        personalRequest.setConfig(personal);
        assertThat(personalRequest.getConfig()).isSameAs(personal);
        TeamPortfolioConfigDto team = new TeamPortfolioConfigDto();
        TeamPortfolioDraftSaveRequest teamRequest = new TeamPortfolioDraftSaveRequest();
        teamRequest.setConfig(team);
        assertThat(teamRequest.getConfig()).isSameAs(team);
    }

    /** 原本不接受的数组及字符串内嵌 JSON 不能绕过根字段边界，旧空值表示继续接受。 */
    @Test void nonObjectConfigShapesMatchThePreviousTypedBinding() throws Exception {
        for (Class<?> type : REQUEST_TYPES) {
            Class<?> legacy = type.getPackageName().endsWith("teamportfolio") ? LegacyTeamRequest.class : LegacyPersonalRequest.class;
            for (String value : List.of("null", "\"\"", "\"null\"", "\" \"", "123", "true", "[]", "[{}]",
                    "\"hello\"", "\"{\\\"editorSchemaRevision\\\":14}\"", "\"{\\\"advancedPortfolioVersion\\\":2}\"")) {
                String raw = "{\"config\":" + value + "}";
                Object previous;
                try { previous = JSON.parseObject(raw, legacy); }
                catch (RuntimeException exception) {
                    assertThatThrownBy(() -> JSON.parseObject(raw, type)).as(type.getSimpleName() + " / " + value)
                            .isInstanceOf(RuntimeException.class);
                    continue;
                }
                assertThat(configJson(JSON.parseObject(raw, type))).isEqualTo(configJson(previous));
            }
        }
    }

    /** 历史读取仍直接解析普通配置 DTO，不能把请求边界扩大到数据库配置。 */
    @Test void ordinaryConfigDtosStillReadAdvancedSnapshotsWithoutVersionProperties() {
        String raw = "{\"advancedPortfolioVersion\":2,\"editorSchemaRevision\":15,\"components\":[]}";
        assertThat(JSON.toJSONString(JSON.parseObject(raw, PortfolioConfigDto.class))).doesNotContain("advancedPortfolioVersion");
        assertThat(JSON.toJSONString(JSON.parseObject(raw, TeamPortfolioConfigDto.class))).doesNotContain("advancedPortfolioVersion");
    }

    /** 字体根表和嵌套选择的省略、null、空对象在两种 JSON 绑定及生产 HTTP 链路均保留。 */
    @Test void fontThreeStateInputSurvivesFastJsonJacksonAndHttpBinding() throws Exception {
        ObjectMapper jackson = new ObjectMapper();
        for (Class<?> type : REQUEST_TYPES) {
            for (String fonts : List.of("", "\"fonts\":null,", "\"fonts\":{},")) {
                String raw = "{\"config\":{" + fonts + "\"components\":["
                        + "{\"componentKey\":\"text\",\"componentType\":\"TEXT_SECTION\",\"config\":{\"fontId\":null,\"content\":\"正文\"}},"
                        + "{\"componentKey\":\"inherit\",\"componentType\":\"TEXT_SECTION\",\"config\":{\"content\":\"继承\"}},"
                        + "{\"componentKey\":\"remote\",\"componentType\":\"TEXT_SECTION\",\"config\":{\"fontId\":\"ALLURA\"}}]}}";
                List<Object> requests = List.of(JSON.parseObject(raw, type), jackson.readValue(raw, type), readHttp(type, raw));
                for (Object request : requests) {
                    FontInput input = fontInput(request);
                    assertThat(input.provided()).as(type.getSimpleName() + " / " + fonts).isEqualTo(!fonts.isEmpty());
                    if (fonts.contains("{}")) { assertThat(input.fonts()).isEqualTo(Map.of()); }
                    else { assertThat(input.fonts()).isNull(); }
                    assertThat(input.components().get(0)).containsEntry("fontId", null);
                    assertThat(input.components().get(1)).doesNotContainKey("fontId");
                    assertThat(input.components().get(2)).containsEntry("fontId", "ALLURA");
                }
            }
        }
    }

    /** 提取个人与团队请求的字体状态，直接检查 DTO 避免断言序列化再次吞掉 null。 */
    private static FontInput fontInput(Object request) throws Exception {
        Object config = request.getClass().getMethod("getConfig").invoke(request);
        if (config instanceof PortfolioConfigDto personal) {
            return new FontInput(personal.isFontsProvided(), personal.getFonts(),
                    personal.getComponents().stream().map(PortfolioConfigDto.Component::getConfig).toList());
        }
        TeamPortfolioConfigDto team = (TeamPortfolioConfigDto) config;
        return new FontInput(team.isFontsProvided(), team.getFonts(),
                team.getComponents().stream().map(component -> component.getConfig()).toList());
    }

    /** 不经过序列化的三态字段对照。 */
    private record FontInput(boolean provided, Object fonts, List<? extends Map<String, Object>> components) { }

    /** 通过生产 converter 顺序执行真正的 HTTP DTO 绑定。 */
    @SuppressWarnings("unchecked")
    private static Object readHttp(Class<?> type, String raw) throws Exception {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        new FastJsonWebConfig().extendMessageConverters(converters);
        HttpMessageConverter<Object> reader = (HttpMessageConverter<Object>) converters.stream()
                .filter(converter -> converter.canRead(type, MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        MockHttpInputMessage input = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return reader.read((Class<Object>) type, input);
    }

    /** 比较各请求绑定后的配置，不依赖字段顺序或请求的其它可选字段。 */
    private static String configJson(Object request) throws Exception {
        return JSON.toJSONString(request.getClass().getMethod("getConfig").invoke(request));
    }

    /** 模拟修改前直接由 Fastjson 绑定普通个人配置的请求。 */
    public static class LegacyPersonalRequest {
        /** 原有普通个人配置字段。 */
        public PortfolioConfigDto config;
        /** 统一读取对照配置。 */
        public PortfolioConfigDto getConfig() { return config; }
    }

    /** 模拟修改前直接由 Fastjson 绑定普通团队配置的请求。 */
    public static class LegacyTeamRequest {
        /** 原有普通团队配置字段。 */
        public TeamPortfolioConfigDto config;
        /** 统一读取对照配置。 */
        public TeamPortfolioConfigDto getConfig() { return config; }
    }
}
