const {
  normalizeVisitorScheduleOptions,
  normalizeVisitorScheduleQueryResult
} = require('../../utils/visitor-portfolio')
const { requestWithVisitorSessionRefresh } = require('../../utils/visitor-session')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const MINE_PORTFOLIO_API_PREFIX = '/api/mine/portfolios'
const MINE_TEAM_PORTFOLIO_API_PREFIX = '/api/mine/team-portfolios'
const DISPLAY_MODE_INLINE_CALENDAR = 'INLINE_CALENDAR'
const PUBLISHED_PREVIEW_SCOPE = 'published'
const DRAFT_PREVIEW_SCOPE = 'draft'
const DATE_REQUIRED_MESSAGE = '请选择日期'
const SLOT_REQUIRED_MESSAGE = '请选择档位'
const LOAD_FAILED_MESSAGE = '月历加载失败'
const QUERY_FAILED_MESSAGE = '档期查询失败'
const IDEMPOTENCY_KEY_PREFIX = 'schedule-query'
const IDEMPOTENCY_KEY_MAX_LENGTH = 64
const IDEMPOTENCY_RANDOM_SEGMENT_LENGTH = 8
const MONTH_PATTERN = /^(\d{4})-(\d{2})$/
const CALENDAR_DAY_BASE_CLASS = 'schedule-calendar-day'
const CALENDAR_DAY_FILLED_CLASS = 'filled'

function pad2(value) {
  return String(value).padStart(2, '0')
}

function formatYearMonth(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}`
}

function formatYearMonthTitle(yearMonth) {
  const match = String(yearMonth || '').match(MONTH_PATTERN)
  return match ? `${match[1]} 年 ${Number(match[2])} 月` : yearMonth || ''
}

function getCurrentYearMonth() {
  return formatYearMonth(new Date())
}

function shiftMonth(yearMonth, offset) {
  const parts = String(yearMonth || '').split('-')
  const year = Number(parts[0])
  const month = Number(parts[1])
  const base = Number.isFinite(year) && Number.isFinite(month)
    ? new Date(year, month - 1 + offset, 1)
    : new Date()
  return formatYearMonth(base)
}

function isValidMonth(value) {
  return MONTH_PATTERN.test(String(value || ''))
}

function normalizeIdempotencySegment(value) {
  const text = String(value === undefined || value === null ? '' : value).trim()
  return encodeURIComponent(text || 'none')
}

function createIdempotencySuffix() {
  const randomSegment = Math.random().toString(16).slice(2, 2 + IDEMPOTENCY_RANDOM_SEGMENT_LENGTH)
  return `${Date.now()}-${randomSegment}`
}

function createIdempotencyKey(data = {}) {
  const baseKey = [
    IDEMPOTENCY_KEY_PREFIX,
    normalizeIdempotencySegment(data.componentKey),
    normalizeIdempotencySegment(data.selectedDate),
    normalizeIdempotencySegment(data.selectedSlotDefinitionId)
  ].join('-')
  const suffix = createIdempotencySuffix()
  const maxBaseLength = IDEMPOTENCY_KEY_MAX_LENGTH - suffix.length - 1
  return `${baseKey.slice(0, maxBaseLength)}-${suffix}`
}

function getRuntimeWx() {
  return typeof wx !== 'undefined' ? wx : { showToast() {} }
}

function isPublishedScope(scope) {
  return String(scope || '') === PUBLISHED_PREVIEW_SCOPE
}

function normalizeTeamPreviewScope(scope) {
  return isPublishedScope(scope) ? PUBLISHED_PREVIEW_SCOPE : DRAFT_PREVIEW_SCOPE
}

function canLoadOptions(data = {}) {
  return data.preview ? Boolean(data.portfolioId) : Boolean(data.shareCode)
}

function buildOptionsRequest(data = {}, month) {
  if (data.preview) {
    const requestData = {
      month,
      componentKey: data.componentKey || ''
    }
    if (data.teamPortfolioId) {
      requestData.scope = normalizeTeamPreviewScope(data.teamPreviewScope)
      return {
        url: `${MINE_TEAM_PORTFOLIO_API_PREFIX}/${data.teamPortfolioId}/member-portfolios/${data.portfolioId}/schedule-options`,
        data: requestData
      }
    }
    if (isPublishedScope(data.previewScope)) {
      requestData.scope = PUBLISHED_PREVIEW_SCOPE
    }
    return {
      url: `${MINE_PORTFOLIO_API_PREFIX}/${data.portfolioId}/schedule-options`,
      data: requestData
    }
  }
  return {
    url: `${VISITOR_PORTFOLIO_API_PREFIX}/${data.shareCode}/schedule-options`,
    authMode: 'visitor',
    data: {
      month,
      componentKey: data.componentKey || ''
    }
  }
}

function buildQueryRequest(data = {}) {
  const payload = {
    componentKey: data.componentKey || '',
    queriedDate: data.selectedDate || '',
    slotDefinitionId: data.selectedSlotDefinitionId,
    idempotencyKey: createIdempotencyKey(data)
  }
  if (data.preview) {
    if (data.teamPortfolioId) {
      const teamScope = normalizeTeamPreviewScope(data.teamPreviewScope)
      return {
        url: `${MINE_TEAM_PORTFOLIO_API_PREFIX}/${data.teamPortfolioId}/member-portfolios/${data.portfolioId}/schedule-query-preview?scope=${teamScope}`,
        method: 'POST',
        data: payload
      }
    }
    const scopeQuery = isPublishedScope(data.previewScope) ? '?scope=published' : ''
    return {
      url: `${MINE_PORTFOLIO_API_PREFIX}/${data.portfolioId}/schedule-query-preview${scopeQuery}`,
      method: 'POST',
      data: payload
    }
  }
  return {
    url: `${VISITOR_PORTFOLIO_API_PREFIX}/${data.shareCode}/schedule-query`,
    method: 'POST',
    authMode: 'visitor',
    data: Object.assign({
      visitorKey: data.visitorKey || ''
    }, payload)
  }
}

function sendScheduleRequest(requestOptions, data = {}) {
  return requestWithVisitorSessionRefresh(requestOptions, {
    shareCode: data.shareCode
  })
}

function findSchedulesByDate(options = {}, date = '') {
  const schedules = Array.isArray(options.schedules) ? options.schedules : []
  return schedules.filter((item) => item.date === date)
}

function removeFilledDayClass(dayClass = '') {
  const classes = String(dayClass || '')
    .split(/\s+/)
    .filter((item) => item && item !== CALENDAR_DAY_FILLED_CLASS)
  return classes.length ? classes.join(' ') : CALENDAR_DAY_BASE_CLASS
}

function hideVisitorScheduleHints(options = {}) {
  const days = Array.isArray(options.days) ? options.days : []
  return Object.assign({}, options, {
    days: days.map((day) => Object.assign({}, day, {
      colors: [],
      count: 0,
      dayClass: removeFilledDayClass(day.dayClass)
    })),
    schedules: []
  })
}

function buildDisplayOptions(data = {}, options = {}) {
  return data.preview ? options : hideVisitorScheduleHints(options)
}

Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
    shareCode: {
      type: String,
      value: ''
    },
    portfolioId: {
      type: Number,
      value: 0
    },
    teamPortfolioId: {
      type: Number,
      value: 0
    },
    teamPreviewScope: {
      type: String,
      value: ''
    },
    visitorKey: {
      type: String,
      value: ''
    },
    preview: {
      type: Boolean,
      value: false
    },
    previewScope: {
      type: String,
      value: ''
    },
    componentKey: {
      type: String,
      value: ''
    },
    scheduleQuery: {
      type: Object,
      value: {}
    }
  },

  data: {
    visible: false,
    selectedMonth: getCurrentYearMonth(),
    selectedMonthText: formatYearMonthTitle(getCurrentYearMonth()),
    options: normalizeVisitorScheduleOptions({}),
    selectedDate: '',
    selectedDaySchedules: [],
    selectedSlotDefinitionId: null,
    loading: false,
    submitting: false,
    errorMessage: '',
    result: null
  },

  observers: {
    'scheduleQuery.displayMode, shareCode, portfolioId, teamPortfolioId, teamPreviewScope, preview': function() {
      this.ensureInlineOptionsLoaded()
    }
  },

  lifetimes: {
    ready() {
      this.ensureInlineOptionsLoaded()
    }
  },

  methods: {
    noop() {},

    ensureInlineOptionsLoaded() {
      if (this.data.scheduleQuery
          && this.data.scheduleQuery.displayMode === DISPLAY_MODE_INLINE_CALENDAR
          && !this.data.options.yearMonth
          && !this.data.loading
          && canLoadOptions(this.data)) {
        this.loadScheduleOptions(this.data.selectedMonth)
      }
    },

    handleOpenCalendar() {
      this.setData({ visible: true })
      if (!this.data.options.yearMonth && !this.data.loading) {
        return this.loadScheduleOptions(this.data.selectedMonth)
      }
      return Promise.resolve()
    },

    handleCloseCalendar() {
      this.setData({ visible: false })
    },

    handlePrevMonth() {
      return this.loadScheduleOptions(shiftMonth(this.data.selectedMonth, -1))
    },

    handleNextMonth() {
      return this.loadScheduleOptions(shiftMonth(this.data.selectedMonth, 1))
    },

    handleMonthPickerChange(event) {
      const selectedMonth = event.detail && event.detail.value
      if (!isValidMonth(selectedMonth)) {
        return Promise.resolve(null)
      }
      return this.loadScheduleOptions(selectedMonth)
    },

    handleRetryLoad() {
      return this.loadScheduleOptions(this.data.selectedMonth)
    },

    async loadScheduleOptions(month) {
      if (this.data.loading) {
        return null
      }
      const targetMonth = month || this.data.selectedMonth || getCurrentYearMonth()
      this.setData({
        selectedMonth: targetMonth,
        selectedMonthText: formatYearMonthTitle(targetMonth),
        loading: true,
        errorMessage: '',
        result: null
      })
      try {
        const requestOptions = buildOptionsRequest(this.data, targetMonth)
        const response = await sendScheduleRequest(requestOptions, this.data)
        const options = normalizeVisitorScheduleOptions(response)
        const displayOptions = buildDisplayOptions(this.data, options)
        const selectedDate = this.data.selectedDate && String(this.data.selectedDate).startsWith(options.yearMonth || targetMonth)
          ? this.data.selectedDate
          : ''
        this.setData({
          selectedMonth: options.yearMonth || targetMonth,
          selectedMonthText: formatYearMonthTitle(options.yearMonth || targetMonth),
          options: displayOptions,
          selectedDate,
          selectedDaySchedules: selectedDate ? findSchedulesByDate(displayOptions, selectedDate) : [],
          selectedSlotDefinitionId: null,
          loading: false,
          errorMessage: ''
        })
        return options
      } catch (error) {
        const message = error && error.message ? error.message : LOAD_FAILED_MESSAGE
        this.setData({
          loading: false,
          errorMessage: message
        })
        getRuntimeWx().showToast({ title: message, icon: 'none' })
        return null
      }
    },

    handleDayTap(event) {
      const date = event.currentTarget.dataset.date || ''
      if (!date) {
        return
      }
      this.setData({
        selectedDate: date,
        selectedDaySchedules: findSchedulesByDate(this.data.options, date),
        selectedSlotDefinitionId: null,
        result: null
      })
    },

    handleSlotTap(event) {
      const slotDefinitionId = Number(event.currentTarget.dataset.id)
      if (!slotDefinitionId) {
        return
      }
      this.setData({
        selectedSlotDefinitionId: slotDefinitionId,
        result: null
      })
    },

    async handleSubmitQuery() {
      if (!this.data.selectedDate) {
        getRuntimeWx().showToast({ title: DATE_REQUIRED_MESSAGE, icon: 'none' })
        return false
      }
      if (!this.data.selectedSlotDefinitionId) {
        getRuntimeWx().showToast({ title: SLOT_REQUIRED_MESSAGE, icon: 'none' })
        return false
      }
      this.setData({
        submitting: true,
        errorMessage: ''
      })
      try {
        const response = await sendScheduleRequest(buildQueryRequest(this.data), this.data)
        const result = normalizeVisitorScheduleQueryResult(response)
        this.setData({
          submitting: false,
          result
        })
        getRuntimeWx().showToast({ title: result.message || '查询完成', icon: 'none' })
        return result
      } catch (error) {
        const message = error && error.message ? error.message : QUERY_FAILED_MESSAGE
        this.setData({
          submitting: false,
          errorMessage: message
        })
        getRuntimeWx().showToast({ title: message, icon: 'none' })
        return false
      }
    }
  }
})
