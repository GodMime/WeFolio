const {
  MOCK_SCHEDULE_DATA,
  buildMockCalendarMonth,
  getMockTabs,
  shiftMockYearMonth,
  showMockLoginRequiredToast
} = require('../../../utils/mock-experience')

function todayText() {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

Page({
  data: {
    activeMode: 'maintenance',
    tabs: getMockTabs('schedule'),
    slotDefinitions: MOCK_SCHEDULE_DATA.slotDefinitions,
    weekdays: ['日', '一', '二', '三', '四', '五', '六'],
    selectedDate: todayText(),
    selectedDateSummary: MOCK_SCHEDULE_DATA.selectedDate.summaryText,
    month: buildMockCalendarMonth('', todayText())
  },

  handleModeTap(event) {
    const mode = event.currentTarget.dataset.mode
    if (!mode || mode === this.data.activeMode) {
      return
    }
    this.setData({
      activeMode: mode
    })
  },

  handleMonthPickerChange(event) {
    const yearMonth = event.detail && event.detail.value
    if (!yearMonth) {
      return
    }
    const selectedDate = `${yearMonth}-01`
    this.setData({
      selectedDate,
      month: buildMockCalendarMonth(yearMonth, selectedDate)
    })
  },

  handlePrevMonth() {
    const yearMonth = shiftMockYearMonth(this.data.month.yearMonth, -1)
    const selectedDate = `${yearMonth}-01`
    this.setData({
      selectedDate,
      month: buildMockCalendarMonth(yearMonth, selectedDate)
    })
  },

  handleNextMonth() {
    const yearMonth = shiftMockYearMonth(this.data.month.yearMonth, 1)
    const selectedDate = `${yearMonth}-01`
    this.setData({
      selectedDate,
      month: buildMockCalendarMonth(yearMonth, selectedDate)
    })
  },

  handleDayTap(event) {
    const selectedDate = event.currentTarget.dataset.date
    if (!selectedDate) {
      return
    }
    this.setData({
      selectedDate,
      month: buildMockCalendarMonth(this.data.month.yearMonth, selectedDate)
    })
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  }
})
