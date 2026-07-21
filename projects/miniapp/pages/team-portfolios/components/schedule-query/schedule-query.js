const DISPLAY_MODES = Object.freeze(['MODAL_CALENDAR', 'INLINE_CALENDAR'])
const QUERY_RANGE_TYPES = Object.freeze(['UNLIMITED', 'FUTURE_DAYS', 'DATE_RANGE'])
const MEMBER_STATE_AVAILABLE = 'AVAILABLE'
const MEMBER_STATE_PARTIAL = 'PARTIAL_AVAILABLE'
const MEMBER_STATE_FULL = 'FULL'
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const MONTH_PATTERN = /^(\d{4})-(\d{2})$/
const CALENDAR_DAY_COUNT = 42
const CALENDAR_DAY_BASE_CLASS = 'schedule-calendar-day'
const CALENDAR_DAY_MUTED_CLASS = 'muted'
const CALENDAR_DAY_DISABLED_CLASS = 'disabled'
const IDEMPOTENCY_KEY_MAX_LENGTH = 64
const IDEMPOTENCY_KEY_PREFIX = 'team-schedule-'
let idempotencySequence = 0

function text(value) {
  return String(value || '').trim()
}

function createScheduleIdempotencyKey() {
  idempotencySequence += 1
  return `${IDEMPOTENCY_KEY_PREFIX}${Date.now().toString(36)}-${idempotencySequence.toString(36)}`
}

function requireScheduleIdempotencyKey(value) {
  const normalized = text(value)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('档期查询幂等键必须为1至64个字符')
  return normalized
}

function scheduleRequestFingerprint(params = {}) {
  return [params.preview === true ? 'PREVIEW' : 'VISITOR', text(params.shareCode), Number(params.portfolioId) || 0, text(params.componentKey), text(params.queriedDate), text(params.previewScope)].join('|')
}

function normalizeQueryRange(source = {}) {
  const type = QUERY_RANGE_TYPES.includes(source.type) ? source.type : 'UNLIMITED'
  if (type === 'FUTURE_DAYS') {
    return { type, futureDays: Number.isInteger(Number(source.futureDays)) ? Number(source.futureDays) : null, startDate: null, endDate: null }
  }
  if (type === 'DATE_RANGE') {
    return { type, futureDays: null, startDate: text(source.startDate) || null, endDate: text(source.endDate) || null }
  }
  return { type: 'UNLIMITED', futureDays: null, startDate: null, endDate: null }
}

function createDefaultScheduleQueryConfig(source = {}) {
  return {
    title: text(source.title),
    description: text(source.description),
    displayMode: DISPLAY_MODES.includes(source.displayMode) ? source.displayMode : 'MODAL_CALENDAR',
    queryRange: normalizeQueryRange(source.queryRange)
  }
}

function isValidIsoDate(value) {
  if (!DATE_PATTERN.test(text(value))) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function pad2(value) {
  return String(value).padStart(2, '0')
}

function formatIsoDate(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

function formatYearMonth(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}`
}

function formatYearMonthTitle(yearMonth) {
  const match = text(yearMonth).match(MONTH_PATTERN)
  return match ? `${match[1]} 年 ${Number(match[2])} 月` : text(yearMonth)
}

function isValidMonth(value) {
  const match = text(value).match(MONTH_PATTERN)
  if (!match) return false
  const month = Number(match[2])
  return month >= 1 && month <= 12
}

function shiftMonth(yearMonth, offset) {
  const targetMonth = isValidMonth(yearMonth) ? yearMonth : formatYearMonth(new Date())
  const [year, month] = targetMonth.split('-').map(Number)
  return formatYearMonth(new Date(year, month - 1 + Number(offset || 0), 1))
}

function resolveInitialMonth(bounds = {}, todayText) {
  let targetDate = isValidIsoDate(todayText) ? todayText : localDateText()
  if (isValidIsoDate(bounds.startDate) && targetDate < bounds.startDate) targetDate = bounds.startDate
  if (isValidIsoDate(bounds.endDate) && targetDate > bounds.endDate) targetDate = bounds.endDate
  return targetDate.slice(0, 7)
}

function buildCalendarDays(yearMonth, bounds = {}) {
  const targetMonth = isValidMonth(yearMonth) ? yearMonth : formatYearMonth(new Date())
  const [year, month] = targetMonth.split('-').map(Number)
  const firstDay = new Date(year, month - 1, 1)
  const calendarStart = new Date(year, month - 1, 1 - firstDay.getDay())
  const startDate = isValidIsoDate(bounds.startDate) ? bounds.startDate : ''
  const endDate = isValidIsoDate(bounds.endDate) ? bounds.endDate : ''
  return Array.from({ length: CALENDAR_DAY_COUNT }, (_, index) => {
    const dateValue = new Date(calendarStart.getFullYear(), calendarStart.getMonth(), calendarStart.getDate() + index)
    const date = formatIsoDate(dateValue)
    const currentMonth = date.startsWith(targetMonth)
    const disabled = Boolean(startDate && date < startDate || endDate && date > endDate)
    const classes = [CALENDAR_DAY_BASE_CLASS]
    if (!currentMonth) classes.push(CALENDAR_DAY_MUTED_CLASS)
    if (disabled) classes.push(CALENDAR_DAY_DISABLED_CLASS)
    return {
      key: date,
      date,
      dayNumber: dateValue.getDate(),
      currentMonth,
      disabled,
      dayClass: classes.join(' ')
    }
  })
}

function validateScheduleQueryConfig(config = {}) {
  if (!DISPLAY_MODES.includes(config.displayMode)) return { valid: false, message: '请选择有效的档期展示方式' }
  const range = config.queryRange || {}
  if (!QUERY_RANGE_TYPES.includes(range.type)) return { valid: false, message: '请选择有效的查询范围' }
  if (range.type === 'FUTURE_DAYS' && (!Number.isInteger(Number(range.futureDays)) || Number(range.futureDays) <= 0)) {
    return { valid: false, message: '可查询未来天数必须大于0' }
  }
  if (range.type === 'DATE_RANGE' && (!isValidIsoDate(range.startDate) || !isValidIsoDate(range.endDate) || range.startDate > range.endDate)) {
    return { valid: false, message: '请选择有效的日期范围' }
  }
  return { valid: true, message: '' }
}

function addDays(dateText, days) {
  const date = new Date(`${dateText}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function localDateText() {
  const date = new Date()
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function resolveScheduleDateBounds(config = {}, todayText) {
  const today = isValidIsoDate(todayText) ? todayText : localDateText()
  const range = normalizeQueryRange(config.queryRange)
  if (range.type === 'FUTURE_DAYS' && Number(range.futureDays) > 0) {
    return { startDate: today, endDate: addDays(today, Number(range.futureDays)) }
  }
  if (range.type === 'DATE_RANGE') return { startDate: range.startDate || '', endDate: range.endDate || '' }
  return { startDate: today, endDate: '' }
}

function mapMemberScheduleState(member = {}) {
  const slotStatuses = Array.isArray(member.slotStatuses) ? member.slotStatuses : []
  if (slotStatuses.length > 0) {
    const availableStatusCount = slotStatuses.filter((status) => status === MEMBER_STATE_AVAILABLE).length
    if (availableStatusCount === slotStatuses.length) return { code: MEMBER_STATE_AVAILABLE, text: '空闲', tone: 'available' }
    if (availableStatusCount > 0) return { code: MEMBER_STATE_PARTIAL, text: '部分档期空闲', tone: 'partial' }
    return { code: MEMBER_STATE_FULL, text: '已满', tone: 'full' }
  }
  const total = Math.max(0, Number(member.totalSlotCount) || 0)
  const available = Math.max(0, Number(member.availableSlotCount) || 0)
  if (member.status === MEMBER_STATE_AVAILABLE || (total > 0 && available >= total)) return { code: MEMBER_STATE_AVAILABLE, text: '空闲', tone: 'available' }
  if (member.status === MEMBER_STATE_PARTIAL || (available > 0 && available < total)) return { code: MEMBER_STATE_PARTIAL, text: '部分档期空闲', tone: 'partial' }
  return { code: MEMBER_STATE_FULL, text: '已满', tone: 'full' }
}

function normalizeTeamScheduleResult(payload = {}) {
  return {
    portfolioType: text(payload.portfolioType) || 'TEAM',
    queriedDate: text(payload.queriedDate),
    status: text(payload.status),
    statusText: text(payload.statusText),
    available: payload.available === true,
    members: (Array.isArray(payload.members) ? payload.members : []).map((member = {}) => Object.assign({}, member, {
      memberUserId: Number(member.memberUserId) || null,
      displayName: text(member.displayName),
      avatarUrl: text(member.avatarUrl),
      displayState: mapMemberScheduleState(member)
    }))
  }
}

function createTeamScheduleQueryRunner(requestFn) {
  let loading = false
  const pendingKeys = new Map()
  return async function run(params = {}) {
    if (loading) return null
    const fingerprint = scheduleRequestFingerprint(params)
    const hasCallerKey = params.idempotencyKey !== undefined && params.idempotencyKey !== null
    const idempotencyKey = hasCallerKey
      ? requireScheduleIdempotencyKey(params.idempotencyKey)
      : (pendingKeys.get(fingerprint) || createScheduleIdempotencyKey())
    pendingKeys.set(fingerprint, idempotencyKey)
    loading = true
    try {
      const data = { componentKey: text(params.componentKey), queriedDate: text(params.queriedDate), idempotencyKey }
      if (params.preview === true) {
        const scopeQuery = params.previewScope ? `?scope=${encodeURIComponent(params.previewScope)}` : ''
        const result = normalizeTeamScheduleResult(await requestFn({ url: `/api/mine/team-portfolios/${Number(params.portfolioId)}/schedule-query-preview${scopeQuery}`, method: 'POST', data }))
        pendingKeys.delete(fingerprint)
        return result
      }
      const result = normalizeTeamScheduleResult(await requestFn({ url: `/api/visitor/team-portfolios/${encodeURIComponent(text(params.shareCode))}/schedule-query`, method: 'POST', authMode: 'visitor', data }))
      pendingKeys.delete(fingerprint)
      return result
    } finally {
      loading = false
    }
  }
}

function updateDraftField(draft, field, value) {
  return Object.assign({}, draft, { [field]: value })
}

function setCalendarMonth(component, yearMonth) {
  if (!isValidMonth(yearMonth)) return false
  const monthChanged = yearMonth !== component.data.selectedMonth
  const selectedDate = !monthChanged || text(component.data.selectedDate).startsWith(yearMonth)
    ? component.data.selectedDate : ''
  component.setData({
    selectedMonth: yearMonth,
    selectedMonthText: formatYearMonthTitle(yearMonth),
    calendarDays: buildCalendarDays(yearMonth, {
      startDate: component.data.dateStart,
      endDate: component.data.dateEnd
    }),
    selectedDate,
    pendingIdempotencyKey: monthChanged ? '' : component.data.pendingIdempotencyKey,
    result: monthChanged ? null : component.data.result
  })
  return true
}

function syncScheduleDraft(component) {
  const draft = createDefaultScheduleQueryConfig(component.properties.config)
  const bounds = resolveScheduleDateBounds(component.properties.config)
  const selectedDate = isValidIsoDate(component.data.selectedDate)
    && (!bounds.startDate || component.data.selectedDate >= bounds.startDate)
    && (!bounds.endDate || component.data.selectedDate <= bounds.endDate)
    ? component.data.selectedDate : ''
  const selectedMonth = selectedDate ? selectedDate.slice(0, 7) : resolveInitialMonth(bounds)
  component.setData({
    draft,
    dateStart: bounds.startDate,
    dateEnd: bounds.endDate,
    selectedDate,
    selectedMonth,
    selectedMonthText: formatYearMonthTitle(selectedMonth),
    calendarDays: buildCalendarDays(selectedMonth, bounds),
    pendingIdempotencyKey: '',
    errorMessage: ''
  })
}

Component({
  properties: {
    shareCode: { type: String, value: '' },
    portfolioId: { type: Number, value: 0 },
    componentKey: { type: String, value: '' },
    preview: { type: Boolean, value: false },
    previewScope: { type: String, value: '' },
    config: { type: Object, value: {}, observer() { if (!this.properties.editMode) syncScheduleDraft(this) } },
    result: { type: Object, value: {} },
    monthOptions: { type: Array, value: [] },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncScheduleDraft(this) } }
  },
  data: {
    modalVisible: false,
    loading: false,
    selectedDate: '',
    selectedMonth: '',
    selectedMonthText: '',
    calendarDays: [],
    pendingIdempotencyKey: '',
    dateStart: '',
    dateEnd: '',
    draft: createDefaultScheduleQueryConfig(),
    displayModes: DISPLAY_MODES,
    queryRangeTypes: QUERY_RANGE_TYPES,
    errorMessage: ''
  },
  methods: {
    beginEdit() { syncScheduleDraft(this) },
    handleConfigInput(event) { const draft = updateDraftField(this.data.draft, event.currentTarget.dataset.field, event.detail.value); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    selectDisplayMode(event) { const draft = updateDraftField(this.data.draft, 'displayMode', event.currentTarget.dataset.mode); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    selectRangeType(event) { const draft = updateDraftField(this.data.draft, 'queryRange', normalizeQueryRange({ type: event.currentTarget.dataset.type })); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    changeFutureDays(event) { const draft = updateDraftField(this.data.draft, 'queryRange', normalizeQueryRange({ type: 'FUTURE_DAYS', futureDays: event.detail.value })); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    changeRangeDate(event) { const field = event.currentTarget.dataset.field; const range = Object.assign({}, this.data.draft.queryRange, { [field]: event.detail.value }); const draft = updateDraftField(this.data.draft, 'queryRange', normalizeQueryRange(range)); this.setData({ draft }); this.triggerEvent('change', { config: draft }) },
    cancelEdit() { syncScheduleDraft(this); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateScheduleQueryConfig(this.data.draft); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: createDefaultScheduleQueryConfig(this.data.draft) }) },
    noop() {},
    openModal() { const bounds = resolveScheduleDateBounds(this.properties.config); this.setData({ modalVisible: true, dateStart: bounds.startDate, dateEnd: bounds.endDate }); setCalendarMonth(this, this.data.selectedMonth || resolveInitialMonth(bounds)); this.triggerEvent('open') },
    closeModal() { this.setData({ modalVisible: false }); this.triggerEvent('close') },
    selectDate(event) { const selectedDate = event.currentTarget.dataset.date || event.detail.value || ''; this.setData({ selectedDate, pendingIdempotencyKey: selectedDate === this.data.selectedDate ? this.data.pendingIdempotencyKey : '' }); this.triggerEvent('datechange', { queriedDate: selectedDate }) },
    handlePrevMonth() { setCalendarMonth(this, shiftMonth(this.data.selectedMonth, -1)) },
    handleNextMonth() { setCalendarMonth(this, shiftMonth(this.data.selectedMonth, 1)) },
    handleMonthPickerChange(event) { setCalendarMonth(this, event && event.detail ? event.detail.value : '') },
    handleDayTap(event) {
      const dataset = event && event.currentTarget ? event.currentTarget.dataset || {} : {}
      const selectedDate = text(dataset.date)
      if (!selectedDate || dataset.disabled === true || dataset.disabled === 'true') return
      const sameDate = selectedDate === this.data.selectedDate
      this.setData({
        selectedDate,
        pendingIdempotencyKey: sameDate ? this.data.pendingIdempotencyKey : '',
        errorMessage: '',
        result: sameDate ? this.data.result : null
      })
      this.triggerEvent('datechange', { queriedDate: selectedDate })
    },
    changeMonth(event) { if (this.data.loading) return; this.setData({ loading: true }); this.triggerEvent('monthchange', { month: event.currentTarget.dataset.month }) },
    completeMonth(event) { this.setData({ loading: false, errorMessage: event && event.detail ? text(event.detail.errorMessage) : '' }) },
    submitQuery() { if (this.data.loading) return; if (!this.data.selectedDate) return this.setData({ errorMessage: '请选择查询日期' }); const idempotencyKey = this.data.pendingIdempotencyKey || createScheduleIdempotencyKey(); this.setData({ loading: true, pendingIdempotencyKey: idempotencyKey, errorMessage: '' }); this.triggerEvent('schedulequery', { shareCode: this.properties.shareCode, portfolioId: this.properties.portfolioId, componentKey: this.properties.componentKey, queriedDate: this.data.selectedDate, idempotencyKey, preview: this.properties.preview, previewScope: this.properties.previewScope }) },
    resolveQuery(event) { this.setData({ loading: false, pendingIdempotencyKey: '', errorMessage: '', result: normalizeTeamScheduleResult(event.detail || {}) }) },
    rejectQuery(event) { const detail = event.detail || {}; this.setData({ loading: false, pendingIdempotencyKey: detail.clearPendingIdempotencyKey === true ? '' : this.data.pendingIdempotencyKey, errorMessage: text(detail.message) || '档期查询失败，请重试' }) }
  },
  lifetimes: {
    attached() { syncScheduleDraft(this) }
  }
})

module.exports = { DISPLAY_MODES, IDEMPOTENCY_KEY_MAX_LENGTH, QUERY_RANGE_TYPES, buildCalendarDays, createDefaultScheduleQueryConfig, createScheduleIdempotencyKey, createTeamScheduleQueryRunner, formatYearMonthTitle, mapMemberScheduleState, normalizeTeamScheduleResult, resolveInitialMonth, resolveScheduleDateBounds, shiftMonth, validateScheduleQueryConfig }
