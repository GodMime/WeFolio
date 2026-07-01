package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dict.SlotDefinitionStatusDict;
import com.jxc.wefolio.dto.MineScheduleResponse;
import com.jxc.wefolio.dto.ScheduleItemSaveRequest;
import com.jxc.wefolio.dto.ScheduleSlotDefinitionRequest;
import com.jxc.wefolio.dto.ScheduleSlotDefinitionStatusRequest;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的档期服务测试 — 覆盖档位定义、月历聚合和档期记录幂等维护。
 */
@ExtendWith(MockitoExtension.class)
class MineScheduleServiceTest {

    /** 档位定义 Mapper 模拟 */
    @Mock
    private SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 档期 Mapper 模拟 */
    @Mock
    private ScheduleEntityMapper scheduleEntityMapper;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void splitQueriesReturnDefinitionsMonthMarkersAndSelectedDateSchedules() {
        SlotDefinitionEntity welcome = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.ACTIVE);
        SlotDefinitionEntity noon = slotDefinition(2L, "午宴档", "10:00", "13:00", "#1677ff",
                SlotDefinitionStatusDict.DISABLED);
        ScheduleEntity tentative = schedule(11L, 1L, "2026-06-24", "迎亲档", "07:30", "09:30",
                "#d98200", ScheduleStatusDict.TENTATIVE, "陈女士", "13800002026");
        ScheduleEntity booked = schedule(12L, 2L, "2026-06-24", "午宴档", "10:00", "13:00",
                "#1677ff", ScheduleStatusDict.BOOKED, "", "");
        ScheduleEntity otherDay = schedule(13L, 1L, "2026-06-28", "迎亲档", "07:30", "09:30",
                "#d98200", ScheduleStatusDict.BOOKED, "林先生", "13900002026");
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(welcome, noon));
        when(scheduleEntityMapper.selectList(any()))
                .thenReturn(List.of(tentative, booked, otherDay), List.of(tentative, booked));

        List<MineScheduleResponse.SlotDefinitionItem> definitions = service().getSlotDefinitions();
        MineScheduleResponse.MonthOverview month = service().getMonthOverview("2026-06");
        MineScheduleResponse.SelectedDateOverview selectedDate = service().getSelectedDateOverview("2026-06-24");

        assertThat(definitions).extracting(MineScheduleResponse.SlotDefinitionItem::getName)
                .containsExactly("迎亲档", "午宴档");
        assertThat(definitions.get(0).isEnabled()).isTrue();
        assertThat(definitions.get(1).isEnabled()).isFalse();
        assertThat(month.getYearMonth()).isEqualTo("2026-06");
        MineScheduleResponse.MonthDayItem selectedDay = month.getDays().stream()
                .filter(day -> "2026-06-24".equals(day.getDate()))
                .findFirst()
                .orElseThrow();
        assertThat(selectedDay.isSelected()).isFalse();
        assertThat(selectedDay.getColors()).containsExactly("#d98200", "#1677ff");
        assertThat(selectedDay.getCount()).isEqualTo(2);
        assertThat(selectedDate.getDate()).isEqualTo("2026-06-24");
        assertThat(selectedDate.getSummaryText()).isEqualTo("2 条档期");
        assertThat(selectedDate.getSchedules()).extracting(MineScheduleResponse.ScheduleItem::getStatusText)
                .containsExactly("待定", "已约");
    }

    /**
     * 档位定义查询 — 排序由数据库执行，服务层不再重复排序。
     */
    @Test
    void slotDefinitionsQuerySortsByStartTimeAndId() throws Exception {
        SlotDefinitionEntity afternoon = slotDefinition(2L, "午后仪式档", "14:00", "16:00", "#1677ff",
                SlotDefinitionStatusDict.ACTIVE);
        SlotDefinitionEntity morning = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.ACTIVE);
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(morning, afternoon));

        List<MineScheduleResponse.SlotDefinitionItem> response = service().getSlotDefinitions();

        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineScheduleService.java"));
        String findSlotDefinitionsMethod = source.substring(
                source.indexOf("private List<SlotDefinitionEntity> findSlotDefinitions(Long userId)"),
                source.indexOf("/**\n     * 查询用户当月档期。")
        );
        assertThat(findSlotDefinitionsMethod)
                .contains(".orderByAsc(SlotDefinitionEntity::getStartTime)")
                .contains(".orderByAsc(SlotDefinitionEntity::getId)");
        assertThat(response).extracting(MineScheduleResponse.SlotDefinitionItem::getName)
                .containsExactly("迎亲档", "午后仪式档");
    }

    /**
     * 档位定义结构 — 请求、响应和实体不再声明排序字段。
     */
    @Test
    void slotDefinitionContractsDoNotExposeSortOrder() {
        assertThat(fieldNames(ScheduleSlotDefinitionRequest.class)).doesNotContain("sortOrder");
        assertThat(fieldNames(MineScheduleResponse.SlotDefinitionItem.class)).doesNotContain("sortOrder");
        assertThat(fieldNames(SlotDefinitionEntity.class)).doesNotContain("sortOrder");
    }

    @Test
    void splitReadQueriesDoNotResortOrRefilterAlreadyScopedData() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineScheduleService.java"));
        String slotDefinitionsMethod = source.substring(
                source.indexOf("public List<MineScheduleResponse.SlotDefinitionItem> getSlotDefinitions()"),
                source.indexOf("/**\n     * 获取月历档期标记。")
        );
        String selectedDateMethod = source.substring(
                source.indexOf("private MineScheduleResponse.SelectedDateOverview buildSelectedDateOverview("),
                source.indexOf("/**\n     * 构建档位定义响应。")
        );

        assertThat(slotDefinitionsMethod).doesNotContain(".sorted(");
        assertThat(selectedDateMethod).doesNotContain(".filter(schedule -> selectedDate.equals(schedule.getScheduleDate()))");
    }

    @Test
    void createSlotDefinitionTrimsAndDefaultsToActive() {
        ScheduleSlotDefinitionRequest request = new ScheduleSlotDefinitionRequest();
        request.setName(" 迎亲档 ");
        request.setStartTime("07:30");
        request.setEndTime("09:30");
        request.setColor("#d98200");
        when(slotDefinitionEntityMapper.insert(any(SlotDefinitionEntity.class))).thenAnswer(invocation -> {
            SlotDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(33L);
            return 1;
        });

        MineScheduleResponse.SlotDefinitionItem item = service().createSlotDefinition(request);

        ArgumentCaptor<SlotDefinitionEntity> captor = ArgumentCaptor.forClass(SlotDefinitionEntity.class);
        verify(slotDefinitionEntityMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getName()).isEqualTo("迎亲档");
        assertThat(captor.getValue().getStartTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(captor.getValue().getStatus()).isEqualTo(SlotDefinitionStatusDict.ACTIVE.getCode());
        assertThat(item.getId()).isEqualTo(33L);
        assertThat(item.getStatusText()).isEqualTo("启用");
    }

    @Test
    void updateSlotDefinitionRejectsDefinitionsOutsideCurrentUser() {
        when(slotDefinitionEntityMapper.selectById(44L)).thenReturn(slotDefinition(44L, 8L, "迎亲档"));
        ScheduleSlotDefinitionRequest request = new ScheduleSlotDefinitionRequest();
        request.setName("午后仪式");
        request.setStartTime("14:00");
        request.setEndTime("16:00");
        request.setColor("#0f8ea8");

        assertThatThrownBy(() -> service().updateSlotDefinition(44L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档位定义不存在或无访问权限");
        verify(slotDefinitionEntityMapper, never()).updateById(any(SlotDefinitionEntity.class));
    }

    /**
     * 档位定义编辑 — 启用中的定义必须先停用，避免影响正在使用的快照。
     */
    @Test
    void updateSlotDefinitionRejectsActiveDefinition() {
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(slotDefinition(1L, 7L, "迎亲档"));
        ScheduleSlotDefinitionRequest request = new ScheduleSlotDefinitionRequest();
        request.setName("早妆档");
        request.setStartTime("06:30");
        request.setEndTime("08:30");
        request.setColor("#0f8ea8");

        assertThatThrownBy(() -> service().updateSlotDefinition(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该档位启用中，请先停用后再编辑");
        verify(slotDefinitionEntityMapper, never()).updateById(any(SlotDefinitionEntity.class));
        verify(scheduleEntityMapper, never()).update(any(ScheduleEntity.class), any(Wrapper.class));
    }

    @Test
    void updateSlotDefinitionSyncsFutureUnlockedScheduleSnapshots() {
        SlotDefinitionEntity definition = slotDefinition(1L, 7L, "迎亲档");
        definition.setStatus(SlotDefinitionStatusDict.DISABLED.getCode());
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(definition);
        when(slotDefinitionEntityMapper.updateById(any(SlotDefinitionEntity.class))).thenReturn(1);
        when(scheduleEntityMapper.update(any(ScheduleEntity.class), any(Wrapper.class))).thenReturn(3);
        ScheduleSlotDefinitionRequest request = new ScheduleSlotDefinitionRequest();
        request.setName("早妆档");
        request.setStartTime("06:30");
        request.setEndTime("08:30");
        request.setColor("#0f8ea8");
        request.setStatus(SlotDefinitionStatusDict.ACTIVE.getCode());

        MineScheduleResponse.SlotDefinitionItem item = service().updateSlotDefinition(1L, request);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleEntityMapper).update(captor.capture(), any(Wrapper.class));
        assertThat(captor.getValue().getSlotNameSnapshot()).isEqualTo("早妆档");
        assertThat(captor.getValue().getStartTimeSnapshot()).isEqualTo(LocalTime.of(6, 30));
        assertThat(captor.getValue().getEndTimeSnapshot()).isEqualTo(LocalTime.of(8, 30));
        assertThat(captor.getValue().getColorSnapshot()).isEqualTo("#0f8ea8");
        assertThat(item.getName()).isEqualTo("早妆档");
    }

    @Test
    void updateSlotDefinitionStatusOnlyAcceptsKnownStatus() {
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(slotDefinition(1L, 7L, "迎亲档"));
        ScheduleSlotDefinitionStatusRequest request = new ScheduleSlotDefinitionStatusRequest();
        request.setStatus("PAUSED");

        assertThatThrownBy(() -> service().updateSlotDefinitionStatus(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档位定义状态无效");
        verify(slotDefinitionEntityMapper, never()).updateById(any(SlotDefinitionEntity.class));
    }

    @Test
    void saveScheduleItemWithoutIdRejectsExistingDateAndSlot() {
        ScheduleEntity existing = schedule(11L, 1L, "2026-06-24", "迎亲档", "07:30", "09:30",
                "#d98200", ScheduleStatusDict.TENTATIVE, "", "");
        when(scheduleEntityMapper.selectOne(any())).thenReturn(existing);
        ScheduleItemSaveRequest request = scheduleSaveRequest(null, 1L);
        request.setStatus(ScheduleStatusDict.TENTATIVE.getCode());
        request.setContactName("陈女士");
        request.setContactPhone("13800002026");
        request.setNote("今晚回电");

        assertThatThrownBy(() -> service().saveScheduleItem(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当天该档位已存在");

        verify(scheduleEntityMapper, never()).updateById(any(ScheduleEntity.class));
        verify(scheduleEntityMapper, never()).insert(any(ScheduleEntity.class));
    }

    /**
     * 档期状态校验 — 已移除的 AVAILABLE 状态不能继续保存。
     */
    @Test
    void saveScheduleItemRejectsRemovedAvailableStatus() {
        ScheduleItemSaveRequest request = scheduleSaveRequest(null, 1L);
        request.setStatus("AVAILABLE");

        assertThatThrownBy(() -> service().saveScheduleItem(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期状态无效");

        verify(slotDefinitionEntityMapper, never()).selectById(any());
        verify(scheduleEntityMapper, never()).selectOne(any());
        verify(scheduleEntityMapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void saveScheduleItemWithoutIdInsertsWhenDateAndSlotDoNotExist() {
        SlotDefinitionEntity welcome = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.ACTIVE);
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(welcome);
        when(scheduleEntityMapper.selectOne(any())).thenReturn(null);
        when(scheduleEntityMapper.insert(any(ScheduleEntity.class))).thenAnswer(invocation -> {
            ScheduleEntity entity = invocation.getArgument(0);
            entity.setId(88L);
            return 1;
        });
        ScheduleItemSaveRequest request = scheduleSaveRequest(null, 1L);
        request.setStatus(ScheduleStatusDict.BOOKED.getCode());
        request.setContactName("林先生");
        request.setContactPhone("13900002026");
        request.setNote("已收定金");

        MineScheduleResponse.ScheduleItem item = service().saveScheduleItem(request);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleEntityMapper).insert(captor.capture());
        verify(scheduleEntityMapper, never()).updateById(any(ScheduleEntity.class));
        ScheduleEntity inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getScheduleDate()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(inserted.getSlotDefinitionId()).isEqualTo(1L);
        assertThat(inserted.getSlotNameSnapshot()).isEqualTo("迎亲档");
        assertThat(inserted.getStartTimeSnapshot()).isEqualTo(LocalTime.of(7, 30));
        assertThat(inserted.getEndTimeSnapshot()).isEqualTo(LocalTime.of(9, 30));
        assertThat(inserted.getColorSnapshot()).isEqualTo("#d98200");
        assertThat(inserted.getStatus()).isEqualTo(ScheduleStatusDict.BOOKED.getCode());
        assertThat(inserted.getContactNameCiphertext()).isEqualTo("林先生");
        assertThat(inserted.getContactPhoneCiphertext()).isEqualTo("13900002026");
        assertThat(inserted.getNote()).isEqualTo("已收定金");
        assertThat(inserted.getLockedSnapshot()).isZero();
        assertThat(inserted.getCreatedAt()).isNull();
        assertThat(inserted.getUpdatedAt()).isNull();
        assertThat(item.getId()).isEqualTo(88L);
        assertThat(item.getStatusTone()).isEqualTo(ScheduleStatusDict.BOOKED.getTone());
    }

    /**
     * 档期编辑 — 原档位停用后，仍允许修改已有档期的联系人、状态和备注。
     */
    @Test
    void saveScheduleItemWithIdAllowsExistingDisabledSlotDefinition() {
        SlotDefinitionEntity disabled = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.DISABLED);
        when(scheduleEntityMapper.selectById(77L)).thenReturn(schedule(77L, 7L, 1L));
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(disabled);
        when(scheduleEntityMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        ScheduleItemSaveRequest request = scheduleSaveRequest(77L, 1L);
        request.setStatus(ScheduleStatusDict.BOOKED.getCode());
        request.setContactName("陈女士");
        request.setContactPhone("13800002026");
        request.setNote("已确认");

        MineScheduleResponse.ScheduleItem item = service().saveScheduleItem(request);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleEntityMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(77L);
        assertThat(captor.getValue().getSlotDefinitionId()).isEqualTo(1L);
        assertThat(captor.getValue().getStatus()).isEqualTo(ScheduleStatusDict.BOOKED.getCode());
        assertThat(captor.getValue().getContactNameCiphertext()).isEqualTo("陈女士");
        assertThat(captor.getValue().getContactPhoneCiphertext()).isEqualTo("13800002026");
        assertThat(captor.getValue().getNote()).isEqualTo("已确认");
        assertThat(item.getId()).isEqualTo(77L);
        assertThat(item.getStatusText()).isEqualTo("已约");
    }

    @Test
    void saveScheduleItemWithIdRejectsRecordsOutsideCurrentUser() {
        when(scheduleEntityMapper.selectById(77L)).thenReturn(schedule(77L, 8L, 1L));
        ScheduleItemSaveRequest request = scheduleSaveRequest(77L, 1L);

        assertThatThrownBy(() -> service().saveScheduleItem(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期记录不存在或无访问权限");
        verify(scheduleEntityMapper, never()).updateById(any(ScheduleEntity.class));
    }

    @Test
    void deleteScheduleItemDeletesOnlyCurrentUserRecord() {
        when(scheduleEntityMapper.selectById(11L)).thenReturn(schedule(11L, 7L, 1L));
        when(scheduleEntityMapper.delete(any(Wrapper.class))).thenReturn(1);

        service().deleteScheduleItem(11L);

        verify(scheduleEntityMapper).delete(any(Wrapper.class));
    }

    /**
     * 档位定义删除 — 启用中的定义不能直接删除。
     */
    @Test
    void deleteSlotDefinitionRejectsActiveDefinition() {
        SlotDefinitionEntity definition = slotDefinition(1L, 7L, "迎亲档");
        definition.setStatus(SlotDefinitionStatusDict.ACTIVE.getCode());
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(definition);

        assertThatThrownBy(() -> service().deleteSlotDefinition(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先停用档位后再删除");
        verify(scheduleEntityMapper, never()).selectList(any());
        verify(slotDefinitionEntityMapper, never()).delete(any(Wrapper.class));
    }

    /**
     * 档位定义删除 — 删除前提示最早几个关联档期日期。
     */
    @Test
    void deleteSlotDefinitionReportsScheduleDatesBeforeDelete() {
        SlotDefinitionEntity definition = slotDefinition(1L, 7L, "迎亲档");
        definition.setStatus(SlotDefinitionStatusDict.DISABLED.getCode());
        ScheduleEntity first = schedule(21L, 7L, 1L);
        ScheduleEntity second = schedule(22L, 7L, 1L);
        ScheduleEntity third = schedule(23L, 7L, 1L);
        ScheduleEntity fourth = schedule(24L, 7L, 1L);
        first.setScheduleDate(LocalDate.of(2026, 6, 24));
        second.setScheduleDate(LocalDate.of(2026, 6, 25));
        third.setScheduleDate(LocalDate.of(2026, 6, 26));
        fourth.setScheduleDate(LocalDate.of(2026, 6, 27));
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(definition);
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of(first, second, third, fourth));

        assertThatThrownBy(() -> service().deleteSlotDefinition(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该档位已用于 2026-06-24、2026-06-25、2026-06-26 等档期，请先删除对应档期后再删除档位");
        verify(slotDefinitionEntityMapper, never()).delete(any(Wrapper.class));
    }

    /**
     * 档位定义删除 — 即使最早返回的档期集中在同一天，也要提示还有更多关联档期。
     */
    @Test
    void deleteSlotDefinitionReportsMoreSchedulesOnSameDate() {
        SlotDefinitionEntity definition = slotDefinition(1L, 7L, "迎亲档");
        definition.setStatus(SlotDefinitionStatusDict.DISABLED.getCode());
        ScheduleEntity first = schedule(21L, 7L, 1L);
        ScheduleEntity second = schedule(22L, 7L, 1L);
        ScheduleEntity third = schedule(23L, 7L, 1L);
        ScheduleEntity fourth = schedule(24L, 7L, 1L);
        first.setScheduleDate(LocalDate.of(2026, 6, 24));
        second.setScheduleDate(LocalDate.of(2026, 6, 24));
        third.setScheduleDate(LocalDate.of(2026, 6, 24));
        fourth.setScheduleDate(LocalDate.of(2026, 6, 24));
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(definition);
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of(first, second, third, fourth));

        assertThatThrownBy(() -> service().deleteSlotDefinition(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该档位已用于 2026-06-24 等档期，请先删除对应档期后再删除档位");
        verify(slotDefinitionEntityMapper, never()).delete(any(Wrapper.class));
    }

    /**
     * 档位定义删除 — 停用且无关联档期时允许逻辑删除。
     */
    @Test
    void deleteSlotDefinitionDeletesDisabledDefinitionWithoutSchedules() {
        SlotDefinitionEntity definition = slotDefinition(1L, 7L, "迎亲档");
        definition.setStatus(SlotDefinitionStatusDict.DISABLED.getCode());
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(definition);
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of());
        when(slotDefinitionEntityMapper.delete(any(Wrapper.class))).thenReturn(1);

        service().deleteSlotDefinition(1L);

        verify(scheduleEntityMapper).selectList(any());
        verify(slotDefinitionEntityMapper).delete(any(Wrapper.class));
    }

    /**
     * 创建待测服务。
     *
     * @return 我的档期服务
     */
    private MineScheduleService service() {
        return new MineScheduleService(slotDefinitionEntityMapper, scheduleEntityMapper);
    }

    /**
     * 获取类声明字段名。
     *
     * @param targetClass 目标类
     * @return 字段名列表
     */
    private List<String> fieldNames(Class<?> targetClass) {
        return Arrays.stream(targetClass.getDeclaredFields()).map(Field::getName).toList();
    }

    /**
     * 构建档位定义。
     *
     * @param id 档位 ID
     * @param name 名称
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param color 颜色
     * @param status 状态
     * @return 档位定义实体
     */
    private SlotDefinitionEntity slotDefinition(
            Long id,
            String name,
            String startTime,
            String endTime,
            String color,
            SlotDefinitionStatusDict status
    ) {
        SlotDefinitionEntity entity = slotDefinition(id, 7L, name);
        entity.setStartTime(LocalTime.parse(startTime));
        entity.setEndTime(LocalTime.parse(endTime));
        entity.setColor(color);
        entity.setStatus(status.getCode());
        entity.setIsSystemDefault(0);
        return entity;
    }

    /**
     * 构建最小档位定义。
     *
     * @param id 档位 ID
     * @param userId 用户 ID
     * @param name 名称
     * @return 档位定义实体
     */
    private SlotDefinitionEntity slotDefinition(Long id, Long userId, String name) {
        SlotDefinitionEntity entity = new SlotDefinitionEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setName(name);
        entity.setStartTime(LocalTime.of(7, 30));
        entity.setEndTime(LocalTime.of(9, 30));
        entity.setColor("#d98200");
        entity.setIsSystemDefault(0);
        entity.setStatus(SlotDefinitionStatusDict.ACTIVE.getCode());
        return entity;
    }

    /**
     * 构建档期记录。
     *
     * @param id 档期 ID
     * @param slotDefinitionId 档位定义 ID
     * @param date 日期
     * @param slotName 档位名称
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param color 颜色
     * @param status 状态
     * @param contactName 联系人
     * @param contactPhone 联系电话
     * @return 档期实体
     */
    private ScheduleEntity schedule(
            Long id,
            Long slotDefinitionId,
            String date,
            String slotName,
            String startTime,
            String endTime,
            String color,
            ScheduleStatusDict status,
            String contactName,
            String contactPhone
    ) {
        ScheduleEntity entity = schedule(id, 7L, slotDefinitionId);
        entity.setScheduleDate(LocalDate.parse(date));
        entity.setSlotNameSnapshot(slotName);
        entity.setStartTimeSnapshot(LocalTime.parse(startTime));
        entity.setEndTimeSnapshot(LocalTime.parse(endTime));
        entity.setColorSnapshot(color);
        entity.setStatus(status.getCode());
        entity.setContactNameCiphertext(contactName);
        entity.setContactPhoneCiphertext(contactPhone);
        return entity;
    }

    /**
     * 构建最小档期记录。
     *
     * @param id 档期 ID
     * @param userId 用户 ID
     * @param slotDefinitionId 档位定义 ID
     * @return 档期实体
     */
    private ScheduleEntity schedule(Long id, Long userId, Long slotDefinitionId) {
        ScheduleEntity entity = new ScheduleEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setScheduleDate(LocalDate.of(2026, 6, 24));
        entity.setSlotDefinitionId(slotDefinitionId);
        entity.setSlotNameSnapshot("迎亲档");
        entity.setStartTimeSnapshot(LocalTime.of(7, 30));
        entity.setEndTimeSnapshot(LocalTime.of(9, 30));
        entity.setColorSnapshot("#d98200");
        entity.setStatus(ScheduleStatusDict.TENTATIVE.getCode());
        entity.setLockedSnapshot(0);
        return entity;
    }

    /**
     * 构建保存请求。
     *
     * @param scheduleId 档期 ID
     * @param slotDefinitionId 档位定义 ID
     * @return 保存请求
     */
    private ScheduleItemSaveRequest scheduleSaveRequest(Long scheduleId, Long slotDefinitionId) {
        ScheduleItemSaveRequest request = new ScheduleItemSaveRequest();
        request.setScheduleId(scheduleId);
        request.setScheduleDate("2026-06-24");
        request.setSlotDefinitionId(slotDefinitionId);
        request.setStatus(ScheduleStatusDict.TENTATIVE.getCode());
        return request;
    }
}
