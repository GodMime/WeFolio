const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  SCHEDULE_STATUS_OPTIONS,
  buildScheduleFieldCounters,
  buildScheduleItemPayload,
  normalizeScheduleOverview,
  validateScheduleItemForm
} = require('../../utils/schedule')

function pad2(value) {
  return String(value).padStart(2, '0')
}

function formatDate(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

function todayText() {
  return formatDate(new Date())
}

function normalizeId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

function buildDefaultForm(date, scheduleId) {
  const form = {
    scheduleDate: date,
    slotDefinitionId: null,
    status: 'AVAILABLE',
    contactName: '',
    contactPhone: '',
    note: ''
  }
  if (scheduleId) {
    form.scheduleId = scheduleId
  }
  return form
}

Page({
  data: {
    loading: true,
    saving: false,
    deleting: false,
    errorMessage: '',
    scheduleDate: todayText(),
    scheduleId: null,
    overview: normalizeScheduleOverview({}),
    statusOptions: SCHEDULE_STATUS_OPTIONS,
    form: buildDefaultForm(todayText(), null),
    counters: buildScheduleFieldCounters({})
  },

  onLoad(options = {}) {
    const scheduleDate = options.date || todayText()
    const scheduleId = normalizeId(options.scheduleId)
    this.setData({
      scheduleDate,
      scheduleId,
      form: buildDefaultForm(scheduleDate, scheduleId),
      counters: buildScheduleFieldCounters({})
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

  async loadSchedule() {
    this.setData({
      loading: true,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: '/api/mine/schedule',
        data: {
          month: this.data.scheduleDate.slice(0, 7),
          date: this.data.scheduleDate
        }
      })
      const overview = normalizeScheduleOverview(response)
      const form = this.resolveForm(overview)
      this.setData({
        overview,
        form,
        counters: buildScheduleFieldCounters(form),
        loading: false
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

  resolveForm(overview) {
    const current = this.data.form || buildDefaultForm(this.data.scheduleDate, this.data.scheduleId)
    const schedules = overview.selectedDate.schedules || []
    const target = this.data.scheduleId
      ? schedules.find((item) => item.id === this.data.scheduleId)
      : null
    if (target) {
      return {
        scheduleId: target.id,
        scheduleDate: target.scheduleDate || this.data.scheduleDate,
        slotDefinitionId: target.slotDefinitionId,
        status: target.status,
        contactName: target.contactName,
        contactPhone: target.contactPhone,
        note: target.note
      }
    }
    const enabledSlot = overview.slotDefinitions.find((item) => item.enabled)
    return Object.assign({}, current, {
      scheduleDate: this.data.scheduleDate,
      slotDefinitionId: current.slotDefinitionId || (enabledSlot ? enabledSlot.id : null)
    })
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleRetry() {
    this.bootstrap()
  },

  handleSlotTap(event) {
    const slotId = Number(event.currentTarget.dataset.id)
    const slot = this.data.overview.slotDefinitions.find((item) => item.id === slotId)
    if (!slot || !slot.enabled) {
      wx.showToast({
        title: '请选择启用中的档位',
        icon: 'none'
      })
      return
    }
    this.setData({
      'form.slotDefinitionId': slotId
    })
  },

  handleStatusTap(event) {
    this.setData({
      'form.status': event.currentTarget.dataset.status
    })
  },

  handleInput(event) {
    const field = event.currentTarget.dataset.field
    const value = event.detail.value
    const form = Object.assign({}, this.data.form, {
      [field]: value
    })
    this.setData({
      [`form.${field}`]: value,
      counters: buildScheduleFieldCounters(form)
    })
  },

  async handleSave() {
    const validation = validateScheduleItemForm(this.data.form)
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
        data: buildScheduleItemPayload(this.data.form)
      })
      this.setData({ saving: false })
      wx.showToast({
        title: '已保存档期',
        icon: 'success'
      })
      wx.navigateBack({ delta: 1 })
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

  handleDelete() {
    const scheduleId = this.data.form.scheduleId || this.data.scheduleId
    if (!scheduleId) {
      wx.showToast({
        title: '还没有可删除的档期',
        icon: 'none'
      })
      return
    }
    wx.showModal({
      title: '删除档期',
      content: '删除后访客端不会再看到这条档期记录',
      confirmText: '删除',
      confirmColor: '#a9354f',
      success: async (result) => {
        if (!result.confirm) {
          return
        }
        this.setData({ deleting: true })
        try {
          await request({
            url: `/api/mine/schedule/items/${scheduleId}/delete`,
            method: 'POST'
          })
          this.setData({ deleting: false })
          wx.showToast({
            title: '已删除档期',
            icon: 'success'
          })
          wx.navigateBack({ delta: 1 })
        } catch (error) {
          if (error && error.authRequired) {
            handleAuthRequired(error.message)
            return
          }
          this.setData({ deleting: false })
          wx.showToast({
            title: error && error.message ? error.message : '删除失败',
            icon: 'none'
          })
        }
      }
    })
  }
})
