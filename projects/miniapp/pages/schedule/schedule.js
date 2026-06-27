const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  DEFAULT_SLOT_COLOR,
  SLOT_COLOR_OPTIONS,
  buildSlotDefinitionFieldCounters,
  buildSlotDefinitionPayload,
  normalizeScheduleOverview,
  validateSlotDefinitionForm
} = require('../../utils/schedule')

const MODE_DEFINITIONS = 'definitions'
const MODE_MAINTENANCE = 'maintenance'
const MINE_PAGE_URL = '/pages/index/index'

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
  const match = String(value || '').match(/^(\d{4})-(\d{2})$/)
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

Page({
  data: Object.assign({
    loading: true,
    saving: false,
    errorMessage: '',
    activeMode: MODE_MAINTENANCE,
    overview: normalizeScheduleOverview({}),
    weekdays: ['日', '一', '二', '三', '四', '五', '六'],
    slotColorOptions: SLOT_COLOR_OPTIONS,
    slotStatusOptions: [
      { value: 'ACTIVE', text: '启用', tone: 'teal' },
      { value: 'DISABLED', text: '停用', tone: 'muted' }
    ],
    slotFormVisible: false,
    slotSheetTitle: '新增档位定义',
    editingSlotId: null,
    slotForm: buildDefaultSlotForm(),
    slotFieldCounters: buildSlotDefinitionFieldCounters(buildDefaultSlotForm()),
    tabs: [
      { key: 'schedule', label: '档期', icon: 'schedule', active: true },
      { key: 'work', label: '作品', icon: 'work' },
      { key: 'portfolio', label: '作品集', icon: 'portfolio' },
      { key: 'mine', label: '我的', icon: 'mine' }
    ]
  }, todayState()),

  onLoad(options = {}) {
    const initialDate = options.date || this.data.selectedDate
    const initialMonth = initialDate.slice(0, 7) || this.data.selectedMonth
    this.setData({
      selectedDate: initialDate,
      selectedMonth: initialMonth
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
      selectedDate: `${selectedMonth}-01`
    })
    this.loadSchedule()
  },

  handleNextMonth() {
    const selectedMonth = shiftMonth(this.data.selectedMonth, 1)
    this.setData({
      selectedMonth,
      selectedDate: `${selectedMonth}-01`
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
      selectedMonth: date.slice(0, 7)
    })
    this.loadSchedule(false)
  },

  handleMaintainDate() {
    wx.navigateTo({
      url: `/pages/schedule-entry/schedule-entry?date=${this.data.selectedDate}`
    })
  },

  handleScheduleTap(event) {
    const scheduleId = event.currentTarget.dataset.id
    const date = event.currentTarget.dataset.date || this.data.selectedDate
    wx.navigateTo({
      url: `/pages/schedule-entry/schedule-entry?date=${date}&scheduleId=${scheduleId}`
    })
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
    const slotId = Number(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot) {
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

  handleSlotCancel() {
    this.setData({
      slotFormVisible: false,
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

  async handleSaveSlot() {
    const validation = validateSlotDefinitionForm(this.data.slotForm)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }
    this.setData({ saving: true })
    try {
      const payload = buildSlotDefinitionPayload(this.data.slotForm)
      if (this.data.editingSlotId) {
        await request({
          url: `/api/mine/schedule/slot-definitions/${this.data.editingSlotId}`,
          method: 'PUT',
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
        saving: false
      })
      this.loadSchedule(false)
      wx.showToast({
        title: '已保存定义',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
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
    const slotId = Number(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot) {
      return
    }
    try {
      await request({
        url: `/api/mine/schedule/slot-definitions/${slotId}/status`,
        method: 'PUT',
        data: {
          status: slot.enabled ? 'DISABLED' : 'ACTIVE'
        }
      })
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
