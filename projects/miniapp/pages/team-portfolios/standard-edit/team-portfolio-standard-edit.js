const { request } = require('../../../utils/request.js')
const { isRemoteUrl } = require('../../../utils/upload-file.js')
const {
  WECHAT_QR_CROP_FILE_TYPE,
  WECHAT_QR_CROP_OUTPUT_WIDTH,
  WECHAT_QR_CROP_QUALITY,
  buildWechatQrCropFrame,
  buildWechatQrCropState,
  cropWechatQrToTempFilePath,
  getWechatQrImageInfo,
  moveWechatQrCropState
} = require('../../../utils/profile-assets.js')
const { uploadTeamPortfolioAsset } = require('../utils/team-portfolio-assets.js')
const {
  hexToHsv,
  hsvToHex,
  normalizeTeamHexColor
} = require('../utils/team-portfolio-color.js')
const {
  createStandardTeamPortfolio,
  fetchTeamPortfolioDetail,
  findTeamPortfolioComponent,
  getTeamMenuComponentList,
  handleTeamMaintainerAuthError,
  moveTeamComponent,
  normalizeTeamPortfolioConfig,
  normalizeTeamVideoCarouselConfig,
  publishTeamPortfolio,
  removeTeamNavigationItem,
  renameTeamNavigationItem,
  replaceTeamMenuComponentList,
  saveTeamPortfolioDraft,
  setTeamBottomNavigationCount,
  showTeamPortfolioUnavailableToast,
  validateTeamPortfolioForPublish,
  visitTeamPortfolioComponents
} = require('../utils/team-portfolios.js')
const { confirmPortfolioPublishDisclaimer } = require('../utils/portfolio-publish-disclaimer.js')
const { formatDuration } = require('../utils/video-carousel.js')
const {
  LEGACY_TEAM_FONT_SIZE_RPX,
  NEW_COMPONENT_FONT_SIZE_RPX,
  PORTFOLIO_TEXT_FONT_FAMILIES,
  PORTFOLIO_TEXT_FONT_OPTIONS,
  buildPortfolioTextFontSizeOptions,
  buildPortfolioTextTypography
} = require('../../../utils/portfolio-text-typography.js')
const {
  getPortfolioFontCapability,
  isPortfolioFontAvailable,
  loadPortfolioFonts
} = require('../../../utils/portfolio-font-loader.js')

const TYPE_BUCKETS = Object.freeze({ TEAM_PROFILE: 'teamProfile', CAROUSEL: 'carousel', VIDEO_CAROUSEL: 'videoCarousel', SINGLE_WORK: 'singleWork', DIVIDER: 'divider', MEMBER_PORTFOLIO_GRID: 'grid', MEMBER_PORTFOLIO_LIST: 'list', TEXT_SECTION: 'text', SCHEDULE_QUERY: 'schedule', CONTACT_FORM: 'contact', QR_CONTACT: 'qr' })
const COMPONENT_NAMES = Object.freeze({ TEAM_PROFILE: '团队资料', CAROUSEL: '轮播图', VIDEO_CAROUSEL: '视频轮播', SINGLE_WORK: '单个作品', DIVIDER: '分割线', MEMBER_PORTFOLIO_GRID: '双列作品集', MEMBER_PORTFOLIO_LIST: '单列作品集', TEXT_SECTION: '文字说明', SCHEDULE_QUERY: '档期查询', CONTACT_FORM: '预留联系信息', QR_CONTACT: '二维码联系' })
const COMPONENT_DESCRIPTIONS = Object.freeze({ TEAM_PROFILE: '展示团队头像、名称和简介', CAROUSEL: '轮播展示成员的图片作品', VIDEO_CAROUSEL: '叠放循环展示视频作品，访客左右滑动浏览、点击播放', SINGLE_WORK: '展示一个成员图片、视频或动图作品', DIVIDER: '分隔不同内容区块', MEMBER_PORTFOLIO_GRID: '双列展示成员已发布作品集', MEMBER_PORTFOLIO_LIST: '单列展示成员已发布作品集', TEXT_SECTION: '添加团队服务说明文字', SCHEDULE_QUERY: '开放访客查询团队档期', CONTACT_FORM: '收集访客预留联系信息', QR_CONTACT: '展示团队二维码联系方式' })
const COMPONENT_TYPES = Object.keys(COMPONENT_NAMES)
const PORTFOLIO_REQUIRED_COMPONENT_TYPES = Object.freeze(['CAROUSEL', 'VIDEO_CAROUSEL', 'MEMBER_PORTFOLIO_GRID', 'MEMBER_PORTFOLIO_LIST'])
const MEMBER_PORTFOLIO_COMPONENT_TYPES = Object.freeze(['MEMBER_PORTFOLIO_GRID', 'MEMBER_PORTFOLIO_LIST'])
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const DRAG_ROW_FALLBACK_HEIGHT = 96
const COMPONENT_DRAG_SCALE = 1.015
const TEAM_PORTFOLIO_COVER_ASSET_TYPE = 'COVER'
const TEAM_PROFILE_AVATAR_ASSET_TYPE = 'TEAM_PROFILE_AVATAR'
const TEAM_QR_CONTACT_ASSET_TYPE = 'QR_CONTACT'
const TEAM_QR_CONTACT_SOURCE_CUSTOM = 'CUSTOM'
const DESIGN_VIEWPORT_RPX = 750
const QR_CONTACT_CROP_MAX_WIDTH_RPX = 560
const QR_CONTACT_CROP_HORIZONTAL_GUTTER_RPX = 116
const QR_CONTACT_CROP_CANVAS_ID = 'teamQrContactCropCanvas'
const CAROUSEL_EDITOR_SCROLL_MIN_HEIGHT_RPX = 460
const CAROUSEL_EDITOR_SCROLL_MAX_HEIGHT_RPX = 926
const CAROUSEL_EDITOR_PANEL_CHROME_HEIGHT_RPX = 250

/**
 * 将轮播图子组件上报的内容高度限制在弹层可用范围内，并同步生成内外两层样式。
 */
function buildCarouselEditorLayoutState(scrollHeightRpx) {
  const requestedHeight = Number(scrollHeightRpx)
  const safeHeight = Number.isFinite(requestedHeight) ? requestedHeight : CAROUSEL_EDITOR_SCROLL_MIN_HEIGHT_RPX
  const height = Math.max(
    CAROUSEL_EDITOR_SCROLL_MIN_HEIGHT_RPX,
    Math.min(CAROUSEL_EDITOR_SCROLL_MAX_HEIGHT_RPX, Math.round(safeHeight))
  )
  return {
    carouselEditorScrollStyle: `height: ${height}rpx;`,
    carouselEditorPanelStyle: `height: calc(${height + CAROUSEL_EDITOR_PANEL_CHROME_HEIGHT_RPX}rpx + env(safe-area-inset-bottom));`
  }
}
const QR_CONTACT_CROP_FAILED_MESSAGE = '二维码裁剪失败，请重试'
const PORTFOLIO_LIST_ROUTE_SUFFIX = '/portfolios/portfolios'
const TEAM_PORTFOLIOS_COMPAT_PAGE_URL = '/pages/team-portfolios/portfolios'
const TEXT_SECTION_MAX_LENGTH = 200
const TEXT_SECTION_REQUIRED_MESSAGE = '请填写文字说明'
const TEAM_PORTFOLIO_TITLE_REQUIRED_MESSAGE = '请填写团队作品集标题'
const SINGLE_WORK_PAGE_SIZE = 20
const TEAM_VIDEO_PAGE_SIZE = 20
const TEAM_VIDEO_MIN_ITEMS = 3
const TEAM_VIDEO_MAX_ITEMS = 8
const SINGLE_WORK_ROW_SUMMARY_STATUS = Object.freeze({
  LOADING: 'LOADING',
  FAILED: 'FAILED',
  UNAVAILABLE: 'UNAVAILABLE'
})
const TEAM_BACKGROUND_COLORS = Object.freeze(['#151515', '#FFFFFF', '#F5F6F8'])
const TEAM_BOTTOM_NAV_COUNTS = Object.freeze([1, 2, 3, 4])
const TEXT_SECTION_ALIGNMENTS = Object.freeze({ LEFT: 'LEFT', CENTER: 'CENTER', RIGHT: 'RIGHT' })
const CONTACT_FORM_DISPLAY_MODES = Object.freeze({ MODAL_FORM: 'MODAL_FORM', INLINE_FORM: 'INLINE_FORM' })
const CONTACT_FORM_DISPLAY_MODE_OPTIONS = Object.freeze([
  { value: CONTACT_FORM_DISPLAY_MODES.MODAL_FORM, label: '弹窗展示' },
  { value: CONTACT_FORM_DISPLAY_MODES.INLINE_FORM, label: '页面内展示' }
])
const SCHEDULE_QUERY_DISPLAY_MODES = Object.freeze({ MODAL_CALENDAR: 'MODAL_CALENDAR', INLINE_CALENDAR: 'INLINE_CALENDAR' })
const SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS = Object.freeze([
  { value: SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR, label: '弹层日历' },
  { value: SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR, label: '页面内日历' }
])
const DIVIDER_COLOR_OPTIONS = Object.freeze([
  { value: 'BLACK', label: '黑色', colorValue: '#17202a' },
  { value: 'WHITE', label: '白色', colorValue: '#ffffff' },
  { value: 'GRAY', label: '灰色', colorValue: '#d7dfe1' },
  { value: 'TRANSPARENT', label: '透明', colorValue: 'transparent' }
])
const TEXT_SECTION_ALIGNMENT_OPTIONS = Object.freeze([
  { value: TEXT_SECTION_ALIGNMENTS.LEFT, label: '左对齐' },
  { value: TEXT_SECTION_ALIGNMENTS.CENTER, label: '居中' },
  { value: TEXT_SECTION_ALIGNMENTS.RIGHT, label: '右对齐' }
])

function buildComponentOptions(components = []) {
  const existingTypes = (Array.isArray(components) ? components : []).map((item) => item.componentType)
  return COMPONENT_TYPES.map((componentType) => ({ componentType, name: COMPONENT_NAMES[componentType], description: COMPONENT_DESCRIPTIONS[componentType], disabled: componentType === 'TEAM_PROFILE' && existingTypes.includes(componentType) }))
}
function buildTeamBackgroundColorPickerState(backgroundColorHsv = {}) {
  const normalizedHsv = {
    hue: Math.min(359, Math.max(0, Number(backgroundColorHsv.hue) || 0)),
    saturation: Math.min(1, Math.max(0, Number(backgroundColorHsv.saturation) || 0)),
    value: Math.min(1, Math.max(0, Number(backgroundColorHsv.value) || 0))
  }
  return {
    backgroundColorHsv: normalizedHsv,
    backgroundColorDraft: hsvToHex(normalizedHsv),
    backgroundHueColor: hsvToHex({
      hue: normalizedHsv.hue,
      saturation: 1,
      value: 1
    }),
    backgroundColorPadDotStyle: [
      `left: ${Math.round(normalizedHsv.saturation * 100)}%`,
      `top: ${Math.round((1 - normalizedHsv.value) * 100)}%`
    ].join('; ')
  }
}
function resolveSingleWorkRowSummary(component = {}, summaryMap = {}) {
  const config = component.config || {}
  const workId = Number(config.workId)
  if (!workId) return '请选择作品'
  const summary = summaryMap[component.componentKey]
  if (!summary || summary.status === SINGLE_WORK_ROW_SUMMARY_STATUS.LOADING) return '作品信息加载中'
  if (summary.status === SINGLE_WORK_ROW_SUMMARY_STATUS.FAILED) return '作品信息加载失败'
  if (summary.status === SINGLE_WORK_ROW_SUMMARY_STATUS.UNAVAILABLE) return '作品不可用，请重新选择'
  return String(summary.title || '').trim() || '作品不可用，请重新选择'
}
function buildComponentList(components = [], singleWorkRowSummaryMap = {}) {
  return (Array.isArray(components) ? components : []).map((item) => Object.assign({}, item, {
    displayName: COMPONENT_NAMES[item.componentType] || item.componentType || '页面组件'
  }, item.componentType === 'SINGLE_WORK'
    ? { showSummary: true, summaryText: resolveSingleWorkRowSummary(item, singleWorkRowSummaryMap) }
    : {}))
}
function buildPublicationState(status) {
  if (status === 'PUBLISHED' || status === 'PUBLISHED_WITH_DRAFT') return { statusText: status === 'PUBLISHED_WITH_DRAFT' ? '有新草稿' : '已发布', statusTone: 'published', showPublishAction: true }
  if (status === 'OFFLINE') return { statusText: '已下线', statusTone: 'muted', showPublishAction: true }
  return { statusText: '草稿', statusTone: 'draft', showPublishAction: false }
}
function touchPoint(event = {}) { const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0]) || {}; return { x: Number(touch.clientX) || 0, y: Number(touch.clientY) || 0 } }
function dragStyle(offsetY) { return `transform: translate3d(0, ${Math.round(Number(offsetY) || 0)}px, 0) scale(${COMPONENT_DRAG_SCALE}); transition: transform 80ms linear, box-shadow 160ms ease; z-index: 3;` }
function normalizeSortOrders(components = []) { return components.map((item, index) => Object.assign({}, item, { sortOrder: (index + 1) * 1000 })) }
function mergeSingleWorkPage(existing = [], incoming = []) {
  const seen = new Set()
  return (Array.isArray(existing) ? existing : [])
    .concat(Array.isArray(incoming) ? incoming : [])
    .filter((item) => {
      const workId = Number(item && item.workId)
      if (!workId || seen.has(workId)) return false
      seen.add(workId)
      return true
    })
}
function teamVideoItemKey(item = {}) { return `${Number(item.memberUserId) || 0}:${Number(item.workId) || 0}` }
function normalizeTeamVideoCandidate(work = {}, fallbackMemberUserId = 0) {
  const memberUserId = Number(work.memberUserId || fallbackMemberUserId)
  const workId = Number(work.workId || work.id)
  if (!Number.isInteger(memberUserId) || memberUserId <= 0 || !Number.isInteger(workId) || workId <= 0) return null
  const tags = Array.isArray(work.tags) ? work.tags : []
  const tagText = tags.map((tag) => String(tag && tag.name || '').trim()).filter(Boolean).join('、')
  return {
    memberUserId,
    workId,
    memberDisplayName: String(work.memberDisplayName || work.displayName || '').trim(),
    title: String(work.title || '').trim() || `视频 #${workId}`,
    coverUrl: String(work.coverUrl || ''),
    mediaUrl: String(work.mediaUrl || ''),
    thumbUrl: String(work.coverUrl || work.mediaUrl || ''),
    durationMs: Number(work.durationMs) || 0,
    durationText: formatDuration(work.durationMs),
    width: Number(work.width) || 0,
    height: Number(work.height) || 0,
    aspectRatio: String(work.aspectRatio || ''),
    aspectRatioText: String(work.aspectRatio || '').trim() || '--',
    tags,
    metaText: tagText ? `视频 · ${tagText}` : '视频'
  }
}
function mergeTeamVideoCandidates(existing = [], incoming = [], memberUserId = 0) {
  const seen = new Set()
  return (Array.isArray(existing) ? existing : [])
    .concat(Array.isArray(incoming) ? incoming : [])
    .map((work) => normalizeTeamVideoCandidate(work, memberUserId))
    .filter((work) => {
      const key = work && teamVideoItemKey(work)
      if (!work || seen.has(key)) return false
      seen.add(key)
      return true
    })
}
function hydrateTeamVideoSelectedItems(items = [], candidates = []) {
  const candidateMap = mergeTeamVideoCandidates([], candidates)
    .reduce((result, work) => result.set(teamVideoItemKey(work), work), new Map())
  return normalizeTeamVideoCarouselConfig({ items }).items.map((item) => candidateMap.get(teamVideoItemKey(item)) || {
    memberUserId: item.memberUserId,
    workId: item.workId,
    title: `视频 #${item.workId}`,
    thumbUrl: '',
    metaText: '视频',
    memberDisplayName: '',
    durationText: formatDuration(0),
    aspectRatioText: '--'
  })
}
function buildTeamVideoWorkOptions(candidates = [], selectedItems = []) {
  const selectedKeys = new Set(selectedItems.map(teamVideoItemKey))
  const selectionOrder = selectedItems.reduce((result, item, index) => result.set(teamVideoItemKey(item), index + 1), new Map())
  return mergeTeamVideoCandidates([], candidates).map((work) => ({
    ...work,
    selected: selectedKeys.has(teamVideoItemKey(work)),
    selectionOrder: selectionOrder.get(teamVideoItemKey(work)) || 0
  }))
}
function buildTeamVideoMembers(members = [], selectedItems = [], activeMemberUserId = 0) {
  const counts = selectedItems.reduce((result, item) => {
    result[item.memberUserId] = (result[item.memberUserId] || 0) + 1
    return result
  }, {})
  return (Array.isArray(members) ? members : []).map((member) => {
    const memberUserId = Number(member && member.memberUserId)
    return Object.assign({}, member, {
      memberUserId,
      displayName: String(member && (member.displayName || member.memberDisplayName) || '').trim() || `成员 #${memberUserId}`,
      selectedCount: counts[memberUserId] || 0,
      active: memberUserId === Number(activeMemberUserId)
    })
  }).filter((member) => member.memberUserId > 0)
}
function updateTeamComponent(config, componentKey, updater) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const location = findTeamPortfolioComponent(normalized, componentKey)
  if (!location) return normalized
  const components = getTeamMenuComponentList(normalized, location.menuKey)
    .map((component) => component.componentKey === componentKey
      ? updater(component)
      : component)
  return replaceTeamMenuComponentList(normalized, location.menuKey, components)
}
function buildServerSafeTeamPortfolioConfig(config = {}) {
  let normalized = normalizeTeamPortfolioConfig(config)
  const coverUrl = normalized.share && normalized.share.coverUrl
  const menuKeys = normalized.bottomNav.enabled
    ? normalized.bottomNav.items.map((item) => item.key)
    : ['']
  menuKeys.forEach((menuKey) => {
    const components = getTeamMenuComponentList(normalized, menuKey).map((component) => {
      if (component.componentType === 'QR_CONTACT') {
        const qrUrl = component.config && component.config.qrUrl
        if (!isRemoteUrl(qrUrl)) return null
        return Object.assign({}, component, {
          config: { qrUrlSource: TEAM_QR_CONTACT_SOURCE_CUSTOM, qrUrl }
        })
      }
      if (component.componentType === 'TEAM_PROFILE') {
        const team = component.config && component.config.team ? component.config.team : {}
        return Object.assign({}, component, {
          config: Object.assign({}, component.config, {
            team: Object.assign({}, team, {
              avatarUrl: isRemoteUrl(team.avatarUrl) ? team.avatarUrl : ''
            })
          })
        })
      }
      return component
    }).filter(Boolean)
    normalized = replaceTeamMenuComponentList(normalized, menuKey, components)
  })
  return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
    share: Object.assign({}, normalized.share, {
      coverUrl: isRemoteUrl(coverUrl) ? coverUrl : ''
    })
  }))
}

function makeKey() { return `component-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` }
function makeIdempotencyKey(prefix) { return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}` }
function getQrContactCropBoxWidth(wxApi = wx) {
  const windowInfo = wxApi && wxApi.getWindowInfo
    ? wxApi.getWindowInfo()
    : (wxApi && wxApi.getSystemInfoSync ? wxApi.getSystemInfoSync() : {})
  const windowWidth = Number(windowInfo.windowWidth) || 375
  const rpxScale = windowWidth / DESIGN_VIEWPORT_RPX
  return Math.floor(Math.min(
    QR_CONTACT_CROP_MAX_WIDTH_RPX * rpxScale,
    windowWidth - QR_CONTACT_CROP_HORIZONTAL_GUTTER_RPX * rpxScale
  ))
}
function resetQrContactCropState() {
  return {
    qrContactCropVisible: false,
    qrContactCropSaving: false,
    qrContactCropErrorText: '',
    qrContactCropState: null,
    qrContactCropTouchStart: null
  }
}
function countTextCodePoints(value) { return Array.from(String(value || '')).length }
function buildTextSectionForm(config = {}) {
  const alignment = String(config.alignment || '').trim()
  const typography = buildPortfolioTextTypography(
    config,
    LEGACY_TEAM_FONT_SIZE_RPX
  )
  return {
    content: String(config.content || '').trim(),
    alignment: TEXT_SECTION_ALIGNMENT_OPTIONS.some((item) => item.value === alignment) ? alignment : TEXT_SECTION_ALIGNMENTS.LEFT,
    fontFamily: typography.fontFamily,
    fontSizeRpx: typography.fontSizeRpx
  }
}
function buildTextSectionFieldCounters(form = {}) { return { content: `${countTextCodePoints(form.content)} / ${TEXT_SECTION_MAX_LENGTH}` } }
function buildTextSectionFontOptions(
  capability = getPortfolioFontCapability()
) {
  return PORTFOLIO_TEXT_FONT_OPTIONS.map((item) =>
    Object.assign({}, item, {
      available: isPortfolioFontAvailable(item.value, capability)
    })
  )
}
function buildTextSectionEditorState(config = {}) {
  const textSectionForm = buildTextSectionForm(config)
  return {
    textSectionForm,
    textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm),
    textSectionFontOptions: buildTextSectionFontOptions(),
    textSectionSizeOptions:
      buildPortfolioTextFontSizeOptions(textSectionForm.fontSizeRpx),
    textSectionTypography: buildPortfolioTextTypography(
      textSectionForm,
      LEGACY_TEAM_FONT_SIZE_RPX
    )
  }
}
function buildContactFormConfigForm(config = {}) {
  return {
    displayMode: config.displayMode === CONTACT_FORM_DISPLAY_MODES.INLINE_FORM
      ? CONTACT_FORM_DISPLAY_MODES.INLINE_FORM
      : CONTACT_FORM_DISPLAY_MODES.MODAL_FORM
  }
}
function updateContactFormConfig(config = {}, componentKey, contactFormConfig = {}) {
  const form = buildContactFormConfigForm(contactFormConfig)
  return updateTeamComponent(config, componentKey, (component) => component.componentType === 'CONTACT_FORM'
      ? Object.assign({}, component, { config: Object.assign({}, component.config, form) })
      : component)
}
function buildScheduleQueryForm(config = {}) {
  return { displayMode: config.displayMode === SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR ? SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR : SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR }
}
function updateScheduleQueryConfig(config = {}, componentKey, scheduleQueryConfig = {}) {
  const form = buildScheduleQueryForm(scheduleQueryConfig)
  return updateTeamComponent(config, componentKey, (component) => component.componentType === 'SCHEDULE_QUERY'
    ? Object.assign({}, component, { config: Object.assign({}, component.config, form) })
    : component)
}
function buildDividerForm(config = {}) {
  const color = DIVIDER_COLOR_OPTIONS.some((item) => item.value === config.color) ? config.color : 'GRAY'
  const heightPx = Number.isInteger(Number(config.heightPx)) && Number(config.heightPx) > 0 ? Number(config.heightPx) : 16
  return { color, heightPx }
}
function updateDividerConfig(config = {}, componentKey, dividerConfig = {}) {
  const form = buildDividerForm(dividerConfig)
  return updateTeamComponent(config, componentKey, (component) => component.componentType === 'DIVIDER'
    ? Object.assign({}, component, { config: Object.assign({}, component.config, form) })
    : component)
}
function updateTextSectionConfig(config = {}, componentKey, textSectionConfig = {}) {
  const form = buildTextSectionForm(textSectionConfig)
  return updateTeamComponent(config, componentKey, (component) => component.componentType === 'TEXT_SECTION'
      ? Object.assign({}, component, { config: Object.assign({}, component.config, form) })
      : component)
}
function isUncertainFailure(error) { return !error || !Number(error.statusCode) || Number(error.statusCode) >= 500 }
function serverBusinessMessage(error, fallbackMessage) {
  const statusCode = Number(error && error.statusCode)
  const message = String(error && error.message || '').trim()
  return statusCode >= 400 && statusCode < 500 && message
    ? message
    : fallbackMessage
}
function resolvePortfolioListBackDelta() {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  for (let index = pages.length - 2; index >= 0; index -= 1) {
    const route = String(pages[index] && pages[index].route || '')
    if (route.endsWith(PORTFOLIO_LIST_ROUTE_SUFFIX)) return pages.length - 1 - index
  }
  return 0
}
function normalizeTeamSnapshot(teamId, source = {}) { const team = source && typeof source.team === 'object' ? source.team : source; return { teamId: Number(team.teamId) || Number(teamId) || 0, teamName: String(team.teamName || team.name || '').trim(), avatarUrl: String(team.avatarUrl || '').trim(), intro: String(team.intro || '').trim() } }
function hasTeamSnapshot(snapshot = {}) { return Number(snapshot.teamId) > 0 && Boolean(String(snapshot.teamName || '').trim()) }
function draftTeamSnapshot(config = {}) {
  const location = visitTeamPortfolioComponents(config)
    .find((item) => item.component.componentType === 'TEAM_PROFILE')
  return location
    ? normalizeTeamSnapshot(0, location.component.config && location.component.config.team)
    : {}
}
function resolveTeamCoverPreviewPath(filePath) {
  if (!filePath || !wx.getImageInfo) return Promise.resolve(filePath || '')
  return new Promise((resolve, reject) => wx.getImageInfo({
    src: filePath,
    success(response = {}) { resolve(response.path || response.tempFilePath || filePath) },
    fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '无法读取封面图片')) }
  }))
}
function buckets(components) {
  const value = { teamProfile: [], carousel: [], videoCarousel: [], singleWork: [], divider: [], grid: [], list: [], text: [], schedule: [], contact: [], qr: [] }
  ;(Array.isArray(components) ? components : []).forEach((component) => { const key = TYPE_BUCKETS[component.componentType]; if (key) value[key].push(component) })
  return value
}

Page({
  teamVideoRequestSeq: 0,
  teamVideoMembersRequestSeq: 0,
  data: { portfolioId: 0, teamId: 0, teamSnapshot: {}, loading: false, errorMessage: '', canMaintain: false, publicationStatus: 'DRAFT_ONLY', draftRevision: 0, publishedRevision: 0, statusText: '草稿', statusTone: 'draft', showPublishAction: false, config: normalizeTeamPortfolioConfig(), activeMenuKey: '', activeMenuTitle: '', activeMenuTitleCount: 0, navigationItems: [], bottomNavCount: 1, backgroundColorOptions: TEAM_BACKGROUND_COLORS, bottomNavCountOptions: TEAM_BOTTOM_NAV_COUNTS, backgroundColorSheetVisible: false, backgroundColorDraft: '#FFFFFF', backgroundColorHsv: hexToHsv('#FFFFFF'), backgroundHueColor: '#FF0000', backgroundColorPadDotStyle: 'left: 0%; top: 0%', componentList: [], componentBuckets: buckets([]), componentOptions: buildComponentOptions(), componentValidation: {}, componentSources: {}, singleWorkRowSummaryMap: {}, hasInvalidComponents: false, saving: false, publishing: false, openingLibrary: false, shareCoverUploading: false, qrContactChoosing: false, qrContactCropVisible: false, qrContactCropSaving: false, qrContactCropErrorText: '', qrContactCropState: null, qrContactCropTouchStart: null, qrContactCropCanvasWidth: WECHAT_QR_CROP_OUTPUT_WIDTH, qrContactCropCanvasHeight: WECHAT_QR_CROP_OUTPUT_WIDTH, teamProfileRefreshing: false, pendingDraftKey: '', pendingPublishKey: '', pendingPublishRevision: 0, shareTitleCounter: '0 / 50', componentSheetVisible: false, textSectionSheetVisible: false, textSectionEditingComponentKey: '', textSectionAlignmentOptions: TEXT_SECTION_ALIGNMENT_OPTIONS, textSectionMaxLength: TEXT_SECTION_MAX_LENGTH, portfolioFontCapability: getPortfolioFontCapability(), ...buildTextSectionEditorState(), contactFormSheetVisible: false, contactFormEditingComponentKey: '', contactFormDisplayModeOptions: CONTACT_FORM_DISPLAY_MODE_OPTIONS, contactFormConfigForm: buildContactFormConfigForm(), scheduleQuerySheetVisible: false, scheduleQueryEditingComponentKey: '', scheduleQueryDisplayModeOptions: SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS, scheduleQueryForm: buildScheduleQueryForm(), dividerSheetVisible: false, dividerEditingComponentKey: '', dividerColorOptions: DIVIDER_COLOR_OPTIONS, dividerForm: buildDividerForm(), componentEditorVisible: false, componentEditorLayoutType: '', activeComponentKey: '', activeComponentType: '', activeComponentName: '', activeComponent: { config: {} }, activeComponentSource: {}, activeComponentNeedsPortfolio: false, teamVideoTitle: '视频作品', teamVideoTitleCount: 4, teamVideoShowTitle: true, teamVideoShowSwipeHint: true, teamVideoMembers: [], teamVideoSelectedMemberUserId: 0, teamVideoCandidates: [], teamVideoWorkOptions: [], teamVideoSelectedItems: [], teamVideoKeyword: '', teamVideoPage: 1, teamVideoPageSize: TEAM_VIDEO_PAGE_SIZE, teamVideoHasMore: false, teamVideoMembersLoading: false, teamVideoWorksLoading: false, teamVideoLoadingMore: false, teamVideoErrorText: '', teamVideoEditingNewComponent: false, revealedComponentKey: '', componentTouchStart: null, draggingIndex: -1, dragTargetIndex: -1, componentDragStartY: 0, componentDragStyle: '', componentMoveSheetVisible: false, componentMoveKey: '', componentMoveTargets: [], componentMovePending: false, highlightedComponentKey: '', componentScrollTarget: '', shareCoverCropVisible: false, shareCoverCropPath: '', ...buildCarouselEditorLayoutState(CAROUSEL_EDITOR_SCROLL_MIN_HEIGHT_RPX) },
  onLoad(options = {}) {
    const portfolioId = Number(options.portfolioId) || 0
    const teamId = Number(options.teamId) || 0
    this.loadPortfolioFontCapability()
    if (portfolioId) { this.setData({ portfolioId }); return this.bootstrap() }
    if (teamId) { this.setData({ teamId }); return this.bootstrapNew() }
    this.setData({ errorMessage: '作品集参数无效' })
  },
  loadPortfolioFontCapability() {
    try {
      loadPortfolioFonts()
        .then((capability) => {
          this.setData({
            portfolioFontCapability: capability,
            textSectionFontOptions: buildTextSectionFontOptions(capability)
          })
        })
        .catch((error) => {
          console.warn('加载团队作品集内置字体失败', error)
        })
    } catch (error) {
      console.warn('启动团队作品集内置字体加载失败', error)
    }
  },
  async bootstrapNew() {
    if (this.data.loading || !this.data.teamId) return
    this.setData({ loading: true, errorMessage: '' })
    try {
      const teamDetail = await request({ url: `/api/mine/teams/${this.data.teamId}` })
      const teamSnapshot = normalizeTeamSnapshot(this.data.teamId, teamDetail && teamDetail.team)
      const componentKey = makeKey()
      const config = normalizeTeamPortfolioConfig({
        schemaVersion: 'standard-team-v1',
        share: this.data.config.share,
        components: [{ componentKey, componentType: 'TEAM_PROFILE', sortOrder: 0, enabled: true, config: { team: teamSnapshot } }]
      })
      this.setData({ teamSnapshot, canMaintain: true, componentValidation: { [componentKey]: true }, hasInvalidComponents: false })
      this.updateConfig(config)
    } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '团队资料加载失败，请重试', canMaintain: false }) } finally { this.setData({ loading: false }) }
  },
  async bootstrap() {
    if (this.data.loading || !this.data.portfolioId) return
    this.setData({ loading: true, errorMessage: '' })
    try {
      const detail = await fetchTeamPortfolioDetail(request, this.data.portfolioId)
      const config = normalizeTeamPortfolioConfig(detail.config)
      const componentValidation = Object.fromEntries(
        visitTeamPortfolioComponents(config).map((item) => [item.component.componentKey, true])
      )
      let teamSnapshot = draftTeamSnapshot(config)
      if (!hasTeamSnapshot(teamSnapshot)) {
        const teamDetail = await request({ url: `/api/mine/teams/${detail.ownerId}` })
        teamSnapshot = normalizeTeamSnapshot(detail.ownerId, teamDetail && teamDetail.team)
      }
      this.setData(Object.assign({
        teamId: detail.ownerId,
        teamSnapshot,
        canMaintain: true,
        publicationStatus: detail.publicationStatus,
        draftRevision: detail.draftRevision,
        publishedRevision: detail.publishedRevision,
        componentValidation,
        hasInvalidComponents: false
      }, buildPublicationState(detail.publicationStatus)))
      this.updateConfig(config)
      this.loadSingleWorkRowSummaries(config)
    } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '团队作品集加载失败，请重试', canMaintain: false }) } finally { this.setData({ loading: false }) }
  },
  onShow() { if (this.data.openingLibrary) this.setData({ openingLibrary: false }) },
  onUnload() {
    if (this.componentHighlightTimer) clearTimeout(this.componentHighlightTimer)
    this.singleWorkRowSummaryRequestSeq = (Number(this.singleWorkRowSummaryRequestSeq) || 0) + 1
  },
  handleRetry() { this.bootstrap() },
  updateConfig(config) {
    const normalized = normalizeTeamPortfolioConfig(config)
    const navigationItems = normalized.bottomNav.enabled
      ? normalized.bottomNav.items.map((item) => Object.assign({}, item, {
        titleLength: Array.from(String(item.title || '')).length
      }))
      : []
    const activeMenuKey = navigationItems.some((item) => item.key === this.data.activeMenuKey)
      ? this.data.activeMenuKey
      : (navigationItems[0] && navigationItems[0].key) || ''
    const activeMenu = navigationItems.find((item) => item.key === activeMenuKey)
    const components = getTeamMenuComponentList(normalized, activeMenuKey)
    const allComponents = visitTeamPortfolioComponents(normalized).map((item) => item.component)
    this.setData({
      config: normalized,
      activeMenuKey,
      activeMenuTitle: activeMenu ? activeMenu.title : '',
      activeMenuTitleCount: activeMenu ? Array.from(activeMenu.title).length : 0,
      navigationItems,
      bottomNavCount: navigationItems.length || 1,
      backgroundColorDraft: normalized.style.backgroundColor,
      componentList: buildComponentList(components, this.data.singleWorkRowSummaryMap),
      componentBuckets: buckets(components),
      componentOptions: buildComponentOptions(allComponents),
      shareTitleCounter: `${String(normalized.share && normalized.share.title || '').length} / 50`
    })
    if (this.data.activeComponentKey) {
      this.syncActiveComponent(normalized, this.data.activeComponentKey)
    }
  },
  refreshComponentList(singleWorkRowSummaryMap = this.data.singleWorkRowSummaryMap) {
    const components = getTeamMenuComponentList(this.data.config, this.data.activeMenuKey)
    this.setData({
      singleWorkRowSummaryMap,
      componentList: buildComponentList(components, singleWorkRowSummaryMap)
    })
  },
  loadSingleWorkRowSummaries(config = this.data.config) {
    const requestSeq = (Number(this.singleWorkRowSummaryRequestSeq) || 0) + 1
    this.singleWorkRowSummaryRequestSeq = requestSeq
    const components = visitTeamPortfolioComponents(config)
      .map((item) => item.component)
      .filter((component) => component.componentType === 'SINGLE_WORK')
    const summaryMap = {}
    const pendingComponents = []
    components.forEach((component) => {
      const componentConfig = component.config || {}
      const workId = Number(componentConfig.workId)
      const memberUserId = Number(componentConfig.memberUserId)
      if (!workId) return
      if (!memberUserId || !Number(this.data.teamId)) {
        summaryMap[component.componentKey] = { workId, status: SINGLE_WORK_ROW_SUMMARY_STATUS.UNAVAILABLE }
        return
      }
      summaryMap[component.componentKey] = { workId, status: SINGLE_WORK_ROW_SUMMARY_STATUS.LOADING }
      pendingComponents.push({ componentKey: component.componentKey, memberUserId, workId })
    })
    this.refreshComponentList(summaryMap)
    if (!pendingComponents.length) return Promise.resolve(summaryMap)
    return Promise.all(pendingComponents.map((component) => request({
      url: `/api/mine/teams/${this.data.teamId}/portfolio-components/single-work/members/${component.memberUserId}/works/page`,
      data: { page: 1, pageSize: 1, selectedWorkId: component.workId }
    }).then((response = {}) => {
      const selectedWork = response.selectedWork
      return selectedWork && Number(selectedWork.workId) === component.workId
        ? Object.assign({}, component, { title: selectedWork.title || '', status: '' })
        : Object.assign({}, component, { status: SINGLE_WORK_ROW_SUMMARY_STATUS.UNAVAILABLE })
    }).catch(() => Object.assign({}, component, {
      status: SINGLE_WORK_ROW_SUMMARY_STATUS.FAILED
    })))).then((results) => {
      if (requestSeq !== this.singleWorkRowSummaryRequestSeq) return this.data.singleWorkRowSummaryMap
      const nextSummaryMap = Object.assign({}, this.data.singleWorkRowSummaryMap)
      results.forEach((result) => {
        const location = findTeamPortfolioComponent(this.data.config, result.componentKey)
        const currentConfig = location && location.component && location.component.config || {}
        if (
          Number(currentConfig.memberUserId) !== result.memberUserId ||
          Number(currentConfig.workId) !== result.workId
        ) return
        nextSummaryMap[result.componentKey] = {
          workId: result.workId,
          title: result.title || '',
          status: result.status
        }
      })
      this.refreshComponentList(nextSummaryMap)
      return nextSummaryMap
    })
  },
  updateSingleWorkRowSummary(componentKey, config = {}) {
    const workId = Number(config.workId)
    const source = this.data.componentSources[componentKey] || {}
    const selectedWork = [source.selectedWork].concat(Array.isArray(source.works) ? source.works : [])
      .find((item) => Number(item && item.workId) === workId)
    const singleWorkRowSummaryMap = Object.assign({}, this.data.singleWorkRowSummaryMap, {
      [componentKey]: selectedWork
        ? { workId, title: selectedWork.title || '', status: '' }
        : { workId, status: SINGLE_WORK_ROW_SUMMARY_STATUS.UNAVAILABLE }
    })
    this.refreshComponentList(singleWorkRowSummaryMap)
  },
  clearPending() { this.setData({ pendingDraftKey: '', pendingPublishKey: '', pendingPublishRevision: 0 }) },
  noop() {},
  syncActiveComponent(config = this.data.config, componentKey = this.data.activeComponentKey) { const location = findTeamPortfolioComponent(config, componentKey); const activeComponent = location ? location.component : { config: {} }; this.setData({ activeComponent, activeComponentSource: this.data.componentSources[componentKey] || {} }) },
  handleOpenComponentSheet() { if (!this.data.canMaintain) return; const allComponents = visitTeamPortfolioComponents(this.data.config).map((item) => item.component); this.setData({ componentSheetVisible: true, revealedComponentKey: '', componentOptions: buildComponentOptions(allComponents) }) },
  handleCloseComponentSheet() { this.setData({ componentSheetVisible: false }) },
  handleSelectComponent(event) { if (event.currentTarget.dataset.disabled) return; const componentType = event.currentTarget.dataset.type; if (!componentType) return; const componentKey = this.addComponent(componentType); this.setData({ componentSheetVisible: false }); if (componentType === 'VIDEO_CAROUSEL' && componentKey) return this.openTeamVideoCarouselSheet(componentKey, true) },
  handleComponentTap(event) { const componentKey = event.currentTarget.dataset.key || ''; const componentType = event.currentTarget.dataset.type || ''; if (this.data.revealedComponentKey === componentKey) return this.setData({ revealedComponentKey: '' }); const location = findTeamPortfolioComponent(this.data.config, componentKey); const activeComponent = location && location.component; if (!activeComponent) return; if (componentType === 'VIDEO_CAROUSEL') return this.openTeamVideoCarouselSheet(componentKey); if (componentType === 'TEXT_SECTION') return this.openTextSectionSheet(componentKey); if (componentType === 'CONTACT_FORM') return this.openContactFormSheet(componentKey); if (componentType === 'SCHEDULE_QUERY') return this.openScheduleQuerySheet(componentKey); if (componentType === 'DIVIDER') return this.openDividerSheet(componentKey); this.setData({ componentEditorVisible: true, componentEditorLayoutType: componentType, activeComponentKey: componentKey, activeComponentType: componentType, activeComponentName: COMPONENT_NAMES[componentType] || '页面组件', activeComponent, activeComponentSource: this.data.componentSources[componentKey] || {}, activeComponentNeedsPortfolio: PORTFOLIO_REQUIRED_COMPONENT_TYPES.includes(componentType), ...(componentType === 'CAROUSEL' ? buildCarouselEditorLayoutState(CAROUSEL_EDITOR_SCROLL_MIN_HEIGHT_RPX) : {}) }) },
  resetTeamVideoEditorState() {
    this.setData({
      teamVideoTitle: '视频作品',
      teamVideoTitleCount: 4,
      teamVideoShowTitle: true,
      teamVideoShowSwipeHint: true,
      teamVideoMembers: [],
      teamVideoSelectedMemberUserId: 0,
      teamVideoCandidates: [],
      teamVideoWorkOptions: [],
      teamVideoSelectedItems: [],
      teamVideoKeyword: '',
      teamVideoPage: 1,
      teamVideoPageSize: TEAM_VIDEO_PAGE_SIZE,
      teamVideoHasMore: false,
      teamVideoMembersLoading: false,
      teamVideoWorksLoading: false,
      teamVideoLoadingMore: false,
      teamVideoErrorText: '',
      teamVideoEditingNewComponent: false
    })
  },
  async openTeamVideoCarouselSheet(componentKey, editingNewComponent = false) {
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const activeComponent = location && location.component
    if (!activeComponent || activeComponent.componentType !== 'VIDEO_CAROUSEL') return
    const config = normalizeTeamVideoCarouselConfig(activeComponent.config || {})
    const selectedItems = hydrateTeamVideoSelectedItems(config.items)
    this.setData({
      componentEditorVisible: true,
      componentEditorLayoutType: 'VIDEO_CAROUSEL',
      activeComponentKey: componentKey,
      activeComponentType: 'VIDEO_CAROUSEL',
      activeComponentName: COMPONENT_NAMES.VIDEO_CAROUSEL,
      activeComponent,
      activeComponentSource: {},
      activeComponentNeedsPortfolio: true,
      teamVideoTitle: config.title,
      teamVideoTitleCount: countTextCodePoints(config.title),
      teamVideoShowTitle: config.showTitle,
      teamVideoShowSwipeHint: config.showSwipeHint,
      teamVideoMembers: [],
      teamVideoSelectedMemberUserId: 0,
      teamVideoCandidates: [],
      teamVideoWorkOptions: [],
      teamVideoSelectedItems: selectedItems,
      teamVideoKeyword: '',
      teamVideoPage: 1,
      teamVideoPageSize: TEAM_VIDEO_PAGE_SIZE,
      teamVideoHasMore: false,
      teamVideoMembersLoading: Boolean(this.data.portfolioId),
      teamVideoWorksLoading: false,
      teamVideoLoadingMore: false,
      teamVideoErrorText: '',
      teamVideoEditingNewComponent: Boolean(editingNewComponent)
    })
    if (this.data.portfolioId) return this.loadTeamVideoMembers()
  },
  async loadTeamVideoMembers() {
    if (!Number(this.data.portfolioId)) return
    const requestSeq = this.teamVideoMembersRequestSeq + 1
    this.teamVideoMembersRequestSeq = requestSeq
    this.setData({ teamVideoMembersLoading: true, teamVideoErrorText: '' })
    try {
      const response = await request({
        url: `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members`
      })
      if (requestSeq !== this.teamVideoMembersRequestSeq || this.data.activeComponentType !== 'VIDEO_CAROUSEL') return
      const sourceMembers = Array.isArray(response) ? response : []
      const preferredMemberUserId = Number(this.data.teamVideoSelectedItems[0] && this.data.teamVideoSelectedItems[0].memberUserId)
      const preferredAvailable = sourceMembers.some((member) => Number(member && member.memberUserId) === preferredMemberUserId)
      const memberUserId = preferredAvailable
        ? preferredMemberUserId
        : Number(sourceMembers[0] && sourceMembers[0].memberUserId) || 0
      this.setData({
        teamVideoMembers: buildTeamVideoMembers(sourceMembers, this.data.teamVideoSelectedItems, memberUserId),
        teamVideoSelectedMemberUserId: memberUserId,
        teamVideoMembersLoading: false,
        teamVideoErrorText: ''
      })
      if (memberUserId) return this.loadTeamVideoWorks(memberUserId, { reset: true })
    } catch (error) {
      if (requestSeq !== this.teamVideoMembersRequestSeq) return
      if (handleTeamMaintainerAuthError(error)) return
      if (showTeamPortfolioUnavailableToast(error)) return
      this.setData({
        teamVideoMembersLoading: false,
        teamVideoErrorText: error && error.message ? error.message : '团队成员加载失败'
      })
    }
  },
  async loadTeamVideoWorks(memberUserId, options = {}) {
    memberUserId = Number(memberUserId || this.data.teamVideoSelectedMemberUserId)
    if (!Number(this.data.portfolioId) || !memberUserId) return
    const reset = options.reset !== false
    const pageSize = Number(this.data.teamVideoPageSize) || TEAM_VIDEO_PAGE_SIZE
    const page = reset ? 1 : (Number(this.data.teamVideoPage) || 1) + 1
    const keyword = String(this.data.teamVideoKeyword || '').trim()
    const requestSeq = this.teamVideoRequestSeq + 1
    this.teamVideoRequestSeq = requestSeq
    this.setData(reset
      ? { teamVideoWorksLoading: true, teamVideoLoadingMore: false, teamVideoErrorText: '' }
      : { teamVideoLoadingMore: true, teamVideoErrorText: '' })
    try {
      const response = await request({
        url: `/api/mine/team-portfolios/${this.data.portfolioId}/components/video-carousel/members/${memberUserId}/works`,
        data: { keyword, page, pageSize }
      })
      if (requestSeq !== this.teamVideoRequestSeq || memberUserId !== Number(this.data.teamVideoSelectedMemberUserId)) return
      const incoming = Array.isArray(response && response.works) ? response.works : []
      const candidates = reset
        ? mergeTeamVideoCandidates([], incoming, memberUserId)
        : mergeTeamVideoCandidates(this.data.teamVideoCandidates, incoming, memberUserId)
      const selectedItems = hydrateTeamVideoSelectedItems(
        this.data.teamVideoSelectedItems,
        candidates.concat(this.data.teamVideoSelectedItems)
      )
      this.setData({
        teamVideoCandidates: candidates,
        teamVideoWorkOptions: buildTeamVideoWorkOptions(candidates, selectedItems),
        teamVideoSelectedItems: selectedItems,
        teamVideoMembers: buildTeamVideoMembers(this.data.teamVideoMembers, selectedItems, memberUserId),
        teamVideoPage: Math.max(1, Number(response && response.page) || page),
        teamVideoPageSize: Math.max(1, Number(response && response.pageSize) || pageSize),
        teamVideoHasMore: Boolean(response && response.hasMore),
        teamVideoWorksLoading: false,
        teamVideoLoadingMore: false,
        teamVideoErrorText: ''
      })
    } catch (error) {
      if (requestSeq !== this.teamVideoRequestSeq) return
      if (handleTeamMaintainerAuthError(error)) return
      if (showTeamPortfolioUnavailableToast(error)) return
      this.setData({
        teamVideoWorksLoading: false,
        teamVideoLoadingMore: false,
        teamVideoErrorText: error && error.message ? error.message : '视频作品加载失败'
      })
    }
  },
  handleTeamVideoTitleInput(event) { const title = Array.from(String(event && event.detail && event.detail.value || '')).slice(0, 10).join(''); this.setData({ teamVideoTitle: title, teamVideoTitleCount: countTextCodePoints(title) }) },
  handleTeamVideoShowTitleChange(event) { this.setData({ teamVideoShowTitle: Boolean(event && event.detail && event.detail.value) }) },
  handleTeamVideoShowSwipeHintChange(event) { this.setData({ teamVideoShowSwipeHint: Boolean(event && event.detail && event.detail.value) }) },
  handleTeamVideoKeywordInput(event) { this.setData({ teamVideoKeyword: String(event && event.detail && event.detail.value || '') }) },
  handleTeamVideoSearchConfirm() { return this.loadTeamVideoWorks(this.data.teamVideoSelectedMemberUserId, { reset: true }) },
  handleTeamVideoClearSearch() { if (!this.data.teamVideoKeyword) return Promise.resolve(); this.setData({ teamVideoKeyword: '' }); return this.loadTeamVideoWorks(this.data.teamVideoSelectedMemberUserId, { reset: true }) },
  handleTeamVideoLoadMore() { if (this.data.teamVideoWorksLoading || this.data.teamVideoLoadingMore || !this.data.teamVideoHasMore) return Promise.resolve(); return this.loadTeamVideoWorks(this.data.teamVideoSelectedMemberUserId, { reset: false }) },
  handleTeamVideoRetry() { return this.data.teamVideoMembers.length ? this.handleTeamVideoSearchConfirm() : this.loadTeamVideoMembers() },
  handleTeamVideoMemberTap(event) {
    const memberUserId = Number(event && event.currentTarget && event.currentTarget.dataset.memberUserId)
    if (!memberUserId || memberUserId === Number(this.data.teamVideoSelectedMemberUserId)) return Promise.resolve()
    this.teamVideoRequestSeq += 1
    this.setData({
      teamVideoSelectedMemberUserId: memberUserId,
      teamVideoMembers: buildTeamVideoMembers(this.data.teamVideoMembers, this.data.teamVideoSelectedItems, memberUserId),
      teamVideoCandidates: [],
      teamVideoWorkOptions: [],
      teamVideoPage: 1,
      teamVideoHasMore: false,
      teamVideoErrorText: ''
    })
    return this.loadTeamVideoWorks(memberUserId, { reset: true })
  },
  handleTeamVideoWorkTap(event) {
    const workId = Number(event && event.currentTarget && event.currentTarget.dataset.workId)
    const memberUserId = Number(this.data.teamVideoSelectedMemberUserId)
    if (!memberUserId || !workId) return
    const key = teamVideoItemKey({ memberUserId, workId })
    const selectedItems = this.data.teamVideoSelectedItems || []
    const selectedIndex = selectedItems.findIndex((item) => teamVideoItemKey(item) === key)
    if (selectedIndex < 0 && selectedItems.length >= TEAM_VIDEO_MAX_ITEMS) {
      wx.showToast({ title: '视频轮播最多选择8个视频', icon: 'none' })
      return
    }
    const candidate = normalizeTeamVideoCandidate(
      this.data.teamVideoWorkOptions.find((work) => teamVideoItemKey(work) === key) || { memberUserId, workId },
      memberUserId
    )
    const nextSelectedItems = selectedIndex >= 0
      ? selectedItems.filter((_, index) => index !== selectedIndex)
      : selectedItems.concat(candidate)
    this.setData({
      teamVideoSelectedItems: nextSelectedItems,
      teamVideoWorkOptions: buildTeamVideoWorkOptions(this.data.teamVideoCandidates, nextSelectedItems),
      teamVideoMembers: buildTeamVideoMembers(this.data.teamVideoMembers, nextSelectedItems, memberUserId)
    })
  },
  saveTeamVideoCarouselConfig() {
    if (this.data.teamVideoSelectedItems.length < TEAM_VIDEO_MIN_ITEMS) {
      wx.showToast({ title: '视频轮播至少选择3个视频', icon: 'none' })
      return
    }
    const componentKey = this.data.activeComponentKey
    const nextConfig = normalizeTeamVideoCarouselConfig({
      title: this.data.teamVideoTitle,
      items: this.data.teamVideoSelectedItems,
      showTitle: this.data.teamVideoShowTitle,
      showSwipeHint: this.data.teamVideoShowSwipeHint
    })
    this.clearPending()
    this.updateConfig(updateTeamComponent(this.data.config, componentKey, (component) => component.componentType === 'VIDEO_CAROUSEL'
      ? Object.assign({}, component, { config: nextConfig })
      : component))
    const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: true })
    this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false), teamVideoEditingNewComponent: false })
    this.handleCloseComponentEditor()
  },
  handleCloseComponentEditor() {
    this.singleWorkRequestSeq = (Number(this.singleWorkRequestSeq) || 0) + 1
    this.teamVideoRequestSeq += 1
    this.teamVideoMembersRequestSeq += 1
    if (this.data.activeComponentType === 'VIDEO_CAROUSEL' && this.data.teamVideoEditingNewComponent) {
      const componentKey = this.data.activeComponentKey
      const location = findTeamPortfolioComponent(this.data.config, componentKey)
      if (location) {
        this.updateConfig(replaceTeamMenuComponentList(
          this.data.config,
          location.menuKey,
          getTeamMenuComponentList(this.data.config, location.menuKey)
            .filter((component) => component.componentKey !== componentKey)
        ))
      }
    }
    this.setData({ componentEditorVisible: false, activeComponentKey: '', activeComponentType: '', activeComponentName: '', activeComponent: { config: {} }, activeComponentSource: {}, activeComponentNeedsPortfolio: false })
    this.resetTeamVideoEditorState()
  },
  openTextSectionSheet(componentKey) {
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const component = location && location.component
    if (!component || component.componentType !== 'TEXT_SECTION') return
    this.setData({
      textSectionSheetVisible: true,
      textSectionEditingComponentKey: componentKey,
      ...buildTextSectionEditorState(component.config)
    })
  },
  handleCloseTextSectionSheet() {
    this.setData({
      textSectionSheetVisible: false,
      textSectionEditingComponentKey: '',
      ...buildTextSectionEditorState()
    })
  },
  handleTextSectionInput(event) {
    const textSectionForm = Object.assign({}, this.data.textSectionForm, { content: event.detail.value || '' })
    this.setData({ textSectionForm, textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm) })
  },
  handleTextSectionAlignmentTap(event) {
    const alignment = event.currentTarget.dataset.value
    if (!TEXT_SECTION_ALIGNMENT_OPTIONS.some((item) => item.value === alignment)) return
    this.setData({ textSectionForm: Object.assign({}, this.data.textSectionForm, { alignment }) })
  },
  handleTextSectionFontTap(event) {
    const value = event.currentTarget.dataset.value
    const option = this.data.textSectionFontOptions.find(
      (item) => item.value === value
    )
    if (!option || !option.available) return
    const textSectionForm = Object.assign(
      {},
      this.data.textSectionForm,
      { fontFamily: value }
    )
    this.setData({
      textSectionForm,
      textSectionTypography: buildPortfolioTextTypography(
        textSectionForm,
        LEGACY_TEAM_FONT_SIZE_RPX
      )
    })
  },
  handleTextSectionFontSizeTap(event) {
    const value = event.currentTarget.dataset.value
    const option = this.data.textSectionSizeOptions.find(
      (item) => item.value === value
    )
    if (!option || typeof value !== 'number') return
    const textSectionForm = Object.assign(
      {},
      this.data.textSectionForm,
      { fontSizeRpx: value }
    )
    this.setData({
      textSectionForm,
      textSectionSizeOptions:
        buildPortfolioTextFontSizeOptions(textSectionForm.fontSizeRpx),
      textSectionTypography: buildPortfolioTextTypography(
        textSectionForm,
        LEGACY_TEAM_FONT_SIZE_RPX
      )
    })
  },
  handleConfirmTextSectionConfig() {
    const form = buildTextSectionForm(this.data.textSectionForm)
    if (!form.content) return wx.showToast({ title: TEXT_SECTION_REQUIRED_MESSAGE, icon: 'none' })
    const componentKey = this.data.textSectionEditingComponentKey
    const config = updateTextSectionConfig(this.data.config, componentKey, form)
    const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: true })
    this.clearPending()
    this.updateConfig(config)
    this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) })
    this.handleCloseTextSectionSheet()
  },
  openContactFormSheet(componentKey) {
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const component = location && location.component
    if (!component || component.componentType !== 'CONTACT_FORM') return
    this.setData({ contactFormSheetVisible: true, contactFormEditingComponentKey: componentKey, contactFormConfigForm: buildContactFormConfigForm(component.config) })
  },
  handleCloseContactFormSheet() {
    this.setData({ contactFormSheetVisible: false, contactFormEditingComponentKey: '', contactFormConfigForm: buildContactFormConfigForm() })
  },
  handleContactFormDisplayModeTap(event) {
    const displayMode = event.currentTarget.dataset.value
    if (!CONTACT_FORM_DISPLAY_MODE_OPTIONS.some((item) => item.value === displayMode)) return
    this.setData({ contactFormConfigForm: { displayMode } })
  },
  handleConfirmContactFormConfig() {
    const componentKey = this.data.contactFormEditingComponentKey
    const config = updateContactFormConfig(this.data.config, componentKey, this.data.contactFormConfigForm)
    const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: true })
    this.clearPending()
    this.updateConfig(config)
    this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) })
    this.handleCloseContactFormSheet()
  },
  openScheduleQuerySheet(componentKey) {
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const component = location && location.component
    if (!component || component.componentType !== 'SCHEDULE_QUERY') return
    this.setData({ scheduleQuerySheetVisible: true, scheduleQueryEditingComponentKey: componentKey, scheduleQueryForm: buildScheduleQueryForm(component.config) })
  },
  handleCloseScheduleQuerySheet() { this.setData({ scheduleQuerySheetVisible: false, scheduleQueryEditingComponentKey: '', scheduleQueryForm: buildScheduleQueryForm() }) },
  handleScheduleQueryDisplayModeTap(event) {
    const displayMode = event.currentTarget.dataset.value
    if (SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS.some((item) => item.value === displayMode)) this.setData({ scheduleQueryForm: { displayMode } })
  },
  handleConfirmScheduleQueryConfig() {
    const componentKey = this.data.scheduleQueryEditingComponentKey
    const config = updateScheduleQueryConfig(this.data.config, componentKey, this.data.scheduleQueryForm)
    const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: true })
    this.clearPending(); this.updateConfig(config)
    this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) })
    this.handleCloseScheduleQuerySheet()
  },
  openDividerSheet(componentKey) {
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const component = location && location.component
    if (!component || component.componentType !== 'DIVIDER') return
    this.setData({ dividerSheetVisible: true, dividerEditingComponentKey: componentKey, dividerForm: buildDividerForm(component.config) })
  },
  handleCloseDividerSheet() { this.setData({ dividerSheetVisible: false, dividerEditingComponentKey: '', dividerForm: buildDividerForm() }) },
  handleDividerColorTap(event) {
    const color = event.currentTarget.dataset.value
    if (DIVIDER_COLOR_OPTIONS.some((item) => item.value === color)) this.setData({ dividerForm: Object.assign({}, this.data.dividerForm, { color }) })
  },
  handleDividerHeightInput(event) { this.setData({ dividerForm: Object.assign({}, this.data.dividerForm, { heightPx: event.detail.value }) }) },
  handleConfirmDividerConfig() {
    const form = buildDividerForm(this.data.dividerForm)
    const componentKey = this.data.dividerEditingComponentKey
    const config = updateDividerConfig(this.data.config, componentKey, form)
    const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: true })
    this.clearPending(); this.updateConfig(config)
    this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) })
    this.handleCloseDividerSheet()
  },
  handleComponentTouchStart(event) { const point = touchPoint(event); this.setData({ componentTouchStart: { key: event.currentTarget.dataset.key || '', index: Number(event.currentTarget.dataset.index), x: point.x, y: point.y } }) },
  handleComponentDragStart(event) { const index = Number(event.currentTarget.dataset.index); if (!Number.isFinite(index)) return; const point = touchPoint(event); this.componentDragRows = []; if (wx.createSelectorQuery) wx.createSelectorQuery().in(this).selectAll('.component-row').boundingClientRect((rows = []) => { this.componentDragRows = rows }).exec(); this.setData({ draggingIndex: index, dragTargetIndex: index, componentDragStartY: point.y, componentDragStyle: dragStyle(0), revealedComponentKey: '' }) },
  handleComponentTouchMove(event) { if (this.data.draggingIndex < 0) return; const point = touchPoint(event); const rows = this.componentDragRows || []; const components = getTeamMenuComponentList(this.data.config, this.data.activeMenuKey); let targetIndex; if (rows.length) { targetIndex = rows.findIndex((row) => point.y < row.top + row.height / 2); if (targetIndex < 0) targetIndex = rows.length - 1 } else { const delta = point.y - this.data.componentDragStartY; targetIndex = Math.max(0, Math.min(components.length - 1, this.data.draggingIndex + Math.round(delta / DRAG_ROW_FALLBACK_HEIGHT))) } this.setData({ dragTargetIndex: targetIndex, componentDragStyle: dragStyle(point.y - this.data.componentDragStartY) }) },
  handleComponentTouchEnd(event) { if (this.data.draggingIndex >= 0) return this.handleComponentDragEnd(); const start = this.data.componentTouchStart; if (!start || !start.key) return; const point = touchPoint(event); const deltaX = point.x - start.x; const deltaY = Math.abs(point.y - start.y); if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) this.setData({ revealedComponentKey: start.key, componentTouchStart: null }); else if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) this.setData({ revealedComponentKey: '', componentTouchStart: null }); else this.setData({ componentTouchStart: null }) },
  handleComponentTouchCancel() { if (this.data.draggingIndex >= 0) return this.handleComponentDragEnd(); this.setData({ componentTouchStart: null }) },
  handleComponentDragEnd() { const from = this.data.draggingIndex; const to = this.data.dragTargetIndex; if (from >= 0 && to >= 0 && from !== to) { const components = getTeamMenuComponentList(this.data.config, this.data.activeMenuKey); const moved = components.splice(from, 1)[0]; components.splice(to, 0, moved); this.clearPending(); this.updateConfig(replaceTeamMenuComponentList(this.data.config, this.data.activeMenuKey, components)) } this.componentDragRows = []; this.setData({ draggingIndex: -1, dragTargetIndex: -1, componentDragStartY: 0, componentDragStyle: '', componentTouchStart: null }) },
  handleRemoveComponent(event) { this.handleComponentDelete(event); this.setData({ revealedComponentKey: '' }) },
  handleShareInput(event) { const field = event.currentTarget.dataset.field; this.clearPending(); this.updateConfig(Object.assign({}, this.data.config, { share: Object.assign({}, this.data.config.share, { [field]: event.detail.value }) })) },
  handleBackgroundColorTap(event) {
    if (!this.data.canMaintain) return
    const backgroundColor = normalizeTeamHexColor(event.currentTarget.dataset.color)
    this.clearPending()
    this.updateConfig(Object.assign({}, this.data.config, { style: { backgroundColor } }))
  },
  handleOpenBackgroundColorSheet() {
    if (!this.data.canMaintain) return
    const backgroundColor = normalizeTeamHexColor(
      this.data.config.style && this.data.config.style.backgroundColor
    )
    this.setData(Object.assign({
      backgroundColorSheetVisible: true
    }, buildTeamBackgroundColorPickerState(hexToHsv(backgroundColor))))
  },
  handleCloseBackgroundColorSheet() {
    this.setData({ backgroundColorSheetVisible: false })
  },
  handleBackgroundHueChange(event) {
    this.setData(buildTeamBackgroundColorPickerState(Object.assign(
      {},
      this.data.backgroundColorHsv,
      { hue: Number(event.detail.value) || 0 }
    )))
  },
  handleBackgroundColorPadTouch(event) {
    const touch = event && event.touches && event.touches[0]
    if (!touch || !wx.createSelectorQuery) return
    wx.createSelectorQuery()
      .in(this)
      .select('.team-background-color-pad')
      .boundingClientRect((rect) => {
        if (!rect || !rect.width || !rect.height) return
        const saturation = Math.min(1, Math.max(0, (Number(touch.clientX) - rect.left) / rect.width))
        const value = 1 - Math.min(1, Math.max(0, (Number(touch.clientY) - rect.top) / rect.height))
        this.setData(buildTeamBackgroundColorPickerState(Object.assign(
          {},
          this.data.backgroundColorHsv,
          { saturation, value }
        )))
      })
      .exec()
  },
  handleBackgroundHexInput(event) {
    const backgroundColorDraft = String(event.detail.value || '').trim().toUpperCase()
    if (/^#[0-9A-F]{6}$/.test(backgroundColorDraft)) {
      this.setData(buildTeamBackgroundColorPickerState(hexToHsv(backgroundColorDraft)))
      return
    }
    this.setData({ backgroundColorDraft })
  },
  handleBackgroundHexBlur() {
    if (!/^#[0-9A-F]{6}$/.test(this.data.backgroundColorDraft)) {
      wx.showToast({ title: '请输入正确的颜色值', icon: 'none' })
    }
  },
  handleConfirmBackgroundColor() {
    if (!this.data.canMaintain) return
    if (!/^#[0-9A-F]{6}$/.test(this.data.backgroundColorDraft)) {
      wx.showToast({ title: '请输入正确的颜色值', icon: 'none' })
      return
    }
    this.clearPending()
    this.updateConfig(Object.assign({}, this.data.config, {
      style: { backgroundColor: this.data.backgroundColorDraft }
    }))
    this.setData({ backgroundColorSheetVisible: false })
  },
  handleBottomNavigationCountTap(event) {
    if (!this.data.canMaintain) return
    const count = Number(event.currentTarget.dataset.count)
    if (!TEAM_BOTTOM_NAV_COUNTS.includes(count)) return
    const currentItems = this.data.config.bottomNav.enabled ? this.data.config.bottomNav.items : []
    const activeMenuIndex = currentItems.findIndex((item) => item.key === this.data.activeMenuKey)
    const applyCount = () => {
      this.clearPending()
      const config = setTeamBottomNavigationCount(this.data.config, count)
      const nextItems = config.bottomNav.enabled ? config.bottomNav.items : []
      const nextMenuKey = nextItems.some((item) => item.key === this.data.activeMenuKey)
        ? this.data.activeMenuKey
        : ((nextItems[Math.min(
            Math.max(activeMenuIndex - 1, 0),
            nextItems.length - 1
          )] || {}).key || '')
      this.setData({ activeMenuKey: nextMenuKey, revealedComponentKey: '' })
      this.updateConfig(config)
    }
    if (count >= currentItems.length) return applyCount()
    const removedItems = count < 2 ? currentItems.slice(1) : currentItems.slice(count)
    const componentCount = removedItems.reduce((total, item) => {
      return total + getTeamMenuComponentList(this.data.config, item.key).length
    }, 0)
    if (!componentCount) return applyCount()
    wx.showModal({
      title: '减少底部菜单？',
      content: `将同时删除末尾菜单中的 ${componentCount} 个组件`,
      confirmText: '确认',
      confirmColor: '#b55656',
      success: (result) => { if (result.confirm) applyCount() }
    })
  },
  handleMenuTabTap(event) {
    const menuKey = event.currentTarget.dataset.key || ''
    this.setData({ activeMenuKey: menuKey, revealedComponentKey: '', highlightedComponentKey: '', componentScrollTarget: '' })
    this.updateConfig(this.data.config)
  },
  handleNavigationTitleInput(event) {
    if (!this.data.canMaintain) return
    const menuKey = event.currentTarget.dataset.key || ''
    this.clearPending()
    this.updateConfig(renameTeamNavigationItem(this.data.config, menuKey, event.detail.value))
  },
  handleRemoveNavigationItem(event) {
    if (!this.data.canMaintain) return
    const menuKey = event.currentTarget.dataset.key || ''
    const items = this.data.config.bottomNav.enabled ? this.data.config.bottomNav.items : []
    const menuIndex = items.findIndex((item) => item.key === menuKey)
    if (menuIndex < 0 || items.length <= 2) return
    const menu = items[menuIndex]
    const componentCount = getTeamMenuComponentList(this.data.config, menuKey).length
    const applyRemove = () => {
      const fallback = items[menuIndex - 1] || items[menuIndex + 1]
      this.setData({ activeMenuKey: fallback ? fallback.key : '', revealedComponentKey: '' })
      this.clearPending()
      this.updateConfig(removeTeamNavigationItem(this.data.config, menuKey))
    }
    const promotionWarning = menuIndex === 0
      ? '删除后，第二个菜单将晋升为第一个菜单，旧版本将展示晋升后的菜单内容。'
      : ''
    const componentWarning = componentCount
      ? `将同时删除其下 ${componentCount} 个组件。`
      : ''
    if (menuIndex !== 0 && !componentCount) return applyRemove()
    wx.showModal({
      title: `删除菜单「${menu.title}」？`,
      content: [promotionWarning, componentWarning].filter(Boolean).join('\n'),
      confirmText: '删除',
      confirmColor: '#b55656',
      success: (result) => { if (result.confirm) applyRemove() }
    })
  },
  handleOpenComponentMoveSheet(event) {
    if (!this.data.config.bottomNav.enabled ||
        this.data.componentMovePending ||
        this.data.saving ||
        this.data.publishing ||
        this.data.shareCoverUploading ||
        this.data.qrContactChoosing ||
        this.data.qrContactCropVisible ||
        this.data.backgroundColorSheetVisible ||
        this.data.componentSheetVisible ||
        this.data.componentEditorVisible ||
        this.data.textSectionSheetVisible ||
        this.data.contactFormSheetVisible ||
        this.data.scheduleQuerySheetVisible ||
        this.data.dividerSheetVisible) return
    const componentKey = event.currentTarget.dataset.key || ''
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    if (!location) return
    const componentMoveTargets = this.data.config.bottomNav.items
      .filter((item) => item.key !== location.menuKey)
      .map((item) => ({
        key: item.key,
        title: item.title,
        componentCount: getTeamMenuComponentList(this.data.config, item.key).length
      }))
    this.setData({
      componentMoveSheetVisible: true,
      componentMoveKey: componentKey,
      componentMoveTargets,
      revealedComponentKey: ''
    })
  },
  handleCloseComponentMoveSheet() {
    if (this.data.componentMovePending) return
    this.setData({ componentMoveSheetVisible: false, componentMoveKey: '', componentMoveTargets: [] })
  },
  handleMoveTargetTap(event) {
    if (this.data.componentMovePending) return
    const componentKey = this.data.componentMoveKey
    const targetMenuKey = event.currentTarget.dataset.key || ''
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const items = this.data.config.bottomNav.enabled ? this.data.config.bottomNav.items : []
    if (!location || !targetMenuKey || !items.some((item) => item.key === targetMenuKey)) {
      wx.showToast({ title: '组件移动失败，请重试', icon: 'none' })
      return
    }
    if (targetMenuKey === location.menuKey) return
    const sourceIndex = items.findIndex((item) => item.key === location.menuKey)
    const sourceComponents = getTeamMenuComponentList(this.data.config, location.menuKey)
    const enabledCount = sourceComponents.filter((item) => item.enabled !== false).length
    if (sourceIndex === 0 && location.component.enabled !== false && enabledCount <= 1) {
      wx.showToast({ title: '第一个菜单至少保留一个组件', icon: 'none' })
      return
    }
    this.setData({ componentMovePending: true, activeMenuKey: targetMenuKey })
    this.clearPending()
    this.updateConfig(moveTeamComponent(
      this.data.config,
      componentKey,
      location.menuKey,
      targetMenuKey
    ))
    this.setData({
      componentMovePending: false,
      componentMoveSheetVisible: false,
      componentMoveKey: '',
      componentMoveTargets: [],
      highlightedComponentKey: componentKey,
      componentScrollTarget: `team-component-${componentKey}`
    })
    if (this.componentHighlightTimer) clearTimeout(this.componentHighlightTimer)
    this.componentHighlightTimer = setTimeout(() => {
      this.setData({ highlightedComponentKey: '', componentScrollTarget: '' })
      this.componentHighlightTimer = null
    }, 2200)
  },
  handleComponentConfigChange(event) {
    const detail = event.detail || {}
    const componentKey = detail.componentKey || (event.currentTarget && event.currentTarget.dataset.key)
    const location = findTeamPortfolioComponent(this.data.config, componentKey)
    const config = updateTeamComponent(this.data.config, componentKey, (component) => Object.assign({}, component, {
      config: detail.config || Object.assign({}, component.config, detail.field ? { [detail.field]: detail.value } : {})
    }))
    this.clearPending()
    const changed = location && location.component
    if (changed && changed.componentType === 'TEXT_SECTION' && detail.field) { const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: false }); this.setData({ componentValidation, hasInvalidComponents: true }) }
    this.updateConfig(config)
  },
  handleComponentValidationChange(event) { const detail = event.detail || {}; const componentValidation = Object.assign({}, this.data.componentValidation, { [detail.componentKey]: detail.valid !== false }); this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) }) },
  handleComponentSave(event) { const key = event.currentTarget.dataset.key; const location = findTeamPortfolioComponent(this.data.config, key); this.handleComponentConfigChange(event); if (location && location.component.componentType === 'SINGLE_WORK') this.updateSingleWorkRowSummary(key, event.detail && event.detail.config); const componentValidation = Object.assign({}, this.data.componentValidation, { [key]: true }); this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((name) => componentValidation[name] === false) }); if (this.data.activeComponentKey === key) this.handleCloseComponentEditor() },
  handleConfirmComponentEditor() { if (this.data.activeComponentType === 'VIDEO_CAROUSEL') return this.saveTeamVideoCarouselConfig(); const component = this.selectComponent('#active-component-editor'); if (component && typeof component.saveEdit === 'function') component.saveEdit() },
  handleComponentDelete(event) {
    const key = event.currentTarget.dataset.key
    const location = findTeamPortfolioComponent(this.data.config, key)
    if (!location) return
    this.clearPending()
    const validation = Object.assign({}, this.data.componentValidation)
    delete validation[key]
    this.setData({ componentValidation: validation, hasInvalidComponents: Object.keys(validation).some((name) => validation[name] === false) })
    this.updateConfig(replaceTeamMenuComponentList(
      this.data.config,
      location.menuKey,
      getTeamMenuComponentList(this.data.config, location.menuKey)
        .filter((item) => item.componentKey !== key)
    ))
  },
  handleMove(event) {
    const key = event.currentTarget.dataset.key
    const direction = Number(event.currentTarget.dataset.direction)
    const components = getTeamMenuComponentList(this.data.config, this.data.activeMenuKey)
    const index = components.findIndex((item) => item.componentKey === key)
    const target = index + direction
    if (index < 0 || target < 0 || target >= components.length) return
    this.clearPending()
    ;[components[index], components[target]] = [components[target], components[index]]
    this.updateConfig(replaceTeamMenuComponentList(this.data.config, this.data.activeMenuKey, normalizeSortOrders(components)))
  },
  handleMaintainTeam(event) { const teamId = Number(event.currentTarget.dataset.teamId || this.data.teamId); if (teamId) wx.navigateTo({ url: `/pages/team-maintenance/team-maintenance?teamId=${teamId}` }) },
  async handleTeamProfileChooseAvatar() {
    if (!wx.chooseMedia) return
    try {
      const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: resolve, fail: reject }))
      const file = media && Array.isArray(media.tempFiles) && media.tempFiles[0]
      if (!file || !file.tempFilePath) throw new Error('未选择有效图片')
      const child = this.selectComponent('#active-component-editor')
      if (child && child.applyAvatar) child.applyAvatar({ detail: { avatarUrl: file.tempFilePath } })
    } catch (error) {
      if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '图片选择失败，请重试', icon: 'none' })
    }
  },
  async handleTeamProfileRefresh() {
    if (this.data.teamProfileRefreshing || !this.data.teamId) return
    this.setData({ teamProfileRefreshing: true })
    const child = this.selectComponent('#active-component-editor')
    try {
      const detail = await request({ url: `/api/mine/teams/${this.data.teamId}` })
      const team = normalizeTeamSnapshot(this.data.teamId, detail && detail.team)
      if (child && child.applyTeamSnapshot) child.applyTeamSnapshot({ detail: { team } })
    } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return
      if (showTeamPortfolioUnavailableToast(error)) return
      if (child && child.applyRefreshError) child.applyRefreshError({ detail: { message: '团队资料刷新失败，请重试' } })
    } finally { this.setData({ teamProfileRefreshing: false }) }
  },
  handleOpenLibrary() { this.setData({ openingLibrary: false }); this.handleOpenComponentSheet() },
  addComponent(componentType) {
    if (!componentType) { this.setData({ openingLibrary: false }); return '' }
    this.clearPending()
    const componentKey = makeKey()
    const componentConfig = componentType === 'TEAM_PROFILE'
      ? { team: Object.assign({}, this.data.teamSnapshot) }
      : componentType === 'SINGLE_WORK'
        ? { memberUserId: null, workId: null, showTitle: true, showDescription: false }
        : componentType === 'VIDEO_CAROUSEL'
          ? normalizeTeamVideoCarouselConfig()
        : MEMBER_PORTFOLIO_COMPONENT_TYPES.includes(componentType)
          ? { showMemberName: true }
          : componentType === 'TEXT_SECTION'
            ? {
                fontFamily: PORTFOLIO_TEXT_FONT_FAMILIES.SYSTEM,
                fontSizeRpx: NEW_COMPONENT_FONT_SIZE_RPX
              }
            : {}
    const components = getTeamMenuComponentList(this.data.config, this.data.activeMenuKey)
    components.push({ componentKey, componentType, sortOrder: (components.length + 1) * 1000, enabled: true, config: componentConfig })
    this.updateConfig(replaceTeamMenuComponentList(this.data.config, this.data.activeMenuKey, components))
    this.setData({ componentValidation: Object.assign({}, this.data.componentValidation, { [componentKey]: false }), hasInvalidComponents: true, openingLibrary: false })
    return componentKey
  },
  updateSource(componentKey, patch) { const componentSources = Object.assign({}, this.data.componentSources, { [componentKey]: Object.assign({}, this.data.componentSources[componentKey], patch) }); const state = { componentSources }; if (componentKey === this.data.activeComponentKey) state.activeComponentSource = componentSources[componentKey]; this.setData(state) },
  async loadSource(componentKey, fingerprint, url, patch, failureState, scopeId = this.data.portfolioId) { if (!Number(scopeId)) return; const source = this.data.componentSources[componentKey] || {}; if (source.loadingFingerprint === fingerprint) return; this.updateSource(componentKey, { loadingFingerprint: fingerprint, errorMessage: '', sourceAvailable: true }); try { const value = await request({ url }); if ((this.data.componentSources[componentKey] || {}).loadingFingerprint !== fingerprint) return; this.updateSource(componentKey, Object.assign({}, patch(value), { loadingFingerprint: '', failedStage: '', memberUserId: 0 })) } catch (error) { if ((this.data.componentSources[componentKey] || {}).loadingFingerprint !== fingerprint) return; if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) { this.updateSource(componentKey, Object.assign({ loadingFingerprint: '', errorMessage: '', sourceAvailable: false }, failureState || {})); return }; this.updateSource(componentKey, Object.assign({ loadingFingerprint: '', errorMessage: '来源加载失败，请重试', sourceAvailable: false }, failureState || {})) } },
  async handleCarouselLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'carousel-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members`, (members) => ({ members, works: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleCarouselMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { works: [], memberUserId, failedStage: '' }); return this.loadSource(key, `carousel-works-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members/${memberUserId}/works`, (works) => ({ works: Array.isArray(works) ? works : [] }), { failedStage: 'member-items', memberUserId }) },
  handleCarouselLayoutChange(event) {
    if (!this.data.componentEditorVisible || this.data.activeComponentType !== 'CAROUSEL') return
    this.setData(buildCarouselEditorLayoutState(event && event.detail && event.detail.scrollHeightRpx))
  },
  handleCarouselRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleCarouselMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleCarouselLoadMembers(event) },
  async handleSingleWorkLoadMembers(event) {
    const key = event.currentTarget.dataset.key
    return this.loadSource(
      key,
      'single-work-members',
      `/api/mine/teams/${this.data.teamId}/portfolio-components/single-work/members`,
      (members) => ({
        members,
        works: [],
        selectedWork: null,
        singleWorkPage: 0,
        singleWorkPageSize: SINGLE_WORK_PAGE_SIZE,
        singleWorkHasMore: false,
        singleWorkLoading: false,
        singleWorkLoadingMore: false,
        singleWorkLoadMoreError: ''
      }),
      { failedStage: 'members', memberUserId: 0 },
      this.data.teamId
    )
  },
  async loadSingleWorkPage(componentKey, memberUserId, options = {}) {
    const normalizedMemberUserId = Number(memberUserId)
    if (!Number(this.data.teamId) || !normalizedMemberUserId) return
    const source = this.data.componentSources[componentKey] || {}
    const reset = options.reset !== false
    const pageSize = reset
      ? SINGLE_WORK_PAGE_SIZE
      : (Number(source.singleWorkPageSize) || SINGLE_WORK_PAGE_SIZE)
    const requestPage = reset ? 1 : (Number(source.singleWorkPage) || 0) + 1
    const selectedWorkId = reset ? (Number(options.selectedWorkId) || null) : null
    const requestSeq = (Number(this.singleWorkRequestSeq) || 0) + 1
    this.singleWorkRequestSeq = requestSeq
    const fingerprint = `${componentKey}:${normalizedMemberUserId}:${requestPage}:${requestSeq}`
    this.updateSource(componentKey, reset ? {
      works: [],
      selectedWork: null,
      memberUserId: normalizedMemberUserId,
      singleWorkPage: 0,
      singleWorkPageSize: pageSize,
      singleWorkHasMore: false,
      singleWorkLoading: true,
      singleWorkLoadingMore: false,
      singleWorkLoadMoreError: '',
      singleWorkSelectedWorkId: selectedWorkId,
      singleWorkRequestFingerprint: fingerprint,
      errorMessage: '',
      failedStage: '',
      sourceAvailable: true
    } : {
      memberUserId: normalizedMemberUserId,
      singleWorkLoadingMore: true,
      singleWorkLoadMoreError: '',
      singleWorkRequestFingerprint: fingerprint
    })
    try {
      const response = await request({
        url: `/api/mine/teams/${this.data.teamId}/portfolio-components/single-work/members/${normalizedMemberUserId}/works/page`,
        data: Object.assign({ page: requestPage, pageSize }, selectedWorkId
          ? { selectedWorkId }
          : {})
      })
      const latest = this.data.componentSources[componentKey] || {}
      if (
        this.singleWorkRequestSeq !== requestSeq ||
        latest.singleWorkRequestFingerprint !== fingerprint ||
        Number(latest.memberUserId) !== normalizedMemberUserId
      ) return
      const incomingWorks = Array.isArray(response && response.works) ? response.works : []
      this.updateSource(componentKey, {
        works: reset ? mergeSingleWorkPage([], incomingWorks) : mergeSingleWorkPage(latest.works, incomingWorks),
        selectedWork: reset ? (response && response.selectedWork || null) : latest.selectedWork,
        memberUserId: normalizedMemberUserId,
        singleWorkPage: Number(response && response.page) || requestPage,
        singleWorkPageSize: Number(response && response.pageSize) || pageSize,
        singleWorkHasMore: Boolean(response && response.hasMore),
        singleWorkLoading: false,
        singleWorkLoadingMore: false,
        singleWorkLoadMoreError: '',
        singleWorkRequestFingerprint: '',
        errorMessage: '',
        failedStage: '',
        sourceAvailable: true
      })
    } catch (error) {
      const latest = this.data.componentSources[componentKey] || {}
      if (
        this.singleWorkRequestSeq !== requestSeq ||
        latest.singleWorkRequestFingerprint !== fingerprint ||
        Number(latest.memberUserId) !== normalizedMemberUserId
      ) return
      if (handleTeamMaintainerAuthError(error)) {
        this.updateSource(componentKey, {
          singleWorkLoading: false,
          singleWorkLoadingMore: false,
          singleWorkRequestFingerprint: ''
        })
        return
      }
      if (showTeamPortfolioUnavailableToast(error)) {
        this.updateSource(componentKey, {
          singleWorkLoading: false,
          singleWorkLoadingMore: false,
          singleWorkRequestFingerprint: '',
          sourceAvailable: false
        })
        return
      }
      if (reset) {
        this.updateSource(componentKey, {
          singleWorkLoading: false,
          singleWorkLoadingMore: false,
          singleWorkRequestFingerprint: '',
          errorMessage: '来源加载失败，请重试',
          sourceAvailable: false,
          failedStage: 'member-items',
          memberUserId: normalizedMemberUserId
        })
        return
      }
      this.updateSource(componentKey, {
        singleWorkLoadingMore: false,
        singleWorkLoadMoreError: '作品加载失败，请重试',
        singleWorkRequestFingerprint: ''
      })
    }
  },
  async handleSingleWorkMemberChange(event) {
    const key = event.currentTarget.dataset.key
    const memberUserId = Number(event.detail && event.detail.memberUserId)
    const selectedWorkId = Number(event.detail && event.detail.selectedWorkId) || null
    return this.loadSingleWorkPage(key, memberUserId, { reset: true, selectedWorkId })
  },
  handleSingleWorkLoadMore(event) {
    const key = event.currentTarget.dataset.key
    const source = this.data.componentSources[key] || {}
    if (
      !source.memberUserId ||
      source.singleWorkLoading ||
      source.singleWorkLoadingMore ||
      !source.singleWorkHasMore
    ) return Promise.resolve()
    return this.loadSingleWorkPage(key, source.memberUserId, { reset: false })
  },
  handleSingleWorkRetrySource(event) {
    const key = event.currentTarget.dataset.key
    const source = this.data.componentSources[key] || {}
    return source.failedStage === 'member-items' && source.memberUserId
      ? this.loadSingleWorkPage(key, source.memberUserId, {
          reset: true,
          selectedWorkId: source.singleWorkSelectedWorkId
        })
      : this.handleSingleWorkLoadMembers(event)
  },
  async handleGridLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'grid-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-grid/members`, (members) => ({ members, portfolios: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleGridMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { portfolios: [], memberUserId, failedStage: '' }); return this.loadSource(key, `grid-portfolios-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-grid/members/${memberUserId}/portfolios`, (portfolios) => ({ portfolios }), { failedStage: 'member-items', memberUserId }) },
  handleGridRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleGridMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleGridLoadMembers(event) },
  async handleListLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'list-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-list/members`, (members) => ({ members, portfolios: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleListMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { portfolios: [], memberUserId, failedStage: '' }); return this.loadSource(key, `list-portfolios-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-list/members/${memberUserId}/portfolios`, (portfolios) => ({ portfolios }), { failedStage: 'member-items', memberUserId }) },
  handleListRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleListMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleListLoadMembers(event) },
  applyShareCover(coverUrl) { this.clearPending(); this.updateConfig(Object.assign({}, this.data.config, { share: Object.assign({}, this.data.config.share, { coverUrl }) })) },
  async handleCoverChoose() {
    if (this.data.shareCoverUploading || !this.data.canMaintain || !wx.chooseMedia) return
    if (typeof wx.cropImage === 'function') {
      try {
        const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: resolve, fail: reject }))
        const file = media && Array.isArray(media.tempFiles) && media.tempFiles[0]
        if (!file || !file.tempFilePath) throw new Error('未选择有效图片')
        const previewPath = await resolveTeamCoverPreviewPath(file.tempFilePath)
        this.setData({ shareCoverCropVisible: true, shareCoverCropPath: previewPath })
      } catch (error) { if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '图片选择失败，请重试', icon: 'none' }) }
      return
    }
    try {
      const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: resolve, fail: reject }))
      const file = media && Array.isArray(media.tempFiles) && media.tempFiles[0]
      if (!file || !file.tempFilePath) throw new Error('未选择有效图片')
      this.applyShareCover(file.tempFilePath)
    } catch (error) { if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '图片选择失败，请重试', icon: 'none' }) }
  },
  handleCloseShareCoverCrop() { if (this.data.shareCoverUploading) return; this.setData({ shareCoverCropVisible: false, shareCoverCropPath: '' }) },
  async handleConfirmShareCoverCrop() {
    if (this.data.shareCoverUploading || !this.data.shareCoverCropPath) return
    this.setData({ shareCoverUploading: true })
    try {
      const result = await new Promise((resolve, reject) => wx.cropImage({ src: this.data.shareCoverCropPath, cropScale: '5:4', success: resolve, fail: reject }))
      const filePath = result && (result.tempFilePath || result.path)
      if (!filePath) throw new Error('封面裁剪失败')
      this.applyShareCover(filePath)
      this.setData({ shareCoverCropVisible: false, shareCoverCropPath: '' })
    } catch (error) { if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '封面裁剪失败，请重试', icon: 'none' }) } finally { this.setData({ shareCoverUploading: false }) }
  },
  async handleQrChoose() {
    if (this.data.qrContactChoosing || this.data.shareCoverUploading || !this.data.canMaintain || !wx.chooseMedia) return
    this.setData({ qrContactChoosing: true })
    try {
      const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: resolve, fail: reject }))
      const file = media && Array.isArray(media.tempFiles) && media.tempFiles[0]
      if (!file || !file.tempFilePath) throw new Error('未选择有效图片')
      const imageInfo = await getWechatQrImageInfo(file, { wxApi: wx })
      const qrContactCropState = buildWechatQrCropState(imageInfo, {
        cropBoxWidth: getQrContactCropBoxWidth(wx)
      })
      this.setData({
        qrContactCropVisible: true,
        qrContactCropSaving: false,
        qrContactCropErrorText: '',
        qrContactCropState,
        qrContactCropTouchStart: null
      })
    } catch (error) { if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '图片选择失败，请重试', icon: 'none' }) } finally { this.setData({ qrContactChoosing: false }) }
  },
  handleCloseQrContactCrop() {
    if (this.data.qrContactCropSaving) return
    this.setData(resetQrContactCropState())
  },
  handleQrContactCropTouchStart(event) {
    const point = touchPoint(event)
    const cropState = this.data.qrContactCropState || {}
    this.setData({
      qrContactCropTouchStart: {
        x: point.x,
        y: point.y,
        offsetX: Number(cropState.offsetX) || 0,
        offsetY: Number(cropState.offsetY) || 0
      }
    })
  },
  handleQrContactCropTouchMove(event) {
    const start = this.data.qrContactCropTouchStart
    const cropState = this.data.qrContactCropState
    if (!start || !cropState) return
    const point = touchPoint(event)
    this.setData({
      qrContactCropState: moveWechatQrCropState(Object.assign({}, cropState, {
        offsetX: start.offsetX,
        offsetY: start.offsetY
      }), {
        deltaX: point.x - start.x,
        deltaY: point.y - start.y
      })
    })
  },
  handleQrContactCropTouchEnd() { this.setData({ qrContactCropTouchStart: null }) },
  handleQrContactCropTouchCancel() { this.setData({ qrContactCropTouchStart: null }) },
  async handleConfirmQrContactCrop() {
    if (this.data.qrContactCropSaving || !this.data.qrContactCropState) return
    const cropState = this.data.qrContactCropState
    this.setData({ qrContactCropSaving: true, qrContactCropErrorText: '' })
    try {
      const croppedPath = await cropWechatQrToTempFilePath({
        page: this,
        wxApi: wx,
        canvasId: QR_CONTACT_CROP_CANVAS_ID,
        imagePath: cropState.imagePath,
        cropFrame: buildWechatQrCropFrame(cropState, { outputWidth: WECHAT_QR_CROP_OUTPUT_WIDTH }),
        fileType: WECHAT_QR_CROP_FILE_TYPE,
        quality: WECHAT_QR_CROP_QUALITY
      })
      const child = this.selectComponent('#active-component-editor')
      if (child && child.applySelectedImage) child.applySelectedImage({ detail: { qrUrl: croppedPath } })
      this.setData(resetQrContactCropState())
    } catch (error) {
      if (/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) return
      this.setData({ qrContactCropSaving: false, qrContactCropErrorText: QR_CONTACT_CROP_FAILED_MESSAGE })
      wx.showToast({ title: QR_CONTACT_CROP_FAILED_MESSAGE, icon: 'none' })
    } finally {
      this.setData({ qrContactCropSaving: false })
    }
  },
  hasInvalidComponents() { return Object.keys(this.data.componentValidation).some((key) => this.data.componentValidation[key] === false) },
  async uploadLocalShareCover(portfolioId, config = this.data.config) {
    const normalized = normalizeTeamPortfolioConfig(config)
    const coverUrl = normalized.share && normalized.share.coverUrl
    if (!coverUrl || isRemoteUrl(coverUrl)) return normalized
    this.setData({ shareCoverUploading: true })
    try {
      const uploadedUrl = await uploadTeamPortfolioAsset({ portfolioId, assetType: TEAM_PORTFOLIO_COVER_ASSET_TYPE, clientId: makeKey(), filePath: coverUrl, requestFn: request })
      return normalizeTeamPortfolioConfig(Object.assign({}, normalized, { share: Object.assign({}, normalized.share, { coverUrl: uploadedUrl }) }))
    } finally { this.setData({ shareCoverUploading: false }) }
  },
  async uploadLocalTeamProfileAvatars(portfolioId, config = this.data.config) {
    let normalized = normalizeTeamPortfolioConfig(config)
    const locations = visitTeamPortfolioComponents(normalized)
    for (const location of locations) {
      const component = location.component
      const team = component.config && component.config.team ? component.config.team : {}
      if (component.componentType !== 'TEAM_PROFILE' || !team.avatarUrl || isRemoteUrl(team.avatarUrl)) continue
      const avatarUrl = await uploadTeamPortfolioAsset({
        portfolioId,
        assetType: TEAM_PROFILE_AVATAR_ASSET_TYPE,
        clientId: makeKey(),
        filePath: team.avatarUrl,
        requestFn: request
      })
      normalized = updateTeamComponent(normalized, component.componentKey, (current) => Object.assign({}, current, {
        config: Object.assign({}, component.config, { team: Object.assign({}, team, { avatarUrl }) })
      }))
    }
    return normalized
  },
  async uploadLocalQrContacts(portfolioId, config = this.data.config) {
    let normalized = normalizeTeamPortfolioConfig(config)
    const locations = visitTeamPortfolioComponents(normalized)
    for (const location of locations) {
      const component = location.component
      const qrUrl = component.config && component.config.qrUrl
      if (component.componentType !== 'QR_CONTACT' || !qrUrl || isRemoteUrl(qrUrl)) continue
      const uploadedUrl = await uploadTeamPortfolioAsset({
        portfolioId,
        assetType: TEAM_QR_CONTACT_ASSET_TYPE,
        clientId: makeKey(),
        filePath: qrUrl,
        requestFn: request
      })
      normalized = updateTeamComponent(normalized, component.componentKey, (current) => Object.assign({}, current, {
        config: { qrUrlSource: TEAM_QR_CONTACT_SOURCE_CUSTOM, qrUrl: uploadedUrl }
      }))
      this.updateConfig(normalized)
    }
    return normalized
  },
  async saveDraft(forPublish = false) {
    if (this.data.saving || (this.data.publishing && !forPublish) || !this.data.canMaintain || this.hasInvalidComponents()) return null
    const title = this.data.config && this.data.config.share && this.data.config.share.title
    if (!String(title || '').trim()) {
      wx.showToast({ title: TEAM_PORTFOLIO_TITLE_REQUIRED_MESSAGE, icon: 'none' })
      return null
    }
    this.setData({ saving: true })
    let failureStage = 'create'
    try {
      const isNew = !this.data.portfolioId
      let portfolioId = this.data.portfolioId
      let clientRevision = this.data.draftRevision
      let publicationStatus = this.data.publicationStatus
      if (isNew) {
        const created = await createStandardTeamPortfolio(request, this.data.teamId, buildServerSafeTeamPortfolioConfig(this.data.config))
        portfolioId = Number(created && created.portfolioId) || 0
        if (!portfolioId) throw new Error('团队作品集创建失败')
        clientRevision = Number(created && created.draftRevision) || 0
        publicationStatus = (created && created.publicationStatus) || publicationStatus
        this.setData(Object.assign({ portfolioId, draftRevision: clientRevision, publicationStatus }, buildPublicationState(publicationStatus)))
      }
      if (!portfolioId) throw new Error('团队作品集创建失败')
      failureStage = 'upload'
      const coverUploadedConfig = await this.uploadLocalShareCover(portfolioId, this.data.config)
      this.updateConfig(coverUploadedConfig)
      const profileUploadedConfig = await this.uploadLocalTeamProfileAvatars(portfolioId, coverUploadedConfig)
      this.updateConfig(profileUploadedConfig)
      const uploadedConfig = await this.uploadLocalQrContacts(portfolioId, profileUploadedConfig)
      this.updateConfig(uploadedConfig)
      failureStage = 'save'
      const idempotencyKey = this.data.pendingDraftKey || makeIdempotencyKey('team-draft')
      if (!this.data.pendingDraftKey) this.setData({ pendingDraftKey: idempotencyKey })
      const result = await saveTeamPortfolioDraft(request, portfolioId, uploadedConfig, clientRevision, idempotencyKey)
      const draftRevision = Number(result && result.draftRevision) || clientRevision
      const config = result && result.config ? normalizeTeamPortfolioConfig(result.config) : uploadedConfig
      publicationStatus = (result && result.publicationStatus) || publicationStatus
      this.setData(Object.assign({ portfolioId, draftRevision, publicationStatus, pendingDraftKey: '' }, buildPublicationState(publicationStatus)))
      this.updateConfig(config)
      return result
    } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return null
      if (showTeamPortfolioUnavailableToast(error)) {
        this.setData({ pendingDraftKey: '' })
        return null
      }
      if (!isUncertainFailure(error)) this.setData({ pendingDraftKey: '' })
      wx.showToast({
        title: failureStage === 'upload'
          ? '素材上传失败，请重试'
          : serverBusinessMessage(error, '保存失败，请检查组件配置'),
        icon: 'none'
      })
      return null
    } finally { this.setData({ saving: false }) }
  },
  async handleSaveTap() {
    const saved = await this.saveDraft()
    if (saved) this.returnToPortfolioList()
  },
  returnToPortfolioList() {
    const delta = resolvePortfolioListBackDelta()
    if (delta > 0) return wx.navigateBack({ delta })
    wx.redirectTo({ url: TEAM_PORTFOLIOS_COMPAT_PAGE_URL })
  },
  handlePreviewTap() { if (!this.data.portfolioId) return; wx.navigateTo({ url: `/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=${this.data.portfolioId}&scope=draft` }) },
  async handlePublishTap() {
    if (this.data.publishing || !this.data.canMaintain) return
    const validation = validateTeamPortfolioForPublish(this.data.config)
    if (!validation.valid) {
      if (validation.menuKey) this.setData({ activeMenuKey: validation.menuKey })
      this.updateConfig(this.data.config)
      this.setData({
        highlightedComponentKey: validation.componentKey || '',
        componentScrollTarget: validation.componentKey
          ? `team-component-${validation.componentKey}`
          : ''
      })
      wx.showToast({ title: validation.message, icon: 'none' })
      return
    }
    const confirmed = await confirmPortfolioPublishDisclaimer()
    if (!confirmed) return
    this.setData({ publishing: true })
    try {
      if (this.data.pendingPublishKey) {
        const result = await publishTeamPortfolio(request, this.data.portfolioId, this.data.pendingPublishRevision, this.data.pendingPublishKey)
        const publicationStatus = result.publicationStatus || 'PUBLISHED'
        this.setData(Object.assign({ publicationStatus, publishedRevision: Number(result.publishedRevision) || this.data.publishedRevision, pendingPublishKey: '', pendingPublishRevision: 0 }, buildPublicationState(publicationStatus)))
        wx.showToast({ title: '已发布', icon: 'success' })
        this.returnToPortfolioList()
        return
      }
      const saved = await this.saveDraft(true)
      if (!saved) return
      const revision = Number(saved.draftRevision) || this.data.draftRevision
      const key = makeIdempotencyKey('team-publish')
      this.setData({ pendingPublishKey: key, pendingPublishRevision: revision })
      const result = await publishTeamPortfolio(request, this.data.portfolioId, revision, key)
      const publicationStatus = result.publicationStatus || 'PUBLISHED'
      this.setData(Object.assign({ publicationStatus, publishedRevision: Number(result.publishedRevision) || this.data.publishedRevision, pendingPublishKey: '', pendingPublishRevision: 0 }, buildPublicationState(publicationStatus)))
      wx.showToast({ title: '已发布', icon: 'success' })
      this.returnToPortfolioList()
    } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return
      if (showTeamPortfolioUnavailableToast(error)) {
        this.setData({ pendingPublishKey: '', pendingPublishRevision: 0 })
        return
      }
      if (!isUncertainFailure(error)) this.setData({ pendingPublishKey: '', pendingPublishRevision: 0 })
      wx.showToast({
        title: serverBusinessMessage(error, '发布失败，请重试'),
        icon: 'none'
      })
    } finally { this.setData({ publishing: false }) }
  }
})
