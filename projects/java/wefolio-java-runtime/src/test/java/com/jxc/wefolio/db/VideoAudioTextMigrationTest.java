package com.jxc.wefolio.db;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 视频音频文本单列迁移、三态语义与通用作品读写隔离的数据库测试。 */
class VideoAudioTextMigrationTest {
    /** 本次唯一新增迁移，不修改历史迁移。 */
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V64__add_work_video_audio_text.sql");

    /** 增量与全量列定义保持一致，且没有额外状态列、表或数据清理语句。 */
    @Test
    void addsExactlyOneNullableColumnAndMatchesFullSchema() throws Exception {
        String sql = Files.readString(MIGRATION);
        String schema = Files.readString(Path.of("full-schema.sql"));
        String work = schema.substring(schema.indexOf("CREATE TABLE `wf_work` ("));
        work = work.substring(0, work.indexOf(") ENGINE="));
        var column = Pattern.compile("ADD COLUMN\\s+(.+?)\\s+AFTER", Pattern.DOTALL).matcher(sql);
        assertThat(column.find()).isTrue();
        String definition = work.lines().map(String::strip)
                .filter(line -> line.startsWith("`video_audio_text` ")).findFirst().orElseThrow();
        assertThat(normalize(definition.substring(0, definition.length() - 1))).isEqualTo(normalize(column.group(1)));
        assertThat(sql.split("ADD COLUMN", -1)).hasSize(2);
        assertThat(sql).contains("CHECK (`video_audio_text` IS NULL OR `media_type` = 'VIDEO')");
        assertThat(work).contains("CONSTRAINT `chk_work_video_audio_text_media` CHECK ((`video_audio_text` is null or `media_type` = _ascii'VIDEO'))");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(CREATE|DELETE|UPDATE|DROP)\\s+");
    }

    /** 旧记录保持 NULL，无音频的空字符串可保存，图片不能保存包括空串在内的转写。 */
    @Test
    void preservesThreeStatesAndRejectsTextForNonVideo() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("CREATE TABLE wf_work (id BIGINT PRIMARY KEY, media_type VARCHAR(16), duration_ms INT)");
        jdbc.update("INSERT INTO wf_work VALUES (1,'VIDEO',1000),(2,'IMAGE',NULL)");
        String sql = Files.readString(MIGRATION);
        String column = sql.substring(sql.indexOf("ADD COLUMN"), sql.indexOf(",\n    ADD CONSTRAINT"))
                .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", "");
        // H2 将 MySQL 的多个 ALTER 子句拆开执行；列类型、默认值与 CHECK 使用迁移原文。
        jdbc.execute("ALTER TABLE wf_work " + column);
        jdbc.execute("ALTER TABLE wf_work " + sql.substring(sql.indexOf("ADD CONSTRAINT")));
        assertThat(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class)).isNull();
        jdbc.update("UPDATE wf_work SET video_audio_text='' WHERE id=1");
        assertThat(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class)).isEmpty();
        jdbc.update("UPDATE wf_work SET video_audio_text=? WHERE id=1", "主持词全文🎤");
        assertThat(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class)).isEqualTo("主持词全文🎤");
        assertThatThrownBy(() -> jdbc.update("UPDATE wf_work SET video_audio_text='' WHERE id=2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 真正运行 MyBatis 通用查询和更新，确认长文本不被加载或随普通编辑写回。 */
    @Test
    void genericWorkReadAndEditCannotLoadOrOverwriteTranscript() throws Exception {
        JdbcDataSource source = dataSource();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WorkEntityMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptor);
        var sessions = factory.getObject();
        TableInfo info = TableInfoHelper.getTableInfo(WorkEntity.class);
        StringBuilder ddl = new StringBuilder("CREATE TABLE wf_work (id BIGINT PRIMARY KEY");
        info.getFieldList().forEach(field -> ddl.append(',').append(field.getColumn()).append(' ')
                .append(sqlType(field.getPropertyType())));
        new JdbcTemplate(source).execute(ddl.append(')').toString());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO wf_work (id,media_type,title,video_audio_text,video_summary_json,deleted,version) VALUES (1,'VIDEO','原标题','保留的全文','保留摘要',0,0)");
        try (var session = sessions.openSession(true)) {
            WorkEntityMapper mapper = session.getMapper(WorkEntityMapper.class);
            WorkEntity work = mapper.selectById(1L);
            assertThat(work.getVideoAudioText()).isNull();
            assertThat(work.getVideoSummaryJson()).isNull();
            work.setTitle("新标题");
            work.setVideoAudioText("过期的转写");
            work.setVideoSummaryJson("过期的摘要");
            assertThat(mapper.updateById(work)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class)).isEqualTo("保留的全文");
            assertThat(jdbc.queryForObject("SELECT video_summary_json FROM wf_work WHERE id=1", String.class)).isEqualTo("保留摘要");
            assertThat(work.toString()).doesNotContain("videoAudioText", "过期的转写", "videoSummaryJson", "过期的摘要");
        }
    }

    /** 每个测试独享内存数据库，禁止访问生产数据源。 */
    private JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:audio_column_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return source;
    }

    /** 映射实体字段到测试表的基本类型；转写无需依赖完整业务建表。 */
    private String sqlType(Class<?> type) {
        if (type == Long.class || type == Integer.class) return "BIGINT";
        if (type == LocalDateTime.class) return "TIMESTAMP";
        if (type == LocalDate.class) return "DATE";
        return "VARCHAR(4096)";
    }

    /** 统一本次列定义的 SQL 关键字大小写与空白，不改变中文注释。 */
    private String normalize(String definition) {
        return definition.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
