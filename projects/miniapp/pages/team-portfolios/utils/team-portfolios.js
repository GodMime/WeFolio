const { request } = require('../../../utils/request.js')
const { normalizeId } = require('../../../utils/id.js')
const {
  NEW_COMPONENT_FONT_SIZE_RPX,
  PORTFOLIO_TEXT_FONT_FAMILIES
} = require('../../../utils/portfolio-text-typography.js')
const teamPortfolioList = require('./team-portfolio-list.js')
const {
  TEAM_PORTFOLIOS_ENDPOINT,
  nonNegativeInteger,
  teamPortfolioEndpoint,
  text
} = teamPortfolioList

const COMPONENT_LIBRARY_ENDPOINT = `${TEAM_PORTFOLIOS_ENDPOINT}/component-library`
const STANDARD_TEAM_SCHEMA_VERSION = 'standard-team-v1'
const TEAM_EDITOR_SCHEMA_REVISION = 3
const DEFAULT_TEAM_BACKGROUND_COLOR = '#FFFFFF'
const TEAM_NAVIGATION_TITLE_MAX_LENGTH = 5
const TEAM_COMPONENT_SORT_ORDER_STEP = 1000
const TEAM_COMPONENT_KEY_PREFIX = 'component-'
const IDEMPOTENCY_KEY_MAX_LENGTH = 64
const TEAM_NAVIGATION_KEY_PATTERN = /^nav_[A-Za-z0-9_-]{1,64}$/
const TEAM_NAVIGATION_COUNT_INVALID_MESSAGE = '底部导航菜单数量必须为2到4个'
const TEAM_NAVIGATION_TITLE_REQUIRED_MESSAGE = '菜单名称不能为空'
const TEAM_NAVIGATION_TITLE_TOO_LONG_MESSAGE = '菜单名称不能超过5个字'
const TEAM_NAVIGATION_TITLE_DUPLICATE_MESSAGE = '菜单名称不能重复'
const TEAM_NAVIGATION_KEY_INVALID_MESSAGE = '菜单标识格式不正确'
const TEAM_NAVIGATION_KEY_DUPLICATE_MESSAGE = '菜单标识不能重复'
const TEAM_FIRST_NAVIGATION_COMPONENTS_DUPLICATE_MESSAGE = '第一个菜单不能重复保存组件'
const TEAM_SCHEMA_UNSUPPORTED_MESSAGE = '团队作品集配置版本不支持'
const TEAM_EDITOR_REVISION_UNSUPPORTED_MESSAGE = '当前客户端暂不支持此团队作品集配置'

function requireIdempotencyKey(value) {
  const normalized = text(value)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('幂等键必须为1至64个字符')
  return normalized
}

function normalizeTeamHexColor(value) {
  const color = text(value).toUpperCase()
  return /^#[0-9A-F]{6}$/.test(color) ? color : DEFAULT_TEAM_BACKGROUND_COLOR
}

// 新编辑器保存时始终声明 revision 3；旧编辑器不发送该字段，由后端据此执行兼容合并。
function normalizeTeamEditorSchemaRevision(value) {
  const revision = Number(value)
  return Number.isInteger(revision) && revision > TEAM_EDITOR_SCHEMA_REVISION
    ? revision
    : TEAM_EDITOR_SCHEMA_REVISION
}

function normalizeTeamVideoCarouselConfig(raw = {}) {
  const seenWorkIds = new Set()
  const items = (Array.isArray(raw.items) ? raw.items : []).reduce((result, item) => {
    const memberUserId = Number(item && item.memberUserId)
    const workId = Number(item && item.workId)
    if (!Number.isInteger(memberUserId) || memberUserId <= 0 ||
      !Number.isInteger(workId) || workId <= 0 || seenWorkIds.has(workId)) return result
    seenWorkIds.add(workId)
    result.push({ memberUserId, workId })
    return result
  }, [])
  return {
    title: Array.from(text(raw.title) || '视频作品').slice(0, 10).join(''),
    items,
    showTitle: typeof raw.showTitle === 'boolean' ? raw.showTitle : true,
    showSwipeHint: typeof raw.showSwipeHint === 'boolean' ? raw.showSwipeHint : true
  }
}

function normalizeTeamComponentList(components = []) {
  return (Array.isArray(components) ? components : [])
    .map((item = {}, index) => ({
      componentKey: text(item.componentKey),
      componentType: text(item.componentType),
      sortOrder: Number.isFinite(Number(item.sortOrder))
        ? Number(item.sortOrder)
        : (index + 1) * TEAM_COMPONENT_SORT_ORDER_STEP,
      enabled: item.enabled !== false,
      config: text(item.componentType) === 'VIDEO_CAROUSEL'
        ? normalizeTeamVideoCarouselConfig(item.config && typeof item.config === 'object' ? item.config : {})
        : (item.config && typeof item.config === 'object' ? item.config : {})
    }))
    .filter((item) => item.componentKey && item.componentType)
    .sort((left, right) => left.sortOrder - right.sortOrder)
}

function normalizeTeamNavigationItem(item = {}, index = 0) {
  const hasTitle = Object.prototype.hasOwnProperty.call(item, 'title')
  const normalized = {
    key: text(item.key) || `nav_${index + 1}`,
    title: Array.from(hasTitle ? text(item.title) : `菜单 ${index + 1}`)
      .slice(0, TEAM_NAVIGATION_TITLE_MAX_LENGTH)
      .join('')
  }
  const iconUrl = text(item.iconUrl)
  if (iconUrl) normalized.iconUrl = iconUrl
  if (index > 0) normalized.components = normalizeTeamComponentList(item.components)
  return normalized
}

function normalizeTeamBottomNavigation(bottomNav = {}) {
  if (!bottomNav || bottomNav.enabled !== true) return { enabled: false }
  const items = Array.isArray(bottomNav.items)
    ? bottomNav.items.slice(0, 4).map(normalizeTeamNavigationItem)
    : []
  return items.length >= 2 ? { enabled: true, items } : { enabled: false }
}

function normalizeTeamPortfolioConfig(payload = {}) {
  const share = payload.share && typeof payload.share === 'object' ? payload.share : {}
  return {
    schemaVersion: text(payload.schemaVersion) || STANDARD_TEAM_SCHEMA_VERSION,
    editorSchemaRevision: normalizeTeamEditorSchemaRevision(
      payload.editorSchemaRevision
    ),
    share: {
      title: text(share.title),
      description: text(share.description),
      coverUrl: text(share.coverUrl)
    },
    style: {
      backgroundColor: normalizeTeamHexColor(
        payload.style && payload.style.backgroundColor
      )
    },
    components: normalizeTeamComponentList(payload.components),
    bottomNav: normalizeTeamBottomNavigation(payload.bottomNav)
  }
}

function getTeamMenuComponentList(config, menuKey = '') {
  const normalized = normalizeTeamPortfolioConfig(config)
  if (!normalized.bottomNav.enabled) return normalized.components.slice()
  const targetKey = text(menuKey) || normalized.bottomNav.items[0].key
  const menuIndex = normalized.bottomNav.items.findIndex((item) => item.key === targetKey)
  if (menuIndex < 0) return []
  return menuIndex > 0
    ? (normalized.bottomNav.items[menuIndex].components || []).slice()
    : normalized.components.slice()
}

function replaceTeamMenuComponentList(config, menuKey = '', components = []) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const nextComponents = normalizeTeamComponentList(
    (Array.isArray(components) ? components : []).map((item, index) => Object.assign({}, item, {
      sortOrder: (index + 1) * TEAM_COMPONENT_SORT_ORDER_STEP
    }))
  )
  if (!normalized.bottomNav.enabled) {
    return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
      components: nextComponents
    }))
  }
  const targetKey = text(menuKey) || normalized.bottomNav.items[0].key
  const menuIndex = normalized.bottomNav.items.findIndex((item) => item.key === targetKey)
  if (menuIndex < 0) return normalized
  if (menuIndex === 0) {
    return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
      components: nextComponents
    }))
  }
  const items = normalized.bottomNav.items.map((item, index) => index === menuIndex
    ? Object.assign({}, item, { components: nextComponents })
    : item)
  return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
    bottomNav: { enabled: true, items }
  }))
}

function createTeamComponentKey() {
  return `${TEAM_COMPONENT_KEY_PREFIX}${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function addTeamComponent(config, componentType, menuKey = '') {
  const normalized = normalizeTeamPortfolioConfig(config)
  const type = text(componentType)
  if (!type) return normalized
  const components = getTeamMenuComponentList(normalized, menuKey)
  components.push({
    componentKey: createTeamComponentKey(),
    componentType: type,
    sortOrder: (components.length + 1) * TEAM_COMPONENT_SORT_ORDER_STEP,
    enabled: true,
    config: type === 'TEXT_SECTION'
      ? {
          fontFamily: PORTFOLIO_TEXT_FONT_FAMILIES.SYSTEM,
          fontSizeRpx: NEW_COMPONENT_FONT_SIZE_RPX
        }
      : type === 'VIDEO_CAROUSEL'
        ? normalizeTeamVideoCarouselConfig()
        : {}
  })
  return replaceTeamMenuComponentList(normalized, menuKey, components)
}

function removeTeamComponent(config, componentKey, menuKey = '') {
  const normalized = normalizeTeamPortfolioConfig(config)
  const targetKey = text(componentKey)
  const components = getTeamMenuComponentList(normalized, menuKey)
  if (!components.some((item) => item.componentKey === targetKey)) return normalized
  return replaceTeamMenuComponentList(
    normalized,
    menuKey,
    components.filter((item) => item.componentKey !== targetKey)
  )
}

function reorderTeamComponent(config, fromIndex, toIndex, menuKey = '') {
  const normalized = normalizeTeamPortfolioConfig(config)
  const components = getTeamMenuComponentList(normalized, menuKey)
  const source = Number(fromIndex)
  const target = Number(toIndex)
  if (!Number.isInteger(source) || !Number.isInteger(target) ||
      source < 0 || target < 0 || source >= components.length || target >= components.length ||
      source === target) return normalized
  const moved = components.splice(source, 1)[0]
  components.splice(target, 0, moved)
  return replaceTeamMenuComponentList(normalized, menuKey, components)
}

function updateTeamComponent(config, componentKey, value, menuKey = '') {
  const normalized = normalizeTeamPortfolioConfig(config)
  const targetKey = text(componentKey)
  const components = getTeamMenuComponentList(normalized, menuKey)
  if (!components.some((item) => item.componentKey === targetKey)) return normalized
  const nextComponents = components.map((component) => {
    if (component.componentKey !== targetKey) return component
    const patch = typeof value === 'function' ? value(component) : value
    return patch && typeof patch === 'object'
      ? Object.assign({}, component, patch, { componentKey: component.componentKey })
      : component
  })
  return replaceTeamMenuComponentList(normalized, menuKey, nextComponents)
}

function visitTeamPortfolioComponents(config, visitor) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const menuItems = normalized.bottomNav.enabled
    ? normalized.bottomNav.items
    : [{ key: '', title: '' }]
  const locations = []
  menuItems.forEach((menu, menuIndex) => {
    getTeamMenuComponentList(normalized, menu.key).forEach((component, componentIndex) => {
      const location = { menuKey: menu.key, menuIndex, componentIndex, component }
      locations.push(location)
      if (typeof visitor === 'function') visitor(location)
    })
  })
  return locations
}

function findTeamPortfolioComponent(config, componentKey, componentType = '') {
  const targetKey = text(componentKey)
  const targetType = text(componentType)
  return visitTeamPortfolioComponents(config).find((location) => {
    return location.component.componentKey === targetKey &&
      (!targetType || location.component.componentType === targetType)
  }) || null
}

function createTeamNavigationKey(index = 0) {
  return `nav_${Date.now().toString(36)}_${index + 1}_${Math.random().toString(36).slice(2, 6)}`
}

function setTeamBottomNavigationCount(config, count) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const targetCount = Math.min(4, Math.max(1, Math.floor(Number(count) || 1)))
  if (targetCount < 2) {
    return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
      bottomNav: { enabled: false }
    }))
  }
  const titles = ['主页', '菜单 2', '菜单 3', '菜单 4']
  const current = normalized.bottomNav.enabled ? normalized.bottomNav.items : []
  const items = Array.from({ length: targetCount }, (_, index) => {
    return current[index] || {
      key: createTeamNavigationKey(index),
      title: titles[index],
      components: []
    }
  }).map(normalizeTeamNavigationItem)
  return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
    bottomNav: { enabled: true, items }
  }))
}

function renameTeamNavigationItem(config, menuKey, title) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const nextTitle = Array.from(text(title))
    .slice(0, TEAM_NAVIGATION_TITLE_MAX_LENGTH)
    .join('')
  if (!normalized.bottomNav.enabled) return normalized
  const items = normalized.bottomNav.items.map((item) => item.key === text(menuKey)
    ? Object.assign({}, item, { title: nextTitle })
    : item)
  return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
    bottomNav: { enabled: true, items }
  }))
}

function removeTeamNavigationItem(config, menuKey) {
  const normalized = normalizeTeamPortfolioConfig(config)
  if (!normalized.bottomNav.enabled) return normalized
  const menuIndex = normalized.bottomNav.items.findIndex((item) => item.key === text(menuKey))
  if (menuIndex < 0) return normalized
  const items = normalized.bottomNav.items.slice()
  let components = normalized.components
  if (menuIndex === 0 && items.length > 1) {
    components = (items[1].components || []).slice()
  }
  items.splice(menuIndex, 1)
  if (items.length < 2) {
    return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
      components,
      bottomNav: { enabled: false }
    }))
  }
  return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
    components,
    bottomNav: {
      enabled: true,
      items: items.map(normalizeTeamNavigationItem)
    }
  }))
}

function moveTeamComponent(config, componentKey, sourceMenuKey, targetMenuKey) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const sourceKey = text(sourceMenuKey)
  const targetKey = text(targetMenuKey)
  if (!normalized.bottomNav.enabled ||
      !normalized.bottomNav.items.some((item) => item.key === sourceKey) ||
      !normalized.bottomNav.items.some((item) => item.key === targetKey) ||
      sourceKey === targetKey) return normalized
  const sourceComponents = getTeamMenuComponentList(normalized, sourceMenuKey)
  const targetComponents = getTeamMenuComponentList(normalized, targetMenuKey)
  const componentIndex = sourceComponents.findIndex((item) => item.componentKey === text(componentKey))
  if (componentIndex < 0) return normalized
  const moved = sourceComponents[componentIndex]
  const firstMenuKey = normalized.bottomNav.items[0].key
  const firstEnabledCount = sourceComponents.filter((item) => item.enabled !== false).length
  if (sourceKey === firstMenuKey && moved.enabled !== false && firstEnabledCount <= 1) {
    return normalized
  }
  let next = replaceTeamMenuComponentList(
    normalized,
    sourceMenuKey,
    sourceComponents.filter((_, index) => index !== componentIndex)
  )
  next = replaceTeamMenuComponentList(next, targetMenuKey, targetComponents.concat(moved))
  return next
}

function validateTeamComponentForPublish(component = {}) {
  const config = component.config || {}
  switch (component.componentType) {
    case 'CAROUSEL':
      return Array.isArray(config.items) && config.items.length ? '' : '请选择轮播作品'
    case 'VIDEO_CAROUSEL': {
      const count = normalizeTeamVideoCarouselConfig(config).items.length
      if (count < 3) return '视频轮播至少选择3个视频'
      return count > 8 ? '视频轮播最多选择8个视频' : ''
    }
    case 'SINGLE_WORK':
      return Number(config.workId) > 0 ? '' : '请选择一个作品'
    case 'MEMBER_PORTFOLIO_GRID':
    case 'MEMBER_PORTFOLIO_LIST':
      return Array.isArray(config.items) && config.items.length ? '' : '请选择成员作品集'
    case 'TEXT_SECTION':
      return text(config.content) ? '' : '请填写文字说明'
    case 'QR_CONTACT':
      return config.qrUrlSource === 'CUSTOM' && !text(config.qrUrl)
        ? '请选择二维码图片'
        : ''
    default:
      return ''
  }
}

function invalidTeamPublishResult(menuKey, componentKey, message) {
  return { valid: false, menuKey, componentKey, message }
}

function rawTeamPublishMenus(config = {}) {
  const bottomNav = config.bottomNav
  if (!bottomNav || bottomNav.enabled !== true) {
    return {
      menus: [{
        key: '',
        title: '',
        components: Array.isArray(config.components) ? config.components : []
      }]
    }
  }
  const items = Array.isArray(bottomNav.items) ? bottomNav.items : []
  if (items.length < 2 || items.length > 4) {
    return { error: invalidTeamPublishResult('', '', TEAM_NAVIGATION_COUNT_INVALID_MESSAGE) }
  }
  const menuKeys = new Set()
  const menuTitles = new Set()
  const menus = []
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index] && typeof items[index] === 'object' ? items[index] : {}
    const menuKey = text(item.key)
    const menuTitle = text(item.title)
    if (!TEAM_NAVIGATION_KEY_PATTERN.test(menuKey)) {
      return {
        error: invalidTeamPublishResult(
          menuKey,
          '',
          TEAM_NAVIGATION_KEY_INVALID_MESSAGE
        )
      }
    }
    if (menuKeys.has(menuKey)) {
      return {
        error: invalidTeamPublishResult(
          menuKey,
          '',
          TEAM_NAVIGATION_KEY_DUPLICATE_MESSAGE
        )
      }
    }
    if (!menuTitle) {
      return {
        error: invalidTeamPublishResult(
          menuKey,
          '',
          TEAM_NAVIGATION_TITLE_REQUIRED_MESSAGE
        )
      }
    }
    if (Array.from(menuTitle).length > TEAM_NAVIGATION_TITLE_MAX_LENGTH) {
      return {
        error: invalidTeamPublishResult(
          menuKey,
          '',
          TEAM_NAVIGATION_TITLE_TOO_LONG_MESSAGE
        )
      }
    }
    if (menuTitles.has(menuTitle)) {
      return {
        error: invalidTeamPublishResult(
          menuKey,
          '',
          TEAM_NAVIGATION_TITLE_DUPLICATE_MESSAGE
        )
      }
    }
    if (index === 0 && item.components !== null && item.components !== undefined) {
      return {
        error: invalidTeamPublishResult(
          menuKey,
          '',
          TEAM_FIRST_NAVIGATION_COMPONENTS_DUPLICATE_MESSAGE
        )
      }
    }
    menuKeys.add(menuKey)
    menuTitles.add(menuTitle)
    menus.push({
      key: menuKey,
      title: menuTitle,
      components: index === 0
        ? (Array.isArray(config.components) ? config.components : [])
        : (Array.isArray(item.components) ? item.components : [])
    })
  }
  return { menus }
}

function validateRawTeamPublishComponents(menus) {
  const componentKeys = new Set()
  let profileFound = false
  for (const menu of menus) {
    const prefix = menu.title ? `【${menu.title}】` : ''
    const enabledComponents = menu.components.filter((component) =>
      component && component.enabled !== false
    )
    if (!enabledComponents.length) {
      return invalidTeamPublishResult(
        menu.key,
        '',
        prefix ? `${prefix}至少添加一个组件` : '作品集至少需要 1 个启用组件'
      )
    }
    for (const component of menu.components) {
      const componentKey = text(component && component.componentKey)
      const componentType = text(component && component.componentType)
      if (!componentKey || !componentType) {
        return invalidTeamPublishResult(
          menu.key,
          componentKey,
          `${prefix}组件信息不完整`
        )
      }
      if (componentKeys.has(componentKey)) {
        return invalidTeamPublishResult(
          menu.key,
          componentKey,
          `${prefix}组件标识不能重复`
        )
      }
      componentKeys.add(componentKey)
      if (componentType === 'TEAM_PROFILE') {
        if (profileFound) {
          return invalidTeamPublishResult(
            menu.key,
            componentKey,
            `${prefix}团队资料组件只能添加一个`
          )
        }
        profileFound = true
      }
    }
  }
  return null
}

function validateTeamPortfolioForPublish(config = {}) {
  if (text(config.schemaVersion) !== STANDARD_TEAM_SCHEMA_VERSION) {
    return invalidTeamPublishResult('', '', TEAM_SCHEMA_UNSUPPORTED_MESSAGE)
  }
  if (Number(config.editorSchemaRevision) !== TEAM_EDITOR_SCHEMA_REVISION) {
    return invalidTeamPublishResult('', '', TEAM_EDITOR_REVISION_UNSUPPORTED_MESSAGE)
  }
  const rawColor = text(config.style && config.style.backgroundColor).toUpperCase()
  if (rawColor && !/^#[0-9A-F]{6}$/.test(rawColor)) {
    return invalidTeamPublishResult('', '', '背景颜色格式不正确')
  }
  const rawMenuResult = rawTeamPublishMenus(config)
  if (rawMenuResult.error) return rawMenuResult.error
  const rawComponentError = validateRawTeamPublishComponents(rawMenuResult.menus)
  if (rawComponentError) return rawComponentError
  const normalized = normalizeTeamPortfolioConfig(config)
  const menus = normalized.bottomNav.enabled
    ? normalized.bottomNav.items.map((item) => ({
        key: item.key,
        title: item.title,
        components: getTeamMenuComponentList(normalized, item.key)
      }))
    : [{ key: '', title: '', components: normalized.components }]
  if (normalized.bottomNav.enabled) {
    const menuKeys = new Set()
    const menuTitles = new Set()
    for (const menu of menus) {
      if (!menu.key || menuKeys.has(menu.key)) {
        return invalidTeamPublishResult(menu.key, '', TEAM_NAVIGATION_KEY_DUPLICATE_MESSAGE)
      }
      if (!menu.title || Array.from(menu.title).length > TEAM_NAVIGATION_TITLE_MAX_LENGTH) {
        return invalidTeamPublishResult(menu.key, '', TEAM_NAVIGATION_TITLE_REQUIRED_MESSAGE)
      }
      if (menuTitles.has(menu.title)) {
        return invalidTeamPublishResult(menu.key, '', TEAM_NAVIGATION_TITLE_DUPLICATE_MESSAGE)
      }
      menuKeys.add(menu.key)
      menuTitles.add(menu.title)
    }
  }
  const componentKeys = new Set()
  let profileFound = false
  for (const menu of menus) {
    const enabledComponents = menu.components.filter((component) => component.enabled !== false)
    const prefix = menu.title ? `【${menu.title}】` : ''
    if (!enabledComponents.length) {
      return invalidTeamPublishResult(
        menu.key,
        '',
        prefix ? `${prefix}至少添加一个组件` : '作品集至少需要 1 个启用组件'
      )
    }
    for (const component of menu.components) {
      if (!component.componentKey || !component.componentType) {
        return invalidTeamPublishResult(
          menu.key, component.componentKey, `${prefix}组件信息不完整`
        )
      }
      if (componentKeys.has(component.componentKey)) {
        return invalidTeamPublishResult(
          menu.key, component.componentKey, `${prefix}组件标识不能重复`
        )
      }
      componentKeys.add(component.componentKey)
      if (component.componentType === 'TEAM_PROFILE') {
        if (profileFound) {
          return invalidTeamPublishResult(
            menu.key, component.componentKey, `${prefix}团队资料组件只能添加一个`
          )
        }
        profileFound = true
      }
      if (component.enabled !== false) {
        const message = validateTeamComponentForPublish(component)
        if (message) {
          return invalidTeamPublishResult(
            menu.key, component.componentKey, `${prefix}${message}`
          )
        }
      }
    }
  }
  return { valid: true, menuKey: '', componentKey: '', message: '' }
}

function normalizeTeamPortfolioDetail(payload = {}) {
  return {
    portfolioId: normalizeId(payload.portfolioId),
    shareCode: text(payload.shareCode),
    ownerType: text(payload.ownerType),
    ownerId: normalizeId(payload.ownerId),
    templateType: text(payload.templateType),
    status: text(payload.status),
    publicationStatus: text(payload.publicationStatus),
    draftRevision: nonNegativeInteger(payload.draftRevision),
    publishedRevision: nonNegativeInteger(payload.publishedRevision),
    config: normalizeTeamPortfolioConfig(payload.config),
    renderData: payload.renderData && typeof payload.renderData === 'object' ? payload.renderData : null
  }
}

function fetchTeamComponentLibrary(requestFn = request) {
  return requestFn({
    url: COMPONENT_LIBRARY_ENDPOINT,
    data: { editorSchemaRevision: TEAM_EDITOR_SCHEMA_REVISION }
  })
}

function fetchTeamPortfolioDetail(requestFn = request, portfolioId) {
  return requestFn({ url: teamPortfolioEndpoint(portfolioId) }).then(normalizeTeamPortfolioDetail)
}

function saveTeamPortfolioDraft(requestFn = request, portfolioId, config, clientRevision, idempotencyKey) {
  let normalizedIdempotencyKey
  try { normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey) } catch (error) { return Promise.reject(error) }
  return requestFn({
    url: teamPortfolioEndpoint(portfolioId, '/draft'),
    method: 'POST',
    data: { config, clientRevision: nonNegativeInteger(clientRevision), idempotencyKey: normalizedIdempotencyKey }
  })
}

function publishTeamPortfolio(requestFn = request, portfolioId, draftRevision, idempotencyKey) {
  let normalizedIdempotencyKey
  try { normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey) } catch (error) { return Promise.reject(error) }
  return requestFn({
    url: teamPortfolioEndpoint(portfolioId, '/publish'),
    method: 'POST',
    data: { draftRevision: nonNegativeInteger(draftRevision), idempotencyKey: normalizedIdempotencyKey }
  })
}

function previewTeamPortfolio(requestFn = request, portfolioId, published = false) {
  return requestFn({ url: teamPortfolioEndpoint(portfolioId, published ? '/published-preview' : '/preview') })
}

module.exports = Object.assign({}, teamPortfolioList, {
  addTeamComponent,
  COMPONENT_LIBRARY_ENDPOINT,
  DEFAULT_TEAM_BACKGROUND_COLOR,
  STANDARD_TEAM_SCHEMA_VERSION,
  TEAM_EDITOR_SCHEMA_REVISION,
  fetchTeamComponentLibrary,
  fetchTeamPortfolioDetail,
  findTeamPortfolioComponent,
  getTeamMenuComponentList,
  moveTeamComponent,
  normalizeTeamPortfolioConfig,
  normalizeTeamPortfolioDetail,
  normalizeTeamVideoCarouselConfig,
  removeTeamNavigationItem,
  removeTeamComponent,
  renameTeamNavigationItem,
  reorderTeamComponent,
  replaceTeamMenuComponentList,
  setTeamBottomNavigationCount,
  previewTeamPortfolio,
  publishTeamPortfolio,
  saveTeamPortfolioDraft,
  validateTeamPortfolioForPublish,
  visitTeamPortfolioComponents,
  updateTeamComponent
})
