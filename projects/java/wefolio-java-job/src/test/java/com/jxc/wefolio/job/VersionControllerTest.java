package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统接口测试 — 验证健康检查和版本信息接口稳定可用。
 */
@SpringBootTest(classes = WefolioJavaJobApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionControllerTest {

    /** Web 接口测试客户端 */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 健康检查接口应返回 UP 状态和当前时间。
     *
     * @throws Exception MockMvc 调用失败时抛出
     */
    @Test
    void healthReturnsUpStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("wefolio-java-job"))
                .andExpect(jsonPath("$.data.timestamp").value(not(blankOrNullString())));
    }

    /**
     * 版本接口应返回构建版本和构建时间字段。
     *
     * @throws Exception MockMvc 调用失败时抛出
     */
    @Test
    void versionReturnsBuildInformation() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.version").value(not(blankOrNullString())))
                .andExpect(jsonPath("$.data.buildTime").value(not(blankOrNullString())))
                .andExpect(jsonPath("$.data.service").value("wefolio-java-job"));
    }
}
