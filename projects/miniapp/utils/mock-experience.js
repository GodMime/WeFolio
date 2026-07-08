const MOCK_LOGIN_REQUIRED_MESSAGE = '请去“我的”页面注册登录'
const MOCK_AVATAR_URL = 'https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png'
const MOCK_ASSET_ROOT = 'https://cdn2.we-folio.dingchenyong.top/demo'
const MOCK_PORTFOLIO_DRAFT_STORAGE_KEY = 'wefolio_mock_portfolio_draft'
const MOCK_PORTFOLIO_ID = 9001
const MOCK_TAG = {
  id: 201,
  name: '风景作品',
  color: '#0f766e',
  count: 7
}
const COMPONENT_TYPES = {
  CAROUSEL: 'CAROUSEL',
  PROFILE: 'PROFILE',
  SCHEDULE_QUERY: 'SCHEDULE_QUERY',
  WORK_GRID: 'WORK_GRID',
  WORK_LIST: 'WORK_LIST',
  QR_CONTACT: 'QR_CONTACT',
  CONTACT_FORM: 'CONTACT_FORM',
  TEXT_SECTION: 'TEXT_SECTION',
  DIVIDER: 'DIVIDER'
}
const COMPONENT_NAMES = {
  CAROUSEL: '轮播图',
  PROFILE: '个人资料',
  SCHEDULE_QUERY: '档期查询',
  WORK_GRID: '双列作品列表',
  WORK_LIST: '单列作品列表',
  QR_CONTACT: '二维码联系',
  CONTACT_FORM: '预留联系信息',
  TEXT_SECTION: '文字说明',
  DIVIDER: '分割线'
}
const MOCK_COMPONENT_DESCRIPTIONS = {
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
const MOCK_COMPONENT_SORT_ORDER_STEP = 1000
const MOCK_COMPONENT_OPTIONS = Object.keys(COMPONENT_TYPES).map((key) => {
  const componentType = COMPONENT_TYPES[key]
  return {
    componentType,
    name: COMPONENT_NAMES[componentType],
    description: MOCK_COMPONENT_DESCRIPTIONS[componentType]
  }
})
const MOCK_COMPONENT_KEY_PREFIXES = {
  CAROUSEL: 'mock_carousel',
  PROFILE: 'mock_profile',
  SCHEDULE_QUERY: 'mock_schedule_query',
  WORK_GRID: 'mock_work_grid',
  WORK_LIST: 'mock_work_list',
  QR_CONTACT: 'mock_qr_contact',
  CONTACT_FORM: 'mock_contact_form',
  TEXT_SECTION: 'mock_text_section',
  DIVIDER: 'mock_divider'
}
const MOCK_COMPONENT_DEFAULT_CONFIGS = {
  CAROUSEL: {
    carouselIntervalMs: 3000,
    workIds: [101, 102, 103]
  },
  WORK_GRID: {
    workIds: [101, 102, 103, 104, 105, 106, 107],
    groups: [{
      groupKey: 'g_all',
      name: '全部作品',
      sortOrder: 1000,
      workIds: [101, 102, 103, 104, 105, 106, 107]
    }]
  },
  WORK_LIST: {
    workIds: [101, 102, 103, 104, 105, 106, 107],
    groups: [{
      groupKey: 'g_all',
      name: '全部作品',
      sortOrder: 1000,
      workIds: [101, 102, 103, 104, 105, 106, 107]
    }]
  },
  SCHEDULE_QUERY: {
    displayMode: 'MODAL_CALENDAR',
    title: '查询我的可约档期',
    description: '体验版仅展示入口，不提交真实档期查询。'
  },
  QR_CONTACT: {
    qrUrlSource: 'PROFILE',
    qrUrl: '',
    qrSize: 240,
    showLabel: true
  },
  CONTACT_FORM: {
    displayMode: 'MODAL_FORM',
    title: '预留联系信息',
    description: '留下称呼和需求，方便服务者后续联系。',
    fields: ['contactName', 'phone', 'wechat', 'needs']
  },
  TEXT_SECTION: {
    title: '',
    content: '用一段文字说明你的服务风格、拍摄流程或报价说明。',
    alignment: 'LEFT'
  },
  DIVIDER: {
    color: 'GRAY',
    heightPx: 16
  }
}
const TEXT_SECTION_ALIGNMENT_CLASS_MAP = {
  LEFT: 'align-left',
  CENTER: 'align-center',
  RIGHT: 'align-right'
}
const DIVIDER_COLOR_VALUE_MAP = {
  BLACK: '#000000',
  WHITE: '#ffffff',
  GRAY: '#eef1f4',
  TRANSPARENT: 'transparent'
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function trimText(value) {
  return String(value || '').trim()
}

function getRuntimeWx() {
  return typeof wx !== 'undefined' ? wx : null
}

function toPositiveId(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? Math.round(numberValue) : 0
}

function buildWork({
  id,
  mediaType,
  title,
  fileName,
  mediaUrl,
  coverUrl,
  mimeType,
  durationMs,
  sortOrder
}) {
  return {
    id,
    workId: id,
    mediaType,
    isVideo: mediaType === 'VIDEO',
    title,
    originalFileName: fileName,
    mediaUrl,
    coverUrl,
    thumbnailUrl: coverUrl || mediaUrl,
    previewUrl: mediaUrl || coverUrl,
    mimeType,
    fileSize: 0,
    durationMs: durationMs || 0,
    width: 0,
    height: 0,
    description: '',
    serviceDate: '',
    sortOrder,
    status: 'ACTIVE',
    referenceCount: 1,
    tags: [clone(MOCK_TAG)]
  }
}

const MOCK_BOTTOM_TABS = [
  {
    key: 'schedule',
    label: '档期',
    icon: 'schedule',
    url: '/pages/mock/schedule/schedule'
  },
  {
    key: 'work',
    label: '作品',
    icon: 'work',
    url: '/pages/mock/works/works'
  },
  {
    key: 'portfolio',
    label: '作品集',
    icon: 'portfolio',
    url: '/pages/mock/portfolios/portfolios'
  },
  {
    key: 'mine',
    label: '我的',
    icon: 'mine',
    url: '/pages/mock/index/index'
  }
]

const MOCK_DASHBOARD = {
  profile: {
    userId: MOCK_PORTFOLIO_ID,
    uniqueCode: 'WFMOCK001',
    displayName: '映期体验账号',
    avatarUrl: MOCK_AVATAR_URL,
    profession: '婚礼影像服务',
    city: '杭州',
    tags: [
      { name: '风景作品' },
      { name: '婚礼纪实' }
    ]
  },
  point: {
    balance: 120,
    todayConsumed: 0,
    lowBalance: false
  },
  metrics: {
    workCount: 7,
    publishedPortfolioCount: 0,
    recentVisitCount: 18
  },
  entries: [
    {
      type: 'visits',
      title: '访问记录',
      desc: '访客来源、访问次数、跟进状态',
      iconUrl: '/assets/system/wefolio-visitor-record-icon.png',
      clickable: false
    },
    {
      type: 'teams',
      title: '我的团队',
      desc: '按角色显示可用功能',
      iconUrl: '/assets/system/wefolio-team-icon.png',
      clickable: false
    },
    {
      type: 'messages',
      title: '我的消息',
      desc: '系统提醒、团队邀请',
      iconUrl: '/assets/system/wefolio-message-icon.png',
      badgeText: '',
      clickable: false
    }
  ]
}

const MOCK_SCHEDULE_DATA = {
  slotDefinitions: [
    {
      id: 1,
      name: '午宴',
      startTime: '10:30',
      endTime: '14:00',
      timeRangeText: '10:30-14:00',
      color: '#d98200',
      status: 'ACTIVE',
      enabled: true
    },
    {
      id: 2,
      name: '晚宴',
      startTime: '17:30',
      endTime: '21:30',
      timeRangeText: '17:30-21:30',
      color: '#36516e',
      status: 'ACTIVE',
      enabled: true
    }
  ],
  month: {
    yearMonth: '2026-07',
    days: []
  },
  selectedDate: {
    date: '2026-07-06',
    summaryText: '暂无档期',
    schedules: []
  }
}

const MOCK_WORK_LIBRARY = {
  page: 1,
  pageSize: 20,
  total: 7,
  hasMore: false,
  summary: {
    totalCount: 7,
    imageCount: 6,
    videoCount: 1
  },
  tags: [clone(MOCK_TAG)],
  works: [
    buildWork({
      id: 101,
      mediaType: 'IMAGE',
      title: '风景图片 1',
      fileName: 'demo-image-1.jpg',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-image-1.jpg`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-1.jpg`,
      mimeType: 'image/jpeg',
      sortOrder: 1000
    }),
    buildWork({
      id: 102,
      mediaType: 'IMAGE',
      title: '风景图片 2',
      fileName: 'demo-image-2.jpg',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-image-2.jpg`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-2.jpg`,
      mimeType: 'image/jpeg',
      sortOrder: 2000
    }),
    buildWork({
      id: 103,
      mediaType: 'IMAGE',
      title: '风景图片 3',
      fileName: 'demo-image-3.jpg',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-image-3.jpg`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-3-thumb.jpg`,
      mimeType: 'image/jpeg',
      sortOrder: 3000
    }),
    buildWork({
      id: 104,
      mediaType: 'IMAGE',
      title: '风景图片 4',
      fileName: 'demo-image-4.jpg',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-image-4.jpg`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-4-thumb.jpg`,
      mimeType: 'image/jpeg',
      sortOrder: 4000
    }),
    buildWork({
      id: 105,
      mediaType: 'IMAGE',
      title: '风景图片 5',
      fileName: 'demo-image-5.jpg',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-image-5.jpg`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-5-thumb.jpg`,
      mimeType: 'image/jpeg',
      sortOrder: 5000
    }),
    buildWork({
      id: 106,
      mediaType: 'IMAGE',
      title: '风景图片 6',
      fileName: 'demo-image-6.jpg',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-image-6.jpg`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-6-thumb.jpg`,
      mimeType: 'image/jpeg',
      sortOrder: 6000
    }),
    buildWork({
      id: 107,
      mediaType: 'VIDEO',
      title: '风景视频 1',
      fileName: 'demo-video-1.mp4',
      mediaUrl: `${MOCK_ASSET_ROOT}/demo-video-1.mp4`,
      coverUrl: `${MOCK_ASSET_ROOT}/demo-video-1-thumb.jpg`,
      mimeType: 'video/mp4',
      durationMs: 15000,
      sortOrder: 7000
    })
  ]
}

const MOCK_PORTFOLIO_LIST = {
  ownerType: 'USER',
  ownerTitle: '个人作品集',
  summary: {
    publishedText: '0 已发布',
    draftText: '1 草稿'
  },
  portfolios: [
    {
      portfolioId: MOCK_PORTFOLIO_ID,
      ownerType: 'USER',
      templateType: 'STANDARD',
      publicationStatus: 'DRAFT',
      statusText: '草稿',
      statusTone: 'draft',
      title: '风景标准个人作品集',
      shareCode: 'MOCK9001',
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-1.jpg`,
      coverAlt: '风景标准个人作品集',
      updatedAt: '2026-07-06 12:00:00',
      updatedText: '最近更新 07-06',
      draftRevision: 1,
      publishedRevision: 0,
      showDraftPreview: true,
      showPublishedPreview: false,
      actionType: 'PUBLISH',
      actionText: '发布',
      actions: {
        cardTap: 'edit',
        preview: 'preview',
        publish: 'toast-login-required',
        delete: 'toast-login-required'
      }
    }
  ]
}

const MOCK_PORTFOLIO_CONFIG = {
  schemaVersion: 'standard-personal-v1',
  share: {
    title: '风景标准个人作品集',
    coverUrl: `${MOCK_ASSET_ROOT}/demo-image-1.jpg`,
    avatarUrl: MOCK_AVATAR_URL
  },
  components: [
    {
      componentKey: 'mock_carousel',
      componentType: COMPONENT_TYPES.CAROUSEL,
      name: '轮播图',
      sortOrder: 1000,
      enabled: true,
      config: {
        carouselIntervalMs: 3000,
        workIds: [101, 102, 103]
      }
    },
    {
      componentKey: 'mock_profile',
      componentType: COMPONENT_TYPES.PROFILE,
      name: '个人资料',
      sortOrder: 2000,
      enabled: true,
      config: {
        profile: {
          avatarUrl: MOCK_AVATAR_URL,
          displayName: '映期体验账号',
          profession: '婚礼影像服务',
          city: '杭州',
          bio: '这里是一份 mock 作品集，用于体验组件编辑、作品选择和预览效果。',
          tags: [
            {
              name: '风景作品',
              color: '#0f766e'
            },
            {
              name: '婚礼纪实',
              color: '#2d5f9a'
            }
          ],
          wechatQrUrl: ''
        },
        visibleFields: {
          avatar: true,
          displayName: true,
          profession: true,
          city: true,
          bio: true,
          tags: true,
          wechatQr: false
        }
      }
    },
    {
      componentKey: 'mock_work_grid',
      componentType: COMPONENT_TYPES.WORK_GRID,
      name: '双列作品列表',
      sortOrder: 3000,
      enabled: true,
      config: {
        workIds: [101, 102, 103, 104, 105, 106, 107],
        groups: [
          {
            groupKey: 'g_all',
            name: '全部作品',
            sortOrder: 1000,
            workIds: [101, 102, 103, 104, 105, 106, 107]
          }
        ]
      }
    },
    {
      componentKey: 'mock_schedule_query',
      componentType: COMPONENT_TYPES.SCHEDULE_QUERY,
      name: '档期查询',
      sortOrder: 4000,
      enabled: true,
      config: {
        displayMode: 'MODAL_CALENDAR',
        title: '查询我的可约档期',
        description: '体验版仅展示入口，不提交真实档期查询。'
      }
    },
    {
      componentKey: 'mock_contact_form',
      componentType: COMPONENT_TYPES.CONTACT_FORM,
      name: '预留联系信息',
      sortOrder: 5000,
      enabled: true,
      config: {
        displayMode: 'MODAL_FORM',
        title: '预留联系信息',
        description: '留下称呼和需求，方便服务者后续联系。',
        fields: ['contactName', 'phone', 'wechat', 'needs']
      }
    }
  ]
}

function getMockTabs(activeKey) {
  return MOCK_BOTTOM_TABS.map((item) => Object.assign({}, item, {
    active: item.key === activeKey
  }))
}

function showMockLoginRequiredToast() {
  const runtimeWx = getRuntimeWx()
  if (!runtimeWx || !runtimeWx.showToast) {
    return
  }
  runtimeWx.showToast({
    title: MOCK_LOGIN_REQUIRED_MESSAGE,
    icon: 'none'
  })
}

function navigateMockTab(url) {
  const runtimeWx = getRuntimeWx()
  if (!runtimeWx || !runtimeWx.redirectTo || !url) {
    return
  }
  runtimeWx.redirectTo({ url })
}

function formatDate(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatYearMonth(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  return `${year}-${month}`
}

function parseYearMonth(yearMonth) {
  const parts = String(yearMonth || '').split('-')
  const year = Number(parts[0])
  const month = Number(parts[1])
  if (!Number.isFinite(year) || !Number.isFinite(month) || month < 1 || month > 12) {
    return new Date()
  }
  return new Date(year, month - 1, 1)
}

function shiftMockYearMonth(yearMonth, offset) {
  const base = parseYearMonth(yearMonth)
  return formatYearMonth(new Date(base.getFullYear(), base.getMonth() + offset, 1))
}

function buildMockCalendarMonth(yearMonth = formatYearMonth(new Date()), selectedDate = formatDate(new Date())) {
  const firstDate = parseYearMonth(yearMonth)
  const year = firstDate.getFullYear()
  const month = firstDate.getMonth()
  const totalDays = new Date(year, month + 1, 0).getDate()
  const leadingCount = firstDate.getDay()
  const days = []

  for (let index = 0; index < leadingCount; index += 1) {
    days.push({
      key: `blank-leading-${index}`,
      date: '',
      dayNumber: '',
      muted: true,
      selected: false,
      dayClass: 'calendar-day muted'
    })
  }

  for (let day = 1; day <= totalDays; day += 1) {
    const date = formatDate(new Date(year, month, day))
    const selected = date === selectedDate
    days.push({
      key: date,
      date,
      dayNumber: day,
      muted: false,
      selected,
      metaText: '',
      colors: [],
      dayClass: selected ? 'calendar-day selected' : 'calendar-day'
    })
  }

  const trailingCount = (7 - (days.length % 7)) % 7
  for (let index = 0; index < trailingCount; index += 1) {
    const position = days.length
    days.push({
      key: `blank-trailing-${position}`,
      date: '',
      dayNumber: '',
      muted: true,
      selected: false,
      dayClass: 'calendar-day muted'
    })
  }

  return {
    yearMonth: formatYearMonth(firstDate),
    title: `${year} 年 ${month + 1} 月`,
    days
  }
}

function findWorksByIds(workIds = []) {
  const idSet = new Set(workIds.map(toPositiveId).filter(Boolean))
  return MOCK_WORK_LIBRARY.works
    .filter((work) => idSet.has(work.id))
    .map((work) => ({
      workId: work.id,
      title: work.title,
      mediaType: work.mediaType,
      isVideo: work.mediaType === 'VIDEO',
      coverUrl: work.coverUrl,
      mediaUrl: work.mediaUrl,
      thumbnailUrl: work.coverUrl || work.mediaUrl,
      previewUrl: work.mediaUrl || work.coverUrl,
      durationMs: work.durationMs || 0,
      description: work.description || ''
    }))
}

function normalizeComponentConfig(component = {}) {
  return Object.assign({}, component, {
    config: Object.assign({}, component.config || {})
  })
}

function buildMockRenderGroups(componentConfig = {}) {
  const groups = Array.isArray(componentConfig.groups) && componentConfig.groups.length
    ? componentConfig.groups
    : [{
        groupKey: 'g_all',
        name: '全部作品',
        sortOrder: 1000,
        workIds: componentConfig.workIds || []
      }]
  return groups.map((group) => ({
    groupKey: group.groupKey || 'g_all',
    name: group.name || '全部作品',
    sortOrder: Number(group.sortOrder) || 1000,
    works: findWorksByIds(group.workIds || componentConfig.workIds)
  }))
}

function buildMockDisplayTags(renderGroups = []) {
  return renderGroups.map((group, index) => ({
    groupKey: group.groupKey,
    name: group.name,
    active: index === 0
  }))
}

function findMockProfileQrUrl(components = []) {
  const profileComponent = components.find((component) => component.componentType === COMPONENT_TYPES.PROFILE) || {}
  return trimText(
    profileComponent.config &&
    profileComponent.config.profile &&
    profileComponent.config.profile.wechatQrUrl
  )
}

function buildMockQrContactConfig(componentConfig = {}, components = []) {
  const qrUrlSource = componentConfig.qrUrlSource === 'CUSTOM' ? 'CUSTOM' : 'PROFILE'
  return Object.assign({}, MOCK_COMPONENT_DEFAULT_CONFIGS.QR_CONTACT, componentConfig, {
    qrUrlSource,
    qrUrl: qrUrlSource === 'CUSTOM'
      ? trimText(componentConfig.qrUrl)
      : findMockProfileQrUrl(components)
  })
}

function buildMockTextSectionConfig(componentConfig = {}) {
  const alignment = ['LEFT', 'CENTER', 'RIGHT'].includes(componentConfig.alignment)
    ? componentConfig.alignment
    : MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION.alignment
  return Object.assign({}, MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION, componentConfig, {
    title: trimText(componentConfig.title),
    content: trimText(componentConfig.content) || MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION.content,
    alignment,
    alignmentClass: TEXT_SECTION_ALIGNMENT_CLASS_MAP[alignment]
  })
}

function buildMockDividerConfig(componentConfig = {}) {
  const color = DIVIDER_COLOR_VALUE_MAP[componentConfig.color]
    ? componentConfig.color
    : MOCK_COMPONENT_DEFAULT_CONFIGS.DIVIDER.color
  const heightPx = Number(componentConfig.heightPx)
  const normalizedHeightPx = Number.isFinite(heightPx) && heightPx > 0
    ? Math.round(heightPx)
    : MOCK_COMPONENT_DEFAULT_CONFIGS.DIVIDER.heightPx
  const colorValue = DIVIDER_COLOR_VALUE_MAP[color]
  return Object.assign({}, MOCK_COMPONENT_DEFAULT_CONFIGS.DIVIDER, componentConfig, {
    color,
    heightPx: normalizedHeightPx,
    colorValue,
    style: `height: ${normalizedHeightPx}px; background-color: ${colorValue};`
  })
}

function buildMockPortfolioRenderData(config = MOCK_PORTFOLIO_CONFIG) {
  const sourceConfig = clone(config)
  const sourceComponents = Array.isArray(sourceConfig.components) ? sourceConfig.components : []
  const components = sourceComponents
    .filter((component) => component.enabled !== false)
    .map(normalizeComponentConfig)
    .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0))
    .map((component) => {
      const componentConfig = component.config || {}
      if (component.componentType === COMPONENT_TYPES.CAROUSEL) {
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          config: {
            carouselIntervalMs: Number(componentConfig.carouselIntervalMs) || 3000
          },
          carouselIntervalMs: Number(componentConfig.carouselIntervalMs) || 3000,
          works: findWorksByIds(componentConfig.workIds)
        }
      }
      if (component.componentType === COMPONENT_TYPES.PROFILE) {
        const profile = Object.assign({}, componentConfig.profile || {}, {
          visibleFields: Object.assign({}, componentConfig.visibleFields || {})
        })
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          profile
        }
      }
      if (component.componentType === COMPONENT_TYPES.WORK_GRID || component.componentType === COMPONENT_TYPES.WORK_LIST) {
        const renderGroups = buildMockRenderGroups(componentConfig)
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          groups: renderGroups,
          activeGroup: renderGroups[0] || { groupKey: '', name: '', works: [] },
          displayTags: buildMockDisplayTags(renderGroups),
          layout: component.componentType === COMPONENT_TYPES.WORK_LIST ? 'single' : 'grid'
        }
      }
      if (component.componentType === COMPONENT_TYPES.SCHEDULE_QUERY) {
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          scheduleQuery: Object.assign({}, componentConfig)
        }
      }
      if (component.componentType === COMPONENT_TYPES.QR_CONTACT) {
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          qrContact: buildMockQrContactConfig(componentConfig, sourceComponents)
        }
      }
      if (component.componentType === COMPONENT_TYPES.TEXT_SECTION) {
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          textSection: buildMockTextSectionConfig(componentConfig)
        }
      }
      if (component.componentType === COMPONENT_TYPES.DIVIDER) {
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          divider: buildMockDividerConfig(componentConfig)
        }
      }
      return {
        componentKey: component.componentKey,
        componentType: component.componentType,
        name: component.name,
        sortOrder: component.sortOrder,
        contactForm: Object.assign({}, componentConfig)
      }
    })

  return {
    preview: true,
    shareCode: 'MOCK9001',
    title: sourceConfig.share && sourceConfig.share.title ? sourceConfig.share.title : '风景标准个人作品集',
    share: Object.assign({}, sourceConfig.share || {}),
    components
  }
}

function buildStandardPortfolio(config = MOCK_PORTFOLIO_CONFIG) {
  const safeConfig = clone(config)
  return {
    portfolio: {
      portfolioId: MOCK_PORTFOLIO_ID,
      ownerType: 'USER',
      templateType: 'STANDARD',
      publicationStatus: 'DRAFT',
      title: '风景标准个人作品集',
      shareCode: 'MOCK9001',
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-1.jpg`,
      draftRevision: 1,
      publishedRevision: 0,
      updatedAt: '2026-07-06 12:00:00'
    },
    config: safeConfig,
    renderData: buildMockPortfolioRenderData(safeConfig)
  }
}

const MOCK_STANDARD_PORTFOLIO = buildStandardPortfolio()

function resolveMockComponentType(componentType) {
  const normalized = trimText(componentType)
  return MOCK_COMPONENT_OPTIONS.some((item) => item.componentType === normalized)
    ? normalized
    : COMPONENT_TYPES.CAROUSEL
}

function buildMockComponentKey(components = [], componentType) {
  const prefix = MOCK_COMPONENT_KEY_PREFIXES[componentType] || `mock_${String(componentType || 'component').toLowerCase()}`
  let maxIndex = 0
  components.forEach((component) => {
    const key = trimText(component && component.componentKey)
    if (key === prefix) {
      maxIndex = Math.max(maxIndex, 1)
      return
    }
    const match = key.match(new RegExp(`^${prefix}_(\\d+)$`))
    if (match) {
      maxIndex = Math.max(maxIndex, Number(match[1]) || 0)
    }
  })
  return `${prefix}_${maxIndex + 1}`
}

function buildMockComponent(componentType, existingComponents = []) {
  const resolvedType = resolveMockComponentType(componentType)
  const option = MOCK_COMPONENT_OPTIONS.find((item) => item.componentType === resolvedType) || MOCK_COMPONENT_OPTIONS[0]
  const template = MOCK_PORTFOLIO_CONFIG.components.find((component) => component.componentType === resolvedType)
  const component = template
    ? clone(template)
    : {
        componentType: resolvedType,
        name: option.name,
        enabled: true,
        config: clone(MOCK_COMPONENT_DEFAULT_CONFIGS[resolvedType] || {})
      }
  return Object.assign({}, component, {
    componentKey: buildMockComponentKey(existingComponents, resolvedType),
    name: option.name,
    sortOrder: (existingComponents.length + 1) * MOCK_COMPONENT_SORT_ORDER_STEP,
    enabled: true
  })
}

function cloneMockDraft(portfolioDraft) {
  const nextDraft = Object.assign({}, clone(portfolioDraft || MOCK_STANDARD_PORTFOLIO))
  nextDraft.config = Object.assign({}, nextDraft.config || {})
  nextDraft.config.share = Object.assign({}, nextDraft.config.share || {})
  nextDraft.config.components = Array.isArray(nextDraft.config.components)
    ? nextDraft.config.components
    : []
  return nextDraft
}

function refreshMockDraftRenderData(draft) {
  const nextDraft = cloneMockDraft(draft)
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function applyMockComponentOrder(draft, components) {
  const nextDraft = cloneMockDraft(draft)
  nextDraft.config.components = (Array.isArray(components) ? components : []).map((component, index) => {
    return Object.assign({}, component, {
      sortOrder: (index + 1) * MOCK_COMPONENT_SORT_ORDER_STEP
    })
  })
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function addMockComponent(portfolioDraft, componentType) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  const components = nextDraft.config.components.slice()
  return applyMockComponentOrder(
    nextDraft,
    components.concat(buildMockComponent(componentType, components))
  )
}

function removeMockComponent(portfolioDraft, componentKey) {
  const targetKey = trimText(componentKey)
  const nextDraft = cloneMockDraft(portfolioDraft)
  return applyMockComponentOrder(
    nextDraft,
    nextDraft.config.components.filter((component) => component.componentKey !== targetKey)
  )
}

function reorderMockComponent(portfolioDraft, fromIndex, toIndex) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  const components = nextDraft.config.components.slice()
  const sourceIndex = Number(fromIndex)
  const targetIndex = Number(toIndex)
  if (
    !Number.isInteger(sourceIndex) ||
    !Number.isInteger(targetIndex) ||
    sourceIndex < 0 ||
    targetIndex < 0 ||
    sourceIndex >= components.length ||
    targetIndex >= components.length ||
    sourceIndex === targetIndex
  ) {
    return refreshMockDraftRenderData(nextDraft)
  }
  const moving = components.splice(sourceIndex, 1)[0]
  components.splice(targetIndex, 0, moving)
  return applyMockComponentOrder(nextDraft, components)
}

function getMockPortfolioDraft() {
  const runtimeWx = getRuntimeWx()
  if (!runtimeWx || !runtimeWx.getStorageSync) {
    return clone(MOCK_STANDARD_PORTFOLIO)
  }
  try {
    const storedDraft = runtimeWx.getStorageSync(MOCK_PORTFOLIO_DRAFT_STORAGE_KEY)
    if (storedDraft && storedDraft.config && Array.isArray(storedDraft.config.components)) {
      return Object.assign({}, clone(storedDraft), {
        renderData: buildMockPortfolioRenderData(storedDraft.config)
      })
    }
  } catch (error) {
    return clone(MOCK_STANDARD_PORTFOLIO)
  }
  return clone(MOCK_STANDARD_PORTFOLIO)
}

function saveMockPortfolioDraft(draft) {
  const nextDraft = Object.assign({}, clone(draft || MOCK_STANDARD_PORTFOLIO))
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  const runtimeWx = getRuntimeWx()
  if (runtimeWx && runtimeWx.setStorageSync) {
    runtimeWx.setStorageSync(MOCK_PORTFOLIO_DRAFT_STORAGE_KEY, nextDraft)
  }
  return nextDraft
}

function resetMockPortfolioDraft() {
  const runtimeWx = getRuntimeWx()
  if (runtimeWx && runtimeWx.removeStorageSync) {
    runtimeWx.removeStorageSync(MOCK_PORTFOLIO_DRAFT_STORAGE_KEY)
  }
  return clone(MOCK_STANDARD_PORTFOLIO)
}

function updateComponentConfig(portfolioDraft, componentKey, updater) {
  const nextDraft = Object.assign({}, clone(portfolioDraft || MOCK_STANDARD_PORTFOLIO))
  nextDraft.config.components = nextDraft.config.components.map((component) => {
    if (component.componentKey !== componentKey) {
      return component
    }
    const nextConfig = typeof updater === 'function'
      ? updater(clone(component.config || {}))
      : Object.assign({}, component.config || {}, updater || {})
    return Object.assign({}, component, {
      config: nextConfig
    })
  })
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function getWorkIdsFromComponent(component = {}) {
  const config = component.config || {}
  const groups = Array.isArray(config.groups) ? config.groups : []
  if (groups.length) {
    return groups[0].workIds || []
  }
  return config.workIds || []
}

module.exports = {
  MOCK_LOGIN_REQUIRED_MESSAGE,
  MOCK_AVATAR_URL,
  MOCK_PORTFOLIO_DRAFT_STORAGE_KEY,
  MOCK_BOTTOM_TABS,
  MOCK_DASHBOARD,
  MOCK_SCHEDULE_DATA,
  MOCK_WORK_LIBRARY,
  MOCK_PORTFOLIO_LIST,
  MOCK_STANDARD_PORTFOLIO,
  MOCK_COMPONENT_OPTIONS,
  COMPONENT_TYPES,
  clone,
  getMockTabs,
  showMockLoginRequiredToast,
  navigateMockTab,
  buildMockCalendarMonth,
  shiftMockYearMonth,
  buildMockPortfolioRenderData,
  buildStandardPortfolio,
  getMockPortfolioDraft,
  saveMockPortfolioDraft,
  resetMockPortfolioDraft,
  addMockComponent,
  removeMockComponent,
  reorderMockComponent,
  updateComponentConfig,
  getWorkIdsFromComponent,
  trimText
}
