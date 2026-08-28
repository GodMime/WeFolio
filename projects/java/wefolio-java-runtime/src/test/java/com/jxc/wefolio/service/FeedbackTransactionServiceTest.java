package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.FeedbackStatusUpdateRequest;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.FeedbackEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 反馈事务服务测试。
 */
@ExtendWith(MockitoExtension.class)
class FeedbackTransactionServiceTest {

    /** 当前测试用户 ID。 */
    private static final Long USER_ID = 7L;

    /** 固定业务时间。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 10, 42, 0, 123_000_000);

    /** 固定业务时钟。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-25T02:42:00.123Z"), ZoneId.of("Asia/Shanghai"));

    /** 由两个 UTF-16 代码单元组成的单个 Unicode 字符。 */
    private static final String EMOJI = "\uD83D\uDE00";

    /** 反馈 Mapper 模拟。 */
    @Mock
    private FeedbackEntityMapper feedbackEntityMapper;

    /** 用户 Mapper 模拟。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 附件上传服务模拟。 */
    @Mock
    private FeedbackUploadService feedbackUploadService;

    /** 轮次 JSON 编解码器。 */
    private FeedbackRoundCodec feedbackRoundCodec;

    /** 被测服务。 */
    private FeedbackTransactionService service;

    /** 初始化认证上下文和通用桩。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(USER_ID, "feedback-transaction-token"));
        feedbackRoundCodec = new FeedbackRoundCodec();
        service = new FeedbackTransactionService(
                feedbackEntityMapper,
                userEntityMapper,
                feedbackUploadService,
                feedbackRoundCodec,
                new FeedbackRoundSnapshotValidator(feedbackRoundCodec, new FeedbackUploadFileValidator()),
                FIXED_CLOCK);
        lenient().when(userEntityMapper.lockActiveUserById(USER_ID)).thenReturn(USER_ID);
        lenient().when(feedbackEntityMapper.insert(any(FeedbackEntity.class))).thenAnswer(invocation -> {
            FeedbackEntity feedback = invocation.getArgument(0);
            feedback.setId(88L);
            return 1;
        });
        lenient().when(feedbackEntityMapper.updateById(any(FeedbackEntity.class))).thenReturn(1);
        lenient().when(feedbackUploadService.validateAndBuildAttachments(eq(USER_ID), anyList()))
                .thenAnswer(invocation -> attachments(invocation.getArgument(1)));
    }

    /** 清理认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 三个事务入口都必须显式对 Exception 回滚。 */
    @Test
    void transactionEntrypointsShouldRollbackForException() throws Exception {
        for (Method method : List.of(
                FeedbackTransactionService.class.getMethod("create", MineFeedbackCreateRequest.class),
                FeedbackTransactionService.class.getMethod("append", Long.class, MineFeedbackCreateRequest.class),
                FeedbackTransactionService.class.getMethod("updateStatus", String.class,
                        FeedbackStatusUpdateRequest.class))) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).isNotNull();
            assertThat(transactional.rollbackFor()).contains(Exception.class);
        }
    }

    /** 活跃问题数为零至两个时均允许创建，且必须先锁用户再统计。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void createShouldLockUserBeforeCountingAndAllowAvailableCapacity(int activeCount) {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(activeCount);

        FeedbackTransactionService.MutationResult result = service.create(request(" create-key ", " 问题描述 "));

        InOrder order = inOrder(userEntityMapper, feedbackEntityMapper);
        order.verify(userEntityMapper).lockActiveUserById(USER_ID);
        order.verify(feedbackEntityMapper).selectByUserIdAndCreateIdempotencyKey(USER_ID, "create-key");
        order.verify(feedbackEntityMapper).countActiveByUserId(eq(USER_ID), anyList());
        order.verify(feedbackEntityMapper).insert(any(FeedbackEntity.class));
        assertThat(result.changed()).isTrue();
        assertThat(result.feedback().getStatus()).isEqualTo(FeedbackStatusDict.PROCESSING.getCode());
    }

    /** 活跃问题达到三个时拒绝创建且不校验或确认附件。 */
    @Test
    void createShouldRejectWhenActiveCountReachedLimit() {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(3);

        assertThatThrownBy(() -> service.create(request("create-key", "问题描述")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ACTIVE_LIMIT_REACHED_MESSAGE);

        verify(feedbackEntityMapper, never()).insert(any(FeedbackEntity.class));
        verifyNoInteractions(feedbackUploadService);
    }

    /** 用户不存在或已停用时必须在统计前拒绝创建。 */
    @Test
    void createShouldRejectWhenActiveUserLockMissing() {
        when(userEntityMapper.lockActiveUserById(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.create(request("create-key", "问题描述")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.USER_NOT_FOUND_MESSAGE);

        verify(feedbackEntityMapper, never()).countActiveByUserId(any(), anyList());
    }

    /** 创建幂等重放直接返回旧反馈，不重复统计、写入或确认附件。 */
    @Test
    void createShouldReturnExistingFeedbackForIdempotentReplay() {
        FeedbackEntity existing = feedback(31L, FeedbackStatusDict.RESOLVED.getCode(), List.of(round(1, "create-key")));
        when(feedbackEntityMapper.selectByUserIdAndCreateIdempotencyKey(USER_ID, "create-key"))
                .thenReturn(existing);

        FeedbackTransactionService.MutationResult result =
                service.create(request("create-key", "重放时内容可以不同"));

        assertThat(result.changed()).isFalse();
        assertThat(result.feedback()).isSameAs(existing);
        assertThat(result.submittedRound().getRoundNo()).isEqualTo(1);
        verify(feedbackEntityMapper, never()).countActiveByUserId(any(), anyList());
        verify(feedbackEntityMapper, never()).insert(any(FeedbackEntity.class));
        verifyNoInteractions(feedbackUploadService);
    }

    /** 创建应保存规范编号、裁剪描述、第一轮 JSON 和附件计数，再确认附件。 */
    @Test
    void createShouldPersistInitialRoundAndConfirmAttachments() {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);
        MineFeedbackCreateRequest request = request("create-key", "  网络无法加载  ");
        request.setUploadTaskIds(List.of(101L, 102L, 103L));

        FeedbackTransactionService.MutationResult result = service.create(request);

        ArgumentCaptor<FeedbackEntity> feedbackCaptor = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackEntityMapper).insert(feedbackCaptor.capture());
        FeedbackEntity saved = feedbackCaptor.getValue();
        List<FeedbackRoundSnapshot> rounds = feedbackRoundCodec.parse(saved.getRoundsJson());
        assertThat(saved.getFeedbackNo())
                .hasSize(22)
                .matches("FB20260825104200[A-HJ-NP-Z2-9]{6}");
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getStatus()).isEqualTo(FeedbackStatusDict.PROCESSING.getCode());
        assertThat(saved.getFeedbackResult()).isNull();
        assertThat(saved.getFeedbackResultAt()).isNull();
        assertThat(saved.getRoundCount()).isEqualTo(1);
        assertThat(saved.getAttachmentCount()).isEqualTo(3);
        assertThat(saved.getCreateIdempotencyKey()).isEqualTo("create-key");
        assertThat(rounds).singleElement().satisfies(savedRound -> {
            assertThat(savedRound.getRoundNo()).isEqualTo(1);
            assertThat(savedRound.getIdempotencyKey()).isEqualTo("create-key");
            assertThat(savedRound.getDescription()).isEqualTo("网络无法加载");
            assertThat(savedRound.getSubmittedAt()).isEqualTo(NOW);
            assertThat(savedRound.getTeamResult()).isNull();
            assertThat(savedRound.getTeamResultAt()).isNull();
            assertThat(savedRound.getAttachments()).hasSize(3);
        });
        verify(feedbackUploadService).confirmTasks(USER_ID, List.of(101L, 102L, 103L), 88L, 1);
        assertThat(result.submittedRound().getDescription()).isEqualTo("网络无法加载");
    }

    /** 一字与两百字描述均应通过创建校验。 */
    @ParameterizedTest
    @ValueSource(ints = {1, 200})
    void createShouldAcceptDescriptionBoundary(int length) {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);
        service.create(request("create-key", "字".repeat(length)));
        verify(feedbackEntityMapper).insert(any(FeedbackEntity.class));
    }

    /** 空白描述应被拒绝。 */
    @Test
    void createShouldRejectBlankDescription() {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);
        assertThatThrownBy(() -> service.create(request("create-key", "  \n ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.DESCRIPTION_EMPTY_MESSAGE);
    }

    /** Unicode 空格描述和团队结果都应按空文本拒绝。 */
    @Test
    void feedbackTextShouldRejectUnicodeSpaceOnlyValues() {
        String unicodeSpaces = "\u00A0\u2007\u202F";
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);

        assertThatThrownBy(() -> service.create(request("unicode-spaces", unicodeSpaces)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.DESCRIPTION_EMPTY_MESSAGE);
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), unicodeSpaces)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_RESULT_EMPTY_MESSAGE);
    }

    /** 两百零一字描述应被拒绝。 */
    @Test
    void createShouldRejectDescriptionOverLimit() {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);
        assertThatThrownBy(() -> service.create(request("create-key", "字".repeat(201))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.DESCRIPTION_TOO_LONG_MESSAGE);
    }

    /** 创建描述长度必须按 Unicode 代码点计算。 */
    @Test
    void createShouldCountDescriptionByUnicodeCodePoint() {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);

        FeedbackTransactionService.MutationResult accepted =
                service.create(request("emoji-200", EMOJI.repeat(200)));

        assertThat(accepted.submittedRound().getDescription()).isEqualTo(EMOJI.repeat(200));
        assertThatThrownBy(() -> service.create(request("emoji-201", EMOJI.repeat(201))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.DESCRIPTION_TOO_LONG_MESSAGE);
    }

    /** 创建幂等键必须是可打印非空 ASCII，拒绝 Unicode、emoji 和控制字符。 */
    @ParameterizedTest
    @ValueSource(strings = {"中文-key", "emoji-\uD83D\uDE00", "line\nbreak"})
    void createShouldRejectNonPrintableAsciiIdempotencyKey(String idempotencyKey) {
        assertThatThrownBy(() -> service.create(request(idempotencyKey, "问题")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.IDEMPOTENCY_KEY_INVALID_MESSAGE);

        verifyNoInteractions(userEntityMapper, feedbackEntityMapper, feedbackUploadService);
    }

    /** 空附件与三个附件均应通过，四个或重复任务 ID 应被拒绝。 */
    @Test
    void createShouldEnforceAttachmentCountAndUniqueness() {
        when(feedbackEntityMapper.countActiveByUserId(eq(USER_ID), anyList())).thenReturn(0);
        service.create(request("empty-attachments", "问题"));

        MineFeedbackCreateRequest tooMany = request("too-many", "问题");
        tooMany.setUploadTaskIds(List.of(1L, 2L, 3L, 4L));
        assertThatThrownBy(() -> service.create(tooMany))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ATTACHMENT_COUNT_INVALID_MESSAGE);

        MineFeedbackCreateRequest duplicate = request("duplicate", "问题");
        duplicate.setUploadTaskIds(List.of(1L, 1L));
        assertThatThrownBy(() -> service.create(duplicate))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_DUPLICATED_MESSAGE);
    }

    /** 追加幂等键命中历史轮次时，即使状态已回处理中也不重复更新或确认。 */
    @Test
    void appendShouldReplayExistingRoundBeforeStateValidation() {
        FeedbackEntity feedback = feedback(41L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, "create-key"), round(2, "append-key")));
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);

        FeedbackTransactionService.MutationResult result =
                service.append(41L, request("append-key", "重放内容"));

        assertThat(result.changed()).isFalse();
        assertThat(result.submittedRound().getRoundNo()).isEqualTo(2);
        verify(feedbackEntityMapper, never()).updateById(any(FeedbackEntity.class));
        verifyNoInteractions(feedbackUploadService);
    }

    /** 只能读取并追加当前用户拥有的反馈。 */
    @Test
    void appendShouldRejectMissingOrForeignFeedback() {
        when(feedbackEntityMapper.lockByIdAndUserId(999L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.append(999L, request("append-key", "补充")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_NOT_FOUND_MESSAGE);
    }

    /** 处理中和已处理状态均不得追加。 */
    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "RESOLVED"})
    void appendShouldOnlyAllowWaitingStatus(String status) {
        FeedbackEntity feedback = feedback(41L, status, List.of(round(1, "create-key")));
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);

        assertThatThrownBy(() -> service.append(41L, request("append-key", "补充")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.APPEND_STATUS_INVALID_MESSAGE);
    }

    /** 三轮反馈不得再追加。 */
    @Test
    void appendShouldRejectFourthRound() {
        FeedbackEntity feedback = feedback(41L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), List.of(
                round(1, "round-1"), round(2, "round-2"), round(3, "round-3")));
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);

        assertThatThrownBy(() -> service.append(41L, request("round-4", "补充")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUND_LIMIT_REACHED_MESSAGE);
    }

    /** 用户追加必须归档上一轮团队要求，追加新轮并清空当前结果。 */
    @Test
    void appendShouldArchiveCurrentTeamResultAndReturnToProcessing() {
        FeedbackRoundSnapshot firstRound = round(1, "create-key");
        firstRound.setDescription("原始描述");
        firstRound.setAttachments(attachments(List.of(91L)));
        FeedbackEntity feedback = feedback(41L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), List.of(firstRound));
        feedback.setFeedbackResult("请补充录屏");
        feedback.setFeedbackResultAt(NOW.minusHours(2));
        feedback.setAttachmentCount(1);
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);
        MineFeedbackCreateRequest request = request("append-key", "  这是补充  ");
        request.setUploadTaskIds(List.of(101L, 102L));

        FeedbackTransactionService.MutationResult result = service.append(41L, request);

        ArgumentCaptor<FeedbackEntity> captor = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackEntityMapper).updateById(captor.capture());
        FeedbackEntity updated = captor.getValue();
        List<FeedbackRoundSnapshot> rounds = feedbackRoundCodec.parse(updated.getRoundsJson());
        assertThat(rounds).hasSize(2);
        assertThat(rounds.get(0).getDescription()).isEqualTo("原始描述");
        assertThat(rounds.get(0).getAttachments()).hasSize(1);
        assertThat(rounds.get(0).getTeamResult()).isEqualTo("请补充录屏");
        assertThat(rounds.get(0).getTeamResultAt()).isEqualTo(NOW.minusHours(2));
        assertThat(rounds.get(1).getRoundNo()).isEqualTo(2);
        assertThat(rounds.get(1).getDescription()).isEqualTo("这是补充");
        assertThat(rounds.get(1).getSubmittedAt()).isEqualTo(NOW);
        assertThat(updated.getStatus()).isEqualTo(FeedbackStatusDict.PROCESSING.getCode());
        assertThat(updated.getFeedbackResult()).isNull();
        assertThat(updated.getFeedbackResultAt()).isNull();
        assertThat(updated.getRoundCount()).isEqualTo(2);
        assertThat(updated.getAttachmentCount()).isEqualTo(3);
        verify(feedbackUploadService).confirmTasks(USER_ID, List.of(101L, 102L), 41L, 2);
        assertThat(result.changed()).isTrue();
        assertThat(result.submittedRound().getRoundNo()).isEqualTo(2);
    }

    /** 追加描述长度必须按 Unicode 代码点计算。 */
    @Test
    void appendShouldCountDescriptionByUnicodeCodePoint() {
        FeedbackEntity acceptedFeedback = feedback(
                41L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), List.of(round(1, "create-key")));
        FeedbackEntity rejectedFeedback = feedback(
                42L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), List.of(round(1, "create-key-2")));
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(acceptedFeedback);
        when(feedbackEntityMapper.lockByIdAndUserId(42L, USER_ID)).thenReturn(rejectedFeedback);

        FeedbackTransactionService.MutationResult accepted =
                service.append(41L, request("emoji-200", EMOJI.repeat(200)));

        assertThat(accepted.submittedRound().getDescription()).isEqualTo(EMOJI.repeat(200));
        assertThatThrownBy(() -> service.append(42L, request("emoji-201", EMOJI.repeat(201))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.DESCRIPTION_TOO_LONG_MESSAGE);
    }

    /** 追加仍必须拒绝四个附件和重复任务 ID。 */
    @Test
    void appendShouldEnforceAttachmentRules() {
        FeedbackEntity feedback = feedback(41L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(round(1, "create-key")));
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);
        MineFeedbackCreateRequest tooMany = request("append-key", "补充");
        tooMany.setUploadTaskIds(List.of(1L, 2L, 3L, 4L));
        assertThatThrownBy(() -> service.append(41L, tooMany))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ATTACHMENT_COUNT_INVALID_MESSAGE);

        MineFeedbackCreateRequest duplicate = request("append-key-2", "补充");
        duplicate.setUploadTaskIds(List.of(1L, 1L));
        assertThatThrownBy(() -> service.append(41L, duplicate))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_TASK_DUPLICATED_MESSAGE);
    }

    /** 待再次反馈期间相同要求幂等成功且不刷新时间。 */
    @Test
    void updateStatusShouldKeepTimestampForSameWaitingResult() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(round(1, "create-key")));
        feedback.setFeedbackResult("请补充录屏");
        feedback.setFeedbackResultAt(NOW.minusHours(2));
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        FeedbackTransactionService.MutationResult result = service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), " 请补充录屏 "));

        assertThat(result.changed()).isFalse();
        assertThat(result.feedback().getFeedbackResultAt()).isEqualTo(NOW.minusHours(2));
        verify(feedbackEntityMapper, never()).updateById(any(FeedbackEntity.class));
    }

    /** 待再次反馈期间不同要求允许覆盖并刷新时间，但不写轮次 JSON。 */
    @Test
    void updateStatusShouldOverwriteDifferentWaitingResult() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(round(1, "create-key")));
        feedback.setFeedbackResult("旧要求");
        feedback.setFeedbackResultAt(NOW.minusHours(2));
        String originalJson = feedback.getRoundsJson();
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        FeedbackTransactionService.MutationResult result = service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), " 新要求 "));

        assertThat(result.changed()).isTrue();
        assertThat(feedback.getFeedbackResult()).isEqualTo("新要求");
        assertThat(feedback.getFeedbackResultAt()).isEqualTo(NOW);
        assertThat(feedback.getRoundsJson()).isEqualTo(originalJson);
        verify(feedbackEntityMapper).updateById(feedback);
    }

    /** 待再次反馈期间覆盖结果时必须按 Unicode 代码点计算长度。 */
    @Test
    void waitingResultShouldCountUnicodeCodePoints() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(round(1, "create-key")));
        feedback.setFeedbackResult("旧要求");
        feedback.setFeedbackResultAt(NOW.minusHours(2));
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        FeedbackTransactionService.MutationResult accepted = service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), EMOJI.repeat(200)));

        assertThat(accepted.feedback().getFeedbackResult()).isEqualTo(EMOJI.repeat(200));
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), EMOJI.repeat(201))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_RESULT_TOO_LONG_MESSAGE);
    }

    /** 待再次反馈直接处理完成时保存本次最终结果，且旧要求不归档。 */
    @Test
    void updateStatusShouldResolveWaitingWithoutArchivingOldRequirement() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(round(1, "create-key")));
        feedback.setFeedbackResult("旧补充要求");
        feedback.setFeedbackResultAt(NOW.minusHours(2));
        String originalJson = feedback.getRoundsJson();
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        service.updateStatus("FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), " 最终处理结果 "));

        assertThat(feedback.getStatus()).isEqualTo(FeedbackStatusDict.RESOLVED.getCode());
        assertThat(feedback.getFeedbackResult()).isEqualTo("最终处理结果");
        assertThat(feedback.getFeedbackResultAt()).isEqualTo(NOW);
        assertThat(feedback.getRoundsJson()).isEqualTo(originalJson);
        assertThat(feedbackRoundCodec.parse(feedback.getRoundsJson()).getFirst().getTeamResult()).isNull();
    }

    /** 处理完成结果必须按 Unicode 代码点计算长度。 */
    @Test
    void resolvedResultShouldCountUnicodeCodePoints() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, "create-key")));
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        FeedbackTransactionService.MutationResult accepted = service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), EMOJI.repeat(200)));

        assertThat(accepted.feedback().getFeedbackResult()).isEqualTo(EMOJI.repeat(200));
        assertThatThrownBy(() -> service.updateStatus(
                "FB52", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), EMOJI.repeat(201))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_RESULT_TOO_LONG_MESSAGE);
    }

    /** 处理中可进入等待补充或已处理状态。 */
    @ParameterizedTest
    @ValueSource(strings = {"WAITING_FOLLOW_UP", "RESOLVED"})
    void updateStatusShouldAllowProcessingTransitions(String targetStatus) {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(round(1, "create-key")));
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        service.updateStatus("FB51", statusRequest(targetStatus, "团队结果"));

        assertThat(feedback.getStatus()).isEqualTo(targetStatus);
        assertThat(feedback.getFeedbackResult()).isEqualTo("团队结果");
        assertThat(feedback.getFeedbackResultAt()).isEqualTo(NOW);
    }

    /** 已处理状态除完全相同幂等重放外均不得再变更。 */
    @Test
    void updateStatusShouldKeepResolvedTerminal() {
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.RESOLVED.getCode(),
                List.of(round(1, "create-key")));
        feedback.setFeedbackResult("最终结果");
        feedback.setFeedbackResultAt(NOW.minusHours(1));
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        FeedbackTransactionService.MutationResult replay = service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), "最终结果"));
        assertThat(replay.changed()).isFalse();
        assertThat(feedback.getFeedbackResultAt()).isEqualTo(NOW.minusHours(1));

        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), "篡改结果")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.STATUS_TRANSITION_INVALID_MESSAGE);
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), "重新打开")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.STATUS_TRANSITION_INVALID_MESSAGE);
        verify(feedbackEntityMapper, never()).updateById(any(FeedbackEntity.class));
    }

    /** 第三轮不能进入或覆盖等待补充，但完全相同请求仍幂等成功。 */
    @Test
    void updateStatusShouldRejectWaitingForThirdRoundExceptExactReplay() {
        List<FeedbackRoundSnapshot> rounds = List.of(
                round(1, "round-1"), round(2, "round-2"), round(3, "round-3"));
        FeedbackEntity processing = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(), rounds);
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(processing);
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), "再补充")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUND_LIMIT_REACHED_MESSAGE);

        FeedbackEntity waiting = feedback(52L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), rounds);
        waiting.setFeedbackResult("已有要求");
        waiting.setFeedbackResultAt(NOW.minusHours(1));
        when(feedbackEntityMapper.lockByFeedbackNo("FB52")).thenReturn(waiting);
        assertThat(service.updateStatus(
                "FB52", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), "已有要求"))
                .changed()).isFalse();
        assertThatThrownBy(() -> service.updateStatus(
                "FB52", statusRequest(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), "覆盖要求")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUND_LIMIT_REACHED_MESSAGE);
    }

    /** 状态目标、反馈结果长度和反馈单存在性必须校验。 */
    @Test
    void updateStatusShouldValidateTargetResultAndFeedback() {
        assertThatThrownBy(() -> service.updateStatus("FB51", statusRequest("PROCESSING", "结果")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.STATUS_INVALID_MESSAGE);
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_RESULT_EMPTY_MESSAGE);
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), "字".repeat(201))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_RESULT_TOO_LONG_MESSAGE);
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(null);
        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), "结果")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.FEEDBACK_NOT_FOUND_MESSAGE);
    }

    /** 损坏的轮次 JSON 必须阻断幂等和状态业务。 */
    @Test
    void transactionShouldRejectCorruptRoundsJson() {
        FeedbackEntity feedback = feedback(41L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(), List.of());
        feedback.setRoundsJson("not-json");
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);

        assertThatThrownBy(() -> service.append(41L, request("append-key", "补充")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }

    /** 创建幂等重放必须拒绝与轮次附件总数不一致的冗余计数。 */
    @Test
    void createReplayShouldRejectMismatchedAttachmentCountWithoutMutation() {
        FeedbackRoundSnapshot firstRound = round(1, "create-key");
        firstRound.setAttachments(attachments(List.of(91L)));
        FeedbackEntity feedback = feedback(31L, FeedbackStatusDict.PROCESSING.getCode(), List.of(firstRound));
        feedback.setAttachmentCount(0);
        when(feedbackEntityMapper.selectByUserIdAndCreateIdempotencyKey(USER_ID, "create-key"))
                .thenReturn(feedback);

        assertThatThrownBy(() -> service.create(request("create-key", "重放")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);

        assertNoFeedbackMutation();
    }

    /** 追加必须拒绝与轮次附件总数不一致的冗余计数。 */
    @Test
    void appendShouldRejectMismatchedAttachmentCountWithoutMutation() {
        FeedbackRoundSnapshot firstRound = round(1, "create-key");
        firstRound.setAttachments(attachments(List.of(91L)));
        FeedbackEntity feedback = feedback(41L, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                List.of(firstRound));
        feedback.setAttachmentCount(0);
        when(feedbackEntityMapper.lockByIdAndUserId(41L, USER_ID)).thenReturn(feedback);

        assertThatThrownBy(() -> service.append(41L, request("append-key", "补充")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);

        assertNoFeedbackMutation();
    }

    /** 内部状态更新必须拒绝与轮次附件总数不一致的冗余计数。 */
    @Test
    void updateStatusShouldRejectMismatchedAttachmentCountWithoutMutation() {
        FeedbackRoundSnapshot firstRound = round(1, "create-key");
        firstRound.setAttachments(attachments(List.of(91L)));
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(firstRound));
        feedback.setAttachmentCount(0);
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), "最终结果")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);

        assertNoFeedbackMutation();
    }

    /** 历史 JSON 中任一轮超过三个附件时必须按损坏数据拒绝。 */
    @Test
    void updateStatusShouldRejectRoundWithMoreThanThreeAttachmentsWithoutMutation() {
        FeedbackRoundSnapshot firstRound = round(1, "create-key");
        firstRound.setAttachments(attachments(List.of(91L, 92L, 93L, 94L)));
        FeedbackEntity feedback = feedback(51L, FeedbackStatusDict.PROCESSING.getCode(),
                List.of(firstRound));
        when(feedbackEntityMapper.lockByFeedbackNo("FB51")).thenReturn(feedback);

        assertThatThrownBy(() -> service.updateStatus(
                "FB51", statusRequest(FeedbackStatusDict.RESOLVED.getCode(), "最终结果")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);

        assertNoFeedbackMutation();
    }

    /** 构造创建或追加请求。 */
    private MineFeedbackCreateRequest request(String idempotencyKey, String description) {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        request.setIdempotencyKey(idempotencyKey);
        request.setDescription(description);
        request.setUploadTaskIds(new ArrayList<>());
        return request;
    }

    /** 构造内部状态更新请求。 */
    private FeedbackStatusUpdateRequest statusRequest(String status, String result) {
        FeedbackStatusUpdateRequest request = new FeedbackStatusUpdateRequest();
        request.setStatus(status);
        request.setFeedbackResult(result);
        return request;
    }

    /** 构造指定状态和轮次的反馈实体。 */
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
                .filter(items -> items != null)
                .mapToInt(List::size)
                .sum());
        return feedback;
    }

    /** 构造单轮反馈快照。 */
    private FeedbackRoundSnapshot round(int roundNo, String idempotencyKey) {
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(roundNo);
        round.setIdempotencyKey(idempotencyKey);
        round.setDescription("第 " + roundNo + " 轮描述");
        round.setSubmittedAt(NOW.minusDays(2L - Math.min(roundNo, 2)));
        round.setAttachments(new ArrayList<>());
        return round;
    }

    /** 按任务 ID 数量构造附件快照。 */
    private List<FeedbackRoundSnapshot.Attachment> attachments(List<Long> taskIds) {
        List<FeedbackRoundSnapshot.Attachment> attachments = new ArrayList<>();
        for (Long taskId : taskIds) {
            FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
            attachment.setObjectKey("WFA3B1E7A2/others/" + taskId + ".jpg");
            attachment.setMediaType("IMAGE");
            attachment.setMimeType("image/jpeg");
            attachment.setSize(1024L);
            attachment.setDurationMs(0L);
            attachments.add(attachment);
        }
        return attachments;
    }

    /** 断言反馈行和附件任务均未发生业务写入。 */
    private void assertNoFeedbackMutation() {
        verify(feedbackEntityMapper, never()).insert(any(FeedbackEntity.class));
        verify(feedbackEntityMapper, never()).updateById(any(FeedbackEntity.class));
        verifyNoInteractions(feedbackUploadService);
    }
}
