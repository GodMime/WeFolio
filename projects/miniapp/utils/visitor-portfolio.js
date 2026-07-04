const {
  formatLunarDayMeta,
  toLunarDate
} = require('./lunar')

const PROFILE_VISIBLE_FIELD_DEFAULTS = {
  avatar: true,
  displayName: true,
  profession: true,
  city: true,
  bio: true,
  tags: true,
  wechatQr: false
}
const DEFAULT_CAROUSEL_INTERVAL_MS = 3000
const ALL_DISPLAY_GROUP_KEY = '__all'
const ALL_DISPLAY_GROUP_NAME = '全部'
const DEFAULT_ALL_GROUP_KEY = 'g_all'
const DEFAULT_ALL_GROUP_NAME = '全部作品'
const MEDIA_TYPE_VIDEO = 'VIDEO'
const DEFAULT_SCHEDULE_DISPLAY_MODE = 'MODAL_CALENDAR'
const VALID_SCHEDULE_DISPLAY_MODES = ['MODAL_CALENDAR', 'INLINE_CALENDAR']
const DEFAULT_CONTACT_FORM_DISPLAY_MODE = 'MODAL_FORM'
const VALID_CONTACT_FORM_DISPLAY_MODES = ['MODAL_FORM', 'INLINE_FORM']
const DEFAULT_TEXT_SECTION_ALIGNMENT = 'LEFT'
const VALID_TEXT_SECTION_ALIGNMENTS = ['LEFT', 'CENTER', 'RIGHT']
const TEXT_SECTION_ALIGNMENT_CLASS_MAP = {
  LEFT: 'align-left',
  CENTER: 'align-center',
  RIGHT: 'align-right'
}
const DEFAULT_DIVIDER_COLOR = 'GRAY'
const DEFAULT_DIVIDER_HEIGHT_PX = 16
const VALID_DIVIDER_COLORS = ['BLACK', 'WHITE', 'GRAY', 'TRANSPARENT']
const DIVIDER_COLOR_VALUE_MAP = {
  BLACK: '#000000',
  WHITE: '#ffffff',
  GRAY: '#eef1f4',
  TRANSPARENT: 'transparent'
}
const SCHEDULE_DAY_META_FIELDS = ['holidayText', 'festivalText', 'noteText', 'lunarText', 'metaText']

function trimText(value) {
  return String(value || '').trim()
}

function toNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function toPositiveNumber(value, fallback) {
  const numberValue = toNumber(value, fallback)
  return numberValue > 0 ? Math.round(numberValue) : fallback
}

function normalizeShare(raw = {}) {
  return {
    title: trimText(raw.title),
    coverUrl: trimText(raw.coverUrl),
    avatarUrl: trimText(raw.avatarUrl)
  }
}

function normalizeRenderWork(raw = {}) {
  const mediaType = trimText(raw.mediaType) || 'IMAGE'
  const coverUrl = trimText(raw.coverUrl)
  const mediaUrl = trimText(raw.mediaUrl)
  return {
    workId: toNumber(raw.workId || raw.id),
    title: trimText(raw.title) || '未命名作品',
    mediaType,
    isVideo: mediaType === MEDIA_TYPE_VIDEO,
    coverUrl,
    mediaUrl,
    thumbnailUrl: coverUrl || mediaUrl,
    previewUrl: mediaUrl || coverUrl,
    durationMs: toNumber(raw.durationMs),
    description: trimText(raw.description)
  }
}

function normalizeDisplayGroups(rawGroups = []) {
  const groups = Array.isArray(rawGroups) ? rawGroups : []
  const sortedGroups = groups.map((group, index) => ({
    groupKey: trimText(group.groupKey) || `g_${index + 1}`,
    name: trimText(group.name) || DEFAULT_ALL_GROUP_NAME,
    sortOrder: toNumber(group.sortOrder, (index + 1) * 1000),
    works: Array.isArray(group.works) ? group.works.map(normalizeRenderWork) : []
  })).sort((left, right) => {
    const orderDiff = left.sortOrder - right.sortOrder
    return orderDiff || left.groupKey.localeCompare(right.groupKey)
  })
  if (!sortedGroups.length) {
    return []
  }
  const allGroup = {
    groupKey: ALL_DISPLAY_GROUP_KEY,
    name: ALL_DISPLAY_GROUP_NAME,
    sortOrder: 0,
    works: buildAllDisplayWorks(sortedGroups)
  }
  return isDefaultAllOnlyGroup(sortedGroups)
    ? [allGroup]
    : [allGroup].concat(sortedGroups)
}

function buildWorkIdentity(work = {}, fallbackIndex = 0) {
  if (work.workId > 0) {
    return `id:${work.workId}`
  }
  return `media:${work.mediaUrl || work.coverUrl || work.title || fallbackIndex}`
}

function buildAllDisplayWorks(groups = []) {
  const seen = new Set()
  return groups.reduce((result, group) => {
    const works = Array.isArray(group.works) ? group.works : []
    works.forEach((work, index) => {
      const identity = buildWorkIdentity(work, index)
      if (seen.has(identity)) {
        return
      }
      seen.add(identity)
      result.push(work)
    })
    return result
  }, [])
}

function isDefaultAllOnlyGroup(groups = []) {
  if (groups.length !== 1) {
    return false
  }
  const group = groups[0]
  return group.groupKey === DEFAULT_ALL_GROUP_KEY || group.name === DEFAULT_ALL_GROUP_NAME
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
    qrUrlSource: trimText(raw.qrUrlSource),
    qrUrl: trimText(raw.qrUrl)
  }
}

function normalizeScheduleQuery(raw = {}) {
  const displayMode = trimText(raw.displayMode)
  return Object.assign({}, raw || {}, {
    displayMode: VALID_SCHEDULE_DISPLAY_MODES.includes(displayMode) ? displayMode : DEFAULT_SCHEDULE_DISPLAY_MODE
  })
}

function normalizeContactForm(raw = {}) {
  const displayMode = trimText(raw.displayMode)
  const fields = Array.isArray(raw.fields) ? raw.fields.map(trimText).filter(Boolean) : []
  return Object.assign({}, raw || {}, {
    title: trimText(raw.title),
    description: trimText(raw.description),
    displayMode: VALID_CONTACT_FORM_DISPLAY_MODES.includes(displayMode) ? displayMode : DEFAULT_CONTACT_FORM_DISPLAY_MODE,
    fields
  })
}

function normalizeTextSection(raw = {}) {
  const alignment = trimText(raw.alignment)
  const normalizedAlignment = VALID_TEXT_SECTION_ALIGNMENTS.includes(alignment)
    ? alignment
    : DEFAULT_TEXT_SECTION_ALIGNMENT
  return Object.assign({}, raw || {}, {
    title: trimText(raw.title),
    content: trimText(raw.content),
    alignment: normalizedAlignment,
    alignmentClass: TEXT_SECTION_ALIGNMENT_CLASS_MAP[normalizedAlignment]
  })
}

function normalizeDivider(raw = {}) {
  const color = trimText(raw.color)
  const normalizedColor = VALID_DIVIDER_COLORS.includes(color) ? color : DEFAULT_DIVIDER_COLOR
  const heightPx = toPositiveNumber(raw.heightPx, DEFAULT_DIVIDER_HEIGHT_PX)
  const colorValue = DIVIDER_COLOR_VALUE_MAP[normalizedColor]
  return Object.assign({}, raw || {}, {
    color: normalizedColor,
    heightPx,
    colorValue,
    style: `height: ${heightPx}px; background-color: ${colorValue};`
  })
}

function normalizeCarouselIntervalMs(raw = {}, config = {}) {
  return toPositiveNumber(
    raw.carouselIntervalMs || config.carouselIntervalMs || config.intervalMs,
    DEFAULT_CAROUSEL_INTERVAL_MS
  )
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
    carouselIntervalMs: normalizeCarouselIntervalMs(raw, config),
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
    scheduleQuery: normalizeScheduleQuery(raw.scheduleQuery || config),
    qrContact,
    previewImageUrl: qrContact.qrUrl,
    contactForm: normalizeContactForm(raw.contactForm || config),
    textSection: normalizeTextSection(raw.textSection || config),
    divider: normalizeDivider(raw.divider || config)
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
  if (event.mediaType) {
    payload.mediaType = trimText(event.mediaType)
  }
  if (event.durationSeconds !== undefined && event.durationSeconds !== null) {
    payload.durationSeconds = toNumber(event.durationSeconds)
  }
  if (event.queriedDate) {
    payload.queriedDate = trimText(event.queriedDate)
  }
  return payload
}

function switchDisplayGroup(portfolio, componentKey, groupKey) {
  const sourcePortfolio = portfolio || {}
  const targetGroupKey = trimText(groupKey)
  const components = (Array.isArray(sourcePortfolio.components) ? sourcePortfolio.components : []).map((component) => {
    if (!component || component.componentKey !== componentKey) {
      return component
    }
    const groups = Array.isArray(component.groups) ? component.groups : []
    const activeGroup = groups.find((group) => group.groupKey === targetGroupKey)
      || component.activeGroup
      || groups[0]
      || { groupKey: '', name: '', sortOrder: 0, works: [] }
    const displayTags = Array.isArray(component.displayTags) && component.displayTags.length
      ? component.displayTags
      : groups.map((group) => ({ groupKey: group.groupKey, name: group.name, active: false }))
    return Object.assign({}, component, {
      activeGroupKey: activeGroup.groupKey,
      activeGroup,
      displayTags: displayTags.map((tag) => Object.assign({}, tag, {
        active: tag.groupKey === activeGroup.groupKey
      }))
    })
  })
  return Object.assign({}, sourcePortfolio, { components })
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

function buildScheduleTimeRangeText(startTime = '', endTime = '') {
  const start = trimText(startTime)
  const end = trimText(endTime)
  return start && end ? `${start}-${end}` : '未设置时间'
}

function normalizeScheduleOptionSlot(raw = {}) {
  const startTime = trimText(raw.startTime)
  const endTime = trimText(raw.endTime)
  return {
    id: toNumber(raw.id),
    name: trimText(raw.name) || '未命名档位',
    startTime,
    endTime,
    timeRangeText: buildScheduleTimeRangeText(startTime, endTime),
    color: trimText(raw.color)
  }
}

function normalizeScheduleOptionDay(raw = {}) {
  const count = toNumber(raw.count)
  const classes = ['schedule-calendar-day']
  if (!raw.currentMonth) {
    classes.push('muted')
  }
  if (count > 0) {
    classes.push('filled')
  }
  return {
    date: trimText(raw.date),
    dayNumber: toNumber(raw.dayNumber),
    currentMonth: Boolean(raw.currentMonth),
    metaText: buildScheduleDayMetaText(raw),
    colors: Array.isArray(raw.colors) ? raw.colors.map(trimText).filter(Boolean) : [],
    count,
    dayClass: classes.join(' ')
  }
}

function buildScheduleDayMetaText(raw = {}) {
  const field = SCHEDULE_DAY_META_FIELDS.find((item) => trimText(raw[item]))
  return field ? trimText(raw[field]) : formatLunarDayMeta(toLunarDate(raw.date))
}

function normalizeScheduleOptionItem(raw = {}) {
  const startTime = trimText(raw.startTime)
  const endTime = trimText(raw.endTime)
  return {
    date: trimText(raw.date),
    slotDefinitionId: toNumber(raw.slotDefinitionId),
    slotName: trimText(raw.slotName),
    startTime,
    endTime,
    timeRangeText: buildScheduleTimeRangeText(startTime, endTime),
    color: trimText(raw.color),
    status: trimText(raw.status),
    statusText: trimText(raw.statusText),
    statusTone: trimText(raw.statusTone),
    contactName: '',
    contactPhone: '',
    note: ''
  }
}

function normalizeVisitorScheduleOptions(raw = {}) {
  return {
    yearMonth: trimText(raw.yearMonth),
    slotDefinitions: Array.isArray(raw.slotDefinitions)
      ? raw.slotDefinitions.map(normalizeScheduleOptionSlot)
      : [],
    days: Array.isArray(raw.days) ? raw.days.map(normalizeScheduleOptionDay) : [],
    schedules: Array.isArray(raw.schedules) ? raw.schedules.map(normalizeScheduleOptionItem) : []
  }
}

function normalizeVisitorScheduleQueryResult(raw = {}) {
  const startTime = trimText(raw.startTime)
  const endTime = trimText(raw.endTime)
  return {
    queriedDate: trimText(raw.queriedDate),
    slotDefinitionId: toNumber(raw.slotDefinitionId),
    slotName: trimText(raw.slotName),
    startTime,
    endTime,
    timeRangeText: buildScheduleTimeRangeText(startTime, endTime),
    color: trimText(raw.color),
    status: trimText(raw.status),
    statusText: trimText(raw.statusText),
    available: Boolean(raw.available),
    message: trimText(raw.message),
    contactName: '',
    contactPhone: '',
    note: ''
  }
}

module.exports = {
  buildVisitorEventPayload,
  normalizeDisplayGroups,
  normalizePortfolioRender,
  normalizeRenderComponent,
  normalizeRenderWork,
  normalizeVisitorPortfolio,
  normalizeVisitorSchedule,
  normalizeVisitorScheduleOptions,
  normalizeVisitorScheduleQueryResult,
  switchDisplayGroup
}
