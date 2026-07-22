package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.mapper.TeamScheduleQueryRecordEntityMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体字段结构约束测试。
 */
class EntityFieldStructureTest {

    @Test
    void baseEntityDeletedShouldUseIdAsLogicDeleteValue() throws NoSuchFieldException {
        Field deletedField = BaseEntity.class.getDeclaredField("deleted");
        TableLogic tableLogic = deletedField.getAnnotation(TableLogic.class);

        assertThat(deletedField.getType()).isEqualTo(Long.class);
        assertThat(tableLogic).isNotNull();
        assertThat(tableLogic.value()).isEqualTo("0");
        assertThat(tableLogic.delval()).isEqualTo("id");
    }

    @Test
    void baseEntityTimeFieldsShouldDeclareAutoFillStrategy() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        TableField createdAtTableField = createdAtField.getAnnotation(TableField.class);
        TableField updatedAtTableField = updatedAtField.getAnnotation(TableField.class);

        assertThat(createdAtTableField).isNotNull();
        assertThat(createdAtTableField.fill()).isEqualTo(FieldFill.INSERT);
        assertThat(updatedAtTableField).isNotNull();
        assertThat(updatedAtTableField.fill()).isEqualTo(FieldFill.INSERT_UPDATE);
    }

    @Test
    void portfolioShareRecordShouldUseBaseCreatedAtOnly() {
        boolean declaresCreatedAt = Arrays.stream(PortfolioShareRecordEntity.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch("createdAt"::equals);

        assertThat(declaresCreatedAt).isFalse();
    }

    @Test
    void portfolioEntityShouldUseExplicitDraftAndPublishedConfigFields() {
        assertThat(PortfolioEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains(
                        "draftConfigJson",
                        "draftRevision",
                        "draftContentHash",
                        "draftSavedBy",
                        "draftSavedAt",
                        "publishedConfigJson",
                        "publishedRevision",
                        "publishedContentHash",
                        "publishedBy",
                        "publishedAt",
                        "publicationStatus"
                )
                .doesNotContain(
                        "title",
                        "intro",
                        "shareCoverUrl",
                        "shareAvatarUrl",
                        "schemaJson"
                );
    }

    @Test
    void workEntityShouldDeclareAuditStatusAndReasons() {
        assertThat(WorkEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("auditStatus", "auditReasonCode", "auditReasonCodes", "auditRejectReason");
    }

    @Test
    void workEntityShouldDeclareAspectRatio() {
        assertThat(WorkEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("aspectRatio");
    }

    @Test
    void userAuthEntityShouldDeclareOpenIdForOwnerSelfVisitDetection() {
        assertThat(UserAuthEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("openId");
    }

    @Test
    void portfolioReferenceEntityShouldDeclareConfigScope() {
        assertThat(PortfolioReferenceEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("configScope");
    }

    @Test
    void scheduleQueryRecordShouldOnlyDeclareApprovedBusinessFields() {
        assertThat(ScheduleQueryRecordEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains(
                        "portfolioId",
                        "portfolioType",
                        "portfolioTitleSnapshot",
                        "visitRecordId",
                        "visitorId",
                        "visitorKey",
                        "ownerType",
                        "ownerId",
                        "sourceType",
                        "displayMode",
                        "queriedDate",
                        "slotDefinitionId",
                        "slotNameSnapshot",
                        "startTimeSnapshot",
                        "endTimeSnapshot",
                        "colorSnapshot",
                        "resultStatus",
                        "resultStatusText",
                        "available",
                        "resultMessage",
                        "queriedAt"
                )
                .doesNotContain(
                        "portfolioShareCodeSnapshot",
                        "portfolioRevision",
                        "triggerType",
                        "idempotencyKey",
                        "componentKey"
                );
    }

    @Test
    void scheduleQueryRecordEnumFieldsShouldDeclareDictionaryReferences() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/entity/ScheduleQueryRecordEntity.java"));

        assertThat(source)
                .contains("@see PortfolioTypeDict")
                .contains("@see PortfolioOwnerTypeDict")
                .contains("@see VisitSourceTypeDict")
                .contains("展示方式：MODAL_CALENDAR 弹层月历 / INLINE_CALENDAR 内联月历")
                .contains("@see ScheduleStatusDict");
    }

    /** 验证团队查档记录实体覆盖独立表的全部业务字段。 */
    @Test
    void teamScheduleQueryRecordShouldDeclareApprovedBusinessFields() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/jxc/wefolio/entity/TeamScheduleQueryRecordEntity.java");

        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);

        assertThat(source)
                .contains("@TableName(\"wf_team_schedule_query_record\")")
                .contains("private Long portfolioId;")
                .contains("private Integer portfolioRevision;")
                .contains("private String portfolioTitleSnapshot;")
                .contains("private Long teamId;")
                .contains("private Long visitRecordId;")
                .contains("private Long visitorId;")
                .contains("private String visitorKey;")
                .contains("private String sourceType;")
                .contains("private String displayMode;")
                .contains("private LocalDate queriedDate;")
                .contains("private String resultStatus;")
                .contains("private String resultStatusText;")
                .contains("private Integer available;")
                .contains("private String resultMessage;")
                .contains("private String teamResultJson;")
                .contains("private Integer availableMemberCount;")
                .contains("private Integer partialAvailableMemberCount;")
                .contains("private Integer fullMemberCount;")
                .contains("private LocalDateTime queriedAt;");
    }

    /** 验证团队查档记录的枚举字段指向对应字典。 */
    @Test
    void teamScheduleQueryRecordEnumFieldsShouldDeclareDictionaryReferences() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/jxc/wefolio/entity/TeamScheduleQueryRecordEntity.java"));

        assertThat(source)
                .contains("import com.jxc.wefolio.dict.VisitSourceTypeDict;")
                .contains("import com.jxc.wefolio.dict.TeamScheduleResultStatusDict;")
                .contains("@see VisitSourceTypeDict")
                .contains("@see TeamScheduleResultStatusDict");
    }

    /** 验证团队查档记录实体继承基类，且声明字段和 Java 类型严格匹配表结构。 */
    @Test
    void teamScheduleQueryRecordShouldDeclareExactlySpecifiedFieldsWithCorrectTypes() {
        Map<String, Class<?>> expectedFieldTypes = Map.ofEntries(
                Map.entry("portfolioId", Long.class),
                Map.entry("portfolioRevision", Integer.class),
                Map.entry("portfolioTitleSnapshot", String.class),
                Map.entry("teamId", Long.class),
                Map.entry("visitRecordId", Long.class),
                Map.entry("visitorId", Long.class),
                Map.entry("visitorKey", String.class),
                Map.entry("sourceType", String.class),
                Map.entry("displayMode", String.class),
                Map.entry("queriedDate", LocalDate.class),
                Map.entry("resultStatus", String.class),
                Map.entry("resultStatusText", String.class),
                Map.entry("available", Integer.class),
                Map.entry("resultMessage", String.class),
                Map.entry("teamResultJson", String.class),
                Map.entry("availableMemberCount", Integer.class),
                Map.entry("partialAvailableMemberCount", Integer.class),
                Map.entry("fullMemberCount", Integer.class),
                Map.entry("queriedAt", LocalDateTime.class)
        );
        Map<String, Class<?>> actualFieldTypes = Arrays.stream(TeamScheduleQueryRecordEntity.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType));

        assertThat(TeamScheduleQueryRecordEntity.class.getSuperclass()).isEqualTo(BaseEntity.class);
        assertThat(actualFieldTypes).containsExactlyInAnyOrderEntriesOf(expectedFieldTypes);
    }

    /** 验证团队查档记录实体源码保留中文 Javadoc。 */
    @Test
    void teamScheduleQueryRecordSourceShouldContainChineseJavadoc() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/jxc/wefolio/entity/TeamScheduleQueryRecordEntity.java");

        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);

        assertThat(source)
                .contains("/**\n * wf_team_schedule_query_record — 团队作品集访客查档记录表。\n */")
                .contains("/** 来源团队作品集 ID */")
                .contains("/** 查询成功时间 */");
    }

    /** 验证团队查档记录 Mapper 仅提供基础持久化能力。 */
    @Test
    void teamScheduleQueryRecordMapperShouldExtendBaseMapper() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/jxc/wefolio/mapper/TeamScheduleQueryRecordEntityMapper.java");

        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);

        assertThat(source)
                .contains("extends BaseMapper<TeamScheduleQueryRecordEntity>")
                .doesNotContain("selectActiveByUserIds")
                .doesNotContain("selectByUserIdsAndDate");

        Type[] genericInterfaces = TeamScheduleQueryRecordEntityMapper.class.getGenericInterfaces();

        assertThat(TeamScheduleQueryRecordEntityMapper.class.getDeclaredMethods()).isEmpty();
        assertThat(genericInterfaces).hasSize(1);
        assertThat(genericInterfaces[0]).isInstanceOf(ParameterizedType.class);

        ParameterizedType baseMapperType = (ParameterizedType) genericInterfaces[0];
        assertThat(baseMapperType.getRawType()).isEqualTo(BaseMapper.class);
        assertThat(baseMapperType.getActualTypeArguments()).containsExactly(TeamScheduleQueryRecordEntity.class);
    }

    @Test
    void workAuditStatusDictionaryShouldDeclareApprovedValues() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/jxc/wefolio/dict/WorkAuditStatusDict.java");

        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);

        assertThat(source)
                .contains("PENDING(\"PENDING\", \"未审核\")")
                .contains("AUDITING(\"AUDITING\", \"审核中\")")
                .contains("PASSED(\"PASSED\", \"审核通过\")")
                .contains("REJECTED(\"REJECTED\", \"确认违规\")")
                .contains("REVIEW_REQUIRED(\"REVIEW_REQUIRED\", \"疑似违规\")")
                .contains("FAILED(\"FAILED\", \"审核失败\")")
                .contains("fromCode(String code)");
    }

    @Test
    void systemMessageEqualityShouldIncludeBaseEntityFields() {
        SystemMessageEntity first = new SystemMessageEntity();
        first.setId(1L);
        SystemMessageEntity second = new SystemMessageEntity();
        second.setId(2L);

        assertThat(first).isNotEqualTo(second);
    }
}
