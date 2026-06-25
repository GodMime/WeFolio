function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function toDisplayText(value) {
  return String(toNumber(value))
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

function normalizeRecord(record = {}) {
  const followTone = record.followTone || 'muted'
  return {
    id: record.id || record.visitorCode || record.visitorLabel,
    visitorLabel: record.visitorLabel || '微信访客',
    visitorInitial: extractVisitorInitial(record),
    sourceText: record.sourceText || '来自未知来源',
    summaryText: record.summaryText || '暂无行为摘要',
    followStatusText: record.followStatusText || '未跟进',
    followTone,
    followToneClass: `follow-pill ${followTone}`,
    lastVisitedText: record.lastVisitedText || ''
  }
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

module.exports = {
  normalizeVisitRecords
}
