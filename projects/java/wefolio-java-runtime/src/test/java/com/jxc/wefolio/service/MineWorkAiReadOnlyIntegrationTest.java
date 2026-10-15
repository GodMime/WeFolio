package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.lock.TestDistributedLockExecutor;
import com.jxc.wefolio.config.WorkAuditProperties;
import com.jxc.wefolio.controller.MineWorkController;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import com.jxc.wefolio.service.teamportfolio.LocalPortfolioReferenceMutex;
import org.apache.ibatis.session.SqlSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 作品 AI 只读集成测试：真实 HTTP 绑定、服务白名单和 MyBatis SQL 均不得改写三类结果。 */
class MineWorkAiReadOnlyIntegrationTest {

    /** 预存的摘要、转写和关键帧，比较原始字节而非只检查响应。 */
    private static final String SUMMARY = "{\"summary\":\"原始摘要\"}";
    /** 预存音频文字。 */
    private static final String AUDIO = "原始音频文字🎤";
    /** 预存关键帧清单。 */
    private static final String FRAMES = "{\"frames\":[{\"objectKey\":\"原始帧\"}]}";

    /** 独立内存数据库，不读取线上配置。 */
    private JdbcTemplate jdbc;
    /** 使用真实映射器执行通用更新。 */
    private SqlSession session;
    /** 作品映射器。 */
    private WorkEntityMapper mapper;
    /** 真实 Controller 与 Service 的 HTTP 测试入口。 */
    private MockMvc mvc;

    /** 创建实体映射对应的测试表，并仅模拟与本次保存无关的外部依赖。 */
    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:work_ai_readonly_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WorkEntityMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptor);
        session = factory.getObject().openSession(true);
        mapper = session.getMapper(WorkEntityMapper.class);
        StringBuilder ddl = new StringBuilder("CREATE TABLE wf_work (id BIGINT PRIMARY KEY");
        TableInfoHelper.getTableInfo(WorkEntity.class).getFieldList().forEach(field -> ddl
                .append(',').append(field.getColumn()).append(' ').append(sqlType(field.getPropertyType())));
        jdbc.execute(ddl.append(')').toString());
        jdbc.update("""
                INSERT INTO wf_work (id,user_id,media_type,title,media_object_key,
                  video_summary_json,video_audio_text,video_frames_json,deleted,version)
                VALUES (18,7,?,'原标题','WFTEST/work/video/video.mp4',?,?,?,0,0)
                """, MediaTypeDict.VIDEO.getCode(), SUMMARY, AUDIO, FRAMES);

        WorkUploadTaskEntityMapper uploads = mock(WorkUploadTaskEntityMapper.class);
        MineWorkService service = new MineWorkService(
                mock(UserEntityMapper.class), mapper, mock(WfTagEntityMapper.class),
                mock(WorkTagEntityMapper.class), uploads, mock(PortfolioReferenceEntityMapper.class),
                mock(CosService.class), mock(AnimationCosService.class), mock(WorkUploadTransactionService.class),
                mock(ContentLimitService.class), new MineWorkAuditService(mapper, new WorkAuditProperties(),
                        new WorkAuditUserReasonResolver(), new WorkManualAuditNoGenerator()),
                new WorkUploadTaskExpirationService(uploads), new TestDistributedLockExecutor(),
                new LocalPortfolioReferenceMutex());
        mvc = MockMvcBuilders.standaloneSetup(
                        new MineWorkController(service, mock(MineWorkAuditApplicationService.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        AuthContextHolder.set(new AuthContext(7L, "test-readonly-token"));
    }

    /** 清理当前身份和独立测试数据库。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    /** 手工夹带底层 AI 字段或展示 DTO 字段，包括清空请求，均只能修改原有可编辑字段。 */
    @ParameterizedTest
    @ValueSource(strings = {"\"篡改内容\"", "null", "\"\""})
    void craftedUpdateCannotOverwriteOrClearStoredAiContent(String injectedValue) throws Exception {
        String body = """
                {"title":"新标题","description":"新说明",
                 "videoSummaryJson":%1$s,"videoAudioText":%1$s,"videoFramesJson":%1$s,
                 "summary":%1$s,"audioText":%1$s,"frames":[],"hasContent":false}
                """.formatted(injectedValue);
        mvc.perform(put("/api/mine/works/18").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.work.title").value("新标题"))
                .andExpect(jsonPath("$.data.work.description").value("新说明"));

        assertThat(jdbc.queryForObject("SELECT title FROM wf_work WHERE id=18", String.class)).isEqualTo("新标题");
        assertStoredAiUnchanged();
    }

    /** 即使普通更新实体被误赋 AI 值，真实 SQL 的禁止更新约束仍保护后台生成结果。 */
    @Test
    void genericEntityUpdateCannotOverwriteAnyAiField() {
        WorkEntity work = mapper.selectById(18L);
        work.setTitle("允许编辑的标题");
        work.setVideoSummaryJson("篡改摘要");
        work.setVideoAudioText("篡改文字");
        work.setVideoFramesJson("篡改帧清单");
        assertThat(mapper.updateById(work)).isEqualTo(1);
        assertStoredAiUnchanged();
    }

    /** 直接读取数据库核对三个字段，不依赖可能隐藏 AI 字段的详情响应。 */
    private void assertStoredAiUnchanged() {
        assertThat(jdbc.queryForObject("SELECT video_summary_json FROM wf_work WHERE id=18", String.class))
                .isEqualTo(SUMMARY);
        assertThat(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=18", String.class))
                .isEqualTo(AUDIO);
        assertThat(jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=18", String.class))
                .isEqualTo(FRAMES);
    }

    /** 为本次实际更新路径提供与实体兼容的最小 H2 字段类型。 */
    private String sqlType(Class<?> type) {
        if (type == Long.class || type == Integer.class) return "BIGINT";
        if (type == LocalDateTime.class) return "TIMESTAMP";
        if (type == LocalDate.class) return "DATE";
        return "VARCHAR(4096)";
    }
}
