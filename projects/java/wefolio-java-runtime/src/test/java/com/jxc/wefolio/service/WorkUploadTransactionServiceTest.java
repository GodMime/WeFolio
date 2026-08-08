package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteResponse;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import com.jxc.wefolio.message.MineWorkMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作品上传确认事务测试 — 覆盖扣积分、建作品和幂等确认。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WorkUploadTransactionServiceTest {

    /** 上传任务 Mapper 模拟 */
    @Mock
    private WorkUploadTaskEntityMapper workUploadTaskEntityMapper;

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

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 内容数量上限服务模拟 */
    @Mock
    private ContentLimitService contentLimitService;

    @BeforeEach
    void setUp() {
        lenient().when(userEntityMapper.lockActiveUserById(7L)).thenReturn(7L);
        lenient().when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class)))
                .thenReturn(1);
    }

    @Test
    void confirmUploadedTaskShouldBeTransactional() throws NoSuchMethodException {
        Method method = WorkUploadTransactionService.class.getMethod(
                "confirmUploadedTask",
                Long.class,
                WorkUploadTaskEntity.class,
                MineWorkUploadCompleteRequest.CompleteItem.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void confirmUploadedTaskShouldConsumePointsCreateWorkAndAttachTags() throws ReflectiveOperationException {
        WorkUploadTaskEntity task = createdImageTask();
        when(wfTagEntityMapper.selectOne(any())).thenReturn(null);
        when(wfTagEntityMapper.insert(any(WfTagEntity.class))).thenAnswer(invocation -> {
            WfTagEntity tag = invocation.getArgument(0);
            tag.setId(30L);
            return 1;
        });
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        setField(item, "aspectRatio", " 16:9 ");

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, item);

        InOrder inOrder = inOrder(userEntityMapper, contentLimitService, pointService, workEntityMapper,
                wfTagEntityMapper, workTagEntityMapper, workUploadTaskEntityMapper);
        inOrder.verify(userEntityMapper).lockActiveUserById(7L);
        inOrder.verify(contentLimitService).ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 1L);
        inOrder.verify(pointService).consume(
                eq(7L),
                eq(PointSceneCodeDict.UPLOAD_IMAGE.getCode()),
                eq("WORK_UPLOAD"),
                eq("99"),
                eq(1),
                eq("confirm-99"),
                eq("上传图片作品"));
        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        inOrder.verify(workEntityMapper).insert(workCaptor.capture());
        WorkEntity work = workCaptor.getValue();
        assertThat(work.getUserId()).isEqualTo(7L);
        assertThat(work.getMediaType()).isEqualTo(MediaTypeDict.IMAGE.getCode());
        assertThat(work.getTitle()).isEqualTo("草坪婚礼");
        assertThat(work.getMediaObjectKey()).isEqualTo("WFA3B1E7A2/work/image/photo.jpg");
        assertThat(work.getMediaSha256()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(work.getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/image/photo.jpg");
        assertThat(work.getCoverSha256()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(readField(work, "aspectRatio")).isEqualTo("16:9");
        assertThat(work.getStatus()).isEqualTo(WorkStatusDict.ACTIVE.getCode());
        inOrder.verify(wfTagEntityMapper).selectOne(any());
        inOrder.verify(wfTagEntityMapper).insert(any(WfTagEntity.class));
        ArgumentCaptor<WorkTagEntity> tagCaptor = ArgumentCaptor.forClass(WorkTagEntity.class);
        inOrder.verify(workTagEntityMapper).insert(tagCaptor.capture());
        assertThat(tagCaptor.getValue().getUserId()).isEqualTo(7L);
        assertThat(tagCaptor.getValue().getWorkId()).isEqualTo(120L);
        assertThat(tagCaptor.getValue().getTagId()).isEqualTo(30L);
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        inOrder.verify(workUploadTaskEntityMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        assertThat(taskCaptor.getValue().getConfirmedWorkId()).isEqualTo(120L);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getWorkId()).isEqualTo(120L);
        verify(workUploadTaskEntityMapper).selectById(99L);
    }

    @Test
    void confirmUploadedTaskShouldRejectBeforePointsAndInsertWhenLimitReached() {
        WorkUploadTaskEntity task = createdImageTask();
        org.mockito.Mockito.doThrow(
                        new BusinessException("图片作品数量已达上限（500个），请删除部分图片作品后再上传"))
                .when(contentLimitService)
                .ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 1L);

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, completeItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片作品数量已达上限（500个），请删除部分图片作品后再上传");

        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(workEntityMapper, never()).insert(any(WorkEntity.class));
    }

    @Test
    void confirmUploadedTaskShouldUseLatestConfirmedTaskAfterUserLock() {
        WorkUploadTaskEntity staleTask = createdAnimationTask();
        WorkUploadTaskEntity confirmedTask = createdAnimationTask();
        confirmedTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        confirmedTask.setConfirmedWorkId(120L);
        WorkEntity existingWork = new WorkEntity();
        existingWork.setId(120L);
        existingWork.setTitle("已确认作品");
        when(workUploadTaskEntityMapper.selectById(99L)).thenReturn(confirmedTask);
        when(workEntityMapper.selectById(120L)).thenReturn(existingWork);

        MineWorkUploadCompleteResponse.Item response =
                service().confirmUploadedTask(7L, staleTask, completeItem());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getWorkId()).isEqualTo(120L);
        verify(userEntityMapper).lockActiveUserById(7L);
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(workEntityMapper, never()).insert(any(WorkEntity.class));
    }

    @Test
    void confirmUploadedTaskShouldRollbackWhenTaskConfirmationUpdateLosesRace() {
        WorkUploadTaskEntity task = createdAnimationTask();
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        when(workUploadTaskEntityMapper.updateById(task)).thenReturn(0);

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, completeItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.WORK_SAVE_FAILED_MESSAGE);

        verify(workUploadTaskEntityMapper).updateById(task);
    }

    @Test
    void confirmUploadedTaskShouldSaveBlankAspectRatioAsNull() throws ReflectiveOperationException {
        WorkUploadTaskEntity task = createdImageTask();
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        setField(item, "aspectRatio", "   ");

        service().confirmUploadedTask(7L, task, item);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).insert(workCaptor.capture());
        assertThat(readField(workCaptor.getValue(), "aspectRatio")).isNull();
    }

    @Test
    void confirmUploadedTaskShouldTruncateAspectRatioToThirtyTwoCharacters() throws ReflectiveOperationException {
        WorkUploadTaskEntity task = createdImageTask();
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        setField(item, "aspectRatio", "12345678901234567890123456789012345");

        service().confirmUploadedTask(7L, task, item);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).insert(workCaptor.capture());
        assertThat(readField(workCaptor.getValue(), "aspectRatio"))
                .isEqualTo("12345678901234567890123456789012");
    }

    @Test
    void confirmUploadedTaskShouldUseCustomCoverTaskForVideoWork() {
        WorkUploadTaskEntity task = createdVideoTask();
        WorkUploadTaskEntity coverTask = createdImageTask();
        coverTask.setId(101L);
        coverTask.setObjectKey("WFA3B1E7A2/work/image/film-cover.jpg");
        coverTask.setFileSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        coverTask.setOriginalFileName("film-thumb.jpg");
        lenient().when(workUploadTaskEntityMapper.selectById(101L)).thenReturn(coverTask);
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        item.setCoverTaskId(101L);

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, item);

        verify(pointService).consume(
                eq(7L),
                eq(PointSceneCodeDict.UPLOAD_VIDEO.getCode()),
                eq("WORK_UPLOAD"),
                eq("99"),
                eq(1),
                eq("confirm-99"),
                eq("上传视频作品"));
        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).insert(workCaptor.capture());
        assertThat(workCaptor.getValue().getMediaType()).isEqualTo(MediaTypeDict.VIDEO.getCode());
        assertThat(workCaptor.getValue().getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/image/film-cover.jpg");
        assertThat(workCaptor.getValue().getCoverSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper, times(2)).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().get(0).getId()).isEqualTo(99L);
        assertThat(taskCaptor.getAllValues().get(0).getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/image/film-cover.jpg");
        assertThat(taskCaptor.getAllValues().get(1).getId()).isEqualTo(101L);
        assertThat(taskCaptor.getAllValues().get(1).getStatus()).isEqualTo(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        assertThat(taskCaptor.getAllValues().get(1).getConfirmedWorkId()).isEqualTo(120L);
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void confirmUploadedTaskShouldCreateAnimationWithIndependentPointsAndFrameFields() {
        WorkUploadTaskEntity task = createdAnimationTask();
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(121L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        item.setTagNames(List.of());

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, item);

        InOrder inOrder = inOrder(userEntityMapper, contentLimitService, pointService, workEntityMapper);
        inOrder.verify(userEntityMapper).lockActiveUserById(7L);
        inOrder.verify(contentLimitService).ensureWorkCapacity(7L, MediaTypeDict.ANIMATION.getCode(), 1L);
        inOrder.verify(pointService).consume(
                7L,
                PointSceneCodeDict.UPLOAD_ANIMATION.getCode(),
                "WORK_UPLOAD",
                "99",
                1,
                "confirm-99",
                "上传动图作品");
        ArgumentCaptor<WorkEntity> captor = ArgumentCaptor.forClass(WorkEntity.class);
        inOrder.verify(workEntityMapper).insert(captor.capture());
        assertThat(captor.getValue().getFrameCount()).isEqualTo(24);
        assertThat(captor.getValue().getCoverFrameNumber()).isEqualTo(1);
        assertThat(captor.getValue().getCoverObjectKey())
                .isEqualTo("WFA3B1E7A2/work/animation/animation-thumb.jpg");
        assertThat(captor.getValue().getCoverSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void confirmUploadedTaskShouldUseThumbnailTaskForLargeImageWork() {
        WorkUploadTaskEntity task = createdImageTask();
        task.setFileSize(150L * 1024L);
        WorkUploadTaskEntity coverTask = createdImageTask();
        coverTask.setId(101L);
        coverTask.setObjectKey("WFA3B1E7A2/work/image/photo-thumb.jpg");
        coverTask.setFileSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        coverTask.setOriginalFileName("photo-thumb.jpg");
        coverTask.setFileSize(90L * 1024L);
        lenient().when(workUploadTaskEntityMapper.selectById(101L)).thenReturn(coverTask);
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        item.setCoverTaskId(101L);

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, item);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).insert(workCaptor.capture());
        assertThat(workCaptor.getValue().getMediaType()).isEqualTo(MediaTypeDict.IMAGE.getCode());
        assertThat(workCaptor.getValue().getMediaObjectKey()).isEqualTo("WFA3B1E7A2/work/image/photo.jpg");
        assertThat(workCaptor.getValue().getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/image/photo-thumb.jpg");
        assertThat(workCaptor.getValue().getCoverSha256())
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        ArgumentCaptor<WorkUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkUploadTaskEntity.class);
        verify(workUploadTaskEntityMapper, times(2)).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().get(0).getId()).isEqualTo(99L);
        assertThat(taskCaptor.getAllValues().get(0).getCoverObjectKey()).isEqualTo("WFA3B1E7A2/work/image/photo-thumb.jpg");
        assertThat(taskCaptor.getAllValues().get(1).getId()).isEqualTo(101L);
        assertThat(taskCaptor.getAllValues().get(1).getConfirmedWorkId()).isEqualTo(120L);
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void confirmUploadedTaskShouldRequireThumbnailForLargeImageBeforeConsumingPoints() {
        WorkUploadTaskEntity task = createdImageTask();
        task.setFileSize(150L * 1024L);

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, completeItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缩略图或封面图不能为空");

        verify(pointService, never()).consume(anyLong(), any(), any(), any(), eq(1), any(), any());
        verify(workEntityMapper, never()).insert(any(WorkEntity.class));
    }

    @Test
    void confirmUploadedTaskShouldTruncateDefaultTitleFromLongOriginalFileName() {
        String longStem = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十额外";
        WorkUploadTaskEntity task = createdImageTask();
        task.setOriginalFileName(longStem + ".jpg");
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        item.setTitle("  ");
        item.setTagNames(List.of());

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, item);

        ArgumentCaptor<WorkEntity> workCaptor = ArgumentCaptor.forClass(WorkEntity.class);
        verify(workEntityMapper).insert(workCaptor.capture());
        assertThat(workCaptor.getValue().getTitle()).isEqualTo(longStem.substring(0, 30));
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void confirmUploadedTaskShouldReturnExistingWorkWithoutConsumingAgain() {
        WorkUploadTaskEntity task = createdImageTask();
        task.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        task.setConfirmedWorkId(120L);
        WorkEntity existing = new WorkEntity();
        existing.setId(120L);
        existing.setUserId(7L);
        existing.setTitle("草坪婚礼");
        when(workEntityMapper.selectById(120L)).thenReturn(existing);

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, completeItem());

        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(workEntityMapper, never()).insert(any(WorkEntity.class));
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getWorkId()).isEqualTo(120L);
        assertThat(response.getMessage()).isEqualTo("作品已确认");
    }

    @Test
    void confirmUploadedTaskShouldRejectDuplicateWorkBySha256BeforeConsumingPoints() {
        WorkUploadTaskEntity task = createdImageTask();
        WorkEntity existing = new WorkEntity();
        existing.setId(130L);
        existing.setUserId(7L);
        existing.setTitle("草坪婚礼");
        when(workEntityMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, completeItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品已存在：「草坪婚礼」");
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(workEntityMapper, never()).insert(any(WorkEntity.class));
    }

    /**
     * 插入作品发生未知唯一键冲突时，应保留原始异常日志辅助排查。
     */
    @Test
    void confirmUploadedTaskShouldLogRawDuplicateKeyWhenSha256LookupMisses(CapturedOutput output) {
        WorkUploadTaskEntity task = createdImageTask();
        DuplicateKeyException duplicateKeyException = new DuplicateKeyException("duplicate primary key");
        when(workEntityMapper.insert(any(WorkEntity.class))).thenThrow(duplicateKeyException);

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, completeItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品已存在，请勿重复上传")
                .hasCause(duplicateKeyException);
        assertThat(output).contains("作品保存唯一键冲突未匹配到已存在 SHA-256 作品");
        assertThat(output).contains("userId=7");
        assertThat(output).contains("taskId=99");
        assertThat(output).contains("mediaSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(output).contains("org.springframework.dao.DuplicateKeyException: duplicate primary key");
    }

    @Test
    void confirmUploadedTaskShouldAllowRetryingFailedTask() {
        WorkUploadTaskEntity task = createdImageTask();
        task.setStatus(WorkUploadTaskStatusDict.FAILED.getCode());
        task.setErrorMessage("上次确认失败");
        when(wfTagEntityMapper.selectOne(any())).thenReturn(null);
        when(wfTagEntityMapper.insert(any(WfTagEntity.class))).thenAnswer(invocation -> {
            WfTagEntity tag = invocation.getArgument(0);
            tag.setId(30L);
            return 1;
        });
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });

        MineWorkUploadCompleteResponse.Item response = service().confirmUploadedTask(7L, task, completeItem());

        assertThat(response.isSuccess()).isTrue();
        verify(pointService).consume(
                eq(7L),
                eq(PointSceneCodeDict.UPLOAD_IMAGE.getCode()),
                eq("WORK_UPLOAD"),
                eq("99"),
                eq(1),
                eq("confirm-99"),
                eq("上传图片作品"));
    }

    @Test
    void confirmUploadedTaskShouldCreateNewTagInsteadOfAttachingDisabledTag() {
        WorkUploadTaskEntity task = createdImageTask();
        WfTagEntity disabledTag = new WfTagEntity();
        disabledTag.setId(30L);
        disabledTag.setUserId(7L);
        disabledTag.setName("高端婚礼");
        disabledTag.setStatus(WfTagStatusDict.DISABLED.getCode());
        when(wfTagEntityMapper.selectOne(any())).thenReturn(disabledTag);
        when(wfTagEntityMapper.selectCount(any())).thenReturn(0L);
        when(wfTagEntityMapper.insert(any(WfTagEntity.class))).thenAnswer(invocation -> {
            WfTagEntity tag = invocation.getArgument(0);
            tag.setId(31L);
            return 1;
        });
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });

        service().confirmUploadedTask(7L, task, completeItem());

        ArgumentCaptor<WfTagEntity> tagCaptor = ArgumentCaptor.forClass(WfTagEntity.class);
        verify(wfTagEntityMapper).insert(tagCaptor.capture());
        assertThat(tagCaptor.getValue().getStatus()).isEqualTo(WfTagStatusDict.ACTIVE.getCode());
        ArgumentCaptor<WorkTagEntity> relationCaptor = ArgumentCaptor.forClass(WorkTagEntity.class);
        verify(workTagEntityMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getTagId()).isEqualTo(31L);
    }

    @Test
    void confirmUploadedTaskShouldRejectCreatingTagWhenUserAlreadyHasTenTags() {
        WorkUploadTaskEntity task = createdImageTask();
        when(wfTagEntityMapper.selectOne(any())).thenReturn(null);
        when(wfTagEntityMapper.selectCount(any())).thenReturn(10L);
        when(workEntityMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity work = invocation.getArgument(0);
            work.setId(120L);
            return 1;
        });

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, completeItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签最多保留 10 个");
        verify(wfTagEntityMapper, never()).insert(any(WfTagEntity.class));
        verify(workTagEntityMapper, never()).insert(any(WorkTagEntity.class));
    }

    @Test
    void confirmUploadedTaskShouldRejectTagNameLongerThanTenWords() {
        WorkUploadTaskEntity task = createdImageTask();
        MineWorkUploadCompleteRequest.CompleteItem item = completeItem();
        item.setTagNames(List.of("一二三四五六七八九十长"));

        assertThatThrownBy(() -> service().confirmUploadedTask(7L, task, item))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签名称不能超过 10 个字");
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(workEntityMapper, never()).insert(any(WorkEntity.class));
        verify(wfTagEntityMapper, never()).insert(any(WfTagEntity.class));
        verify(workTagEntityMapper, never()).insert(any(WorkTagEntity.class));
    }

    private WorkUploadTransactionService service() {
        return new WorkUploadTransactionService(
                workUploadTaskEntityMapper,
                userEntityMapper,
                workEntityMapper,
                wfTagEntityMapper,
                workTagEntityMapper,
                pointService,
                contentLimitService,
                new WorkUploadTaskExpirationService(workUploadTaskEntityMapper));
    }

    private WorkUploadTaskEntity createdImageTask() {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(99L);
        task.setUserId(7L);
        task.setBatchId("batch-a");
        task.setMediaType(MediaTypeDict.IMAGE.getCode());
        task.setObjectKey("WFA3B1E7A2/work/image/photo.jpg");
        task.setFileSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        task.setOriginalFileName("photo.jpg");
        task.setMimeType("image/jpeg");
        task.setFileSize(1024L);
        task.setWidth(1200);
        task.setHeight(800);
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return task;
    }

    private WorkUploadTaskEntity createdVideoTask() {
        WorkUploadTaskEntity task = createdImageTask();
        task.setMediaType(MediaTypeDict.VIDEO.getCode());
        task.setObjectKey("WFA3B1E7A2/work/video/film.mp4");
        task.setOriginalFileName("film.mp4");
        task.setMimeType("video/mp4");
        task.setFileSize(4096L);
        task.setDurationMs(60_000);
        task.setWidth(1920);
        task.setHeight(1080);
        return task;
    }

    private WorkUploadTaskEntity createdAnimationTask() {
        WorkUploadTaskEntity task = createdImageTask();
        task.setMediaType(MediaTypeDict.ANIMATION.getCode());
        task.setObjectKey("WFA3B1E7A2/work/animation/animation.gif");
        task.setOriginalFileName("animation.gif");
        task.setMimeType("image/gif");
        task.setFrameCount(24);
        task.setCoverObjectKey("WFA3B1E7A2/work/animation/animation-thumb.jpg");
        task.setCoverSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return task;
    }

    private MineWorkUploadCompleteRequest.CompleteItem completeItem() {
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(99L);
        item.setTitle(" 草坪婚礼 ");
        item.setTagNames(List.of("高端婚礼"));
        item.setIdempotencyKey("confirm-99");
        return item;
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
