package com.jxc.wefolio.mapper;

import com.jxc.wefolio.entity.ScheduleEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队查档批量 Mapper 契约测试 — 防止批量查询退化为按成员逐条查询。
 */
class TeamScheduleBatchMapperContractTest {

    private static final List<Long> USER_IDS = List.of(101L, 202L);
    private static final LocalDate QUERIED_DATE = LocalDate.of(2026, 7, 10);
    private static final List<String> SCHEDULE_SELECTED_COLUMNS = List.of(
            "id",
            "user_id",
            "schedule_date",
            "slot_definition_id",
            "status",
            "slot_name_snapshot",
            "start_time_snapshot",
            "end_time_snapshot",
            "color_snapshot"
    );

    /** 验证档位定义批量查询仅查询生效档位，并使用稳定排序。 */
    @Test
    void slotDefinitionMapperShouldDeclareSingleActiveUserBatchQuery() throws IOException {
        String mapperSource = readMapperSource("SlotDefinitionEntityMapper.java");

        assertThat(mapperSource)
                .contains("selectActiveByUserIds")
                .contains("@Param(\"userIds\") Collection<Long> userIds")
                .contains("SELECT id,user_id,name,start_time,end_time,color,status FROM wf_slot_definition")
                .contains("<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach>")
                .contains("AND status='ACTIVE' AND deleted=0")
                .contains("ORDER BY user_id, start_time, id")
                .doesNotContain("sort_order")
                .doesNotContain("AVAILABLE");
        assertThat(countOccurrences(mapperSource, "<foreach collection='userIds'"))
                .isEqualTo(1);
    }

    /** 验证档期批量查询按目标日期和成员集合过滤未删除数据。 */
    @Test
    void scheduleMapperShouldDeclareSingleDateAndUserBatchQuery() throws IOException {
        String mapperSource = readMapperSource("ScheduleEntityMapper.java");

        assertThat(mapperSource)
                .contains("selectByUserIdsAndDate")
                .contains("@Param(\"userIds\") Collection<Long> userIds")
                .contains("@Param(\"queriedDate\") LocalDate queriedDate")
                .contains("SELECT id,user_id,schedule_date,slot_definition_id,status,slot_name_snapshot,start_time_snapshot,end_time_snapshot,color_snapshot ")
                .contains("FROM wf_schedule")
                .contains("<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach>")
                .contains("AND schedule_date=#{queriedDate} AND deleted=0")
                .contains("ORDER BY user_id, slot_definition_id, id")
                .doesNotContain("sort_order")
                .doesNotContain("AVAILABLE");
        assertThat(countOccurrences(mapperSource, "<foreach collection='userIds'"))
                .isEqualTo(1);
    }

    /** 验证两个注解脚本可由 MyBatis 解析，批量参数可展开并绑定目标日期。 */
    @Test
    void mapperSelectScriptsShouldExpandUserIdsAndBindQueriedDate() throws NoSuchMethodException {
        Map<String, Object> parameters = Map.of(
                "userIds", USER_IDS,
                "queriedDate", QUERIED_DATE
        );

        BoundSql slotDefinitionBoundSql = createSqlSource(
                SlotDefinitionEntityMapper.class.getMethod("selectActiveByUserIds", Collection.class)
        ).getBoundSql(parameters);
        BoundSql scheduleBoundSql = createSqlSource(
                ScheduleEntityMapper.class.getMethod("selectByUserIdsAndDate", Collection.class, LocalDate.class)
        ).getBoundSql(parameters);

        assertThat(normalizeSql(slotDefinitionBoundSql.getSql()))
                .isEqualTo("SELECT id,user_id,name,start_time,end_time,color,status FROM wf_slot_definition "
                        + "WHERE user_id IN ( ? , ? ) AND status='ACTIVE' AND deleted=0 "
                        + "ORDER BY user_id, start_time, id");
        assertThat(slotDefinitionBoundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("__frch_userId_0", "__frch_userId_1");

        assertThat(normalizeSql(scheduleBoundSql.getSql()))
                .isEqualTo("SELECT id,user_id,schedule_date,slot_definition_id,status,slot_name_snapshot,"
                        + "start_time_snapshot,end_time_snapshot,color_snapshot FROM wf_schedule "
                        + "WHERE user_id IN ( ? , ? ) AND schedule_date=? AND deleted=0 "
                        + "ORDER BY user_id, slot_definition_id, id");
        assertThat(scheduleBoundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("__frch_userId_0", "__frch_userId_1", "queriedDate");
        assertThat(scheduleBoundSql.hasAdditionalParameter("__frch_userId_0")).isTrue();
        assertThat(scheduleBoundSql.getAdditionalParameter("__frch_userId_0")).isEqualTo(USER_IDS.getFirst());
        assertThat(scheduleBoundSql.getAdditionalParameter("__frch_userId_1")).isEqualTo(USER_IDS.getLast());
    }

    /** 验证档期查询投影中的每个下划线列都有对应的实体驼峰属性。 */
    @Test
    void scheduleSelectedColumnsShouldMapToScheduleEntityProperties() {
        Set<String> scheduleEntityProperties = declaredAndInheritedFieldNames(ScheduleEntity.class);
        List<String> selectedProperties = SCHEDULE_SELECTED_COLUMNS.stream()
                .map(this::toCamelCase)
                .toList();

        assertThat(selectedProperties).containsExactly(
                "id",
                "userId",
                "scheduleDate",
                "slotDefinitionId",
                "status",
                "slotNameSnapshot",
                "startTimeSnapshot",
                "endTimeSnapshot",
                "colorSnapshot"
        );
        assertThat(scheduleEntityProperties).containsAll(selectedProperties);
    }

    /** 读取指定 Mapper 的源码。 */
    private String readMapperSource(String mapperName) throws IOException {
        return Files.readString(Path.of("src/main/java/com/jxc/wefolio/mapper", mapperName));
    }

    /** 从 Mapper 方法反射取得注解 SQL，并创建 MyBatis 可执行脚本源。 */
    private SqlSource createSqlSource(Method mapperMethod) {
        Select select = mapperMethod.getAnnotation(Select.class);

        assertThat(select).isNotNull();
        assertThat(select.value()).hasSize(1);

        return new XMLLanguageDriver().createSqlSource(new Configuration(), select.value()[0], Map.class);
    }

    /** 归一化 SQL 中由动态标签引入的空白，便于锁定语义结构。 */
    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 收集实体及其父类声明字段，覆盖基础主键和业务属性。 */
    private Set<String> declaredAndInheritedFieldNames(Class<?> entityType) {
        Set<String> fieldNames = new HashSet<>();
        for (Class<?> currentType = entityType; currentType != null; currentType = currentType.getSuperclass()) {
            for (Field field : currentType.getDeclaredFields()) {
                fieldNames.add(field.getName());
            }
        }
        return fieldNames;
    }

    /** 将数据库下划线列名转换为实体使用的驼峰属性名。 */
    private String toCamelCase(String columnName) {
        StringBuilder propertyName = new StringBuilder();
        boolean nextUpperCase = false;
        for (char character : columnName.toCharArray()) {
            if (character == '_') {
                nextUpperCase = true;
            } else {
                propertyName.append(nextUpperCase ? Character.toUpperCase(character) : character);
                nextUpperCase = false;
            }
        }
        return propertyName.toString();
    }

    /** 统计文本出现次数，确保每个查询只声明一个 IN 参数循环。 */
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
