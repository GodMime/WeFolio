const { normalizeHexColor } = require('./portfolio-color')
const {
  LEGACY_PERSONAL_FONT_SIZE_RPX,
  NEW_COMPONENT_FONT_SIZE_RPX,
  PORTFOLIO_TEXT_FONT_FAMILIES,
  normalizePortfolioTextFontFamily,
  normalizePortfolioTextFontSizeRpx
} = require('./portfolio-text-typography')

const SCHEMA_VERSION = 'standard-personal-v1'
const EDITOR_SCHEMA_REVISION = 3
const SORT_ORDER_STEP = 1000
const NAVIGATION_TITLE_MAX_LENGTH = 5
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
const DEFAULT_CONTACT_FORM_FIELDS = ['contactName', 'phone', 'wechat', 'needs']

const COMPONENT_TYPES = {
  CAROUSEL: 'CAROUSEL',
  PROFILE: 'PROFILE',
  SCHEDULE_QUERY: 'SCHEDULE_QUERY',
  WORK_GRID: 'WORK_GRID',
  WORK_LIST: 'WORK_LIST',
  SINGLE_WORK: 'SINGLE_WORK',
  QR_CONTACT: 'QR_CONTACT',
  CONTACT_FORM: 'CONTACT_FORM',
  TEXT_SECTION: 'TEXT_SECTION',
  DIVIDER: 'DIVIDER',
  HYPERLINK: 'HYPERLINK'
}

const COMPONENT_NAMES = {
  CAROUSEL: '轮播图',
  PROFILE: '个人资料',
  SCHEDULE_QUERY: '档期查询',
  WORK_GRID: '双列作品列表',
  WORK_LIST: '单列作品列表',
  SINGLE_WORK: '单个作品',
  QR_CONTACT: '二维码联系',
  CONTACT_FORM: '预留联系信息',
  TEXT_SECTION: '文字说明',
  DIVIDER: '分割线',
  HYPERLINK: '超链接'
}

const HYPERLINK_ACTION_TYPES = {
  INTERNAL_PORTFOLIO: 'INTERNAL_PORTFOLIO',
  EXTERNAL_LINK: 'EXTERNAL_LINK'
}
const HYPERLINK_ACTION_TYPE_OPTIONS = [
  {
    value: HYPERLINK_ACTION_TYPES.INTERNAL_PORTFOLIO,
    label: '内部作品集跳转',
    description: '跳转至一个已发布的作品集，访客可返回'
  },
  {
    value: HYPERLINK_ACTION_TYPES.EXTERNAL_LINK,
    label: '外部链接复制',
    description: '点击后复制链接或分享内容并展示提示语'
  }
]
const HYPERLINK_ICON_POSITIONS = {
  OVERLAY: 'OVERLAY',
  OVERLAY_BOTTOM_CENTER: 'OVERLAY_BOTTOM_CENTER',
  OVERLAY_CENTER: 'OVERLAY_CENTER',
  BELOW: 'BELOW'
}
const HYPERLINK_ICON_POSITION_OPTIONS = [
  {
    value: HYPERLINK_ICON_POSITIONS.OVERLAY,
    label: '图标悬浮于图片内右下角',
    description: '悬浮在图片内部右下角，带脉冲引导'
  },
  {
    value: HYPERLINK_ICON_POSITIONS.OVERLAY_BOTTOM_CENTER,
    label: '图标悬浮于图片内下方',
    description: '悬浮在图片内部底部居中，带脉冲引导'
  },
  {
    value: HYPERLINK_ICON_POSITIONS.OVERLAY_CENTER,
    label: '图标悬浮于图片内正中',
    description: '悬浮在图片内部正中，带脉冲引导'
  },
  {
    value: HYPERLINK_ICON_POSITIONS.BELOW,
    label: '图标置于图片下方',
    description: '居中显示在图片下方一行'
  }
]
const HYPERLINK_EXTERNAL_CONTENT_MAX_LENGTH = 2048
const HYPERLINK_PROMPT_TEXT_MAX_LENGTH = 30
const HYPERLINK_DISPLAY_WORK_REQUIRED_MESSAGE = '请选择图片或动图作品'
const HYPERLINK_TARGET_REQUIRED_MESSAGE = '请选择已发布的个人作品集'

const SCHEDULE_QUERY_DISPLAY_MODES = {
  MODAL_CALENDAR: 'MODAL_CALENDAR',
  INLINE_CALENDAR: 'INLINE_CALENDAR'
}

const SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS = [
  { value: SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR, label: '弹层显示月历' },
  { value: SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR, label: '直接显示月历' }
]

const CONTACT_FORM_DISPLAY_MODES = {
  MODAL_FORM: 'MODAL_FORM',
  INLINE_FORM: 'INLINE_FORM'
}

const CONTACT_FORM_DISPLAY_MODE_OPTIONS = [
  { value: CONTACT_FORM_DISPLAY_MODES.MODAL_FORM, label: '弹层显示表单' },
  { value: CONTACT_FORM_DISPLAY_MODES.INLINE_FORM, label: '直接显示表单' }
]
const TEXT_SECTION_MAX_LENGTH = 200
const TEXT_SECTION_ALIGNMENTS = {
  LEFT: 'LEFT',
  CENTER: 'CENTER',
  RIGHT: 'RIGHT'
}
const TEXT_SECTION_ALIGNMENT_OPTIONS = [
  { value: TEXT_SECTION_ALIGNMENTS.LEFT, label: '左对齐' },
  { value: TEXT_SECTION_ALIGNMENTS.CENTER, label: '居中' },
  { value: TEXT_SECTION_ALIGNMENTS.RIGHT, label: '右对齐' }
]
const DEFAULT_DIVIDER_HEIGHT_PX = 16
const PUBLISH_COMPONENT_MESSAGES = {
  CAROUSEL_WORK_REQUIRED: '请选择轮播作品',
  DISPLAY_WORK_REQUIRED: '请选择要展示的作品',
  SINGLE_WORK_REQUIRED: '请选择一个作品',
  CUSTOM_QR_REQUIRED: '请上传自定义联系二维码',
  TEXT_CONTENT_REQUIRED: '文字说明内容不能为空',
  TEXT_CONTENT_TOO_LONG: `文字说明不能超过 ${TEXT_SECTION_MAX_LENGTH} 字`
}
const DIVIDER_COLORS = {
  BLACK: 'BLACK',
  WHITE: 'WHITE',
  GRAY: 'GRAY',
  TRANSPARENT: 'TRANSPARENT'
}
const DIVIDER_COLOR_VALUES = {
  BLACK: '#000000',
  WHITE: '#ffffff',
  GRAY: '#eef1f4',
  TRANSPARENT: 'transparent'
}
const DIVIDER_COLOR_OPTIONS = [
  { value: DIVIDER_COLORS.BLACK, label: '黑', colorValue: DIVIDER_COLOR_VALUES.BLACK },
  { value: DIVIDER_COLORS.WHITE, label: '白', colorValue: DIVIDER_COLOR_VALUES.WHITE },
  { value: DIVIDER_COLORS.GRAY, label: '灰', colorValue: DIVIDER_COLOR_VALUES.GRAY },
  { value: DIVIDER_COLORS.TRANSPARENT, label: '透明', colorValue: DIVIDER_COLOR_VALUES.TRANSPARENT }
]

function trimText(value) {
  return String(value || '').trim()
}

function countText(value) {
  return Array.from(String(value || '')).length
}

function countUnicodeCodePoints(value) {
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

function normalizeSingleWorkConfig(raw = {}) {
  const workId = toNumber(raw && raw.workId)
  return Object.assign({
    workId: Number.isInteger(workId) && workId > 0 ? workId : 0,
  }, normalizeWorkDisplayOptions(raw))
}

function normalizeWorkDisplayOptions(raw = {}) {
  return {
    showTitle: typeof raw.showTitle === 'boolean' ? raw.showTitle : true,
    showDescription: typeof raw.showDescription === 'boolean' ? raw.showDescription : false
  }
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

function normalizeContactFormDisplayMode(value) {
  const displayMode = trimText(value)
  return CONTACT_FORM_DISPLAY_MODE_OPTIONS.some((item) => item.value === displayMode)
    ? displayMode
    : CONTACT_FORM_DISPLAY_MODES.MODAL_FORM
}

function normalizeContactFormConfig(raw = {}) {
  const fields = Array.isArray(raw.fields)
    ? raw.fields.map(trimText).filter(Boolean)
    : []
  return Object.assign({}, raw || {}, {
    displayMode: normalizeContactFormDisplayMode(raw && raw.displayMode),
    fields: fields.length > 0 ? Array.from(new Set(fields)) : DEFAULT_CONTACT_FORM_FIELDS.slice()
  })
}

function normalizeTextSectionAlignment(value) {
  const alignment = trimText(value)
  return TEXT_SECTION_ALIGNMENT_OPTIONS.some((item) => item.value === alignment)
    ? alignment
    : TEXT_SECTION_ALIGNMENTS.LEFT
}

function normalizeTextSectionConfig(raw = {}) {
  return Object.assign({}, raw || {}, {
    content: trimText(raw && raw.content),
    alignment: normalizeTextSectionAlignment(raw && raw.alignment),
    fontFamily: normalizePortfolioTextFontFamily(raw && raw.fontFamily),
    fontSizeRpx: normalizePortfolioTextFontSizeRpx(
      raw && raw.fontSizeRpx,
      LEGACY_PERSONAL_FONT_SIZE_RPX
    )
  })
}

function normalizeDividerColor(value) {
  const color = trimText(value)
  return DIVIDER_COLOR_OPTIONS.some((item) => item.value === color)
    ? color
    : DIVIDER_COLORS.GRAY
}

function normalizeDividerHeightPx(value) {
  const height = Math.round(toNumber(value, DEFAULT_DIVIDER_HEIGHT_PX))
  return height > 0 ? height : DEFAULT_DIVIDER_HEIGHT_PX
}

function normalizeDividerConfig(raw = {}) {
  return Object.assign({}, raw || {}, {
    color: normalizeDividerColor(raw && raw.color),
    heightPx: normalizeDividerHeightPx(raw && raw.heightPx)
  })
}

function normalizeHyperlinkConfig(raw = {}) {
  const config = Object.assign({}, raw || {})
  const workId = toNumber(config.workId)
  config.workId = Number.isInteger(workId) && workId > 0 ? workId : 0
  const actionType = trimText(config.actionType)
  config.actionType = HYPERLINK_ACTION_TYPE_OPTIONS.some((item) => item.value === actionType)
    ? actionType
    : ''
  config.showClickIcon = typeof config.showClickIcon === 'boolean'
    ? config.showClickIcon
    : false
  const iconPosition = trimText(config.iconPosition)
  config.iconPosition = HYPERLINK_ICON_POSITION_OPTIONS.some((item) => item.value === iconPosition)
    ? iconPosition
    : HYPERLINK_ICON_POSITIONS.OVERLAY
  if (config.actionType === HYPERLINK_ACTION_TYPES.INTERNAL_PORTFOLIO) {
    const targetPortfolioId = toNumber(config.targetPortfolioId)
    config.targetPortfolioId = Number.isInteger(targetPortfolioId) && targetPortfolioId > 0
      ? targetPortfolioId
      : 0
    delete config.externalContent
    delete config.promptText
  } else if (config.actionType === HYPERLINK_ACTION_TYPES.EXTERNAL_LINK) {
    config.externalContent = typeof config.externalContent === 'string' ? config.externalContent : ''
    config.promptText = typeof config.promptText === 'string' ? config.promptText : ''
    delete config.targetPortfolioId
  } else {
    delete config.targetPortfolioId
    delete config.externalContent
    delete config.promptText
  }
  return config
}

function collectComponentWorkIds(component = {}) {
  const config = component.config || {}
  if (component.componentType === COMPONENT_TYPES.SINGLE_WORK ||
    component.componentType === COMPONENT_TYPES.HYPERLINK) {
    return normalizeWorkIds([config.workId])
  }
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
    Object.assign(config, normalizeWorkDisplayOptions(config))
  }
  if (componentType === COMPONENT_TYPES.SINGLE_WORK) {
    const singleWorkConfig = normalizeSingleWorkConfig(config)
    Object.keys(config).forEach((key) => delete config[key])
    Object.assign(config, singleWorkConfig)
  }
  if (componentType === COMPONENT_TYPES.HYPERLINK) {
    const hyperlinkConfig = normalizeHyperlinkConfig(config)
    Object.keys(config).forEach((key) => delete config[key])
    Object.assign(config, hyperlinkConfig)
  }
  if (componentType === COMPONENT_TYPES.CONTACT_FORM) {
    Object.assign(config, normalizeContactFormConfig(config))
  }
  if (componentType === COMPONENT_TYPES.TEXT_SECTION) {
    Object.assign(config, normalizeTextSectionConfig(config))
  }
  if (componentType === COMPONENT_TYPES.DIVIDER) {
    Object.assign(config, normalizeDividerConfig(config))
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
  if (component.componentType === COMPONENT_TYPES.SCHEDULE_QUERY) {
    component.config = normalizeScheduleQueryConfig(component.config)
  }
  return component
}

function normalizeComponentList(components) {
  return Array.isArray(components)
    ? components
        .filter((item) => item && item.enabled !== false)
        .map(normalizeComponent)
        .sort((left, right) => {
          const orderDiff = toNumber(left.sortOrder) - toNumber(right.sortOrder)
          return orderDiff || trimText(left.componentKey).localeCompare(trimText(right.componentKey))
        })
        .map((item, index) => Object.assign({}, item, { sortOrder: (index + 1) * SORT_ORDER_STEP }))
    : []
}

function normalizeStyleConfig(raw = {}) {
  return {
    backgroundColor: normalizeHexColor(raw && raw.backgroundColor)
  }
}

function normalizeNavigationItem(raw = {}, index = 0) {
  const title = Array.from(trimText(raw.title)).slice(0, NAVIGATION_TITLE_MAX_LENGTH).join('')
  const item = {
    key: trimText(raw.key) || `nav_${index + 1}`,
    title: title || `菜单${index + 1}`
  }
  const iconUrl = trimText(raw.iconUrl)
  if (iconUrl) {
    item.iconUrl = iconUrl
  }
  if (index > 0) {
    item.components = normalizeComponentList(raw.components)
  }
  return item
}

function normalizeBottomNavConfig(raw = {}) {
  if (!raw || raw.enabled !== true) {
    return { enabled: false }
  }
  const items = Array.isArray(raw.items)
    ? raw.items.slice(0, 4).map(normalizeNavigationItem)
    : []
  if (items.length < 2) {
    return { enabled: false }
  }
  return {
    enabled: true,
    items
  }
}

function normalizePortfolioConfig(raw = {}) {
  return {
    schemaVersion: trimText(raw.schemaVersion) || SCHEMA_VERSION,
    editorSchemaRevision: EDITOR_SCHEMA_REVISION,
    share: normalizeShare(raw.share || {}),
    style: normalizeStyleConfig(raw.style || {}),
    components: normalizeComponentList(raw.components),
    bottomNav: normalizeBottomNavConfig(raw.bottomNav || {})
  }
}

function getMenuComponentList(config, menuKey = '') {
  const normalized = normalizePortfolioConfig(config)
  const bottomNav = normalized.bottomNav
  if (!bottomNav.enabled || !bottomNav.items.length) {
    return normalized.components.slice()
  }
  const targetKey = trimText(menuKey) || bottomNav.items[0].key
  const index = bottomNav.items.findIndex((item) => item.key === targetKey)
  if (index <= 0) {
    return normalized.components.slice()
  }
  return (bottomNav.items[index].components || []).slice()
}

function replaceMenuComponentList(config, menuKey = '', components = []) {
  const normalized = normalizePortfolioConfig(config)
  const nextComponents = normalizeComponentList(components)
  const bottomNav = normalized.bottomNav
  if (!bottomNav.enabled || !bottomNav.items.length) {
    return normalizePortfolioConfig(Object.assign({}, normalized, { components: nextComponents }))
  }
  const targetKey = trimText(menuKey) || bottomNav.items[0].key
  const index = bottomNav.items.findIndex((item) => item.key === targetKey)
  if (index <= 0) {
    return normalizePortfolioConfig(Object.assign({}, normalized, { components: nextComponents }))
  }
  const items = bottomNav.items.map((item, itemIndex) => itemIndex === index
    ? Object.assign({}, item, { components: nextComponents })
    : item)
  return normalizePortfolioConfig(Object.assign({}, normalized, {
    bottomNav: Object.assign({}, bottomNav, { items })
  }))
}

function visitPortfolioComponents(config, visitor) {
  const normalized = normalizePortfolioConfig(config)
  const navigationItems = normalized.bottomNav.enabled ? normalized.bottomNav.items : []
  const menuKeys = navigationItems.length ? navigationItems.map((item) => item.key) : ['']
  const locations = []
  menuKeys.forEach((menuKey, menuIndex) => {
    getMenuComponentList(normalized, menuKey).forEach((component, componentIndex) => {
      const location = {
        menuKey,
        menuIndex,
        componentIndex,
        component
      }
      locations.push(location)
      if (typeof visitor === 'function') {
        visitor(location)
      }
    })
  })
  return locations
}

function findPortfolioComponent(config, componentKey, componentType = '') {
  const targetKey = trimText(componentKey)
  const targetType = trimText(componentType)
  return visitPortfolioComponents(config).find((location) => {
    return location.component.componentKey === targetKey
      && (!targetType || location.component.componentType === targetType)
  }) || null
}

function removeNavigationItem(config, menuKey) {
  const normalized = normalizePortfolioConfig(config)
  if (!normalized.bottomNav.enabled) {
    return normalized
  }
  const targetIndex = normalized.bottomNav.items.findIndex((item) => item.key === trimText(menuKey))
  if (targetIndex < 0) {
    return normalized
  }
  const items = normalized.bottomNav.items.slice()
  let components = normalized.components
  if (targetIndex === 0 && items.length > 1) {
    components = (items[1].components || []).slice()
  }
  items.splice(targetIndex, 1)
  if (items.length < 2) {
    return normalizePortfolioConfig(Object.assign({}, normalized, {
      components,
      bottomNav: { enabled: false }
    }))
  }
  const nextItems = items.map((item, index) => normalizeNavigationItem(item, index))
  return normalizePortfolioConfig(Object.assign({}, normalized, {
    components,
    bottomNav: { enabled: true, items: nextItems }
  }))
}

function setBottomNavigationCount(config, count) {
  const normalized = normalizePortfolioConfig(config)
  const targetCount = Math.min(4, Math.max(1, Math.floor(toNumber(count, 1))))
  if (targetCount < 2) {
    return normalizePortfolioConfig(Object.assign({}, normalized, {
      bottomNav: { enabled: false }
    }))
  }
  const defaultTitles = ['主页', '作品', '档期', '联系']
  const currentItems = normalized.bottomNav.enabled ? normalized.bottomNav.items : []
  const items = Array.from({ length: targetCount }, (_, index) => {
    if (currentItems[index]) {
      return normalizeNavigationItem(currentItems[index], index)
    }
    return normalizeNavigationItem({
      key: `nav_${Date.now()}_${index + 1}`,
      title: defaultTitles[index],
      components: []
    }, index)
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, {
    bottomNav: { enabled: true, items }
  }))
}

function renameNavigationItem(config, menuKey, title) {
  const normalized = normalizePortfolioConfig(config)
  if (!normalized.bottomNav.enabled) {
    return normalized
  }
  const normalizedTitle = Array.from(trimText(title)).slice(0, NAVIGATION_TITLE_MAX_LENGTH).join('')
  if (!normalizedTitle) {
    return normalized
  }
  const items = normalized.bottomNav.items.map((item) => item.key === trimText(menuKey)
    ? Object.assign({}, item, { title: normalizedTitle })
    : item)
  return normalizePortfolioConfig(Object.assign({}, normalized, {
    bottomNav: Object.assign({}, normalized.bottomNav, { items })
  }))
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
  if (selected.some((work) => !['IMAGE', 'VIDEO'].includes(trimText(work.mediaType)))) {
    return { valid: false, message: '该组件只能选择图片或视频作品' }
  }
  return { valid: true, message: '' }
}

function validateSingleWorkComponent(component = {}, works = []) {
  const workId = normalizeSingleWorkConfig(component.config || {}).workId
  if (!workId) {
    return { valid: false, message: '请选择一个作品' }
  }
  const selected = works.find((work) => toNumber(work && work.id) === workId)
  if (!selected || !['IMAGE', 'VIDEO', 'ANIMATION'].includes(trimText(selected.mediaType))) {
    return { valid: false, message: '请选择有效作品' }
  }
  return { valid: true, message: '' }
}

function validateHyperlinkComponent(component = {}, works = []) {
  const config = normalizeHyperlinkConfig(component.config || {})
  if (!config.workId) {
    return { valid: false, message: HYPERLINK_DISPLAY_WORK_REQUIRED_MESSAGE }
  }
  const selected = works.find((work) => toNumber(work && work.id) === config.workId)
  if (!selected) {
    return { valid: false, message: '请选择有效展示作品' }
  }
  const mediaType = trimText(selected.mediaType)
  if (!['IMAGE', 'ANIMATION'].includes(mediaType)) {
    return { valid: false, message: '展示作品只能选择图片或动图' }
  }
  if (mediaType === 'ANIMATION' && trimText(selected.auditStatus) !== 'PASSED') {
    return { valid: false, message: '请选择审核通过的动图作品' }
  }
  if (!HYPERLINK_ACTION_TYPE_OPTIONS.some((item) => item.value === config.actionType)) {
    return { valid: false, message: '请选择点击行为' }
  }
  if (config.actionType === HYPERLINK_ACTION_TYPES.INTERNAL_PORTFOLIO) {
    return config.targetPortfolioId > 0
      ? { valid: true, message: '' }
      : { valid: false, message: HYPERLINK_TARGET_REQUIRED_MESSAGE }
  }
  const contentLength = countUnicodeCodePoints(config.externalContent)
  if (contentLength < 1 || contentLength > HYPERLINK_EXTERNAL_CONTENT_MAX_LENGTH) {
    return { valid: false, message: '链接或分享内容长度必须为1至2048个字符' }
  }
  const promptLength = countUnicodeCodePoints(config.promptText)
  if (promptLength < 1 || promptLength > HYPERLINK_PROMPT_TEXT_MAX_LENGTH) {
    return { valid: false, message: '提示语长度必须为1至30个字符' }
  }
  return { valid: true, message: '' }
}

/**
 * 校验当前草稿中无需服务端数据即可确定的组件发布必填项。
 *
 * @param {object} component 作品集组件
 * @returns {string} 空字符串表示通过，否则返回错误文案
 */
function validatePortfolioComponentForPublish(component = {}) {
  const config = component.config || {}
  switch (component.componentType) {
    case COMPONENT_TYPES.CAROUSEL:
      return normalizeWorkIds(config.workIds).length > 0
        ? ''
        : PUBLISH_COMPONENT_MESSAGES.CAROUSEL_WORK_REQUIRED
    case COMPONENT_TYPES.WORK_GRID:
    case COMPONENT_TYPES.WORK_LIST:
      return collectComponentWorkIds(component).length > 0
        ? ''
        : PUBLISH_COMPONENT_MESSAGES.DISPLAY_WORK_REQUIRED
    case COMPONENT_TYPES.SINGLE_WORK:
      return normalizeSingleWorkConfig(config).workId > 0
        ? ''
        : PUBLISH_COMPONENT_MESSAGES.SINGLE_WORK_REQUIRED
    case COMPONENT_TYPES.HYPERLINK: {
      const hyperlink = normalizeHyperlinkConfig(config)
      if (!hyperlink.workId) {
        return HYPERLINK_DISPLAY_WORK_REQUIRED_MESSAGE
      }
      if (!HYPERLINK_ACTION_TYPE_OPTIONS.some((item) => item.value === hyperlink.actionType)) {
        return '请选择点击行为'
      }
      if (hyperlink.actionType === HYPERLINK_ACTION_TYPES.INTERNAL_PORTFOLIO) {
        return hyperlink.targetPortfolioId > 0 ? '' : HYPERLINK_TARGET_REQUIRED_MESSAGE
      }
      const contentLength = countUnicodeCodePoints(hyperlink.externalContent)
      if (contentLength < 1 || contentLength > HYPERLINK_EXTERNAL_CONTENT_MAX_LENGTH) {
        return '链接或分享内容长度必须为1至2048个字符'
      }
      const promptLength = countUnicodeCodePoints(hyperlink.promptText)
      return promptLength < 1 || promptLength > HYPERLINK_PROMPT_TEXT_MAX_LENGTH
        ? '提示语长度必须为1至30个字符'
        : ''
    }
    case COMPONENT_TYPES.QR_CONTACT:
      return config.qrUrlSource === 'CUSTOM' && !trimText(config.qrUrl)
        ? PUBLISH_COMPONENT_MESSAGES.CUSTOM_QR_REQUIRED
        : ''
    case COMPONENT_TYPES.TEXT_SECTION: {
      const content = trimText(config.content)
      if (!content) {
        return PUBLISH_COMPONENT_MESSAGES.TEXT_CONTENT_REQUIRED
      }
      return countText(content) > TEXT_SECTION_MAX_LENGTH
        ? PUBLISH_COMPONENT_MESSAGES.TEXT_CONTENT_TOO_LONG
        : ''
    }
    default:
      return ''
  }
}

/**
 * 按菜单和组件显示顺序定位发布前第一个可确定的配置错误。
 *
 * 外部作品是否仍可用等动态规则继续由服务端执行最终校验。
 *
 * @param {object} config 作品集配置
 * @returns {{valid: boolean, menuKey: string, componentKey: string, message: string}} 校验结果
 */
function validatePortfolioForPublish(config = {}) {
  const normalized = normalizePortfolioConfig(config)
  const navigationEnabled = normalized.bottomNav.enabled
  const menus = navigationEnabled
    ? normalized.bottomNav.items.map((item) => ({
        key: item.key,
        title: item.title,
        components: getMenuComponentList(normalized, item.key)
      }))
    : [{
        key: '',
        title: '',
        components: normalized.components
      }]

  for (const menu of menus) {
    const messagePrefix = menu.title ? `【${menu.title}】` : ''
    if (menu.components.length === 0) {
      return {
        valid: false,
        menuKey: menu.key,
        componentKey: '',
        message: messagePrefix
          ? `${messagePrefix}至少添加一个组件`
          : '作品集至少需要 1 个启用组件'
      }
    }
    for (const component of menu.components) {
      const componentMessage = validatePortfolioComponentForPublish(component)
      if (componentMessage) {
        return {
          valid: false,
          menuKey: menu.key,
          componentKey: component.componentKey,
          message: `${messagePrefix}${componentMessage}`
        }
      }
    }
  }

  return {
    valid: true,
    menuKey: '',
    componentKey: '',
    message: ''
  }
}

function updateMenuComponentList(config, menuKey, updater) {
  const normalized = normalizePortfolioConfig(config)
  const components = getMenuComponentList(normalized, menuKey)
  return replaceMenuComponentList(normalized, menuKey, updater(components))
}

function addComponent(config, componentType, menuKey = '') {
  return updateMenuComponentList(config, menuKey, (components) => {
    const resolvedType = resolveComponentType(componentType)
    if (resolvedType === COMPONENT_TYPES.PROFILE
      && components.some((item) => item.componentType === COMPONENT_TYPES.PROFILE)) {
      return components
    }
    const nextComponent = createComponent(resolvedType, {
      sortOrder: (components.length + 1) * SORT_ORDER_STEP,
      config: resolvedType === COMPONENT_TYPES.TEXT_SECTION
        ? {
            fontFamily: PORTFOLIO_TEXT_FONT_FAMILIES.SYSTEM,
            fontSizeRpx: NEW_COMPONENT_FONT_SIZE_RPX
          }
        : {}
    })
    return components.concat(nextComponent)
  })
}

function removeComponent(config, componentKey, menuKey = '') {
  return updateMenuComponentList(config, menuKey, (components) => {
    return components.filter((item) => item.componentKey !== componentKey)
  })
}

function updateComponentWorkIds(config, componentKey, workIds, menuKey = '') {
  const targetKey = trimText(componentKey)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, {
        workIds: normalizeWorkIds(workIds)
      })
    })
  }))
}

function updateSingleWorkConfig(config, componentKey, singleWorkConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextSingleWorkConfig = normalizeSingleWorkConfig(singleWorkConfig)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.SINGLE_WORK) {
      return component
    }
    // SINGLE_WORK 配置采用严格白名单，整体替换可避免遗留或未来未知字段进入保存载荷。
    return Object.assign({}, component, { config: nextSingleWorkConfig })
  }))
}

function updateComponentHyperlinkConfig(config, componentKey, hyperlinkConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextHyperlinkConfig = normalizeHyperlinkConfig(hyperlinkConfig)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.HYPERLINK) {
      return component
    }
    return Object.assign({}, component, { config: nextHyperlinkConfig })
  }))
}

function updateWorkDisplayOptions(config, componentKey, options = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const displayOptions = normalizeWorkDisplayOptions(options)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || !isWorkListComponent(component.componentType)) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, displayOptions)
    })
  }))
}

function updateComponentProfileConfig(config, componentKey, profileConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextProfileConfig = normalizeProfileComponentConfig(profileConfig)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.PROFILE) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextProfileConfig)
    })
  }))
}

function updateComponentScheduleQueryConfig(config, componentKey, scheduleQueryConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextScheduleQueryConfig = normalizeScheduleQueryConfig(scheduleQueryConfig)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.SCHEDULE_QUERY) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextScheduleQueryConfig)
    })
  }))
}

function updateComponentContactFormConfig(config, componentKey, contactFormConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextContactFormConfig = normalizeContactFormConfig(contactFormConfig)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.CONTACT_FORM) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextContactFormConfig)
    })
  }))
}

function updateComponentTextSectionConfig(config, componentKey, textSectionConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextTextSectionConfig = {
    content: trimText(textSectionConfig.content),
    alignment: normalizeTextSectionAlignment(textSectionConfig.alignment)
  }
  if (Object.prototype.hasOwnProperty.call(textSectionConfig, 'fontFamily')) {
    nextTextSectionConfig.fontFamily =
      normalizePortfolioTextFontFamily(textSectionConfig.fontFamily)
  }
  if (Object.prototype.hasOwnProperty.call(textSectionConfig, 'fontSizeRpx')) {
    nextTextSectionConfig.fontSizeRpx = normalizePortfolioTextFontSizeRpx(
      textSectionConfig.fontSizeRpx,
      LEGACY_PERSONAL_FONT_SIZE_RPX
    )
  }
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.TEXT_SECTION) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextTextSectionConfig)
    })
  }))
}

function updateComponentDividerConfig(config, componentKey, dividerConfig = {}, menuKey = '') {
  const targetKey = trimText(componentKey)
  const nextDividerConfig = normalizeDividerConfig(dividerConfig)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.DIVIDER) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, nextDividerConfig)
    })
  }))
}

function updateComponentDisplayGroups(config, componentKey, updater, menuKey = '') {
  const targetKey = trimText(componentKey)
  return updateMenuComponentList(config, menuKey, (components) => components.map((component) => {
    if (component.componentKey !== targetKey || !isWorkListComponent(component.componentType)) {
      return component
    }
    const groups = normalizeDisplayGroups(component.config && component.config.groups)
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, {
        groups: normalizeDisplayGroups(updater(groups))
      })
    })
  }))
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

function addDisplayGroup(config, componentKey, name, menuKey = '') {
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
  }, menuKey)
}

function copyWorkTagsToDisplayGroups(config, componentKey, tags = [], menuKey = '') {
  const groups = Array.isArray(tags)
    ? tags.map((tag, index) => ({
        groupKey: `g_${index + 1}`,
        name: trimText(tag && tag.name),
        sortOrder: (index + 1) * SORT_ORDER_STEP,
        workIds: []
      })).filter((group) => group.name)
    : []
  return updateComponentDisplayGroups(config, componentKey, () => groups, menuKey)
}

function importWorksIntoDisplayGroup(config, componentKey, groupKey, workIds = [], menuKey = '') {
  const targetGroupKey = trimText(groupKey)
  const importedIds = normalizeWorkIds(workIds)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.map((group) => {
    if (group.groupKey !== targetGroupKey) {
      return group
    }
    return Object.assign({}, group, {
      workIds: normalizeWorkIds(group.workIds.concat(importedIds))
    })
  }), menuKey)
}

function updateDisplayGroupName(config, componentKey, groupKey, name, menuKey = '') {
  const targetGroupKey = trimText(groupKey)
  const nextName = trimText(name)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.map((group) => {
    if (group.groupKey !== targetGroupKey || !nextName) {
      return group
    }
    return Object.assign({}, group, { name: nextName })
  }), menuKey)
}

function removeDisplayGroup(config, componentKey, groupKey, menuKey = '') {
  const targetGroupKey = trimText(groupKey)
  return updateComponentDisplayGroups(
    config,
    componentKey,
    (groups) => groups.filter((group) => group.groupKey !== targetGroupKey),
    menuKey
  )
}

function reorderDisplayGroup(config, componentKey, fromIndex, toIndex, menuKey = '') {
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
  }, menuKey)
}

function updateDisplayGroupWorkIds(config, componentKey, groupKey, workIds = [], menuKey = '') {
  const targetGroupKey = trimText(groupKey)
  return updateComponentDisplayGroups(config, componentKey, (groups) => groups.map((group) => {
    if (group.groupKey !== targetGroupKey) {
      return group
    }
    return Object.assign({}, group, {
      workIds: normalizeWorkIds(workIds)
    })
  }), menuKey)
}

function reorderComponent(config, fromIndex, toIndex, menuKey = '') {
  const sourceIndex = toNumber(fromIndex, -1)
  const targetIndex = toNumber(toIndex, -1)
  return updateMenuComponentList(config, menuKey, (components) => {
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex >= components.length || targetIndex >= components.length) {
      return components
    }
    if (sourceIndex === targetIndex) {
      return components
    }
    const reordered = components.slice()
    const moving = reordered.splice(sourceIndex, 1)[0]
    reordered.splice(targetIndex, 0, moving)
    return reordered.map((item, index) => Object.assign({}, item, {
      sortOrder: (index + 1) * SORT_ORDER_STEP
    }))
  })
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
  CONTACT_FORM_DISPLAY_MODE_OPTIONS,
  CONTACT_FORM_DISPLAY_MODES,
  COMPONENT_NAMES,
  COMPONENT_TYPES,
  DEFAULT_DIVIDER_HEIGHT_PX,
  DIVIDER_COLOR_OPTIONS,
  DIVIDER_COLOR_VALUES,
  DIVIDER_COLORS,
  DISPLAY_GROUP_NAME_MAX_LENGTH,
  EDITOR_SCHEMA_REVISION,
  HYPERLINK_ACTION_TYPE_OPTIONS,
  HYPERLINK_ACTION_TYPES,
  HYPERLINK_DISPLAY_WORK_REQUIRED_MESSAGE,
  HYPERLINK_EXTERNAL_CONTENT_MAX_LENGTH,
  HYPERLINK_ICON_POSITION_OPTIONS,
  HYPERLINK_ICON_POSITIONS,
  HYPERLINK_PROMPT_TEXT_MAX_LENGTH,
  HYPERLINK_TARGET_REQUIRED_MESSAGE,
  NAVIGATION_TITLE_MAX_LENGTH,
  SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS,
  SCHEDULE_QUERY_DISPLAY_MODES,
  SCHEMA_VERSION,
  TEXT_SECTION_ALIGNMENT_OPTIONS,
  TEXT_SECTION_ALIGNMENTS,
  TEXT_SECTION_MAX_LENGTH,
  addDisplayGroup,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  copyWorkTagsToDisplayGroups,
  countUnicodeCodePoints,
  createComponent,
  findPortfolioComponent,
  getMenuComponentList,
  importWorksIntoDisplayGroup,
  normalizeBottomNavConfig,
  normalizeHyperlinkConfig,
  normalizePortfolioConfig,
  normalizeNavigationItem,
  normalizeStyleConfig,
  normalizeContactFormConfig,
  normalizeDividerConfig,
  normalizeDisplayGroups,
  normalizeProfileComponentConfig,
  normalizeScheduleQueryConfig,
  normalizeSingleWorkConfig,
  normalizeWorkDisplayOptions,
  normalizeTextSectionConfig,
  normalizeWorkIds,
  reorderComponent,
  reorderDisplayGroup,
  replaceMenuComponentList,
  removeComponent,
  removeDisplayGroup,
  removeNavigationItem,
  renameNavigationItem,
  setBottomNavigationCount,
  updateDisplayGroupName,
  updateDisplayGroupWorkIds,
  updateComponentContactFormConfig,
  updateComponentDividerConfig,
  updateComponentHyperlinkConfig,
  updateComponentProfileConfig,
  updateComponentScheduleQueryConfig,
  updateSingleWorkConfig,
  updateWorkDisplayOptions,
  updateComponentTextSectionConfig,
  updateComponentWorkIds,
  validateDisplayGroupName,
  validateCarouselComponent,
  validateHyperlinkComponent,
  validatePortfolioForPublish,
  validateSingleWorkComponent,
  validateWorkGridComponent,
  visitPortfolioComponents
}
