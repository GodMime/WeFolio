const { request } = require('../../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../../utils/session')
const { noop } = require('../../../utils/noop')
const { isRemoteUrl } = require('../../../utils/upload-file')
const { normalizeProfile: normalizeBasicProfile } = require('../../../utils/profile')
const { normalizeWorkList, normalizeWorkTags } = require('../utils/works')
const { confirmPortfolioPublishDisclaimer } = require('../../../utils/portfolio-publish-disclaimer')
const {
  PORTFOLIO_ASSET_TYPES,
  PORTFOLIO_COVER_CROP_FILE_TYPE,
  PORTFOLIO_COVER_CROP_OUTPUT_WIDTH,
  PORTFOLIO_COVER_CROP_QUALITY,
  PORTFOLIO_COVER_RATIO_HEIGHT,
  PORTFOLIO_COVER_RATIO_WIDTH,
  buildPortfolioCoverCropFrame,
  buildPortfolioCoverCropState,
  createChoosePortfolioImageOptions,
  cropPortfolioCoverToTempFilePath,
  getPortfolioCoverImageInfo,
  movePortfolioCoverCropState,
  shouldCropPortfolioCover,
  uploadPortfolioImageAsset
} = require('../utils/portfolio-assets')
const {
  CONTACT_FORM_DISPLAY_MODE_OPTIONS,
  CONTACT_FORM_DISPLAY_MODES,
  COMPONENT_NAMES,
  COMPONENT_TYPES,
  DEFAULT_DIVIDER_HEIGHT_PX,
  DIVIDER_COLOR_OPTIONS,
  DIVIDER_COLORS,
  SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS,
  SCHEDULE_QUERY_DISPLAY_MODES,
  TEXT_SECTION_ALIGNMENT_OPTIONS,
  TEXT_SECTION_ALIGNMENTS,
  TEXT_SECTION_MAX_LENGTH,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  createComponent,
  normalizeContactFormConfig,
  normalizeDividerConfig,
  normalizeScheduleQueryConfig,
  normalizeProfileComponentConfig,
  normalizePortfolioConfig,
  normalizeTextSectionConfig,
  normalizeWorkIds,
  reorderComponent,
  removeComponent,
  updateComponentContactFormConfig,
  updateComponentDividerConfig,
  updateComponentScheduleQueryConfig,
  updateComponentProfileConfig,
  updateComponentTextSectionConfig,
  updateComponentWorkIds
} = require('../../../utils/portfolios')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'
const STANDARD_PERSONAL_API_URL = '/api/mine/portfolios/standard-personal'
const COMPONENT_LIBRARY_API_URL = '/api/mine/portfolios/component-library'
const BASIC_PROFILE_API_URL = '/api/mine/profile'
const WORKS_API_URL = '/api/mine/works'
const PASSED_WORK_AUDIT_STATUS = 'PASSED'
const PORTFOLIOS_PAGE_ROUTE = 'pages/portfolios/portfolios'
const PORTFOLIOS_PAGE_URL = `/${PORTFOLIOS_PAGE_ROUTE}`
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const COMPONENT_DRAG_SCALE = 1.015
const COMPONENT_WORK_PAGE_SIZE = 20
const DISPLAY_GROUP_WORK_PAGE_SIZE = 100
const DISPLAY_GROUP_TAG_KEY_PREFIX = 'tag_'
const DISPLAY_GROUP_SORT_ORDER_STEP = 1000
const DESIGN_VIEWPORT_RPX = 750
const SHARE_COVER_CROP_CANVAS_ID = 'portfolioCoverCropCanvas'
const SHARE_COVER_CROP_MAX_WIDTH_RPX = 640
const SHARE_COVER_CROP_HORIZONTAL_GUTTER_RPX = 112
const PROFILE_TAG_SPLIT_REGEXP = /[,\n，、]/
const PROFILE_VISIBLE_FIELD_OPTIONS = [
  { field: 'avatar', label: '头像' },
  { field: 'displayName', label: '姓名 / 艺名' },
  { field: 'profession', label: '职业身份' },
  { field: 'city', label: '服务城市' },
  { field: 'bio', label: '个人简介' },
  { field: 'tags', label: '个人标签' },
  { field: 'wechatQr', label: '微信二维码' }
]
const QR_CONTACT_SOURCE_PROFILE = 'PROFILE'
const QR_CONTACT_SOURCE_CUSTOM = 'CUSTOM'
const QR_CONTACT_REMOVED_CONFIG_KEYS = ['title', 'description']
const SHARE_FIELD_LIMITS = {
  title: 50
}
const PROFILE_FIELD_LIMITS = {
  displayName: 50,
  profession: 50,
  city: 50,
  bio: 500,
  tagsText: 100
}
const PUBLICATION_STATUS_DRAFT = 'DRAFT'
const PUBLICATION_STATUS_PUBLISHED = 'PUBLISHED'
const PUBLICATION_STATUS_OFFLINE = 'OFFLINE'
const PUBLICATION_STATUS_TEXT_MAP = {
  [PUBLICATION_STATUS_DRAFT]: '草稿',
  [PUBLICATION_STATUS_PUBLISHED]: '已发布',
  [PUBLICATION_STATUS_OFFLINE]: '已下线'
}
const PUBLICATION_STATUS_TONE_MAP = {
  [PUBLICATION_STATUS_DRAFT]: 'draft',
  [PUBLICATION_STATUS_PUBLISHED]: 'published',
  [PUBLICATION_STATUS_OFFLINE]: 'muted'
}
const IDEMPOTENCY_PREFIX_DRAFT = 'draft'
const IDEMPOTENCY_PREFIX_PUBLISH = 'publish'
const BASIC_PROFILE_LOAD_ERROR_MESSAGE = '基础资料加载失败'
const TEXT_SECTION_REQUIRED_MESSAGE = '请填写文字说明'
const DIVIDER_HEIGHT_REQUIRED_MESSAGE = '请输入大于 0 的高度'
const WORK_ASPECT_RATIO_FALLBACK_TEXT = '--'

const DEFAULT_COMPONENT_DESCRIPTIONS = {
  CAROUSEL: '展示已选择的图片作品',
  PROFILE: '展示个人资料和服务标签',
  SCHEDULE_QUERY: '开放访客查询档期',
  WORK_GRID: '双列展示图片和视频作品',
  WORK_LIST: '单列展示重点图片和视频作品',
  QR_CONTACT: '展示二维码联系方式',
  CONTACT_FORM: '收集访客预留联系信息',
  TEXT_SECTION: '添加服务说明文字',
  DIVIDER: '分隔不同内容区块'
}

function makeIdempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function buildPublicationStatusState(publicationStatus) {
  const normalizedStatus = PUBLICATION_STATUS_TEXT_MAP[publicationStatus]
    ? publicationStatus
    : PUBLICATION_STATUS_DRAFT
  return {
    publicationStatus: normalizedStatus,
    statusText: PUBLICATION_STATUS_TEXT_MAP[normalizedStatus],
    statusTone: PUBLICATION_STATUS_TONE_MAP[normalizedStatus],
    showPublishAction: normalizedStatus === PUBLICATION_STATUS_PUBLISHED
  }
}

const DEFAULT_PUBLICATION_STATUS_STATE = buildPublicationStatusState()

function buildComponentOptions(options = [], components = []) {
  const profileAdded = components.some((component) => component.componentType === COMPONENT_TYPES.PROFILE)
  return options.map((item) => Object.assign({}, item, {
    disabled: item.componentType === COMPONENT_TYPES.PROFILE && profileAdded
  }))
}

function buildDefaultComponentOptions(components = []) {
  const options = Object.keys(COMPONENT_TYPES).map((key) => {
    const componentType = COMPONENT_TYPES[key]
    return {
      componentType,
      name: COMPONENT_NAMES[componentType],
      description: DEFAULT_COMPONENT_DESCRIPTIONS[componentType] || '标准个人作品集可选组件'
    }
  })
  return buildComponentOptions(options, components)
}

function getTouchClientY(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0])
  const clientY = touch && Number(touch.clientY)
  return Number.isFinite(clientY) ? clientY : null
}

function getTouchClientX(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0])
  const clientX = touch && Number(touch.clientX)
  return Number.isFinite(clientX) ? clientX : null
}

function buildComponentDragStyle(offsetY = 0) {
  const roundedOffset = Math.round(Number(offsetY) || 0)
  return `transform: translate3d(0, ${roundedOffset}px, 0) scale(${COMPONENT_DRAG_SCALE}); transition: transform 80ms linear, box-shadow 160ms ease, border-color 160ms ease, background 160ms ease; z-index: 3;`
}

function findComponentByKey(config = {}, componentKey) {
  return (config.components || []).find((component) => component.componentKey === componentKey) || null
}

function isDisplayGroupComponent(componentType) {
  return componentType === COMPONENT_TYPES.WORK_GRID || componentType === COMPONENT_TYPES.WORK_LIST
}

function isEditableComponentType(componentType) {
  return componentType === COMPONENT_TYPES.CAROUSEL ||
    componentType === COMPONENT_TYPES.PROFILE ||
    componentType === COMPONENT_TYPES.QR_CONTACT ||
    componentType === COMPONENT_TYPES.SCHEDULE_QUERY ||
    componentType === COMPONENT_TYPES.CONTACT_FORM ||
    componentType === COMPONENT_TYPES.TEXT_SECTION ||
    componentType === COMPONENT_TYPES.DIVIDER ||
    isDisplayGroupComponent(componentType)
}

function buildSelectedCountText(workIds = []) {
  return `${normalizeWorkIds(workIds).length} 已选`
}

function clonePlainObject(value = {}) {
  return JSON.parse(JSON.stringify(value || {}))
}

function buildDisplayGroupTagKey(tagId) {
  const normalizedTagId = Number(tagId)
  return Number.isFinite(normalizedTagId) && normalizedTagId > 0 ? `${DISPLAY_GROUP_TAG_KEY_PREFIX}${normalizedTagId}` : ''
}

function parseDisplayGroupTagId(groupKey = '') {
  const value = String(groupKey || '')
  if (!value.startsWith(DISPLAY_GROUP_TAG_KEY_PREFIX)) {
    return null
  }
  const tagId = Number(value.slice(DISPLAY_GROUP_TAG_KEY_PREFIX.length))
  return Number.isFinite(tagId) && tagId > 0 ? tagId : null
}

function getDisplayGroups(component = {}) {
  return component.config && Array.isArray(component.config.groups) ? component.config.groups : []
}

function findWorkTagByGroupKey(tags = [], groupKey = '') {
  const tagId = parseDisplayGroupTagId(groupKey)
  return tags.find((tag) => tag.id === tagId) || null
}

function findWorkTagForDisplayGroup(tags = [], group = {}) {
  const matchedByKey = findWorkTagByGroupKey(tags, group.groupKey)
  if (matchedByKey) {
    return matchedByKey
  }
  const groupName = String(group.name || '').trim()
  return tags.find((tag) => tag.name === groupName) || null
}

function findDisplayGroupByTag(component = {}, tag = {}) {
  const targetKey = buildDisplayGroupTagKey(tag.id)
  const groups = getDisplayGroups(component)
  return groups.find((group) => group.groupKey === targetKey) ||
    groups.find((group) => group.name === tag.name) ||
    null
}

function buildSelectedDisplayGroups(component = {}, tags = []) {
  const seenKeys = new Set()
  return getDisplayGroups(component).reduce((result, group) => {
    const tag = findWorkTagForDisplayGroup(tags, group)
    const groupKey = tag ? buildDisplayGroupTagKey(tag.id) : ''
    if (!tag || !groupKey || seenKeys.has(groupKey)) {
      return result
    }
    seenKeys.add(groupKey)
    result.push({
      groupKey,
      name: tag.name,
      sortOrder: (result.length + 1) * DISPLAY_GROUP_SORT_ORDER_STEP,
      workIds: normalizeWorkIds(group.workIds)
    })
    return result
  }, [])
}

function buildDisplayGroupOptions(component = {}, activeGroupKey = '', tags = []) {
  const selectedGroups = buildSelectedDisplayGroups(component, tags)
  const selectedMap = selectedGroups.reduce((result, group, index) => {
    result[group.groupKey] = Object.assign({}, group, { selectionOrder: index + 1 })
    return result
  }, {})
  return tags.map((tag) => {
    const groupKey = buildDisplayGroupTagKey(tag.id)
    const selectedGroup = selectedMap[groupKey]
    const workIds = normalizeWorkIds(selectedGroup && selectedGroup.workIds)
    return Object.assign({}, tag, {
      groupKey,
      active: groupKey === activeGroupKey,
      selected: Boolean(selectedGroup),
      selectionOrder: selectedGroup ? selectedGroup.selectionOrder : 0,
      countText: `${workIds.length}`
    })
  })
}

function buildActiveDisplayGroupWorkCountText(component = {}, activeGroupKey = '', tags = []) {
  const tag = findWorkTagByGroupKey(tags, activeGroupKey)
  const group = tag ? findDisplayGroupByTag(component, tag) : null
  return `${normalizeWorkIds(group && group.workIds).length} 个已选`
}

function normalizeWorkAspectRatioText(work = {}) {
  return String(work.aspectRatioText || work.aspectRatio || '').trim() || WORK_ASPECT_RATIO_FALLBACK_TEXT
}

function buildComponentWorkOptions(works = [], selectedIds = [], componentType = '') {
  const selectedSet = new Set(normalizeWorkIds(selectedIds))
  return works
    .filter((work) => componentType !== COMPONENT_TYPES.CAROUSEL || work.mediaType === 'IMAGE')
    .map((work) => Object.assign({}, work, {
      selected: selectedSet.has(work.id),
      thumbUrl: work.coverUrl || work.mediaUrl || '',
      metaText: work.tagText && work.tagText !== '未设置标签' ? `${work.typeText} · ${work.tagText}` : work.typeText,
      aspectRatioText: normalizeWorkAspectRatioText(work)
    }))
}

function normalizeDisplayGroupWorkPreview(work = {}, fallbackId = 0) {
  const workId = Number(work.id || fallbackId)
  const title = String(work.title || '').trim() || `作品 #${workId}`
  const typeText = String(work.typeText || work.mediaType || '').trim()
  const tagText = String(work.tagText || '').trim()
  return {
    id: workId,
    title,
    thumbUrl: work.thumbUrl || work.coverUrl || work.mediaUrl || '',
    metaText: tagText && tagText !== '未设置标签' ? `${typeText || '作品'} · ${tagText}` : (typeText || '作品'),
    aspectRatioText: normalizeWorkAspectRatioText(work)
  }
}

function mergeDisplayGroupWorkMap(currentMap = {}, works = []) {
  return works.reduce((result, work) => {
    const workId = Number(work && work.id)
    if (!Number.isFinite(workId) || workId <= 0) {
      return result
    }
    result[workId] = normalizeDisplayGroupWorkPreview(work, workId)
    return result
  }, Object.assign({}, currentMap))
}

function workHasTag(work = {}, tagId) {
  return Array.isArray(work.tags) && work.tags.some((tag) => tag.id === tagId)
}

function buildDisplayGroupWorkOptions(component = {}, activeGroupKey = '', tags = [], works = []) {
  const tag = findWorkTagByGroupKey(tags, activeGroupKey)
  if (!tag) {
    return []
  }
  const group = findDisplayGroupByTag(component, tag)
  const selectedIds = normalizeWorkIds(group && group.workIds)
  const selectedOrderMap = selectedIds.reduce((result, workId, index) => {
    result[workId] = index + 1
    return result
  }, {})
  const workMap = works.reduce((result, work) => {
    const workId = Number(work && work.id)
    if (Number.isFinite(workId) && workId > 0) {
      result[workId] = work
    }
    return result
  }, {})
  const taggedWorks = works.filter((work) => workHasTag(work, tag.id))
  const taggedWorkIds = taggedWorks.reduce((result, work) => {
    const workId = Number(work && work.id)
    if (Number.isFinite(workId) && workId > 0) {
      result.add(workId)
    }
    return result
  }, new Set())
  const selectedWorksWithoutTag = selectedIds
    .map((workId) => workMap[workId])
    .filter((work) => work && !taggedWorkIds.has(Number(work.id)))
  return taggedWorks
    .concat(selectedWorksWithoutTag)
    .map((work) => Object.assign(normalizeDisplayGroupWorkPreview(work, work.id), {
      selected: Boolean(selectedOrderMap[work.id]),
      selectionOrder: selectedOrderMap[work.id] || 0
    }))
}

function resolveActiveDisplayGroupKey(component = {}, tags = [], preferredGroupKey = '') {
  if (findWorkTagByGroupKey(tags, preferredGroupKey)) {
    return preferredGroupKey
  }
  const selectedGroups = buildSelectedDisplayGroups(component, tags)
  if (selectedGroups[0]) {
    return selectedGroups[0].groupKey
  }
  return tags[0] ? buildDisplayGroupTagKey(tags[0].id) : ''
}

function replaceDisplayComponentGroups(config = {}, componentKey = '', groups = []) {
  const targetKey = String(componentKey || '')
  const normalized = normalizePortfolioConfig(config)
  const normalizedGroups = groups.map((group, index) => ({
    groupKey: String(group.groupKey || ''),
    name: String(group.name || '').trim(),
    sortOrder: (index + 1) * DISPLAY_GROUP_SORT_ORDER_STEP,
    workIds: normalizeWorkIds(group.workIds)
  })).filter((group) => group.groupKey && group.name)
  const components = normalized.components.map((component) => {
    if (component.componentKey !== targetKey || !isDisplayGroupComponent(component.componentType)) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, {
        workIds: [],
        groups: normalizedGroups
      })
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalized, { components }))
}

function normalizeComponentWorkTagId(value) {
  const tagId = Number(value)
  return Number.isFinite(tagId) && tagId > 0 ? tagId : null
}

function mergeComponentWorks(currentWorks = [], nextWorks = []) {
  const seen = new Set()
  return currentWorks.concat(nextWorks).filter((work) => {
    const workId = Number(work && work.id)
    if (!Number.isFinite(workId) || workId <= 0 || seen.has(workId)) {
      return false
    }
    seen.add(workId)
    return true
  })
}

function buildProfileForm(profile = {}) {
  return {
    avatarUrl: profile.avatarUrl || '',
    displayName: profile.displayName || '',
    profession: profile.profession || '',
    city: profile.city || '',
    bio: profile.bio || '',
    tagsText: Array.isArray(profile.tags) ? profile.tags.map((tag) => tag.name).filter(Boolean).join('，') : '',
    wechatQrUrl: profile.wechatQrUrl || ''
  }
}

function buildQrContactForm(config = {}) {
  const qrUrlSource = config.qrUrlSource === QR_CONTACT_SOURCE_CUSTOM ? QR_CONTACT_SOURCE_CUSTOM : QR_CONTACT_SOURCE_PROFILE
  return {
    qrUrlSource,
    qrUrl: qrUrlSource === QR_CONTACT_SOURCE_CUSTOM ? config.qrUrl || '' : ''
  }
}

function buildQrContactSourcePatch(currentSource, nextSource) {
  const normalizedCurrentSource = currentSource === QR_CONTACT_SOURCE_CUSTOM
    ? QR_CONTACT_SOURCE_CUSTOM
    : QR_CONTACT_SOURCE_PROFILE
  const normalizedNextSource = nextSource === QR_CONTACT_SOURCE_CUSTOM
    ? QR_CONTACT_SOURCE_CUSTOM
    : QR_CONTACT_SOURCE_PROFILE
  const patch = {
    'qrContactForm.qrUrlSource': normalizedNextSource
  }
  if (normalizedCurrentSource !== normalizedNextSource) {
    patch['qrContactForm.qrUrl'] = ''
  }
  return patch
}

function buildScheduleQueryForm(config = {}) {
  const normalized = normalizeScheduleQueryConfig(config)
  return {
    displayMode: normalized.displayMode || SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR
  }
}

function buildContactFormConfigForm(config = {}) {
  const normalized = normalizeContactFormConfig(config)
  return {
    displayMode: normalized.displayMode || CONTACT_FORM_DISPLAY_MODES.MODAL_FORM
  }
}

function buildTextSectionForm(config = {}) {
  const normalized = normalizeTextSectionConfig(config)
  return {
    content: normalized.content || '',
    alignment: normalized.alignment || TEXT_SECTION_ALIGNMENTS.LEFT
  }
}

function buildDividerForm(config = {}) {
  const normalized = normalizeDividerConfig(config)
  return {
    color: normalized.color || DIVIDER_COLORS.GRAY,
    heightPx: normalized.heightPx || DEFAULT_DIVIDER_HEIGHT_PX
  }
}

function parseDividerHeightPx(value) {
  const height = Math.round(Number(value))
  return Number.isFinite(height) && height > 0 ? height : null
}

function countText(value) {
  return Array.from(String(value || '')).length
}

function buildShareFieldCounters(share = {}) {
  return Object.keys(SHARE_FIELD_LIMITS).reduce((result, field) => {
    result[field] = `${countText(share[field])} / ${SHARE_FIELD_LIMITS[field]}`
    return result
  }, {})
}

function buildProfileFieldCounters(form = {}) {
  return Object.keys(PROFILE_FIELD_LIMITS).reduce((result, field) => {
    result[field] = `${countText(form[field])} / ${PROFILE_FIELD_LIMITS[field]}`
    return result
  }, {})
}

function buildTextSectionFieldCounters(form = {}) {
  return {
    content: `${countText(form.content)} / ${TEXT_SECTION_MAX_LENGTH}`
  }
}

function buildProfileVisibleOptions(visibleFields = {}) {
  const normalized = normalizeProfileComponentConfig({ visibleFields }).visibleFields
  return PROFILE_VISIBLE_FIELD_OPTIONS.map((item) => Object.assign({}, item, {
    checked: Boolean(normalized[item.field])
  }))
}

function buildVisibleFieldsFromOptions(options = []) {
  return options.reduce((result, item) => {
    result[item.field] = Boolean(item.checked)
    return result
  }, {})
}

function parseProfileTagsText(tagsText = '') {
  const seen = new Set()
  return String(tagsText || '')
    .split(PROFILE_TAG_SPLIT_REGEXP)
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item)) {
        return false
      }
      seen.add(item)
      return true
    })
    .map((name) => ({ name }))
}

function buildProfileConfigFromForm(form = {}, visibleOptions = []) {
  return normalizeProfileComponentConfig({
    profile: {
      avatarUrl: form.avatarUrl,
      displayName: form.displayName,
      profession: form.profession,
      city: form.city,
      bio: form.bio,
      tags: parseProfileTagsText(form.tagsText),
      wechatQrUrl: form.wechatQrUrl
    },
    visibleFields: buildVisibleFieldsFromOptions(visibleOptions)
  })
}

function resolveBasicProfileQrContactQrUrl(raw = {}) {
  return normalizeBasicProfile(raw).wechatQrUrl || ''
}

function updateComponentQrContactConfig(config, componentKey, qrContactForm = {}) {
  const normalizedConfig = normalizePortfolioConfig(config)
  const targetKey = componentKey || ''
  const form = buildQrContactForm(qrContactForm)
  const qrUrl = form.qrUrlSource === QR_CONTACT_SOURCE_CUSTOM ? form.qrUrl : String(qrContactForm.qrUrl || '')
  const components = (normalizedConfig.components || []).map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.QR_CONTACT) {
      return component
    }
    const nextComponentConfig = Object.assign({}, component.config || {}, qrContactForm || {}, {
      qrUrlSource: form.qrUrlSource,
      qrUrl
    })
    QR_CONTACT_REMOVED_CONFIG_KEYS.forEach((key) => {
      delete nextComponentConfig[key]
    })
    return Object.assign({}, component, {
      config: nextComponentConfig
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalizedConfig, { components }))
}

function buildProfileFormFromBasicProfile(raw = {}, currentForm = {}) {
  const profile = normalizeBasicProfile(raw)
  const nickname = String(profile.nickname || '').trim()
  return {
    avatarUrl: profile.avatarUrl,
    displayName: nickname || profile.displayName,
    profession: profile.profession,
    city: profile.city,
    bio: profile.intro,
    tagsText: profile.tags.map((tag) => tag.content).filter(Boolean).join('，'),
    wechatQrUrl: currentForm.wechatQrUrl || ''
  }
}

function hasProfileCopyValue(profile = {}) {
  return Boolean(profile.avatarUrl ||
    profile.displayName ||
    profile.profession ||
    profile.city ||
    profile.bio ||
    profile.wechatQrUrl ||
    (Array.isArray(profile.tags) && profile.tags.length > 0))
}

function shouldApplyBasicProfileDefaults(config = {}) {
  return (config.components || []).some((component) => {
    if (component.componentType !== COMPONENT_TYPES.PROFILE) {
      return false
    }
    const profileConfig = normalizeProfileComponentConfig(component.config || {})
    return !hasProfileCopyValue(profileConfig.profile)
  })
}

function buildProfileConfigFromBasicProfile(raw = {}, currentConfig = {}) {
  const profileConfig = normalizeProfileComponentConfig(currentConfig)
  const profileForm = buildProfileFormFromBasicProfile(raw, buildProfileForm(profileConfig.profile))
  return buildProfileConfigFromForm(profileForm, buildProfileVisibleOptions(profileConfig.visibleFields))
}

function applyBasicProfileDefaultsToConfig(config = {}, raw = {}) {
  const normalizedConfig = normalizePortfolioConfig(config)
  let changed = false
  const components = (normalizedConfig.components || []).map((component) => {
    if (component.componentType !== COMPONENT_TYPES.PROFILE) {
      return component
    }
    const profileConfig = normalizeProfileComponentConfig(component.config || {})
    if (hasProfileCopyValue(profileConfig.profile)) {
      return component
    }
    const nextProfileConfig = buildProfileConfigFromBasicProfile(raw, profileConfig)
    if (!hasProfileCopyValue(nextProfileConfig.profile)) {
      return component
    }
    changed = true
    return Object.assign({}, component, {
      config: nextProfileConfig
    })
  })
  if (!changed) {
    return normalizedConfig
  }
  return normalizePortfolioConfig(Object.assign({}, normalizedConfig, { components }))
}

function resolvePortfolioListBackDelta() {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  for (let index = pages.length - 2; index >= 0; index -= 1) {
    if (pages[index] && pages[index].route === PORTFOLIOS_PAGE_ROUTE) {
      return pages.length - 1 - index
    }
  }
  return 0
}

function resolveChosenImageFile(response = {}) {
  const files = Array.isArray(response.tempFiles) ? response.tempFiles : []
  const firstFile = files[0] || {}
  const filePath = firstFile.tempFilePath || firstFile.path || ''
  return filePath ? Object.assign({}, firstFile, { path: filePath }) : null
}

function resolveChosenImagePath(response = {}) {
  const imageFile = resolveChosenImageFile(response)
  return imageFile ? imageFile.path : ''
}

function getShareCoverCropBoxWidth() {
  const fallbackWindowWidth = 375
  const systemInfo = typeof wx !== 'undefined' && wx.getSystemInfoSync ? wx.getSystemInfoSync() : {}
  const windowWidth = Number(systemInfo.windowWidth) || fallbackWindowWidth
  const rpxScale = windowWidth / DESIGN_VIEWPORT_RPX
  return Math.floor(Math.min(
    SHARE_COVER_CROP_MAX_WIDTH_RPX * rpxScale,
    windowWidth - SHARE_COVER_CROP_HORIZONTAL_GUTTER_RPX * rpxScale
  ))
}

function resetShareCoverCropState() {
  return {
    shareCoverCropVisible: false,
    shareCoverCropSaving: false,
    shareCoverCropErrorText: '',
    shareCoverCropState: null,
    shareCoverCropTouchStart: null
  }
}

function resolveServerSafeAssetUrl(url) {
  const value = String(url || '').trim()
  return !value || isRemoteUrl(value) ? value : ''
}

function buildServerSafePortfolioConfig(config = {}) {
  const normalizedConfig = normalizePortfolioConfig(config)
  const share = Object.assign({}, normalizedConfig.share, {
    coverUrl: resolveServerSafeAssetUrl(normalizedConfig.share && normalizedConfig.share.coverUrl),
    avatarUrl: resolveServerSafeAssetUrl(normalizedConfig.share && normalizedConfig.share.avatarUrl)
  })
  const components = (normalizedConfig.components || []).map((component) => {
    if (component.componentType === COMPONENT_TYPES.PROFILE) {
      const profileConfig = normalizeProfileComponentConfig(component.config || {})
      const profile = Object.assign({}, profileConfig.profile, {
        avatarUrl: resolveServerSafeAssetUrl(profileConfig.profile.avatarUrl),
        wechatQrUrl: resolveServerSafeAssetUrl(profileConfig.profile.wechatQrUrl)
      })
      return Object.assign({}, component, {
        config: Object.assign({}, component.config || {}, profileConfig, { profile })
      })
    }
    if (component.componentType === COMPONENT_TYPES.QR_CONTACT) {
      return Object.assign({}, component, {
        config: Object.assign({}, component.config || {}, {
          qrUrl: resolveServerSafeAssetUrl(component.config && component.config.qrUrl)
        })
      })
    }
    return component
  })
  return normalizePortfolioConfig(Object.assign({}, normalizedConfig, { share, components }))
}

function updateShareCoverUrlInConfig(config = {}, coverUrl = '') {
  const normalizedConfig = normalizePortfolioConfig(config)
  return normalizePortfolioConfig(Object.assign({}, normalizedConfig, {
    share: Object.assign({}, normalizedConfig.share, { coverUrl })
  }))
}

Page({
  componentWorkRequestSeq: 0,

  data: {
    portfolioId: null,
    draftRevision: 0,
    publishedRevision: 0,
    publicationStatus: DEFAULT_PUBLICATION_STATUS_STATE.publicationStatus,
    statusText: DEFAULT_PUBLICATION_STATUS_STATE.statusText,
    statusTone: DEFAULT_PUBLICATION_STATUS_STATE.statusTone,
    showPublishAction: DEFAULT_PUBLICATION_STATUS_STATE.showPublishAction,
    draggingIndex: -1,
    dragTargetIndex: -1,
    componentDragStartY: null,
    componentDragStyle: '',
    revealedComponentKey: '',
    componentTouchStart: null,
    componentSheetVisible: false,
    componentOptions: buildDefaultComponentOptions(),
    componentWorkSheetVisible: false,
    componentWorkSheetTitle: '编辑轮播作品',
    componentWorkLoading: false,
    componentWorkLoadingMore: false,
    componentWorkErrorText: '',
    componentWorkEmptyText: '暂无图片作品',
    componentWorkOptions: [],
    componentWorkFilterTags: [],
    componentWorkKeyword: '',
    componentWorkSelectedTagId: null,
    componentWorkPage: 1,
    componentWorkPageSize: COMPONENT_WORK_PAGE_SIZE,
    componentWorkHasMore: false,
    componentWorkSelectedIds: [],
    componentWorkSelectedCountText: '0 已选',
    editingComponentKey: '',
    editingComponentType: '',
    displayGroupSheetVisible: false,
    editingDisplayComponentKey: '',
    editingDisplayComponentType: '',
    displayGroupOptions: [],
    activeDisplayGroupKey: '',
    activeDisplayGroupWorkCountText: '0 个已选',
    displayGroupWorkOptions: [],
    displayGroupAllWorks: [],
    displayGroupWorkMap: {},
    displayGroupOriginalConfig: null,
    workTagOptions: [],
    displayGroupLoading: false,
    displayGroupErrorText: '',
    profileSheetVisible: false,
    profileSheetLoading: false,
    profileSheetErrorText: '',
    editingProfileComponentKey: '',
    profileForm: buildProfileForm(),
    profileFieldCounters: buildProfileFieldCounters(buildProfileForm()),
    profileVisibleOptions: buildProfileVisibleOptions(),
    qrContactSheetVisible: false,
    editingQrContactComponentKey: '',
    qrContactProfileQrUrl: '',
    qrContactForm: buildQrContactForm(),
    scheduleQuerySheetVisible: false,
    scheduleQueryEditingComponentKey: '',
    scheduleQueryDisplayModeOptions: SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS,
    scheduleQueryForm: buildScheduleQueryForm(),
    contactFormSheetVisible: false,
    contactFormEditingComponentKey: '',
    contactFormDisplayModeOptions: CONTACT_FORM_DISPLAY_MODE_OPTIONS,
    contactFormConfigForm: buildContactFormConfigForm(),
    textSectionSheetVisible: false,
    textSectionEditingComponentKey: '',
    textSectionAlignmentOptions: TEXT_SECTION_ALIGNMENT_OPTIONS,
    textSectionMaxLength: TEXT_SECTION_MAX_LENGTH,
    textSectionForm: buildTextSectionForm(),
    textSectionFieldCounters: buildTextSectionFieldCounters(buildTextSectionForm()),
    dividerSheetVisible: false,
    dividerEditingComponentKey: '',
    dividerColorOptions: DIVIDER_COLOR_OPTIONS,
    dividerForm: buildDividerForm(),
    shareFieldCounters: buildShareFieldCounters(),
    shareCoverCropVisible: false,
    shareCoverCropSaving: false,
    shareCoverCropErrorText: '',
    shareCoverCropState: null,
    shareCoverCropTouchStart: null,
    shareCoverCropCanvasWidth: PORTFOLIO_COVER_CROP_OUTPUT_WIDTH,
    shareCoverCropCanvasHeight: Math.round(
      PORTFOLIO_COVER_CROP_OUTPUT_WIDTH * PORTFOLIO_COVER_RATIO_HEIGHT / PORTFOLIO_COVER_RATIO_WIDTH
    ),
    config: normalizePortfolioConfig({
      components: [createComponent(COMPONENT_TYPES.PROFILE)]
    })
  },

  onLoad(options = {}) {
    this.setData({ portfolioId: options.portfolioId || null })
    return this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      wx.navigateTo({ url: '/pages/login/login' })
      return
    }
    if (!this.data.portfolioId) {
      return this.loadBasicProfileDefaults(this.data.config)
    }
    return request({ url: `${PORTFOLIO_API_PREFIX}/${this.data.portfolioId}` })
      .then((response) => {
        const config = normalizePortfolioConfig(response.config || {})
        this.setData(Object.assign({
          draftRevision: response.draftRevision || 0,
          publishedRevision: response.publishedRevision || 0,
          config,
          shareFieldCounters: buildShareFieldCounters(config.share)
        }, buildPublicationStatusState(response.publicationStatus)))
        return this.loadBasicProfileDefaults(config)
      })
      .catch((error) => {
        if (error.authRequired) {
          handleMaintainerAuthRequired(error.message)
          return
        }
        wx.showToast({ title: error.message || '加载失败', icon: 'none' })
      })
  },

  loadBasicProfileDefaults(config = this.data.config) {
    const normalizedConfig = normalizePortfolioConfig(config)
    if (!shouldApplyBasicProfileDefaults(normalizedConfig)) {
      return Promise.resolve(normalizedConfig)
    }
    return request({ url: BASIC_PROFILE_API_URL })
      .then((response) => {
        const nextConfig = applyBasicProfileDefaultsToConfig(normalizedConfig, response)
        this.setData({
          config: nextConfig,
          shareFieldCounters: buildShareFieldCounters(nextConfig.share)
        })
        return nextConfig
      })
      .catch((error) => {
        if (error && error.authRequired) {
          handleMaintainerAuthRequired(error.message)
        }
        return normalizedConfig
      })
  },

  handleShareInput(event) {
    const path = event.currentTarget.dataset.path
    const config = Object.assign({}, this.data.config)
    config.share = Object.assign({}, config.share)
    if (path === 'share.title') {
      config.share.title = event.detail.value
    } else {
      return
    }
    this.setData({
      config,
      shareFieldCounters: buildShareFieldCounters(config.share)
    })
  },

  setShareCoverUrl(coverUrl) {
    const config = Object.assign({}, this.data.config)
    config.share = Object.assign({}, config.share, { coverUrl })
    const normalizedConfig = normalizePortfolioConfig(config)
    this.setData({
      config: normalizedConfig,
      shareFieldCounters: buildShareFieldCounters(normalizedConfig.share),
      shareCoverCropVisible: false,
      shareCoverCropSaving: false,
      shareCoverCropErrorText: '',
      shareCoverCropState: null,
      shareCoverCropTouchStart: null
    })
  },

  prepareSelectedShareCover(imageFile) {
    return getPortfolioCoverImageInfo(imageFile)
      .then((imageInfo) => {
        if (!shouldCropPortfolioCover(imageInfo)) {
          this.setShareCoverUrl(imageInfo.path)
          return
        }
        const cropState = buildPortfolioCoverCropState(imageInfo, {
          cropBoxWidth: getShareCoverCropBoxWidth()
        })
        this.setData({
          shareCoverCropVisible: true,
          shareCoverCropSaving: false,
          shareCoverCropErrorText: '',
          shareCoverCropState: cropState,
          shareCoverCropTouchStart: null
        })
      })
      .catch((error) => {
        wx.showToast({ title: error && error.message ? error.message : '选择封面失败', icon: 'none' })
      })
  },

  handleChooseShareCover() {
    wx.chooseMedia(Object.assign({}, createChoosePortfolioImageOptions(), {
      success: (response) => {
        const imageFile = resolveChosenImageFile(response)
        if (imageFile) {
          this.prepareSelectedShareCover(imageFile)
        }
      },
      fail: (error) => {
        if (error && error.errMsg && !/cancel/i.test(error.errMsg)) {
          wx.showToast({ title: '选择封面失败', icon: 'none' })
        }
      }
    }))
  },

  handleCloseShareCoverCrop() {
    if (this.data.shareCoverCropSaving) {
      return
    }
    this.setData(resetShareCoverCropState())
  },

  handleShareCoverCropTouchStart(event) {
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    const cropState = this.data.shareCoverCropState || {}
    this.setData({
      shareCoverCropTouchStart: {
        x: clientX === null ? 0 : clientX,
        y: clientY === null ? 0 : clientY,
        offsetX: Number(cropState.offsetX) || 0,
        offsetY: Number(cropState.offsetY) || 0
      }
    })
  },

  handleShareCoverCropTouchMove(event) {
    const start = this.data.shareCoverCropTouchStart
    const cropState = this.data.shareCoverCropState
    if (!start || !cropState) {
      return
    }
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    const baseState = Object.assign({}, cropState, {
      offsetX: start.offsetX,
      offsetY: start.offsetY
    })
    this.setData({
      shareCoverCropState: movePortfolioCoverCropState(baseState, {
        deltaX: (clientX === null ? start.x : clientX) - start.x,
        deltaY: (clientY === null ? start.y : clientY) - start.y
      })
    })
  },

  handleShareCoverCropTouchEnd() {
    this.setData({ shareCoverCropTouchStart: null })
  },

  handleShareCoverCropTouchCancel() {
    this.setData({ shareCoverCropTouchStart: null })
  },

  handleConfirmShareCoverCrop() {
    if (this.data.shareCoverCropSaving || !this.data.shareCoverCropState) {
      return Promise.resolve()
    }
    const cropState = this.data.shareCoverCropState
    const cropFrame = buildPortfolioCoverCropFrame(cropState, {
      outputWidth: PORTFOLIO_COVER_CROP_OUTPUT_WIDTH
    })
    this.setData({
      shareCoverCropSaving: true,
      shareCoverCropErrorText: ''
    })
    return cropPortfolioCoverToTempFilePath({
      page: this,
      wxApi: wx,
      canvasId: SHARE_COVER_CROP_CANVAS_ID,
      imagePath: cropState.imagePath,
      cropFrame,
      fileType: PORTFOLIO_COVER_CROP_FILE_TYPE,
      quality: PORTFOLIO_COVER_CROP_QUALITY
    }).then((croppedPath) => {
      this.setShareCoverUrl(croppedPath)
    }).catch((error) => {
      const message = error && error.message ? error.message : '封面裁剪失败'
      this.setData({
        shareCoverCropSaving: false,
        shareCoverCropErrorText: message
      })
      wx.showToast({ title: message, icon: 'none' })
    })
  },

  handleOpenComponentSheet() {
    this.setData({
      componentSheetVisible: true,
      componentOptions: buildComponentOptions(this.data.componentOptions, this.data.config.components)
    })
    this.loadComponentOptions()
  },

  loadComponentOptions() {
    request({ url: COMPONENT_LIBRARY_API_URL })
      .then((response) => {
        if (response && Array.isArray(response.components) && response.components.length > 0) {
          this.setData({
            componentOptions: buildComponentOptions(response.components, this.data.config.components)
          })
        }
      })
      .catch(() => {})
  },

  handleCloseComponentSheet() {
    this.setData({ componentSheetVisible: false })
  },

  noop,

  handleSelectComponent(event) {
    const componentType = event.currentTarget.dataset.type
    const disabled = event.currentTarget.dataset.disabled
    const profileAdded = componentType === COMPONENT_TYPES.PROFILE &&
      this.data.config.components.some((component) => component.componentType === COMPONENT_TYPES.PROFILE)
    if (!componentType || disabled || profileAdded) {
      return
    }
    this.setData({
      config: addComponent(this.data.config, componentType),
      componentSheetVisible: false
    })
  },

  handleComponentTouchStart(event) {
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    this.setData({
      componentTouchStart: {
        key: event.currentTarget.dataset.key || '',
        index: Number(event.currentTarget.dataset.index),
        x: clientX === null ? 0 : clientX,
        y: clientY === null ? 0 : clientY
      }
    })
  },

  handleComponentDragStart(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isFinite(index)) {
      return
    }
    const clientY = getTouchClientY(event)
    wx.createSelectorQuery()
      .in(this)
      .selectAll('.component-row')
      .boundingClientRect((rows = []) => {
        this.componentDragRows = rows
      })
      .exec()
    this.setData({
      draggingIndex: index,
      dragTargetIndex: index,
      componentDragStartY: clientY,
      componentDragStyle: buildComponentDragStyle(0),
      revealedComponentKey: ''
    })
  },

  handleComponentTouchMove(event) {
    if (this.data.draggingIndex < 0) {
      return
    }
    const clientY = getTouchClientY(event)
    const rows = this.componentDragRows || []
    if (clientY === null || rows.length === 0) {
      return
    }
    let targetIndex = rows.findIndex((row) => clientY < row.top + row.height / 2)
    if (targetIndex < 0) {
      targetIndex = rows.length - 1
    }
    if (targetIndex !== this.data.dragTargetIndex) {
      this.setData({ dragTargetIndex: targetIndex })
    }
    const startY = Number(this.data.componentDragStartY)
    const offsetY = Number.isFinite(startY) ? clientY - startY : 0
    this.setData({ componentDragStyle: buildComponentDragStyle(offsetY) })
  },

  handleComponentTouchEnd(event) {
    if (this.data.draggingIndex >= 0) {
      this.handleComponentDragEnd()
      return
    }
    const start = this.data.componentTouchStart
    if (!start || !start.key) {
      return
    }
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    const deltaX = (clientX === null ? start.x : clientX) - start.x
    const deltaY = Math.abs((clientY === null ? start.y : clientY) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedComponentKey: start.key,
        componentTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedComponentKey: '',
        componentTouchStart: null
      })
      return
    }
    this.setData({ componentTouchStart: null })
  },

  handleComponentTouchCancel() {
    if (this.data.draggingIndex >= 0) {
      this.handleComponentDragEnd()
      return
    }
    this.setData({ componentTouchStart: null })
  },

  handleComponentDragEnd() {
    const { draggingIndex, dragTargetIndex } = this.data
    const nextState = {
      draggingIndex: -1,
      dragTargetIndex: -1,
      componentDragStartY: null,
      componentDragStyle: '',
      componentTouchStart: null
    }
    if (draggingIndex >= 0 && dragTargetIndex >= 0 && draggingIndex !== dragTargetIndex) {
      nextState.config = reorderComponent(this.data.config, draggingIndex, dragTargetIndex)
    }
    this.componentDragRows = []
    this.setData(nextState)
  },

  handleComponentTap(event) {
    const componentKey = event.currentTarget.dataset.key || ''
    const componentType = event.currentTarget.dataset.type || ''
    if (this.data.revealedComponentKey === componentKey) {
      this.setData({ revealedComponentKey: '' })
      return undefined
    }
    if (!isEditableComponentType(componentType)) {
      return undefined
    }
    if (componentType === COMPONENT_TYPES.CAROUSEL) {
      return this.openComponentWorkSheet(componentKey, componentType)
    }
    if (componentType === COMPONENT_TYPES.PROFILE) {
      return this.openProfileSheet(componentKey)
    }
    if (componentType === COMPONENT_TYPES.QR_CONTACT) {
      return this.openQrContactSheet(componentKey)
    }
    if (componentType === COMPONENT_TYPES.SCHEDULE_QUERY) {
      return this.openScheduleQuerySheet(componentKey)
    }
    if (componentType === COMPONENT_TYPES.CONTACT_FORM) {
      return this.openContactFormSheet(componentKey)
    }
    if (componentType === COMPONENT_TYPES.TEXT_SECTION) {
      return this.openTextSectionSheet(componentKey)
    }
    if (componentType === COMPONENT_TYPES.DIVIDER) {
      return this.openDividerSheet(componentKey)
    }
    if (isDisplayGroupComponent(componentType)) {
      return this.openDisplayGroupSheet(componentKey, componentType)
    }
    return undefined
  },

  openScheduleQuerySheet(componentKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component || component.componentType !== COMPONENT_TYPES.SCHEDULE_QUERY) {
      return undefined
    }
    this.setData({
      scheduleQuerySheetVisible: true,
      scheduleQueryEditingComponentKey: componentKey,
      scheduleQueryForm: buildScheduleQueryForm(component.config || {})
    })
    return undefined
  },

  handleCloseScheduleQuerySheet() {
    this.setData({
      scheduleQuerySheetVisible: false,
      scheduleQueryEditingComponentKey: '',
      scheduleQueryForm: buildScheduleQueryForm()
    })
  },

  handleScheduleQueryDisplayModeTap(event) {
    const value = event.currentTarget.dataset.value
    this.setData({
      'scheduleQueryForm.displayMode': value
    })
  },

  handleConfirmScheduleQueryConfig() {
    const config = updateComponentScheduleQueryConfig(
      this.data.config,
      this.data.scheduleQueryEditingComponentKey,
      this.data.scheduleQueryForm
    )
    this.setData({
      config,
      scheduleQuerySheetVisible: false,
      scheduleQueryEditingComponentKey: '',
      scheduleQueryForm: buildScheduleQueryForm()
    })
  },

  openContactFormSheet(componentKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component || component.componentType !== COMPONENT_TYPES.CONTACT_FORM) {
      return undefined
    }
    this.setData({
      contactFormSheetVisible: true,
      contactFormEditingComponentKey: componentKey,
      contactFormConfigForm: buildContactFormConfigForm(component.config || {})
    })
    return undefined
  },

  handleCloseContactFormSheet() {
    this.setData({
      contactFormSheetVisible: false,
      contactFormEditingComponentKey: '',
      contactFormConfigForm: buildContactFormConfigForm()
    })
  },

  handleContactFormDisplayModeTap(event) {
    const value = event.currentTarget.dataset.value
    this.setData({
      'contactFormConfigForm.displayMode': value
    })
  },

  handleConfirmContactFormConfig() {
    const config = updateComponentContactFormConfig(
      this.data.config,
      this.data.contactFormEditingComponentKey,
      this.data.contactFormConfigForm
    )
    this.setData({
      config,
      contactFormSheetVisible: false,
      contactFormEditingComponentKey: '',
      contactFormConfigForm: buildContactFormConfigForm()
    })
  },

  openTextSectionSheet(componentKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component || component.componentType !== COMPONENT_TYPES.TEXT_SECTION) {
      return undefined
    }
    const textSectionForm = buildTextSectionForm(component.config || {})
    this.setData({
      textSectionSheetVisible: true,
      textSectionEditingComponentKey: componentKey,
      textSectionForm,
      textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm)
    })
    return undefined
  },

  handleCloseTextSectionSheet() {
    const textSectionForm = buildTextSectionForm()
    this.setData({
      textSectionSheetVisible: false,
      textSectionEditingComponentKey: '',
      textSectionForm,
      textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm)
    })
  },

  handleTextSectionInput(event) {
    const content = event.detail.value || ''
    const textSectionForm = Object.assign({}, this.data.textSectionForm, { content })
    this.setData({
      'textSectionForm.content': content,
      textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm)
    })
  },

  handleTextSectionAlignmentTap(event) {
    const value = event.currentTarget.dataset.value
    this.setData({
      'textSectionForm.alignment': value
    })
  },

  handleConfirmTextSectionConfig() {
    const form = buildTextSectionForm(this.data.textSectionForm)
    if (!form.content) {
      wx.showToast({ title: TEXT_SECTION_REQUIRED_MESSAGE, icon: 'none' })
      return
    }
    const config = updateComponentTextSectionConfig(
      this.data.config,
      this.data.textSectionEditingComponentKey,
      form
    )
    const textSectionForm = buildTextSectionForm()
    this.setData({
      config,
      textSectionSheetVisible: false,
      textSectionEditingComponentKey: '',
      textSectionForm,
      textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm)
    })
  },

  openDividerSheet(componentKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component || component.componentType !== COMPONENT_TYPES.DIVIDER) {
      return undefined
    }
    this.setData({
      dividerSheetVisible: true,
      dividerEditingComponentKey: componentKey,
      dividerForm: buildDividerForm(component.config || {})
    })
    return undefined
  },

  handleCloseDividerSheet() {
    this.setData({
      dividerSheetVisible: false,
      dividerEditingComponentKey: '',
      dividerForm: buildDividerForm()
    })
  },

  handleDividerColorTap(event) {
    const value = event.currentTarget.dataset.value
    this.setData({
      'dividerForm.color': value
    })
  },

  handleDividerHeightInput(event) {
    this.setData({
      'dividerForm.heightPx': event.detail.value || ''
    })
  },

  handleConfirmDividerConfig() {
    const heightPx = parseDividerHeightPx(this.data.dividerForm.heightPx)
    if (!heightPx) {
      wx.showToast({ title: DIVIDER_HEIGHT_REQUIRED_MESSAGE, icon: 'none' })
      return
    }
    const config = updateComponentDividerConfig(
      this.data.config,
      this.data.dividerEditingComponentKey,
      Object.assign({}, this.data.dividerForm, { heightPx })
    )
    this.setData({
      config,
      dividerSheetVisible: false,
      dividerEditingComponentKey: '',
      dividerForm: buildDividerForm()
    })
  },

  openDisplayGroupSheet(componentKey, componentType) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    this.setData({
      displayGroupSheetVisible: true,
      editingDisplayComponentKey: componentKey,
      editingDisplayComponentType: componentType,
      activeDisplayGroupKey: '',
      displayGroupOptions: [],
      activeDisplayGroupWorkCountText: '0 个已选',
      displayGroupWorkOptions: [],
      displayGroupAllWorks: [],
      displayGroupOriginalConfig: clonePlainObject(this.data.config),
      displayGroupErrorText: '',
      displayGroupLoading: true
    })
    return this.loadDisplayGroupCatalog(componentKey)
  },

  openProfileSheet(componentKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    const profileConfig = normalizeProfileComponentConfig(component.config || {})
    if (!hasProfileCopyValue(profileConfig.profile)) {
      return this.loadBasicProfileDefaults(this.data.config).then((config) => {
        const nextComponent = findComponentByKey(config, componentKey) || component
        this.showProfileSheet(componentKey, nextComponent)
      })
    }
    this.showProfileSheet(componentKey, component)
    return Promise.resolve()
  },

  showProfileSheet(componentKey, component) {
    const profileConfig = normalizeProfileComponentConfig(component.config || {})
    this.setData({
      profileSheetVisible: true,
      profileSheetLoading: false,
      profileSheetErrorText: '',
      editingProfileComponentKey: componentKey,
      profileForm: buildProfileForm(profileConfig.profile),
      profileFieldCounters: buildProfileFieldCounters(buildProfileForm(profileConfig.profile)),
      profileVisibleOptions: buildProfileVisibleOptions(profileConfig.visibleFields)
    })
  },

  handleCloseProfileSheet() {
    this.setData({
      profileSheetVisible: false,
      profileSheetLoading: false,
      profileSheetErrorText: '',
      editingProfileComponentKey: ''
    })
  },

  handleProfileInput(event) {
    const field = event.currentTarget.dataset.field || ''
    if (!field) {
      return
    }
    const nextForm = Object.assign({}, this.data.profileForm, {
      [field]: event.detail.value
    })
    this.setData({
      [`profileForm.${field}`]: event.detail.value,
      profileFieldCounters: buildProfileFieldCounters(nextForm)
    })
  },

  setProfileAvatarUrl(avatarUrl) {
    const nextForm = Object.assign({}, this.data.profileForm, { avatarUrl })
    this.setData({
      'profileForm.avatarUrl': avatarUrl,
      profileFieldCounters: buildProfileFieldCounters(nextForm)
    })
  },

  setProfileWechatQrUrl(wechatQrUrl) {
    const nextForm = Object.assign({}, this.data.profileForm, { wechatQrUrl })
    this.setData({
      'profileForm.wechatQrUrl': wechatQrUrl,
      profileFieldCounters: buildProfileFieldCounters(nextForm)
    })
  },

  handleChooseProfileAvatar() {
    wx.chooseMedia(Object.assign({}, createChoosePortfolioImageOptions(), {
      success: (response) => {
        const avatarPath = resolveChosenImagePath(response)
        if (avatarPath) {
          this.setProfileAvatarUrl(avatarPath)
        }
      },
      fail: (error) => {
        if (error && error.errMsg && !/cancel/i.test(error.errMsg)) {
          wx.showToast({ title: '选择头像失败', icon: 'none' })
        }
      }
    }))
  },

  handleChooseProfileWechatQr() {
    wx.chooseMedia(Object.assign({}, createChoosePortfolioImageOptions(), {
      success: (response) => {
        const qrPath = resolveChosenImagePath(response)
        if (qrPath) {
          this.setProfileWechatQrUrl(qrPath)
        }
      },
      fail: (error) => {
        if (error && error.errMsg && !/cancel/i.test(error.errMsg)) {
          wx.showToast({ title: '选择二维码失败', icon: 'none' })
        }
      }
    }))
  },

  handleProfileVisibleFieldChange(event) {
    const field = event.currentTarget.dataset.field || ''
    if (!field) {
      return
    }
    this.setData({
      profileVisibleOptions: this.data.profileVisibleOptions.map((item) => {
        if (item.field !== field) {
          return item
        }
        return Object.assign({}, item, { checked: Boolean(event.detail.value) })
      })
    })
  },

  handleRefreshProfileFromBase() {
    return new Promise((resolve) => {
      wx.showModal({
        title: '刷新基础资料',
        content: '将用当前基础信息覆盖作品集内个人资料副本，是否继续？',
        confirmText: '刷新',
        success: (response) => {
          if (!response.confirm) {
            resolve(false)
            return
          }
          resolve(this.refreshProfileFromBase())
        },
        fail: () => resolve(false)
      })
    })
  },

  refreshProfileFromBase() {
    this.setData({
      profileSheetLoading: true,
      profileSheetErrorText: ''
    })
    return request({ url: BASIC_PROFILE_API_URL })
      .then((response) => {
        const profileForm = buildProfileFormFromBasicProfile(response, this.data.profileForm)
        this.setData({
          profileSheetLoading: false,
          profileForm,
          profileFieldCounters: buildProfileFieldCounters(profileForm)
        })
      })
      .catch((error) => {
        if (error && error.authRequired) {
          this.setData({ profileSheetLoading: false })
          handleMaintainerAuthRequired(error.message)
          return
        }
        this.setData({
          profileSheetLoading: false,
          profileSheetErrorText: error && error.message ? error.message : BASIC_PROFILE_LOAD_ERROR_MESSAGE
        })
      })
  },

  handleConfirmProfileSheet() {
    if (!this.data.editingProfileComponentKey) {
      return
    }
    const profileConfig = buildProfileConfigFromForm(this.data.profileForm, this.data.profileVisibleOptions)
    this.setData({
      config: updateComponentProfileConfig(this.data.config, this.data.editingProfileComponentKey, profileConfig),
      profileSheetVisible: false,
      profileSheetLoading: false,
      profileSheetErrorText: '',
      editingProfileComponentKey: ''
    })
  },

  openQrContactSheet(componentKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    const qrContactForm = buildQrContactForm(component.config || {})
    this.setData({
      qrContactSheetVisible: true,
      editingQrContactComponentKey: componentKey,
      qrContactProfileQrUrl: '',
      qrContactForm
    })
    if (qrContactForm.qrUrlSource !== QR_CONTACT_SOURCE_CUSTOM) {
      return this.loadQrContactProfileQrUrl()
    }
    return Promise.resolve()
  },

  handleCloseQrContactSheet() {
    this.setData({
      qrContactSheetVisible: false,
      editingQrContactComponentKey: '',
      qrContactProfileQrUrl: '',
      qrContactForm: buildQrContactForm()
    })
  },

  handleUseProfileQrContact() {
    this.setData(Object.assign(
      buildQrContactSourcePatch(this.data.qrContactForm.qrUrlSource, QR_CONTACT_SOURCE_PROFILE),
      { qrContactProfileQrUrl: '' }
    ))
    return this.loadQrContactProfileQrUrl()
  },

  handleUseCustomQrContact() {
    this.setData(Object.assign(
      buildQrContactSourcePatch(this.data.qrContactForm.qrUrlSource, QR_CONTACT_SOURCE_CUSTOM),
      { qrContactProfileQrUrl: '' }
    ))
  },

  loadQrContactProfileQrUrl() {
    return request({ url: BASIC_PROFILE_API_URL })
      .then((response) => {
        const qrUrl = resolveBasicProfileQrContactQrUrl(response)
        this.setData({ qrContactProfileQrUrl: qrUrl })
        return qrUrl
      })
      .catch((error) => {
        this.setData({ qrContactProfileQrUrl: '' })
        if (error && error.authRequired) {
          handleMaintainerAuthRequired(error.message)
          return ''
        }
        wx.showToast({
          title: error && error.message ? error.message : BASIC_PROFILE_LOAD_ERROR_MESSAGE,
          icon: 'none'
        })
        return ''
      })
  },

  loadQrContactProfileQrUrlForSaving(config = this.data.config) {
    const normalizedConfig = normalizePortfolioConfig(config)
    const shouldLoadProfileQr = (normalizedConfig.components || []).some((component) => {
      if (!component || component.componentType !== COMPONENT_TYPES.QR_CONTACT) {
        return false
      }
      const componentConfig = component.config || {}
      return componentConfig.qrUrlSource !== QR_CONTACT_SOURCE_CUSTOM
    })
    if (!shouldLoadProfileQr) {
      return Promise.resolve('')
    }
    return request({ url: BASIC_PROFILE_API_URL })
      .then((response) => resolveBasicProfileQrContactQrUrl(response))
      .catch((error) => {
        if (error && error.authRequired) {
          handleMaintainerAuthRequired(error.message)
        }
        throw new Error(error && error.message ? error.message : BASIC_PROFILE_LOAD_ERROR_MESSAGE)
      })
  },

  setQrContactImageUrl(qrUrl) {
    this.setData({
      'qrContactForm.qrUrlSource': QR_CONTACT_SOURCE_CUSTOM,
      'qrContactForm.qrUrl': qrUrl
    })
  },

  handleChooseQrContactImage() {
    wx.chooseMedia(Object.assign({}, createChoosePortfolioImageOptions(), {
      success: (response) => {
        const qrPath = resolveChosenImagePath(response)
        if (qrPath) {
          this.setQrContactImageUrl(qrPath)
        }
      },
      fail: (error) => {
        if (error && error.errMsg && !/cancel/i.test(error.errMsg)) {
          wx.showToast({ title: '选择二维码失败', icon: 'none' })
        }
      }
    }))
  },

  handleConfirmQrContactSheet() {
    if (!this.data.editingQrContactComponentKey) {
      return
    }
    this.setData({
      config: updateComponentQrContactConfig(
        this.data.config,
        this.data.editingQrContactComponentKey,
        this.data.qrContactForm
      ),
      qrContactSheetVisible: false,
      editingQrContactComponentKey: '',
      qrContactProfileQrUrl: '',
      qrContactForm: buildQrContactForm()
    })
  },

  handleCloseDisplayGroupSheet() {
    const originalConfig = this.data.displayGroupOriginalConfig
    this.setData(Object.assign({
      displayGroupSheetVisible: false,
      editingDisplayComponentKey: '',
      editingDisplayComponentType: '',
      activeDisplayGroupKey: '',
      activeDisplayGroupWorkCountText: '0 个已选',
      displayGroupWorkOptions: [],
      displayGroupAllWorks: [],
      displayGroupOriginalConfig: null,
      displayGroupErrorText: '',
      displayGroupLoading: false
    }, originalConfig ? { config: originalConfig } : {}))
  },

  handleCancelDisplayGroupSheet() {
    this.handleCloseDisplayGroupSheet()
  },

  handleConfirmDisplayGroupSheet() {
    this.setData({
      displayGroupSheetVisible: false,
      editingDisplayComponentKey: '',
      editingDisplayComponentType: '',
      activeDisplayGroupKey: '',
      activeDisplayGroupWorkCountText: '0 个已选',
      displayGroupWorkOptions: [],
      displayGroupAllWorks: [],
      displayGroupOriginalConfig: null,
      displayGroupErrorText: '',
      displayGroupLoading: false
    })
  },

  refreshDisplayGroupOptions(componentKey = this.data.editingDisplayComponentKey, activeGroupKey = this.data.activeDisplayGroupKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    const tags = this.data.workTagOptions
    const works = this.data.displayGroupAllWorks
    this.setData({
      displayGroupOptions: buildDisplayGroupOptions(component || {}, activeGroupKey, tags),
      activeDisplayGroupWorkCountText: buildActiveDisplayGroupWorkCountText(component || {}, activeGroupKey, tags),
      displayGroupWorkOptions: buildDisplayGroupWorkOptions(component || {}, activeGroupKey, tags, works)
    })
  },

  async loadAllDisplayGroupWorks(page = 1, collectedWorks = []) {
    const response = await request({
      url: WORKS_API_URL,
      data: {
        auditStatus: PASSED_WORK_AUDIT_STATUS,
        page,
        pageSize: DISPLAY_GROUP_WORK_PAGE_SIZE
      }
    })
    const list = normalizeWorkList(response)
    const works = collectedWorks.concat(list.works)
    if (list.hasMore) {
      return this.loadAllDisplayGroupWorks((list.page || page) + 1, works)
    }
    return works
  },

  async loadDisplayGroupCatalog(componentKey) {
    try {
      const tagResponse = await request({ url: `${WORKS_API_URL}/tags` })
      const tags = normalizeWorkTags(tagResponse)
      const works = await this.loadAllDisplayGroupWorks()
      const currentComponent = findComponentByKey(this.data.config, componentKey)
      const selectedGroups = buildSelectedDisplayGroups(currentComponent || {}, tags)
      const config = replaceDisplayComponentGroups(this.data.config, componentKey, selectedGroups)
      const component = findComponentByKey(config, componentKey)
      const activeGroupKey = resolveActiveDisplayGroupKey(component || {}, tags, this.data.activeDisplayGroupKey)
      const displayGroupWorkMap = mergeDisplayGroupWorkMap(this.data.displayGroupWorkMap, works)
      this.setData({
        config,
        workTagOptions: tags,
        displayGroupAllWorks: works,
        displayGroupWorkMap,
        activeDisplayGroupKey: activeGroupKey,
        displayGroupOptions: buildDisplayGroupOptions(component || {}, activeGroupKey, tags),
        activeDisplayGroupWorkCountText: buildActiveDisplayGroupWorkCountText(component || {}, activeGroupKey, tags),
        displayGroupWorkOptions: buildDisplayGroupWorkOptions(component || {}, activeGroupKey, tags, works),
        displayGroupLoading: false,
        displayGroupErrorText: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ displayGroupLoading: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        displayGroupLoading: false,
        displayGroupErrorText: error && error.message ? error.message : '作品标签和作品加载失败'
      })
    }
  },

  handleSelectDisplayGroup(event) {
    const groupKey = event.currentTarget.dataset.groupKey || ''
    if (!groupKey) {
      return
    }
    this.setData({ activeDisplayGroupKey: groupKey })
    this.refreshDisplayGroupOptions(this.data.editingDisplayComponentKey, groupKey)
  },

  handleToggleDisplayGroupTag(event) {
    const tagId = Number(event.currentTarget.dataset.tagId)
    const tag = this.data.workTagOptions.find((item) => item.id === tagId)
    const componentKey = this.data.editingDisplayComponentKey
    const component = findComponentByKey(this.data.config, componentKey)
    if (!tag || !component) {
      return
    }
    const groupKey = buildDisplayGroupTagKey(tag.id)
    const selectedGroups = buildSelectedDisplayGroups(component, this.data.workTagOptions)
    const existingIndex = selectedGroups.findIndex((group) => group.groupKey === groupKey)
    const nextGroups = existingIndex >= 0
      ? selectedGroups.filter((group) => group.groupKey !== groupKey)
      : selectedGroups.concat({
          groupKey,
          name: tag.name,
          sortOrder: (selectedGroups.length + 1) * DISPLAY_GROUP_SORT_ORDER_STEP,
          workIds: []
        })
    const config = replaceDisplayComponentGroups(this.data.config, componentKey, nextGroups)
    this.setData({
      config,
      activeDisplayGroupKey: groupKey,
      displayGroupErrorText: ''
    })
    this.refreshDisplayGroupOptions(componentKey, groupKey)
  },

  handleToggleDisplayGroupWork(event) {
    const workId = Number(event.currentTarget.dataset.id)
    const componentKey = this.data.editingDisplayComponentKey
    const component = findComponentByKey(this.data.config, componentKey)
    const tag = findWorkTagByGroupKey(this.data.workTagOptions, this.data.activeDisplayGroupKey)
    if (!component || !tag || !Number.isFinite(workId) || workId <= 0) {
      return
    }
    const groupKey = buildDisplayGroupTagKey(tag.id)
    const selectedGroups = buildSelectedDisplayGroups(component, this.data.workTagOptions)
    const existingGroup = selectedGroups.find((group) => group.groupKey === groupKey)
    const sourceWorkIds = normalizeWorkIds(existingGroup && existingGroup.workIds)
    const nextWorkIds = sourceWorkIds.includes(workId)
      ? sourceWorkIds.filter((id) => id !== workId)
      : sourceWorkIds.concat(workId)
    const nextGroup = {
      groupKey,
      name: tag.name,
      sortOrder: existingGroup ? existingGroup.sortOrder : (selectedGroups.length + 1) * DISPLAY_GROUP_SORT_ORDER_STEP,
      workIds: nextWorkIds
    }
    const nextGroups = existingGroup
      ? selectedGroups.map((group) => group.groupKey === groupKey ? nextGroup : group)
      : selectedGroups.concat(nextGroup)
    const config = replaceDisplayComponentGroups(this.data.config, componentKey, nextGroups)
    this.setData({
      config,
      activeDisplayGroupKey: groupKey,
      displayGroupErrorText: ''
    })
    this.refreshDisplayGroupOptions(componentKey, groupKey)
  },

  handleRemoveComponent(event) {
    this.setData({
      config: removeComponent(this.data.config, event.currentTarget.dataset.key),
      revealedComponentKey: ''
    })
  },

  openComponentWorkSheet(componentKey, componentType, options = {}) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    const selectedIds = normalizeWorkIds(
      Object.prototype.hasOwnProperty.call(options, 'selectedIds')
        ? options.selectedIds
        : component.config && component.config.workIds
    )
    this.setData({
      componentWorkSheetVisible: true,
      componentWorkSheetTitle: options.title || (componentType === COMPONENT_TYPES.CAROUSEL ? '编辑轮播作品' : '编辑展示作品'),
      componentWorkLoading: true,
      componentWorkLoadingMore: false,
      componentWorkErrorText: '',
      componentWorkEmptyText: componentType === COMPONENT_TYPES.CAROUSEL ? '暂无图片作品' : '暂无作品',
      componentWorkOptions: [],
      componentWorkFilterTags: [],
      componentWorkKeyword: '',
      componentWorkSelectedTagId: null,
      componentWorkPage: 1,
      componentWorkPageSize: COMPONENT_WORK_PAGE_SIZE,
      componentWorkHasMore: false,
      componentWorkSelectedIds: selectedIds,
      componentWorkSelectedCountText: buildSelectedCountText(selectedIds),
      editingComponentKey: componentKey,
      editingComponentType: componentType
    })
    return this.loadComponentWorks({ reset: true, componentType, selectedIds })
  },

  async loadComponentWorks(options = {}) {
    const reset = options.reset !== false
    const componentType = options.componentType || this.data.editingComponentType
    const selectedIds = options.selectedIds || this.data.componentWorkSelectedIds
    const pageSize = Number(this.data.componentWorkPageSize) || COMPONENT_WORK_PAGE_SIZE
    const nextPage = reset ? 1 : (Number(this.data.componentWorkPage) || 1) + 1
    const keyword = String(this.data.componentWorkKeyword || '').trim()
    const tagId = normalizeComponentWorkTagId(this.data.componentWorkSelectedTagId)
    const mediaType = componentType === COMPONENT_TYPES.CAROUSEL ? 'IMAGE' : ''
    const requestSeq = this.componentWorkRequestSeq + 1
    this.componentWorkRequestSeq = requestSeq
    this.setData(reset ? {
      componentWorkLoading: true,
      componentWorkLoadingMore: false,
      componentWorkErrorText: '',
      componentWorkOptions: []
    } : {
      componentWorkLoadingMore: true,
      componentWorkErrorText: ''
    })
    try {
      const response = await request({
        url: WORKS_API_URL,
        data: {
          keyword,
          tagId: tagId || undefined,
          mediaType: mediaType || undefined,
          auditStatus: PASSED_WORK_AUDIT_STATUS,
          page: nextPage,
          pageSize
        }
      })
      if (requestSeq !== this.componentWorkRequestSeq) {
        return
      }
      const list = normalizeWorkList(response)
      const works = reset ? list.works : mergeComponentWorks(this.data.componentWorkOptions, list.works)
      const displayGroupWorkMap = mergeDisplayGroupWorkMap(this.data.displayGroupWorkMap, works)
      this.setData({
        componentWorkLoading: false,
        componentWorkLoadingMore: false,
        componentWorkErrorText: '',
        componentWorkOptions: buildComponentWorkOptions(works, selectedIds, componentType),
        componentWorkFilterTags: list.filterTags,
        componentWorkPage: list.page,
        componentWorkPageSize: list.pageSize || pageSize,
        componentWorkHasMore: list.hasMore,
        displayGroupWorkMap
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({
          componentWorkLoading: false,
          componentWorkLoadingMore: false
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        componentWorkLoading: false,
        componentWorkLoadingMore: false,
        componentWorkErrorText: error && error.message ? error.message : '作品加载失败'
      })
    }
  },

  handleRetryLoadComponentWorks() {
    this.setData({
      componentWorkLoading: true,
      componentWorkLoadingMore: false,
      componentWorkErrorText: ''
    })
    return this.loadComponentWorks({ reset: true })
  },

  handleComponentWorkKeywordInput(event) {
    this.setData({
      componentWorkKeyword: event.detail.value || ''
    })
  },

  handleComponentWorkSearchConfirm() {
    return this.loadComponentWorks({ reset: true })
  },

  handleClearComponentWorkSearch() {
    if (!this.data.componentWorkKeyword) {
      return Promise.resolve()
    }
    this.setData({
      componentWorkKeyword: ''
    })
    return this.loadComponentWorks({ reset: true })
  },

  handleComponentWorkTagTap(event) {
    const tagId = normalizeComponentWorkTagId(event.currentTarget.dataset.tagId)
    if ((this.data.componentWorkSelectedTagId || null) === tagId) {
      return Promise.resolve()
    }
    this.setData({
      componentWorkSelectedTagId: tagId
    })
    return this.loadComponentWorks({ reset: true })
  },

  handleComponentWorkScrollToLower() {
    if (this.data.componentWorkLoading || this.data.componentWorkLoadingMore || !this.data.componentWorkHasMore) {
      return Promise.resolve()
    }
    return this.loadComponentWorks({ reset: false })
  },

  handleCloseComponentWorkSheet() {
    this.setData({
      componentWorkSheetVisible: false,
      editingComponentKey: '',
      editingComponentType: '',
      componentWorkErrorText: '',
      componentWorkLoadingMore: false
    })
  },

  handleToggleComponentWork(event) {
    const workId = Number(event.currentTarget.dataset.id)
    if (!Number.isFinite(workId) || workId <= 0) {
      return
    }
    const selectedIds = normalizeWorkIds(this.data.componentWorkSelectedIds)
    const nextSelectedIds = selectedIds.includes(workId)
      ? selectedIds.filter((id) => id !== workId)
      : selectedIds.concat(workId)
    this.setData({
      componentWorkSelectedIds: nextSelectedIds,
      componentWorkSelectedCountText: buildSelectedCountText(nextSelectedIds),
      componentWorkOptions: buildComponentWorkOptions(this.data.componentWorkOptions, nextSelectedIds, this.data.editingComponentType)
    })
  },

  handleConfirmComponentWorks() {
    if (!this.data.editingComponentKey) {
      return
    }
    const componentKey = this.data.editingComponentKey
    const config = updateComponentWorkIds(this.data.config, componentKey, this.data.componentWorkSelectedIds)
    const displayGroupWorkMap = mergeDisplayGroupWorkMap(this.data.displayGroupWorkMap, this.data.componentWorkOptions)
    this.setData({
      config,
      displayGroupWorkMap,
      componentWorkSheetVisible: false,
      editingComponentKey: '',
      editingComponentType: ''
    })
  },

  ensureDraftPortfolio() {
    if (this.data.portfolioId) {
      return Promise.resolve(this.data.portfolioId)
    }
    return request({
      url: STANDARD_PERSONAL_API_URL,
      method: 'POST',
      data: {
        config: buildServerSafePortfolioConfig(this.data.config)
      }
    }).then((response = {}) => {
      const portfolioId = response.portfolioId
      if (!portfolioId) {
        throw new Error('创建失败')
      }
      this.setData(Object.assign({
        portfolioId,
        draftRevision: response.draftRevision || 0,
        publishedRevision: response.publishedRevision || 0
      }, buildPublicationStatusState(response.publicationStatus)))
      return portfolioId
    })
  },

  saveDraftForPortfolio(portfolioId, config = this.data.config, options = {}) {
    return request({
      url: `${PORTFOLIO_API_PREFIX}/${portfolioId}/draft`,
      method: 'PUT',
      data: buildDraftPayload(config, this.data.draftRevision, makeIdempotencyKey(IDEMPOTENCY_PREFIX_DRAFT))
    }).then((response = {}) => {
      this.setData(Object.assign({
        portfolioId: response.portfolioId || portfolioId,
        draftRevision: response.draftRevision || this.data.draftRevision,
        publishedRevision: response.publishedRevision || this.data.publishedRevision
      }, buildPublicationStatusState(response.publicationStatus || this.data.publicationStatus)))
      if (options.showToast !== false) {
        wx.showToast({ title: '草稿已保存', icon: 'success' })
      }
      if (options.returnToList !== false) {
        this.returnToPortfolioList()
      }
      return response
    })
  },

  uploadLocalShareCover(portfolioId, config = this.data.config) {
    const nextConfig = normalizePortfolioConfig(config)
    const coverUrl = nextConfig.share && nextConfig.share.coverUrl
    if (!coverUrl) {
      return Promise.resolve(nextConfig)
    }
    return uploadPortfolioImageAsset(portfolioId, coverUrl, {
      assetType: PORTFOLIO_ASSET_TYPES.COVER,
      assetLabel: '封面图片',
      clientIdPrefix: 'cover'
    }).then((uploadedUrl) => {
      if (uploadedUrl && uploadedUrl !== coverUrl) {
        return updateShareCoverUrlInConfig(nextConfig, uploadedUrl)
      }
      return nextConfig
    })
  },

  uploadLocalProfileImages(portfolioId, config = this.data.config) {
    let nextConfig = normalizePortfolioConfig(config)
    const profileComponents = (nextConfig.components || [])
      .filter((component) => component.componentType === COMPONENT_TYPES.PROFILE)

    return profileComponents.reduce((chain, component) => {
      return chain.then(() => {
        const currentComponent = findComponentByKey(nextConfig, component.componentKey) || component
        let profileConfig = normalizeProfileComponentConfig(currentComponent.config || {})
        const avatarUrl = profileConfig.profile.avatarUrl

        const uploadAvatar = avatarUrl
          ? uploadPortfolioImageAsset(portfolioId, avatarUrl, {
            assetType: PORTFOLIO_ASSET_TYPES.PROFILE_AVATAR,
            assetLabel: '头像图片',
            clientIdPrefix: 'profile-avatar'
          }).then((uploadedUrl) => {
            if (uploadedUrl && uploadedUrl !== avatarUrl) {
              profileConfig = normalizeProfileComponentConfig(Object.assign({}, profileConfig, {
                profile: Object.assign({}, profileConfig.profile, { avatarUrl: uploadedUrl })
              }))
              nextConfig = updateComponentProfileConfig(nextConfig, component.componentKey, profileConfig)
            }
            return uploadedUrl || avatarUrl
          })
          : Promise.resolve('')

        return uploadAvatar.then(() => {
          const wechatQrUrl = profileConfig.profile.wechatQrUrl
          if (!wechatQrUrl) {
            return ''
          }
          return uploadPortfolioImageAsset(portfolioId, wechatQrUrl, {
            assetType: PORTFOLIO_ASSET_TYPES.QR_CONTACT,
            assetLabel: '微信二维码图片',
            clientIdPrefix: 'profile-wechat-qr'
          }).then((uploadedUrl) => {
            if (uploadedUrl && uploadedUrl !== wechatQrUrl) {
              const nextProfileConfig = normalizeProfileComponentConfig(Object.assign({}, profileConfig, {
                profile: Object.assign({}, profileConfig.profile, { wechatQrUrl: uploadedUrl })
              }))
              nextConfig = updateComponentProfileConfig(nextConfig, component.componentKey, nextProfileConfig)
            }
            return uploadedUrl || wechatQrUrl
          })
        })
      })
    }, Promise.resolve('')).then(() => nextConfig)
  },

  uploadLocalQrContactImages(portfolioId, config = this.data.config) {
    let nextConfig = normalizePortfolioConfig(config)
    const qrContactComponents = (nextConfig.components || [])
      .filter((component) => component.componentType === COMPONENT_TYPES.QR_CONTACT)

    return this.loadQrContactProfileQrUrlForSaving(nextConfig).then((profileQrUrl) => {
      return qrContactComponents.reduce((chain, component) => {
        return chain.then(() => {
          const currentComponent = findComponentByKey(nextConfig, component.componentKey) || component
          const componentConfig = currentComponent.config || {}
          const qrUrlSource = componentConfig.qrUrlSource || QR_CONTACT_SOURCE_PROFILE
          const qrUrl = componentConfig.qrUrl || ''
          if (qrUrlSource !== QR_CONTACT_SOURCE_CUSTOM) {
            nextConfig = updateComponentQrContactConfig(nextConfig, component.componentKey, Object.assign({}, componentConfig, {
              qrUrl: profileQrUrl,
              qrUrlSource: QR_CONTACT_SOURCE_PROFILE
            }))
            return profileQrUrl
          }
          if (!qrUrl) {
            return ''
          }
          return uploadPortfolioImageAsset(portfolioId, qrUrl, {
            assetType: PORTFOLIO_ASSET_TYPES.QR_CONTACT,
            assetLabel: '二维码图片',
            clientIdPrefix: 'qr-contact'
          }).then((uploadedUrl) => {
            if (uploadedUrl && uploadedUrl !== qrUrl) {
              nextConfig = updateComponentQrContactConfig(nextConfig, component.componentKey, Object.assign({}, componentConfig, {
                qrUrl: uploadedUrl,
                qrUrlSource: QR_CONTACT_SOURCE_CUSTOM
              }))
            }
            return uploadedUrl || qrUrl
          })
        })
      }, Promise.resolve('')).then(() => nextConfig)
    })
  },

  uploadLocalPortfolioAssets(portfolioId) {
    return this.uploadLocalShareCover(portfolioId, this.data.config)
      .then((config) => this.uploadLocalProfileImages(portfolioId, config))
      .then((config) => this.uploadLocalQrContactImages(portfolioId, config))
      .then((config) => {
        this.setData({
          config,
          shareFieldCounters: buildShareFieldCounters(config.share)
        })
        return config
      })
  },

  handleSaveDraft() {
    return this.ensureDraftPortfolio().then((portfolioId) => {
      return this.uploadLocalPortfolioAssets(portfolioId)
        .then((config) => this.saveDraftForPortfolio(portfolioId, config))
    }).catch((error) => {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    })
  },

  handlePublish() {
    return confirmPortfolioPublishDisclaimer().then((confirmed) => {
      if (!confirmed) {
        return
      }
      return this.ensureDraftPortfolio().then((portfolioId) => {
        return this.uploadLocalPortfolioAssets(portfolioId)
          .then((config) => this.saveDraftForPortfolio(portfolioId, config, {
            showToast: false,
            returnToList: false
          }))
          .then(() => request({
            url: `${PORTFOLIO_API_PREFIX}/${portfolioId}/publish`,
            method: 'POST',
            data: buildPublishPayload(this.data.draftRevision, makeIdempotencyKey(IDEMPOTENCY_PREFIX_PUBLISH))
          }))
          .then((response = {}) => {
            this.setData(Object.assign({
              portfolioId: response.portfolioId || portfolioId,
              draftRevision: response.draftRevision || this.data.draftRevision,
              publishedRevision: response.publishedRevision || this.data.publishedRevision
            }, buildPublicationStatusState(response.publicationStatus || PUBLICATION_STATUS_PUBLISHED)))
            wx.showToast({ title: '已发布', icon: 'success' })
            this.returnToPortfolioList()
          })
      })
    }).catch((error) => {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      wx.showToast({ title: error && error.message ? error.message : '发布失败', icon: 'none' })
    })
  },

  returnToPortfolioList() {
    const delta = resolvePortfolioListBackDelta()
    if (delta > 0) {
      wx.navigateBack({ delta })
      return
    }
    wx.redirectTo({ url: PORTFOLIOS_PAGE_URL })
  },

  handlePreview() {
    if (!this.data.portfolioId) {
      return
    }
    wx.navigateTo({ url: `/pages/portfolios/standard-preview/portfolio-standard-preview?portfolioId=${this.data.portfolioId}` })
  }
})
