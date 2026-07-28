const { normalizeId } = require('../../../utils/id')
const {
  formatLunarDayMeta,
  formatLunarFullText,
  toLunarDate
} = require('../../../utils/lunar')

const DEFAULT_SLOT_COLOR = '#c28f4b'

const SLOT_COLOR_OPTIONS = [
  { name: '琥珀', color: '#c28f4b' },
  { name: '湖蓝', color: '#6f8cb5' },
  { name: '青绿', color: '#5f999b' },
  { name: '玫红', color: '#af7482' },
  { name: '森绿', color: '#738e71' },
  { name: '紫藤', color: '#897dbc' },
  { name: '珊瑚', color: '#c87a65' },
  { name: '墨蓝', color: '#667b96' }
].map((item) => Object.assign({}, item, {
  style: `background: ${item.color};`,
  swatchStyle: `background: ${item.color};`
}))

const SLOT_STATUS_TEXT = {
  ACTIVE: '启用',
  DISABLED: '停用'
}

const DEFAULT_SCHEDULE_STATUS = 'TENTATIVE'

const SCHEDULE_STATUS_OPTIONS = [
  { value: 'TENTATIVE', text: '待定', tone: 'amber' },
  { value: 'BOOKED', text: '已约', tone: 'rose' },
  { value: 'REST', text: '休息', tone: 'muted' }
]

const SCHEDULE_STATUS_MAP = SCHEDULE_STATUS_OPTIONS.reduce((result, item) => {
  result[item.value] = item
  return result
}, {})

const SLOT_FIELD_LIMITS = {
  name: 30
}

const SCHEDULE_FIELD_LIMITS = {
  contactName: 50,
  contactPhone: 50,
  note: 1000
}

const COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIME_PATTERN = /^\d{2}:\d{2}$/
const SLOT_DURATION_MINUTES = 2 * 60
const MINUTES_PER_HOUR = 60
const LAST_MINUTE_OF_DAY = 23 * MINUTES_PER_HOUR + 59
const DAY_META_FIELDS = ['holidayText', 'festivalText', 'noteText', 'lunarText', 'metaText']

function trimText(value) {
  return String(value || '').trim()
}

function countText(value) {
  return Array.from(String(value || '')).length
}

function toNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function normalizeColor(value) {
  const color = trimText(value)
  return COLOR_PATTERN.test(color) ? color : DEFAULT_SLOT_COLOR
}

function normalizeTime(value) {
  const time = trimText(value)
  return TIME_PATTERN.test(time) ? time : ''
}

function buildDefaultSlotEndTime(startTime) {
  const [hour, minute] = normalizeTime(startTime).split(':').map(Number)
  const endMinutes = Math.min(
    hour * MINUTES_PER_HOUR + minute + SLOT_DURATION_MINUTES,
    LAST_MINUTE_OF_DAY
  )
  return `${String(Math.floor(endMinutes / MINUTES_PER_HOUR)).padStart(2, '0')}:${String(endMinutes % MINUTES_PER_HOUR).padStart(2, '0')}`
}

function buildTimeRangeText(startTime, endTime) {
  if (!startTime || !endTime) {
    return '未设置时间'
  }
  return `${startTime}-${endTime}`
}

function normalizeSlotDefinition(raw = {}) {
  const color = normalizeColor(raw.color)
  const startTime = normalizeTime(raw.startTime)
  const endTime = normalizeTime(raw.endTime)
  const status = trimText(raw.status) || (raw.enabled === false ? 'DISABLED' : 'ACTIVE')
  const enabled = raw.enabled === undefined ? status === 'ACTIVE' : Boolean(raw.enabled)
  return {
    id: normalizeId(raw.id),
    name: trimText(raw.name) || '未命名档位',
    startTime,
    endTime,
    timeRangeText: buildTimeRangeText(startTime, endTime),
    color,
    colorStyle: `background: ${color};`,
    isSystemDefault: toNumber(raw.isSystemDefault),
    status,
    statusText: raw.statusText || SLOT_STATUS_TEXT[status] || '未知',
    enabled,
    statusClass: `slot-status ${enabled ? 'active' : 'muted'}`
  }
}

function formatYearMonthTitle(yearMonth) {
  const value = trimText(yearMonth)
  const match = value.match(/^(\d{4})-(\d{2})$/)
  return match ? `${match[1]} 年 ${Number(match[2])} 月` : '本月'
}

function formatDateTitle(dateText) {
  const value = trimText(dateText)
  const match = value.match(/^\d{4}-(\d{2})-(\d{2})$/)
  return match ? `${match[1]}月${match[2]}日` : '请选择日期'
}

function normalizeMarkerColors(colors) {
  if (!Array.isArray(colors)) {
    return []
  }
  return colors.map(normalizeColor).map((color) => ({
    color,
    style: `background: ${color};`
  }))
}

function buildSelectedDateLunarTitleText(raw = {}) {
  const titleText = trimText(raw.lunarTitleText)
  if (titleText) {
    return titleText
  }
  const lunarText = formatLunarFullText(toLunarDate(raw.date))
  return lunarText ? `农历${lunarText}` : ''
}

function buildDayMetaText(raw = {}) {
  const field = DAY_META_FIELDS.find((item) => trimText(raw[item]))
  return field ? trimText(raw[field]) : formatLunarDayMeta(toLunarDate(raw.date))
}

function normalizeMonthDay(raw = {}) {
  const count = toNumber(raw.count)
  const classes = ['calendar-day']
  if (!raw.currentMonth) {
    classes.push('muted')
  }
  if (raw.selected) {
    classes.push('selected')
  }
  if (count > 0) {
    classes.push('filled')
  }
  return {
    date: raw.date || '',
    dayNumber: toNumber(raw.dayNumber),
    currentMonth: Boolean(raw.currentMonth),
    selected: Boolean(raw.selected),
    colors: Array.isArray(raw.colors) ? raw.colors.map(normalizeColor) : [],
    markerColors: normalizeMarkerColors(raw.colors),
    metaText: buildDayMetaText(raw),
    count,
    countText: count > 0 ? String(count) : '',
    dayClass: classes.join(' ')
  }
}

function normalizeScheduleItem(raw = {}) {
  const status = trimText(raw.status) || DEFAULT_SCHEDULE_STATUS
  const statusOption = SCHEDULE_STATUS_MAP[status] || {}
  const tone = raw.statusTone || statusOption.tone || 'muted'
  const contactName = trimText(raw.contactName)
  const contactPhone = trimText(raw.contactPhone)
  return {
    id: normalizeId(raw.id),
    scheduleDate: raw.scheduleDate || '',
    slotDefinitionId: normalizeId(raw.slotDefinitionId),
    slotName: trimText(raw.slotName) || '未命名档位',
    startTime: normalizeTime(raw.startTime),
    endTime: normalizeTime(raw.endTime),
    timeRangeText: buildTimeRangeText(normalizeTime(raw.startTime), normalizeTime(raw.endTime)),
    color: normalizeColor(raw.color),
    colorStyle: `background: ${normalizeColor(raw.color)};`,
    status,
    statusText: raw.statusText || statusOption.text || '未知',
    statusTone: tone,
    statusClass: `schedule-status ${tone}`,
    contactName,
    contactPhone,
    contactText: buildContactText(contactName, contactPhone),
    note: trimText(raw.note),
    lockedSnapshot: toNumber(raw.lockedSnapshot),
    descText: raw.descText || ''
  }
}

function buildContactText(contactName, contactPhone) {
  if (contactName && contactPhone) {
    return `${contactName} · ${contactPhone}`
  }
  if (contactName) {
    return contactName
  }
  if (contactPhone) {
    return contactPhone
  }
  return '未留联系人'
}

function normalizeSlotDefinitions(raw = []) {
  return Array.isArray(raw) ? raw.map(normalizeSlotDefinition) : []
}

function normalizeMonthOverview(raw = {}, selectedDate = '') {
  const selectedDateText = trimText(selectedDate)
  return {
    yearMonth: raw.yearMonth || '',
    titleText: formatYearMonthTitle(raw.yearMonth),
    days: Array.isArray(raw.days)
      ? raw.days.map((item) => normalizeMonthDay(selectedDateText
        ? Object.assign({}, item, { selected: item.date === selectedDateText })
        : item))
      : []
  }
}

function normalizeSelectedDateOverview(raw = {}) {
  const schedules = Array.isArray(raw.schedules)
    ? raw.schedules.map(normalizeScheduleItem)
    : []
  return {
    date: raw.date || '',
    titleText: formatDateTitle(raw.date),
    lunarTitleText: buildSelectedDateLunarTitleText(raw),
    summaryText: raw.summaryText || (schedules.length > 0 ? `${schedules.length} 条档期` : '暂无档期'),
    schedules,
    empty: schedules.length === 0
  }
}

function markMonthSelectedDate(month = {}, selectedDate = '') {
  return normalizeMonthOverview(month, selectedDate)
}

function normalizeScheduleOverview(raw = {}) {
  const selectedDate = raw.selectedDate || {}
  return {
    slotDefinitions: normalizeSlotDefinitions(raw.slotDefinitions),
    month: normalizeMonthOverview(raw.month || {}, selectedDate.date),
    selectedDate: normalizeSelectedDateOverview(selectedDate)
  }
}

function buildSlotDefinitionPayload(form = {}) {
  return {
    name: trimText(form.name),
    startTime: normalizeTime(form.startTime),
    endTime: normalizeTime(form.endTime),
    color: normalizeColor(form.color),
    status: trimText(form.status) || 'ACTIVE'
  }
}

function buildScheduleItemPayload(form = {}) {
  const payload = {
    scheduleDate: trimText(form.scheduleDate),
    slotDefinitionId: normalizeId(form.slotDefinitionId),
    status: trimText(form.status) || DEFAULT_SCHEDULE_STATUS,
    contactName: trimText(form.contactName),
    contactPhone: trimText(form.contactPhone),
    note: trimText(form.note)
  }
  const scheduleId = normalizeId(form.scheduleId)
  if (scheduleId) {
    payload.scheduleId = scheduleId
  }
  return payload
}

function buildScheduleFieldCounters(form = {}) {
  return Object.keys(SCHEDULE_FIELD_LIMITS).reduce((result, field) => {
    result[field] = `${countText(form[field])} / ${SCHEDULE_FIELD_LIMITS[field]}`
    return result
  }, {})
}

function buildSlotDefinitionFieldCounters(form = {}) {
  return Object.keys(SLOT_FIELD_LIMITS).reduce((result, field) => {
    result[field] = `${countText(form[field])} / ${SLOT_FIELD_LIMITS[field]}`
    return result
  }, {})
}

function validateSlotDefinitionPayload(payload = {}) {
  if (!payload.name) {
    return { valid: false, message: '请输入档位名称' }
  }
  if (countText(payload.name) > SLOT_FIELD_LIMITS.name) {
    return { valid: false, message: '档位名称不能超过 30 个字' }
  }
  if (!payload.startTime) {
    return { valid: false, message: '请选择开始时间' }
  }
  if (!payload.endTime) {
    return { valid: false, message: '请选择结束时间' }
  }
  if (payload.startTime >= payload.endTime) {
    return { valid: false, message: '开始时间必须早于结束时间' }
  }
  if (!COLOR_PATTERN.test(trimText(payload.color))) {
    return { valid: false, message: '请选择有效的档位颜色' }
  }
  if (!SLOT_STATUS_TEXT[payload.status]) {
    return { valid: false, message: '请选择有效的档位定义状态' }
  }
  return { valid: true, message: '' }
}

function validateSlotDefinitionForm(form = {}) {
  return validateSlotDefinitionPayload(buildSlotDefinitionPayload(form))
}

function validateScheduleItemPayload(payload = {}) {
  if (!DATE_PATTERN.test(payload.scheduleDate)) {
    return { valid: false, message: '请选择档期日期' }
  }
  if (!payload.slotDefinitionId) {
    return { valid: false, message: '请选择档位定义' }
  }
  if (!SCHEDULE_STATUS_MAP[payload.status]) {
    return { valid: false, message: '请选择有效的档期状态' }
  }
  if (countText(payload.contactName) > SCHEDULE_FIELD_LIMITS.contactName) {
    return { valid: false, message: '联系人不能超过 50 个字' }
  }
  if (countText(payload.contactPhone) > SCHEDULE_FIELD_LIMITS.contactPhone) {
    return { valid: false, message: '联系电话不能超过 50 个字' }
  }
  if (countText(payload.note) > SCHEDULE_FIELD_LIMITS.note) {
    return { valid: false, message: '档期备注不能超过 1000 个字' }
  }
  return { valid: true, message: '' }
}

function validateScheduleItemForm(form = {}) {
  return validateScheduleItemPayload(buildScheduleItemPayload(form))
}

module.exports = {
  DEFAULT_SCHEDULE_STATUS,
  DEFAULT_SLOT_COLOR,
  SCHEDULE_STATUS_OPTIONS,
  SLOT_COLOR_OPTIONS,
  buildDefaultSlotEndTime,
  buildScheduleFieldCounters,
  buildScheduleItemPayload,
  buildSlotDefinitionFieldCounters,
  buildSlotDefinitionPayload,
  markMonthSelectedDate,
  normalizeMonthOverview,
  normalizeScheduleOverview,
  normalizeSelectedDateOverview,
  normalizeSlotDefinitions,
  validateScheduleItemForm,
  validateScheduleItemPayload,
  validateSlotDefinitionPayload,
  validateSlotDefinitionForm
}
