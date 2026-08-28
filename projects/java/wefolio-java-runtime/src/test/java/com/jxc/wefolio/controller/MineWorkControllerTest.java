package com.jxc.wefolio.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineWorkBatchDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkBatchDeleteRequest;
import com.jxc.wefolio.dto.MineWorkBatchDeleteResponse;
import com.jxc.wefolio.dto.MineWorkAuditResubmitResponse;
import com.jxc.wefolio.dto.MineWorkCoverUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkCoverUploadTicketResponse;
import com.jxc.wefolio.dto.MineWorkDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkDetailResponse;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.dto.MineWorkSortItemsResponse;
import com.jxc.wefolio.dto.MineWorkTagResponse;
import com.jxc.wefolio.dto.MineWorkTagUpsertRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketResponse;
import com.jxc.wefolio.dto.MineWorkUpdateRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteResponse;
import com.jxc.wefolio.dto.MineWorkUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkUploadTicketResponse;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.service.MineWorkService;
import com.jxc.wefolio.service.MineWorkAuditApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的作品控制器测试 — 固定作品页面接口路径、访问控制注解和服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MineWorkControllerTest {

    /** 作品列表接口参数类型。 */
    private static final Class<?>[] WORK_LIST_PARAMETER_TYPES = {
            String.class, Long.class, String.class, String.class, int.class, int.class
    };

    /** 我的作品服务模拟 */
    @Mock
    private MineWorkService mineWorkService;

    /** 我的作品审核应用服务模拟 */
    @Mock
    private MineWorkAuditApplicationService mineWorkAuditApplicationService;

    @Test
    void mediaTypeCompatibilityContractShouldBeDeprecated() throws Exception {
        Method worksMethod = MineWorkController.class.getMethod("works", WORK_LIST_PARAMETER_TYPES);
        Deprecated mediaTypeDeprecated = worksMethod.getParameters()[2].getAnnotation(Deprecated.class);

        assertThat(mediaTypeDeprecated).isNotNull();
        assertThat(mediaTypeDeprecated.since()).isEqualTo("2026-08");
        assertThat(mediaTypeDeprecated.forRemoval()).isFalse();
        for (String fieldName : Arrays.asList("imageCount", "videoCount", "animationCount")) {
            Deprecated countDeprecated = MineWorkListResponse.Summary.class
                    .getDeclaredField(fieldName)
                    .getAnnotation(Deprecated.class);
            assertThat(countDeprecated).as(fieldName + " 废弃标记").isNotNull();
            assertThat(countDeprecated.since()).as(fieldName + " 废弃版本").isEqualTo("2026-08");
            assertThat(countDeprecated.forRemoval()).as(fieldName + " 暂不删除").isFalse();
        }
    }

    @Test
    void mediaTypeCompatibilityParameterShouldWriteWarningWhenUsed() {
        MineWorkController controller = new MineWorkController(
                mineWorkService, mineWorkAuditApplicationService);
        Logger logger = (Logger) LoggerFactory.getLogger(MineWorkController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.works("婚礼", 12L, "IMAGE", null, 1, 20);
            controller.works("婚礼", 12L, null, null, 1, 20);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.WARN);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("调用已弃用作品媒体类型筛选参数: mediaType=IMAGE");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void workEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MineWorkController controller = new MineWorkController(
                mineWorkService, mineWorkAuditApplicationService);
        MineWorkListResponse listResponse = new MineWorkListResponse();
        MineWorkTagResponse tagResponse = new MineWorkTagResponse();
        MineWorkDetailResponse detailResponse = new MineWorkDetailResponse();
        MineWorkUploadTicketRequest ticketRequest = new MineWorkUploadTicketRequest();
        MineWorkUploadTicketResponse ticketResponse = new MineWorkUploadTicketResponse();
        MineWorkCoverUploadTicketRequest coverTicketRequest = new MineWorkCoverUploadTicketRequest();
        MineWorkCoverUploadTicketResponse coverTicketResponse = new MineWorkCoverUploadTicketResponse();
        MineWorkThumbnailUploadTicketRequest thumbnailTicketRequest = new MineWorkThumbnailUploadTicketRequest();
        MineWorkThumbnailUploadTicketResponse thumbnailTicketResponse = new MineWorkThumbnailUploadTicketResponse();
        MineWorkUploadCompleteRequest completeRequest = new MineWorkUploadCompleteRequest();
        MineWorkUploadCompleteResponse completeResponse = new MineWorkUploadCompleteResponse();
        MineWorkUpdateRequest updateRequest = new MineWorkUpdateRequest();
        MineWorkSortRequest sortRequest = new MineWorkSortRequest();
        MineWorkSortItemsResponse sortItemsResponse = new MineWorkSortItemsResponse();
        MineWorkDeleteCheckResponse deleteCheckResponse = new MineWorkDeleteCheckResponse();
        MineWorkBatchDeleteRequest batchDeleteRequest = new MineWorkBatchDeleteRequest();
        MineWorkBatchDeleteCheckResponse batchDeleteCheckResponse = new MineWorkBatchDeleteCheckResponse();
        MineWorkBatchDeleteResponse batchDeleteResponse = new MineWorkBatchDeleteResponse();
        MineWorkAuditResubmitResponse auditResubmitResponse = new MineWorkAuditResubmitResponse();
        MineWorkTagUpsertRequest createTagRequest = new MineWorkTagUpsertRequest();
        MineWorkTagUpsertRequest updateTagRequest = new MineWorkTagUpsertRequest();
        MineWorkListResponse.TagItem createdTag = new MineWorkListResponse.TagItem();
        createdTag.setId(31L);
        createdTag.setName("高端婚礼");
        createdTag.setColor("#0f766e");
        MineWorkListResponse.TagItem updatedTag = new MineWorkListResponse.TagItem();
        updatedTag.setId(31L);
        updatedTag.setName("草坪婚礼");
        updatedTag.setColor("#2d5f9a");
        when(mineWorkService.listWorks(
                "草坪",
                12L,
                "IMAGE",
                WorkAuditStatusDict.PASSED.getCode(),
                2,
                10)).thenReturn(listResponse);
        when(mineWorkService.listTags()).thenReturn(tagResponse);
        when(mineWorkService.createTag(createTagRequest)).thenReturn(createdTag);
        when(mineWorkService.updateTag(31L, updateTagRequest)).thenReturn(updatedTag);
        when(mineWorkService.getWorkDetail(99L)).thenReturn(detailResponse);
        when(mineWorkService.createUploadTickets(ticketRequest)).thenReturn(ticketResponse);
        when(mineWorkService.createCoverUploadTicket(99L, coverTicketRequest)).thenReturn(coverTicketResponse);
        when(mineWorkService.createThumbnailUploadTicket(99L, thumbnailTicketRequest)).thenReturn(thumbnailTicketResponse);
        when(mineWorkService.completeUpload(completeRequest)).thenReturn(completeResponse);
        when(mineWorkService.updateWork(99L, updateRequest)).thenReturn(detailResponse);
        when(mineWorkService.listSortItems("TAG", 12L)).thenReturn(sortItemsResponse);
        when(mineWorkService.checkDeleteWork(99L)).thenReturn(deleteCheckResponse);
        when(mineWorkService.checkDeleteWorks(batchDeleteRequest)).thenReturn(batchDeleteCheckResponse);
        when(mineWorkService.deleteWorks(batchDeleteRequest)).thenReturn(batchDeleteResponse);
        when(mineWorkAuditApplicationService.resubmit(99L)).thenReturn(auditResubmitResponse);

        Response<MineWorkListResponse> listed = controller.works(
                "草坪",
                12L,
                "IMAGE",
                WorkAuditStatusDict.PASSED.getCode(),
                2,
                10);
        Response<MineWorkTagResponse> tags = controller.tags();
        Response<MineWorkListResponse.TagItem> created = controller.createTag(createTagRequest);
        Response<MineWorkListResponse.TagItem> tagUpdated = controller.updateTag(31L, updateTagRequest);
        Response<Void> tagDeleted = controller.deleteTag(31L);
        Response<MineWorkDetailResponse> detail = controller.detail(99L);
        Response<MineWorkUploadTicketResponse> ticket = controller.createUploadTickets(ticketRequest);
        Response<MineWorkCoverUploadTicketResponse> coverTicket = controller.createCoverUploadTicket(99L, coverTicketRequest);
        Response<MineWorkThumbnailUploadTicketResponse> thumbnailTicket =
                controller.createThumbnailUploadTicket(99L, thumbnailTicketRequest);
        Response<MineWorkUploadCompleteResponse> completed = controller.completeUpload(completeRequest);
        Response<MineWorkDetailResponse> updated = controller.updateWork(99L, updateRequest);
        Response<MineWorkSortItemsResponse> sortItems = controller.sortItems("TAG", 12L);
        Response<Void> sorted = controller.sortWorks(sortRequest);
        Response<MineWorkDeleteCheckResponse> deleteCheck = controller.checkDeleteWork(99L);
        Response<MineWorkBatchDeleteCheckResponse> batchDeleteCheck = controller.checkDeleteWorks(batchDeleteRequest);
        Response<Void> deleted = controller.deleteWork(99L);
        Response<MineWorkBatchDeleteResponse> batchDeleted = controller.deleteWorks(batchDeleteRequest);
        Response<MineWorkAuditResubmitResponse> auditResubmitted = controller.resubmitAudit(99L);

        assertThat(MineWorkController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("works",
                new Class<?>[] {String.class, Long.class, String.class, String.class, int.class, int.class},
                "/api/mine/works");
        assertGetMapping("tags", new Class<?>[] {}, "/api/mine/works/tags");
        assertPostMapping("createTag",
                new Class<?>[] {MineWorkTagUpsertRequest.class},
                "/api/mine/works/tags");
        assertPutMapping("updateTag",
                new Class<?>[] {Long.class, MineWorkTagUpsertRequest.class},
                "/api/mine/works/tags/{tagId}");
        assertGetMapping("detail", new Class<?>[] {Long.class}, "/api/mine/works/{workId}");
        assertPostMapping("createUploadTickets",
                new Class<?>[] {MineWorkUploadTicketRequest.class},
                "/api/mine/works/upload-tickets");
        assertPostMapping("createCoverUploadTicket",
                new Class<?>[] {Long.class, MineWorkCoverUploadTicketRequest.class},
                "/api/mine/works/{workId}/cover-upload-ticket");
        assertPostMapping("createThumbnailUploadTicket",
                new Class<?>[] {Long.class, MineWorkThumbnailUploadTicketRequest.class},
                "/api/mine/works/{workId}/thumbnail-upload-ticket");
        assertPostMapping("completeUpload",
                new Class<?>[] {MineWorkUploadCompleteRequest.class},
                "/api/mine/works/upload-complete");
        assertPutMapping("updateWork",
                new Class<?>[] {Long.class, MineWorkUpdateRequest.class},
                "/api/mine/works/{workId}");
        assertPostMapping("sortWorks", new Class<?>[] {MineWorkSortRequest.class}, "/api/mine/works/sort");
        assertGetMapping("sortItems", new Class<?>[] {String.class, Long.class}, "/api/mine/works/sort-items");
        assertGetMapping("checkDeleteWork", new Class<?>[] {Long.class}, "/api/mine/works/{workId}/delete-check");
        assertPostMapping("checkDeleteWorks",
                new Class<?>[] {MineWorkBatchDeleteRequest.class},
                "/api/mine/works/delete-check");
        assertPostMapping("deleteWork", new Class<?>[] {Long.class}, "/api/mine/works/delete/{workId}");
        assertPostMapping("deleteWorks",
                new Class<?>[] {MineWorkBatchDeleteRequest.class},
                "/api/mine/works/delete");
        assertPostMapping("deleteTag", new Class<?>[] {Long.class}, "/api/mine/works/tags/delete/{tagId}");
        assertPostMapping("resubmitAudit", new Class<?>[] {Long.class}, "/api/mine/works/{workId}/audit-resubmit");
        assertThat(MineWorkController.class.getMethod("works",
                        String.class, Long.class, String.class, String.class, int.class, int.class)
                .getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(MineWorkController.class.getMethod("detail", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(listed.getData()).isSameAs(listResponse);
        assertThat(tags.getData()).isSameAs(tagResponse);
        assertThat(created.getData()).isSameAs(createdTag);
        assertThat(tagUpdated.getData()).isSameAs(updatedTag);
        assertThat(tagDeleted.isSuccess()).isTrue();
        assertThat(detail.getData()).isSameAs(detailResponse);
        assertThat(ticket.getData()).isSameAs(ticketResponse);
        assertThat(coverTicket.getData()).isSameAs(coverTicketResponse);
        assertThat(thumbnailTicket.getData()).isSameAs(thumbnailTicketResponse);
        assertThat(completed.getData()).isSameAs(completeResponse);
        assertThat(updated.getData()).isSameAs(detailResponse);
        assertThat(sortItems.getData()).isSameAs(sortItemsResponse);
        assertThat(sorted.isSuccess()).isTrue();
        assertThat(deleteCheck.getData()).isSameAs(deleteCheckResponse);
        assertThat(batchDeleteCheck.getData()).isSameAs(batchDeleteCheckResponse);
        assertThat(deleted.isSuccess()).isTrue();
        assertThat(batchDeleted.getData()).isSameAs(batchDeleteResponse);
        assertThat(auditResubmitted.getData()).isSameAs(auditResubmitResponse);
        verify(mineWorkService).listSortItems("TAG", 12L);
        verify(mineWorkService).sortWorks(sortRequest);
        verify(mineWorkService).checkDeleteWorks(batchDeleteRequest);
        verify(mineWorkService).deleteWork(99L);
        verify(mineWorkService).deleteWorks(batchDeleteRequest);
        verify(mineWorkService).deleteTag(31L);
        verify(mineWorkAuditApplicationService).resubmit(99L);
    }

    @Test
    void controllerDoesNotDeclareDeleteMapping() {
        boolean hasDeleteMapping = Arrays.stream(MineWorkController.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DeleteMapping.class));

        assertThat(hasDeleteMapping).isFalse();
    }

    /**
     * 作品编辑请求 JSON 绑定 — 支持同一个编辑接口接收标签 ID 列表。
     */
    @Test
    void updateRequestShouldBindTagIdsFromJson() throws Exception {
        MineWorkUpdateRequest request = new ObjectMapper().readValue("""
                {
                  "title": "主舞台",
                  "description": "现场图",
                  "tagIds": [2, 3]
                }
                """, MineWorkUpdateRequest.class);

        assertThat(request.getTagIds()).containsExactly(2L, 3L);
    }

    /**
     * 历史小程序请求 JSON 不含动图字段时，既有上传与编辑结构仍可正常绑定。
     */
    @Test
    void legacyWorkRequestsShouldRemainCompatibleWithoutAnimationFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MineWorkUploadTicketRequest ticketRequest = objectMapper.readValue("""
                {
                  "batchId": "legacy-batch",
                  "files": [{
                    "clientId": "legacy-image",
                    "mediaType": "IMAGE",
                    "fileName": "photo.jpg",
                    "mimeType": "image/jpeg",
                    "fileSize": 1024,
                    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "width": 1200,
                    "height": 800,
                    "idempotencyKey": "legacy-ticket"
                  }]
                }
                """, MineWorkUploadTicketRequest.class);
        MineWorkUploadCompleteRequest completeRequest = objectMapper.readValue("""
                {
                  "items": [{
                    "taskId": 99,
                    "title": "旧版图片",
                    "description": "旧版确认请求",
                    "aspectRatio": "3:2",
                    "tagNames": ["婚礼"],
                    "idempotencyKey": "legacy-confirm"
                  }]
                }
                """, MineWorkUploadCompleteRequest.class);
        MineWorkUpdateRequest updateRequest = objectMapper.readValue("""
                {
                  "title": "旧版标题",
                  "description": "旧版编辑请求",
                  "coverFrameTimeMs": 1200,
                  "coverTaskId": 88,
                  "tagIds": [2, 3],
                  "width": 1920,
                  "height": 1080
                }
                """, MineWorkUpdateRequest.class);

        assertThat(ticketRequest.getFiles()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getMediaType()).isEqualTo("IMAGE");
                    assertThat(item.getDurationMs()).isNull();
                });
        assertThat(completeRequest.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getTaskId()).isEqualTo(99L);
                    assertThat(item.getCoverTaskId()).isNull();
                });
        assertThat(updateRequest.getCoverFrameTimeMs()).isEqualTo(1200L);
        assertThat(updateRequest.getCoverFrameNumber()).isNull();
        assertThat(updateRequest.getCoverFrameIdempotencyKey()).isNull();
    }

    /**
     * 旧客户端可忽略确认响应新增的可选错误码，普通成功项保持空值。
     */
    @Test
    void legacyUploadCompleteResponseFieldsShouldRemainUnchangedForSuccess() {
        MineWorkUploadCompleteResponse.Item item =
                MineWorkUploadCompleteResponse.Item.success(99L, null, "上传成功");

        assertThat(item.getTaskId()).isEqualTo(99L);
        assertThat(item.isSuccess()).isTrue();
        assertThat(item.getErrorCode()).isNull();
        assertThat(item.getMessage()).isEqualTo("上传成功");
    }

    private void assertGetMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        GetMapping mapping = MineWorkController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertPostMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PostMapping mapping = MineWorkController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertPutMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PutMapping mapping = MineWorkController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }
}
