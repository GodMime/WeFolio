package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.FastJsonWebConfig;
import com.jxc.wefolio.controller.PortfolioFontController;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.message.GlobalExceptionMessage;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.PortfolioTimelineMessage;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实个人、团队 HTTP 入口验证能力声明的解析错误和旧请求兼容性。 */
@ExtendWith(MockitoExtension.class)
class PortfolioClientCapabilitiesHttpTest {
    /** 个人和团队字体准备都复用草稿请求 DTO。 */
    private static final List<String> PATHS = List.of(
            "/api/mine/portfolios/11/fonts/prepare", "/api/mine/team-portfolios/11/fonts/prepare");
    /** 字体目录能力模拟。 */
    @Mock private PortfolioFontSources sources;
    /** 个人业务入口模拟。 */
    @Mock private MinePortfolioService personal;
    /** 团队业务入口模拟。 */
    @Mock private MineTeamPortfolioService team;

    /** 模拟已由认证切面确认的维护者。 */
    @BeforeEach void login() { AuthContextHolder.set(new AuthContext(7L, "test-maintainer")); }
    /** 请求结束后清理身份。 */
    @AfterEach void cleanup() { AuthContextHolder.clear(); }

    /** 两种转换器包装 setter 异常后仍返回能力声明的稳定提示，且不进入业务调用。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidTimelineCapabilityKeepsBusinessMessage(boolean jackson) throws Exception {
        MockMvc mvc = mvc(jackson);
        for (String path : PATHS) {
            for (String value : List.of("null", "\"1\"", "true", "1.0", "2", "-1", "2147483648", "{}", "[]")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"clientCapabilities\":{\"portfolioTimeline\":" + value + "}}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.message").value(PortfolioTimelineMessage.INVALID_CAPABILITY));
            }
        }
        verifyNoInteractions(sources, personal, team);
    }

    /** JSON 语法错误和其它字段类型错误仍使用原通用响应，普通配置边界保留各解析器的既有语义。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedBindingErrorsKeepGenericMessage(boolean jackson) throws Exception {
        MockMvc mvc = mvc(jackson);
        for (String path : PATHS) {
            for (String raw : List.of("{\"clientRevision\":]}", "{\"clientRevision\":\"not-an-integer\"}")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(raw))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.message").value(GlobalExceptionMessage.REQUEST_BODY_FORMAT_ERROR_MESSAGE));
            }
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"config\":{\"advancedPortfolioVersion\":2}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(jackson
                            ? GlobalExceptionMessage.REQUEST_BODY_FORMAT_ERROR_MESSAGE
                            : PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE));
        }
        verifyNoInteractions(sources, personal, team);
    }

    /** 旧端省略能力、仅声明字体，以及新端声明零或一，均继续委派原业务入口。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyAndSupportedDeclarationsRemainAccepted(boolean jackson) throws Exception {
        MockMvc mvc = mvc(jackson);
        List<String> requests = List.of("{}", "{\"clientCapabilities\":null}",
                "{\"clientCapabilities\":{\"portfolioRemoteFont\":1}}",
                "{\"clientCapabilities\":{\"portfolioTimeline\":0}}",
                "{\"clientCapabilities\":{\"portfolioTimeline\":1}}");
        for (String path : PATHS) {
            for (String raw : requests) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(raw))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            }
        }
        verify(personal, times(requests.size())).prepareFonts(eq(11L), any(MinePortfolioDraftSaveRequest.class));
        verify(team, times(requests.size())).prepareFonts(eq(11L), any(TeamPortfolioDraftSaveRequest.class), eq(7L));
    }

    /** 显式覆盖 Jackson 和生产 Fastjson 转换器顺序，避免只验证默认测试栈。 */
    private MockMvc mvc(boolean jackson) {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        if (jackson) { converters.add(new MappingJackson2HttpMessageConverter()); }
        else { new FastJsonWebConfig().extendMessageConverters(converters); }
        return MockMvcBuilders.standaloneSetup(new PortfolioFontController(sources, personal, team))
                .setMessageConverters(converters.toArray(HttpMessageConverter<?>[]::new))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
