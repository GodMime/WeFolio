const SCHEMA_VERSION = 'standard-personal-v1'
const SORT_ORDER_STEP = 1000
const DISPLAY_GROUP_NAME_MAX_LENGTH = 20
const PROFILE_TAG_DEFAULT_COLOR = '#0f766e'
const PROFILE_VISIBLE_FIELD_DEFAULTS = {
  avatar: true,
  displayName: true,
  profession: true,
  city: true,
  bio: true,
  tags: true,
  wechatQr: false
}

const COMPONENT_TYPES = {
  CAROUSEL: 'CAROUSEL',
  PROFILE: 'PROFILE',
  SCHEDULE_QUERY: 'SCHEDULE_QUERY',
  WORK_GRID: 'WORK_GRID',
  WORK_LIST: 'WORK_LIST',
  QR_CONTACT: 'QR_CONTACT',
  CONTACT_FORM: 'CONTACT_FORM',
  TEXT_SECTION: 'TEXT_SECTION'
}

const COMPONENT_NAMES = {
  CAROUSEL: '轮播图',
  PROFILE: '个人资料',
  SCHEDULE_QUERY: '档期查询',
  WORK_GRID: '双列作品列表',
  WORK_LIST: '单列作品列表',
  QR_CONTACT: '二维码联系',
  CONTACT_FORM: '预留联系信息',
  TEXT_SECTION: '文字说明'
}

const SCHEDULE_QUERY_DISPLAY_MODES = {
  MODAL_CALENDAR: 'MODAL_CALENDAR',
  INLINE_CALENDAR: 'INLINE_CALENDAR'
}

const SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS = [
  { value: SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR, label: '弹层显示月历' },
  { value: SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR, label: '直接显示月历' }
]

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

function normalizeDisplayGroup(raw = {}, index = 0) {
  return {
    groupKey: trimText(raw.groupKey) || `g_${index + 1}`,
    name: trimText(raw.name) || '全部作品',
    sortOrder: toNumber(raw.sortOrder, (index + 1) * SORT_ORDER_STEP),
    workIds: normalizeWorkIds(raw.workIds)
  }
}

function normalizeDisplayGroups(groups, fallbackWorkIds = []) {
  const normalizedFallbackWorkIds = normalizeWorkIds(fallbackWorkIds)
  const source = Array.isArray(groups) && groups.length > 0
    ? groups
    : normalizedFallbackWorkIds.length > 0
      ? [{ groupKey: 'g_all', name: '全部作品', sortOrder: SORT_ORDER_STEP, workIds: normalizedFallbackWorkIds }]
      : []
  return source
    .map(normalizeDisplayGroup)
    .filter((group) => group.name)
    .sort((left, right) => {
      const orderDiff = left.sortOrder - right.sortOrder
      return orderDiff || left.groupKey.localeCompare(right.groupKey)
    })
    .map((group, index) => Object.assign({}, group, {
      sortOrder: (index + 1) * SORT_ORDER_STEP
    }))
}

function normalizeProfileTags(tags = []) {
  if (!Array.isArray(tags)) {
    return []
  }
  const seen = new Set()
  return tags.reduce((result, tag) => {
    const name = trimText(tag && typeof tag === 'object' ? tag.name || tag.content || tag.text : tag)
    if (!name || seen.has(name)) {
      return result
    }
    seen.add(name)
    result.push({
      name,
      color: trimText(tag && typeof tag === 'object' ? tag.color : '') || PROFILE_TAG_DEFAULT_COLOR
    })
    return result
  }, [])
}

function normalizeProfileVisibleFields(visibleFields = {}) {
  return Object.keys(PROFILE_VISIBLE_FIELD_DEFAULTS).reduce((result, field) => {
    result[field] = Object.prototype.hasOwnProperty.call(visibleFields, field)
      ? Boolean(visibleFields[field])
      : PROFILE_VISIBLE_FIELD_DEFAULTS[field]
    return result
  }, {})
}

function normalizeProfileComponentConfig(raw = {}) {
  const profile = raw.profile || {}
  return {
    profile: {
      avatarUrl: trimText(profile.avatarUrl),
      displayName: trimText(profile.displayName),
      profession: trimText(profile.profession),
      city: trimText(profile.city),
      bio: trimText(profile.bio),
      tags: normalizeProfileTags(profile.tags),
      wechatQrUrl: trimText(profile.wechatQrUrl)
    },
    visibleFields: normalizeProfileVisibleFields(raw.visibleFields)
  }
}

function isWorkListComponent(componentType) {
  return componentType === COMPONENT_TYPES.WORK_GRID || componentType === COMPONENT_TYPES.WORK_LIST
}

function normalizeScheduleQueryDisplayMode(value) {
  const displayMode = trimText(value)
  return SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS.some((item) => item.value === displayMode)
    ? displayMode
    : SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR
}

function normalizeScheduleQueryConfig(raw = {}) {
  return Object.assign({}, raw || {}, {
    displayMode: normalizeScheduleQueryDisplayMode(raw && raw.displayMode)
  })
}

function collectComponentWorkIds(component = {}) {
  const config = component.config || {}
  const groups = normalizeDisplayGroups(config.groups, config.workIds)
  if (groups.length > 0) {
    return normalizeWorkIds(groups.flatMap((group) => group.workIds))
  }
  return normalizeWorkIds(config.workIds)
}

function createComponent(componentType, options = {}) {
  const config = Object.assign({}, options.config || {})
  if (Array.isArray(config.workIds)) {
    config.workIds = normalizeWorkIds(config.workIds)
  }
  if (isWorkListComponent(componentType)) {
    config.groups = normalizeDisplayGroups(config.groups, config.workIds)
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
  if (isWorkListComponent(component.componentType)) {
    component.config.groups = normalizeDisplayGroups(component.config.groups, component.config.workIds)
  }
  if (component.componentType === COMPONENT_TYPES.SCHEDULE_QUERY) {
    component.config = normalizeScheduleQueryConfig(component.config)
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
  const ids = collectComponentWorkIds(component)
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
  const ids = collectComponentWorkIds(component)
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

function updateComponentProfileConfig(config, componentKey, profileConfig = {}) {
  const normalized = normalizePortfolioConfig(config)
  const targetKey = trimText(componentKey)
  const nextProfileConfig = normalizeProfileComponentConfig(profileConfig)
  const components = normalized.components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.PROFILE) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextProfileConfig)
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, { components }))
}

function updateComponentScheduleQueryConfig(config, componentKey, scheduleQueryConfig = {}) {
  const normalized = normalizePortfolioConfig(config)
  const targetKey = trimText(componentKey)
  const nextScheduleQueryConfig = normalizeScheduleQueryConfig(scheduleQueryConfig)
  const components = normalized.components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.SCHEDULE_QUERY) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextScheduleQueryConfig)
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, { components }))
}

function updateComponentDisplayGroups(config, componentKey, updater) {
  const normalized = normalizePortfolioConfig(config)
  const targetKey = trimText(componentKey)
  const components = normalized.components.map((component) => {
    if (component.componentKey !== targetKey || !isWorkListComponent(component.componentType)) {
      return component
    }
    const groups = normalizeDisplayGroups(component.config && component.config.groups)
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, {
        groups: normalizeDisplayGroups(updater(groups))
      })
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, { components }))
}

function validateDisplayGroupName(groups = [], name = '', currentGroupKey = '') {
  const nextName = trimText(name)
  if (!nextName) {
    return { valid: false, message: '展示标签名称不能为空', name: '' }
  }
  if (countText(nextName) > DISPLAY_GROUP_NAME_MAX_LENGTH) {
    return { valid: false, message: `展示标签名称不能超过${DISPLAY_GROUP_NAME_MAX_LENGTH}个字`, name: nextName }
  }
  const targetGroupKey = trimText(currentGroupKey)
  const duplicated = normalizeDisplayGroups(groups).some((group) => {
    return group.groupKey !== targetGroupKey && group.name === nextName
  })
  if (duplicated) {
    return { valid: false, message: '展示标签名称不能重复', name: nextName }
  }
  return { valid: true, message: '', name: nextName }
}

function createDisplayGroupKey(groups = []) {
  const usedKeys = new Set(groups.map((group) => trimText(group.groupKey)).filter(Boolean))
  let index = groups.length + 1
  while (usedKeys.has(`g_${index}`)) {
    index += 1
  }
  return `g_${index}`
}

function addDisplayGroup(config, componentKey, name) {
  return updateComponentDisplayGroups(config, componentKey, (groups) => {
    const validation = validateDisplayGroupName(groups, name)
    if (!validation.valid) {
      return groups
    }
    return groups.concat({
      groupKey: createDisplayGroupKey(groups),
      name: validation.name,
      sortOrder: (groups.length + 1) * SORT_ORDER_STEP,
      workIds: []
    })
  })
}

function copyWorkTagsToDisplayGroups(config, componentKey, tags = []) {
  const groups = Array.isArray(tags)
    ? tags.map((tag, index) => ({
        groupKey: `g_${index + 1}`,
        name: trimText(tag && tag.name),
        sortOrder: (index + 1) * SORT_ORDER_STEP,
        workIds: []
      })).filter((group) => group.name)
    : []
  return updateComponentDisplayGroups(config, componentKey, () => groups)
}

function importWorksIntoDisplayGroup(config, componentKey, groupKey, workIds = []) {
  const targetGroupKey = trimText(groupKey)
  const importedIds = normalizeWorkIds(workIds)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.map((group) => {
    if (group.groupKey !== targetGroupKey) {
      return group
    }
    return Object.assign({}, group, {
      workIds: normalizeWorkIds(group.workIds.concat(importedIds))
    })
  }))
}

function updateDisplayGroupName(config, componentKey, groupKey, name) {
  const targetGroupKey = trimText(groupKey)
  const nextName = trimText(name)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.map((group) => {
    if (group.groupKey !== targetGroupKey || !nextName) {
      return group
    }
    return Object.assign({}, group, { name: nextName })
  }))
}

function removeDisplayGroup(config, componentKey, groupKey) {
  const targetGroupKey = trimText(groupKey)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.filter((group) => group.groupKey !== targetGroupKey))
}

function reorderDisplayGroup(config, componentKey, fromIndex, toIndex) {
  const sourceIndex = toNumber(fromIndex, -1)
  const targetIndex = toNumber(toIndex, -1)
  return updateComponentDisplayGroups(config, componentKey, (groups) => {
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex >= groups.length || targetIndex >= groups.length) {
      return groups
    }
    const nextGroups = groups.slice()
    const moving = nextGroups.splice(sourceIndex, 1)[0]
    nextGroups.splice(targetIndex, 0, moving)
    return nextGroups.map((group, index) => Object.assign({}, group, {
      sortOrder: (index + 1) * SORT_ORDER_STEP
    }))
  })
}

function updateDisplayGroupWorkIds(config, componentKey, groupKey, workIds = []) {
  const targetGroupKey = trimText(groupKey)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.map((group) => {
    if (group.groupKey !== targetGroupKey) {
      return group
    }
    return Object.assign({}, group, {
      workIds: normalizeWorkIds(workIds)
    })
  }))
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
  DISPLAY_GROUP_NAME_MAX_LENGTH,
  SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS,
  SCHEDULE_QUERY_DISPLAY_MODES,
  SCHEMA_VERSION,
  addDisplayGroup,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  copyWorkTagsToDisplayGroups,
  createComponent,
  importWorksIntoDisplayGroup,
  normalizePortfolioConfig,
  normalizeDisplayGroups,
  normalizeProfileComponentConfig,
  normalizeScheduleQueryConfig,
  normalizeWorkIds,
  reorderComponent,
  reorderDisplayGroup,
  removeComponent,
  removeDisplayGroup,
  updateDisplayGroupName,
  updateDisplayGroupWorkIds,
  updateComponentProfileConfig,
  updateComponentScheduleQueryConfig,
  updateComponentWorkIds,
  validateDisplayGroupName,
  validateCarouselComponent,
  validateWorkGridComponent
}
