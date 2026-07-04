const PROFILE_VISIBLE_FIELD_DEFAULTS = {
  avatar: true,
  displayName: true,
  profession: true,
  city: true,
  bio: true,
  tags: true,
  wechatQr: false
}

function trimText(value) {
  return String(value || '').trim()
}

function toNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function normalizeShare(raw = {}) {
  return {
    title: trimText(raw.title),
    coverUrl: trimText(raw.coverUrl),
    avatarUrl: trimText(raw.avatarUrl)
  }
}

function normalizeRenderWork(raw = {}) {
  return {
    workId: toNumber(raw.workId || raw.id),
    title: trimText(raw.title) || '未命名作品',
    mediaType: trimText(raw.mediaType) || 'IMAGE',
    coverUrl: trimText(raw.coverUrl),
    mediaUrl: trimText(raw.mediaUrl),
    durationMs: toNumber(raw.durationMs),
    description: trimText(raw.description)
  }
}

function normalizeDisplayGroups(rawGroups = []) {
  const groups = Array.isArray(rawGroups) ? rawGroups : []
  return groups.map((group, index) => ({
    groupKey: trimText(group.groupKey) || `g_${index + 1}`,
    name: trimText(group.name) || '作品集展示标签',
    sortOrder: toNumber(group.sortOrder, (index + 1) * 1000),
    works: Array.isArray(group.works) ? group.works.map(normalizeRenderWork) : []
  })).sort((left, right) => {
    const orderDiff = left.sortOrder - right.sortOrder
    return orderDiff || left.groupKey.localeCompare(right.groupKey)
  })
}

function normalizeProfileVisibleFields(visibleFields = {}) {
  return Object.keys(PROFILE_VISIBLE_FIELD_DEFAULTS).reduce((result, field) => {
    result[field] = Object.prototype.hasOwnProperty.call(visibleFields, field)
      ? Boolean(visibleFields[field])
      : PROFILE_VISIBLE_FIELD_DEFAULTS[field]
    return result
  }, {})
}

function normalizeProfile(raw = {}) {
  const tags = Array.isArray(raw.tags) ? raw.tags : []
  const visibleFields = normalizeProfileVisibleFields(raw.visibleFields)
  return {
    avatarUrl: visibleFields.avatar ? trimText(raw.avatarUrl) : '',
    displayName: visibleFields.displayName ? trimText(raw.displayName) : '',
    profession: visibleFields.profession ? trimText(raw.profession) : '',
    city: visibleFields.city ? trimText(raw.city) : '',
    bio: visibleFields.bio ? trimText(raw.bio) : '',
    wechatQrUrl: visibleFields.wechatQr ? trimText(raw.wechatQrUrl) : '',
    visibleFields,
    tags: visibleFields.tags ? tags.map((tag) => ({
      name: trimText(tag.name),
      color: trimText(tag.color)
    })).filter((tag) => tag.name) : []
  }
}

function normalizeQrContact(raw = {}) {
  return {
    title: trimText(raw.title),
    description: trimText(raw.description),
    qrUrlSource: trimText(raw.qrUrlSource),
    qrUrl: trimText(raw.qrUrl)
  }
}

function normalizeRenderComponent(raw = {}) {
  const componentType = trimText(raw.componentType)
  const groups = normalizeDisplayGroups(raw.groups)
  const activeGroup = groups[0] || { groupKey: '', name: '', sortOrder: 0, works: [] }
  const qrContact = normalizeQrContact(raw.qrContact || {})
  const config = raw.config || {}
  const profileSource = raw.profile || Object.assign({}, config.profile || {}, {
    visibleFields: config.visibleFields || {}
  })
  const component = {
    componentKey: trimText(raw.componentKey),
    componentType,
    name: trimText(raw.name),
    sortOrder: toNumber(raw.sortOrder),
    title: trimText(raw.title),
    config: Object.assign({}, config),
    works: Array.isArray(raw.works) ? raw.works.map(normalizeRenderWork) : [],
    groups,
    displayTags: groups.map((group, index) => ({
      groupKey: group.groupKey,
      name: group.name,
      active: index === 0
    })),
    activeGroupKey: activeGroup.groupKey,
    activeGroup,
    layout: componentType === 'WORK_LIST' ? 'single' : componentType === 'WORK_GRID' ? 'grid' : '',
    profile: normalizeProfile(profileSource),
    scheduleQuery: Object.assign({}, raw.scheduleQuery || {}),
    qrContact,
    previewImageUrl: qrContact.qrUrl,
    contactForm: Object.assign({ fields: [] }, raw.contactForm || {}),
    textSection: Object.assign({ title: '', content: '' }, raw.textSection || {})
  }
  return component
}

function normalizePortfolioRender(raw = {}) {
  const share = normalizeShare(raw.share || {})
  const underMaintenance = Boolean(raw.underMaintenance)
  const components = underMaintenance
    ? []
    : (Array.isArray(raw.components) ? raw.components.map(normalizeRenderComponent) : [])
        .sort((left, right) => {
          const orderDiff = left.sortOrder - right.sortOrder
          return orderDiff || left.componentKey.localeCompare(right.componentKey)
        })
  return {
    shareCode: trimText(raw.shareCode),
    portfolioId: toNumber(raw.portfolioId),
    title: trimText(raw.title) || share.title || '个人作品集',
    share,
    preview: Boolean(raw.preview),
    underMaintenance,
    maintenanceText: {
      primary: trimText(raw.maintenanceText && raw.maintenanceText.primary) || 'UNDER MAINTENANCE',
      secondary: trimText(raw.maintenanceText && raw.maintenanceText.secondary) || '维护中'
    },
    visitRecordId: raw.visitRecordId || null,
    components
  }
}

function normalizeComponents(components = []) {
  return Array.isArray(components)
    ? components
        .filter((item) => item && item.enabled !== false)
        .map((item) => normalizeRenderComponent({
          componentKey: item.componentKey,
          componentType: item.componentType,
          sortOrder: item.sortOrder,
          name: item.name,
          title: item.config && item.config.title,
          config: item.config || {},
          works: item.config && Array.isArray(item.config.works) ? item.config.works : [],
          groups: item.config && Array.isArray(item.config.groups) ? item.config.groups : []
        }))
        .sort((left, right) => left.sortOrder - right.sortOrder)
    : []
}

function normalizeVisitorPortfolio(raw = {}) {
  if (raw.renderData) {
    const render = normalizePortfolioRender(Object.assign({}, raw.renderData, {
      underMaintenance: Boolean(raw.underMaintenance || raw.renderData.underMaintenance)
    }))
    return Object.assign({}, render, {
      config: raw.config || {},
      publishedRevision: toNumber(raw.publishedRevision)
    })
  }
  const config = raw.config || {}
  const share = normalizeShare(config.share || {})
  const underMaintenance = Boolean(raw.underMaintenance)
  return {
    shareCode: trimText(raw.shareCode),
    portfolioId: toNumber(raw.portfolioId),
    publishedRevision: toNumber(raw.publishedRevision),
    title: trimText(raw.title) || share.title || '个人作品集',
    share,
    preview: false,
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
  normalizeDisplayGroups,
  normalizePortfolioRender,
  normalizeRenderComponent,
  normalizeRenderWork,
  normalizeVisitorPortfolio,
  normalizeVisitorSchedule
}
