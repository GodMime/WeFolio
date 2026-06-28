package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 我的档期服务 — 负责维护者个人档位定义、月历聚合和档期记录维护。
 */
@Service
@RequiredArgsConstructor
public class MineScheduleService {

    /** 档位名称最大长度 */
    private static final int SLOT_NAME_MAX_LENGTH = 30;

    /** 联系人姓名最大长度 */
    private static final int CONTACT_NAME_MAX_LENGTH = 50;

    /** 联系电话最大长度 */
    private static final int CONTACT_PHONE_MAX_LENGTH = 50;

    /** 档期备注最大长度 */
    private static final int NOTE_MAX_LENGTH = 1000;

    /** 重复档期提示 */
    private static final String SCHEDULE_DUPLICATE_MESSAGE = "当天该档位已存在";

    /** 月历固定格子数 */
    private static final int CALENDAR_DAY_COUNT = 42;

    /** 未锁定快照 */
    private static final int SNAPSHOT_UNLOCKED = 0;

    /** 十六进制颜色格式 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** 月份格式 */
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 日期格式 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 时间格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 档位定义 Mapper */
    private final SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 档期 Mapper */
    private final ScheduleEntityMapper scheduleEntityMapper;

    /**
     * 获取档期聚合数据。
     *
     * @param monthText 月份，格式 yyyy-MM
     * @param dateText 选中日期，格式 yyyy-MM-dd
     * @return 档期聚合响应
     */
    public MineScheduleResponse getScheduleOverview(String monthText, String dateText) {
        Long userId = AuthContextHolder.requireUserId();
        YearMonth month = parseYearMonth(monthText);
        LocalDate selectedDate = parseSelectedDate(dateText, month);
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();

        List<SlotDefinitionEntity> definitions = safeList(slotDefinitionEntityMapper.selectList(
                Wrappers.lambdaQuery(SlotDefinitionEntity.class)
                        .eq(SlotDefinitionEntity::getUserId, userId)
                        .orderByAsc(SlotDefinitionEntity::getStartTime)
                        .orderByAsc(SlotDefinitionEntity::getId)
        ));
        List<ScheduleEntity> monthSchedules = safeList(scheduleEntityMapper.selectList(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, userId)
                        .ge(ScheduleEntity::getScheduleDate, startDate)
                        .le(ScheduleEntity::getScheduleDate, endDate)
                        .orderByAsc(ScheduleEntity::getScheduleDate)
                        .orderByAsc(ScheduleEntity::getStartTimeSnapshot)
                        .orderByAsc(ScheduleEntity::getId)
        ));

        MineScheduleResponse response = new MineScheduleResponse();
        response.setSlotDefinitions(definitions.stream()
                .sorted(Comparator.comparing(SlotDefinitionEntity::getStartTime)
                        .thenComparing(SlotDefinitionEntity::getId))
                .map(this::buildSlotDefinitionItem)
                .toList());
        response.setMonth(buildMonthOverview(month, selectedDate, monthSchedules));
        response.setSelectedDate(buildSelectedDateOverview(selectedDate, monthSchedules));
        return response;
    }

    /**
     * 新增档位定义。
     *
     * @param request 保存请求
     * @return 新增后的档位定义
     */
    @Transactional(rollbackFor = Exception.class)
    public MineScheduleResponse.SlotDefinitionItem createSlotDefinition(ScheduleSlotDefinitionRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        SlotDefinitionEntity entity = new SlotDefinitionEntity();
        applySlotDefinitionRequest(entity, request, true);
        entity.setUserId(userId);
        entity.setIsSystemDefault(0);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            slotDefinitionEntityMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("档位名称已存在", e);
        }
        if (entity.getId() == null) {
            throw new BusinessException("档位定义保存失败，请重试");
        }
        return buildSlotDefinitionItem(entity);
    }

    /**
     * 编辑档位定义。
     *
     * @param slotDefinitionId 档位定义 ID
     * @param request 保存请求
     * @return 更新后的档位定义
     */
    @Transactional(rollbackFor = Exception.class)
    public MineScheduleResponse.SlotDefinitionItem updateSlotDefinition(
            Long slotDefinitionId,
            ScheduleSlotDefinitionRequest request
    ) {
        SlotDefinitionEntity entity = requireOwnedSlotDefinition(slotDefinitionId);
        if (SlotDefinitionStatusDict.ACTIVE.getCode().equals(entity.getStatus())) {
            throw new BusinessException("该档位启用中，请先停用后再编辑");
        }
        applySlotDefinitionRequest(entity, request, false);
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            int updated = slotDefinitionEntityMapper.updateById(entity);
            if (updated <= 0) {
                throw new BusinessException("档位定义保存失败，请重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("档位名称已存在", e);
        }
        syncFutureUnlockedScheduleSnapshots(entity);
        return buildSlotDefinitionItem(entity);
    }

    /**
     * 更新档位定义启停状态。
     *
     * @param slotDefinitionId 档位定义 ID
     * @param request 状态请求
     * @return 更新后的档位定义
     */
    @Transactional(rollbackFor = Exception.class)
    public MineScheduleResponse.SlotDefinitionItem updateSlotDefinitionStatus(
            Long slotDefinitionId,
            ScheduleSlotDefinitionStatusRequest request
    ) {
        SlotDefinitionEntity entity = requireOwnedSlotDefinition(slotDefinitionId);
        SlotDefinitionStatusDict status = parseSlotStatus(request == null ? null : request.getStatus());
        entity.setStatus(status.getCode());
        entity.setUpdatedAt(LocalDateTime.now());
        int updated = slotDefinitionEntityMapper.updateById(entity);
        if (updated <= 0) {
            throw new BusinessException("档位定义保存失败，请重试");
        }
        return buildSlotDefinitionItem(entity);
    }

    /**
     * 删除停用档位定义。
     *
     * <p>已有档期会保留档位快照。为了避免维护者误删仍在使用的定义，删除前必须先清空关联档期。</p>
     *
     * @param slotDefinitionId 档位定义 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSlotDefinition(Long slotDefinitionId) {
        SlotDefinitionEntity entity = requireOwnedSlotDefinition(slotDefinitionId);
        if (!SlotDefinitionStatusDict.DISABLED.getCode().equals(entity.getStatus())) {
            throw new BusinessException("请先停用档位后再删除");
        }
        List<ScheduleEntity> schedules = findSlotDefinitionSchedules(entity);
        if (!schedules.isEmpty()) {
            throw new BusinessException(buildSlotDefinitionDeleteBlockedMessage(schedules));
        }
        int deleted = slotDefinitionEntityMapper.delete(
                Wrappers.lambdaQuery(SlotDefinitionEntity.class)
                        .eq(SlotDefinitionEntity::getId, entity.getId())
                        .eq(SlotDefinitionEntity::getUserId, entity.getUserId())
        );
        if (deleted <= 0) {
            throw new BusinessException("档位定义删除失败，请刷新后重试");
        }
    }

    /**
     * 保存档期记录。传入 ID 时更新指定记录，未传 ID 时新增记录，若同日同档位已存在则报错。
     *
     * @param request 保存请求
     * @return 保存后的档期记录
     */
    @Transactional(rollbackFor = Exception.class)
    public MineScheduleResponse.ScheduleItem saveScheduleItem(ScheduleItemSaveRequest request) {
        if (request == null) {
            throw new BusinessException("档期内容不能为空");
        }
        Long userId = AuthContextHolder.requireUserId();
        ScheduleStatusDict scheduleStatus = parseScheduleStatus(request.getStatus());
        ScheduleEntity target;
        if (request.getScheduleId() == null) {
            if (findExistingSchedule(userId, request) != null) {
                throw new BusinessException(SCHEDULE_DUPLICATE_MESSAGE);
            }
            target = new ScheduleEntity();
            target.setUserId(userId);
            target.setCreatedAt(LocalDateTime.now());
            target.setLockedSnapshot(SNAPSHOT_UNLOCKED);
        } else {
            target = requireOwnedSchedule(request.getScheduleId());
        }
        SlotDefinitionEntity definition = requireSlotDefinitionForSchedule(userId, request.getSlotDefinitionId(), target);
        applyScheduleRequest(target, definition, scheduleStatus, request);
        target.setUpdatedAt(LocalDateTime.now());
        try {
            if (target.getId() == null) {
                scheduleEntityMapper.insert(target);
            } else {
                int updated = scheduleEntityMapper.updateById(target);
                if (updated <= 0) {
                    throw new BusinessException("档期保存失败，请刷新后重试");
                }
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(SCHEDULE_DUPLICATE_MESSAGE, e);
        }
        return buildScheduleItem(target);
    }

    /**
     * 删除档期记录。
     *
     * @param scheduleId 档期 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteScheduleItem(Long scheduleId) {
        Long userId = AuthContextHolder.requireUserId();
        ScheduleEntity entity = requireOwnedSchedule(scheduleId);
        int deleted = scheduleEntityMapper.delete(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getId, entity.getId())
                        .eq(ScheduleEntity::getUserId, userId)
        );
        if (deleted <= 0) {
            throw new BusinessException("档期删除失败，请刷新后重试");
        }
    }

    /**
     * 应用档位定义保存请求。
     *
     * @param entity 目标实体
     * @param request 请求
     * @param create 是否新增
     */
    private void applySlotDefinitionRequest(
            SlotDefinitionEntity entity,
            ScheduleSlotDefinitionRequest request,
            boolean create
    ) {
        if (request == null) {
            throw new BusinessException("档位定义内容不能为空");
        }
        entity.setName(normalizeRequiredString(request.getName(), SLOT_NAME_MAX_LENGTH, "档位名称"));
        LocalTime startTime = parseTime(request.getStartTime(), "开始时间");
        LocalTime endTime = parseTime(request.getEndTime(), "结束时间");
        if (!startTime.isBefore(endTime)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }
        entity.setStartTime(startTime);
        entity.setEndTime(endTime);
        entity.setColor(normalizeColor(request.getColor()));
        if (create && (request.getStatus() == null || request.getStatus().isBlank())) {
            entity.setStatus(SlotDefinitionStatusDict.ACTIVE.getCode());
            return;
        }
        entity.setStatus(parseSlotStatus(request.getStatus()).getCode());
    }

    /**
     * 应用档期保存请求。
     *
     * @param entity 档期实体
     * @param definition 档位定义
     * @param request 保存请求
     */
    private void applyScheduleRequest(
            ScheduleEntity entity,
            SlotDefinitionEntity definition,
            ScheduleStatusDict scheduleStatus,
            ScheduleItemSaveRequest request
    ) {
        entity.setScheduleDate(parseDate(request.getScheduleDate(), "档期日期"));
        entity.setSlotDefinitionId(definition.getId());
        entity.setSlotNameSnapshot(definition.getName());
        entity.setStartTimeSnapshot(definition.getStartTime());
        entity.setEndTimeSnapshot(definition.getEndTime());
        entity.setColorSnapshot(definition.getColor());
        entity.setStatus(scheduleStatus.getCode());
        entity.setContactNameCiphertext(normalizeOptionalString(
                request.getContactName(), CONTACT_NAME_MAX_LENGTH, "联系人姓名"));
        entity.setContactPhoneCiphertext(normalizeOptionalString(
                request.getContactPhone(), CONTACT_PHONE_MAX_LENGTH, "联系电话"));
        entity.setNote(normalizeOptionalString(request.getNote(), NOTE_MAX_LENGTH, "档期备注"));
        if (entity.getLockedSnapshot() == null) {
            entity.setLockedSnapshot(SNAPSHOT_UNLOCKED);
        }
    }

    /**
     * 同步未来未锁定档期的档位快照。
     *
     * @param definition 档位定义
     */
    private void syncFutureUnlockedScheduleSnapshots(SlotDefinitionEntity definition) {
        ScheduleEntity snapshot = new ScheduleEntity();
        snapshot.setSlotNameSnapshot(definition.getName());
        snapshot.setStartTimeSnapshot(definition.getStartTime());
        snapshot.setEndTimeSnapshot(definition.getEndTime());
        snapshot.setColorSnapshot(definition.getColor());
        snapshot.setUpdatedAt(LocalDateTime.now());
        scheduleEntityMapper.update(
                snapshot,
                Wrappers.lambdaUpdate(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, definition.getUserId())
                        .eq(ScheduleEntity::getSlotDefinitionId, definition.getId())
                        .eq(ScheduleEntity::getLockedSnapshot, SNAPSHOT_UNLOCKED)
                        .ge(ScheduleEntity::getScheduleDate, LocalDate.now())
        );
    }

    /**
     * 查询阻止档位定义删除的关联档期。
     *
     * @param definition 档位定义
     * @return 最早的关联档期列表
     */
    private List<ScheduleEntity> findSlotDefinitionSchedules(SlotDefinitionEntity definition) {
        return safeList(scheduleEntityMapper.selectList(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, definition.getUserId())
                        .eq(ScheduleEntity::getSlotDefinitionId, definition.getId())
                        .orderByAsc(ScheduleEntity::getScheduleDate)
                        .last("LIMIT 4")
        ));
    }

    /**
     * 构建档位定义删除被阻止时的提示语。
     *
     * @param schedules 关联档期列表
     * @return 提示语
     */
    private String buildSlotDefinitionDeleteBlockedMessage(List<ScheduleEntity> schedules) {
        List<String> dates = schedules.stream()
                .map(ScheduleEntity::getScheduleDate)
                .filter(Objects::nonNull)
                .distinct()
                .map(date -> date.format(DATE_FORMATTER))
                .limit(4)
                .toList();
        if (dates.isEmpty()) {
            return "该档位已有关联档期，请先删除对应档期后再删除档位";
        }
        boolean hasMore = schedules.size() > 3;
        String dateText = dates.stream()
                .limit(3)
                .collect(Collectors.joining("、"));
        return "该档位已用于 " + dateText + (hasMore ? " 等档期" : " 的档期")
                + "，请先删除对应档期后再删除档位";
    }

    /**
     * 查询已有档期记录。
     *
     * @param userId 用户 ID
     * @param request 请求
     * @return 已有记录，未命中返回空
     */
    private ScheduleEntity findExistingSchedule(Long userId, ScheduleItemSaveRequest request) {
        if (request.getSlotDefinitionId() == null) {
            throw new BusinessException("请选择档位定义");
        }
        return scheduleEntityMapper.selectOne(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, userId)
                        .eq(ScheduleEntity::getScheduleDate, parseDate(request.getScheduleDate(), "档期日期"))
                        .eq(ScheduleEntity::getSlotDefinitionId, request.getSlotDefinitionId())
                        .last("LIMIT 1")
        );
    }

    /**
     * 获取当前用户档位定义。
     *
     * @param slotDefinitionId 档位定义 ID
     * @return 档位定义
     */
    private SlotDefinitionEntity requireOwnedSlotDefinition(Long slotDefinitionId) {
        Long userId = AuthContextHolder.requireUserId();
        if (slotDefinitionId == null) {
            throw new BusinessException("档位定义不存在或无访问权限");
        }
        SlotDefinitionEntity entity = slotDefinitionEntityMapper.selectById(slotDefinitionId);
        if (entity == null || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException("档位定义不存在或无访问权限");
        }
        return entity;
    }

    /**
     * 获取用于维护档期的档位定义。
     *
     * <p>新增档期或切换档位时必须选择启用定义；编辑原有关联档位时允许定义已停用。</p>
     *
     * @param userId 用户 ID
     * @param slotDefinitionId 档位定义 ID
     * @param currentSchedule 当前档期记录
     * @return 档位定义
     */
    private SlotDefinitionEntity requireSlotDefinitionForSchedule(
            Long userId,
            Long slotDefinitionId,
            ScheduleEntity currentSchedule
    ) {
        if (slotDefinitionId == null) {
            throw new BusinessException("请选择档位定义");
        }
        SlotDefinitionEntity entity = slotDefinitionEntityMapper.selectById(slotDefinitionId);
        if (entity == null || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException("档位定义不存在或无访问权限");
        }
        if (currentSchedule.getId() != null && Objects.equals(currentSchedule.getSlotDefinitionId(), entity.getId())) {
            return entity;
        }
        if (!SlotDefinitionStatusDict.ACTIVE.getCode().equals(entity.getStatus())) {
            throw new BusinessException("停用档位不能维护新档期");
        }
        return entity;
    }

    /**
     * 获取当前用户档期记录。
     *
     * @param scheduleId 档期 ID
     * @return 档期记录
     */
    private ScheduleEntity requireOwnedSchedule(Long scheduleId) {
        Long userId = AuthContextHolder.requireUserId();
        if (scheduleId == null) {
            throw new BusinessException("档期记录不存在或无访问权限");
        }
        ScheduleEntity entity = scheduleEntityMapper.selectById(scheduleId);
        if (entity == null || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException("档期记录不存在或无访问权限");
        }
        return entity;
    }

    /**
     * 构建月历概览。
     *
     * @param month 月份
     * @param selectedDate 选中日期
     * @param schedules 当月档期
     * @return 月历概览
     */
    private MineScheduleResponse.MonthOverview buildMonthOverview(
            YearMonth month,
            LocalDate selectedDate,
            List<ScheduleEntity> schedules
    ) {
        Map<LocalDate, List<ScheduleEntity>> schedulesByDate = schedules.stream()
                .collect(Collectors.groupingBy(ScheduleEntity::getScheduleDate, LinkedHashMap::new, Collectors.toList()));
        LocalDate firstDay = month.atDay(1);
        int offset = firstDay.getDayOfWeek().getValue() % DayOfWeek.SUNDAY.getValue();
        LocalDate calendarStart = firstDay.minusDays(offset);
        MineScheduleResponse.MonthOverview overview = new MineScheduleResponse.MonthOverview();
        overview.setYearMonth(month.format(YEAR_MONTH_FORMATTER));
        List<MineScheduleResponse.MonthDayItem> days = new ArrayList<>();
        for (int index = 0; index < CALENDAR_DAY_COUNT; index++) {
            LocalDate date = calendarStart.plusDays(index);
            List<ScheduleEntity> daySchedules = schedulesByDate.getOrDefault(date, List.of()).stream()
                    .sorted(Comparator.comparing(ScheduleEntity::getStartTimeSnapshot)
                            .thenComparing(ScheduleEntity::getId))
                    .toList();
            MineScheduleResponse.MonthDayItem item = new MineScheduleResponse.MonthDayItem();
            item.setDate(date.format(DATE_FORMATTER));
            item.setDayNumber(date.getDayOfMonth());
            item.setCurrentMonth(YearMonth.from(date).equals(month));
            item.setSelected(date.equals(selectedDate));
            item.setColors(daySchedules.stream()
                    .map(ScheduleEntity::getColorSnapshot)
                    .filter(color -> color != null && !color.isBlank())
                    .distinct()
                    .toList());
            item.setCount(daySchedules.size());
            days.add(item);
        }
        overview.setDays(days);
        return overview;
    }

    /**
     * 构建选中日期概览。
     *
     * @param selectedDate 选中日期
     * @param schedules 当月档期
     * @return 选中日期概览
     */
    private MineScheduleResponse.SelectedDateOverview buildSelectedDateOverview(
            LocalDate selectedDate,
            List<ScheduleEntity> schedules
    ) {
        List<MineScheduleResponse.ScheduleItem> items = schedules.stream()
                .filter(schedule -> selectedDate.equals(schedule.getScheduleDate()))
                .sorted(Comparator.comparing(ScheduleEntity::getStartTimeSnapshot)
                        .thenComparing(ScheduleEntity::getId))
                .map(this::buildScheduleItem)
                .toList();
        MineScheduleResponse.SelectedDateOverview overview = new MineScheduleResponse.SelectedDateOverview();
        overview.setDate(selectedDate.format(DATE_FORMATTER));
        overview.setSchedules(items);
        overview.setSummaryText(items.size() + " 条档期");
        return overview;
    }

    /**
     * 构建档位定义响应。
     *
     * @param entity 档位定义实体
     * @return 档位定义响应
     */
    private MineScheduleResponse.SlotDefinitionItem buildSlotDefinitionItem(SlotDefinitionEntity entity) {
        SlotDefinitionStatusDict status = SlotDefinitionStatusDict.fromCode(entity.getStatus());
        MineScheduleResponse.SlotDefinitionItem item = new MineScheduleResponse.SlotDefinitionItem();
        item.setId(entity.getId());
        item.setName(entity.getName());
        item.setStartTime(formatTime(entity.getStartTime()));
        item.setEndTime(formatTime(entity.getEndTime()));
        item.setColor(entity.getColor());
        item.setIsSystemDefault(entity.getIsSystemDefault());
        item.setStatus(entity.getStatus());
        item.setStatusText(status == null ? "未知" : status.getDisplayName());
        item.setEnabled(SlotDefinitionStatusDict.ACTIVE.getCode().equals(entity.getStatus()));
        return item;
    }

    /**
     * 构建档期明细响应。
     *
     * @param entity 档期实体
     * @return 档期明细响应
     */
    private MineScheduleResponse.ScheduleItem buildScheduleItem(ScheduleEntity entity) {
        ScheduleStatusDict status = ScheduleStatusDict.fromCode(entity.getStatus());
        MineScheduleResponse.ScheduleItem item = new MineScheduleResponse.ScheduleItem();
        item.setId(entity.getId());
        item.setScheduleDate(entity.getScheduleDate() == null ? "" : entity.getScheduleDate().format(DATE_FORMATTER));
        item.setSlotDefinitionId(entity.getSlotDefinitionId());
        item.setSlotName(entity.getSlotNameSnapshot());
        item.setStartTime(formatTime(entity.getStartTimeSnapshot()));
        item.setEndTime(formatTime(entity.getEndTimeSnapshot()));
        item.setColor(entity.getColorSnapshot());
        item.setStatus(entity.getStatus());
        item.setStatusText(status == null ? "未知" : status.getDisplayName());
        item.setStatusTone(resolveScheduleStatusTone(entity.getStatus()));
        item.setContactName(defaultString(entity.getContactNameCiphertext()));
        item.setContactPhone(defaultString(entity.getContactPhoneCiphertext()));
        item.setNote(defaultString(entity.getNote()));
        item.setLockedSnapshot(entity.getLockedSnapshot());
        item.setDescText(buildScheduleDesc(item));
        return item;
    }

    /**
     * 构建档期描述。
     *
     * @param item 档期明细
     * @return 描述文案
     */
    private String buildScheduleDesc(MineScheduleResponse.ScheduleItem item) {
        String contact = item.getContactName();
        if (contact == null || contact.isBlank()) {
            contact = item.getContactPhone();
        }
        if (contact == null || contact.isBlank()) {
            contact = "未填写联系人";
        }
        return item.getStartTime() + "-" + item.getEndTime() + " · " + contact;
    }

    /**
     * 解析月份。
     *
     * @param value 月份文本
     * @return 月份
     */
    private YearMonth parseYearMonth(String value) {
        try {
            if (value == null || value.isBlank()) {
                return YearMonth.now();
            }
            return YearMonth.parse(value, YEAR_MONTH_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BusinessException("月份格式无效");
        }
    }

    /**
     * 解析选中日期。
     *
     * @param value 日期文本
     * @param month 当前月份
     * @return 日期
     */
    private LocalDate parseSelectedDate(String value, YearMonth month) {
        if (value == null || value.isBlank()) {
            return month.atDay(1);
        }
        return parseDate(value, "档期日期");
    }

    /**
     * 解析日期。
     *
     * @param value 日期文本
     * @param fieldName 字段名
     * @return 日期
     */
    private LocalDate parseDate(String value, String fieldName) {
        try {
            if (value == null || value.isBlank()) {
                throw new DateTimeParseException("blank", "", 0);
            }
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BusinessException(fieldName + "格式无效");
        }
    }

    /**
     * 解析时间。
     *
     * @param value 时间文本
     * @param fieldName 字段名
     * @return 时间
     */
    private LocalTime parseTime(String value, String fieldName) {
        try {
            if (value == null || value.isBlank()) {
                throw new DateTimeParseException("blank", "", 0);
            }
            return LocalTime.parse(value, TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BusinessException(fieldName + "格式无效");
        }
    }

    /**
     * 解析档位定义状态。
     *
     * @param value 状态编码
     * @return 状态
     */
    private SlotDefinitionStatusDict parseSlotStatus(String value) {
        SlotDefinitionStatusDict status = SlotDefinitionStatusDict.fromCode(normalizeOptionalString(value));
        if (status == null) {
            throw new BusinessException("档位定义状态无效");
        }
        return status;
    }

    /**
     * 解析档期状态。
     *
     * @param value 状态编码
     * @return 状态
     */
    private ScheduleStatusDict parseScheduleStatus(String value) {
        ScheduleStatusDict status = ScheduleStatusDict.fromCode(normalizeOptionalString(value));
        if (status == null) {
            throw new BusinessException("档期状态无效");
        }
        return status;
    }

    /**
     * 标准化颜色。
     *
     * @param value 颜色
     * @return 标准颜色
     */
    private String normalizeColor(String value) {
        String normalized = normalizeRequiredString(value, 7, "档位颜色");
        if (!COLOR_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("请选择有效的档位颜色");
        }
        return normalized.toLowerCase();
    }

    /**
     * 标准化必填字符串。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名
     * @return 标准字符串
     */
    private String normalizeRequiredString(String value, int maxLength, String fieldName) {
        String normalized = normalizeOptionalString(value);
        if (normalized.isBlank()) {
            throw new BusinessException(fieldName + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过 " + maxLength + " 个字");
        }
        return normalized;
    }

    /**
     * 标准化可选字符串。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名
     * @return 标准字符串
     */
    private String normalizeOptionalString(String value, int maxLength, String fieldName) {
        String normalized = normalizeOptionalString(value);
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过 " + maxLength + " 个字");
        }
        return normalized;
    }

    /**
     * 标准化可选字符串。
     *
     * @param value 原始值
     * @return 标准字符串
     */
    private String normalizeOptionalString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 安全列表。
     *
     * @param source 原列表
     * @param <T> 元素类型
     * @return 非空列表
     */
    private <T> List<T> safeList(List<T> source) {
        return source == null ? List.of() : source;
    }

    /**
     * 格式化时间。
     *
     * @param time 时间
     * @return 时间文本
     */
    private String formatTime(LocalTime time) {
        return time == null ? "" : time.format(TIME_FORMATTER);
    }

    /**
     * 空字符串兜底。
     *
     * @param value 原始值
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 解析档期状态色调。
     *
     * @param status 状态编码
     * @return 色调
     */
    private String resolveScheduleStatusTone(String status) {
        ScheduleStatusDict statusDict = ScheduleStatusDict.fromCode(status);
        return statusDict == null ? ScheduleStatusDict.REST.getTone() : statusDict.getTone();
    }
}
