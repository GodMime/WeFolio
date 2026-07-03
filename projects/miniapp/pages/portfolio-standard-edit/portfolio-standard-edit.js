const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { isRemoteUrl } = require('../../utils/upload-file')
const { normalizeProfile: normalizeBasicProfile } = require('../../utils/profile')
const { normalizeWorkList, normalizeWorkTags } = require('../../utils/works')
const {
  PORTFOLIO_ASSET_TYPES,
  createChoosePortfolioImageOptions,
  uploadPortfolioImageAsset
} = require('../../utils/portfolio-assets')
const {
  COMPONENT_NAMES,
  COMPONENT_TYPES,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  copyWorkTagsToDisplayGroups,
  createComponent,
  importWorksIntoDisplayGroup,
  normalizeProfileComponentConfig,
  normalizePortfolioConfig,
  normalizeWorkIds,
  reorderComponent,
  removeComponent,
  updateComponentProfileConfig,
  updateComponentWorkIds
} = require('../../utils/portfolios')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'
const STANDARD_PERSONAL_API_URL = '/api/mine/portfolios/standard-personal'
const COMPONENT_LIBRARY_API_URL = '/api/mine/portfolios/component-library'
const BASIC_PROFILE_API_URL = '/api/mine/profile'
const WORKS_API_URL = '/api/mine/works'
const PORTFOLIOS_PAGE_ROUTE = 'pages/portfolios/portfolios'
const PORTFOLIOS_PAGE_URL = `/${PORTFOLIOS_PAGE_ROUTE}`
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const COMPONENT_DRAG_SCALE = 1.015
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
const PROFILE_FIELD_LIMITS = {
  displayName: 50,
  profession: 50,
  city: 50,
  bio: 500,
  tagsText: 100,
  wechatQrUrl: 512
}

const DEFAULT_COMPONENT_DESCRIPTIONS = {
  CAROUSEL: '展示已选择的图片作品',
  PROFILE: '展示个人资料和服务标签',
  SCHEDULE_QUERY: '开放访客查询档期',
  WORK_GRID: '双列展示图片和视频作品',
  WORK_LIST: '单列展示重点图片和视频作品',
  QR_CONTACT: '展示二维码联系方式',
  CONTACT_FORM: '收集访客预留联系信息',
  TEXT_SECTION: '添加服务说明文字'
}

function makeIdempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function buildDefaultComponentOptions() {
  return Object.keys(COMPONENT_TYPES).map((key) => {
    const componentType = COMPONENT_TYPES[key]
    return {
      componentType,
      name: COMPONENT_NAMES[componentType],
      description: DEFAULT_COMPONENT_DESCRIPTIONS[componentType] || '标准个人作品集可选组件'
    }
  })
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
    isDisplayGroupComponent(componentType)
}

function buildSelectedCountText(workIds = []) {
  return `${normalizeWorkIds(workIds).length} 已选`
}

function buildDisplayGroupOptions(component = {}, activeGroupKey = '') {
  const groups = component.config && Array.isArray(component.config.groups) ? component.config.groups : []
  const activeKey = activeGroupKey || (groups[0] && groups[0].groupKey) || ''
  return groups.map((group) => {
    const workIds = normalizeWorkIds(group.workIds)
    return Object.assign({}, group, {
      active: group.groupKey === activeKey,
      countText: `${workIds.length} 个作品`
    })
  })
}

function buildComponentWorkOptions(works = [], selectedIds = [], componentType = '') {
  const selectedSet = new Set(normalizeWorkIds(selectedIds))
  return works
    .filter((work) => componentType !== COMPONENT_TYPES.CAROUSEL || work.mediaType === 'IMAGE')
    .map((work) => Object.assign({}, work, {
      selected: selectedSet.has(work.id),
      thumbUrl: work.coverUrl || work.mediaUrl || '',
      metaText: work.tagText && work.tagText !== '未设置标签' ? `${work.typeText} · ${work.tagText}` : work.typeText
    }))
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
  return {
    title: config.title || '',
    description: config.description || '',
    qrUrlSource: config.qrUrlSource === QR_CONTACT_SOURCE_CUSTOM ? QR_CONTACT_SOURCE_CUSTOM : QR_CONTACT_SOURCE_PROFILE,
    qrUrl: config.qrUrl || ''
  }
}

function countText(value) {
  return Array.from(String(value || '')).length
}

function buildProfileFieldCounters(form = {}) {
  return Object.keys(PROFILE_FIELD_LIMITS).reduce((result, field) => {
    result[field] = `${countText(form[field])} / ${PROFILE_FIELD_LIMITS[field]}`
    return result
  }, {})
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

function updateComponentQrContactConfig(config, componentKey, qrContactForm = {}) {
  const normalizedConfig = normalizePortfolioConfig(config)
  const targetKey = componentKey || ''
  const form = buildQrContactForm(qrContactForm)
  const components = (normalizedConfig.components || []).map((component) => {
    if (component.componentKey !== targetKey || component.componentType !== COMPONENT_TYPES.QR_CONTACT) {
      return component
    }
    return Object.assign({}, component, {
      config: Object.assign({}, component.config || {}, {
        title: form.title,
        description: form.description,
        qrUrlSource: form.qrUrlSource,
        qrUrl: form.qrUrl
      })
    })
  })
  return normalizePortfolioConfig(Object.assign({}, normalizedConfig, { components }))
}

function buildProfileFormFromBasicProfile(raw = {}, currentForm = {}) {
  const profile = normalizeBasicProfile(raw)
  return {
    avatarUrl: profile.avatarUrl,
    displayName: profile.displayName,
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

function resolveChosenImagePath(response = {}) {
  const files = Array.isArray(response.tempFiles) ? response.tempFiles : []
  const firstFile = files[0] || {}
  return firstFile.tempFilePath || firstFile.path || ''
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
    componentWorkErrorText: '',
    componentWorkOptions: [],
    componentWorkSelectedIds: [],
    componentWorkSelectedCountText: '0 已选',
    editingComponentKey: '',
    editingComponentType: '',
    displayGroupSheetVisible: false,
    editingDisplayComponentKey: '',
    editingDisplayComponentType: '',
    displayGroupOptions: [],
    activeDisplayGroupKey: '',
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
    qrContactForm: buildQrContactForm(),
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
        this.setData({
          draftRevision: response.draftRevision || 0,
          publishedRevision: response.publishedRevision || 0,
          config
        })
        return this.loadBasicProfileDefaults(config)
      })
      .catch((error) => {
        if (error.authRequired) {
          handleAuthRequired(error.message)
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
        this.setData({ config: nextConfig })
        return nextConfig
      })
      .catch((error) => {
        if (error && error.authRequired) {
          handleAuthRequired(error.message)
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
    }
    if (path === 'share.intro') {
      config.share.intro = event.detail.value
    }
    this.setData({ config: normalizePortfolioConfig(config) })
  },

  setShareCoverUrl(coverUrl) {
    const config = Object.assign({}, this.data.config)
    config.share = Object.assign({}, config.share, { coverUrl })
    this.setData({ config: normalizePortfolioConfig(config) })
  },

  handleChooseShareCover() {
    wx.chooseMedia(Object.assign({}, createChoosePortfolioImageOptions(), {
      success: (response) => {
        const coverPath = resolveChosenImagePath(response)
        if (coverPath) {
          this.setShareCoverUrl(coverPath)
        }
      },
      fail: (error) => {
        if (error && error.errMsg && !/cancel/i.test(error.errMsg)) {
          wx.showToast({ title: '选择封面失败', icon: 'none' })
        }
      }
    }))
  },

  handleOpenComponentSheet() {
    this.setData({ componentSheetVisible: true })
    this.loadComponentOptions()
  },

  loadComponentOptions() {
    request({ url: COMPONENT_LIBRARY_API_URL })
      .then((response) => {
        if (response && Array.isArray(response.components) && response.components.length > 0) {
          this.setData({ componentOptions: response.components })
        }
      })
      .catch(() => {})
  },

  handleCloseComponentSheet() {
    this.setData({ componentSheetVisible: false })
  },

  noop() {},

  handleSelectComponent(event) {
    const componentType = event.currentTarget.dataset.type
    if (!componentType) {
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
    if (isDisplayGroupComponent(componentType)) {
      return this.openDisplayGroupSheet(componentKey, componentType)
    }
    return undefined
  },

  openDisplayGroupSheet(componentKey, componentType) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    const groups = component.config && Array.isArray(component.config.groups) ? component.config.groups : []
    const activeGroupKey = groups[0] ? groups[0].groupKey : ''
    this.setData({
      displayGroupSheetVisible: true,
      editingDisplayComponentKey: componentKey,
      editingDisplayComponentType: componentType,
      activeDisplayGroupKey: activeGroupKey,
      displayGroupOptions: buildDisplayGroupOptions(component, activeGroupKey),
      displayGroupErrorText: ''
    })
    return Promise.resolve()
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
          handleAuthRequired(error.message)
          return
        }
        this.setData({
          profileSheetLoading: false,
          profileSheetErrorText: error && error.message ? error.message : '基础资料加载失败'
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
    this.setData({
      qrContactSheetVisible: true,
      editingQrContactComponentKey: componentKey,
      qrContactForm: buildQrContactForm(component.config || {})
    })
    return Promise.resolve()
  },

  handleCloseQrContactSheet() {
    this.setData({
      qrContactSheetVisible: false,
      editingQrContactComponentKey: '',
      qrContactForm: buildQrContactForm()
    })
  },

  handleQrContactInput(event) {
    const field = event.currentTarget.dataset.field || ''
    if (!field) {
      return
    }
    this.setData({
      [`qrContactForm.${field}`]: event.detail.value || ''
    })
  },

  handleUseProfileQrContact() {
    this.setData({
      'qrContactForm.qrUrlSource': QR_CONTACT_SOURCE_PROFILE
    })
  },

  handleUseCustomQrContact() {
    this.setData({
      'qrContactForm.qrUrlSource': QR_CONTACT_SOURCE_CUSTOM
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
      qrContactForm: buildQrContactForm()
    })
  },

  handleCloseDisplayGroupSheet() {
    this.setData({
      displayGroupSheetVisible: false,
      editingDisplayComponentKey: '',
      editingDisplayComponentType: '',
      activeDisplayGroupKey: '',
      displayGroupErrorText: ''
    })
  },

  refreshDisplayGroupOptions(componentKey = this.data.editingDisplayComponentKey, activeGroupKey = this.data.activeDisplayGroupKey) {
    const component = findComponentByKey(this.data.config, componentKey)
    this.setData({
      displayGroupOptions: buildDisplayGroupOptions(component || {}, activeGroupKey)
    })
  },

  handleSelectDisplayGroup(event) {
    const groupKey = event.currentTarget.dataset.groupKey || ''
    this.setData({ activeDisplayGroupKey: groupKey })
    this.refreshDisplayGroupOptions(this.data.editingDisplayComponentKey, groupKey)
  },

  handleCopyWorkTagsToDisplayGroups() {
    if (!this.data.editingDisplayComponentKey) {
      return Promise.resolve()
    }
    this.setData({ displayGroupLoading: true, displayGroupErrorText: '' })
    return request({ url: `${WORKS_API_URL}/tags` })
      .then((response) => {
        const tags = normalizeWorkTags(response)
        const config = copyWorkTagsToDisplayGroups(this.data.config, this.data.editingDisplayComponentKey, tags)
        const component = findComponentByKey(config, this.data.editingDisplayComponentKey)
        const firstGroupKey = component && component.config.groups[0] ? component.config.groups[0].groupKey : ''
        this.setData({
          config,
          workTagOptions: tags,
          activeDisplayGroupKey: firstGroupKey,
          displayGroupLoading: false
        })
        this.refreshDisplayGroupOptions(this.data.editingDisplayComponentKey, firstGroupKey)
      })
      .catch((error) => {
        if (error && error.authRequired) {
          this.setData({ displayGroupLoading: false })
          handleAuthRequired(error.message)
          return
        }
        this.setData({
          displayGroupLoading: false,
          displayGroupErrorText: error && error.message ? error.message : '作品标签加载失败'
        })
      })
  },

  handleImportWorksByTag(event) {
    const tagId = Number(event.currentTarget.dataset.tagId)
    if (!this.data.editingDisplayComponentKey || !this.data.activeDisplayGroupKey || !Number.isFinite(tagId) || tagId <= 0) {
      return Promise.resolve()
    }
    this.setData({ displayGroupLoading: true, displayGroupErrorText: '' })
    return request({
      url: WORKS_API_URL,
      data: {
        page: 1,
        pageSize: 100,
        tagId
      }
    }).then((response) => {
      const works = normalizeWorkList(response).works
      const config = importWorksIntoDisplayGroup(
        this.data.config,
        this.data.editingDisplayComponentKey,
        this.data.activeDisplayGroupKey,
        works.map((work) => work.id)
      )
      this.setData({
        config,
        displayGroupLoading: false
      })
      this.refreshDisplayGroupOptions()
    }).catch((error) => {
      if (error && error.authRequired) {
        this.setData({ displayGroupLoading: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        displayGroupLoading: false,
        displayGroupErrorText: error && error.message ? error.message : '作品导入失败'
      })
    })
  },

  handleRemoveComponent(event) {
    this.setData({
      config: removeComponent(this.data.config, event.currentTarget.dataset.key),
      revealedComponentKey: ''
    })
  },

  openComponentWorkSheet(componentKey, componentType) {
    const component = findComponentByKey(this.data.config, componentKey)
    if (!component) {
      return Promise.resolve()
    }
    const selectedIds = normalizeWorkIds(component.config && component.config.workIds)
    this.setData({
      componentWorkSheetVisible: true,
      componentWorkSheetTitle: componentType === COMPONENT_TYPES.CAROUSEL ? '编辑轮播作品' : '编辑展示作品',
      componentWorkLoading: true,
      componentWorkErrorText: '',
      componentWorkOptions: buildComponentWorkOptions(this.data.componentWorkOptions, selectedIds, componentType),
      componentWorkSelectedIds: selectedIds,
      componentWorkSelectedCountText: buildSelectedCountText(selectedIds),
      editingComponentKey: componentKey,
      editingComponentType: componentType
    })
    return this.loadComponentWorks(componentType, selectedIds)
  },

  async loadComponentWorks(componentType = this.data.editingComponentType, selectedIds = this.data.componentWorkSelectedIds) {
    const requestSeq = this.componentWorkRequestSeq + 1
    this.componentWorkRequestSeq = requestSeq
    try {
      const response = await request({
        url: WORKS_API_URL,
        data: {
          page: 1,
          pageSize: 100
        }
      })
      if (requestSeq !== this.componentWorkRequestSeq) {
        return
      }
      const works = normalizeWorkList(response).works
      this.setData({
        componentWorkLoading: false,
        componentWorkErrorText: '',
        componentWorkOptions: buildComponentWorkOptions(works, selectedIds, componentType)
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ componentWorkLoading: false })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        componentWorkLoading: false,
        componentWorkErrorText: error && error.message ? error.message : '作品加载失败'
      })
    }
  },

  handleRetryLoadComponentWorks() {
    this.setData({
      componentWorkLoading: true,
      componentWorkErrorText: ''
    })
    this.loadComponentWorks()
  },

  handleCloseComponentWorkSheet() {
    this.setData({
      componentWorkSheetVisible: false,
      editingComponentKey: '',
      editingComponentType: '',
      componentWorkErrorText: ''
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
    this.setData({
      config: updateComponentWorkIds(this.data.config, this.data.editingComponentKey, this.data.componentWorkSelectedIds),
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
      this.setData({
        portfolioId,
        draftRevision: response.draftRevision || 0,
        publishedRevision: response.publishedRevision || 0
      })
      return portfolioId
    })
  },

  saveDraftForPortfolio(portfolioId, config = this.data.config) {
    return request({
      url: `${PORTFOLIO_API_PREFIX}/${portfolioId}/draft`,
      method: 'PUT',
      data: buildDraftPayload(config, this.data.draftRevision, makeIdempotencyKey('draft'))
    }).then((response) => {
      this.setData({
        portfolioId: response.portfolioId || portfolioId,
        draftRevision: response.draftRevision || this.data.draftRevision,
        publishedRevision: response.publishedRevision || this.data.publishedRevision
      })
      wx.showToast({ title: '草稿已保存', icon: 'success' })
      this.returnToPortfolioList()
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

  uploadLocalProfileAvatars(portfolioId, config = this.data.config) {
    let nextConfig = normalizePortfolioConfig(config)
    const profileComponents = (nextConfig.components || [])
      .filter((component) => component.componentType === COMPONENT_TYPES.PROFILE)

    return profileComponents.reduce((chain, component) => {
      return chain.then(() => {
        const currentComponent = findComponentByKey(nextConfig, component.componentKey) || component
        const profileConfig = normalizeProfileComponentConfig(currentComponent.config || {})
        const avatarUrl = profileConfig.profile.avatarUrl
        if (!avatarUrl) {
          return ''
        }
        return uploadPortfolioImageAsset(portfolioId, avatarUrl, {
          assetType: PORTFOLIO_ASSET_TYPES.PROFILE_AVATAR,
          assetLabel: '头像图片',
          clientIdPrefix: 'profile-avatar'
        }).then((uploadedUrl) => {
          if (uploadedUrl && uploadedUrl !== avatarUrl) {
            const nextProfileConfig = normalizeProfileComponentConfig(Object.assign({}, profileConfig, {
              profile: Object.assign({}, profileConfig.profile, { avatarUrl: uploadedUrl })
            }))
            nextConfig = updateComponentProfileConfig(nextConfig, component.componentKey, nextProfileConfig)
          }
          return uploadedUrl || avatarUrl
        })
      })
    }, Promise.resolve('')).then(() => nextConfig)
  },

  uploadLocalQrContactImages(portfolioId, config = this.data.config) {
    let nextConfig = normalizePortfolioConfig(config)
    const qrContactComponents = (nextConfig.components || [])
      .filter((component) => component.componentType === COMPONENT_TYPES.QR_CONTACT)

    return qrContactComponents.reduce((chain, component) => {
      return chain.then(() => {
        const currentComponent = findComponentByKey(nextConfig, component.componentKey) || component
        const componentConfig = currentComponent.config || {}
        const qrUrl = componentConfig.qrUrl || ''
        if (componentConfig.qrUrlSource !== QR_CONTACT_SOURCE_CUSTOM || !qrUrl) {
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
  },

  uploadLocalPortfolioAssets(portfolioId) {
    return this.uploadLocalShareCover(portfolioId, this.data.config)
      .then((config) => this.uploadLocalProfileAvatars(portfolioId, config))
      .then((config) => this.uploadLocalQrContactImages(portfolioId, config))
      .then((config) => {
        this.setData({ config })
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
    wx.navigateTo({ url: `/pages/portfolio-standard-preview/portfolio-standard-preview?portfolioId=${this.data.portfolioId}` })
  },

  handlePublish() {
    request({
      url: `${PORTFOLIO_API_PREFIX}/${this.data.portfolioId}/publish`,
      method: 'POST',
      data: buildPublishPayload(this.data.draftRevision, makeIdempotencyKey('publish'))
    }).then((response) => {
      this.setData({ publishedRevision: response.publishedRevision || this.data.publishedRevision })
      wx.showToast({ title: '已发布', icon: 'success' })
    }).catch((error) => {
      wx.showToast({ title: error.message || '发布失败', icon: 'none' })
    })
  }
})
