package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队作品集访问汇总、资源归属与完整幂等测试。
 */
class TeamPortfolioVisitServiceTest {

    /** 团队 ID。 */
    private static final long TEAM_ID = 31L;

    /** 作品集 ID。 */
    private static final long PORTFOLIO_ID = 41L;

    /** 访客 ID。 */
    private static final long VISITOR_ID = 51L;

    /** 访问汇总 ID。 */
    private static final long VISIT_RECORD_ID = 61L;

    /** 访客稳定键。 */
    private static final String VISITOR_KEY = "visitor-team-a";

    /** 初始化 MyBatis-Plus 表元数据。 */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, VisitRecordEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, VisitEventEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, PortfolioReferenceEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, WorkEntity.class);
    }

    /**
     * 首次打开必须创建 TEAM 归属汇总和服务端打开事件，且无积分依赖。
     */
    @Test
    void openCreatesTeamOwnedRecordAndInternalOpenedEventWithoutPoints() {
        Context context = context();
        when(context.recordMapper.selectOne(any())).thenReturn(null);
        when(context.recordMapper.insert(any(VisitRecordEntity.class))).thenAnswer(invocation -> {
            VisitRecordEntity record = invocation.getArgument(0);
            record.setId(VISIT_RECORD_ID);
            return 1;
        });
        when(context.eventMapper.selectOne(any())).thenReturn(null);
        when(context.eventMapper.insert(any(VisitEventEntity.class))).thenReturn(1);
        when(context.recordMapper.updateById(any(VisitRecordEntity.class))).thenReturn(1);

        VisitRecordEntity result = context.service.recordOpen(
                portfolio(), VISITOR_ID, VISITOR_KEY,
                VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode(), "open-1");

        assertThat(result.getOwnerType()).isEqualTo(PortfolioOwnerTypeDict.TEAM.getCode());
        assertThat(result.getOwnerId()).isEqualTo(TEAM_ID);
        assertThat(result.getPortfolioType()).isEqualTo(PortfolioTypeDict.TEAM.getCode());
        assertThat(result.getVisitCount()).isEqualTo(1);
        assertThat(result.getFollowStatus()).isEqualTo(FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        org.mockito.ArgumentCaptor<VisitEventEntity> event =
                org.mockito.ArgumentCaptor.forClass(VisitEventEntity.class);
        verify(context.eventMapper).insert(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(VisitEventTypeDict.PORTFOLIO_OPENED.getCode());
        assertThat(event.getValue().getMetadata()).isEqualTo("{\"sourceType\":\"WECHAT_SHARE_CARD\"}");
        assertThat(TeamPortfolioVisitService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("PointService"));
    }

    /**
     * 首次打开并发插入 VisitRecord 的输家必须重查同一归属记录后继续事件幂等。
     */
    @Test
    void openVisitRecordDuplicateRaceReloadsWinnerAndCreatesNoSecondRecord() {
        Context context = context();
        VisitRecordEntity winner = ownedRecord();
        when(context.recordMapper.selectOne(any())).thenReturn(null, winner);
        when(context.recordMapper.insert(any(VisitRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("visit race"));
        when(context.eventMapper.selectOne(any())).thenReturn(null);
        when(context.eventMapper.insert(any(VisitEventEntity.class))).thenReturn(1);
        when(context.recordMapper.updateById(winner)).thenReturn(1);

        VisitRecordEntity result = context.service.recordOpen(
                portfolio(), VISITOR_ID, VISITOR_KEY, VisitSourceTypeDict.QR_CODE.getCode(), "open-race");

        assertThat(result).isSameAs(winner);
        verify(context.recordMapper, times(1)).insert(any(VisitRecordEntity.class));
        org.mockito.ArgumentCaptor<Wrapper<VisitRecordEntity>> recordQueries =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.recordMapper, times(2)).selectOne(recordQueries.capture());
        LambdaQueryWrapper<VisitRecordEntity> ordinaryRead =
                (LambdaQueryWrapper<VisitRecordEntity>) recordQueries.getAllValues().get(0);
        LambdaQueryWrapper<VisitRecordEntity> winnerCurrentRead =
                (LambdaQueryWrapper<VisitRecordEntity>) recordQueries.getAllValues().get(1);
        assertThat(ordinaryRead.getSqlSegment().toUpperCase()).doesNotContain("FOR UPDATE");
        assertThat(winnerCurrentRead.getSqlSegment().toUpperCase()).contains("LIMIT 1 FOR UPDATE");
        assertThat(winnerCurrentRead.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                VISITOR_ID, VISITOR_KEY, PORTFOLIO_ID, PortfolioTypeDict.TEAM.getCode(),
                PortfolioOwnerTypeDict.TEAM.getCode(), TEAM_ID);
        verify(context.eventMapper, times(1)).insert(any(VisitEventEntity.class));
    }

    /**
     * VisitRecord 并发冲突后找不到同归属赢家必须稳定失败且不写事件。
     */
    @Test
    void openVisitRecordDuplicateRaceWithoutOwnedWinnerFailsBeforeEvent() {
        Context context = context();
        when(context.recordMapper.selectOne(any())).thenReturn(null);
        when(context.recordMapper.insert(any(VisitRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("visit race"));

        assertThatThrownBy(() -> context.service.recordOpen(
                portfolio(), VISITOR_ID, VISITOR_KEY, VisitSourceTypeDict.UNKNOWN.getCode(), "open-race-bad"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(context.eventMapper, context.referenceMapper);
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * 公共事件只允许五种客户端语义，三种服务端事件必须拒绝且零副作用。
     */
    @Test
    void publicEventsRejectServerOnlyTypesBeforeEveryMapper() {
        Context context = context();
        VisitorTeamPortfolioEventRequest opened = new VisitorTeamPortfolioEventRequest();
        opened.setEventType(VisitEventTypeDict.PORTFOLIO_OPENED.getCode());
        opened.setIdempotencyKey("forged-opened");
        opened.setSourceType(VisitSourceTypeDict.UNKNOWN.getCode());
        VisitorTeamPortfolioEventRequest schedule = new VisitorTeamPortfolioEventRequest();
        schedule.setEventType(VisitEventTypeDict.SCHEDULE_QUERIED.getCode());
        schedule.setIdempotencyKey("forged-schedule");
        schedule.setComponentKey("schedule-1");
        schedule.setQueriedDate(LocalDate.of(2026, 8, 1));
        VisitorTeamPortfolioEventRequest lead = new VisitorTeamPortfolioEventRequest();
        lead.setEventType(VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode());
        lead.setIdempotencyKey("forged-lead");
        lead.setLeadId(91L);
        for (VisitorTeamPortfolioEventRequest request : List.of(opened, schedule, lead)) {
            assertThatThrownBy(() -> context.service.recordEvent(
                    portfolio(), VISITOR_ID, VISITOR_KEY, request))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(context.recordMapper, context.eventMapper, context.referenceMapper, context.workMapper);
    }

    /**
     * 五种客户端事件必须按类型要求精确字段，缺失字段时零写入。
     */
    @Test
    void publicEventsRequireExactTypedFields() {
        Context context = context();
        VisitorTeamPortfolioEventRequest work = clientEvent(VisitEventTypeDict.WORK_VIEWED, "bad-work");
        work.setComponentKey(null);
        VisitorTeamPortfolioEventRequest video = clientEvent(VisitEventTypeDict.VIDEO_PLAYED, "bad-video");
        video.setDurationSeconds(null);
        VisitorTeamPortfolioEventRequest qr = clientEvent(VisitEventTypeDict.QR_CODE_INTERACTED, "bad-qr");
        qr.setAction(null);
        VisitorTeamPortfolioEventRequest member = clientEvent(VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED, "bad-member");
        member.setMemberPortfolioId(null);
        VisitorTeamPortfolioEventRequest contact = clientEvent(VisitEventTypeDict.CONTACT_FORM_EXPOSED, "bad-contact");
        contact.setComponentKey(null);

        for (VisitorTeamPortfolioEventRequest request : List.of(work, video, qr, member, contact)) {
            assertThatThrownBy(() -> context.service.recordEvent(
                    portfolio(), VISITOR_ID, VISITOR_KEY, request))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(context.recordMapper, context.eventMapper, context.referenceMapper, context.workMapper);
    }

    /**
     * WORK、成员作品集和二维码事件必须命中当前发布作用域的有效引用与组件键。
     */
    @Test
    @SuppressWarnings("unchecked")
    void clientResourcesUseStrictPublishedReferenceWrappersWithoutInQueries() {
        Context context = successfulEventContext();
        when(context.workMapper.selectById(71L)).thenReturn(work(71L, MediaTypeDict.IMAGE.getCode()));
        when(context.referenceMapper.selectList(any())).thenAnswer(invocation -> {
            Wrapper<PortfolioReferenceEntity> wrapper = invocation.getArgument(0);
            LambdaQueryWrapper<PortfolioReferenceEntity> lambdaWrapper =
                    (LambdaQueryWrapper<PortfolioReferenceEntity>) wrapper;
            lambdaWrapper.getSqlSegment();
            Map<String, Object> values = lambdaWrapper.getParamNameValuePairs();
            String type = values.values().stream().map(String::valueOf)
                    .filter(value -> List.of("WORK", "MEMBER_PORTFOLIO", "QR_CODE_ASSET").contains(value))
                    .findFirst().orElseThrow();
            return List.of(reference(type));
        });

        context.service.recordEvent(portfolio(), VISITOR_ID, VISITOR_KEY,
                clientEvent(VisitEventTypeDict.WORK_VIEWED, "work-1"));
        context.service.recordEvent(portfolio(), VISITOR_ID, VISITOR_KEY,
                clientEvent(VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED, "member-1"));
        context.service.recordEvent(portfolio(), VISITOR_ID, VISITOR_KEY,
                clientEvent(VisitEventTypeDict.QR_CODE_INTERACTED, "qr-1"));

        org.mockito.ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.referenceMapper, times(3)).selectList(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(raw -> {
            LambdaQueryWrapper<PortfolioReferenceEntity> wrapper =
                    (LambdaQueryWrapper<PortfolioReferenceEntity>) raw;
            assertThat(wrapper.getSqlSegment()).contains(
                    "portfolio_id", "config_scope", "reference_type", "reference_id",
                    "component_key", "is_valid", "deleted");
            assertThat(wrapper.getSqlSegment().toUpperCase()).doesNotContain(" IN ");
            assertThat(wrapper.getParamNameValuePairs().values()).contains(
                    PORTFOLIO_ID, PortfolioConfigScopeDict.PUBLISHED.getCode(), 1, 0L);
        });
        verify(context.workMapper, times(1)).selectById(71L);
    }

    /**
     * 资源未发布、失效或错组件时必须在事件和计数写入前拒绝。
     */
    @Test
    void missingOrMismatchedPublishedReferenceHasZeroEventSideEffects() {
        Context context = context();
        when(context.recordMapper.selectOne(any())).thenReturn(ownedRecord());
        PortfolioReferenceEntity wrong = reference(ReferenceTypeDict.WORK.getCode());
        wrong.setComponentKey("other-component");
        when(context.referenceMapper.selectList(any())).thenReturn(List.of(wrong));

        assertThatThrownBy(() -> context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY,
                clientEvent(VisitEventTypeDict.WORK_VIEWED, "wrong-ref")))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(context.eventMapper);
        verifyNoInteractions(context.workMapper);
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * 发布图片不能伪报视频事件，发布视频也不能伪报图片查看事件。
     */
    @Test
    void trustedWorkMediaTypeRejectsCrossTypeAnalyticsWithoutWrites() {
        assertTrustedMediaRejected(
                clientEvent(VisitEventTypeDict.VIDEO_PLAYED, "image-as-video"),
                work(71L, MediaTypeDict.IMAGE.getCode()));
        assertTrustedMediaRejected(
                clientEvent(VisitEventTypeDict.WORK_VIEWED, "video-as-image"),
                work(71L, MediaTypeDict.VIDEO.getCode()));
    }

    /**
     * WORK/VIDEO 必须携带明确匹配事件语义的 typed mediaType，缺失或错类型时零 Mapper 交互。
     */
    @Test
    void workAndVideoRequireExplicitEventOwnedMediaTypeBeforeEveryMapper() {
        Context context = context();
        VisitorTeamPortfolioEventRequest missing = clientEvent(VisitEventTypeDict.WORK_VIEWED, "missing-media");
        missing.setMediaType(null);
        VisitorTeamPortfolioEventRequest wrong = clientEvent(VisitEventTypeDict.WORK_VIEWED, "wrong-media");
        wrong.setMediaType(MediaTypeDict.VIDEO.getCode());

        for (VisitorTeamPortfolioEventRequest request : List.of(missing, wrong)) {
            assertThatThrownBy(() -> context.service.recordEvent(
                    portfolio(), VISITOR_ID, VISITOR_KEY, request))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(context.recordMapper, context.eventMapper, context.referenceMapper, context.workMapper);
    }

    /**
     * 可信作品必须精确匹配 workId 且未删除，否则事件和计数均不得写入。
     */
    @Test
    void trustedWorkMustMatchExactIdAndLogicalDeleteState() {
        WorkEntity wrongId = work(999L, MediaTypeDict.IMAGE.getCode());
        WorkEntity deleted = work(71L, MediaTypeDict.IMAGE.getCode());
        deleted.setDeleted(71L);

        assertTrustedMediaRejected(
                clientEvent(VisitEventTypeDict.WORK_VIEWED, "wrong-work-id"), wrongId);
        assertTrustedMediaRejected(
                clientEvent(VisitEventTypeDict.WORK_VIEWED, "deleted-work"), deleted);
    }

    /**
     * 合法 IMAGE/ANIMATION/VIDEO 事件必须先命中发布引用，再读取可信作品媒体类型。
     */
    @Test
    void legalImageAndVideoEventsValidateReferenceBeforeTrustedWork() {
        assertTrustedMediaAccepted(VisitEventTypeDict.WORK_VIEWED, MediaTypeDict.IMAGE.getCode());
        assertTrustedMediaAccepted(VisitEventTypeDict.WORK_VIEWED, MediaTypeDict.ANIMATION.getCode());
        assertTrustedMediaAccepted(VisitEventTypeDict.VIDEO_PLAYED, MediaTypeDict.VIDEO.getCode());
    }

    @Test
    void videoPlayedShouldRejectAnimationMediaTypeBeforeMapperAccess() {
        Context context = context();
        VisitorTeamPortfolioEventRequest request =
                clientEvent(VisitEventTypeDict.VIDEO_PLAYED, "animation-as-video");
        request.setMediaType(MediaTypeDict.ANIMATION.getCode());

        assertThatThrownBy(() -> context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(context.recordMapper, context.eventMapper, context.referenceMapper, context.workMapper);
    }

    /**
     * 同幂等键只有完整业务载荷一致才可复用，普通预查命中不得重复计数。
     */
    @Test
    void sameCompletePayloadRetrySkipsEventAndCounter() {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        VisitorTeamPortfolioEventRequest request = clientEvent(VisitEventTypeDict.WORK_VIEWED, "work-retry");
        VisitEventEntity existing = persistedEvent(record, request,
                "{\"componentKey\":\"carousel-1\",\"mediaType\":\"IMAGE\"}");
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.referenceMapper.selectList(any())).thenReturn(List.of(reference(ReferenceTypeDict.WORK.getCode())));
        when(context.workMapper.selectById(71L)).thenReturn(work(71L, MediaTypeDict.IMAGE.getCode()));
        when(context.eventMapper.selectOne(any())).thenReturn(existing);

        TeamPortfolioVisitService.EventRecordResult result = context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request);

        assertThat(result.recorded()).isFalse();
        assertThat(result.visitRecord()).isSameAs(record);
        verify(context.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * 同 key 同类型但不同作品、组件、时长或 canonical metadata 必须冲突且零计数。
     */
    @Test
    void sameKeyDifferentWorkPayloadConflictsWithoutCounter() {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        VisitorTeamPortfolioEventRequest current = clientEvent(VisitEventTypeDict.VIDEO_PLAYED, "video-key");
        VisitEventEntity existing = persistedEvent(record, current,
                "{\"componentKey\":\"carousel-1\",\"mediaType\":\"VIDEO\"}");
        existing.setWorkId(999L);
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.referenceMapper.selectList(any())).thenReturn(List.of(reference(ReferenceTypeDict.WORK.getCode())));
        when(context.workMapper.selectById(71L)).thenReturn(work(71L, MediaTypeDict.VIDEO.getCode()));
        when(context.eventMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, current))
                .isInstanceOf(BusinessException.class);

        verify(context.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * 组件键、成员作品集 ID 和时长均属于完整幂等载荷，任一变化都必须冲突。
     */
    @Test
    void changedComponentMemberPortfolioOrDurationConflictsWithoutCounter() {
        VisitorTeamPortfolioEventRequest work = clientEvent(VisitEventTypeDict.WORK_VIEWED, "component-key");
        assertClientPayloadConflict(
                work,
                "{\"componentKey\":\"other-component\",\"mediaType\":\"IMAGE\"}",
                ReferenceTypeDict.WORK,
                event -> {
                });

        VisitorTeamPortfolioEventRequest member =
                clientEvent(VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED, "member-key");
        assertClientPayloadConflict(
                member,
                "{\"componentKey\":\"member-1\",\"memberPortfolioId\":88}",
                ReferenceTypeDict.MEMBER_PORTFOLIO,
                event -> {
                });

        VisitorTeamPortfolioEventRequest video = clientEvent(VisitEventTypeDict.VIDEO_PLAYED, "duration-key");
        assertClientPayloadConflict(
                video,
                "{\"componentKey\":\"carousel-1\",\"mediaType\":\"VIDEO\"}",
                ReferenceTypeDict.WORK,
                event -> event.setDurationSeconds(13));
    }

    /**
     * 幂等复用必须同时绑定当前 VisitRecord、作品集、团队和访客。
     */
    @Test
    void changedVisitRecordPortfolioTeamOrVisitorOwnershipConflictsWithoutCounter() {
        List<Consumer<VisitEventEntity>> ownershipMutations = List.of(
                event -> event.setVisitRecordId(999L),
                event -> event.setPortfolioId(999L),
                event -> event.setOwnerType(PortfolioOwnerTypeDict.USER.getCode()),
                event -> event.setOwnerId(999L),
                event -> event.setVisitorKey("other-visitor"));
        for (int index = 0; index < ownershipMutations.size(); index++) {
            VisitorTeamPortfolioEventRequest request =
                    clientEvent(VisitEventTypeDict.WORK_VIEWED, "ownership-" + index);
            assertClientPayloadConflict(
                    request,
                    "{\"componentKey\":\"carousel-1\",\"mediaType\":\"IMAGE\"}",
                    ReferenceTypeDict.WORK,
                    ownershipMutations.get(index));
        }
    }

    /**
     * 查档幂等必须比较日期和 componentKey，不同载荷不得返回 skipped 快照上下文。
     */
    @Test
    void sameScheduleKeyDifferentDateOrComponentConflictsWithoutSnapshotEligibility() {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        TeamPortfolioScheduleQueryRequest request = scheduleRequest("schedule-key", "schedule-1",
                LocalDate.of(2026, 8, 2));
        VisitorTeamPortfolioEventRequest original = new VisitorTeamPortfolioEventRequest();
        original.setEventType(VisitEventTypeDict.SCHEDULE_QUERIED.getCode());
        original.setIdempotencyKey("schedule-key");
        original.setComponentKey("schedule-1");
        original.setQueriedDate(LocalDate.of(2026, 8, 1));
        VisitEventEntity existing = persistedEvent(record, original,
                "{\"componentKey\":\"schedule-1\"}");
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.eventMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> context.service.recordScheduleQuery(
                portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .isInstanceOf(BusinessException.class);

        verify(context.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));

        Context componentContext = context();
        TeamPortfolioScheduleQueryRequest changedComponent = scheduleRequest(
                "schedule-component-key", "schedule-2", LocalDate.of(2026, 8, 1));
        original.setIdempotencyKey("schedule-component-key");
        when(componentContext.recordMapper.selectOne(any())).thenReturn(record);
        when(componentContext.eventMapper.selectOne(any())).thenReturn(
                persistedEvent(record, original, "{\"componentKey\":\"schedule-1\"}"));

        assertThatThrownBy(() -> componentContext.service.recordScheduleQuery(
                portfolio(), VISITOR_ID, VISITOR_KEY, changedComponent))
                .isInstanceOf(BusinessException.class);

        verify(componentContext.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(componentContext.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * 线索提交事件幂等必须比较 leadId，不同线索不得复用同一 key。
     */
    @Test
    void sameContactKeyDifferentLeadConflictsWithoutCounter() {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        VisitorTeamPortfolioEventRequest original = new VisitorTeamPortfolioEventRequest();
        original.setEventType(VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode());
        original.setIdempotencyKey("lead-key");
        original.setLeadId(91L);
        VisitEventEntity existing = persistedEvent(record, original, "{\"leadId\":91}");
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.eventMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> context.service.recordContactLeadSubmitted(
                portfolio(), VISITOR_ID, VISITOR_KEY, 92L, "lead-key"))
                .isInstanceOf(BusinessException.class);

        verify(context.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * DuplicateKey race 必须使用与普通预查相同的完整载荷比较。
     */
    @Test
    void duplicateEventRaceUsesSameCompletePayloadComparison() {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        VisitorTeamPortfolioEventRequest request = clientEvent(VisitEventTypeDict.QR_CODE_INTERACTED, "qr-race");
        VisitEventEntity raced = persistedEvent(record, request,
                "{\"action\":\"LONG_PRESS\",\"componentKey\":\"qr-1\"}");
        raced.setMetadata("{\"action\":\"CLICK\",\"componentKey\":\"qr-1\"}");
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.referenceMapper.selectList(any())).thenReturn(List.of(reference(ReferenceTypeDict.QR_CODE_ASSET.getCode())));
        when(context.eventMapper.selectOne(any())).thenReturn(null, raced);
        when(context.eventMapper.insert(any(VisitEventEntity.class)))
                .thenThrow(new DuplicateKeyException("event race"));

        assertThatThrownBy(() -> context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .isInstanceOf(BusinessException.class);

        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * DuplicateKey race 中完整载荷一致时必须复用首次事件且不重复计数。
     */
    @Test
    void duplicateEventRaceWithSameCompletePayloadSkipsCounter() {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        VisitorTeamPortfolioEventRequest request = clientEvent(VisitEventTypeDict.QR_CODE_INTERACTED, "qr-race-same");
        VisitEventEntity raced = persistedEvent(record, request,
                "{\"action\":\"LONG_PRESS\",\"componentKey\":\"qr-1\"}");
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.referenceMapper.selectList(any())).thenReturn(
                List.of(reference(ReferenceTypeDict.QR_CODE_ASSET.getCode())));
        when(context.eventMapper.selectOne(any())).thenReturn(null, raced);
        when(context.eventMapper.insert(any(VisitEventEntity.class)))
                .thenThrow(new DuplicateKeyException("event race"));

        TeamPortfolioVisitService.EventRecordResult result = context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request);

        assertThat(result.recorded()).isFalse();
        assertThat(result.visitRecord()).isSameAs(record);
        org.mockito.ArgumentCaptor<Wrapper<VisitEventEntity>> eventQueries =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.eventMapper, times(2)).selectOne(eventQueries.capture());
        LambdaQueryWrapper<VisitEventEntity> ordinaryRead =
                (LambdaQueryWrapper<VisitEventEntity>) eventQueries.getAllValues().get(0);
        LambdaQueryWrapper<VisitEventEntity> racedCurrentRead =
                (LambdaQueryWrapper<VisitEventEntity>) eventQueries.getAllValues().get(1);
        assertThat(ordinaryRead.getSqlSegment().toUpperCase()).doesNotContain("FOR UPDATE");
        assertThat(racedCurrentRead.getSqlSegment().toUpperCase()).contains("LIMIT 1 FOR UPDATE");
        assertThat(racedCurrentRead.getParamNameValuePairs().values())
                .containsExactly(request.getIdempotencyKey());
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * 八类事件必须只更新各自计数，成员跳转和表单曝光仅刷新最近访问时间。
     */
    @Test
    void newEventsIncrementOnlyTheirOwnedCountersAndDuration() {
        assertCounter(VisitEventTypeDict.WORK_VIEWED, record -> {
            assertThat(record.getViewWorkCount()).isEqualTo(1);
            assertThat(record.getTotalDurationSeconds()).isZero();
        });
        assertCounter(VisitEventTypeDict.VIDEO_PLAYED, record -> {
            assertThat(record.getPlayVideoCount()).isEqualTo(1);
            assertThat(record.getTotalDurationSeconds()).isEqualTo(12);
        });
        assertCounter(VisitEventTypeDict.QR_CODE_INTERACTED,
                record -> assertThat(record.getQrActionCount()).isEqualTo(1));
        assertCounter(VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED, record -> {
            assertThat(record.getViewWorkCount()).isZero();
            assertThat(record.getQrActionCount()).isZero();
        });
        assertCounter(VisitEventTypeDict.CONTACT_FORM_EXPOSED,
                record -> assertThat(record.getContactSubmitCount()).isZero());

        Context scheduleContext = successfulEventContext();
        VisitRecordEntity scheduleRecord = scheduleContext.service.recordScheduleQuery(
                portfolio(), VISITOR_ID, VISITOR_KEY,
                scheduleRequest("count-schedule", "schedule-1", LocalDate.of(2026, 8, 1)))
                .visitRecord();
        assertThat(scheduleRecord.getScheduleQueryCount()).isEqualTo(1);

        Context contactContext = successfulEventContext();
        VisitRecordEntity contactRecord = contactContext.service.recordContactLeadSubmitted(
                portfolio(), VISITOR_ID, VISITOR_KEY, 91L, "count-contact").visitRecord();
        assertThat(contactRecord.getContactSubmitCount()).isEqualTo(1);
    }

    /**
     * 表单曝光和查档事件必须命中全菜单中的已启用同类型组件。
     */
    @Test
    void interactiveEventsShouldRejectMissingPublishedComponentBeforePersistence() {
        Context contactContext = successfulEventContext();
        PortfolioEntity contactPortfolio = portfolio();
        contactPortfolio.setPublishedConfigJson(
                contactPortfolio.getPublishedConfigJson().replace("contact-1", "contact-other"));

        assertThatThrownBy(() -> contactContext.service.recordEvent(
                contactPortfolio, VISITOR_ID, VISITOR_KEY,
                clientEvent(VisitEventTypeDict.CONTACT_FORM_EXPOSED, "missing-contact")))
                .isInstanceOf(BusinessException.class);
        verify(contactContext.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(contactContext.recordMapper, never()).updateById(any(VisitRecordEntity.class));

        Context scheduleContext = successfulEventContext();
        PortfolioEntity schedulePortfolio = portfolio();
        schedulePortfolio.setPublishedConfigJson(
                schedulePortfolio.getPublishedConfigJson().replace(
                        "\"componentType\":\"SCHEDULE_QUERY\"",
                        "\"componentType\":\"TEXT_SECTION\""));

        assertThatThrownBy(() -> scheduleContext.service.recordScheduleQuery(
                schedulePortfolio, VISITOR_ID, VISITOR_KEY,
                scheduleRequest("missing-schedule", "schedule-1", LocalDate.of(2026, 8, 1))))
                .isInstanceOf(BusinessException.class);
        verify(scheduleContext.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(scheduleContext.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /**
     * metadata 未知敏感键、嵌套值、超长 UTF-8 内容必须固定拒绝且零 Mapper 交互。
     */
    @Test
    void metadataRejectsUnknownSensitiveNestedAndOversizedValuesBeforePersistence() {
        Context context = context();
        List<Map<String, Object>> invalidMetadata = List.of(
                Map.of("access_token", "secret"),
                Map.of("authToken", "secret"),
                Map.of("phoneNumber", "13800000000"),
                Map.of("action", Map.of("nested", true)),
                Map.of("action", List.of("CLICK")),
                Map.of("unknown", "value"),
                Map.of("action", "中".repeat(1200)));
        for (int index = 0; index < invalidMetadata.size(); index++) {
            VisitorTeamPortfolioEventRequest request =
                    clientEvent(VisitEventTypeDict.QR_CODE_INTERACTED, "metadata-" + index);
            request.setMetadata(invalidMetadata.get(index));
            assertThatThrownBy(() -> context.service.recordEvent(
                    portfolio(), VISITOR_ID, VISITOR_KEY, request))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(context.recordMapper, context.eventMapper, context.referenceMapper, context.workMapper);
    }

    /**
     * typed action 优先且只允许安全 scalar，持久化 metadata 必须 canonical 且受 UTF-8 字节限制。
     */
    @Test
    void typedMetadataIsCanonicalAndUtf8Bounded() {
        Context context = successfulEventContext();
        when(context.referenceMapper.selectList(any())).thenReturn(List.of(reference(ReferenceTypeDict.QR_CODE_ASSET.getCode())));
        VisitorTeamPortfolioEventRequest request =
                clientEvent(VisitEventTypeDict.QR_CODE_INTERACTED, "qr-canonical");
        Map<String, Object> compatibility = new LinkedHashMap<>();
        compatibility.put("action", "LONG_PRESS");
        request.setMetadata(compatibility);

        context.service.recordEvent(portfolio(), VISITOR_ID, VISITOR_KEY, request);

        org.mockito.ArgumentCaptor<VisitEventEntity> event =
                org.mockito.ArgumentCaptor.forClass(VisitEventEntity.class);
        verify(context.eventMapper).insert(event.capture());
        assertThat(event.getValue().getMetadata())
                .isEqualTo("{\"action\":\"LONG_PRESS\",\"componentKey\":\"qr-1\"}");
        assertThat(event.getValue().getMetadata().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
    }

    /**
     * 已删除作品集必须在访问记录、引用和事件 Mapper 前拒绝。
     */
    @Test
    void deletedPortfolioIsRejectedBeforeEveryWrite() {
        Context context = context();
        PortfolioEntity deleted = portfolio();
        deleted.setDeleted(99L);

        assertThatThrownBy(() -> context.service.recordOpen(
                deleted, VISITOR_ID, VISITOR_KEY, VisitSourceTypeDict.UNKNOWN.getCode(), "deleted"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(context.recordMapper, context.eventMapper, context.referenceMapper, context.workMapper);
    }

    /**
     * 四个写入口必须保持 rollbackFor Exception。
     */
    @Test
    void everyWriteEntryRollsBackForException() throws Exception {
        assertRollback("recordOpen", PortfolioEntity.class, Long.class, String.class, String.class, String.class);
        assertRollback("recordEvent", PortfolioEntity.class, Long.class, String.class,
                VisitorTeamPortfolioEventRequest.class);
        assertRollback("recordScheduleQuery", PortfolioEntity.class, Long.class, String.class,
                TeamPortfolioScheduleQueryRequest.class);
        assertRollback("recordContactLeadSubmitted", PortfolioEntity.class, Long.class, String.class,
                Long.class, String.class);
    }

    /** 断言可信作品主数据不匹配时拒绝事件且保持零写入。 */
    private static void assertTrustedMediaRejected(
            VisitorTeamPortfolioEventRequest request,
            WorkEntity trustedWork
    ) {
        Context context = context();
        when(context.recordMapper.selectOne(any())).thenReturn(ownedRecord());
        when(context.referenceMapper.selectList(any())).thenReturn(
                List.of(reference(ReferenceTypeDict.WORK.getCode())));
        when(context.workMapper.selectById(request.getWorkId())).thenReturn(trustedWork);

        assertThatThrownBy(() -> context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .isInstanceOf(BusinessException.class);

        org.mockito.InOrder resourceOrder = org.mockito.Mockito.inOrder(
                context.referenceMapper, context.workMapper);
        resourceOrder.verify(context.referenceMapper).selectList(any());
        resourceOrder.verify(context.workMapper).selectById(request.getWorkId());
        verifyNoInteractions(context.eventMapper);
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /** 断言匹配的可信媒体类型允许事件写入，且引用校验先于作品读取。 */
    private static void assertTrustedMediaAccepted(VisitEventTypeDict type, String mediaType) {
        Context context = successfulEventContext();
        VisitorTeamPortfolioEventRequest request = clientEvent(type, "trusted-" + type.getCode());
        request.setMediaType(mediaType);
        when(context.referenceMapper.selectList(any())).thenReturn(
                List.of(reference(ReferenceTypeDict.WORK.getCode())));
        when(context.workMapper.selectById(request.getWorkId())).thenReturn(
                work(request.getWorkId(), mediaType));

        TeamPortfolioVisitService.EventRecordResult result = context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request);

        assertThat(result.recorded()).isTrue();
        org.mockito.InOrder resourceOrder = org.mockito.Mockito.inOrder(
                context.referenceMapper, context.workMapper);
        resourceOrder.verify(context.referenceMapper).selectList(any());
        resourceOrder.verify(context.workMapper).selectById(request.getWorkId());
        verify(context.eventMapper).insert(any(VisitEventEntity.class));
        verify(context.recordMapper).updateById(result.visitRecord());
    }

    /** 断言客户端事件完整载荷变化时幂等冲突且零计数。 */
    private static void assertClientPayloadConflict(
            VisitorTeamPortfolioEventRequest request,
            String existingMetadata,
            ReferenceTypeDict referenceType,
            Consumer<VisitEventEntity> mutation
    ) {
        Context context = context();
        VisitRecordEntity record = ownedRecord();
        VisitEventEntity existing = persistedEvent(record, request, existingMetadata);
        mutation.accept(existing);
        when(context.recordMapper.selectOne(any())).thenReturn(record);
        when(context.referenceMapper.selectList(any())).thenReturn(
                List.of(reference(referenceType.getCode())));
        if (referenceType == ReferenceTypeDict.WORK) {
            when(context.workMapper.selectById(request.getWorkId())).thenReturn(
                    work(request.getWorkId(), request.getMediaType()));
        }
        when(context.eventMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .isInstanceOf(BusinessException.class);

        verify(context.eventMapper, never()).insert(any(VisitEventEntity.class));
        verify(context.recordMapper, never()).updateById(any(VisitRecordEntity.class));
    }

    /** 断言新客户端事件只更新当前类型拥有的汇总字段。 */
    private static void assertCounter(
            VisitEventTypeDict type,
            Consumer<VisitRecordEntity> assertion
    ) {
        Context context = successfulEventContext();
        if (type == VisitEventTypeDict.WORK_VIEWED || type == VisitEventTypeDict.VIDEO_PLAYED) {
            when(context.referenceMapper.selectList(any())).thenReturn(
                    List.of(reference(ReferenceTypeDict.WORK.getCode())));
            when(context.workMapper.selectById(71L)).thenReturn(work(71L, type == VisitEventTypeDict.WORK_VIEWED
                    ? MediaTypeDict.IMAGE.getCode() : MediaTypeDict.VIDEO.getCode()));
        } else if (type == VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED) {
            when(context.referenceMapper.selectList(any())).thenReturn(
                    List.of(reference(ReferenceTypeDict.MEMBER_PORTFOLIO.getCode())));
        } else if (type == VisitEventTypeDict.QR_CODE_INTERACTED) {
            when(context.referenceMapper.selectList(any())).thenReturn(
                    List.of(reference(ReferenceTypeDict.QR_CODE_ASSET.getCode())));
        }
        VisitRecordEntity record = context.service.recordEvent(
                portfolio(), VISITOR_ID, VISITOR_KEY,
                clientEvent(type, "count-" + type.getCode())).visitRecord();
        assertion.accept(record);
    }

    /** 创建可成功写事件的测试上下文。 */
    private static Context successfulEventContext() {
        Context context = context();
        when(context.recordMapper.selectOne(any())).thenReturn(ownedRecord());
        when(context.eventMapper.selectOne(any())).thenReturn(null);
        when(context.eventMapper.insert(any(VisitEventEntity.class))).thenReturn(1);
        when(context.recordMapper.updateById(any(VisitRecordEntity.class))).thenReturn(1);
        return context;
    }

    /** 创建服务测试上下文。 */
    private static Context context() {
        VisitRecordEntityMapper recordMapper = mock(VisitRecordEntityMapper.class);
        VisitEventEntityMapper eventMapper = mock(VisitEventEntityMapper.class);
        PortfolioReferenceEntityMapper referenceMapper = mock(PortfolioReferenceEntityMapper.class);
        WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
        return new Context(
                new TeamPortfolioVisitService(recordMapper, eventMapper, referenceMapper, workMapper),
                recordMapper,
                eventMapper,
                referenceMapper,
                workMapper);
    }

    /** 创建有效团队作品集。 */
    private static PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(PORTFOLIO_ID);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(TEAM_ID);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setShareCode("TPF-TASK8");
        portfolio.setDeleted(0L);
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-team-v1","share":{"title":"团队作品集"},"components":[],
                 "bottomNav":{"enabled":true,"items":[
                   {"key":"nav_home","title":"首页"},
                   {"key":"nav_actions","title":"互动","components":[
                     {"componentKey":"contact-1","componentType":"CONTACT_FORM","enabled":true,"config":{}},
                     {"componentKey":"schedule-1","componentType":"SCHEDULE_QUERY","enabled":true,"config":{}}
                   ]}
                 ]}}
                """);
        return portfolio;
    }

    /** 创建归属正确的访问汇总。 */
    private static VisitRecordEntity ownedRecord() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(VISIT_RECORD_ID);
        record.setVisitorId(VISITOR_ID);
        record.setVisitorKey(VISITOR_KEY);
        record.setPortfolioId(PORTFOLIO_ID);
        record.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        record.setOwnerId(TEAM_ID);
        record.setPortfolioType(PortfolioTypeDict.TEAM.getCode());
        record.setSourceType(VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode());
        record.setVisitCount(1);
        record.setViewWorkCount(0);
        record.setPlayVideoCount(0);
        record.setScheduleQueryCount(0);
        record.setQrActionCount(0);
        record.setContactSubmitCount(0);
        record.setTotalDurationSeconds(0);
        return record;
    }

    /** 创建按类型合法的客户端事件。 */
    private static VisitorTeamPortfolioEventRequest clientEvent(VisitEventTypeDict type, String key) {
        VisitorTeamPortfolioEventRequest request = new VisitorTeamPortfolioEventRequest();
        request.setEventType(type.getCode());
        request.setIdempotencyKey(key);
        switch (type) {
            case WORK_VIEWED -> {
                request.setComponentKey("carousel-1");
                request.setWorkId(71L);
                request.setMediaType("IMAGE");
            }
            case VIDEO_PLAYED -> {
                request.setComponentKey("carousel-1");
                request.setWorkId(71L);
                request.setMediaType("VIDEO");
                request.setDurationSeconds(12);
            }
            case QR_CODE_INTERACTED -> {
                request.setComponentKey("qr-1");
                request.setAction("LONG_PRESS");
            }
            case MEMBER_PORTFOLIO_OPENED -> {
                request.setComponentKey("member-1");
                request.setMemberPortfolioId(77L);
            }
            case CONTACT_FORM_EXPOSED -> request.setComponentKey("contact-1");
            default -> throw new IllegalArgumentException(type.getCode());
        }
        return request;
    }

    /** 创建已持久化事件。 */
    private static VisitEventEntity persistedEvent(
            VisitRecordEntity record,
            VisitorTeamPortfolioEventRequest request,
            String metadata
    ) {
        VisitEventEntity event = new VisitEventEntity();
        event.setId(81L);
        event.setVisitRecordId(record.getId());
        event.setPortfolioId(PORTFOLIO_ID);
        event.setPortfolioRevision(3);
        event.setVisitorKey(VISITOR_KEY);
        event.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        event.setOwnerId(TEAM_ID);
        event.setEventType(request.getEventType());
        event.setWorkId(request.getWorkId());
        event.setQueriedDate(request.getQueriedDate());
        event.setDurationSeconds(request.getDurationSeconds());
        event.setIdempotencyKey(request.getIdempotencyKey());
        event.setMetadata(metadata);
        event.setOccurredAt(LocalDateTime.of(2026, 7, 11, 10, 0));
        return event;
    }

    /** 创建匹配当前事件类型的发布引用。 */
    private static PortfolioReferenceEntity reference(String type) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setId(101L);
        reference.setPortfolioId(PORTFOLIO_ID);
        reference.setConfigScope(PortfolioConfigScopeDict.PUBLISHED.getCode());
        reference.setReferenceType(type);
        reference.setIsValid(1);
        reference.setDeleted(0L);
        if (ReferenceTypeDict.WORK.getCode().equals(type)) {
            reference.setReferenceId(71L);
            reference.setComponentKey("carousel-1");
        } else if (ReferenceTypeDict.MEMBER_PORTFOLIO.getCode().equals(type)) {
            reference.setReferenceId(77L);
            reference.setComponentKey("member-1");
        } else if (ReferenceTypeDict.QR_CODE_ASSET.getCode().equals(type)) {
            reference.setReferenceId(TEAM_ID);
            reference.setComponentKey("qr-1");
        }
        return reference;
    }

    /** 创建可信作品主数据。 */
    private static WorkEntity work(Long workId, String mediaType) {
        WorkEntity work = new WorkEntity();
        work.setId(workId);
        work.setMediaType(mediaType);
        work.setDeleted(0L);
        return work;
    }

    /** 创建团队查档请求。 */
    private static TeamPortfolioScheduleQueryRequest scheduleRequest(
            String idempotencyKey,
            String componentKey,
            LocalDate queriedDate
    ) {
        TeamPortfolioScheduleQueryRequest request = new TeamPortfolioScheduleQueryRequest();
        request.setIdempotencyKey(idempotencyKey);
        request.setComponentKey(componentKey);
        request.setQueriedDate(queriedDate);
        return request;
    }

    /** 断言事务回滚策略。 */
    private static void assertRollback(String method, Class<?>... parameterTypes) throws Exception {
        Transactional transactional = TeamPortfolioVisitService.class
                .getMethod(method, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    /** 测试依赖集合。 */
    private record Context(
            TeamPortfolioVisitService service,
            VisitRecordEntityMapper recordMapper,
            VisitEventEntityMapper eventMapper,
            PortfolioReferenceEntityMapper referenceMapper,
            WorkEntityMapper workMapper
    ) {
    }
}
