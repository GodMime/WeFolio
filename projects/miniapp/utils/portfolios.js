const SCHEMA_VERSION = 'standard-personal-v1'
const SORT_ORDER_STEP = 1000

const COMPONENT_TYPES = {
  CAROUSEL: 'CAROUSEL',
  PROFILE: 'PROFILE',
  SCHEDULE_QUERY: 'SCHEDULE_QUERY',
  WORK_GRID: 'WORK_GRID',
  QR_CONTACT: 'QR_CONTACT',
  CONTACT_FORM: 'CONTACT_FORM',
  TEXT_SECTION: 'TEXT_SECTION'
}

const COMPONENT_NAMES = {
  CAROUSEL: '轮播图',
  PROFILE: '个人资料',
  SCHEDULE_QUERY: '档期查询',
  WORK_GRID: '双列作品列表',
  QR_CONTACT: '二维码联系',
  CONTACT_FORM: '预留联系信息',
  TEXT_SECTION: '文字说明'
}

function trimText(value) {
  return String(value || '').trim()
}

function toNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function normalizeWorkIds(workIds) {
  if (!Array.isArray(workIds)) {
    return []
  }
  const seen = new Set()
  return workIds.reduce((result, item) => {
    const id = toNumber(item)
    if (id > 0 && !seen.has(id)) {
      seen.add(id)
      result.push(id)
    }
    return result
  }, [])
}

function createComponent(componentType, options = {}) {
  const config = Object.assign({}, options.config || {})
  if (Array.isArray(config.workIds)) {
    config.workIds = normalizeWorkIds(config.workIds)
  }
  return {
    componentKey: trimText(options.componentKey) || `c_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
    componentType,
    name: COMPONENT_NAMES[componentType] || '组件',
    sortOrder: toNumber(options.sortOrder, SORT_ORDER_STEP),
    enabled: options.enabled !== false,
    config
  }
}

function normalizeShare(raw = {}) {
  return {
    title: trimText(raw.title),
    intro: trimText(raw.intro),
    coverUrl: trimText(raw.coverUrl),
    avatarUrl: trimText(raw.avatarUrl)
  }
}

function normalizeComponent(raw = {}, index = 0) {
  const component = createComponent(trimText(raw.componentType) || COMPONENT_TYPES.TEXT_SECTION, raw)
  component.sortOrder = toNumber(raw.sortOrder, (index + 1) * SORT_ORDER_STEP)
  component.enabled = raw.enabled !== false
  component.config = Object.assign({}, raw.config || {})
  if (Array.isArray(component.config.workIds)) {
    component.config.workIds = normalizeWorkIds(component.config.workIds)
  }
  return component
}

function normalizePortfolioConfig(raw = {}) {
  const components = Array.isArray(raw.components)
    ? raw.components
        .filter((item) => item && item.enabled !== false)
        .map(normalizeComponent)
        .sort((left, right) => {
          const orderDiff = toNumber(left.sortOrder) - toNumber(right.sortOrder)
          return orderDiff || trimText(left.componentKey).localeCompare(trimText(right.componentKey))
        })
        .map((item, index) => Object.assign({}, item, { sortOrder: (index + 1) * SORT_ORDER_STEP }))
    : []

  return {
    schemaVersion: trimText(raw.schemaVersion) || SCHEMA_VERSION,
    share: normalizeShare(raw.share || {}),
    components
  }
}

function resolveComponentType(componentType) {
  const normalized = trimText(componentType)
  return COMPONENT_NAMES[normalized] ? normalized : COMPONENT_TYPES.TEXT_SECTION
}

function findSelectedWorks(component = {}, works = []) {
  const ids = normalizeWorkIds(component.config && component.config.workIds)
  const workMap = works.reduce((result, work) => {
    result[toNumber(work.id)] = work
    return result
  }, {})
  return ids.map((id) => workMap[id]).filter(Boolean)
}

function validateCarouselComponent(component = {}, works = []) {
  const ids = normalizeWorkIds(component.config && component.config.workIds)
  if (ids.length === 0) {
    return { valid: false, message: '请选择轮播作品' }
  }
  const selected = findSelectedWorks(component, works)
  if (selected.length !== ids.length) {
    return { valid: false, message: '请选择有效作品' }
  }
  if (selected.some((work) => trimText(work.mediaType) !== 'IMAGE')) {
    return { valid: false, message: '轮播图只能选择图片作品' }
  }
  return { valid: true, message: '' }
}

function validateWorkGridComponent(component = {}, works = []) {
  const ids = normalizeWorkIds(component.config && component.config.workIds)
  if (ids.length === 0) {
    return { valid: false, message: '请选择展示作品' }
  }
  const selected = findSelectedWorks(component, works)
  if (selected.length !== ids.length) {
    return { valid: false, message: '请选择有效作品' }
  }
  return { valid: true, message: '' }
}

function addComponent(config, componentType) {
  const normalized = normalizePortfolioConfig(config)
  const components = normalized.components.slice()
  const nextComponent = createComponent(resolveComponentType(componentType), {
    sortOrder: (components.length + 1) * SORT_ORDER_STEP
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, {
    components: components.concat(nextComponent)
  }))
}

function removeComponent(config, componentKey) {
  return normalizePortfolioConfig(Object.assign({}, config, {
    components: (config.components || []).filter((item) => item.componentKey !== componentKey)
  }))
}

function updateComponentWorkIds(config, componentKey, workIds) {
  const normalized = normalizePortfolioConfig(config)
  const targetKey = trimText(componentKey)
  const components = normalized.components.map((component) => {
    if (component.componentKey !== targetKey) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, {
        workIds: normalizeWorkIds(workIds)
      })
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, { components }))
}

function reorderComponent(config, fromIndex, toIndex) {
  const components = Array.isArray(config && config.components) ? config.components.slice() : []
  const sourceIndex = toNumber(fromIndex, -1)
  const targetIndex = toNumber(toIndex, -1)
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex >= components.length || targetIndex >= components.length) {
    return normalizePortfolioConfig(config)
  }
  if (sourceIndex === targetIndex) {
    return normalizePortfolioConfig(config)
  }
  const moving = components.splice(sourceIndex, 1)[0]
  components.splice(targetIndex, 0, moving)
  const reordered = components.map((item, index) => Object.assign({}, item, {
    sortOrder: (index + 1) * SORT_ORDER_STEP
  }))
  return normalizePortfolioConfig(Object.assign({}, config, { components: reordered }))
}

function buildDraftPayload(config, clientRevision, idempotencyKey) {
  return {
    config,
    clientRevision,
    idempotencyKey
  }
}

function buildPublishPayload(draftRevision, idempotencyKey) {
  return {
    draftRevision,
    idempotencyKey
  }
}

module.exports = {
  COMPONENT_NAMES,
  COMPONENT_TYPES,
  SCHEMA_VERSION,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  createComponent,
  normalizePortfolioConfig,
  normalizeWorkIds,
  reorderComponent,
  removeComponent,
  updateComponentWorkIds,
  validateCarouselComponent,
  validateWorkGridComponent
}
