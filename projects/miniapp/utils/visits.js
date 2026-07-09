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
const DETAIL_TYPE_SCHEDULE_QUERIES = 'scheduleQueries'
const DETAIL_TYPE_CONTACT_LEADS = 'contactLeads'
const TREND_CHART_WIDTH = 646
const TREND_CHART_HEIGHT = 156
const TREND_CHART_PADDING_TOP = 30
const TREND_CHART_PADDING_BOTTOM = 24

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

function buildMetric(label, value, action = '') {
  const interactive = Boolean(action)
  return {
    label,
    value: toDisplayText(value),
    action,
    interactive,
    className: interactive ? 'metric interactive' : 'metric'
  }
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

function formatChartCoordinate(value) {
  const rounded = Math.round(Number(value) * 100) / 100
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2)
}

function buildTrendChartSvg(points) {
  const list = Array.isArray(points) ? points : []
  const values = list.map((item) => toNumber(item.value))
  const maxValue = Math.max(...values, 0)
  const chartHeight = TREND_CHART_HEIGHT - TREND_CHART_PADDING_TOP - TREND_CHART_PADDING_BOTTOM
  const linePoints = values.map((value, index) => {
    const x = list.length > 0 ? ((index + 0.5) / list.length) * TREND_CHART_WIDTH : TREND_CHART_WIDTH / 2
    const y = maxValue > 0
      ? TREND_CHART_PADDING_TOP + chartHeight - (value / maxValue) * chartHeight
      : TREND_CHART_PADDING_TOP + chartHeight * 0.68
    return { x, y, value }
  })
  const baselineY = TREND_CHART_HEIGHT - TREND_CHART_PADDING_BOTTOM
  const firstX = linePoints.length ? linePoints[0].x : 0
  const lastX = linePoints.length ? linePoints[linePoints.length - 1].x : TREND_CHART_WIDTH
  const gridLines = [0, 1, 2].map((index) => {
    const y = TREND_CHART_PADDING_TOP + (chartHeight / 2) * index
    return `<path d="M${formatChartCoordinate(firstX)} ${formatChartCoordinate(y)}H${formatChartCoordinate(lastX)}" stroke="#edf2f6" stroke-width="1"/>`
  }).join('')
  const linePath = linePoints.map((point, index) => `${index === 0 ? 'M' : 'L'}${formatChartCoordinate(point.x)} ${formatChartCoordinate(point.y)}`).join('')
  const areaPath = linePoints.length
    ? `${linePath}L${formatChartCoordinate(lastX)} ${formatChartCoordinate(baselineY)}L${formatChartCoordinate(firstX)} ${formatChartCoordinate(baselineY)}Z`
    : ''
  const area = areaPath ? `<path d="${areaPath}" fill="#0f766e" opacity="0.12"/>` : ''
  const line = linePath ? `<path d="${linePath}" fill="none" stroke="#0f766e" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>` : ''
  const dots = linePoints.map((point) => {
    const isHot = maxValue > 0 && point.value === maxValue
    const fill = isHot ? '#c9963f' : '#ffffff'
    const stroke = isHot ? '#c9963f' : '#0f766e'
    const radius = isHot ? 5 : 4
    return `<circle cx="${formatChartCoordinate(point.x)}" cy="${formatChartCoordinate(point.y)}" r="${radius}" fill="${fill}" stroke="${stroke}" stroke-width="2"/>`
  }).join('')
  const labels = linePoints.map((point) => {
    const labelY = Math.max(14, point.y - 9)
    return `<text x="${formatChartCoordinate(point.x)}" y="${formatChartCoordinate(labelY)}" fill="#536b82" font-size="11" font-family="sans-serif" text-anchor="middle">${point.value}</text>`
  }).join('')
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${TREND_CHART_WIDTH}" height="${TREND_CHART_HEIGHT}" viewBox="0 0 ${TREND_CHART_WIDTH} ${TREND_CHART_HEIGHT}" preserveAspectRatio="none">${gridLines}${area}${line}${dots}${labels}</svg>`
  return `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`
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

function normalizeScheduleQueryItem(item = {}) {
  const available = Boolean(item.available)
  return {
    id: item.id || '',
    visitorLabel: item.visitorLabel || '微信访客',
    visitorAvatarUrl: item.visitorAvatarUrl || '',
    visitorInitial: extractVisitorInitial(item),
    portfolioTitle: item.portfolioTitle || '',
    queriedDateText: item.queriedDateText || '',
    slotText: item.slotText || '',
    resultStatus: item.resultStatus || '',
    resultStatusText: item.resultStatusText || item.resultMessage || '',
    available,
    resultMessage: item.resultMessage || '',
    resultToneClass: `detail-status ${available ? 'teal' : 'rose'}`,
    sourceText: item.sourceText || '来自未知来源',
    createdTimeText: item.createdTimeText || ''
  }
}

function normalizeContactLeadItem(item = {}) {
  const followStatus = item.followStatus || FOLLOW_STATUS_NOT_FOLLOWED_UP
  const phone = item.phone || ''
  const phoneLast4 = item.phoneLast4 || ''
  const wechat = item.wechat || ''
  const wechatMaskHint = item.wechatMaskHint || ''
  return {
    id: item.id || '',
    contactName: item.contactName || '未留姓名',
    phone,
    phoneLast4,
    phoneText: phone || '未留手机',
    phoneCopyText: phone,
    phoneCanCopy: Boolean(phone),
    phoneLast4Text: phoneLast4 ? `手机尾号 ${phoneLast4}` : '未留手机',
    wechat,
    wechatMaskHint,
    wechatText: wechat || '未留微信',
    wechatCopyText: wechat,
    wechatCanCopy: Boolean(wechat),
    desiredSchedule: item.desiredSchedule || '',
    needs: item.needs || '',
    portfolioTitle: item.portfolioTitle || '',
    sourceText: item.sourceText || '来自未知来源',
    followStatus,
    followStatusText: buildFollowStatusText(followStatus, item.followStatusText),
    followToneClass: `follow-pill ${buildFollowTone(followStatus, item.followTone)}`,
    canMarkFollowed: followStatus === FOLLOW_STATUS_NOT_FOLLOWED_UP,
    submittedTimeText: item.submittedTimeText || ''
  }
}

function normalizeVisitDetailPage(type, raw = {}, fallbackPage = {}) {
  const pageNo = toPositiveNumber(raw.pageNo, toPositiveNumber(fallbackPage.pageNo, 1))
  const pageSize = toPositiveNumber(raw.pageSize, toPositiveNumber(fallbackPage.pageSize, 20))
  const hasMore = Boolean(raw.hasMore)
  const normalizer = type === DETAIL_TYPE_CONTACT_LEADS ? normalizeContactLeadItem : normalizeScheduleQueryItem
  return {
    type,
    title: type === DETAIL_TYPE_CONTACT_LEADS ? '预留信息' : '查询档期',
    pageNo,
    pageSize,
    hasMore,
    nextPage: hasMore ? pageNo + 1 : null,
    items: Array.isArray(raw.items) ? raw.items.map(normalizer) : []
  }
}

function appendVisitDetailPage(current = {}, nextPage = {}) {
  const pageNo = toPositiveNumber(nextPage.pageNo, toPositiveNumber(current.pageNo, 1))
  const pageSize = toPositiveNumber(nextPage.pageSize, toPositiveNumber(current.pageSize, 20))
  const hasMore = Boolean(nextPage.hasMore)
  return Object.assign({}, current, nextPage, {
    type: nextPage.type || current.type || DETAIL_TYPE_SCHEDULE_QUERIES,
    title: nextPage.title || current.title || '查询档期',
    items: []
      .concat(Array.isArray(current.items) ? current.items : [])
      .concat(Array.isArray(nextPage.items) ? nextPage.items : []),
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
      buildMetric('累计访问次数', summary.totalVisitCount),
      buildMetric('今日访问', summary.todayVisitCount),
      buildMetric('查询档期', summary.scheduleQueryCount, DETAIL_TYPE_SCHEDULE_QUERIES),
      buildMetric('预留信息', summary.contactLeadCount, DETAIL_TYPE_CONTACT_LEADS)
    ],
    trend: {
      changeText: trend.changeText || '暂无趋势',
      points,
      chartSvg: buildTrendChartSvg(points)
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

function markContactLeadFollowed(detailPage = {}, leadId, followedLead = {}) {
  const items = Array.isArray(detailPage.items) ? detailPage.items : []
  const normalizedLeadId = String(leadId)
  return Object.assign({}, detailPage, {
    items: items.map((item) => {
      if (String(item.id) !== normalizedLeadId) {
        return item
      }
      return normalizeContactLeadItem(Object.assign({}, item, {
        followStatus: FOLLOW_STATUS_CONTACTED,
        followStatusText: FOLLOW_STATUS_TEXT_CONTACTED,
        followTone: FOLLOW_TONE_TEAL
      }, followedLead, {
        id: followedLead.id || item.id
      }))
    })
  })
}

module.exports = {
  appendVisitDetailPage,
  appendVisitEventTimeline,
  markContactLeadFollowed,
  markVisitRecordFollowed,
  normalizeVisitDetailPage,
  normalizeVisitEventTimeline,
  normalizeVisitRecords
}
