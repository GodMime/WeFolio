package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.MineFeedbackCreationStateResponse;
import com.jxc.wefolio.dto.MineFeedbackDetailResponse;
import com.jxc.wefolio.dto.MineFeedbackListResponse;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.FeedbackEntityMapper;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的反馈查询服务测试。
 */
@ExtendWith(MockitoExtension.class)
class MineFeedbackServiceTest {

    /** 当前测试用户 ID。 */
    private static final Long USER_ID = 7L;

    /** 由两个 UTF-16 代码单元组成的单个 Unicode 字符。 */
    private static final String EMOJI = "\uD83D\uDE00";

    /** 反馈 Mapper 模拟。 */
    @Mock
    private FeedbackEntityMapper feedbackEntityMapper;

    /** COS 服务模拟。 */
    @Mock
    private CosService cosService;

    /** 轮次 JSON 编解码器。 */
    private FeedbackRoundCodec feedbackRoundCodec;

    /** 被测服务。 */
    private MineFeedbackService service;

    /** 初始化 LambdaQueryWrapper 所需的表信息。 */
    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                FeedbackEntity.class);
    }

    /** 初始化认证上下文和服务。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(USER_ID, "mine-feedback-token"));
        feedbackRoundCodec = new FeedbackRoundCodec();
        service = new MineFeedbackService(
                feedbackEntityMapper,
                new FeedbackRoundSnapshotValidator(feedbackRoundCodec, new FeedbackUploadFileValidator()),
                cosService);
    }

    /** 清理认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 创建额度应返回精确计数、开关和提示文案。 */
    @ParameterizedTest
    @CsvSource({
            "0,true,还可提交 3 个待处理问题",
            "1,true,还可提交 2 个待处理问题",
            "2,true,还可提交 1 个待处理问题",
            "3,false,当前已有 3 个待处理问题，请处理完成后再提交",
            "4,false,当前已有 3 个待处理问题，请处理完成后再提交"
    })
    void creationStateShouldDescribeAvailableCapacity(int activeCount, boolean canCreate, String hint) {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(activeCount);

        MineFeedbackCreationStateResponse response = service.creationState();

        assertThat(response.getActiveCount()).isEqualTo(activeCount);
        assertThat(response.getMaxActiveCount()).isEqualTo(3);
        assertThat(response.isCanCreate()).isEqualTo(canCreate);
        assertThat(response.getHintText()).isEqualTo(hint);
    }

    /** 详情查询必须同时使用反馈 ID 与当前用户 ID 隔离数据。 */
    @Test
    void detailShouldEnforceCurrentUserOwnership() {
        when(feedbackEntityMapper.selectByIdAndUserId(99L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_NOT_FOUND_MESSAGE);

        verify(feedbackEntityMapper).selectByIdAndUserId(99L, USER_ID);
        verify(feedbackEntityMapper, never()).selectById(99L);
    }

    /** 详情应解析轮次、生成公开 URL、翻译状态并计算可追加标记。 */
    @Test
    void detailShouldReturnParsedTimelineAndPublicAttachmentUrls() {
        FeedbackRoundSnapshot first = round(1, "原始问题");
        first.setTeamResult("请补充录屏");
        first.setTeamResultAt(LocalDateTime.of(2026, 8, 25, 11, 0));
        FeedbackRoundSnapshot second = round(2, "补充说明");
        FeedbackRoundSnapshot.Attachment video = attachment("user/others/demo.mp4");
        video.setMediaType(FeedbackMediaTypeDict.VIDEO.getCode());
        video.setMimeType("video/mp4");
        video.setDurationMs(32000L);
        second.setAttachments(List.of(video));
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(first, second));
        feedback.setFeedbackResult("请再补充网络环境");
        feedback.setFeedbackResultAt(LocalDateTime.of(2026, 8, 25, 12, 0));
        when(feedbackEntityMapper.selectByIdAndUserId(51L, USER_ID)).thenReturn(feedback);
        when(cosService.publicUrl("user/others/demo.mp4")).thenReturn("https://cdn.example/demo.mp4");

        MineFeedbackDetailResponse response = service.detail(51L);

        assertThat(response.getId()).isEqualTo(51L);
        assertThat(response.getFeedbackNo()).isEqualTo("FB51");
        assertThat(response.getStatus()).isEqualTo(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());
        assertThat(response.getStatusText()).isEqualTo("待再次反馈");
        assertThat(response.getFeedbackResult()).isEqualTo("请再补充网络环境");
        assertThat(response.isCanAppendRound()).isTrue();
        assertThat(response.getRounds()).hasSize(2);
        assertThat(response.getRounds().get(0).getTeamResult()).isEqualTo("请补充录屏");
        assertThat(response.getRounds().get(1).getAttachments()).singleElement().satisfies(item -> {
            assertThat(item.getUrl()).isEqualTo("https://cdn.example/demo.mp4");
            assertThat(item.getMediaType()).isEqualTo(FeedbackMediaTypeDict.VIDEO.getCode());
            assertThat(item.getDurationMs()).isEqualTo(32000L);
        });
        assertThat(response).hasNoNullFieldsOrPropertiesExcept("createdAt", "updatedAt");
    }

    /** 非等待状态或第三轮详情不得开放补充入口。 */
    @Test
    void detailShouldDisableAppendOutsideWaitingCapacity() {
        FeedbackEntity processing = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, "问题")));
        when(feedbackEntityMapper.selectByIdAndUserId(51L, USER_ID)).thenReturn(processing);
        assertThat(service.detail(51L).isCanAppendRound()).isFalse();

        FeedbackEntity thirdRound = feedback(52L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), List.of(
                round(1, "第一轮"), round(2, "第二轮"), round(3, "第三轮")));
        when(feedbackEntityMapper.selectByIdAndUserId(52L, USER_ID)).thenReturn(thirdRound);
        assertThat(service.detail(52L).isCanAppendRound()).isFalse();
    }

    /** 分页参数为空或越界时应归一化为第 1 页、默认 20、最大 50。 */
    @Test
    void listShouldNormalizePageParameters() {
        when(feedbackEntityMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            IPage<FeedbackEntity> requested = invocation.getArgument(0);
            return new Page<FeedbackEntity>(requested.getCurrent(), requested.getSize(), 0)
                    .setRecords(List.of());
        });

        MineFeedbackListResponse defaults = service.list(null, null);
        MineFeedbackListResponse maximum = service.list(0, 99);

        assertThat(defaults.getPageNo()).isEqualTo(1);
        assertThat(defaults.getPageSize()).isEqualTo(20);
        assertThat(maximum.getPageNo()).isEqualTo(1);
        assertThat(maximum.getPageSize()).isEqualTo(50);
    }

    /** 历史列表必须按当前用户隔离，并按更新时间和 ID 倒序。 */
    @Test
    void listShouldScopeByUserAndOrderByLatestActivity() {
        Page<FeedbackEntity> selected = new Page<>(2, 20, 41);
        selected.setRecords(List.of(feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, "问题摘要")))));
        when(feedbackEntityMapper.selectPage(any(), any())).thenReturn(selected);

        MineFeedbackListResponse response = service.list(2, 20);

        ArgumentCaptor<LambdaQueryWrapper<FeedbackEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(feedbackEntityMapper).selectPage(any(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("user_id", "ORDER BY updated_at DESC,id DESC");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs()).containsValue(USER_ID);
        assertThat(response.getPageNo()).isEqualTo(2);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getTotal()).isEqualTo(41L);
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getDescriptionSummary()).isEqualTo("问题摘要");
            assertThat(item.getStatusText()).isEqualTo("处理中");
            assertThat(item.getRoundCount()).isEqualTo(1);
            assertThat(item.getAttachmentCount()).isZero();
        });
    }

    /** 列表项只能包含首轮描述摘要，不能暴露原始 JSON 字段。 */
    @Test
    void listItemShouldExposeSummaryInsteadOfRawJson() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, "字".repeat(100)), round(2, "第二轮隐私内容")));
        Page<FeedbackEntity> selected = new Page<>(1, 20, 1);
        selected.setRecords(List.of(feedback));
        when(feedbackEntityMapper.selectPage(any(), any())).thenReturn(selected);

        MineFeedbackListResponse.Item item = service.list(1, 20).getItems().getFirst();

        assertThat(item.getDescriptionSummary()).hasSizeLessThanOrEqualTo(80);
        assertThat(List.of(item.getClass().getDeclaredFields()))
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("roundsJson", "rounds");
    }

    /** 八十个 emoji 恰好达到摘要上限时必须完整保留。 */
    @Test
    void listSummaryShouldKeepEightyEmoji() {
        String description = EMOJI.repeat(80);

        assertThat(listSummary(description)).isEqualTo(description);
    }

    /** 八十一个 emoji 必须按 Unicode 代码点截为八十个。 */
    @Test
    void listSummaryShouldTruncateEightyOneEmojiToEighty() {
        assertThat(listSummary(EMOJI.repeat(81))).isEqualTo(EMOJI.repeat(80));
    }

    /** 七十九个 ASCII 字符加一个 emoji 不得拆分代理项。 */
    @Test
    void listSummaryShouldNotSplitSurrogatePairAtBoundary() {
        String description = "a".repeat(79) + EMOJI;

        String summary = listSummary(description);

        assertThat(summary).isEqualTo(description);
        assertThat(summary.codePoints())
                .noneMatch(codePoint -> codePoint >= Character.MIN_SURROGATE
                        && codePoint <= Character.MAX_SURROGATE);
    }

    /** 损坏或与轮次数不一致的 JSON 必须阻断详情和列表。 */
    @Test
    void queriesShouldRejectCorruptRoundsJson() {
        FeedbackEntity corrupt = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(), List.of(round(1, "问题")));
        corrupt.setRoundsJson("not-json");
        when(feedbackEntityMapper.selectByIdAndUserId(51L, USER_ID)).thenReturn(corrupt);
        assertThatThrownBy(() -> service.detail(51L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);

        Page<FeedbackEntity> selected = new Page<>(1, 20, 1);
        selected.setRecords(List.of(corrupt));
        when(feedbackEntityMapper.selectPage(any(), any())).thenReturn(selected);
        assertThatThrownBy(() -> service.list(1, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }

    /** 查询必须拒绝空轮次幂等键。 */
    @Test
    void detailShouldRejectBlankRoundIdempotencyKey() {
        FeedbackRoundSnapshot invalidRound = round(1, "问题");
        invalidRound.setIdempotencyKey(" ");
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(invalidRound));
        when(feedbackEntityMapper.selectByIdAndUserId(51L, USER_ID)).thenReturn(feedback);

        assertThatThrownBy(() -> service.detail(51L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }

    /** 查询必须拒绝历史 JSON 中单轮四个附件。 */
    @Test
    void detailShouldRejectRoundWithFourAttachments() {
        FeedbackRoundSnapshot invalidRound = round(1, "问题");
        invalidRound.setAttachments(List.of(
                attachment("user/others/1.jpg"),
                attachment("user/others/2.jpg"),
                attachment("user/others/3.jpg"),
                attachment("user/others/4.jpg")));
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(invalidRound));
        when(feedbackEntityMapper.selectByIdAndUserId(51L, USER_ID)).thenReturn(feedback);

        assertThatThrownBy(() -> service.detail(51L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }

    /** 构造反馈实体。 */
    private FeedbackEntity feedback(Long id, String status, List<FeedbackRoundSnapshot> rounds) {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setId(id);
        feedback.setFeedbackNo("FB" + id);
        feedback.setUserId(USER_ID);
        feedback.setStatus(status);
        feedback.setRoundsJson(feedbackRoundCodec.serialize(rounds));
        feedback.setRoundCount(rounds.size());
        feedback.setAttachmentCount(rounds.stream()
                .map(FeedbackRoundSnapshot::getAttachments)
                .mapToInt(List::size)
                .sum());
        feedback.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        feedback.setUpdatedAt(LocalDateTime.of(2026, 8, 25, 9, 0));
        return feedback;
    }

    /** 构造反馈轮次。 */
    private FeedbackRoundSnapshot round(int roundNo, String description) {
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(roundNo);
        round.setIdempotencyKey("round-" + roundNo);
        round.setDescription(description);
        round.setSubmittedAt(LocalDateTime.of(2026, 8, 24 + Math.min(roundNo, 1), 10, 0));
        round.setAttachments(new ArrayList<>());
        return round;
    }

    /** 构造附件快照。 */
    private FeedbackRoundSnapshot.Attachment attachment(String objectKey) {
        FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
        attachment.setObjectKey(objectKey);
        attachment.setMediaType(FeedbackMediaTypeDict.IMAGE.getCode());
        attachment.setMimeType("image/jpeg");
        attachment.setSize(1024L);
        attachment.setDurationMs(0L);
        return attachment;
    }

    /** 使用单条反馈查询首轮描述摘要。 */
    private String listSummary(String description) {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, description)));
        Page<FeedbackEntity> selected = new Page<>(1, 20, 1);
        selected.setRecords(List.of(feedback));
        when(feedbackEntityMapper.selectPage(any(), any())).thenReturn(selected);
        return service.list(1, 20).getItems().getFirst().getDescriptionSummary();
    }
}
