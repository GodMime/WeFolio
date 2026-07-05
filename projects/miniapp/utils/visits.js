const FOLLOW_STATUS_NOT_FOLLOWED_UP = 'NOT_FOLLOWED_UP'
const FOLLOW_STATUS_CONTACTED = 'CONTACTED'
const FOLLOW_STATUS_DEAL_WON = 'DEAL_WON'
const FOLLOW_STATUS_INVALID = 'INVALID'
const FOLLOW_STATUS_TEXT_NOT_FOLLOWED_UP = '未跟进'
const FOLLOW_STATUS_TEXT_CONTACTED = '已跟进'
const FOLLOW_STATUS_TEXT_DEAL_WON = '已成交'
const FOLLOW_STATUS_TEXT_INVALID = '无效'
const FOLLOW_TONE_ROSE = 'rose'
const FOLLOW_TONE_TEAL = 'teal'
const FOLLOW_TONE_BLUE = 'blue'
const FOLLOW_TONE_MUTED = 'muted'

function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function toDisplayText(value) {
  return String(toNumber(value))
}

function toPositiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : fallback
}

function defaultTrendPoints() {
  return ['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map((label) => ({
    label,
    value: 0,
    valueText: '0',
    height: 18
  }))
}

function normalizeTrendPoints(points) {
  const list = Array.isArray(points) && points.length > 0 ? points : defaultTrendPoints()
  const normalized = list.map((item) => ({
    label: item.label || '',
    date: item.date || '',
    value: toNumber(item.value)
  }))
  const maxValue = normalized.reduce((max, item) => Math.max(max, item.value), 0)
  return normalized.map((item) => ({
    label: item.label,
    date: item.date,
    value: item.value,
    valueText: toDisplayText(item.value),
    height: maxValue > 0 ? Math.max(18, Math.round((item.value / maxValue) * 100)) : 18
  }))
}

function extractVisitorInitial(record) {
  if (record.visitorInitial) {
    return String(record.visitorInitial).slice(0, 1).toUpperCase()
  }
  const source = record.visitorCode || record.visitorLabel || ''
  const match = String(source).match(/[A-Za-z0-9]/)
  return match ? match[0].toUpperCase() : '访'
}

function normalizeVisitEvent(event = {}) {
  const tone = event.tone || 'muted'
  return {
    id: event.eventId || event.id || `${event.eventType || 'event'}-${event.occurredDateText || ''}-${event.occurredTimeText || ''}`,
    eventId: event.eventId || event.id || null,
    eventType: event.eventType || '',
    title: event.title || '访问事件',
    detailText: event.detailText || '暂无补充信息',
    occurredDateText: event.occurredDateText || '',
    occurredTimeText: event.occurredTimeText || '',
    tone,
    toneClass: `visit-event-dot ${tone}`
  }
}

function compareVisitEventDesc(left, right) {
  const leftTimeKey = `${left.occurredDateText || ''} ${left.occurredTimeText || ''}`
  const rightTimeKey = `${right.occurredDateText || ''} ${right.occurredTimeText || ''}`
  if (leftTimeKey > rightTimeKey) {
    return -1
  }
  if (leftTimeKey < rightTimeKey) {
    return 1
  }
  return toNumber(right.eventId || right.id) - toNumber(left.eventId || left.id)
}

function normalizeVisitEvents(events) {
  return Array.isArray(events) ? events.map(normalizeVisitEvent).sort(compareVisitEventDesc) : []
}

function buildEventCountText(events) {
  return `${events.length} 个事件`
}

function normalizeFollowStatus(record = {}) {
  return record.followStatus || FOLLOW_STATUS_NOT_FOLLOWED_UP
}

function buildFollowStatusText(followStatus, displayText) {
  if (displayText) {
    return displayText
  }
  if (followStatus === FOLLOW_STATUS_CONTACTED) {
    return FOLLOW_STATUS_TEXT_CONTACTED
  }
  if (followStatus === FOLLOW_STATUS_DEAL_WON) {
    return FOLLOW_STATUS_TEXT_DEAL_WON
  }
  if (followStatus === FOLLOW_STATUS_INVALID) {
    return FOLLOW_STATUS_TEXT_INVALID
  }
  return FOLLOW_STATUS_TEXT_NOT_FOLLOWED_UP
}

function buildFollowTone(followStatus, tone) {
  if (tone) {
    return tone
  }
  if (followStatus === FOLLOW_STATUS_CONTACTED) {
    return FOLLOW_TONE_TEAL
  }
  if (followStatus === FOLLOW_STATUS_DEAL_WON) {
    return FOLLOW_TONE_BLUE
  }
  if (followStatus === FOLLOW_STATUS_INVALID) {
    return FOLLOW_TONE_MUTED
  }
  return FOLLOW_TONE_ROSE
}

function normalizeRecord(record = {}) {
  const followStatus = normalizeFollowStatus(record)
  const followTone = buildFollowTone(followStatus, record.followTone)
  const events = normalizeVisitEvents(record.events)
  const pageNo = toPositiveNumber(record.pageNo, 1)
  const pageSize = toPositiveNumber(record.pageSize, 20)
  const hasMore = Boolean(record.hasMore)
  return {
    id: record.id || record.recordId || record.visitorCode || record.visitorLabel,
    recordId: record.recordId || record.id || null,
    visitorLabel: record.visitorLabel || '微信访客',
    visitorInitial: extractVisitorInitial(record),
    visitorAvatarUrl: record.visitorAvatarUrl || '',
    sourceText: record.sourceText || '来自未知来源',
    summaryText: record.summaryText || '暂无行为摘要',
    followStatus,
    followStatusText: buildFollowStatusText(followStatus, record.followStatusText),
    followTone,
    followToneClass: `follow-pill ${followTone}`,
    canMarkFollowed: followStatus === FOLLOW_STATUS_NOT_FOLLOWED_UP,
    lastVisitedText: record.lastVisitedText || '',
    events,
    eventCountText: buildEventCountText(events),
    pageNo,
    pageSize,
    hasMore,
    nextPage: hasMore ? pageNo + 1 : null
  }
}

function normalizeVisitEventTimeline(raw = {}, fallbackRecord = {}) {
  const merged = Object.assign({}, fallbackRecord, raw, {
    id: raw.recordId || fallbackRecord.id,
    recordId: raw.recordId || fallbackRecord.recordId || fallbackRecord.id,
    events: Array.isArray(raw.events) ? raw.events : fallbackRecord.events
  })
  return normalizeRecord(merged)
}

function appendVisitEventTimeline(current = {}, nextPage = {}) {
  const events = normalizeVisitEvents([]
    .concat(Array.isArray(current.events) ? current.events : [])
    .concat(Array.isArray(nextPage.events) ? nextPage.events : []))
  const pageNo = toPositiveNumber(nextPage.pageNo, toPositiveNumber(current.pageNo, 1))
  const pageSize = toPositiveNumber(nextPage.pageSize, toPositiveNumber(current.pageSize, 20))
  const hasMore = Boolean(nextPage.hasMore)
  return Object.assign({}, current, nextPage, {
    events,
    eventCountText: buildEventCountText(events),
    pageNo,
    pageSize,
    hasMore,
    nextPage: hasMore ? pageNo + 1 : null
  })
}

function normalizeVisitRecords(raw = {}) {
  const summary = raw.summary || {}
  const trend = raw.trend || {}
  const points = normalizeTrendPoints(trend.points)
  return {
    metrics: [
      { label: '累计访问次数', value: toDisplayText(summary.totalVisitCount) },
      { label: '今日访问', value: toDisplayText(summary.todayVisitCount) },
      { label: '查询档期', value: toDisplayText(summary.scheduleQueryCount) }
    ],
    trend: {
      changeText: trend.changeText || '暂无趋势',
      points
    },
    records: Array.isArray(raw.records) ? raw.records.map(normalizeRecord) : []
  }
}

function markVisitRecordFollowed(visitData = {}, recordId, followedRecord = {}) {
  const records = Array.isArray(visitData.records) ? visitData.records : []
  const normalizedRecordId = String(recordId)
  return Object.assign({}, visitData, {
    records: records.map((record) => {
      if (String(record.id) !== normalizedRecordId && String(record.recordId) !== normalizedRecordId) {
        return record
      }
      return normalizeRecord(Object.assign({}, record, {
        followStatus: FOLLOW_STATUS_CONTACTED,
        followStatusText: FOLLOW_STATUS_TEXT_CONTACTED,
        followTone: FOLLOW_TONE_TEAL
      }, followedRecord, {
        id: followedRecord.id || record.id,
        recordId: followedRecord.recordId || record.recordId || record.id,
        events: Array.isArray(followedRecord.events) ? followedRecord.events : record.events
      }))
    })
  })
}

module.exports = {
  appendVisitEventTimeline,
  markVisitRecordFollowed,
  normalizeVisitEventTimeline,
  normalizeVisitRecords
}
