/**
 * 交互命名参考正式组件 components/portfolio-schedule-query/portfolio-schedule-query.js。
 * 本组件仅维护体验版本地选择状态，不得引入网络请求或访客会话。
 */
const DISPLAY_MODE_INLINE_CALENDAR = 'INLINE_CALENDAR'
const DATE_REQUIRED_MESSAGE = '请选择日期'
const SLOT_REQUIRED_MESSAGE = '请选择档位'
const MONTH_PATTERN = /^(\d{4})-(\d{2})$/

/**
 * 获取安全的微信运行时对象。
 *
 * @returns {{showToast: Function}} 微信运行时兼容对象
 */
function getRuntimeWx() {
  return typeof wx !== 'undefined' ? wx : { showToast() {} }
}

/**
 * 将年月移动指定月数。
 *
 * @param {string} yearMonth 年月文本
 * @param {number} offset 月份偏移
 * @returns {string} 移动后的年月文本
 */
function shiftMonth(yearMonth, offset) {
  const match = String(yearMonth || '').match(MONTH_PATTERN)
  const base = match
    ? new Date(Number(match[1]), Number(match[2]) - 1 + offset, 1)
    : new Date()
  const month = String(base.getMonth() + 1).padStart(2, '0')
  return `${base.getFullYear()}-${month}`
}

Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
    scheduleQuery: {
      type: Object,
      value: {}
    },
    calendarMonth: {
      type: Object,
      value: {}
    },
    slotDefinitions: {
      type: Array,
      value: []
    }
  },

  data: {
    inlineMode: DISPLAY_MODE_INLINE_CALENDAR,
    visible: false,
    selectedDate: '',
    selectedSlotDefinitionId: null
  },

  methods: {
    noop() {},

    handleOpenCalendar() {
      this.setData({ visible: true })
    },

    handleCloseCalendar() {
      this.setData({ visible: false })
    },

    handlePrevMonth() {
      this.changeMonth(shiftMonth(this.data.calendarMonth.yearMonth, -1))
    },

    handleNextMonth() {
      this.changeMonth(shiftMonth(this.data.calendarMonth.yearMonth, 1))
    },

    handleMonthPickerChange(event) {
      const yearMonth = event.detail && event.detail.value
      if (!MONTH_PATTERN.test(String(yearMonth || ''))) {
        return
      }
      this.changeMonth(yearMonth)
    },

    changeMonth(yearMonth) {
      this.setData({
        selectedDate: '',
        selectedSlotDefinitionId: null
      })
      this.triggerEvent('monthchange', { yearMonth })
    },

    handleDayTap(event) {
      const date = event.currentTarget && event.currentTarget.dataset.date
      if (!date) {
        return
      }
      this.setData({
        selectedDate: date,
        selectedSlotDefinitionId: null
      })
    },

    handleSlotTap(event) {
      const slotDefinitionId = Number(event.currentTarget && event.currentTarget.dataset.id)
      if (!slotDefinitionId) {
        return
      }
      this.setData({ selectedSlotDefinitionId: slotDefinitionId })
    },

    handleSubmitQuery() {
      if (!this.data.selectedDate) {
        getRuntimeWx().showToast({ title: DATE_REQUIRED_MESSAGE, icon: 'none' })
        return false
      }
      if (!this.data.selectedSlotDefinitionId) {
        getRuntimeWx().showToast({ title: SLOT_REQUIRED_MESSAGE, icon: 'none' })
        return false
      }
      this.triggerEvent('lockedaction')
      return true
    }
  }
})
