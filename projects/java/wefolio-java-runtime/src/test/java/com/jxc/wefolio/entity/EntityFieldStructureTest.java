package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.mapper.TeamScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** 验证意见反馈实体与上传任务实体严格映射新增表字段和注解。 */
    @Test
    void feedbackEntitiesShouldDeclareApprovedFieldsAndDictionaryReferences() throws IOException {
        Path feedbackPath = Path.of("src/main/java/com/jxc/wefolio/entity/FeedbackEntity.java");
        Path uploadTaskPath = Path.of("src/main/java/com/jxc/wefolio/entity/FeedbackUploadTaskEntity.java");

        assertThat(feedbackPath).exists();
        assertThat(uploadTaskPath).exists();

        String feedbackSource = Files.readString(feedbackPath);
        String uploadTaskSource = Files.readString(uploadTaskPath);

        assertThat(FeedbackEntity.class.getSuperclass()).isEqualTo(BaseEntity.class);
        assertThat(FeedbackUploadTaskEntity.class.getSuperclass()).isEqualTo(BaseEntity.class);
        assertThat(FeedbackEntity.class.getAnnotation(TableName.class).value()).isEqualTo("wf_feedback");
        assertThat(FeedbackUploadTaskEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("wf_feedback_upload_task");
        assertDeclaredFieldsExactly(FeedbackEntity.class, Map.ofEntries(
                Map.entry("feedbackNo", String.class),
                Map.entry("userId", Long.class),
                Map.entry("status", String.class),
                Map.entry("feedbackResult", String.class),
                Map.entry("feedbackResultAt", LocalDateTime.class),
                Map.entry("roundsJson", String.class),
                Map.entry("roundCount", Integer.class),
                Map.entry("attachmentCount", Integer.class),
                Map.entry("createIdempotencyKey", String.class)
        ));
        assertDeclaredFieldsExactly(FeedbackUploadTaskEntity.class, Map.ofEntries(
                Map.entry("userId", Long.class),
                Map.entry("clientId", String.class),
                Map.entry("objectKey", String.class),
                Map.entry("mediaType", String.class),
                Map.entry("mimeType", String.class),
                Map.entry("fileSize", Long.class),
                Map.entry("durationMs", Long.class),
                Map.entry("status", String.class),
                Map.entry("expiresAt", LocalDateTime.class),
                Map.entry("feedbackId", Long.class),
                Map.entry("roundNo", Integer.class)
        ));

        assertSourceDataAnnotationCount(feedbackSource, 1);
        assertSourceDataAnnotationCount(uploadTaskSource, 1);
        assertClassAndFieldsHaveChineseJavadoc(
                FeedbackEntity.class, feedbackSource, "public class FeedbackEntity");
        assertClassAndFieldsHaveChineseJavadoc(
                FeedbackUploadTaskEntity.class, uploadTaskSource, "public class FeedbackUploadTaskEntity");
        assertThat(feedbackSource).contains("@see FeedbackStatusDict");
        assertThat(uploadTaskSource)
                .contains("@see FeedbackMediaTypeDict")
                .contains("@see FeedbackUploadTaskStatusDict");
    }

    /** 验证意见反馈 Mapper 提供用户隔离查询、锁行查询及状态集合计数能力。 */
    @Test
    void feedbackMappersShouldDeclareRequiredQueries() throws IOException {
        Path feedbackMapperPath = Path.of(
                "src/main/java/com/jxc/wefolio/mapper/FeedbackEntityMapper.java");
        Path uploadTaskMapperPath = Path.of(
                "src/main/java/com/jxc/wefolio/mapper/FeedbackUploadTaskEntityMapper.java");

        assertThat(feedbackMapperPath).exists();
        assertThat(uploadTaskMapperPath).exists();

        String feedbackMapperSource = Files.readString(feedbackMapperPath);
        String uploadTaskMapperSource = Files.readString(uploadTaskMapperPath);

        assertThat(feedbackMapperSource)
                .contains("extends BaseMapper<FeedbackEntity>")
                .contains("lockByIdAndUserId")
                .contains("lockByFeedbackNo")
                .contains("countActiveByUserId")
                .contains("selectByIdAndUserId")
                .contains("FOR UPDATE")
                .contains("<foreach")
                .doesNotContain("'PROCESSING'")
                .doesNotContain("'WAITING_FOLLOW_UP'");
        assertThat(uploadTaskMapperSource)
                .contains("extends BaseMapper<FeedbackUploadTaskEntity>")
                .contains("lockByIdAndUserId")
                .contains("FOR UPDATE");
    }

    /** 验证反馈轮次快照模型精确承载轮次、处理结果与附件元数据。 */
    @Test
    void feedbackRoundSnapshotShouldDeclareExactRoundAndAttachmentStructure() throws Exception {
        Path sourcePath = Path.of("src/main/java/com/jxc/wefolio/model/FeedbackRoundSnapshot.java");

        assertThat(sourcePath).exists();

        String source = Files.readString(sourcePath);
        assertDeclaredFieldsExactly(FeedbackRoundSnapshot.class, Map.ofEntries(
                Map.entry("roundNo", Integer.class),
                Map.entry("idempotencyKey", String.class),
                Map.entry("description", String.class),
                Map.entry("submittedAt", LocalDateTime.class),
                Map.entry("teamResult", String.class),
                Map.entry("teamResultAt", LocalDateTime.class),
                Map.entry("attachments", List.class)
        ));
        assertDeclaredFieldsExactly(FeedbackRoundSnapshot.Attachment.class, Map.ofEntries(
                Map.entry("objectKey", String.class),
                Map.entry("mediaType", String.class),
                Map.entry("mimeType", String.class),
                Map.entry("size", long.class),
                Map.entry("durationMs", long.class)
        ));

        assertThat(FeedbackRoundSnapshot.Attachment.class.getEnclosingClass())
                .isEqualTo(FeedbackRoundSnapshot.class);
        assertThat(Modifier.isStatic(FeedbackRoundSnapshot.Attachment.class.getModifiers())).isTrue();

        Type attachmentsType = FeedbackRoundSnapshot.class.getDeclaredField("attachments").getGenericType();
        assertThat(attachmentsType).isInstanceOf(ParameterizedType.class);
        ParameterizedType parameterizedAttachments = (ParameterizedType) attachmentsType;
        assertThat(parameterizedAttachments.getRawType()).isEqualTo(List.class);
        assertThat(parameterizedAttachments.getActualTypeArguments())
                .containsExactly(FeedbackRoundSnapshot.Attachment.class);

        assertSourceDataAnnotationCount(source, 2);
        assertClassAndFieldsHaveChineseJavadoc(
                FeedbackRoundSnapshot.class, source, "public class FeedbackRoundSnapshot");
        assertClassAndFieldsHaveChineseJavadoc(
                FeedbackRoundSnapshot.Attachment.class, source, "public static class Attachment");
        assertThat(source).contains("@see FeedbackMediaTypeDict");
    }

    /**
     * 反射验证类只声明指定名称和类型的字段。
     *
     * @param type 待验证类型
     * @param expectedFields 预期字段名称和类型
     */
    private void assertDeclaredFieldsExactly(Class<?> type, Map<String, Class<?>> expectedFields) {
        Map<String, Class<?>> actualFields = Arrays.stream(type.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType));

        assertThat(actualFields).containsExactlyInAnyOrderEntriesOf(expectedFields);
    }

    /**
     * 验证源码中的 Lombok Data 注解数量。
     *
     * @param source Java 源码
     * @param expectedCount 预期注解数量
     */
    private void assertSourceDataAnnotationCount(String source, long expectedCount) {
        long actualCount = Pattern.compile("(?m)^\\s*@Data\\s*$")
                .matcher(source)
                .results()
                .count();

        assertThat(actualCount).isEqualTo(expectedCount);
    }

    /**
     * 验证类及全部声明字段前紧邻中文 Javadoc。
     *
     * @param type 待验证类型
     * @param source Java 源码
     * @param classDeclaration 类声明文本
     */
    private void assertClassAndFieldsHaveChineseJavadoc(
            Class<?> type,
            String source,
            String classDeclaration
    ) {
        assertChineseJavadocImmediatelyBefore(source, source.indexOf(classDeclaration), type.getSimpleName());

        for (Field field : type.getDeclaredFields()) {
            Matcher declarationMatcher = Pattern.compile(
                            "(?m)^\\s*private\\s+[^;=]+\\s+" + Pattern.quote(field.getName()) + "\\s*;")
                    .matcher(source);
            assertThat(declarationMatcher.find())
                    .as("字段 %s.%s 应存在源码声明", type.getSimpleName(), field.getName())
                    .isTrue();
            assertChineseJavadocImmediatelyBefore(
                    source,
                    declarationMatcher.start(),
                    type.getSimpleName() + "." + field.getName()
            );
        }
    }

    /**
     * 验证声明前最近的注释块是中文 Javadoc，且其后没有其它字段声明。
     *
     * @param source Java 源码
     * @param declarationIndex 声明起始位置
     * @param subject 验证对象名称
     */
    private void assertChineseJavadocImmediatelyBefore(String source, int declarationIndex, String subject) {
        assertThat(declarationIndex).as("%s 应存在源码声明", subject).isGreaterThanOrEqualTo(0);

        int javadocStart = source.lastIndexOf("/**", declarationIndex);
        int javadocEnd = source.indexOf("*/", javadocStart);
        assertThat(javadocStart).as("%s 应声明 Javadoc", subject).isGreaterThanOrEqualTo(0);
        assertThat(javadocEnd).as("%s 的 Javadoc 应完整结束", subject).isBetween(javadocStart, declarationIndex);

        String javadoc = source.substring(javadocStart, javadocEnd + 2);
        String gap = source.substring(javadocEnd + 2, declarationIndex);
        assertThat(Pattern.compile("[\\p{IsHan}]").matcher(javadoc).find())
                .as("%s 的 Javadoc 应包含中文说明", subject)
                .isTrue();
        assertThat(gap).as("%s 的 Javadoc 后不应夹有其它字段声明", subject).doesNotContain(";");
    }
}
