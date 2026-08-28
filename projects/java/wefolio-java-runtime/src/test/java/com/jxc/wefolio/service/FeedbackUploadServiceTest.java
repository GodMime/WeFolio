package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackUploadTaskStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketRequest;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketResponse;
import com.jxc.wefolio.entity.FeedbackUploadTaskEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.FeedbackUploadTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反馈附件上传服务测试。
 */
@ExtendWith(MockitoExtension.class)
class FeedbackUploadServiceTest {

    /** 测试用户 ID。 */
    private static final Long USER_ID = 7L;

    /** 测试用户个人唯一码。 */
    private static final String UNIQUE_CODE = "WFA3B1E7A2";

    /** 图片最大字节数。 */
    private static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;

    /** 视频最大字节数。 */
    private static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024;

    /** 测试固定当前时间。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 10, 0);

    /** 反馈业务统一时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 测试固定时钟。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            NOW.atZone(SHANGHAI_ZONE).toInstant(),
            SHANGHAI_ZONE);

    /** 反馈上传任务 Mapper 模拟。 */
    @Mock
    private FeedbackUploadTaskEntityMapper uploadTaskMapper;

    /** 用户 Mapper 模拟。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** COS 服务模拟。 */
    @Mock
    private CosService cosService;

    /** 自动生成模拟任务 ID。 */
    private final AtomicLong nextTaskId = new AtomicLong(100L);

    /** 初始化 LambdaUpdateWrapper 所需的反馈上传任务表信息。 */
    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FeedbackUploadTaskEntity.class);
    }

    /** 设置认证上下文和通用成功桩。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(USER_ID, "feedback-test-token"));
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUniqueCode(UNIQUE_CODE);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        lenient().when(userEntityMapper.selectById(USER_ID)).thenReturn(user);
        lenient().when(uploadTaskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        lenient().when(uploadTaskMapper.insert(any(FeedbackUploadTaskEntity.class)))
                .thenAnswer(invocation -> {
                    FeedbackUploadTaskEntity task = invocation.getArgument(0);
                    task.setId(nextTaskId.incrementAndGet());
                    return 1;
                });
        lenient().when(cosService.createPostUploadTicket(
                        anyString(), anyString(), anyLong(), any(), any(ZoneId.class), eq(true)))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://upload.example.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        Map.of("key", invocation.getArgument(0))));
    }

    /** 清理认证上下文，避免测试线程复用时串号。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 上传票据 DTO 应完整承载请求元数据、服务端限制和逐文件票据。 */
    @Test
    void uploadTicketDtosShouldCarryDeclaredContract() {
        MineFeedbackUploadTicketRequest.UploadFileItem file = new MineFeedbackUploadTicketRequest.UploadFileItem();
        file.setClientId("local-image-1");
        file.setFileName("现场照片.jpg");
        file.setMediaType("IMAGE");
        file.setMimeType("image/jpeg");
        file.setFileSize(2048L);
        file.setDurationMs(0L);
        MineFeedbackUploadTicketRequest request = new MineFeedbackUploadTicketRequest();
        request.setFiles(List.of(file));

        MineFeedbackUploadTicketResponse.Item ticket = new MineFeedbackUploadTicketResponse.Item();
        ticket.setTaskId(101L);
        ticket.setClientId(file.getClientId());
        ticket.setMediaType(file.getMediaType());
        ticket.setObjectKey("WFA3B1E7A2/others/0123456789abcdef0123456789abcdef.jpg");
        ticket.setUploadUrl("https://upload.example.com");
        ticket.setFormData(Map.of("key", ticket.getObjectKey()));
        ticket.setExpiresAt(LocalDateTime.of(2026, 8, 25, 10, 15));
        ticket.setMaxBytes(10L * 1024 * 1024);
        MineFeedbackUploadTicketResponse response = new MineFeedbackUploadTicketResponse();
        response.setMaxFileCount(3);
        response.setImageMaxBytes(10L * 1024 * 1024);
        response.setVideoMaxBytes(100L * 1024 * 1024);
        response.setVideoMaxDurationMs(600_000L);
        response.setItems(List.of(ticket));

        assertThat(request.getFiles()).containsExactly(file);
        assertThat(response.getMaxFileCount()).isEqualTo(3);
        assertThat(response.getImageMaxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(response.getVideoMaxBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(response.getVideoMaxDurationMs()).isEqualTo(600_000L);
        assertThat(response.getItems()).containsExactly(ticket);
        assertThat(fieldNames(MineFeedbackUploadTicketRequest.UploadFileItem.class))
                .containsExactlyInAnyOrder("clientId", "fileName", "mediaType", "mimeType", "fileSize", "durationMs");
        assertThat(fieldNames(MineFeedbackUploadTicketResponse.Item.class))
                .containsExactlyInAnyOrder(
                        "taskId", "clientId", "mediaType", "objectKey", "uploadUrl", "formData", "expiresAt", "maxBytes");
    }

    /** 票据请求必须包含 1 至 3 个文件，且不执行反馈状态或轮次校验。 */
    @Test
    void createTicketsShouldRequireOneToThreeFilesOnly() {
        MineFeedbackUploadTicketRequest empty = new MineFeedbackUploadTicketRequest();
        MineFeedbackUploadTicketRequest tooMany = request(
                image("image-1", "1.jpg", 1L, 0L),
                image("image-2", "2.jpg", 1L, 0L),
                image("image-3", "3.jpg", 1L, 0L),
                image("image-4", "4.jpg", 1L, 0L));

        assertThatThrownBy(() -> service().createUploadTickets(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_COUNT_INVALID_MESSAGE);
        assertThatThrownBy(() -> service().createUploadTickets(empty))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_COUNT_INVALID_MESSAGE);
        assertThatThrownBy(() -> service().createUploadTickets(tooMany))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_COUNT_INVALID_MESSAGE);

        MineFeedbackUploadTicketResponse response = service().createUploadTickets(request(
                image("image-1", "1.jpg", 1L, null),
                image("image-2", "2.png", 2L, 0L),
                video("video-1", "3.mp4", 3L, 1L)));

        assertThat(response.getItems()).hasSize(3);
        verify(userEntityMapper).selectById(USER_ID);
    }

    /** 已停用用户不能创建反馈上传任务或 COS 票据。 */
    @Test
    void createTicketsShouldRejectDisabledUserBeforePersistenceOrCos() {
        UserEntity disabledUser = new UserEntity();
        disabledUser.setId(USER_ID);
        disabledUser.setUniqueCode(UNIQUE_CODE);
        disabledUser.setStatus(UserStatusDict.DISABLED.getCode());
        when(userEntityMapper.selectById(USER_ID)).thenReturn(disabledUser);

        assertThatThrownBy(() -> service().createUploadTickets(request(
                image("disabled-image", "photo.jpg", 1L, 0L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.USER_NOT_FOUND_MESSAGE);
        verify(uploadTaskMapper, never()).insert(any(FeedbackUploadTaskEntity.class));
        verify(cosService, never()).createPostUploadTicket(
                anyString(), anyString(), anyLong(), any(), any(ZoneId.class));
    }

    /** 客户端文件标识只接受去除首尾空白后 1 至 64 个字符。 */
    @Test
    void createTicketsShouldValidateClientIdBounds() {
        for (String invalid : Arrays.asList(null, "", "   ", "x".repeat(65))) {
            MineFeedbackUploadTicketRequest.UploadFileItem file = image(invalid, "photo.jpg", 1L, 0L);
            assertThatThrownBy(() -> service().createUploadTickets(request(file)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineFeedbackMessage.UPLOAD_CLIENT_ID_INVALID_MESSAGE);
        }

        MineFeedbackUploadTicketResponse response = service().createUploadTickets(request(
                image("a", "one.jpg", 1L, 0L),
                image("x".repeat(64), "two.png", 1L, 0L)));

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getClientId()).isEqualTo("a");
        assertThat(response.getItems().get(1).getClientId()).hasSize(64);
    }

    /** 图片与视频扩展名必须和归一化后的 MIME 精确匹配白名单。 */
    @Test
    void createTicketsShouldAcceptExactExtensionAndMimeWhitelist() {
        List<MineFeedbackUploadTicketRequest.UploadFileItem> validFiles = List.of(
                file("image-jpg", "a.JPG", "IMAGE", " IMAGE/JPEG ; charset=binary", 1L, null),
                file("image-jpeg", "a.jpeg", "IMAGE", "image/jpeg", 1L, 0L),
                file("image-png", "a.png", "IMAGE", "image/png", 1L, 0L),
                file("image-webp", "a.webp", "IMAGE", "image/webp", 1L, 0L),
                file("video-mp4", "a.mp4", "VIDEO", "video/mp4", 1L, 1L),
                file("video-mov", "a.mov", "VIDEO", "video/quicktime", 1L, 1L));

        for (MineFeedbackUploadTicketRequest.UploadFileItem validFile : validFiles) {
            MineFeedbackUploadTicketResponse response = service().createUploadTickets(request(validFile));
            assertThat(response.getItems()).singleElement()
                    .satisfies(item -> assertThat(item.getObjectKey())
                            .endsWith(validFile.getFileName().substring(validFile.getFileName().lastIndexOf('.'))
                                    .toLowerCase()));
        }

        ArgumentCaptor<FeedbackUploadTaskEntity> taskCaptor =
                ArgumentCaptor.forClass(FeedbackUploadTaskEntity.class);
        verify(uploadTaskMapper, times(validFiles.size())).insert(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues()).extracting(FeedbackUploadTaskEntity::getMimeType)
                .containsExactly(
                        "image/jpeg", "image/jpeg", "image/png", "image/webp",
                        "video/mp4", "video/quicktime");
    }

    /** 非白名单扩展名、MIME 错配、路径文件名和非字典媒体类型均应拒绝。 */
    @Test
    void createTicketsShouldRejectInvalidFileNameOrType() {
        List<MineFeedbackUploadTicketRequest.UploadFileItem> invalidNames = List.of(
                file("missing-ext", "photo", "IMAGE", "image/jpeg", 1L, 0L),
                file("trailing-dot", "photo.", "IMAGE", "image/jpeg", 1L, 0L),
                file("path-name", "folder/photo.jpg", "IMAGE", "image/jpeg", 1L, 0L));
        for (MineFeedbackUploadTicketRequest.UploadFileItem invalidName : invalidNames) {
            assertThatThrownBy(() -> service().createUploadTickets(request(invalidName)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineFeedbackMessage.UPLOAD_FILE_NAME_INVALID_MESSAGE);
        }

        List<MineFeedbackUploadTicketRequest.UploadFileItem> invalidTypes = List.of(
                file("gif", "photo.gif", "IMAGE", "image/gif", 1L, 0L),
                file("jpg-mismatch", "photo.jpg", "IMAGE", "image/png", 1L, 0L),
                file("mov-mismatch", "clip.mov", "VIDEO", "video/mp4", 1L, 1L),
                file("m4v", "clip.m4v", "VIDEO", "video/x-m4v", 1L, 1L),
                file("lower-media", "photo.jpg", "image", "image/jpeg", 1L, 0L),
                file("unknown-media", "photo.jpg", "AUDIO", "image/jpeg", 1L, 0L));
        for (MineFeedbackUploadTicketRequest.UploadFileItem invalidType : invalidTypes) {
            assertThatThrownBy(() -> service().createUploadTickets(request(invalidType)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
        }
    }

    /** 文件大小必须为正且不超过对应媒体限制。 */
    @Test
    void createTicketsShouldValidateFileSizeBoundaries() {
        assertThat(service().createUploadTickets(request(
                image("image-max", "photo.jpg", IMAGE_MAX_BYTES, 0L))).getItems()).hasSize(1);
        assertThat(service().createUploadTickets(request(
                video("video-max", "clip.mp4", VIDEO_MAX_BYTES, 600_000L))).getItems()).hasSize(1);

        List<MineFeedbackUploadTicketRequest.UploadFileItem> invalid = Arrays.asList(
                image("image-null", "photo.jpg", null, 0L),
                image("image-zero", "photo.jpg", 0L, 0L),
                image("image-over", "photo.jpg", IMAGE_MAX_BYTES + 1L, 0L),
                video("video-over", "clip.mp4", VIDEO_MAX_BYTES + 1L, 1L));
        for (MineFeedbackUploadTicketRequest.UploadFileItem file : invalid) {
            assertThatThrownBy(() -> service().createUploadTickets(request(file)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineFeedbackMessage.UPLOAD_FILE_SIZE_INVALID_MESSAGE);
        }
    }

    /** 视频时长必须处于有效范围，图片空时长归一为 0 且拒绝非零值。 */
    @Test
    void createTicketsShouldValidateAndNormalizeDuration() {
        assertThat(service().createUploadTickets(request(
                image("image-null-duration", "photo.jpg", 1L, null))).getItems()).hasSize(1);

        List<MineFeedbackUploadTicketRequest.UploadFileItem> invalidVideos = Arrays.asList(
                video("video-null", "clip.mp4", 1L, null),
                video("video-zero", "clip.mp4", 1L, 0L),
                video("video-long", "clip.mp4", 1L, 600_001L));
        for (MineFeedbackUploadTicketRequest.UploadFileItem file : invalidVideos) {
            assertThatThrownBy(() -> service().createUploadTickets(request(file)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineFeedbackMessage.UPLOAD_FILE_DURATION_INVALID_MESSAGE);
        }
        assertThatThrownBy(() -> service().createUploadTickets(request(
                image("image-duration", "photo.jpg", 1L, 1L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_IMAGE_DURATION_INVALID_MESSAGE);

        ArgumentCaptor<FeedbackUploadTaskEntity> taskCaptor =
                ArgumentCaptor.forClass(FeedbackUploadTaskEntity.class);
        verify(uploadTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getDurationMs()).isZero();
    }

    /** 新票据必须使用服务端 UUID 对象键、媒体限制和固定十五分钟有效期。 */
    @Test
    void createTicketsShouldGenerateServerObjectKeyAndFifteenMinuteTicket() {
        MineFeedbackUploadTicketResponse response = service().createUploadTickets(request(
                file("client-video", "现场.CLIP.MP4", "VIDEO", "Video/MP4; charset=binary", 1024L, 32_000L)));

        MineFeedbackUploadTicketResponse.Item item = response.getItems().getFirst();
        assertThat(item.getObjectKey())
                .matches("^" + UNIQUE_CODE + "/others/[0-9a-f]{32}\\.mp4$");
        assertThat(item.getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
        assertThat(item.getMaxBytes()).isEqualTo(VIDEO_MAX_BYTES);
        assertThat(response.getMaxFileCount()).isEqualTo(3);
        assertThat(response.getImageMaxBytes()).isEqualTo(IMAGE_MAX_BYTES);
        assertThat(response.getVideoMaxBytes()).isEqualTo(VIDEO_MAX_BYTES);
        assertThat(response.getVideoMaxDurationMs()).isEqualTo(600_000L);

        verify(cosService).createPostUploadTicket(
                eq(item.getObjectKey()), eq("video/mp4"), eq(VIDEO_MAX_BYTES), eq(NOW.plusMinutes(15)),
                eq(SHANGHAI_ZONE), eq(true));
        verify(cosService).ensurePostOverwriteProtectionAvailable();
        ArgumentCaptor<FeedbackUploadTaskEntity> taskCaptor =
                ArgumentCaptor.forClass(FeedbackUploadTaskEntity.class);
        verify(uploadTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(taskCaptor.getValue().getStatus())
                .isEqualTo(FeedbackUploadTaskStatusDict.PENDING.getCode());
        assertThat(taskCaptor.getValue().getFileSize()).isEqualTo(1024L);
        assertThat(taskCaptor.getValue().getDurationMs()).isEqualTo(32_000L);
    }

    /** 反馈签票必须把业务时钟时区显式传给 COS。 */
    @Test
    void createTicketsShouldPassFeedbackClockZoneToCos() {
        service().createUploadTickets(request(image("clock-zone", "photo.jpg", 1024L, 0L)));

        List<Invocation> ticketInvocations = mockingDetails(cosService).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("createPostUploadTicket"))
                .toList();
        assertThat(ticketInvocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.getArguments()).hasSize(6);
            assertThat(invocation.getArguments()[4]).isEqualTo(SHANGHAI_ZONE);
            assertThat(invocation.getArguments()[5]).isEqualTo(true);
        });
    }

    /** 有效的同用户待上传任务应复用任务和对象键并按原过期时间重新签票。 */
    @Test
    void createTicketsShouldRegenerateValidExistingTask() {
        FeedbackUploadTaskEntity existing = pendingTask(
                301L, USER_ID, "same-client", "existing/image.jpg",
                FeedbackMediaTypeDict.IMAGE.getCode(), "image/jpeg", 2048L, 0L, NOW.plusMinutes(8));
        when(uploadTaskMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        MineFeedbackUploadTicketResponse response = service().createUploadTickets(request(
                image("same-client", "photo.jpg", 2048L, 0L)));

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getTaskId()).isEqualTo(301L);
            assertThat(item.getObjectKey()).isEqualTo("existing/image.jpg");
            assertThat(item.getExpiresAt()).isEqualTo(NOW.plusMinutes(8));
        });
        verify(uploadTaskMapper, never()).insert(any(FeedbackUploadTaskEntity.class));
        verify(cosService).createPostUploadTicket(
                "existing/image.jpg", "image/jpeg", IMAGE_MAX_BYTES, NOW.plusMinutes(8), SHANGHAI_ZONE, true);
    }

    /** 已过期或非待上传的同 clientId 任务必须提示客户端更换本地任务标识。 */
    @Test
    void createTicketsShouldRejectExpiredOrNonPendingExistingClientId() {
        List<FeedbackUploadTaskEntity> invalidTasks = List.of(
                pendingTask(1L, USER_ID, "same-client", "old.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW),
                task(2L, USER_ID, "same-client", "used.jpg", "IMAGE", "image/jpeg", 1L, 0L,
                        FeedbackUploadTaskStatusDict.CONFIRMED.getCode(), NOW.plusMinutes(1)));

        for (FeedbackUploadTaskEntity invalidTask : invalidTasks) {
            reset(uploadTaskMapper);
            when(uploadTaskMapper.selectOne(any(Wrapper.class))).thenReturn(invalidTask);
            assertThatThrownBy(() -> service().createUploadTickets(request(
                    image("same-client", "photo.jpg", 1L, 0L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(MineFeedbackMessage.UPLOAD_CLIENT_ID_REUSED_MESSAGE);
        }
    }

    /** 唯一索引竞争失败后应回读并复用并发请求已创建的有效任务。 */
    @Test
    void createTicketsShouldReloadValidTaskAfterDuplicateInsertRace() {
        FeedbackUploadTaskEntity winner = pendingTask(
                401L, USER_ID, "race-client", "winner/photo.webp",
                "IMAGE", "image/webp", 4096L, 0L, NOW.plusMinutes(15));
        when(uploadTaskMapper.selectOne(any(Wrapper.class))).thenReturn(null, winner);
        when(uploadTaskMapper.insert(any(FeedbackUploadTaskEntity.class)))
                .thenThrow(new DuplicateKeyException("client id race"));

        MineFeedbackUploadTicketResponse response = service().createUploadTickets(request(
                file("race-client", "photo.webp", "IMAGE", "image/webp", 4096L, 0L)));

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getTaskId()).isEqualTo(401L);
            assertThat(item.getObjectKey()).isEqualTo("winner/photo.webp");
        });
        verify(uploadTaskMapper, times(2)).selectOne(any(Wrapper.class));
    }

    /** 空上传任务 ID 列表应直接构造为空附件列表且不访问数据库或 COS。 */
    @Test
    void validateAttachmentsShouldAcceptEmptyTaskIds() {
        assertThat(service().validateAndBuildAttachments(USER_ID, List.of())).isEmpty();
        verify(uploadTaskMapper, never()).lockByIdAndUserId(anyLong(), anyLong());
        verify(cosService, never()).headObject(anyString());
    }

    /** 附件任务 ID 必须非空且唯一。 */
    @Test
    void validateAttachmentsShouldRejectNullOrDuplicateTaskIds() {
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, Arrays.asList(1L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_MISSING_MESSAGE);
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(1L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_STATUS_INVALID_MESSAGE);
    }

    /** 附件任务必须存在且属于方法指定用户。 */
    @Test
    void validateAttachmentsShouldRejectMissingOrWrongUserTask() {
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(null);
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_MISSING_MESSAGE);

        FeedbackUploadTaskEntity wrongUser = pendingTask(
                2L, 99L, "wrong-user", "wrong.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(2L, USER_ID)).thenReturn(wrongUser);
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_NOT_OWNED_MESSAGE);
    }

    /** 附件快照只接受未过期的待上传任务。 */
    @Test
    void validateAttachmentsShouldRejectExpiredOrNonPendingTask() {
        FeedbackUploadTaskEntity expired = pendingTask(
                1L, USER_ID, "expired", "expired.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW);
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(expired);
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_EXPIRED_MESSAGE);

        FeedbackUploadTaskEntity confirmed = task(
                2L, USER_ID, "confirmed", "confirmed.jpg", "IMAGE", "image/jpeg", 1L, 0L,
                FeedbackUploadTaskStatusDict.CONFIRMED.getCode(), NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(2L, USER_ID)).thenReturn(confirmed);
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_STATUS_INVALID_MESSAGE);
    }

    /** COS HEAD 返回空或远端异常时应转换为稳定业务异常。 */
    @Test
    void validateAttachmentsShouldTranslateMissingOrFailedCosHead() {
        FeedbackUploadTaskEntity first = pendingTask(
                1L, USER_ID, "head-null", "null.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW.plusMinutes(1));
        FeedbackUploadTaskEntity second = pendingTask(
                2L, USER_ID, "head-error", "error.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(first);
        when(uploadTaskMapper.lockByIdAndUserId(2L, USER_ID)).thenReturn(second);
        when(cosService.headObject("null.jpg")).thenReturn(null);
        when(cosService.headObject("error.jpg")).thenThrow(new RuntimeException("remote details"));

        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_OBJECT_READ_FAILED_MESSAGE)
                .hasMessageNotContaining("remote details");
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_OBJECT_READ_FAILED_MESSAGE)
                .hasMessageNotContaining("remote details");
    }

    /** COS 对象大小或归一化 MIME 与任务声明不一致时应拒绝构造快照。 */
    @Test
    void validateAttachmentsShouldRejectSizeOrMimeMismatch() {
        FeedbackUploadTaskEntity task = pendingTask(
                1L, USER_ID, "mismatch", "mismatch.jpg", "IMAGE", "image/jpeg", 2048L, 0L, NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(task);
        when(cosService.headObject("mismatch.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 2047L))
                .thenReturn(new CosService.ObjectHead("image/png", 2048L));

        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_OBJECT_MISMATCH_MESSAGE);
        assertThatThrownBy(() -> service().validateAndBuildAttachments(USER_ID, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_OBJECT_MISMATCH_MESSAGE);
    }

    /** 合法任务应按输入顺序生成精确附件快照，并接受带参数的大小写 MIME。 */
    @Test
    void validateAttachmentsShouldBuildExactSnapshots() {
        FeedbackUploadTaskEntity image = pendingTask(
                1L, USER_ID, "image", "feedback/image.jpg", "IMAGE", "image/jpeg", 2048L, 0L,
                NOW.plusMinutes(1));
        FeedbackUploadTaskEntity video = pendingTask(
                2L, USER_ID, "video", "feedback/video.mov", "VIDEO", "video/quicktime", 4096L, 32_000L,
                NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(image);
        when(uploadTaskMapper.lockByIdAndUserId(2L, USER_ID)).thenReturn(video);
        when(cosService.headObject("feedback/image.jpg"))
                .thenReturn(new CosService.ObjectHead("IMAGE/JPEG; charset=binary", 2048L));
        when(cosService.headObject("feedback/video.mov"))
                .thenReturn(new CosService.ObjectHead("video/quicktime", 4096L));

        List<FeedbackRoundSnapshot.Attachment> attachments =
                service().validateAndBuildAttachments(USER_ID, List.of(1L, 2L));

        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0)).satisfies(snapshot -> {
            assertThat(snapshot.getObjectKey()).isEqualTo("feedback/image.jpg");
            assertThat(snapshot.getMediaType()).isEqualTo("IMAGE");
            assertThat(snapshot.getMimeType()).isEqualTo("image/jpeg");
            assertThat(snapshot.getSize()).isEqualTo(2048L);
            assertThat(snapshot.getDurationMs()).isZero();
        });
        assertThat(attachments.get(1)).satisfies(snapshot -> {
            assertThat(snapshot.getObjectKey()).isEqualTo("feedback/video.mov");
            assertThat(snapshot.getMediaType()).isEqualTo("VIDEO");
            assertThat(snapshot.getMimeType()).isEqualTo("video/quicktime");
            assertThat(snapshot.getSize()).isEqualTo(4096L);
            assertThat(snapshot.getDurationMs()).isEqualTo(32_000L);
        });
        verify(cosService, never()).publicUrl(anyString());
    }

    /** 空确认列表应直接返回且不锁定、不更新任务。 */
    @Test
    void confirmTasksShouldNoOpForEmptyIds() {
        service().confirmTasks(USER_ID, List.of(), 501L, 2);

        verify(uploadTaskMapper, never()).lockByIdAndUserId(anyLong(), anyLong());
        verify(uploadTaskMapper, never()).update(nullable(FeedbackUploadTaskEntity.class), any());
    }

    /** 已到期的待上传任务在确认阶段必须拒绝，且不能执行条件更新。 */
    @Test
    void confirmTasksShouldRejectExpiredPendingTaskWithoutUpdate() {
        FeedbackUploadTaskEntity expired = pendingTask(
                1L, USER_ID, "expired", "expired.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW);
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(expired);

        assertThatThrownBy(() -> service().confirmTasks(USER_ID, List.of(1L), 501L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_EXPIRED_MESSAGE);
        verify(uploadTaskMapper, never()).update(nullable(FeedbackUploadTaskEntity.class), any());
    }

    /** 确认任务应重新锁定并以待上传状态条件关联反馈 ID 与轮次。 */
    @Test
    void confirmTasksShouldAssociateFeedbackAndRoundConditionally() {
        FeedbackUploadTaskEntity first = pendingTask(
                1L, USER_ID, "first", "first.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW.plusMinutes(1));
        FeedbackUploadTaskEntity second = pendingTask(
                2L, USER_ID, "second", "second.mp4", "VIDEO", "video/mp4", 2L, 1L, NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(first);
        when(uploadTaskMapper.lockByIdAndUserId(2L, USER_ID)).thenReturn(second);
        when(uploadTaskMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service().confirmTasks(USER_ID, List.of(1L, 2L), 501L, 2);

        ArgumentCaptor<Wrapper<FeedbackUploadTaskEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(uploadTaskMapper, times(2)).update(isNull(), wrapperCaptor.capture());
        for (Wrapper<FeedbackUploadTaskEntity> wrapper : wrapperCaptor.getAllValues()) {
            LambdaUpdateWrapper<FeedbackUploadTaskEntity> update = (LambdaUpdateWrapper<FeedbackUploadTaskEntity>) wrapper;
            assertThat(update.getSqlSet()).contains("status", "feedback_id", "round_no");
            assertThat(update.getSqlSegment()).contains("id", "user_id", "status", "deleted", "expires_at");
            assertThat(update.getParamNameValuePairs().values())
                    .contains(FeedbackUploadTaskStatusDict.CONFIRMED.getCode(), 501L, 2, NOW);
        }
    }

    /** 任务不存在、状态变化或条件更新失败时确认必须失败。 */
    @Test
    void confirmTasksShouldRequirePendingTaskAndSuccessfulUpdate() {
        when(uploadTaskMapper.lockByIdAndUserId(1L, USER_ID)).thenReturn(null);
        assertThatThrownBy(() -> service().confirmTasks(USER_ID, List.of(1L), 501L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_MISSING_MESSAGE);

        FeedbackUploadTaskEntity confirmed = task(
                2L, USER_ID, "used", "used.jpg", "IMAGE", "image/jpeg", 1L, 0L,
                FeedbackUploadTaskStatusDict.CONFIRMED.getCode(), NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(2L, USER_ID)).thenReturn(confirmed);
        assertThatThrownBy(() -> service().confirmTasks(USER_ID, List.of(2L), 501L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_STATUS_INVALID_MESSAGE);

        FeedbackUploadTaskEntity pending = pendingTask(
                3L, USER_ID, "pending", "pending.jpg", "IMAGE", "image/jpeg", 1L, 0L, NOW.plusMinutes(1));
        when(uploadTaskMapper.lockByIdAndUserId(3L, USER_ID)).thenReturn(pending);
        when(uploadTaskMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service().confirmTasks(USER_ID, List.of(3L), 501L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_CONFIRM_FAILED_MESSAGE);
    }

    /** 创建使用固定测试时钟的服务实例。 */
    private FeedbackUploadService service() {
        return new FeedbackUploadService(
                uploadTaskMapper,
                userEntityMapper,
                cosService,
                new FeedbackUploadFileValidator(),
                FIXED_CLOCK);
    }

    /** 构造包含指定文件的上传票据请求。 */
    private MineFeedbackUploadTicketRequest request(MineFeedbackUploadTicketRequest.UploadFileItem... files) {
        MineFeedbackUploadTicketRequest request = new MineFeedbackUploadTicketRequest();
        request.setFiles(List.of(files));
        return request;
    }

    /** 构造 JPG 图片上传元数据。 */
    private MineFeedbackUploadTicketRequest.UploadFileItem image(
            String clientId,
            String fileName,
            Long fileSize,
            Long durationMs
    ) {
        String mimeType = fileName != null && fileName.toLowerCase().endsWith(".png")
                ? "image/png" : "image/jpeg";
        return file(clientId, fileName, "IMAGE", mimeType, fileSize, durationMs);
    }

    /** 构造 MP4 视频上传元数据。 */
    private MineFeedbackUploadTicketRequest.UploadFileItem video(
            String clientId,
            String fileName,
            Long fileSize,
            Long durationMs
    ) {
        return file(clientId, fileName, "VIDEO", "video/mp4", fileSize, durationMs);
    }

    /** 构造指定声明值的上传文件元数据。 */
    private MineFeedbackUploadTicketRequest.UploadFileItem file(
            String clientId,
            String fileName,
            String mediaType,
            String mimeType,
            Long fileSize,
            Long durationMs
    ) {
        MineFeedbackUploadTicketRequest.UploadFileItem file = new MineFeedbackUploadTicketRequest.UploadFileItem();
        file.setClientId(clientId);
        file.setFileName(fileName);
        file.setMediaType(mediaType);
        file.setMimeType(mimeType);
        file.setFileSize(fileSize);
        file.setDurationMs(durationMs);
        return file;
    }

    /** 构造未过期待上传任务。 */
    private FeedbackUploadTaskEntity pendingTask(
            Long id,
            Long userId,
            String clientId,
            String objectKey,
            String mediaType,
            String mimeType,
            Long fileSize,
            Long durationMs,
            LocalDateTime expiresAt
    ) {
        return task(id, userId, clientId, objectKey, mediaType, mimeType, fileSize, durationMs,
                FeedbackUploadTaskStatusDict.PENDING.getCode(), expiresAt);
    }

    /** 构造指定状态的上传任务。 */
    private FeedbackUploadTaskEntity task(
            Long id,
            Long userId,
            String clientId,
            String objectKey,
            String mediaType,
            String mimeType,
            Long fileSize,
            Long durationMs,
            String status,
            LocalDateTime expiresAt
    ) {
        FeedbackUploadTaskEntity task = new FeedbackUploadTaskEntity();
        task.setId(id);
        task.setUserId(userId);
        task.setClientId(clientId);
        task.setObjectKey(objectKey);
        task.setMediaType(mediaType);
        task.setMimeType(mimeType);
        task.setFileSize(fileSize);
        task.setDurationMs(durationMs);
        task.setStatus(status);
        task.setExpiresAt(expiresAt);
        return task;
    }

    /** 读取 DTO 声明字段名，防止请求误接受客户端对象键或 URL。 */
    private Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
    }
}
