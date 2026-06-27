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
    void overviewReturnsDefinitionsMonthMarkersAndSelectedDateSchedules() {
        SlotDefinitionEntity welcome = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.ACTIVE);
        SlotDefinitionEntity noon = slotDefinition(2L, "午宴档", "10:00", "13:00", "#1677ff",
                SlotDefinitionStatusDict.DISABLED);
        ScheduleEntity tentative = schedule(11L, 1L, "2026-06-24", "迎亲档", "07:30", "09:30",
                "#d98200", ScheduleStatusDict.TENTATIVE, "陈女士", "13800002026");
        ScheduleEntity available = schedule(12L, 2L, "2026-06-24", "午宴档", "10:00", "13:00",
                "#1677ff", ScheduleStatusDict.AVAILABLE, "", "");
        ScheduleEntity otherDay = schedule(13L, 1L, "2026-06-28", "迎亲档", "07:30", "09:30",
                "#d98200", ScheduleStatusDict.BOOKED, "林先生", "13900002026");
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(welcome, noon));
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of(tentative, available, otherDay));

        MineScheduleResponse response = service().getScheduleOverview("2026-06", "2026-06-24");

        assertThat(response.getSlotDefinitions()).extracting(MineScheduleResponse.SlotDefinitionItem::getName)
                .containsExactly("迎亲档", "午宴档");
        assertThat(response.getSlotDefinitions().get(0).isEnabled()).isTrue();
        assertThat(response.getSlotDefinitions().get(1).isEnabled()).isFalse();
        assertThat(response.getMonth().getYearMonth()).isEqualTo("2026-06");
        MineScheduleResponse.MonthDayItem selectedDay = response.getMonth().getDays().stream()
                .filter(day -> "2026-06-24".equals(day.getDate()))
                .findFirst()
                .orElseThrow();
        assertThat(selectedDay.isSelected()).isTrue();
        assertThat(selectedDay.getColors()).containsExactly("#d98200", "#1677ff");
        assertThat(selectedDay.getCount()).isEqualTo(2);
        assertThat(response.getSelectedDate().getDate()).isEqualTo("2026-06-24");
        assertThat(response.getSelectedDate().getSummaryText()).isEqualTo("2 条档期，1 个可约");
        assertThat(response.getSelectedDate().getSchedules()).extracting(MineScheduleResponse.ScheduleItem::getStatusText)
                .containsExactly("待定", "空闲");
    }

    /**
     * 档位定义聚合 — 即使 Mapper 返回顺序不稳定，也按开始时间和 ID 升序输出。
     */
    @Test
    void overviewSortsSlotDefinitionsByStartTimeAndId() {
        SlotDefinitionEntity afternoon = slotDefinition(2L, "午后仪式档", "14:00", "16:00", "#1677ff",
                SlotDefinitionStatusDict.ACTIVE);
        SlotDefinitionEntity morning = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.ACTIVE);
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(afternoon, morning));
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of());

        MineScheduleResponse response = service().getScheduleOverview("2026-06", "2026-06-24");

        assertThat(response.getSlotDefinitions()).extracting(MineScheduleResponse.SlotDefinitionItem::getName)
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

    @Test
    void updateSlotDefinitionSyncsFutureUnlockedScheduleSnapshots() {
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(slotDefinition(1L, 7L, "迎亲档"));
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
    void saveScheduleItemWithoutIdUpdatesExistingDateAndSlot() {
        SlotDefinitionEntity welcome = slotDefinition(1L, "迎亲档", "07:30", "09:30", "#d98200",
                SlotDefinitionStatusDict.ACTIVE);
        ScheduleEntity existing = schedule(11L, 1L, "2026-06-24", "迎亲档", "07:30", "09:30",
                "#d98200", ScheduleStatusDict.AVAILABLE, "", "");
        when(slotDefinitionEntityMapper.selectById(1L)).thenReturn(welcome);
        when(scheduleEntityMapper.selectOne(any())).thenReturn(existing);
        when(scheduleEntityMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        ScheduleItemSaveRequest request = scheduleSaveRequest(null, 1L);
        request.setStatus(ScheduleStatusDict.TENTATIVE.getCode());
        request.setContactName("陈女士");
        request.setContactPhone("13800002026");
        request.setNote("今晚回电");

        MineScheduleResponse.ScheduleItem item = service().saveScheduleItem(request);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleEntityMapper).updateById(captor.capture());
        verify(scheduleEntityMapper, never()).insert(any(ScheduleEntity.class));
        assertThat(captor.getValue().getId()).isEqualTo(11L);
        assertThat(captor.getValue().getStatus()).isEqualTo(ScheduleStatusDict.TENTATIVE.getCode());
        assertThat(captor.getValue().getContactPhoneCiphertext()).isEqualTo("13800002026");
        assertThat(item.getId()).isEqualTo(11L);
        assertThat(item.getStatusText()).isEqualTo("待定");
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
        assertThat(inserted.getCreatedAt()).isNotNull();
        assertThat(inserted.getUpdatedAt()).isNotNull();
        assertThat(item.getId()).isEqualTo(88L);
        assertThat(item.getStatusTone()).isEqualTo(ScheduleStatusDict.BOOKED.getTone());
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
        entity.setStatus(ScheduleStatusDict.AVAILABLE.getCode());
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
        request.setStatus(ScheduleStatusDict.AVAILABLE.getCode());
        return request;
    }
}
