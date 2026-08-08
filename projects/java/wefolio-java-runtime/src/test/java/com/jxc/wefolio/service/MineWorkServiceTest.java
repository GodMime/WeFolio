package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.WorkAuditProperties;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkAuditReasonCodeDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
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
import com.jxc.wefolio.dto.MineWorkTagUpsertRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketResponse;
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
import com.jxc.wefolio.message.MineWorkMessage;
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
import java.lang.reflect.Field;
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
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** 动图 COS 服务模拟 */
    @Mock
    private AnimationCosService animationCosService;

    /** 上传完成事务服务模拟 */
    @Mock
    private WorkUploadTransactionService workUploadTransactionService;

    /** 内容数量上限服务模拟 */
    @Mock
    private ContentLimitService contentLimitService;

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
    void listWorksShouldBatchLoadSummaryReferencesAndTags() throws ReflectiveOperationException {
        WorkEntity first = ownedWork(11L);
        first.setMediaType(MediaTypeDict.IMAGE.getCode());
        first.setTitle("草坪婚礼");
        first.setOriginalFileName("photo.jpg");
        first.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        first.setCoverObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        first.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        first.setAuditRound(1);
        setField(first, "aspectRatio", "3:2");
        WorkEntity second = ownedWork(12L);
        second.setMediaType(MediaTypeDict.VIDEO.getCode());
        second.setTitle("片头快剪");
        second.setOriginalFileName("film.mp4");
        second.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        second.setAuditStatus(WorkAuditStatusDict.REVIEW_REQUIRED.getCode());
        second.setAuditRound(2);
        second.setAuditReasonCode(WorkAuditReasonCodeDict.PORN_CONTENT.getCode());
        second.setAuditReasonCodes("[\"PORN_CONTENT\",\"ADVERTISING_CONTENT\"]");
        second.setAuditRejectReason("腾讯云判定违规：label=Porn，result=2，score=88");
        WorkEntity third = ownedWork(13L);
        third.setMediaType(MediaTypeDict.ANIMATION.getCode());
        third.setTitle("动图预告");
        third.setOriginalFileName("preview.webp");
        third.setMediaObjectKey("WFA3B1E7A2/work/animation/preview.webp");
        third.setCoverObjectKey("WFA3B1E7A2/work/animation/preview-thumb.jpg");
        third.setFrameCount(120);
        third.setCoverFrameNumber(18);
        third.setAuditStatus(WorkAuditStatusDict.PENDING.getCode());
        third.setAuditRound(1);
        Page<WorkEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(first, second, third));
        page.setTotal(3L);
        when(workEntityMapper.selectPage(any(), any())).thenReturn(page);
        when(workEntityMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("mediaType", MediaTypeDict.IMAGE.getCode(), "itemCount", 1L),
                Map.of("mediaType", MediaTypeDict.VIDEO.getCode(), "itemCount", 1L),
                Map.of("mediaType", MediaTypeDict.ANIMATION.getCode(), "itemCount", 1L),
                Map.of("mediaType", "UNKNOWN", "itemCount", 2L)
        ));
        WfTagEntity ceremonyTag = ownedTag(31L, "户外仪式", "#0f766e");
        WfTagEntity editTag = ownedTag(32L, "快剪", "#2d5f9a");
        when(wfTagEntityMapper.selectList(any())).thenReturn(List.of(ceremonyTag, editTag));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(
                workTagRelation(11L, 31L),
                workTagRelation(12L, 32L)
        ));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                portfolioReference(11L, 201L, PortfolioConfigScopeDict.PUBLISHED.getCode()),
                portfolioReference(12L, 202L, PortfolioConfigScopeDict.PUBLISHED.getCode()),
                portfolioReference(12L, 203L, PortfolioConfigScopeDict.PUBLISHED.getCode())
        ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        MineWorkListResponse response = service().listWorks(null, null, 1, 20);

        assertThat(response.getSummary().getTotalCount()).isEqualTo(5L);
        assertThat(response.getSummary().getImageCount()).isEqualTo(1L);
        assertThat(response.getSummary().getVideoCount()).isEqualTo(1L);
        assertThat(response.getSummary().getAnimationCount()).isEqualTo(1L);
        assertThat(response.getTags()).extracting(MineWorkListResponse.TagItem::getCount)
                .containsExactly(5L, 1L, 1L);
        assertThat(response.getWorks()).hasSize(3);
        assertThat(response.getWorks().get(0).getReferenceCount()).isEqualTo(1L);
        assertThat(response.getWorks().get(0).getAuditStatus()).isEqualTo("PASSED");
        assertThat(response.getWorks().get(0).getAuditStatusText()).isEqualTo("审核通过");
        assertThat(response.getWorks().get(0).getAuditRound()).isEqualTo(1);
        assertThat(response.getWorks().get(0).getMaxAuditRounds()).isEqualTo(3);
        assertThat(response.getWorks().get(0).getRemainingAuditResubmitCount()).isEqualTo(2);
        assertThat(response.getWorks().get(0).isCanResubmitAudit()).isFalse();
        assertThat(response.getWorks().get(0).getAuditRejectReason()).isNull();
        assertThat(readField(response.getWorks().get(0), "aspectRatio")).isEqualTo("3:2");
        assertThat(response.getWorks().get(0).getTags()).extracting(MineWorkListResponse.TagItem::getName)
                .containsExactly("户外仪式");
        assertThat(response.getWorks().get(1).getReferenceCount()).isEqualTo(2L);
        assertThat(response.getWorks().get(1).getAuditStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(response.getWorks().get(1).getAuditStatusText()).isEqualTo("疑似违规");
        assertThat(response.getWorks().get(1).getAuditRound()).isEqualTo(2);
        assertThat(response.getWorks().get(1).getMaxAuditRounds()).isEqualTo(3);
        assertThat(response.getWorks().get(1).getRemainingAuditResubmitCount()).isEqualTo(1);
        assertThat(response.getWorks().get(1).isCanResubmitAudit()).isTrue();
        assertThat(response.getWorks().get(1).getAuditRejectReason())
                .isEqualTo("作品可能包含色情或低俗内容，需要进一步确认，建议调整后重新提交")
                .doesNotContain("腾讯云", "Porn", "result=", "score=");
        assertThat(response.getWorks().get(1).getAuditReasons())
                .extracting(MineWorkListResponse.AuditReasonItem::getCode)
                .containsExactly("PORN_CONTENT", "ADVERTISING_CONTENT");
        assertThat(response.getWorks().get(1).getAuditReasons())
                .extracting(MineWorkListResponse.AuditReasonItem::getMessage)
                .allMatch(message -> message.contains("需要进一步确认"));
        assertThat(response.getWorks().get(1).getTags()).extracting(MineWorkListResponse.TagItem::getName)
                .containsExactly("快剪");
        assertThat(response.getWorks().get(2).getFrameCount()).isEqualTo(120);
        assertThat(response.getWorks().get(2).getCoverFrameNumber()).isEqualTo(18);
        verify(workEntityMapper, never()).selectCount(any());
        verify(workTagEntityMapper, never()).selectCount(any());
        verify(workEntityMapper, times(1)).selectMaps(any());
        verify(workTagEntityMapper, times(1)).selectList(any());
        verify(portfolioReferenceEntityMapper, times(1)).selectList(any());
        verify(wfTagEntityMapper, never()).selectBatchIds(any());
    }

    @Test
    void listWorksShouldCountTagUsageByRequestedMediaType() {
        WorkEntity imageWork = ownedWork(11L);
        imageWork.setMediaType(MediaTypeDict.IMAGE.getCode());
        imageWork.setTitle("草坪婚礼");
        Page<WorkEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(imageWork));
        page.setTotal(1L);
        when(workEntityMapper.selectPage(any(), any())).thenReturn(page);
        when(workEntityMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("mediaType", MediaTypeDict.IMAGE.getCode(), "itemCount", 1L)
        ));
        WfTagEntity ceremonyTag = ownedTag(31L, "户外仪式", "#0f766e");
        WfTagEntity videoTag = ownedTag(32L, "快剪", "#2d5f9a");
        when(wfTagEntityMapper.selectList(any())).thenReturn(List.of(ceremonyTag, videoTag));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(
                workTagRelation(11L, 31L),
                workTagRelation(12L, 32L)
        ));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(imageWork));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        MineWorkListResponse response = service().listWorks(null, null, MediaTypeDict.IMAGE.getCode(), 1, 20);

        assertThat(response.getSummary().getTotalCount()).isEqualTo(1L);
        assertThat(response.getSummary().getImageCount()).isEqualTo(1L);
        assertThat(response.getSummary().getVideoCount()).isZero();
        assertThat(response.getTags()).extracting(MineWorkListResponse.TagItem::getCount)
                .containsExactly(1L, 1L, 0L);
        assertThat(response.getWorks()).extracting(MineWorkListResponse.WorkItem::getMediaType)
                .containsExactly(MediaTypeDict.IMAGE.getCode());
    }

    @Test
    void listWorksShouldCountTagUsageByRequestedAuditStatus() {
        WorkEntity passedWork = ownedWork(11L);
        passedWork.setMediaType(MediaTypeDict.IMAGE.getCode());
        passedWork.setTitle("草坪婚礼");
        passedWork.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        Page<WorkEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(passedWork));
        page.setTotal(1L);
        when(workEntityMapper.selectPage(any(), any())).thenReturn(page);
        when(workEntityMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("mediaType", MediaTypeDict.IMAGE.getCode(), "itemCount", 1L)
        ));
        WfTagEntity passedTag = ownedTag(31L, "户外仪式", "#0f766e");
        WfTagEntity rejectedTag = ownedTag(32L, "违规案例", "#2d5f9a");
        when(wfTagEntityMapper.selectList(any())).thenReturn(List.of(passedTag, rejectedTag));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(
                workTagRelation(11L, 31L),
                workTagRelation(12L, 32L)
        ));
        when(workEntityMapper.selectList(any())).thenReturn(List.of(passedWork));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        MineWorkListResponse response = service().listWorks(
                null,
                null,
                null,
                WorkAuditStatusDict.PASSED.getCode(),
                1,
                20);

        assertThat(response.getSummary().getTotalCount()).isEqualTo(1L);
        assertThat(response.getSummary().getImageCount()).isEqualTo(1L);
        assertThat(response.getSummary().getVideoCount()).isZero();
        assertThat(response.getTags()).extracting(MineWorkListResponse.TagItem::getCount)
                .containsExactly(1L, 1L, 0L);
        assertThat(response.getWorks()).extracting(MineWorkListResponse.WorkItem::getAuditStatus)
                .containsExactly(WorkAuditStatusDict.PASSED.getCode());
    }

    @Test
    void listWorksShouldCountDraftAndPublishedReferencesFromSamePortfolioOnlyOnce() {
        WorkEntity work = ownedWork(11L);
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setTitle("草坪婚礼");
        Page<WorkEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(work));
        page.setTotal(1L);
        when(workEntityMapper.selectPage(any(), any())).thenReturn(page);
        when(workEntityMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("mediaType", MediaTypeDict.IMAGE.getCode(), "itemCount", 1L)
        ));
        when(wfTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                portfolioReference(11L, 200L, PortfolioConfigScopeDict.DRAFT.getCode()),
                portfolioReference(11L, 200L, PortfolioConfigScopeDict.PUBLISHED.getCode())
        ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        MineWorkListResponse response = service().listWorks(null, null, 1, 20);

        assertThat(response.getWorks().get(0).getReferenceCount()).isEqualTo(1L);
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
        assertThat(response.getImageMaxBytes()).isEqualTo(10L * 1024L * 1024L);
        assertThat(response.getVideoMaxBytes()).isEqualTo(100L * 1024L * 1024L);
        assertThat(response.getAnimationMaxBytes()).isEqualTo(10L * 1024L * 1024L);
        verify(contentLimitService).ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 1L);
        verify(contentLimitService).ensureWorkCapacity(7L, MediaTypeDict.VIDEO.getCode(), 1L);
    }

    @Test
    void createUploadTicketsShouldAcceptAnimationGifAndWebpWithIndependentCapacity() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(cosService.createPostUploadTicket(any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(
                ticketFile("animation-gif", MediaTypeDict.ANIMATION.getCode(),
                        "story.gif", "image/gif", 10L * 1024L * 1024L),
                ticketFile("animation-webp", MediaTypeDict.ANIMATION.getCode(),
                        "story.webp", "image/webp", 1024L)));

        MineWorkUploadTicketResponse response = service().createUploadTickets(request);

        ArgumentCaptor<WorkUploadTaskEntity> captor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(WorkUploadTaskEntity::getObjectKey)
                .allMatch(key -> key.matches(
                        "WFA3B1E7A2/work/animation/WFA3B1E7A2-A-\\d{13}-[12]\\.(gif|webp)"));
        assertThat(response.getItems()).extracting(MineWorkUploadTicketResponse.Item::getMaxBytes)
                .containsOnly(10L * 1024L * 1024L);
        verify(contentLimitService)
                .ensureWorkCapacity(7L, MediaTypeDict.ANIMATION.getCode(), 2L);
    }

    @Test
    void createUploadTicketsShouldKeepGifAndWebpCompatibleWithImageFallback() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(cosService.createPostUploadTicket(any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(
                ticketFile("image-gif", MediaTypeDict.IMAGE.getCode(), "still.gif", "image/gif", 1024L),
                ticketFile("image-webp", MediaTypeDict.IMAGE.getCode(), "still.webp", "image/webp", 1024L)));

        MineWorkUploadTicketResponse response = service().createUploadTickets(request);

        assertThat(response.getItems()).extracting(MineWorkUploadTicketResponse.Item::getObjectKey)
                .allMatch(key -> key.contains("/work/image/"));
        verify(cosService).createPostUploadTicket(
                any(), eq("image/gif"), eq(MineWorkService.IMAGE_MAX_BYTES), any());
        verify(cosService).createPostUploadTicket(
                any(), eq("image/webp"), eq(MineWorkService.IMAGE_MAX_BYTES), any());
    }

    @Test
    void createUploadTicketsShouldRejectAnimationMimeMismatchAndEmptyFile() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineWorkUploadTicketRequest.UploadFileItem wrongMime = ticketFile(
                "wrong-mime", MediaTypeDict.ANIMATION.getCode(), "story.gif", "image/webp", 1024L);
        MineWorkUploadTicketRequest wrongMimeRequest = new MineWorkUploadTicketRequest();
        wrongMimeRequest.setFiles(List.of(wrongMime));

        assertThatThrownBy(() -> service().createUploadTickets(wrongMimeRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage("动图文件格式不支持");

        MineWorkUploadTicketRequest.UploadFileItem empty = ticketFile(
                "empty", MediaTypeDict.ANIMATION.getCode(), "story.gif", "image/gif", 0L);
        MineWorkUploadTicketRequest emptyRequest = new MineWorkUploadTicketRequest();
        emptyRequest.setFiles(List.of(empty));
        assertThatThrownBy(() -> service().createUploadTickets(emptyRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage("上传文件不能为空");
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
    }

    @Test
    void createUploadTicketsShouldRejectWholeBatchBeforeTaskCreationWhenImageLimitExceeded() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineWorkUploadTicketRequest.UploadFileItem first = ticketFile(
                "client-1", MediaTypeDict.IMAGE.getCode(), "photo-1.jpg", "image/jpeg", 1024L);
        MineWorkUploadTicketRequest.UploadFileItem second = ticketFile(
                "client-2", MediaTypeDict.IMAGE.getCode(), "photo-2.jpg", "image/jpeg", 2048L);
        MineWorkUploadTicketRequest request = new MineWorkUploadTicketRequest();
        request.setFiles(List.of(first, second));
        doThrow(new BusinessException("图片作品数量已达上限（500个），请删除部分图片作品后再上传"))
                .when(contentLimitService)
                .ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 2L);

        assertThatThrownBy(() -> service().createUploadTickets(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片作品数量已达上限（500个），请删除部分图片作品后再上传");

        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
        verify(cosService, never()).createPostUploadTicket(any(), any(), anyLong(), any());
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
        verifyNoInteractions(contentLimitService);
    }

    @Test
    void createCoverUploadTicketShouldCreateDerivedVideoCoverTask() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/video/WFA3B1E7A2-V-1782807167829-3.mp4");
        work.setOriginalFileName("film.mp4");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(cosService.createPostUploadTicket(
                any(String.class),
                eq("image/jpeg"),
                eq(100L * 1024L),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        when(workUploadTaskEntityMapper.insert(any(WorkUploadTaskEntity.class))).thenAnswer(invocation -> {
            WorkUploadTaskEntity task = invocation.getArgument(0);
            task.setId(301L);
            return 1;
        });
        MineWorkCoverUploadTicketRequest request = new MineWorkCoverUploadTicketRequest();
        request.setClientId("edit-cover-18");
        request.setFileName("cover.jpg");
        request.setMimeType("image/jpeg");
        request.setFileSize(90_000L);
        request.setSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        request.setWidth(640);
        request.setHeight(360);
        request.setIdempotencyKey("cover-ticket-18");

        MineWorkCoverUploadTicketResponse response = service().createCoverUploadTicket(18L, request);

        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper).insert(taskCaptor.capture());
        WorkUploadTaskEntity task = taskCaptor.getValue();
        assertThat(task.getUserId()).isEqualTo(7L);
        assertThat(task.getMediaType()).isEqualTo(MediaTypeDict.IMAGE.getCode());
        assertThat(task.getObjectKey())
                .startsWith("WFA3B1E7A2/work/video/WFA3B1E7A2-V-1782807167829-3-thumb-")
                .endsWith(".jpg");
        assertThat(task.getOriginalFileName()).isEqualTo("film-thumb.jpg");
        assertThat(task.getFileSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(task.getFileSize()).isEqualTo(90_000L);
        assertThat(task.getWidth()).isEqualTo(640);
        assertThat(task.getHeight()).isEqualTo(360);
        assertThat(response.getTaskId()).isEqualTo(301L);
        assertThat(response.getClientId()).isEqualTo("edit-cover-18");
        assertThat(response.getMaxBytes()).isEqualTo(100L * 1024L);
        assertThat(response.getObjectKey()).isEqualTo(task.getObjectKey());
    }

    @Test
    void createThumbnailUploadTicketShouldCreateVersionedKeyWhenImageHasIndependentCover() {
        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setUserId(7L);
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setOriginalFileName("photo.jpg");
        when(workEntityMapper.selectById(17L)).thenReturn(work);
        when(cosService.createPostUploadTicket(
                any(String.class),
                eq("image/jpeg"),
                eq(100L * 1024L),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        when(workUploadTaskEntityMapper.insert(any(WorkUploadTaskEntity.class))).thenAnswer(invocation -> {
            WorkUploadTaskEntity task = invocation.getArgument(0);
            task.setId(401L);
            return 1;
        });
        MineWorkThumbnailUploadTicketRequest request = thumbnailRequest();

        MineWorkThumbnailUploadTicketResponse response = service().createThumbnailUploadTicket(17L, request);

        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper).insert(taskCaptor.capture());
        WorkUploadTaskEntity task = taskCaptor.getValue();
        assertThat(task.getBatchId()).isEqualTo("edit-thumbnail-17");
        assertThat(task.getMediaType()).isEqualTo(MediaTypeDict.IMAGE.getCode());
        assertThat(task.getObjectKey())
                .matches("WFA3B1E7A2/work/image/photo-thumb-\\d+-[0-9a-f]{32}\\.jpg")
                .isNotEqualTo("WFA3B1E7A2/work/image/photo-thumb.jpg");
        assertThat(task.getCoverObjectKey()).isEqualTo(task.getObjectKey());
        assertThat(task.getOriginalFileName()).isEqualTo("photo-thumb.jpg");
        assertThat(task.getFileSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(task.getFileSize()).isEqualTo(90_000L);
        assertThat(task.getWidth()).isEqualTo(960);
        assertThat(task.getHeight()).isEqualTo(540);
        assertThat(response.getTaskId()).isEqualTo(401L);
        assertThat(response.getClientId()).isEqualTo("edit-work-17-thumbnail");
        assertThat(response.getMaxBytes()).isEqualTo(100L * 1024L);
        assertThat(response.getObjectKey()).isEqualTo(task.getObjectKey());
    }

    @Test
    void createThumbnailUploadTicketShouldDeriveThumbKeyWhenLegacyCoverIsOriginalKey() {
        String mediaObjectKey = "WFA3B1E7A2/work/image/photo.jpg";
        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setUserId(7L);
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey(mediaObjectKey);
        work.setCoverObjectKey(mediaObjectKey);
        work.setOriginalFileName("photo.jpg");
        when(workEntityMapper.selectById(17L)).thenReturn(work);
        when(cosService.createPostUploadTicket(
                any(String.class),
                eq("image/jpeg"),
                eq(100L * 1024L),
                any(LocalDateTime.class)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://bucket.cos.ap-guangzhou.myqcloud.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.util.Map.of("key", invocation.getArgument(0))));
        when(workUploadTaskEntityMapper.insert(any(WorkUploadTaskEntity.class))).thenAnswer(invocation -> {
            WorkUploadTaskEntity task = invocation.getArgument(0);
            task.setId(402L);
            return 1;
        });
        MineWorkThumbnailUploadTicketRequest request = thumbnailRequest();

        MineWorkThumbnailUploadTicketResponse response = service().createThumbnailUploadTicket(17L, request);

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cosService).createPostUploadTicket(
                objectKeyCaptor.capture(),
                eq("image/jpeg"),
                eq(100L * 1024L),
                any(LocalDateTime.class));
        verify(cosService, never()).createPostUploadTicket(
                eq(mediaObjectKey),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class));
        assertThat(objectKeyCaptor.getValue())
                .matches("WFA3B1E7A2/work/image/photo-thumb-\\d+-[0-9a-f]{32}\\.jpg")
                .isNotEqualTo(mediaObjectKey);
        assertThat(response.getObjectKey()).isEqualTo(objectKeyCaptor.getValue());
    }

    @Test
    void createThumbnailUploadTicketShouldRejectVideoWork() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/video/film.mp4");
        work.setOriginalFileName("film.mp4");
        when(workEntityMapper.selectById(18L)).thenReturn(work);

        assertThatThrownBy(() -> service().createThumbnailUploadTicket(18L, thumbnailRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.IMAGE_THUMBNAIL_UPDATE_MEDIA_TYPE_MESSAGE);
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
        verify(cosService, never()).createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class));
    }

    @Test
    void createThumbnailUploadTicketShouldRejectInvalidDimensions() {
        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setUserId(7L);
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setOriginalFileName("photo.jpg");
        when(workEntityMapper.selectById(17L)).thenReturn(work);

        MineWorkThumbnailUploadTicketRequest missingWidth = thumbnailRequest();
        missingWidth.setWidth(null);
        MineWorkThumbnailUploadTicketRequest zeroHeight = thumbnailRequest();
        zeroHeight.setHeight(0);
        MineWorkThumbnailUploadTicketRequest tooLargeWidth = thumbnailRequest();
        tooLargeWidth.setWidth(10_001);

        for (MineWorkThumbnailUploadTicketRequest request : List.of(missingWidth, zeroHeight, tooLargeWidth)) {
            assertThatThrownBy(() -> service().createThumbnailUploadTicket(17L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineWorkMessage.COVER_TASK_DIMENSION_MESSAGE);
        }
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
        verify(cosService, never()).createPostUploadTicket(
                any(String.class),
                any(String.class),
                anyLong(),
                any(LocalDateTime.class));
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
    void completeUploadShouldPersistAnimationMetadataAndGeneratedCoverBeforeConfirm() {
        WorkUploadTaskEntity task = animationTask(99L, "story.gif", "image/gif");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(workUploadTaskEntityMapper.updateById(task)).thenReturn(1);
        when(cosService.headObject(task.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/gif", task.getFileSize()));
        when(animationCosService.inspect(task.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("GIF", 720, 1280, 24));
        when(animationCosService.generateCover(eq(task.getObjectKey()), any(String.class), eq(1)))
                .thenAnswer(invocation -> new AnimationCosService.GeneratedFrame(
                        invocation.getArgument(1),
                        "image/jpeg",
                        8192L,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(task), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem(99L);
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(task.getFrameCount()).isEqualTo(24);
        assertThat(task.getWidth()).isEqualTo(720);
        assertThat(task.getHeight()).isEqualTo(1280);
        assertThat(task.getCoverObjectKey())
                .isEqualTo("WFA3B1E7A2/work/animation/story-thumb.jpg");
        assertThat(task.getCoverSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        verify(workUploadTaskEntityMapper).updateById(task);
        verify(workUploadTransactionService).confirmUploadedTask(7L, task, item);
        assertThat(response.getItems().get(0).isSuccess()).isTrue();
    }

    @Test
    void completeUploadShouldReuseWinnerCoverWhenAnimationPreparationLosesRace() {
        WorkUploadTaskEntity initialTask = animationTask(99L, "story.gif", "image/gif");
        WorkUploadTaskEntity winnerTask = animationTask(99L, "story.gif", "image/gif");
        winnerTask.setFrameCount(24);
        winnerTask.setWidth(720);
        winnerTask.setHeight(1280);
        winnerTask.setCoverObjectKey("WFA3B1E7A2/work/animation/story-thumb.jpg");
        winnerTask.setCoverSha256(
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(initialTask, winnerTask);
        when(workUploadTaskEntityMapper.updateById(initialTask)).thenReturn(0);
        when(cosService.headObject(initialTask.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/gif", initialTask.getFileSize()));
        when(animationCosService.inspect(initialTask.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("gif", 720, 1280, 24));
        AtomicReference<String> generatedCoverKey = new AtomicReference<>();
        when(animationCosService.generateCover(eq(initialTask.getObjectKey()), any(String.class), eq(1)))
                .thenAnswer(invocation -> {
                    generatedCoverKey.set(invocation.getArgument(1));
                    return new AnimationCosService.GeneratedFrame(
                            generatedCoverKey.get(),
                            "image/jpeg",
                            8192L,
                            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
                });
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(initialTask), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isTrue();
        assertThat(initialTask.getCoverObjectKey()).isEqualTo(winnerTask.getCoverObjectKey());
        assertThat(initialTask.getCoverSha256()).isEqualTo(winnerTask.getCoverSha256());
        verify(animationCosService, never()).deleteQuietly(
                generatedCoverKey.get(), "persist-animation-cover-race");
        verify(animationCosService, never()).deleteQuietly(
                initialTask.getObjectKey(), "confirm-business-failed-source");
    }

    @Test
    void completeUploadShouldNeverDeleteConfirmedAnimationObjectsWhenIdempotentRetryFails() {
        WorkUploadTaskEntity task = animationTask(99L, "story.gif", "image/gif");
        task.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        task.setConfirmedWorkId(120L);
        task.setFrameCount(24);
        task.setCoverObjectKey("WFA3B1E7A2/work/animation/story-thumb.jpg");
        task.setCoverSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(cosService.headObject(task.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/gif", task.getFileSize()));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(task), any()))
                .thenThrow(new BusinessException("确认重试暂时失败"));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("确认重试暂时失败");
        verify(animationCosService, never()).deleteQuietly(any(), any());
    }

    @Test
    void completeUploadShouldNotDeleteObjectsWhenConcurrentConfirmationHasCommitted() {
        WorkUploadTaskEntity initialTask = animationTask(99L, "story.gif", "image/gif");
        WorkUploadTaskEntity confirmedTask = animationTask(99L, "story.gif", "image/gif");
        confirmedTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        confirmedTask.setConfirmedWorkId(120L);
        confirmedTask.setFrameCount(24);
        confirmedTask.setCoverObjectKey("WFA3B1E7A2/work/animation/story-thumb.jpg");
        confirmedTask.setCoverSha256(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        when(workUploadTaskEntityMapper.selectById(99L))
                .thenReturn(initialTask, confirmedTask, confirmedTask);
        when(workUploadTaskEntityMapper.updateById(initialTask)).thenReturn(1);
        when(cosService.headObject(initialTask.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/gif", initialTask.getFileSize()));
        when(animationCosService.inspect(initialTask.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("gif", 720, 1280, 24));
        when(animationCosService.generateCover(
                initialTask.getObjectKey(),
                "WFA3B1E7A2/work/animation/story-thumb.jpg",
                1))
                .thenReturn(new AnimationCosService.GeneratedFrame(
                        "WFA3B1E7A2/work/animation/story-thumb.jpg",
                        "image/jpeg",
                        8192L,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(initialTask), any()))
                .thenThrow(new BusinessException("并发确认已由其它请求完成"));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        verify(animationCosService, never()).deleteQuietly(any(), any());
    }

    @Test
    void completeUploadShouldKeepAnimationObjectsWhenFailureDoesNotProveFileInvalid() {
        WorkUploadTaskEntity task = animationTask(99L, "story.gif", "image/gif");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(workUploadTaskEntityMapper.updateById(task)).thenReturn(1);
        when(cosService.headObject(task.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/gif", task.getFileSize()));
        when(animationCosService.inspect(task.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("gif", 720, 1280, 24));
        when(animationCosService.generateCover(
                task.getObjectKey(),
                "WFA3B1E7A2/work/animation/story-thumb.jpg",
                1))
                .thenReturn(new AnimationCosService.GeneratedFrame(
                        "WFA3B1E7A2/work/animation/story-thumb.jpg",
                        "image/jpeg",
                        8192L,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(task), any()))
                .thenThrow(new BusinessException("动图作品最多保留 100 个"));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("动图作品最多保留 100 个");
        verify(animationCosService, never()).deleteQuietly(any(), any());
    }

    @Test
    void completeUploadShouldReturnSingleFrameErrorCodeAndCleanAnimationObject() {
        WorkUploadTaskEntity task = animationTask(99L, "story.webp", "image/webp");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(cosService.headObject(task.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/webp", task.getFileSize()));
        when(animationCosService.inspect(task.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("webp", 720, 1280, 1));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getErrorCode()).isEqualTo("ANIMATION_SINGLE_FRAME");
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("该文件只有 1 帧，将按图片重新上传");
        verify(animationCosService).deleteQuietly(task.getObjectKey(), "single-frame-source");
        verify(animationCosService, never()).generateCover(any(), any(), eq(1));
        verifyNoInteractions(workUploadTransactionService);
    }

    @Test
    void completeUploadShouldRejectDynamicGifUploadedAsImageAndCleanObject() {
        WorkUploadTaskEntity task = uploadTask(
                99L,
                "batch-a",
                "WFA3B1E7A2/work/image/story.gif",
                "ticket-story");
        task.setOriginalFileName("story.gif");
        task.setMimeType("image/gif");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(cosService.headObject(task.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/gif", task.getFileSize()));
        when(animationCosService.inspect(task.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("gif", 640, 480, 2));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getErrorCode()).isNull();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("检测到动态图片，请使用动图作品上传");
        verify(animationCosService).deleteQuietly(task.getObjectKey(), "image-animation-type-invalid");
        verifyNoInteractions(workUploadTransactionService);
    }

    @Test
    void completeUploadShouldAcceptLegacySingleFrameWebpImageWithDefaultJpegMime() {
        WorkUploadTaskEntity task = uploadTask(
                99L,
                "batch-a",
                "WFA3B1E7A2/work/image/story.webp",
                "ticket-story");
        task.setOriginalFileName("story.webp");
        task.setMimeType("image/jpeg");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(task);
        when(cosService.headObject(task.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/jpeg", task.getFileSize()));
        when(animationCosService.inspect(task.getObjectKey()))
                .thenReturn(new AnimationCosService.AnimationMetadata("webp", 640, 480, 1));
        WorkEntity work = new WorkEntity();
        work.setId(120L);
        when(workUploadTransactionService.confirmUploadedTask(eq(7L), eq(task), any()))
                .thenReturn(MineWorkUploadCompleteResponse.Item.success(99L, work, "上传成功"));
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(completeItem(99L)));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems().get(0).isSuccess()).isTrue();
        verify(animationCosService, never()).deleteQuietly(any(), any());
        verify(workUploadTransactionService).confirmUploadedTask(eq(7L), eq(task), any());
    }

    @Test
    void completeUploadShouldNotMarkForeignTaskFailedWhenOwnershipCheckFails() {
        WorkUploadTaskEntity foreignTask = uploadTask(
                404L,
                "foreign-batch",
                "WFA9C9E9A0/work/image/foreign.jpg",
                "foreign-ticket");
        foreignTask.setUserId(8L);
        when(workUploadTaskEntityMapper.selectById(404L)).thenReturn(foreignTask);
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(404L);
        MineWorkUploadCompleteRequest request = new MineWorkUploadCompleteRequest();
        request.setItems(List.of(item));

        MineWorkUploadCompleteResponse response = service().completeUpload(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).isSuccess()).isFalse();
        assertThat(response.getItems().get(0).getMessage()).isEqualTo("上传任务不存在");
        verify(workUploadTaskEntityMapper, never()).updateById(any(WorkUploadTaskEntity.class));
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
    void updateWorkShouldKeepTagsWhenTagIdsAreOmitted() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setOriginalFileName("photo.jpg");
        WorkTagEntity firstRelation = workTagRelation(18L, 1L);
        WorkTagEntity secondRelation = workTagRelation(18L, 2L);
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(firstRelation, secondRelation));
        when(wfTagEntityMapper.selectBatchIds(any())).thenReturn(List.of(
                ownedTag(1L, "婚礼", "#0f766e"),
                ownedTag(2L, "晚宴", "#2d5f9a")));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");

        MineWorkDetailResponse response = service().updateWork(18L, request);

        assertThat(response.getWork().getTags()).extracting(MineWorkListResponse.TagItem::getId)
                .containsExactly(1L, 2L);
        verify(wfTagEntityMapper, never()).selectById(anyLong());
        verify(workTagEntityMapper, never()).delete(any());
        verify(workTagEntityMapper, never()).insert(any(WorkTagEntity.class));
    }

    /**
     * 更新作品标签 — 未被引用的作品允许新增和删除标签绑定。
     */
    @Test
    void updateWorkShouldReplaceTagsWhenWorkIsNotReferenced() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setOriginalFileName("photo.jpg");
        WorkTagEntity oldRelation = workTagRelation(18L, 1L);
        WorkTagEntity keptRelation = workTagRelation(18L, 2L);
        WorkTagEntity addedRelation = workTagRelation(18L, 3L);
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(wfTagEntityMapper.selectById(2L)).thenReturn(ownedTag(2L, "婚礼", "#0f766e"));
        when(wfTagEntityMapper.selectById(3L)).thenReturn(ownedTag(3L, "晚宴", "#2d5f9a"));
        when(workTagEntityMapper.selectList(any())).thenReturn(
                List.of(oldRelation, keptRelation),
                List.of(),
                List.of(keptRelation, addedRelation));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(wfTagEntityMapper.selectBatchIds(any())).thenReturn(List.of(
                ownedTag(2L, "婚礼", "#0f766e"),
                ownedTag(3L, "晚宴", "#2d5f9a")));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");
        request.setTagIds(List.of(2L, 3L));

        service().updateWork(18L, request);

        verify(workTagEntityMapper).delete(any());
        verify(workTagEntityMapper).insert(any(WorkTagEntity.class));
    }

    /**
     * 更新作品标签 — 已被引用的作品允许保留旧标签并新增标签。
     */
    @Test
    void updateWorkShouldAllowAddingTagsWhenWorkIsReferenced() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setOriginalFileName("photo.jpg");
        WorkTagEntity keptRelation = workTagRelation(18L, 1L);
        WorkTagEntity addedRelation = workTagRelation(18L, 2L);
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(wfTagEntityMapper.selectById(1L)).thenReturn(ownedTag(1L, "婚礼", "#0f766e"));
        when(wfTagEntityMapper.selectById(2L)).thenReturn(ownedTag(2L, "晚宴", "#2d5f9a"));
        when(workTagEntityMapper.selectList(any())).thenReturn(
                List.of(keptRelation),
                List.of(),
                List.of(keptRelation, addedRelation));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(
                List.of(portfolioReference(18L, 201L, PortfolioConfigScopeDict.PUBLISHED.getCode())));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(wfTagEntityMapper.selectBatchIds(any())).thenReturn(List.of(
                ownedTag(1L, "婚礼", "#0f766e"),
                ownedTag(2L, "晚宴", "#2d5f9a")));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");
        request.setTagIds(List.of(1L, 2L));

        service().updateWork(18L, request);

        verify(workTagEntityMapper, never()).delete(any());
        verify(workTagEntityMapper).insert(any(WorkTagEntity.class));
    }

    /**
     * 更新作品标签 — 已被引用的作品不能删除任何已有标签绑定。
     */
    @Test
    void updateWorkShouldRejectRemovingTagsWhenWorkIsReferenced() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        WorkTagEntity firstRelation = workTagRelation(18L, 1L);
        WorkTagEntity secondRelation = workTagRelation(18L, 2L);
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(wfTagEntityMapper.selectById(2L)).thenReturn(ownedTag(2L, "晚宴", "#2d5f9a"));
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of(firstRelation, secondRelation));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(
                List.of(portfolioReference(18L, 201L, PortfolioConfigScopeDict.PUBLISHED.getCode())));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");
        request.setTagIds(List.of(2L));

        assertThatThrownBy(() -> service().updateWork(18L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.WORK_TAG_REMOVE_REFERENCED_MESSAGE);

        verify(workTagEntityMapper, never()).delete(any());
        verify(workTagEntityMapper, never()).insert(any(WorkTagEntity.class));
        verify(workEntityMapper, never()).updateById(any(WorkEntity.class));
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
    void updateWorkShouldReplaceVideoCoverFromUploadedCoverTaskAndDeleteOldCoverObject() {
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
        WorkUploadTaskEntity coverTask = uploadTask(
                301L,
                "edit-cover-18",
                "WFA3B1E7A2/work/video/film-thumb-1782807167829.jpg",
                "cover-ticket-18");
        coverTask.setOriginalFileName("film-thumb.jpg");
        coverTask.setFileSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        coverTask.setFileSize(90_000L);
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(workUploadTaskEntityMapper.selectById(301L)).thenReturn(coverTask);
        when(cosService.headObject("WFA3B1E7A2/work/video/film-thumb-1782807167829.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 90_000L));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");
        request.setCoverTaskId(301L);

        service().updateWork(18L, request);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workEntityMapper).updateById(workCaptor.capture());
        verify(workUploadTaskEntityMapper).updateById(taskCaptor.capture());
        verify(cosService).delete("WFA3B1E7A2/work/video/old-thumb.jpg");
        WorkEntity updatedWork = workCaptor.getValue();
        assertThat(updatedWork.getTitle()).isEqualTo("新标题");
        assertThat(updatedWork.getDescription()).isEqualTo("新说明");
        assertThat(updatedWork.getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/video/film-thumb-1782807167829.jpg");
        assertThat(updatedWork.getCoverSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        WorkUploadTaskEntity updatedTask = taskCaptor.getValue();
        assertThat(updatedTask.getStatus()).isEqualTo(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        assertThat(updatedTask.getConfirmedWorkId()).isEqualTo(18L);
    }

    @Test
    void updateWorkShouldReplaceImageThumbnailAndDeleteOldIndependentCover() {
        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        work.setCoverObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        work.setCoverSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        work.setOriginalFileName("photo.jpg");
        WorkUploadTaskEntity thumbnailTask = uploadTask(
                401L,
                "edit-thumbnail-17",
                "WFA3B1E7A2/work/image/photo-thumb-1783651200000.jpg",
                "thumbnail-ticket-17");
        thumbnailTask.setOriginalFileName("photo-thumb.jpg");
        thumbnailTask.setFileSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        thumbnailTask.setFileSize(90_000L);
        when(workEntityMapper.selectById(17L)).thenReturn(work, work);
        when(workUploadTaskEntityMapper.selectById(401L)).thenReturn(thumbnailTask);
        when(cosService.headObject("WFA3B1E7A2/work/image/photo-thumb-1783651200000.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 90_000L));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle(" 新标题 ");
        request.setDescription(" 新说明 ");
        request.setThumbnailTaskId(401L);

        service().updateWork(17L, request);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workEntityMapper).updateById(workCaptor.capture());
        verify(workUploadTaskEntityMapper).updateById(taskCaptor.capture());
        verify(cosService).delete("WFA3B1E7A2/work/image/photo-thumb.jpg");
        WorkEntity updatedWork = workCaptor.getValue();
        assertThat(updatedWork.getTitle()).isEqualTo("新标题");
        assertThat(updatedWork.getDescription()).isEqualTo("新说明");
        assertThat(updatedWork.getCoverObjectKey())
                .isEqualTo("WFA3B1E7A2/work/image/photo-thumb-1783651200000.jpg");
        assertThat(updatedWork.getCoverSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        WorkUploadTaskEntity updatedTask = taskCaptor.getValue();
        assertThat(updatedTask.getStatus()).isEqualTo(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        assertThat(updatedTask.getConfirmedWorkId()).isEqualTo(17L);
    }

    /**
     * 图片作品旧封面直接使用原图时，替换缩略图后必须保留原图对象。
     */
    @Test
    void updateWorkShouldKeepOriginalImageWhenLegacyCoverPointsToMediaObject() {
        String mediaObjectKey = "WFA3B1E7A2/work/image/photo.jpg";
        String newCoverObjectKey = "WFA3B1E7A2/work/image/photo-thumb-1783651200000.jpg";
        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey(mediaObjectKey);
        work.setCoverObjectKey(mediaObjectKey);
        work.setCoverSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        work.setOriginalFileName("photo.jpg");
        WorkUploadTaskEntity thumbnailTask = uploadTask(
                401L,
                "edit-thumbnail-17",
                newCoverObjectKey,
                "thumbnail-ticket-17");
        thumbnailTask.setOriginalFileName("photo-thumb.jpg");
        thumbnailTask.setFileSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        thumbnailTask.setFileSize(90_000L);
        when(workEntityMapper.selectById(17L)).thenReturn(work, work);
        when(workUploadTaskEntityMapper.selectById(401L)).thenReturn(thumbnailTask);
        when(cosService.headObject(newCoverObjectKey))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 90_000L));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle("新标题");
        request.setDescription("新说明");
        request.setThumbnailTaskId(401L);

        service().updateWork(17L, request);

        verify(cosService, never()).delete(mediaObjectKey);
        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).updateById(workCaptor.capture());
        assertThat(workCaptor.getValue().getCoverObjectKey()).isEqualTo(newCoverObjectKey);
    }

    @Test
    void updateWorkShouldRejectMultipleCoverEditModes() {
        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setUserId(7L);
        work.setTitle("旧标题");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        when(workEntityMapper.selectById(17L)).thenReturn(work);
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle("新标题");
        request.setCoverTaskId(301L);
        request.setThumbnailTaskId(401L);

        assertThatThrownBy(() -> service().updateWork(17L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.COVER_EDIT_MODE_CONFLICT_MESSAGE);
        verify(workEntityMapper, never()).updateById(any(WorkEntity.class));
    }

    @Test
    void updateWorkShouldRequireAnimationCoverFieldsAsPairAndRejectNonAnimationWork() {
        WorkEntity animation = editableAnimationWork();
        when(workEntityMapper.selectById(18L)).thenReturn(animation);
        MineWorkUpdateRequest missingKey = new MineWorkUpdateRequest();
        missingKey.setTitle("新标题");
        missingKey.setCoverFrameNumber(5);

        assertThatThrownBy(() -> service().updateWork(18L, missingKey))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.ANIMATION_COVER_FIELDS_REQUIRED_MESSAGE);

        WorkEntity image = ownedWork(19L);
        image.setMediaType(MediaTypeDict.IMAGE.getCode());
        image.setTitle("图片");
        when(workEntityMapper.selectById(19L)).thenReturn(image);
        MineWorkUpdateRequest wrongType = new MineWorkUpdateRequest();
        wrongType.setTitle("图片");
        wrongType.setCoverFrameNumber(1);
        wrongType.setCoverFrameIdempotencyKey("session-image");

        assertThatThrownBy(() -> service().updateWork(19L, wrongType))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.ANIMATION_COVER_UPDATE_MEDIA_TYPE_MESSAGE);
        verify(animationCosService, never()).generateCover(any(), any(), any(Integer.class));
    }

    @Test
    void updateWorkShouldRejectAnimationCoverFrameOutsideKnownRange() {
        WorkEntity work = editableAnimationWork();
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        MineWorkUpdateRequest zero = animationCoverUpdateRequest(0, "session-zero");
        MineWorkUpdateRequest tooLarge = animationCoverUpdateRequest(25, "session-large");

        assertThatThrownBy(() -> service().updateWork(18L, zero))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.ANIMATION_COVER_FRAME_INVALID_MESSAGE);
        assertThatThrownBy(() -> service().updateWork(18L, tooLarge))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.ANIMATION_COVER_FRAME_INVALID_MESSAGE);
        verify(animationCosService, never()).generateCover(any(), any(), any(Integer.class));
    }

    @Test
    void updateWorkShouldGenerateVersionedAnimationCoverAndDeleteOnlyOldCover() {
        WorkEntity work = editableAnimationWork();
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(animationCosService.generateCover(
                eq(work.getMediaObjectKey()),
                any(String.class),
                eq(5)))
                .thenAnswer(invocation -> new AnimationCosService.GeneratedFrame(
                        invocation.getArgument(1),
                        "image/jpeg",
                        8192L,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        service().updateWork(18L, animationCoverUpdateRequest(5, "session-a"));

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).updateById(workCaptor.capture());
        assertThat(workCaptor.getValue().getCoverObjectKey())
                .matches("WFA3B1E7A2/work/animation/story-thumb-\\d{13}-[0-9a-f]{32}\\.jpg");
        assertThat(workCaptor.getValue().getCoverFrameNumber()).isEqualTo(5);
        assertThat(workCaptor.getValue().getCoverSha256())
                .isEqualTo("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        verify(cosService).delete("WFA3B1E7A2/work/animation/story-thumb.jpg");
        verify(cosService, never()).delete(work.getMediaObjectKey());
    }

    @Test
    void updateWorkShouldReuseAnimationCoverForSameSessionKey() {
        WorkEntity work = editableAnimationWork();
        WorkUploadTaskEntity coverTask = uploadTask(
                501L,
                "edit-animation-cover-18-5",
                "WFA3B1E7A2/work/animation/story-thumb-1783651200000.jpg",
                "WORK_ANIMATION_COVER:18:session-a");
        coverTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        coverTask.setConfirmedWorkId(18L);
        coverTask.setFileSha256("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        when(workEntityMapper.selectById(18L)).thenReturn(work, work);
        when(workUploadTaskEntityMapper.selectOne(any())).thenReturn(coverTask);
        when(cosService.headObject(coverTask.getObjectKey()))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 8192L));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        service().updateWork(18L, animationCoverUpdateRequest(5, "session-a"));

        verify(animationCosService, never()).generateCover(any(), any(), any(Integer.class));
        verify(workUploadTaskEntityMapper, never()).insert(any(WorkUploadTaskEntity.class));
        ArgumentCaptor<WorkEntity> captor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).updateById(captor.capture());
        assertThat(captor.getValue().getCoverObjectKey()).isEqualTo(coverTask.getObjectKey());
    }

    @Test
    void updateWorkShouldRejectReusingAnimationCoverSessionKeyForDifferentFrame() {
        WorkEntity work = editableAnimationWork();
        WorkUploadTaskEntity coverTask = uploadTask(
                501L,
                "edit-animation-cover-18-5",
                "WFA3B1E7A2/work/animation/story-thumb-1783651200000.jpg",
                "WORK_ANIMATION_COVER:18:session-a");
        coverTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        coverTask.setConfirmedWorkId(18L);
        coverTask.setFileSha256("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(workUploadTaskEntityMapper.selectOne(any())).thenReturn(coverTask);

        assertThatThrownBy(() -> service().updateWork(
                18L,
                animationCoverUpdateRequest(6, "session-a")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.ANIMATION_COVER_GENERATE_FAILED_MESSAGE);

        verify(workEntityMapper, never()).updateById(any(WorkEntity.class));
        verify(animationCosService, never()).generateCover(any(), any(), any(Integer.class));
    }

    @Test
    void updateWorkShouldGenerateDifferentAnimationCoverKeysForNewSessionsOnSameFrame() {
        WorkEntity first = editableAnimationWork();
        WorkEntity second = editableAnimationWork();
        when(workEntityMapper.selectById(18L)).thenReturn(first, first, second, second);
        when(animationCosService.generateCover(eq(first.getMediaObjectKey()), any(), eq(5)))
                .thenAnswer(invocation -> new AnimationCosService.GeneratedFrame(
                        invocation.getArgument(1),
                        "image/jpeg",
                        8192L,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(1);
        when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class))).thenReturn(1);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workTagEntityMapper.selectList(any())).thenReturn(List.of());
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example/" + invocation.getArgument(0));

        service().updateWork(18L, animationCoverUpdateRequest(5, "session-a"));
        service().updateWork(18L, animationCoverUpdateRequest(5, "session-b"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(animationCosService, times(2)).generateCover(
                eq(first.getMediaObjectKey()),
                keyCaptor.capture(),
                eq(5));
        assertThat(keyCaptor.getAllValues()).hasSize(2);
        assertThat(keyCaptor.getAllValues().get(0)).isNotEqualTo(keyCaptor.getAllValues().get(1));
    }

    @Test
    void updateWorkShouldCleanNewAnimationCoverAndKeepOldCoverWhenSaveFails() {
        WorkEntity work = editableAnimationWork();
        AtomicReference<String> generatedKey = new AtomicReference<>();
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(animationCosService.generateCover(eq(work.getMediaObjectKey()), any(), eq(5)))
                .thenAnswer(invocation -> {
                    generatedKey.set(invocation.getArgument(1));
                    return new AnimationCosService.GeneratedFrame(
                            generatedKey.get(),
                            "image/jpeg",
                            8192L,
                            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
                });
        when(workEntityMapper.updateById(any(WorkEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service().updateWork(
                18L,
                animationCoverUpdateRequest(5, "session-failed")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.WORK_SAVE_FAILED_MESSAGE);

        verify(animationCosService).deleteQuietly(generatedKey.get(), "update-animation-cover-failed");
        verify(cosService, never()).delete("WFA3B1E7A2/work/animation/story-thumb.jpg");
        verify(cosService, never()).delete(work.getMediaObjectKey());
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
    void deleteWorkShouldDeleteAnimationSourceAndIndependentCover() {
        WorkEntity work = editableAnimationWork();
        when(workEntityMapper.selectById(18L)).thenReturn(work);
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(workEntityMapper.update(any(), any())).thenReturn(1);

        service().deleteWork(18L);

        verify(cosService).delete(work.getMediaObjectKey());
        verify(cosService).delete("WFA3B1E7A2/work/animation/story-thumb.jpg");
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
                portfolioReference(12L, 212L, PortfolioConfigScopeDict.PUBLISHED.getCode()),
                portfolioReference(12L, 213L, PortfolioConfigScopeDict.PUBLISHED.getCode())
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
    void checkDeleteWorksShouldDeduplicateReferencesByPortfolioIdAcrossScopes() {
        WorkEntity work = ownedWork(12L);
        work.setTitle("被引用作品");
        when(workEntityMapper.selectBatchIds(any())).thenReturn(List.of(work));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                portfolioReference(12L, 220L, PortfolioConfigScopeDict.DRAFT.getCode()),
                portfolioReference(12L, 220L, PortfolioConfigScopeDict.PUBLISHED.getCode()),
                portfolioReference(12L, 220L, PortfolioConfigScopeDict.PUBLISHED.getCode())
        ));
        MineWorkBatchDeleteRequest request = new MineWorkBatchDeleteRequest();
        request.setWorkIds(List.of(12L));

        MineWorkBatchDeleteCheckResponse response = service().checkDeleteWorks(request);

        assertThat(response.getItems().get(0).getReferenceCount()).isEqualTo(1L);
        assertThat(response.getItems().get(0).getMessage())
                .isEqualTo("作品已被 1 个作品集引用，请先从作品集中移除");
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
        WorkAuditProperties workAuditProperties = new WorkAuditProperties();
        MineWorkAuditService mineWorkAuditService = new MineWorkAuditService(
                workEntityMapper,
                workAuditProperties,
                new WorkAuditUserReasonResolver());
        return new MineWorkService(
                userEntityMapper,
                workEntityMapper,
                wfTagEntityMapper,
                workTagEntityMapper,
                workUploadTaskEntityMapper,
                portfolioReferenceEntityMapper,
                cosService,
                animationCosService,
                workUploadTransactionService,
                contentLimitService,
                mineWorkAuditService,
                new WorkUploadTaskExpirationService(workUploadTaskEntityMapper));
    }

    private MineWorkUploadCompleteRequest.CompleteItem completeItem(Long taskId) {
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(taskId);
        item.setTitle("动图作品");
        item.setTagNames(List.of());
        item.setIdempotencyKey("confirm-" + taskId);
        return item;
    }

    private WorkUploadTaskEntity animationTask(Long taskId, String fileName, String mimeType) {
        WorkUploadTaskEntity task = uploadTask(
                taskId,
                "batch-animation",
                "WFA3B1E7A2/work/animation/" + fileName,
                "ticket-animation-" + taskId);
        task.setMediaType(MediaTypeDict.ANIMATION.getCode());
        task.setOriginalFileName(fileName);
        task.setMimeType(mimeType);
        task.setFileSize(4096L);
        return task;
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

    private WorkEntity editableAnimationWork() {
        WorkEntity work = ownedWork(18L);
        work.setTitle("旧动图");
        work.setDescription("旧说明");
        work.setMediaType(MediaTypeDict.ANIMATION.getCode());
        work.setMediaObjectKey("WFA3B1E7A2/work/animation/story.gif");
        work.setCoverObjectKey("WFA3B1E7A2/work/animation/story-thumb.jpg");
        work.setCoverSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        work.setOriginalFileName("story.gif");
        work.setMimeType("image/gif");
        work.setFileSize(4096L);
        work.setFrameCount(24);
        work.setCoverFrameNumber(1);
        work.setWidth(720);
        work.setHeight(1280);
        return work;
    }

    private MineWorkUpdateRequest animationCoverUpdateRequest(int frameNumber, String idempotencyKey) {
        MineWorkUpdateRequest request = new MineWorkUpdateRequest();
        request.setTitle("新动图");
        request.setDescription("新说明");
        request.setCoverFrameNumber(frameNumber);
        request.setCoverFrameIdempotencyKey(idempotencyKey);
        return request;
    }

    private WorkTagEntity workTagRelation(Long workId, Long tagId) {
        WorkTagEntity relation = new WorkTagEntity();
        relation.setUserId(7L);
        relation.setWorkId(workId);
        relation.setTagId(tagId);
        return relation;
    }

    private PortfolioReferenceEntity portfolioReference(Long workId) {
        return portfolioReference(workId, workId + 1000L, PortfolioConfigScopeDict.PUBLISHED.getCode());
    }

    private PortfolioReferenceEntity portfolioReference(Long workId, Long portfolioId, String configScope) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(portfolioId);
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(workId);
        reference.setConfigScope(configScope);
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

    private MineWorkThumbnailUploadTicketRequest thumbnailRequest() {
        MineWorkThumbnailUploadTicketRequest request = new MineWorkThumbnailUploadTicketRequest();
        request.setClientId("edit-work-17-thumbnail");
        request.setFileName("photo-thumb.jpg");
        request.setMimeType("image/jpeg");
        request.setFileSize(90_000L);
        request.setSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        request.setWidth(960);
        request.setHeight(540);
        request.setRatio("16:9");
        request.setIdempotencyKey("thumbnail-ticket-17");
        return request;
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

    private static void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
