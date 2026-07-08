package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineWorkBatchDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkBatchDeleteRequest;
import com.jxc.wefolio.dto.MineWorkBatchDeleteResponse;
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
import com.jxc.wefolio.service.MineWorkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的作品控制器测试 — 固定作品页面接口路径、访问控制注解和服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MineWorkControllerTest {

    /** 我的作品服务模拟 */
    @Mock
    private MineWorkService mineWorkService;

    @Test
    void workEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MineWorkController controller = new MineWorkController(mineWorkService);
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
        when(mineWorkService.listWorks("草坪", 12L, "IMAGE", 2, 10)).thenReturn(listResponse);
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

        Response<MineWorkListResponse> listed = controller.works("草坪", 12L, "IMAGE", 2, 10);
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

        assertThat(MineWorkController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("works", new Class<?>[] {String.class, Long.class, String.class, int.class, int.class}, "/api/mine/works");
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
        assertThat(MineWorkController.class.getMethod("works", String.class, Long.class, String.class, int.class, int.class)
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
        verify(mineWorkService).listSortItems("TAG", 12L);
        verify(mineWorkService).sortWorks(sortRequest);
        verify(mineWorkService).checkDeleteWorks(batchDeleteRequest);
        verify(mineWorkService).deleteWork(99L);
        verify(mineWorkService).deleteWorks(batchDeleteRequest);
        verify(mineWorkService).deleteTag(31L);
    }

    @Test
    void controllerDoesNotDeclareDeleteMapping() {
        boolean hasDeleteMapping = Arrays.stream(MineWorkController.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DeleteMapping.class));

        assertThat(hasDeleteMapping).isFalse();
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
