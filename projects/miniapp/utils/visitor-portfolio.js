function trimText(value) {
  return String(value || '').trim()
}

function toNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function normalizeComponents(components = []) {
  return Array.isArray(components)
    ? components
        .filter((item) => item && item.enabled !== false)
        .map((item) => ({
          componentKey: trimText(item.componentKey),
          componentType: trimText(item.componentType),
          sortOrder: toNumber(item.sortOrder),
          enabled: item.enabled !== false,
          config: Object.assign({}, item.config || {})
        }))
        .sort((left, right) => left.sortOrder - right.sortOrder)
    : []
}

function normalizeVisitorPortfolio(raw = {}) {
  const config = raw.config || {}
  const share = config.share || {}
  const underMaintenance = Boolean(raw.underMaintenance)
  return {
    shareCode: trimText(raw.shareCode),
    portfolioId: toNumber(raw.portfolioId),
    publishedRevision: toNumber(raw.publishedRevision),
    title: trimText(raw.title) || trimText(share.title) || '个人作品集',
    underMaintenance,
    maintenanceText: {
      primary: trimText(raw.maintenanceText && raw.maintenanceText.primary) || 'UNDER MAINTENANCE',
      secondary: trimText(raw.maintenanceText && raw.maintenanceText.secondary) || '维护中'
    },
    visitRecordId: raw.visitRecordId || null,
    config,
    components: underMaintenance ? [] : normalizeComponents(config.components)
  }
}

function buildVisitorEventPayload(event = {}, idempotencyKey) {
  const payload = {
    visitorKey: trimText(event.visitorKey),
    eventType: trimText(event.eventType),
    idempotencyKey
  }
  if (event.workId !== undefined && event.workId !== null) {
    payload.workId = toNumber(event.workId)
  }
  if (event.durationSeconds !== undefined && event.durationSeconds !== null) {
    payload.durationSeconds = toNumber(event.durationSeconds)
  }
  if (event.queriedDate) {
    payload.queriedDate = trimText(event.queriedDate)
  }
  return payload
}

function normalizeVisitorSchedule(raw = {}) {
  const schedules = Array.isArray(raw.schedules) ? raw.schedules : []
  return {
    schedules: schedules.map((item) => ({
      date: trimText(item.date),
      slotName: trimText(item.slotName),
      startTime: trimText(item.startTime),
      endTime: trimText(item.endTime),
      color: trimText(item.color),
      status: trimText(item.status),
      statusText: trimText(item.statusText),
      statusTone: trimText(item.statusTone),
      contactName: '',
      contactPhone: '',
      note: ''
    }))
  }
}

module.exports = {
  buildVisitorEventPayload,
  normalizeVisitorPortfolio,
  normalizeVisitorSchedule
}
