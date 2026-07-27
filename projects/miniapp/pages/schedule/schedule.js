const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  DEFAULT_SCHEDULE_STATUS,
  DEFAULT_SLOT_COLOR,
  SCHEDULE_STATUS_OPTIONS,
  SLOT_COLOR_OPTIONS,
  buildScheduleFieldCounters,
  buildScheduleItemPayload,
  buildSlotDefinitionFieldCounters,
  buildSlotDefinitionPayload,
  markMonthSelectedDate,
  normalizeMonthOverview,
  normalizeScheduleOverview,
  normalizeSelectedDateOverview,
  normalizeSlotDefinitions,
  validateScheduleItemPayload,
  validateSlotDefinitionPayload
} = require('./utils/schedule')

const MODE_DEFINITIONS = 'definitions'
const MODE_MAINTENANCE = 'maintenance'
const MINE_PAGE_URL = '/pages/index/index'
const WORKS_PAGE_URL = '/pages/works/works'
const PORTFOLIOS_PAGE_URL = '/pages/portfolios/portfolios'
const MONTH_PATTERN = /^(\d{4})-(\d{2})$/
const MONTH_FIRST_DAY = '01'

function pad2(value) {
  return String(value).padStart(2, '0')
}

function formatDate(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

function formatMonth(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}`
}

function parseMonth(value) {
  const match = String(value || '').match(MONTH_PATTERN)
  if (!match) {
    return new Date()
  }
  return new Date(Number(match[1]), Number(match[2]) - 1, 1)
}

function shiftMonth(value, offset) {
  const date = parseMonth(value)
  date.setMonth(date.getMonth() + offset)
  return formatMonth(date)
}

function isValidMonth(value) {
  return MONTH_PATTERN.test(String(value || ''))
}

function formatMonthFirstDate(yearMonth) {
  return `${yearMonth}-${MONTH_FIRST_DAY}`
}

function todayState() {
  const now = new Date()
  return {
    selectedMonth: formatMonth(now),
    selectedDate: formatDate(now)
  }
}

function buildDefaultSlotForm() {
  return {
    name: '',
    startTime: '07:30',
    endTime: '09:30',
    color: DEFAULT_SLOT_COLOR,
    status: 'ACTIVE'
  }
}

function buildDefaultScheduleForm(date) {
  return {
    scheduleDate: date,
    slotDefinitionId: null,
    status: DEFAULT_SCHEDULE_STATUS,
    contactName: '',
    contactPhone: '',
    note: ''
  }
}

function buildScheduleFormFromItem(item, fallbackDate) {
  return {
    scheduleId: item.id,
    scheduleDate: item.scheduleDate || fallbackDate,
    slotDefinitionId: item.slotDefinitionId,
    status: item.status,
    contactName: item.contactName,
    contactPhone: item.contactPhone,
    note: item.note
  }
}

function selectFirstEnabledSlotId(slotDefinitions) {
  const slot = (slotDefinitions || []).find((item) => item.enabled)
  return slot ? slot.id : null
}

const INITIAL_TODAY_STATE = todayState()
const INITIAL_SLOT_FORM = buildDefaultSlotForm()
const INITIAL_SCHEDULE_FORM = buildDefaultScheduleForm(INITIAL_TODAY_STATE.selectedDate)

Page({
  slotDefinitionsRequestSeq: 0,
  calendarRequestSeq: 0,
  dayRequestSeq: 0,

  data: Object.assign({
    slotDefinitionsLoading: true,
    calendarLoading: true,
    dayLoading: true,
    saving: false,
    slotDefinitionsErrorMessage: '',
    calendarErrorMessage: '',
    dayErrorMessage: '',
    activeMode: MODE_MAINTENANCE,
    overview: normalizeScheduleOverview({
      month: { yearMonth: INITIAL_TODAY_STATE.selectedMonth },
      selectedDate: { date: INITIAL_TODAY_STATE.selectedDate }
    }),
    weekdays: ['日', '一', '二', '三', '四', '五', '六'],
    slotColorOptions: SLOT_COLOR_OPTIONS,
    statusOptions: SCHEDULE_STATUS_OPTIONS,
    slotStatusOptions: [
      { value: 'ACTIVE', text: '启用', tone: 'teal' },
      { value: 'DISABLED', text: '停用', tone: 'muted' }
    ],
    slotFormVisible: false,
    slotSheetTitle: '新增档位定义',
    editingSlotId: null,
    deletingSlotId: null,
    togglingSlotId: null,
    revealedSlotId: null,
    slotTouchStart: null,
    slotForm: INITIAL_SLOT_FORM,
    slotFieldCounters: buildSlotDefinitionFieldCounters(INITIAL_SLOT_FORM),
    scheduleFormVisible: false,
    scheduleSheetTitle: '新增档期',
    editingScheduleId: null,
    deletingScheduleId: null,
    revealedScheduleId: null,
    scheduleTouchStart: null,
    scheduleForm: INITIAL_SCHEDULE_FORM,
    scheduleFieldCounters: buildScheduleFieldCounters(INITIAL_SCHEDULE_FORM),
    tabs: [
      { key: 'schedule', label: '档期', icon: 'schedule', active: true },
      { key: 'work', label: '作品', icon: 'work' },
      { key: 'portfolio', label: '作品集', icon: 'portfolio' },
      { key: 'mine', label: '我的', icon: 'mine' }
    ]
  }, INITIAL_TODAY_STATE),

  onLoad(options = {}) {
    const initialDate = options.date || this.data.selectedDate
    const initialMonth = initialDate.slice(0, 7) || this.data.selectedMonth
    const scheduleForm = buildDefaultScheduleForm(initialDate)
    this.setData({
      selectedDate: initialDate,
      selectedMonth: initialMonth,
      overview: normalizeScheduleOverview({
        month: { yearMonth: initialMonth },
        selectedDate: { date: initialDate }
      }),
      scheduleForm,
      scheduleFieldCounters: buildScheduleFieldCounters(scheduleForm)
    })
  },

  onShow() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadInitialSchedule()
  },

  loadInitialSchedule() {
    this.loadSlotDefinitions()
    this.loadCalendar()
    this.loadDaySchedules()
  },

  async loadSlotDefinitions(showLoading = true) {
    const requestSeq = this.slotDefinitionsRequestSeq + 1
    this.slotDefinitionsRequestSeq = requestSeq
    if (showLoading) {
      this.setData({
        slotDefinitionsLoading: true,
        slotDefinitionsErrorMessage: ''
      })
    }
    try {
      const response = await request({
        url: '/api/mine/schedule/slot-definitions'
      })
      if (requestSeq !== this.slotDefinitionsRequestSeq) {
        return
      }
      this.setData({
        'overview.slotDefinitions': normalizeSlotDefinitions(response),
        slotDefinitionsLoading: false,
        slotDefinitionsErrorMessage: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ slotDefinitionsLoading: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        slotDefinitionsLoading: false,
        slotDefinitionsErrorMessage: error && error.message ? error.message : '档位定义加载失败'
      })
    }
  },

  async loadCalendar(showLoading = true) {
    const requestSeq = this.calendarRequestSeq + 1
    const selectedMonth = this.data.selectedMonth
    this.calendarRequestSeq = requestSeq
    if (showLoading) {
      this.setData({
        calendarLoading: true,
        calendarErrorMessage: ''
      })
    }
    try {
      const response = await request({
        url: '/api/mine/schedule/month',
        data: {
          month: selectedMonth
        }
      })
      if (requestSeq !== this.calendarRequestSeq || selectedMonth !== this.data.selectedMonth) {
        return
      }
      this.setData({
        'overview.month': normalizeMonthOverview(response, this.data.selectedDate),
        calendarLoading: false,
        calendarErrorMessage: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ calendarLoading: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        calendarLoading: false,
        calendarErrorMessage: error && error.message ? error.message : '月历加载失败'
      })
    }
  },

  async loadDaySchedules(showLoading = true) {
    const requestSeq = this.dayRequestSeq + 1
    const selectedDate = this.data.selectedDate
    this.dayRequestSeq = requestSeq
    if (showLoading) {
      this.setData({
        dayLoading: true,
        dayErrorMessage: ''
      })
    } else {
      this.setData({
        dayErrorMessage: ''
      })
    }
    try {
      const response = await request({
        url: '/api/mine/schedule/day',
        data: {
          date: selectedDate
        }
      })
      if (requestSeq !== this.dayRequestSeq || selectedDate !== this.data.selectedDate) {
        return
      }
      this.setData({
        'overview.selectedDate': normalizeSelectedDateOverview(response),
        dayLoading: false,
        dayErrorMessage: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ dayLoading: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        dayLoading: false,
        dayErrorMessage: error && error.message ? error.message : '当天档期加载失败'
      })
    }
  },

  refreshCalendarAndDay() {
    this.loadCalendar()
    this.loadDaySchedules()
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  noop() {},

  handleModeTap(event) {
    this.setData({
      activeMode: event.currentTarget.dataset.mode || MODE_MAINTENANCE
    })
  },

  handlePrevMonth() {
    const selectedMonth = shiftMonth(this.data.selectedMonth, -1)
    const selectedDate = formatMonthFirstDate(selectedMonth)
    this.setData({
      selectedMonth,
      selectedDate,
      revealedScheduleId: null,
      calendarLoading: true,
      dayLoading: true,
      calendarErrorMessage: '',
      dayErrorMessage: '',
      'overview.selectedDate': normalizeSelectedDateOverview({ date: selectedDate })
    })
    this.refreshCalendarAndDay()
  },

  handleNextMonth() {
    const selectedMonth = shiftMonth(this.data.selectedMonth, 1)
    const selectedDate = formatMonthFirstDate(selectedMonth)
    this.setData({
      selectedMonth,
      selectedDate,
      revealedScheduleId: null,
      calendarLoading: true,
      dayLoading: true,
      calendarErrorMessage: '',
      dayErrorMessage: '',
      'overview.selectedDate': normalizeSelectedDateOverview({ date: selectedDate })
    })
    this.refreshCalendarAndDay()
  },

  handleMonthPickerChange(event) {
    const selectedMonth = event.detail && event.detail.value
    if (!isValidMonth(selectedMonth)) {
      return
    }
    const selectedDate = formatMonthFirstDate(selectedMonth)
    this.setData({
      selectedMonth,
      selectedDate,
      revealedScheduleId: null,
      calendarLoading: true,
      dayLoading: true,
      calendarErrorMessage: '',
      dayErrorMessage: '',
      'overview.selectedDate': normalizeSelectedDateOverview({ date: selectedDate })
    })
    this.refreshCalendarAndDay()
  },

  handleDayTap(event) {
    const date = event.currentTarget.dataset.date
    if (!date) {
      return
    }
    const selectedMonth = date.slice(0, 7)
    const sameMonth = selectedMonth === this.data.selectedMonth
    const updates = {
      selectedDate: date,
      selectedMonth,
      revealedScheduleId: null,
      dayErrorMessage: ''
    }
    if (sameMonth) {
      updates['overview.month'] = markMonthSelectedDate(this.data.overview.month, date)
    } else {
      updates.calendarLoading = true
      updates.dayLoading = true
      updates.calendarErrorMessage = ''
      updates['overview.selectedDate'] = normalizeSelectedDateOverview({ date })
    }
    this.setData(updates)
    if (sameMonth) {
      this.loadDaySchedules(false)
      return
    }
    this.refreshCalendarAndDay()
  },

  handleMaintainDate() {
    const scheduleForm = Object.assign(buildDefaultScheduleForm(this.data.selectedDate), {
      slotDefinitionId: selectFirstEnabledSlotId(this.data.overview.slotDefinitions)
    })
    this.setData({
      scheduleFormVisible: true,
      scheduleSheetTitle: '新增档期',
      editingScheduleId: null,
      revealedScheduleId: null,
      scheduleForm,
      scheduleFieldCounters: buildScheduleFieldCounters(scheduleForm)
    })
  },

  handleScheduleTap(event) {
    const scheduleId = normalizeId(event.currentTarget.dataset.id)
    const schedule = this.data.overview.selectedDate.schedules.find((item) => item.id === scheduleId)
    if (!schedule) {
      return
    }
    const scheduleForm = buildScheduleFormFromItem(schedule, this.data.selectedDate)
    this.setData({
      scheduleFormVisible: true,
      scheduleSheetTitle: '编辑档期',
      editingScheduleId: schedule.id,
      revealedScheduleId: null,
      scheduleForm,
      scheduleFieldCounters: buildScheduleFieldCounters(scheduleForm)
    })
  },

  handleScheduleTouchStart(event) {
    const scheduleId = normalizeId(event.currentTarget.dataset.id)
    const schedule = this.data.overview.selectedDate.schedules.find((item) => item.id === scheduleId)
    if (!schedule) {
      this.setData({ scheduleTouchStart: null })
      return
    }
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      scheduleTouchStart: {
        scheduleId,
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handleScheduleTouchMove() {},

  handleScheduleTouchEnd(event) {
    this.handleTouchEnd(event, 'scheduleTouchStart', 'revealedScheduleId', 'scheduleId')
  },

  handleScheduleTouchCancel() {
    this.setData({ scheduleTouchStart: null })
  },

  handleSlotAdd() {
    const slotForm = buildDefaultSlotForm()
    this.setData({
      slotFormVisible: true,
      slotSheetTitle: '新增档位定义',
      editingSlotId: null,
      slotForm,
      slotFieldCounters: buildSlotDefinitionFieldCounters(slotForm)
    })
  },

  handleSlotEdit(event) {
    const slotId = normalizeId(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot) {
      return
    }
    if (slot.enabled) {
      wx.showToast({
        title: '停用后才可编辑',
        icon: 'none'
      })
      return
    }
    const slotForm = {
      name: slot.name,
      startTime: slot.startTime,
      endTime: slot.endTime,
      color: slot.color,
      status: slot.status
    }
    this.setData({
      slotFormVisible: true,
      slotSheetTitle: '编辑档位定义',
      editingSlotId: slot.id,
      slotForm,
      slotFieldCounters: buildSlotDefinitionFieldCounters(slotForm)
    })
  },

  handleSlotTouchStart(event) {
    const slotId = normalizeId(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot || slot.enabled) {
      this.setData({ slotTouchStart: null })
      return
    }
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      slotTouchStart: {
        slotId,
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handleSlotTouchMove() {},

  handleSlotTouchEnd(event) {
    this.handleTouchEnd(event, 'slotTouchStart', 'revealedSlotId', 'slotId')
  },

  handleTouchEnd(event, touchStateKey, revealedKey, idKey) {
    const start = this.data[touchStateKey]
    if (!start) {
      return
    }
    const touch = (event.changedTouches && event.changedTouches[0]) || {}
    const deltaX = (touch.clientX || start.x) - start.x
    const deltaY = Math.abs((touch.clientY || start.y) - start.y)
    const itemId = start[idKey]
    if (deltaY <= 48 && deltaX < -32) {
      this.setData({
        [revealedKey]: itemId,
        [touchStateKey]: null
      })
      return
    }
    if (deltaX > 24 || Math.abs(deltaX) < 8) {
      this.setData({
        [revealedKey]: null,
        [touchStateKey]: null
      })
      return
    }
    this.setData({ [touchStateKey]: null })
  },

  handleSlotTouchCancel() {
    this.setData({ slotTouchStart: null })
  },

  handleSlotCancel() {
    this.setData({
      slotFormVisible: false,
      editingSlotId: null,
      saving: false
    })
  },

  handleScheduleCancel() {
    this.setData({
      scheduleFormVisible: false,
      editingScheduleId: null,
      saving: false
    })
  },

  handleSlotInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    const value = event.detail.value || ''
    const slotForm = Object.assign({}, this.data.slotForm, {
      [field]: value
    })
    this.setData({
      [`slotForm.${field}`]: value,
      slotFieldCounters: buildSlotDefinitionFieldCounters(slotForm)
    })
  },

  handleScheduleInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    const value = event.detail.value || ''
    const scheduleForm = Object.assign({}, this.data.scheduleForm, {
      [field]: value
    })
    this.setData({
      [`scheduleForm.${field}`]: value,
      scheduleFieldCounters: buildScheduleFieldCounters(scheduleForm)
    })
  },

  handleSlotTimeChange(event) {
    const field = event.currentTarget.dataset.field
    this.setData({
      [`slotForm.${field}`]: event.detail.value
    })
  },

  handleSlotColorTap(event) {
    this.setData({
      'slotForm.color': event.currentTarget.dataset.color
    })
  },

  handleSlotStatusTap(event) {
    this.setData({
      'slotForm.status': event.currentTarget.dataset.status
    })
  },

  handleScheduleSlotTap(event) {
    const slotId = normalizeId(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot || !slot.enabled) {
      wx.showToast({
        title: '请选择启用中的档位',
        icon: 'none'
      })
      return
    }
    this.setData({
      'scheduleForm.slotDefinitionId': slotId
    })
  },

  handleScheduleStatusTap(event) {
    this.setData({
      'scheduleForm.status': event.currentTarget.dataset.status
    })
  },

  async handleSaveSchedule() {
    const payload = buildScheduleItemPayload(this.data.scheduleForm)
    const validation = validateScheduleItemPayload(payload)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }
    this.setData({ saving: true })
    try {
      await request({
        url: '/api/mine/schedule/items/save',
        method: 'POST',
        data: payload
      })
      this.setData({
        scheduleFormVisible: false,
        editingScheduleId: null,
        revealedScheduleId: null,
        saving: false
      })
      this.refreshCalendarAndDay()
      wx.showToast({
        title: '已保存档期',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ saving: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ saving: false })
      wx.showToast({
        title: error && error.message ? error.message : '保存失败',
        icon: 'none'
      })
    }
  },

  async handleSaveSlot() {
    const payload = buildSlotDefinitionPayload(this.data.slotForm)
    const validation = validateSlotDefinitionPayload(payload)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }
    const editingSlotId = this.data.editingSlotId
    this.setData({ saving: true })
    try {
      if (editingSlotId) {
        await request({
          url: `/api/mine/schedule/slot-definitions/save/${editingSlotId}`,
          method: 'POST',
          data: payload
        })
      } else {
        await request({
          url: '/api/mine/schedule/slot-definitions',
          method: 'POST',
          data: payload
        })
      }
      this.setData({
        slotFormVisible: false,
        editingSlotId: null,
        saving: false
      })
      this.loadSlotDefinitions()
      if (editingSlotId) {
        this.refreshCalendarAndDay()
      }
      wx.showToast({
        title: '已保存定义',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ saving: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ saving: false })
      wx.showToast({
        title: error && error.message ? error.message : '保存失败',
        icon: 'none'
      })
    }
  },

  async handleToggleSlotStatus(event) {
    if (this.data.togglingSlotId !== null) {
      return
    }
    const slotId = normalizeId(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot) {
      return
    }
    this.setData({ togglingSlotId: slotId })
    try {
      await request({
        url: `/api/mine/schedule/slot-definitions/status/${slotId}`,
        method: 'POST',
        data: {
          status: slot.enabled ? 'DISABLED' : 'ACTIVE'
        }
      })
      this.setData({ revealedSlotId: null })
      await this.loadSlotDefinitions(false)
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      wx.showToast({
        title: error && error.message ? error.message : '状态更新失败',
        icon: 'none'
      })
    } finally {
      this.setData({ togglingSlotId: null })
    }
  },

  handleDeleteScheduleItem(event) {
    const scheduleId = normalizeId(event.currentTarget.dataset.id)
    const schedule = this.data.overview.selectedDate.schedules.find((item) => item.id === scheduleId)
    if (!schedule) {
      return
    }
    wx.showModal({
      title: '删除档期',
      content: `确认删除 ${schedule.scheduleDate || this.data.selectedDate} 的“${schedule.slotName}”？`,
      confirmText: '删除',
      confirmColor: '#a9354f',
      success: async (result) => {
        if (!result.confirm) {
          this.setData({ revealedScheduleId: null })
          return
        }
        this.setData({ deletingScheduleId: scheduleId })
        try {
          await request({
            url: `/api/mine/schedule/items/delete/${scheduleId}`,
            method: 'POST'
          })
          this.setData({
            deletingScheduleId: null,
            revealedScheduleId: null
          })
          wx.showToast({
            title: '已删除档期',
            icon: 'success'
          })
          this.refreshCalendarAndDay()
        } catch (error) {
          if (error && error.authRequired) {
            this.setData({
              deletingScheduleId: null,
              revealedScheduleId: null
            })
            handleMaintainerAuthRequired(error.message)
            return
          }
          this.setData({
            deletingScheduleId: null,
            revealedScheduleId: null
          })
          wx.showToast({
            title: error && error.message ? error.message : '删除失败',
            icon: 'none'
          })
        }
      }
    })
  },

  handleDeleteSlotDefinition(event) {
    const slotId = normalizeId(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot) {
      return
    }
    if (slot.enabled) {
      wx.showToast({
        title: '请先停用档位',
        icon: 'none'
      })
      return
    }
    wx.showModal({
      title: '删除档位',
      content: `确认删除“${slot.name}”？删除后不会再出现在档位定义列表。`,
      confirmText: '删除',
      confirmColor: '#a9354f',
      success: async (result) => {
        if (!result.confirm) {
          this.setData({ revealedSlotId: null })
          return
        }
        this.setData({ deletingSlotId: slotId })
        try {
          await request({
            url: `/api/mine/schedule/slot-definitions/delete/${slotId}`,
            method: 'POST'
          })
          this.setData({
            deletingSlotId: null,
            revealedSlotId: null
          })
          wx.showToast({
            title: '已删除档位',
            icon: 'success'
          })
          this.loadSlotDefinitions()
        } catch (error) {
          if (error && error.authRequired) {
            this.setData({
              deletingSlotId: null,
              revealedSlotId: null
            })
            handleMaintainerAuthRequired(error.message)
            return
          }
          this.setData({
            deletingSlotId: null,
            revealedSlotId: null
          })
          wx.showModal({
            title: '无法删除',
            content: error && error.message ? error.message : '删除失败，请稍后再试',
            showCancel: false,
            confirmText: '知道了'
          })
        }
      }
    })
  },

  handleTabTap(event) {
    const label = event.currentTarget.dataset.label
    if (label === '档期') {
      return
    }
    if (label === '我的') {
      wx.redirectTo({
        url: MINE_PAGE_URL
      })
      return
    }
    if (label === '作品') {
      wx.redirectTo({
        url: WORKS_PAGE_URL
      })
      return
    }
    if (label === '作品集') {
      wx.redirectTo({
        url: PORTFOLIOS_PAGE_URL
      })
      return
    }
    wx.showToast({
      title: `${label}页面接入中`,
      icon: 'none'
    })
  }
})
