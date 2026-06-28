const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  DEFAULT_SCHEDULE_STATUS,
  DEFAULT_SLOT_COLOR,
  SCHEDULE_STATUS_OPTIONS,
  SLOT_COLOR_OPTIONS,
  buildScheduleFieldCounters,
  buildScheduleItemPayload,
  buildSlotDefinitionFieldCounters,
  buildSlotDefinitionPayload,
  normalizeScheduleOverview,
  validateScheduleItemPayload,
  validateSlotDefinitionPayload
} = require('../../utils/schedule')

const MODE_DEFINITIONS = 'definitions'
const MODE_MAINTENANCE = 'maintenance'
const MINE_PAGE_URL = '/pages/index/index'
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
  data: Object.assign({
    loading: true,
    saving: false,
    errorMessage: '',
    activeMode: MODE_MAINTENANCE,
    overview: normalizeScheduleOverview({}),
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
    this.loadSchedule()
  },

  async loadSchedule(showLoading = true) {
    if (showLoading) {
      this.setData({
        loading: true,
        errorMessage: ''
      })
    }
    try {
      const response = await request({
        url: '/api/mine/schedule',
        data: {
          month: this.data.selectedMonth,
          date: this.data.selectedDate
        }
      })
      this.setData({
        overview: normalizeScheduleOverview(response),
        loading: false,
        errorMessage: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '档期加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleRetry() {
    this.bootstrap()
  },

  noop() {},

  handleModeTap(event) {
    this.setData({
      activeMode: event.currentTarget.dataset.mode || MODE_MAINTENANCE
    })
  },

  handlePrevMonth() {
    const selectedMonth = shiftMonth(this.data.selectedMonth, -1)
    this.setData({
      selectedMonth,
      selectedDate: formatMonthFirstDate(selectedMonth),
      revealedScheduleId: null
    })
    this.loadSchedule()
  },

  handleNextMonth() {
    const selectedMonth = shiftMonth(this.data.selectedMonth, 1)
    this.setData({
      selectedMonth,
      selectedDate: formatMonthFirstDate(selectedMonth),
      revealedScheduleId: null
    })
    this.loadSchedule()
  },

  handleMonthPickerChange(event) {
    const selectedMonth = event.detail && event.detail.value
    if (!isValidMonth(selectedMonth)) {
      return
    }
    this.setData({
      selectedMonth,
      selectedDate: formatMonthFirstDate(selectedMonth),
      revealedScheduleId: null
    })
    this.loadSchedule()
  },

  handleDayTap(event) {
    const date = event.currentTarget.dataset.date
    if (!date) {
      return
    }
    this.setData({
      selectedDate: date,
      selectedMonth: date.slice(0, 7),
      revealedScheduleId: null
    })
    this.loadSchedule(false)
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
      this.loadSchedule(false)
      wx.showToast({
        title: '已保存档期',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ saving: false })
        handleAuthRequired(error.message)
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
    this.setData({ saving: true })
    try {
      if (this.data.editingSlotId) {
        await request({
          url: `/api/mine/schedule/slot-definitions/save/${this.data.editingSlotId}`,
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
      this.loadSchedule(false)
      wx.showToast({
        title: '已保存定义',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ saving: false })
        handleAuthRequired(error.message)
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
    const slotId = normalizeId(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot) {
      return
    }
    try {
      await request({
        url: `/api/mine/schedule/slot-definitions/status/${slotId}`,
        method: 'POST',
        data: {
          status: slot.enabled ? 'DISABLED' : 'ACTIVE'
        }
      })
      this.setData({ revealedSlotId: null })
      this.loadSchedule(false)
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      wx.showToast({
        title: error && error.message ? error.message : '状态更新失败',
        icon: 'none'
      })
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
          this.loadSchedule(false)
        } catch (error) {
          if (error && error.authRequired) {
            this.setData({
              deletingScheduleId: null,
              revealedScheduleId: null
            })
            handleAuthRequired(error.message)
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
          this.loadSchedule(false)
        } catch (error) {
          if (error && error.authRequired) {
            this.setData({
              deletingSlotId: null,
              revealedSlotId: null
            })
            handleAuthRequired(error.message)
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
    wx.showToast({
      title: `${label}页面接入中`,
      icon: 'none'
    })
  }
})
