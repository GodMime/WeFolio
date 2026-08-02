package com.jxc.wefolio.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护端统一查档 Mapper 契约测试。
 */
class MineScheduleQueryRecordMapperContractTest {

    private static final Long USER_ID = 7L;
    private static final List<Long> TEAM_IDS = List.of(201L, 202L);

    /** 两个分表必须先按各自索引截取候选集，再做稳定的全局分页。 */
    @Test
    void visibleRecordsShouldUnionPersonalAndTeamCandidatesBeforeStablePagination() throws NoSuchMethodException {
        Map<String, Object> parameters = visibleRecordParameters(TEAM_IDS);

        BoundSql boundSql = createSqlSource(visibleRecordsMethod()).getBoundSql(parameters);
        String sql = normalizeSql(boundSql.getSql());

        assertThat(sql)
                .contains("SELECT id AS source_record_id,'PERSONAL' AS record_type")
                .contains("FROM wf_schedule_query_record WHERE owner_type='USER' AND owner_id=? "
                        + "AND portfolio_type='PERSONAL' AND deleted=0 ORDER BY queried_at DESC,id DESC LIMIT ?")
                .contains("UNION ALL")
                .contains("SELECT id AS source_record_id,'TEAM' AS record_type")
                .contains("FROM wf_team_schedule_query_record WHERE team_id IN ( ? , ? ) AND deleted=0 "
                        + "ORDER BY queried_at DESC,id DESC LIMIT ?")
                .contains("ORDER BY queried_at DESC,record_type ASC,source_record_id DESC LIMIT ?,?")
                .doesNotContain("IN ()");
        assertThat(countOccurrences(sql, "id AS source_record_id")).isEqualTo(2);
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly(
                        "userId",
                        "candidateLimit",
                        "__frch_teamId_0",
                        "__frch_teamId_1",
                        "candidateLimit",
                        "offset",
                        "resultLimit");
        assertThat(boundSql.getAdditionalParameter("__frch_teamId_0")).isEqualTo(201L);
        assertThat(boundSql.getAdditionalParameter("__frch_teamId_1")).isEqualTo(202L);
    }

    /** 没有可管理团队时必须直接查询个人表，并沿用相同的分页行数参数。 */
    @Test
    void visibleRecordsShouldUseDirectPersonalQueryWhenTeamIdsAreEmpty() throws NoSuchMethodException {
        Map<String, Object> parameters = visibleRecordParameters(List.of());

        BoundSql boundSql = createSqlSource(visibleRecordsMethod()).getBoundSql(parameters);
        String sql = normalizeSql(boundSql.getSql());

        assertThat(sql)
                .contains("SELECT id AS source_record_id,'PERSONAL' AS record_type")
                .contains("FROM wf_schedule_query_record WHERE owner_type='USER' AND owner_id=? "
                        + "AND portfolio_type='PERSONAL' AND deleted=0")
                .contains("ORDER BY queried_at DESC,id DESC LIMIT ?,?")
                .doesNotContain("wf_team_schedule_query_record")
                .doesNotContain("UNION ALL")
                .doesNotContain("IN ()");
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("userId", "offset", "resultLimit");
    }

    /** 统计必须复用个人归属条件，并仅追加可管理团队范围。 */
    @Test
    void visibleRecordCountShouldUseTheSamePersonalAndTeamScope() throws NoSuchMethodException {
        Method method = ScheduleQueryRecordEntityMapper.class.getMethod(
                "countVisibleRecords", Long.class, Collection.class);

        BoundSql withTeams = createSqlSource(method).getBoundSql(Map.of(
                "userId", USER_ID,
                "teamIds", TEAM_IDS));
        BoundSql withoutTeams = createSqlSource(method).getBoundSql(Map.of(
                "userId", USER_ID,
                "teamIds", List.of()));

        assertThat(normalizeSql(withTeams.getSql()))
                .contains("FROM wf_schedule_query_record WHERE owner_type='USER' AND owner_id=? "
                        + "AND portfolio_type='PERSONAL' AND deleted=0")
                .contains("FROM wf_team_schedule_query_record WHERE team_id IN ( ? , ? ) AND deleted=0")
                .doesNotContain("ORDER BY");
        assertThat(withTeams.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("userId", "__frch_teamId_0", "__frch_teamId_1");

        assertThat(normalizeSql(withoutTeams.getSql()))
                .contains("FROM wf_schedule_query_record")
                .doesNotContain("wf_team_schedule_query_record")
                .doesNotContain("IN ()")
                .doesNotContain("ORDER BY");
        assertThat(withoutTeams.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("userId");
    }

    /** 构造列表查询参数，明确候选上限与最终返回上限是两个不同概念。 */
    private Map<String, Object> visibleRecordParameters(List<Long> teamIds) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userId", USER_ID);
        parameters.put("teamIds", teamIds);
        parameters.put("candidateLimit", 41L);
        parameters.put("offset", 20L);
        parameters.put("resultLimit", 21);
        return parameters;
    }

    /** 取得列表查询方法。 */
    private Method visibleRecordsMethod() throws NoSuchMethodException {
        return ScheduleQueryRecordEntityMapper.class.getMethod(
                "selectVisibleRecords", Long.class, Collection.class, long.class, long.class, int.class);
    }

    /** 从 Mapper 方法反射取得注解 SQL，并创建 MyBatis 可执行脚本源。 */
    private SqlSource createSqlSource(Method mapperMethod) {
        Select select = mapperMethod.getAnnotation(Select.class);

        assertThat(select).as("Mapper 方法必须声明动态查询 SQL").isNotNull();
        assertThat(select.value()).hasSize(1);

        return new XMLLanguageDriver().createSqlSource(new Configuration(), select.value()[0], Map.class);
    }

    /** 归一化动态 SQL 空白，避免格式差异影响语义断言。 */
    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 统计目标片段出现次数。 */
    private int countOccurrences(String source, String target) {
        int occurrences = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) >= 0) {
            occurrences++;
            index += target.length();
        }
        return occurrences;
    }
}
