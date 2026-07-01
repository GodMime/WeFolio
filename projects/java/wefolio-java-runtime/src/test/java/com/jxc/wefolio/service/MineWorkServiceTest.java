package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.dto.MineWorkBatchDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkBatchDeleteRequest;
import com.jxc.wefolio.dto.MineWorkBatchDeleteResponse;
import com.jxc.wefolio.dto.MineWorkDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.dto.MineWorkSortItemsResponse;
import com.jxc.wefolio.dto.MineWorkTagUpsertRequest;
import com.jxc.wefolio.dto.MineWorkUpdateRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteResponse;
import com.jxc.wefolio.dto.MineWorkUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkUploadTicketResponse;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.annotations.Update;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的作品服务测试 — 覆盖直传票据、上传完成确认和引用删除拦截。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MineWorkServiceTest {

    /** 用户 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 作品 Mapper 模拟 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** 标签 Mapper 模拟 */
    @Mock
    private WfTagEntityMapper wfTagEntityMapper;

    /** 作品标签 Mapper 模拟 */
    @Mock
    private WorkTagEntityMapper workTagEntityMapper;

    /** 上传任务 Mapper 模拟 */
    @Mock
    private WorkUploadTaskEntityMapper workUploadTaskEntityMapper;

    /** 作品集引用 Mapper 模拟 */
    @Mock
    private PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** COS 服务模拟 */
    @Mock
    private CosService cosService;

    /** 上传完成事务服务模拟 */
    @Mock
    private WorkUploadTransactionService workUploadTransactionService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void createUploadTicketsShouldBeTransactional() throws NoSuchMethodException {
        Method method = MineWorkService.class.getMethod(
                "createUploadTickets",
                MineWorkUploadTicketRequest.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void tagMutationsShouldBeTransactional() throws NoSuchMethodException {
        Method createMethod = MineWorkService.class.getMethod("createTag", MineWorkTagUpsertRequest.class);
        Method updateMethod = MineWorkService.class.getMethod("updateTag", Long.class, MineWorkTagUpsertRequest.class);
        Method deleteMethod = MineWorkService.class.getMethod("deleteTag", Long.class);

        assertThat(createMethod.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        assertThat(updateMethod.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        assertThat(deleteMethod.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
    }

    @Test
    void createTagShouldPersistTrimmedNameAndFixedPaletteColor() {
        when(wfTagEntityMapper.selectCount(any())).thenReturn(1L);
        when(workTagEntityMapper.selectCount(any())).thenReturn(0L);
        when(wfTagEntityMapper.selectOne(any())).thenReturn(null);
        when(wfTagEntityMapper.insert(any(WfTagEntity.class))).thenAnswer(invocation -> {
            WfTagEntity tag = invocation.getArgument(0);
            tag.setId(31L);
            return 1;
        });
        MineWorkTagUpsertRequest request = tagRequest(" 高端婚礼 ", "#0f766e");

        MineWorkListResponse.TagItem response = service().createTag(request);

        ArgumentCaptor<WfTagEntity> captor = ArgumentCaptor.forClass(WfTagEntity.class);
        verify(wfTagEntityMapper).insert(captor.capture());
        WfTagEntity inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getName()).isEqualTo("高端婚礼");
        assertThat(inserted.getColor()).isEqualTo("#0f766e");
        assertThat(inserted.getStatus()).isEqualTo(WfTagStatusDict.ACTIVE.getCode());
        assertThat(inserted.getCreatedAt()).isNull();
        assertThat(inserted.getUpdatedAt()).isNull();
        assertThat(response.getId()).isEqualTo(31L);
        assertThat(response.getName()).isEqualTo("高端婚礼");
        assertThat(response.getColor()).isEqualTo("#0f766e");
        assertThat(response.getCount()).isZero();
    }

    @Test
    void createTagShouldRejectColorOutsideFixedPalette() {
        MineWorkTagUpsertRequest request = tagRequest("高端婚礼", "#123456");

        assertThatThrownBy(() -> service().createTag(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择有效的标签颜色");
        verify(wfTagEntityMapper, never()).insert(any(WfTagEntity.class));
    }

    @Test
    void updateTagShouldChangeOwnedTagTextAndColor() {
        WfTagEntity tag = ownedTag(31L, "高端婚礼", "#0f766e");
        when(wfTagEntityMapper.selectById(31L)).thenReturn(tag);
        when(wfTagEntityMapper.selectOne(any())).thenReturn(null);
        when(wfTagEntityMapper.updateById(any(WfTagEntity.class))).thenReturn(1);
        when(workTagEntityMapper.selectCount(any())).thenReturn(3L);
        MineWorkTagUpsertRequest request = tagRequest(" 草坪婚礼 ", "#2d5f9a");

        MineWorkListResponse.TagItem response = service().updateTag(31L, request);

        ArgumentCaptor<WfTagEntity> captor = ArgumentCaptor.forClass(WfTagEntity.class);
        verify(wfTagEntityMapper).updateById(captor.capture());
        WfTagEntity updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(31L);
        assertThat(updated.getName()).isEqualTo("草坪婚礼");
        assertThat(updated.getColor()).isEqualTo("#2d5f9a");
        assertThat(updated.getUpdatedAt()).isNull();
        assertThat(response.getName()).isEqualTo("草坪婚礼");
        assertThat(response.getColor()).isEqualTo("#2d5f9a");
        assertThat(response.getCount()).isEqualTo(3L);
    }

    @Test
    void updateTagShouldRejectDuplicateName() {
        when(wfTagEntityMapper.selectById(31L)).thenReturn(ownedTag(31L, "高端婚礼", "#0f766e"));
        when(wfTagEntityMapper.selectOne(any())).thenReturn(ownedTag(44L, "草坪婚礼", "#2d5f9a"));
        MineWorkTagUpsertRequest request = tagRequest("草坪婚礼", "#2d5f9a");

        assertThatThrownBy(() -> service().updateTag(31L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签不能重复");
        verify(wfTagEntityMapper, never()).updateById(any(WfTagEntity.class));
    }

    @Test
    void deleteTagShouldRejectTagWithWorksUsingHelpfulMessage() {
        when(wfTagEntityMapper.selectById(31L)).thenReturn(ownedTag(31L, "高端婚礼", "#0f766e"));
        when(workTagEntityMapper.selectCount(any())).thenReturn(8L);

        assertThatThrownBy(() -> service().deleteTag(31L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签「高端婚礼」下还有 8 个作品，先移除这些作品的标签后再删除。");
        verify(wfTagEntityMapper, never()).deleteById(31L);
    }

    @Test
    void deleteTagShouldSoftDeleteUnusedOwnedTag() {
        when(wfTagEntityMapper.selectById(31L)).thenReturn(ownedTag(31L, "高端婚礼", "#0f766e"));
        when(workTagEntityMapper.selectCount(any())).thenReturn(0L);
        when(wfTagEntityMapper.deleteById(31L)).thenReturn(1);

        service().deleteTag(31L);

        verify(wfTagEntityMapper).deleteById(31L);
    }

    @Test
    void listWorksShouldBatchLoadSummaryReferencesAndTags() {
        WorkEntity first = ownedWork(11L);
        first.setMediaType(MediaTypeDict.IMAGE.getCode());
        first.setTitle("草坪婚礼");
        first.setOriginalFileName("photo.jpg");
        first.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        first.setCoverObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        WorkEntity second = ownedWork(12L);
        second.setMediaType(MediaTypeDict.VIDEO.getCode());
        second.setTitle("片头快剪");
        second.setOriginalFileName("film.mp4");
        second.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        Page<WorkEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(first, second));
        page.setTotal(2L);
        when(workEntityMapper.selectPage(any(), any())).thenReturn(page);
        when(workEntityMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("mediaType", MediaTypeDict.IMAGE.getCode(), "itemCount", 1L),
                Map.of("mediaType", MediaTypeDict.VIDEO.getCode(), "itemCount", 1L)
        ));
        WfTagEntity ceremonyTag = ownedTag(31L, "户外仪式", "#0f766e");
        WfTagEntity editTag = ownedTag(32L, "快剪", "#2d5f9a");
        when(wfTagEntityMapper.selectList(any())).thenReturn(List.of(ceremonyTag, editTag));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(
                workTagRelation(11L, 31L),
                workTagRelation(12L, 32L)
        ));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                portfolioReference(11L),
                portfolioReference(12L),
                portfolioReference(12L)
        ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        MineWorkListResponse response = service().listWorks(null, null, 1, 20);

        assertThat(response.getSummary().getTotalCount()).isEqualTo(2L);
        assertThat(response.getSummary().getImageCount()).isEqualTo(1L);
        assertThat(response.getSummary().getVideoCount()).isEqualTo(1L);
        assertThat(response.getTags()).extracting(MineWorkListResponse.TagItem::getCount)
                .containsExactly(2L, 1L, 1L);
        assertThat(response.getWorks()).hasSize(2);
        assertThat(response.getWorks().get(0).getReferenceCount()).isEqualTo(1L);
        assertThat(response.getWorks().get(0).getTags()).extracting(MineWorkListResponse.TagItem::getName)
                .containsExactly("户外仪式");
        assertThat(response.getWorks().get(1).getReferenceCount()).isEqualTo(2L);
        assertThat(response.getWorks().get(1).getTags()).extracting(MineWorkListResponse.TagItem::getName)
                .containsExactly("快剪");
        verify(workEntityMapper, never()).selectCount(any());
        verify(workTagEntityMapper, never()).selectCount(any());
        verify(workEntityMapper, times(1)).selectMaps(any());
        verify(workTagEntityMapper, times(1)).selectList(any());
        verify(portfolioReferenceEntityMapper, times(1)).selectList(any());
        verify(wfTagEntityMapper, never()).selectBatchIds(any());
    }

    @Test
    void createUploadTicketsShouldGenerateOwnedCosKeyAndPersistCreatedTask() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(cosService.createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    String objectKey = invocation.getArgument(0);
                    return new CosService.PostUploadTicket(
                            "https://bucket.cos.ap-guangzhou.myqcloud.com",
                            objectKey,
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            java.util.Map.of("key", objectKey));
                });
        MineWorkUploadTicketRequest.UploadFileItem imageFile = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo.jpg", "image/jpeg", 1024L);
        MineWorkUploadTicketRequest.UploadFileItem videoFile = ticketFile(
                "client-2", MediaTypeDict.VIDEO.getCode(), "film.mp4", "video/mp4", 4096L);
        videoFile.setDurationMs(30_000);
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setBatchId("batch-a");
        request.setFiles(List.of(imageFile, videoFile));

        MineWorkUploadTicketResponse response = service().createUploadTickets(request);

        ArgumentCaptor<WorkUploadTaskEntity> captor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper, times(2)).insert(captor.capture());
        WorkUploadTaskEntity firstTask = captor.getAllValues().get(0);
        WorkUploadTaskEntity secondTask = captor.getAllValues().get(1);
        assertThat(firstTask.getUserId()).isEqualTo(7L);
        assertThat(firstTask.getBatchId()).isEqualTo("batch-a");
        assertThat(firstTask.getMediaType()).isEqualTo(MediaTypeDict.IMAGE.getCode());
        assertThat(firstTask.getObjectKey())
                .matches("WFA3B1E7A2/work/image/WFA3B1E7A2-P-\\d{13}-1\\.jpg");
        assertThat(firstTask.getStatus()).isEqualTo(WorkUploadTaskStatusDict.CREATED.getCode());
        assertThat(secondTask.getUserId()).isEqualTo(7L);
        assertThat(secondTask.getBatchId()).isEqualTo("batch-a");
        assertThat(secondTask.getMediaType()).isEqualTo(MediaTypeDict.VIDEO.getCode());
        assertThat(secondTask.getObjectKey())
                .matches("WFA3B1E7A2/work/video/WFA3B1E7A2-V-\\d{13}-2\\.mp4");
        assertThat(secondTask.getStatus()).isEqualTo(WorkUploadTaskStatusDict.CREATED.getCode());
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getObjectKey()).isEqualTo(firstTask.getObjectKey());
        assertThat(response.getItems().get(1).getObjectKey()).isEqualTo(secondTask.getObjectKey());
        assertThat(response.getItems().get(0).getUploadUrl()).contains("myqcloud.com");
    }

    @Test
    void createUploadTicketsShouldPersistClientSha256OnTask() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(cosService.createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        MineWorkUploadTicketRequest.UploadFileItem file = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo.jpg", "image/jpeg", 1024L);
        file.setSha256("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(file));

        service().createUploadTickets(request);

        ArgumentCaptor<WorkUploadTaskEntity> captor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper).insert(captor.capture());
        assertThat(captor.getValue().getFileSha256())
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void createUploadTicketsShouldRejectInvalidSha256() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineWorkUploadTicketRequest.UploadFileItem file = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo.jpg", "image/jpeg", 1024L);
        file.setSha256("not-sha256");
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(file));

        assertThatThrownBy(() -> service().createUploadTickets(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件 SHA-256 格式不正确");
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
    }

    @Test
    void createUploadTicketsShouldRejectDuplicateExistingWorkBySha256() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        WorkEntity existing = new WorkEntity();
        existing.setId(120L);
        existing.setUserId(7L);
        existing.setTitle("草坪婚礼");
        existing.setMediaSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(workEntityMapper.selectOne(any())).thenReturn(existing);
        MineWorkUploadTicketRequest.UploadFileItem file = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo.jpg", "image/jpeg", 1024L);
        file.setSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(file));

        assertThatThrownBy(() -> service().createUploadTickets(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品已存在：「草坪婚礼」");
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
    }

    @Test
    void createUploadTicketsShouldDeriveThumbObjectKeysFromSourceTask() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        WorkUploadTaskEntity sourceImageTask = uploadTask(
                201L,
                "batch-a",
                "WFA3B1E7A2/work/image/WFA3B1E7A2-P-1782807167829-1.jpg",
                "ticket-image-large");
        sourceImageTask.setMediaType(MediaTypeDict.IMAGE.getCode());
        sourceImageTask.setOriginalFileName("photo.png");
        WorkUploadTaskEntity sourceVideoTask = uploadTask(
                202L,
                "batch-a",
                "WFA3B1E7A2/work/video/WFA3B1E7A2-V-1782807167829-3.mp4",
                "ticket-video-a");
        sourceVideoTask.setMediaType(MediaTypeDict.VIDEO.getCode());
        sourceVideoTask.setOriginalFileName("film.mp4");
        sourceVideoTask.setMimeType("video/mp4");
        when(workUploadTaskEntityMapper.selectById(201L)).thenReturn(sourceImageTask);
        when(workUploadTaskEntityMapper.selectById(202L)).thenReturn(sourceVideoTask);
        when(cosService.createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    String objectKey = invocation.getArgument(0);
                    return new CosService.PostUploadTicket(
                            "https://bucket.cos.ap-guangzhou.myqcloud.com",
                            objectKey,
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            java.util.Map.of("key", objectKey));
                });
        MineWorkUploadTicketRequest.UploadFileItem imageThumbFile = ticketFile(
                "image-large-cover", MediaTypeDict.IMAGE.getCode(), "photo-thumb.jpg", "image/jpeg", 90_000L);
        imageThumbFile.setSourceTaskId(201L);
        MineWorkUploadTicketRequest.UploadFileItem videoCoverFile = ticketFile(
                "video-a-cover", MediaTypeDict.IMAGE.getCode(), "film-thumb.jpg", "image/jpeg", 80_000L);
        videoCoverFile.setSourceTaskId(202L);
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setBatchId("cover-batch");
        request.setFiles(List.of(imageThumbFile, videoCoverFile));

        MineWorkUploadTicketResponse response = service().createUploadTickets(request);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getObjectKey())
                .isEqualTo("WFA3B1E7A2/work/image/WFA3B1E7A2-P-1782807167829-1-thumb.jpg");
        assertThat(response.getItems().get(1).getObjectKey())
                .isEqualTo("WFA3B1E7A2/work/video/WFA3B1E7A2-V-1782807167829-3-thumb.jpg");
    }

    /**
     * 主作品文件名带 -thumb 后缀时，没有 sourceTaskId 也仍按主作品生成对象键。
     */
    @Test
    void createUploadTicketsShouldNotTreatMainFileNameThumbSuffixAsThumbnail() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(cosService.createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    String objectKey = invocation.getArgument(0);
                    return new CosService.PostUploadTicket(
                            "https://bucket.cos.ap-guangzhou.myqcloud.com",
                            objectKey,
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            java.util.Map.of("key", objectKey));
                });
        MineWorkUploadTicketRequest.UploadFileItem imageFile = ticketFile(
                "client-thumb-name", MediaTypeDict.IMAGE.getCode(), "photo-thumb.jpg", "image/jpeg", 1024L);
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setBatchId("batch-thumb-name");
        request.setFiles(List.of(imageFile));

        MineWorkUploadTicketResponse response = service().createUploadTickets(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getObjectKey())
                .matches("WFA3B1E7A2/work/image/WFA3B1E7A2-P-\\d{13}-1\\.jpg");
        assertThat(response.getItems().get(0).getObjectKey()).doesNotContain("-1-thumb.jpg");
    }

    @Test
    void createUploadTicketsShouldRejectOversizedVideoBeforeCreatingTask() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineWorkUploadTicketRequest.UploadFileItem file = ticketFile(
                "client-1", MediaTypeDict.VIDEO.getCode(), "film.mp4", "video/mp4", 101L * 1024L * 1024L);
        file.setDurationMs(30_000);
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(file));

        assertThatThrownBy(() -> service().createUploadTickets(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频作品不能超过 100MB");
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
        verify(cosService, never()).createPostUploadTicket(any(), any(), anyLong(), any());
    }

    @Test
    void createUploadTicketsShouldRejectSameBatchDuplicateFileBeforeCreatingTask() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        lenient().when(cosService.createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        MineWorkUploadTicketRequest.UploadFileItem firstFile = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo.jpg", "image/jpeg", 1024L);
        MineWorkUploadTicketRequest.UploadFileItem secondFile = ticketFile(
                "client-2", MediaTypeDict.IMAGE.getCode(), "photo-copy.jpg", "image/jpeg", 2048L);
        secondFile.setIdempotencyKey(firstFile.getIdempotencyKey());
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setBatchId("batch-a");
        request.setFiles(List.of(firstFile, secondFile));

        assertThatThrownBy(() -> service().createUploadTickets(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("同一批次存在重复文件，请重新选择后上传");
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
        verify(cosService, never()).createPostUploadTicket(any(), any(), anyLong(), any());
    }

    @Test
    void createUploadTicketsShouldUseDedicatedMessageForObjectKeyCollisionGuard() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineWorkService.java"));
        String messageSource = Files.readString(Path.of("src/main/java/com/jxc/wefolio/message/MineWorkMessage.java"));
        int objectKeyGuardIndex = source.indexOf("if (!objectKeys.add(objectKey))");
        assertThat(objectKeyGuardIndex).isGreaterThanOrEqualTo(0);
        String objectKeyGuard = source.substring(
                objectKeyGuardIndex,
                source.indexOf("preparedFiles.add", objectKeyGuardIndex));

        assertThat(messageSource).contains("WORK_OBJECT_KEY_CONFLICT_MESSAGE = \"上传文件命名冲突，请稍后重试\"");
        assertThat(objectKeyGuard)
                .contains("throw new BusinessException(MineWorkMessage.WORK_OBJECT_KEY_CONFLICT_MESSAGE);")
                .doesNotContain("SAME_BATCH_DUPLICATE_FILE_MESSAGE");
    }

    @Test
    void createUploadTicketsShouldReusePreparedIdempotencyKeyWhenBuildingTask() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineWorkService.java"));
        int buildTaskIndex = source.indexOf("private WorkUploadTaskEntity buildUploadTask");
        assertThat(buildTaskIndex).isGreaterThanOrEqualTo(0);
        String buildUploadTask = source.substring(buildTaskIndex, source.indexOf("private record PreparedUploadFile"));

        assertThat(source).contains("String idempotencyKey");
        assertThat(source).contains("new PreparedUploadFile(file, mediaType, objectKey, idempotencyKey, fileSha256)");
        assertThat(source).contains("preparedFile.idempotencyKey()");
        assertThat(source).contains("preparedFile.fileSha256()");
        assertThat(buildUploadTask)
                .contains("task.setIdempotencyKey(idempotencyKey);")
                .doesNotContain("normalizeTicketIdempotency(batchId, file)");
    }

    @Test
    void createUploadTicketsShouldReturnExistingTaskWhenIdempotencyKeyDuplicate() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        WorkUploadTaskEntity existingTask = uploadTask(
                66L,
                "batch-a",
                "WFA3B1E7A2/work/image/existing.jpg",
                "ticket-client-1");
        when(workUploadTaskEntityMapper.insert(any(WorkUploadTaskEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(workUploadTaskEntityMapper.selectOne(any())).thenReturn(existingTask);
        when(cosService.createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        MineWorkUploadTicketRequest.UploadFileItem file = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo.jpg", "image/jpeg", 1024L);
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setBatchId("batch-a");
        request.setFiles(List.of(file));

        MineWorkUploadTicketResponse response = service().createUploadTickets(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getTaskId()).isEqualTo(66L);
        assertThat(response.getItems().get(0).getClientId()).isEqualTo("client-1");
        assertThat(response.getItems().get(0).getObjectKey()).isEqualTo(existingTask.getObjectKey());
        verify(workUploadTaskEntityMapper).selectOne(any());
    }

    @Test
    void completeUploadShouldVerifyCosObjectBeforeTransactionalConfirmation() {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(99L);
        task.setUserId(7L);
        task.setMediaType(MediaTypeDict.IMAGE.getCode());
        task.setObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        task.setOriginalFileName("photo.jpg");
        task.setMimeType("image/jpeg");
        task.setFileSize(4096L);
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        work.setTitle("草坪婚礼");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(cosService.headObject("WFA3B1E7A2/work/image/photo.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 4096L));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(task), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        item.setTitle(" 草坪婚礼 ");
        item.setTagNames(List.of("户外仪式"));
        item.setIdempotencyKey("confirm-99");
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        verify(cosService).headObject("WFA3B1E7A2/work/image/photo.jpg");
        verify(workUploadTransactionService).confirmUploadedTask(eq(7L), eq(task), any());
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
        assertThat(response.getItems().get(0).getWorkId()).isEqualTo(120L);
    }

    @Test
    void completeUploadShouldVerifyCustomCoverTaskBeforeTransactionalConfirmation() {
        WorkUploadTaskEntity videoTask = new WorkUploadTaskEntity();
        videoTask.setId(99L);
        videoTask.setUserId(7L);
        videoTask.setMediaType(MediaTypeDict.VIDEO.getCode());
        videoTask.setObjectKey("WFA3B1E7A2/work/video/film.mp4");
        videoTask.setOriginalFileName("film.mp4");
        videoTask.setMimeType("video/mp4");
        videoTask.setFileSize(4096L);
        videoTask.setDurationMs(60_000);
        videoTask.setWidth(1080);
        videoTask.setHeight(1920);
        videoTask.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        videoTask.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        WorkUploadTaskEntity coverTask = uploadTask(
                101L,
                "cover-batch",
                "WFA3B1E7A2/work/image/film-cover.jpg",
                "cover-ticket-video-a");
        coverTask.setOriginalFileName("film-thumb.jpg");
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        work.setTitle("片头快剪");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(videoTask);
        when(workUploadTaskEntityMapper.selectById(101L)).thenReturn(coverTask);
        when(cosService.headObject("WFA3B1E7A2/work/video/film.mp4"))
                .thenReturn(new CosService.ObjectHead("video/mp4", 4096L));
        when(cosService.headObject("WFA3B1E7A2/work/image/film-cover.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(videoTask), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        item.setCoverTaskId(101L);
        item.setTitle(" 片头快剪 ");
        item.setIdempotencyKey("confirm-99");
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        verify(cosService).headObject("WFA3B1E7A2/work/video/film.mp4");
        verify(cosService).headObject("WFA3B1E7A2/work/image/film-cover.jpg");
        verify(workUploadTransactionService).confirmUploadedTask(eq(7L), eq(videoTask), any());
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
    }

    @Test
    void completeUploadShouldGenerateFirstFrameCoverForVideoWithoutCoverTask(CapturedOutput output) {
        WorkUploadTaskEntity videoTask = new WorkUploadTaskEntity();
        videoTask.setId(99L);
        videoTask.setUserId(7L);
        videoTask.setBatchId("batch-video");
        videoTask.setMediaType(MediaTypeDict.VIDEO.getCode());
        videoTask.setObjectKey("WFA3B1E7A2/work/video/film.mp4");
        videoTask.setFileSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        videoTask.setOriginalFileName("film.mp4");
        videoTask.setMimeType("video/mp4");
        videoTask.setFileSize(4096L);
        videoTask.setDurationMs(60_000);
        videoTask.setWidth(1080);
        videoTask.setHeight(1920);
        videoTask.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        videoTask.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        AtomicReference<WorkUploadTaskEntity> generatedCoverTask = new AtomicReference<>();
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        work.setTitle("片头快剪");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(videoTask);
        when(workUploadTaskEntityMapper.selectById(202L)).thenAnswer(invocation -> generatedCoverTask.get());
        when(workUploadTaskEntityMapper.insert(any(WorkUploadTaskEntity.class))).thenAnswer(invocation -> {
            WorkUploadTaskEntity task = invocation.getArgument(0);
            task.setId(202L);
            generatedCoverTask.set(task);
            return 1;
        });
        when(cosService.headObject("WFA3B1E7A2/work/video/film.mp4"))
                .thenReturn(new CosService.ObjectHead("video/mp4", 4096L));
        when(cosService.headObject("WFA3B1E7A2/work/video/film-thumb.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 12_345L));
        when(cosService.snapshotVideoFrameToObject(
                "WFA3B1E7A2/work/video/film.mp4",
                "WFA3B1E7A2/work/video/film-thumb.jpg",
                0L,
                360,
                640))
                .thenReturn(new CosService.SnapshotObject(
                        "WFA3B1E7A2/work/video/film-thumb.jpg",
                        "image/jpeg",
                        12_345L,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(videoTask), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        item.setTitle(" 片头快剪 ");
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        ArgumentCaptor<WorkUploadTaskEntity> coverCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        ArgumentCaptor<MineWorkUploadCompleteRequest.CompleteItem> itemCaptor =
                ArgumentCaptor.forClass(MineWorkUploadCompleteRequest.CompleteItem.class);
        verify(workUploadTaskEntityMapper).insert(coverCaptor.capture());
        verify(workUploadTransactionService).confirmUploadedTask(eq(7L), eq(videoTask), itemCaptor.capture());
        WorkUploadTaskEntity coverTask = coverCaptor.getValue();
        assertThat(coverTask.getUserId()).isEqualTo(7L);
        assertThat(coverTask.getBatchId()).isEqualTo("batch-video");
        assertThat(coverTask.getMediaType()).isEqualTo(MediaTypeDict.IMAGE.getCode());
        assertThat(coverTask.getObjectKey()).isEqualTo("WFA3B1E7A2/work/video/film-thumb.jpg");
        assertThat(coverTask.getOriginalFileName()).isEqualTo("film-thumb.jpg");
        assertThat(coverTask.getMimeType()).isEqualTo("image/jpeg");
        assertThat(coverTask.getFileSize()).isEqualTo(12_345L);
        assertThat(coverTask.getFileSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(coverTask.getStatus()).isEqualTo(WorkUploadTaskStatusDict.UPLOADED.getCode());
        assertThat(itemCaptor.getValue().getCoverTaskId()).isEqualTo(202L);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
        assertThat(output)
                .contains("视频作品默认封面截帧入参")
                .contains("userId=7")
                .contains("sourceTaskId=99")
                .contains("sourceObjectKey=WFA3B1E7A2/work/video/film.mp4")
                .contains("coverObjectKey=WFA3B1E7A2/work/video/film-thumb.jpg")
                .contains("frameTimeMs=0")
                .contains("mediaWidth=1080")
                .contains("mediaHeight=1920")
                .contains("snapshotWidth=360")
                .contains("snapshotHeight=640");
    }

    @Test
    void completeUploadShouldVerifyLargeImageThumbnailTaskBeforeTransactionalConfirmation() {
        WorkUploadTaskEntity imageTask = new WorkUploadTaskEntity();
        imageTask.setId(99L);
        imageTask.setUserId(7L);
        imageTask.setMediaType(MediaTypeDict.IMAGE.getCode());
        imageTask.setObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        imageTask.setOriginalFileName("photo.png");
        imageTask.setMimeType("image/png");
        imageTask.setFileSize(150L * 1024L);
        imageTask.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        imageTask.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        WorkUploadTaskEntity coverTask = uploadTask(
                101L,
                "cover-batch",
                "WFA3B1E7A2/work/image/photo-thumb.jpg",
                "cover-ticket-image-a");
        coverTask.setOriginalFileName("photo-thumb.jpg");
        coverTask.setFileSize(90L * 1024L);
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        work.setTitle("草坪婚礼");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(imageTask);
        when(workUploadTaskEntityMapper.selectById(101L)).thenReturn(coverTask);
        when(cosService.headObject("WFA3B1E7A2/work/image/photo.jpg"))
                .thenReturn(new CosService.ObjectHead("image/png", 150L * 1024L));
        when(cosService.headObject("WFA3B1E7A2/work/image/photo-thumb.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 90L * 1024L));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(imageTask), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        item.setCoverTaskId(101L);
        item.setTitle(" 草坪婚礼 ");
        item.setIdempotencyKey("confirm-99");
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        verify(cosService).headObject("WFA3B1E7A2/work/image/photo.jpg");
        verify(cosService).headObject("WFA3B1E7A2/work/image/photo-thumb.jpg");
        verify(workUploadTransactionService).confirmUploadedTask(eq(7L), eq(imageTask), any());
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
    }

    @Test
    void completeUploadShouldFailWhenLargeImageMissingThumbnailTask() {
        WorkUploadTaskEntity imageTask = new WorkUploadTaskEntity();
        imageTask.setId(99L);
        imageTask.setUserId(7L);
        imageTask.setMediaType(MediaTypeDict.IMAGE.getCode());
        imageTask.setObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        imageTask.setOriginalFileName("photo.jpg");
        imageTask.setMimeType("image/jpeg");
        imageTask.setFileSize(150L * 1024L);
        imageTask.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        imageTask.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(imageTask);
        when(cosService.headObject("WFA3B1E7A2/work/image/photo.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 150L * 1024L));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("图片缩略图缺失，请重新上传");
        verify(workUploadTransactionService, never()).confirmUploadedTask(any(), any(), any());
    }

    @Test
    void completeUploadShouldFailWhenCoverTaskExceedsThumbLimit() {
        WorkUploadTaskEntity videoTask = new WorkUploadTaskEntity();
        videoTask.setId(99L);
        videoTask.setUserId(7L);
        videoTask.setMediaType(MediaTypeDict.VIDEO.getCode());
        videoTask.setObjectKey("WFA3B1E7A2/work/video/film.mp4");
        videoTask.setOriginalFileName("film.mp4");
        videoTask.setMimeType("video/mp4");
        videoTask.setFileSize(4096L);
        videoTask.setDurationMs(60_000);
        videoTask.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        videoTask.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        WorkUploadTaskEntity coverTask = uploadTask(
                101L,
                "cover-batch",
                "WFA3B1E7A2/work/image/film-thumb.jpg",
                "cover-ticket-video-a");
        coverTask.setOriginalFileName("film-thumb.jpg");
        coverTask.setFileSize(100L * 1024L + 1L);
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(videoTask);
        when(workUploadTaskEntityMapper.selectById(101L)).thenReturn(coverTask);
        when(cosService.headObject("WFA3B1E7A2/work/video/film.mp4"))
                .thenReturn(new CosService.ObjectHead("video/mp4", 4096L));
        when(cosService.headObject("WFA3B1E7A2/work/image/film-thumb.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 100L * 1024L + 1L));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        item.setCoverTaskId(101L);
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("缩略图或封面图不能超过 100KB");
        verify(workUploadTransactionService, never()).confirmUploadedTask(any(), any(), any());
    }

    @Test
    void completeUploadShouldReturnItemFailureWhenCosHeadThrowsRuntimeException() {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(99L);
        task.setUserId(7L);
        task.setMediaType(MediaTypeDict.IMAGE.getCode());
        task.setObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        task.setMimeType("image/jpeg");
        task.setFileSize(1024L);
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(cosService.headObject("WFA3B1E7A2/work/image/photo.jpg"))
                .thenThrow(new RuntimeException("COS 对象读取失败"));
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("上传文件读取失败，请重新上传");
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(WorkUploadTaskStatusDict.FAILED.getCode());
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("上传文件读取失败，请重新上传");
        verify(workUploadTransactionService, never()).confirmUploadedTask(any(), any(), any());
    }

    @Test
    void completeUploadShouldKeepBatchResultWhenTransactionalConfirmationThrowsRuntimeException() {
        WorkUploadTaskEntity firstTask = uploadTask(
                99L,
                "batch-a",
                "WFA3B1E7A2/work/image/first.jpg",
                "ticket-client-1");
        WorkUploadTaskEntity secondTask = uploadTask(
                100L,
                "batch-a",
                "WFA3B1E7A2/work/image/second.jpg",
                "ticket-client-2");
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        work.setTitle("第一张");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(firstTask);
        when(workUploadTaskEntityMapper.selectById(100L)).thenReturn(secondTask);
        when(cosService.headObject(any()))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(firstTask), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(secondTask), any()))
                .thenThrow(new RuntimeException("数据库连接中断"));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        MineWorkUploadCompleteRequest.CompleteItem firstItem = new MineWorkUploadCompleteRequest.CompleteItem();
        firstItem.setTaskId(99L);
        MineWorkUploadCompleteRequest.CompleteItem secondItem = new MineWorkUploadCompleteRequest.CompleteItem();
        secondItem.setTaskId(100L);
        request.setItems(List.of(firstItem, secondItem));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
        assertThat(response.getItems().get(1).isSuccess()).isFalse();
        assertThat(response.getItems().get(1).getMessage()).isEqualTo("作品确认失败，请稍后重试");
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getId()).isEqualTo(100L);
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("作品确认失败，请稍后重试");
    }

    @Test
    void updateWorkShouldOnlySaveEditableFieldsWithoutReplacingTags() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setOriginalFileName("photo.jpg");
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");

        service().updateWork(18L, request);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).updateById(workCaptor.capture());
        assertThat(workCaptor.getValue().getTitle()).isEqualTo("新标题");
        assertThat(workCaptor.getValue().getDescription()).isEqualTo("新说明");
        verify(workTagEntityMapper, never()).delete(any());
        verify(workTagEntityMapper, never()).insert(any(WorkTagEntity.class));
        verify(wfTagEntityMapper, never()).insert(any(WfTagEntity.class));
    }

    @Test
    void updateWorkShouldRejectCoverFrameForImageWork() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setOriginalFileName("photo.jpg");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle("新标题");
        request.setCoverFrameTimeMs(1200L);

        assertThatThrownBy(() -> service().updateWork(18L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("只有视频作品可以修改封面");
        verify(workEntityMapper, never()).updateById(any(WorkEntity.class));
    }

    @Test
    void updateWorkShouldReplaceVideoCoverFromFrameTimeAndDeleteOldCoverObject(CapturedOutput output) {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        work.setCoverObjectKey("WFA3B1E7A2/work/video/old-thumb.jpg");
        work.setCoverSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        work.setOriginalFileName("film.mp4");
        work.setWidth(1920);
        work.setHeight(1080);
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(cosService.snapshotVideoFrameToObject(
                "WFA3B1E7A2/work/video/film.mp4",
                "WFA3B1E7A2/work/video/film-thumb-5200.jpg",
                5200L,
                360,
                640))
                .thenReturn(new CosService.SnapshotObject(
                        "WFA3B1E7A2/work/video/film-thumb-5200.jpg",
                        "image/jpeg",
                        90L * 1024L,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");
        request.setCoverFrameTimeMs(5200L);
        request.setWidth(1080);
        request.setHeight(1920);

        service().updateWork(18L, request);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).updateById(workCaptor.capture());
        verify(cosService).delete("WFA3B1E7A2/work/video/old-thumb.jpg");
        verify(workUploadTaskEntityMapper, never()).updateById(any(WorkUploadTaskEntity.class));
        WorkEntity updatedWork = workCaptor.getValue();
        assertThat(updatedWork.getTitle()).isEqualTo("新标题");
        assertThat(updatedWork.getDescription()).isEqualTo("新说明");
        assertThat(updatedWork.getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/video/film-thumb-5200.jpg");
        assertThat(updatedWork.getCoverSha256())
                .isEqualTo("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        assertThat(updatedWork.getWidth()).isEqualTo(1080);
        assertThat(updatedWork.getHeight()).isEqualTo(1920);
        assertThat(output)
                .contains("视频作品替换封面截帧入参")
                .contains("workId=18")
                .contains("sourceObjectKey=WFA3B1E7A2/work/video/film.mp4")
                .contains("coverObjectKey=WFA3B1E7A2/work/video/film-thumb-5200.jpg")
                .contains("requestedFrameTimeMs=5200")
                .contains("frameTimeMs=5200")
                .contains("requestWidth=1080")
                .contains("requestHeight=1920")
                .contains("snapshotWidth=360")
                .contains("snapshotHeight=640");
    }

    @Test
    void updateWorkShouldDeleteGeneratedCoverWhenSnapshotExceedsLimit() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        work.setCoverObjectKey("WFA3B1E7A2/work/video/old-thumb.jpg");
        work.setOriginalFileName("film.mp4");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(cosService.snapshotVideoFrameToObject(
                "WFA3B1E7A2/work/video/film.mp4",
                "WFA3B1E7A2/work/video/film-thumb-5200.jpg",
                5200L,
                640,
                640))
                .thenReturn(new CosService.SnapshotObject(
                        "WFA3B1E7A2/work/video/film-thumb-5200.jpg",
                        "image/jpeg",
                        100L * 1024L + 1L,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle("新标题");
        request.setCoverFrameTimeMs(5200L);

        assertThatThrownBy(() -> service().updateWork(18L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缩略图或封面图不能超过 100KB");
        verify(cosService).delete("WFA3B1E7A2/work/video/film-thumb-5200.jpg");
        verify(workEntityMapper, never()).updateById(any(WorkEntity.class));
    }

    @Test
    void updateWorkShouldDeleteGeneratedCoverWhenSaveFails() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        work.setCoverObjectKey("WFA3B1E7A2/work/video/old-thumb.jpg");
        work.setOriginalFileName("film.mp4");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(cosService.snapshotVideoFrameToObject(
                "WFA3B1E7A2/work/video/film.mp4",
                "WFA3B1E7A2/work/video/film-thumb-5200.jpg",
                5200L,
                640,
                640))
                .thenReturn(new CosService.SnapshotObject(
                        "WFA3B1E7A2/work/video/film-thumb-5200.jpg",
                        "image/jpeg",
                        90L * 1024L,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(0);
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle("新标题");
        request.setCoverFrameTimeMs(5200L);

        assertThatThrownBy(() -> service().updateWork(18L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品保存失败，请刷新后重试");
        verify(cosService).delete("WFA3B1E7A2/work/video/film-thumb-5200.jpg");
        verify(cosService, never()).delete("WFA3B1E7A2/work/video/old-thumb.jpg");
    }

    @Test
    void sortWorksShouldRejectEmptyItemsBeforeMapperSql() {
        MineWorkSortRequest request = new MineWorkSortRequest();
        request.setItems(List.of());

        assertThatThrownBy(() -> service().sortWorks(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请提交要排序的作品");
        verify(workEntityMapper, never()).updateSortOrders(any(), any());
    }

    @Test
    void sortWorksShouldAvoidPerRowUpdateById() {
        WorkEntity first = ownedWork(11L);
        WorkEntity second = ownedWork(12L);
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(workEntityMapper.updateSortOrders(eq(7L), any())).thenReturn(2);
        MineWorkSortRequest.Item firstItem = sortItem(11L, 20);
        MineWorkSortRequest.Item secondItem = sortItem(12L, 10);
        MineWorkSortRequest request = new MineWorkSortRequest();
        request.setItems(List.of(firstItem, secondItem));

        service().sortWorks(request);

        verify(workEntityMapper, never()).updateById(any(WorkEntity.class));
        verify(workEntityMapper).updateSortOrders(eq(7L), any());
    }

    @Test
    void sortWorksShouldUpdateTagScopedSortWithoutChangingGlobalOrder() {
        WorkEntity first = ownedWork(11L);
        WorkEntity second = ownedWork(12L);
        when(wfTagEntityMapper.selectById(31L)).thenReturn(ownedTag(31L, "高端婚礼", "#0f766e"));
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(
                workTagRelation(11L, 31L),
                workTagRelation(12L, 31L)
        ));
        when(workTagEntityMapper.updateSortOrders(eq(7L), eq(31L), any())).thenReturn(2);
        MineWorkSortRequest request = new MineWorkSortRequest();
        request.setScope("TAG");
        request.setTagId(31L);
        request.setItems(List.of(sortItem(11L, 2000), sortItem(12L, 1000)));

        service().sortWorks(request);

        verify(workEntityMapper, never()).updateSortOrders(any(), any());
        verify(workTagEntityMapper).updateSortOrders(eq(7L), eq(31L), any());
    }

    @Test
    void sortWorksShouldRejectTagScopeWhenWorkIsNotBoundToTag() {
        WorkEntity first = ownedWork(11L);
        WorkEntity second = ownedWork(12L);
        when(wfTagEntityMapper.selectById(31L)).thenReturn(ownedTag(31L, "高端婚礼", "#0f766e"));
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(workTagRelation(11L, 31L)));
        MineWorkSortRequest request = new MineWorkSortRequest();
        request.setScope("TAG");
        request.setTagId(31L);
        request.setItems(List.of(sortItem(11L, 2000), sortItem(12L, 1000)));

        assertThatThrownBy(() -> service().sortWorks(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品不在当前标签下，请刷新后重试");
        verify(workTagEntityMapper, never()).updateSortOrders(any(), any(), any());
        verify(workEntityMapper, never()).updateSortOrders(any(), any());
    }

    @Test
    void listSortItemsShouldReturnTagScopedOrderItems() {
        WorkEntity first = ownedWork(11L);
        first.setTitle("草坪婚礼");
        first.setMediaType(MediaTypeDict.IMAGE.getCode());
        first.setCoverObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        first.setSortOrder(2000);
        WorkEntity second = ownedWork(12L);
        second.setTitle("片头快剪");
        second.setMediaType(MediaTypeDict.VIDEO.getCode());
        second.setCoverObjectKey("WFA3B1E7A2/work/video/film-thumb.jpg");
        second.setSortOrder(1000);
        WorkTagEntity firstRelation = workTagRelation(11L, 31L);
        firstRelation.setSortOrder(3000);
        WorkTagEntity secondRelation = workTagRelation(12L, 31L);
        secondRelation.setSortOrder(1000);
        when(wfTagEntityMapper.selectById(31L)).thenReturn(ownedTag(31L, "高端婚礼", "#0f766e"));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(secondRelation, firstRelation));
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        MineWorkSortItemsResponse response = service().listSortItems("TAG", 31L);

        assertThat(response.getScope()).isEqualTo("TAG");
        assertThat(response.getTagId()).isEqualTo(31L);
        assertThat(response.getTotal()).isEqualTo(2L);
        assertThat(response.getWorks()).extracting(MineWorkSortItemsResponse.SortWorkItem::getId)
                .containsExactly(12L, 11L);
        assertThat(response.getWorks()).extracting(MineWorkSortItemsResponse.SortWorkItem::getSortOrder)
                .containsExactly(1000, 3000);
        assertThat(response.getWorks().get(0).getCoverUrl())
                .isEqualTo("https://cos.example/WFA3B1E7A2/work/video/film-thumb.jpg");
    }

    @Test
    void updateSortOrdersMapperShouldNotAcceptApplicationUpdatedAt() throws NoSuchMethodException {
        Method method = WorkEntityMapper.class.getMethod("updateSortOrders", Long.class, List.class);

        assertThat(method).isNotNull();
        Update update = method.getAnnotation(Update.class);
        assertThat(String.join(" ", update.value()))
                .doesNotContain("#{updatedAt}")
                .doesNotContain("updated_at =");
    }

    @Test
    void deleteWorkShouldRejectReferencedActiveWork() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("草坪婚礼");
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setId(300L);
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(18L);
        reference.setPortfolioId(200L);
        reference.setIsValid(1);
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(reference));

        assertThatThrownBy(() -> service().deleteWork(18L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品已被 1 个作品集引用，请先从作品集中移除");
        verify(workEntityMapper, never()).delete(any());
        verify(workTagEntityMapper, never()).delete(any());
        verify(cosService, never()).delete(any());
    }

    @Test
    void deleteWorkShouldPersistDeletedAtWhenSoftDeleting() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("草坪婚礼");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workEntityMapper.update(any(), any())).thenReturn(1);

        service().deleteWork(18L);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateWrapper> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(workEntityMapper).update(any(), updateCaptor.capture());
        verify(workTagEntityMapper).delete(any());
        @SuppressWarnings("unchecked")
        UpdateWrapper<WorkEntity> wrapper = updateCaptor.getValue();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertThat(wrapper.getSqlSet()).contains("deleted=#{ew.paramNameValuePairs.MPGENVAL2}");
        assertThat(params.get("MPGENVAL2")).isEqualTo(18L);
        verify(workEntityMapper, never()).delete(any());
    }

    @Test
    void deleteWorkShouldDeleteUniqueCosObjectsAfterDatabaseDelete() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("草坪婚礼");
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workEntityMapper.update(any(), any())).thenReturn(1);

        service().deleteWork(18L);

        verify(workTagEntityMapper).delete(any());
        verify(cosService).delete("WFA3B1E7A2/work/image/photo.jpg");
        verify(cosService).delete("WFA3B1E7A2/work/image/photo-thumb.jpg");
    }

    @Test
    void deleteWorkShouldDeleteSharedMediaAndCoverKeyOnlyOnce() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("草坪婚礼");
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workEntityMapper.update(any(), any())).thenReturn(1);

        service().deleteWork(18L);

        verify(cosService, times(1)).delete("WFA3B1E7A2/work/image/photo.jpg");
    }

    @Test
    void deleteWorkShouldKeepDatabaseDeleteWhenCosDeleteFails() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("草坪婚礼");
        work.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        work.setCoverObjectKey("WFA3B1E7A2/work/video/film-thumb.jpg");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workEntityMapper.update(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("COS 删除失败")).when(cosService).delete("WFA3B1E7A2/work/video/film.mp4");

        service().deleteWork(18L);

        verify(workEntityMapper).update(any(), any());
        verify(workTagEntityMapper).delete(any());
        verify(cosService).delete("WFA3B1E7A2/work/video/film.mp4");
        verify(cosService).delete("WFA3B1E7A2/work/video/film-thumb.jpg");
    }

    @Test
    void checkDeleteWorkShouldReturnReferenceCount() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("草坪婚礼");
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setId(300L);
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(18L);
        reference.setPortfolioId(200L);
        reference.setIsValid(1);
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(reference));

        MineWorkDeleteCheckResponse response = service().checkDeleteWork(18L);

        assertThat(response.isCanDelete()).isFalse();
        assertThat(response.getReferenceCount()).isEqualTo(1);
        assertThat(response.getMessage()).isEqualTo("作品已被 1 个作品集引用，请先从作品集中移除");
    }

    @Test
    void checkDeleteWorksShouldReportDeletableAndBlockedItems() {
        WorkEntity first = ownedWork(11L);
        first.setTitle("可删除作品");
        WorkEntity second = ownedWork(12L);
        second.setTitle("被引用作品");
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                portfolioReference(12L),
                portfolioReference(12L)
        ));
        MineWorkBatchDeleteRequest request = new MineWorkBatchDeleteRequest();
        request.setWorkIds(List.of(11L, 12L));

        MineWorkBatchDeleteCheckResponse response = service().checkDeleteWorks(request);

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getDeletableCount()).isEqualTo(1);
        assertThat(response.getBlockedCount()).isEqualTo(1);
        assertThat(response.getItems()).extracting(MineWorkBatchDeleteCheckResponse.Item::getWorkId)
                .containsExactly(11L, 12L);
        assertThat(response.getItems().get(0).isCanDelete()).isTrue();
        assertThat(response.getItems().get(1).isCanDelete()).isFalse();
        assertThat(response.getItems().get(1).getMessage())
                .isEqualTo("作品已被 2 个作品集引用，请先从作品集中移除");
    }

    @Test
    void deleteWorksShouldKeepReferencedWorksAndDeleteOnlyAllowedItems() {
        WorkEntity first = ownedWork(11L);
        first.setTitle("可删除作品");
        first.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        WorkEntity second = ownedWork(12L);
        second.setTitle("被引用作品");
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(portfolioReference(12L)));
        when(workEntityMapper.update(any(), any())).thenReturn(1);
        MineWorkBatchDeleteRequest request = new MineWorkBatchDeleteRequest();
        request.setWorkIds(List.of(11L, 12L));

        MineWorkBatchDeleteResponse response = service().deleteWorks(request);

        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFailedCount()).isEqualTo(1);
        assertThat(response.getItems()).extracting(MineWorkBatchDeleteResponse.Item::getWorkId)
                .containsExactly(11L, 12L);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
        assertThat(response.getItems().get(1).isSuccess()).isFalse();
        assertThat(response.getItems().get(1).getMessage())
                .isEqualTo("作品已被 1 个作品集引用，请先从作品集中移除");
        verify(workEntityMapper, times(1)).update(any(), any());
        verify(workTagEntityMapper, times(1)).delete(any());
        verify(cosService).delete("WFA3B1E7A2/work/image/photo.jpg");
    }

    private MineWorkService service() {
        return new MineWorkService(
                userEntityMapper,
                workEntityMapper,
                wfTagEntityMapper,
                workTagEntityMapper,
                workUploadTaskEntityMapper,
                portfolioReferenceEntityMapper,
                cosService,
                workUploadTransactionService);
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUniqueCode("WFA3B1E7A2");
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }

    private WorkEntity ownedWork(Long workId) {
        WorkEntity work = new WorkEntity();
        work.setId(workId);
        work.setUserId(7L);
        work.setTitle("作品" + workId);
        return work;
    }

    private WorkTagEntity workTagRelation(Long workId, Long tagId) {
        WorkTagEntity relation = new WorkTagEntity();
        relation.setUserId(7L);
        relation.setWorkId(workId);
        relation.setTagId(tagId);
        return relation;
    }

    private PortfolioReferenceEntity portfolioReference(Long workId) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(workId);
        reference.setIsValid(1);
        return reference;
    }

    private MineWorkSortRequest.Item sortItem(Long workId, Integer sortOrder) {
        MineWorkSortRequest.Item item = new MineWorkSortRequest.Item();
        item.setWorkId(workId);
        item.setSortOrder(sortOrder);
        return item;
    }

    private MineWorkTagUpsertRequest tagRequest(String name, String color) {
        MineWorkTagUpsertRequest request = new MineWorkTagUpsertRequest();
        request.setName(name);
        request.setColor(color);
        return request;
    }

    private WfTagEntity ownedTag(Long tagId, String name, String color) {
        WfTagEntity tag = new WfTagEntity();
        tag.setId(tagId);
        tag.setUserId(7L);
        tag.setName(name);
        tag.setColor(color);
        tag.setStatus(WfTagStatusDict.ACTIVE.getCode());
        return tag;
    }

    private WorkUploadTaskEntity uploadTask(Long taskId, String batchId, String objectKey, String idempotencyKey) {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(taskId);
        task.setBatchId(batchId);
        task.setUserId(7L);
        task.setMediaType(MediaTypeDict.IMAGE.getCode());
        task.setObjectKey(objectKey);
        task.setOriginalFileName("photo.jpg");
        task.setMimeType("image/jpeg");
        task.setFileSize(1024L);
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        task.setIdempotencyKey(idempotencyKey);
        return task;
    }

    private MineWorkUploadTicketRequest.UploadFileItem ticketFile(
            String clientId,
            String mediaType,
            String fileName,
            String mimeType,
            long size
    ) {
        MineWorkUploadTicketRequest.UploadFileItem item = new MineWorkUploadTicketRequest.UploadFileItem();
        item.setClientId(clientId);
        item.setMediaType(mediaType);
        item.setFileName(fileName);
        item.setMimeType(mimeType);
        item.setFileSize(size);
        item.setSha256(defaultSha256(clientId));
        item.setIdempotencyKey("ticket-" + clientId);
        return item;
    }

    private String defaultSha256(String clientId) {
        return String.format("%064x", Integer.toUnsignedLong(clientId.hashCode()));
    }
}
