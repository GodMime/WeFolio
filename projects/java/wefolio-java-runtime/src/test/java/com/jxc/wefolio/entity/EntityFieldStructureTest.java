package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
    void workEntityShouldDeclareAuditStatusAndRejectReason() {
        assertThat(WorkEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("auditStatus", "auditRejectReason");
    }

    @Test
    void workEntityShouldDeclareAspectRatio() {
        assertThat(WorkEntity.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("aspectRatio");
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
