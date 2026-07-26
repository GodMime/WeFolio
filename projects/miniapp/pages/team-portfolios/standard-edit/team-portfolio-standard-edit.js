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
const { createStandardTeamPortfolio, fetchTeamPortfolioDetail, handleTeamMaintainerAuthError, normalizeTeamPortfolioConfig, publishTeamPortfolio, saveTeamPortfolioDraft, showTeamPortfolioUnavailableToast } = require('../utils/team-portfolios.js')
const { confirmPortfolioPublishDisclaimer } = require('../utils/portfolio-publish-disclaimer.js')

const TYPE_BUCKETS = Object.freeze({ TEAM_PROFILE: 'teamProfile', CAROUSEL: 'carousel', SINGLE_WORK: 'singleWork', DIVIDER: 'divider', MEMBER_PORTFOLIO_GRID: 'grid', MEMBER_PORTFOLIO_LIST: 'list', TEXT_SECTION: 'text', SCHEDULE_QUERY: 'schedule', CONTACT_FORM: 'contact', QR_CONTACT: 'qr' })
const COMPONENT_NAMES = Object.freeze({ TEAM_PROFILE: '团队资料', CAROUSEL: '轮播图', SINGLE_WORK: '单个作品', DIVIDER: '分割线', MEMBER_PORTFOLIO_GRID: '双列作品集', MEMBER_PORTFOLIO_LIST: '单列作品集', TEXT_SECTION: '文字说明', SCHEDULE_QUERY: '档期查询', CONTACT_FORM: '预留联系信息', QR_CONTACT: '二维码联系' })
const COMPONENT_DESCRIPTIONS = Object.freeze({ TEAM_PROFILE: '展示团队头像、名称和简介', CAROUSEL: '轮播展示成员的图片作品', SINGLE_WORK: '展示一个成员图片或视频作品', DIVIDER: '分隔不同内容区块', MEMBER_PORTFOLIO_GRID: '双列展示成员已发布作品集', MEMBER_PORTFOLIO_LIST: '单列展示成员已发布作品集', TEXT_SECTION: '添加团队服务说明文字', SCHEDULE_QUERY: '开放访客查询团队档期', CONTACT_FORM: '收集访客预留联系信息', QR_CONTACT: '展示团队二维码联系方式' })
const COMPONENT_TYPES = Object.keys(COMPONENT_NAMES)
const PORTFOLIO_REQUIRED_COMPONENT_TYPES = Object.freeze(['CAROUSEL', 'MEMBER_PORTFOLIO_GRID', 'MEMBER_PORTFOLIO_LIST'])
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
const QR_CONTACT_CROP_FAILED_MESSAGE = '二维码裁剪失败，请重试'
const PORTFOLIO_LIST_ROUTE_SUFFIX = '/portfolios/portfolios'
const TEAM_PORTFOLIOS_COMPAT_PAGE_URL = '/pages/team-portfolios/portfolios'
const TEXT_SECTION_MAX_LENGTH = 200
const TEXT_SECTION_REQUIRED_MESSAGE = '请填写文字说明'
const TEAM_PORTFOLIO_TITLE_REQUIRED_MESSAGE = '请填写团队作品集标题'
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
function buildComponentList(components = []) { return (Array.isArray(components) ? components : []).map((item) => Object.assign({}, item, { displayName: COMPONENT_NAMES[item.componentType] || item.componentType || '页面组件' })) }
function buildPublicationState(status) {
  if (status === 'PUBLISHED' || status === 'PUBLISHED_WITH_DRAFT') return { statusText: status === 'PUBLISHED_WITH_DRAFT' ? '有新草稿' : '已发布', statusTone: 'published', showPublishAction: true }
  if (status === 'OFFLINE') return { statusText: '已下线', statusTone: 'muted', showPublishAction: true }
  return { statusText: '草稿', statusTone: 'draft', showPublishAction: false }
}
function touchPoint(event = {}) { const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0]) || {}; return { x: Number(touch.clientX) || 0, y: Number(touch.clientY) || 0 } }
function dragStyle(offsetY) { return `transform: translate3d(0, ${Math.round(Number(offsetY) || 0)}px, 0) scale(${COMPONENT_DRAG_SCALE}); transition: transform 80ms linear, box-shadow 160ms ease; z-index: 3;` }
function normalizeSortOrders(components = []) { return components.map((item, sortOrder) => Object.assign({}, item, { sortOrder })) }
function buildServerSafeTeamPortfolioConfig(config = {}) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const coverUrl = normalized.share && normalized.share.coverUrl
  const components = normalized.components.map((component) => {
    if (component.componentType === 'QR_CONTACT') {
      const qrUrl = component.config && component.config.qrUrl
      if (!isRemoteUrl(qrUrl)) return null
      return Object.assign({}, component, {
        config: {
          qrUrlSource: TEAM_QR_CONTACT_SOURCE_CUSTOM,
          qrUrl
        }
      })
    }
    if (component.componentType === 'TEAM_PROFILE') {
      const team = component.config && component.config.team ? component.config.team : {}
      return Object.assign({}, component, {
        config: Object.assign({}, component.config, {
          team: Object.assign({}, team, { avatarUrl: isRemoteUrl(team.avatarUrl) ? team.avatarUrl : '' })
        })
      })
    }
    return component
  }).filter(Boolean)
  return normalizeTeamPortfolioConfig(Object.assign({}, normalized, {
    share: Object.assign({}, normalized.share, { coverUrl: isRemoteUrl(coverUrl) ? coverUrl : '' }),
    components
  }))
}

function makeKey() { return `component-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` }
function makeIdempotencyKey(prefix) { return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}` }
function getQrContactCropBoxWidth(wxApi = wx) {
  const systemInfo = wxApi && wxApi.getSystemInfoSync ? wxApi.getSystemInfoSync() : {}
  const windowWidth = Number(systemInfo.windowWidth) || 375
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
  return {
    content: String(config.content || '').trim(),
    alignment: TEXT_SECTION_ALIGNMENT_OPTIONS.some((item) => item.value === alignment) ? alignment : TEXT_SECTION_ALIGNMENTS.LEFT
  }
}
function buildTextSectionFieldCounters(form = {}) { return { content: `${countTextCodePoints(form.content)} / ${TEXT_SECTION_MAX_LENGTH}` } }
function buildContactFormConfigForm(config = {}) {
  return {
    displayMode: config.displayMode === CONTACT_FORM_DISPLAY_MODES.INLINE_FORM
      ? CONTACT_FORM_DISPLAY_MODES.INLINE_FORM
      : CONTACT_FORM_DISPLAY_MODES.MODAL_FORM
  }
}
function updateContactFormConfig(config = {}, componentKey, contactFormConfig = {}) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const form = buildContactFormConfigForm(contactFormConfig)
  return Object.assign({}, normalized, {
    components: normalized.components.map((component) => component.componentKey === componentKey && component.componentType === 'CONTACT_FORM'
      ? Object.assign({}, component, { config: Object.assign({}, component.config, form) })
      : component)
  })
}
function buildScheduleQueryForm(config = {}) {
  return { displayMode: config.displayMode === SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR ? SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR : SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR }
}
function updateScheduleQueryConfig(config = {}, componentKey, scheduleQueryConfig = {}) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const form = buildScheduleQueryForm(scheduleQueryConfig)
  return Object.assign({}, normalized, { components: normalized.components.map((component) => component.componentKey === componentKey && component.componentType === 'SCHEDULE_QUERY' ? Object.assign({}, component, { config: Object.assign({}, component.config, form) }) : component) })
}
function buildDividerForm(config = {}) {
  const color = DIVIDER_COLOR_OPTIONS.some((item) => item.value === config.color) ? config.color : 'GRAY'
  const heightPx = Number.isInteger(Number(config.heightPx)) && Number(config.heightPx) > 0 ? Number(config.heightPx) : 16
  return { color, heightPx }
}
function updateDividerConfig(config = {}, componentKey, dividerConfig = {}) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const form = buildDividerForm(dividerConfig)
  return Object.assign({}, normalized, { components: normalized.components.map((component) => component.componentKey === componentKey && component.componentType === 'DIVIDER' ? Object.assign({}, component, { config: Object.assign({}, component.config, form) }) : component) })
}
function updateTextSectionConfig(config = {}, componentKey, textSectionConfig = {}) {
  const normalized = normalizeTeamPortfolioConfig(config)
  const form = buildTextSectionForm(textSectionConfig)
  return Object.assign({}, normalized, {
    components: normalized.components.map((component) => component.componentKey === componentKey && component.componentType === 'TEXT_SECTION'
      ? Object.assign({}, component, { config: Object.assign({}, component.config, form) })
      : component)
  })
}
function isUncertainFailure(error) { return !error || !Number(error.statusCode) || Number(error.statusCode) >= 500 }
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
function draftTeamSnapshot(config = {}) { const component = (Array.isArray(config.components) ? config.components : []).find((item) => item.componentType === 'TEAM_PROFILE'); return component ? normalizeTeamSnapshot(0, component.config && component.config.team) : {} }
function resolveTeamCoverPreviewPath(filePath) {
  if (!filePath || !wx.getImageInfo) return Promise.resolve(filePath || '')
  return new Promise((resolve, reject) => wx.getImageInfo({
    src: filePath,
    success(response = {}) { resolve(response.path || response.tempFilePath || filePath) },
    fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '无法读取封面图片')) }
  }))
}
function buckets(components) {
  const value = { teamProfile: [], carousel: [], singleWork: [], divider: [], grid: [], list: [], text: [], schedule: [], contact: [], qr: [] }
  ;(Array.isArray(components) ? components : []).forEach((component) => { const key = TYPE_BUCKETS[component.componentType]; if (key) value[key].push(component) })
  return value
}

Page({
  data: { portfolioId: 0, teamId: 0, teamSnapshot: {}, loading: false, errorMessage: '', canMaintain: false, publicationStatus: 'DRAFT_ONLY', draftRevision: 0, publishedRevision: 0, statusText: '草稿', statusTone: 'draft', showPublishAction: false, config: { schemaVersion: 'standard-team-v1', share: {}, components: [] }, componentList: [], componentBuckets: buckets([]), componentOptions: buildComponentOptions(), componentValidation: {}, componentSources: {}, hasInvalidComponents: false, saving: false, publishing: false, openingLibrary: false, shareCoverUploading: false, qrContactChoosing: false, qrContactCropVisible: false, qrContactCropSaving: false, qrContactCropErrorText: '', qrContactCropState: null, qrContactCropTouchStart: null, qrContactCropCanvasWidth: WECHAT_QR_CROP_OUTPUT_WIDTH, qrContactCropCanvasHeight: WECHAT_QR_CROP_OUTPUT_WIDTH, teamProfileRefreshing: false, pendingDraftKey: '', pendingPublishKey: '', pendingPublishRevision: 0, shareTitleCounter: '0 / 50', componentSheetVisible: false, textSectionSheetVisible: false, textSectionEditingComponentKey: '', textSectionAlignmentOptions: TEXT_SECTION_ALIGNMENT_OPTIONS, textSectionMaxLength: TEXT_SECTION_MAX_LENGTH, textSectionForm: buildTextSectionForm(), textSectionFieldCounters: buildTextSectionFieldCounters(buildTextSectionForm()), contactFormSheetVisible: false, contactFormEditingComponentKey: '', contactFormDisplayModeOptions: CONTACT_FORM_DISPLAY_MODE_OPTIONS, contactFormConfigForm: buildContactFormConfigForm(), scheduleQuerySheetVisible: false, scheduleQueryEditingComponentKey: '', scheduleQueryDisplayModeOptions: SCHEDULE_QUERY_DISPLAY_MODE_OPTIONS, scheduleQueryForm: buildScheduleQueryForm(), dividerSheetVisible: false, dividerEditingComponentKey: '', dividerColorOptions: DIVIDER_COLOR_OPTIONS, dividerForm: buildDividerForm(), componentEditorVisible: false, activeComponentKey: '', activeComponentType: '', activeComponentName: '', activeComponent: { config: {} }, activeComponentSource: {}, activeComponentNeedsPortfolio: false, revealedComponentKey: '', componentTouchStart: null, draggingIndex: -1, dragTargetIndex: -1, componentDragStartY: 0, componentDragStyle: '', shareCoverCropVisible: false, shareCoverCropPath: '' },
  onLoad(options = {}) {
    const portfolioId = Number(options.portfolioId) || 0
    const teamId = Number(options.teamId) || 0
    if (portfolioId) { this.setData({ portfolioId }); return this.bootstrap() }
    if (teamId) { this.setData({ teamId }); return this.bootstrapNew() }
    this.setData({ errorMessage: '作品集参数无效' })
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
      const componentValidation = Object.fromEntries(config.components.map((item) => [item.componentKey, true]))
      let teamSnapshot = draftTeamSnapshot(config)
      if (!hasTeamSnapshot(teamSnapshot)) {
        const teamDetail = await request({ url: `/api/mine/teams/${detail.ownerId}` })
        teamSnapshot = normalizeTeamSnapshot(detail.ownerId, teamDetail && teamDetail.team)
      }
      this.setData(Object.assign({ teamId: detail.ownerId, teamSnapshot, canMaintain: true, publicationStatus: detail.publicationStatus, draftRevision: detail.draftRevision, publishedRevision: detail.publishedRevision, config, componentList: buildComponentList(config.components), componentBuckets: buckets(config.components), componentOptions: buildComponentOptions(config.components), componentValidation, hasInvalidComponents: false, shareTitleCounter: `${String(config.share && config.share.title || '').length} / 50` }, buildPublicationState(detail.publicationStatus)))
    } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '团队作品集加载失败，请重试', canMaintain: false }) } finally { this.setData({ loading: false }) }
  },
  onShow() { if (this.data.openingLibrary) this.setData({ openingLibrary: false }) },
  handleRetry() { this.bootstrap() },
  updateConfig(config) { const components = normalizeSortOrders(config.components || []); const normalized = Object.assign({}, config, { components }); this.setData({ config: normalized, componentList: buildComponentList(components), componentBuckets: buckets(components), componentOptions: buildComponentOptions(components), shareTitleCounter: `${String(normalized.share && normalized.share.title || '').length} / 50` }); if (this.data.activeComponentKey) this.syncActiveComponent(normalized, this.data.activeComponentKey) },
  clearPending() { this.setData({ pendingDraftKey: '', pendingPublishKey: '', pendingPublishRevision: 0 }) },
  noop() {},
  syncActiveComponent(config = this.data.config, componentKey = this.data.activeComponentKey) { const activeComponent = (config.components || []).find((item) => item.componentKey === componentKey) || { config: {} }; this.setData({ activeComponent, activeComponentSource: this.data.componentSources[componentKey] || {} }) },
  handleOpenComponentSheet() { if (!this.data.canMaintain) return; this.setData({ componentSheetVisible: true, revealedComponentKey: '', componentOptions: buildComponentOptions(this.data.config.components) }) },
  handleCloseComponentSheet() { this.setData({ componentSheetVisible: false }) },
  handleSelectComponent(event) { if (event.currentTarget.dataset.disabled) return; const componentType = event.currentTarget.dataset.type; if (!componentType) return; this.addComponent(componentType); this.setData({ componentSheetVisible: false }) },
  handleComponentTap(event) { const componentKey = event.currentTarget.dataset.key || ''; const componentType = event.currentTarget.dataset.type || ''; if (this.data.revealedComponentKey === componentKey) return this.setData({ revealedComponentKey: '' }); const activeComponent = (this.data.config.components || []).find((item) => item.componentKey === componentKey); if (!activeComponent) return; if (componentType === 'TEXT_SECTION') return this.openTextSectionSheet(componentKey); if (componentType === 'CONTACT_FORM') return this.openContactFormSheet(componentKey); if (componentType === 'SCHEDULE_QUERY') return this.openScheduleQuerySheet(componentKey); if (componentType === 'DIVIDER') return this.openDividerSheet(componentKey); this.setData({ componentEditorVisible: true, activeComponentKey: componentKey, activeComponentType: componentType, activeComponentName: COMPONENT_NAMES[componentType] || '页面组件', activeComponent, activeComponentSource: this.data.componentSources[componentKey] || {}, activeComponentNeedsPortfolio: PORTFOLIO_REQUIRED_COMPONENT_TYPES.includes(componentType) }) },
  handleCloseComponentEditor() { this.setData({ componentEditorVisible: false, activeComponentKey: '', activeComponentType: '', activeComponentName: '', activeComponent: { config: {} }, activeComponentSource: {}, activeComponentNeedsPortfolio: false }) },
  openTextSectionSheet(componentKey) {
    const component = this.data.config.components.find((item) => item.componentKey === componentKey)
    if (!component || component.componentType !== 'TEXT_SECTION') return
    const textSectionForm = buildTextSectionForm(component.config)
    this.setData({ textSectionSheetVisible: true, textSectionEditingComponentKey: componentKey, textSectionForm, textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm) })
  },
  handleCloseTextSectionSheet() {
    const textSectionForm = buildTextSectionForm()
    this.setData({ textSectionSheetVisible: false, textSectionEditingComponentKey: '', textSectionForm, textSectionFieldCounters: buildTextSectionFieldCounters(textSectionForm) })
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
    const component = this.data.config.components.find((item) => item.componentKey === componentKey)
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
    const component = this.data.config.components.find((item) => item.componentKey === componentKey)
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
    const component = this.data.config.components.find((item) => item.componentKey === componentKey)
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
  handleComponentTouchMove(event) { if (this.data.draggingIndex < 0) return; const point = touchPoint(event); const rows = this.componentDragRows || []; let targetIndex; if (rows.length) { targetIndex = rows.findIndex((row) => point.y < row.top + row.height / 2); if (targetIndex < 0) targetIndex = rows.length - 1 } else { const delta = point.y - this.data.componentDragStartY; targetIndex = Math.max(0, Math.min(this.data.config.components.length - 1, this.data.draggingIndex + Math.round(delta / DRAG_ROW_FALLBACK_HEIGHT))) } this.setData({ dragTargetIndex: targetIndex, componentDragStyle: dragStyle(point.y - this.data.componentDragStartY) }) },
  handleComponentTouchEnd(event) { if (this.data.draggingIndex >= 0) return this.handleComponentDragEnd(); const start = this.data.componentTouchStart; if (!start || !start.key) return; const point = touchPoint(event); const deltaX = point.x - start.x; const deltaY = Math.abs(point.y - start.y); if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) this.setData({ revealedComponentKey: start.key, componentTouchStart: null }); else if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) this.setData({ revealedComponentKey: '', componentTouchStart: null }); else this.setData({ componentTouchStart: null }) },
  handleComponentTouchCancel() { if (this.data.draggingIndex >= 0) return this.handleComponentDragEnd(); this.setData({ componentTouchStart: null }) },
  handleComponentDragEnd() { const from = this.data.draggingIndex; const to = this.data.dragTargetIndex; if (from >= 0 && to >= 0 && from !== to) { const components = this.data.config.components.slice(); const moved = components.splice(from, 1)[0]; components.splice(to, 0, moved); this.clearPending(); this.updateConfig(Object.assign({}, this.data.config, { components })) } this.componentDragRows = []; this.setData({ draggingIndex: -1, dragTargetIndex: -1, componentDragStartY: 0, componentDragStyle: '', componentTouchStart: null }) },
  handleRemoveComponent(event) { this.handleComponentDelete(event); this.setData({ revealedComponentKey: '' }) },
  handleShareInput(event) { const field = event.currentTarget.dataset.field; this.clearPending(); this.updateConfig(Object.assign({}, this.data.config, { share: Object.assign({}, this.data.config.share, { [field]: event.detail.value }) })) },
  handleComponentConfigChange(event) {
    const detail = event.detail || {}; const componentKey = detail.componentKey || (event.currentTarget && event.currentTarget.dataset.key); const components = this.data.config.components.map((item) => item.componentKey === componentKey ? Object.assign({}, item, { config: detail.config || Object.assign({}, item.config, detail.field ? { [detail.field]: detail.value } : {}) }) : item)
    this.clearPending()
    const changed = this.data.config.components.find((item) => item.componentKey === componentKey)
    if (changed && changed.componentType === 'TEXT_SECTION' && detail.field) { const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: false }); this.setData({ componentValidation, hasInvalidComponents: true }) }
    this.updateConfig(Object.assign({}, this.data.config, { components }))
  },
  handleComponentValidationChange(event) { const detail = event.detail || {}; const componentValidation = Object.assign({}, this.data.componentValidation, { [detail.componentKey]: detail.valid !== false }); this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) }) },
  handleComponentSave(event) { const key = event.currentTarget.dataset.key; this.handleComponentConfigChange(event); const componentValidation = Object.assign({}, this.data.componentValidation, { [key]: true }); this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((name) => componentValidation[name] === false) }); if (this.data.activeComponentKey === key) this.handleCloseComponentEditor() },
  handleConfirmComponentEditor() { const component = this.selectComponent('#active-component-editor'); if (component && typeof component.saveEdit === 'function') component.saveEdit() },
  handleComponentDelete(event) { const key = event.currentTarget.dataset.key; this.clearPending(); const validation = Object.assign({}, this.data.componentValidation); delete validation[key]; this.setData({ componentValidation: validation, hasInvalidComponents: Object.keys(validation).some((name) => validation[name] === false) }); this.updateConfig(Object.assign({}, this.data.config, { components: this.data.config.components.filter((item) => item.componentKey !== key) })) },
  handleMove(event) { const key = event.currentTarget.dataset.key; const direction = Number(event.currentTarget.dataset.direction); const components = this.data.config.components.slice(); const index = components.findIndex((item) => item.componentKey === key); const target = index + direction; if (index < 0 || target < 0 || target >= components.length) return; this.clearPending(); [components[index], components[target]] = [components[target], components[index]]; this.updateConfig(Object.assign({}, this.data.config, { components: components.map((item, sortOrder) => Object.assign({}, item, { sortOrder })) })) },
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
  addComponent(componentType) { if (!componentType) return this.setData({ openingLibrary: false }); this.clearPending(); const componentKey = makeKey(); const componentConfig = componentType === 'TEAM_PROFILE' ? { team: Object.assign({}, this.data.teamSnapshot) } : componentType === 'SINGLE_WORK' ? { memberUserId: null, workId: null, showTitle: true, showDescription: false } : MEMBER_PORTFOLIO_COMPONENT_TYPES.includes(componentType) ? { showMemberName: true } : {}; const components = this.data.config.components.concat({ componentKey, componentType, sortOrder: this.data.config.components.length, enabled: true, config: componentConfig }); this.updateConfig(Object.assign({}, this.data.config, { components })); this.setData({ componentValidation: Object.assign({}, this.data.componentValidation, { [componentKey]: false }), hasInvalidComponents: true, openingLibrary: false }) },
  updateSource(componentKey, patch) { const componentSources = Object.assign({}, this.data.componentSources, { [componentKey]: Object.assign({}, this.data.componentSources[componentKey], patch) }); const state = { componentSources }; if (componentKey === this.data.activeComponentKey) state.activeComponentSource = componentSources[componentKey]; this.setData(state) },
  async loadSource(componentKey, fingerprint, url, patch, failureState, scopeId = this.data.portfolioId) { if (!Number(scopeId)) return; const source = this.data.componentSources[componentKey] || {}; if (source.loadingFingerprint === fingerprint) return; this.updateSource(componentKey, { loadingFingerprint: fingerprint, errorMessage: '', sourceAvailable: true }); try { const value = await request({ url }); if ((this.data.componentSources[componentKey] || {}).loadingFingerprint !== fingerprint) return; this.updateSource(componentKey, Object.assign({}, patch(value), { loadingFingerprint: '', failedStage: '', memberUserId: 0 })) } catch (error) { if ((this.data.componentSources[componentKey] || {}).loadingFingerprint !== fingerprint) return; if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) { this.updateSource(componentKey, Object.assign({ loadingFingerprint: '', errorMessage: '', sourceAvailable: false }, failureState || {})); return }; this.updateSource(componentKey, Object.assign({ loadingFingerprint: '', errorMessage: '来源加载失败，请重试', sourceAvailable: false }, failureState || {})) } },
  async handleCarouselLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'carousel-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members`, (members) => ({ members, works: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleCarouselMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { works: [], memberUserId, failedStage: '' }); return this.loadSource(key, `carousel-works-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members/${memberUserId}/works`, (works) => ({ works }), { failedStage: 'member-items', memberUserId }) },
  handleCarouselRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleCarouselMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleCarouselLoadMembers(event) },
  async handleSingleWorkLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'single-work-members', `/api/mine/teams/${this.data.teamId}/portfolio-components/single-work/members`, (members) => ({ members, works: [] }), { failedStage: 'members', memberUserId: 0 }, this.data.teamId) },
  async handleSingleWorkMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { works: [], memberUserId, failedStage: '' }); return this.loadSource(key, `single-work-works-${memberUserId}`, `/api/mine/teams/${this.data.teamId}/portfolio-components/single-work/members/${memberUserId}/works`, (works) => ({ works }), { failedStage: 'member-items', memberUserId }, this.data.teamId) },
  handleSingleWorkRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleSingleWorkMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleSingleWorkLoadMembers(event) },
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
    const normalized = normalizeTeamPortfolioConfig(config)
    const components = []
    for (const component of normalized.components) {
      const team = component.config && component.config.team ? component.config.team : {}
      if (component.componentType !== 'TEAM_PROFILE' || !team.avatarUrl || isRemoteUrl(team.avatarUrl)) {
        components.push(component)
        continue
      }
      const avatarUrl = await uploadTeamPortfolioAsset({
        portfolioId,
        assetType: TEAM_PROFILE_AVATAR_ASSET_TYPE,
        clientId: makeKey(),
        filePath: team.avatarUrl,
        requestFn: request
      })
      components.push(Object.assign({}, component, {
        config: Object.assign({}, component.config, { team: Object.assign({}, team, { avatarUrl }) })
      }))
    }
    return normalizeTeamPortfolioConfig(Object.assign({}, normalized, { components }))
  },
  async uploadLocalQrContacts(portfolioId, config = this.data.config) {
    let normalized = normalizeTeamPortfolioConfig(config)
    for (let index = 0; index < normalized.components.length; index += 1) {
      const component = normalized.components[index]
      const qrUrl = component.config && component.config.qrUrl
      if (component.componentType !== 'QR_CONTACT' || !qrUrl || isRemoteUrl(qrUrl)) continue
      const uploadedUrl = await uploadTeamPortfolioAsset({
        portfolioId,
        assetType: TEAM_QR_CONTACT_ASSET_TYPE,
        clientId: makeKey(),
        filePath: qrUrl,
        requestFn: request
      })
      const components = normalized.components.slice()
      components[index] = Object.assign({}, component, {
        config: { qrUrlSource: TEAM_QR_CONTACT_SOURCE_CUSTOM, qrUrl: uploadedUrl }
      })
      normalized = normalizeTeamPortfolioConfig(Object.assign({}, normalized, { components }))
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
      this.setData(Object.assign({ portfolioId, draftRevision, publicationStatus, config, componentList: buildComponentList(config.components), componentBuckets: buckets(config.components), componentOptions: buildComponentOptions(config.components), pendingDraftKey: '' }, buildPublicationState(publicationStatus)))
      return result
    } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return null
      if (showTeamPortfolioUnavailableToast(error)) {
        this.setData({ pendingDraftKey: '' })
        return null
      }
      if (!isUncertainFailure(error)) this.setData({ pendingDraftKey: '' })
      wx.showToast({ title: failureStage === 'upload' ? '素材上传失败，请重试' : '保存失败，请检查组件配置', icon: 'none' })
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
      wx.showToast({ title: '发布失败，请重试', icon: 'none' })
    } finally { this.setData({ publishing: false }) }
  }
})
