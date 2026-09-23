const {
  buildPortfolioTextTypography
} = require('../../../utils/portfolio-text-typography')
const {
  formatLunarDayMeta,
  formatLunarFullText,
  toLunarDate
} = require('./lunar')

const MOCK_LOGIN_REQUIRED_MESSAGE = '请去“我的”页面注册登录'
const MOCK_AVATAR_URL = 'https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png'
const MOCK_ASSET_ROOT = 'https://cdn2.we-folio.dingchenyong.top/demo'
const MOCK_AUDIO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/IF_YOU-BIGBANG.mp3'
const MOCK_ANIMATION_URL = 'https://cdn2.we-folio.dingchenyong.top/system/OpenAI-transparent.gif'
const MOCK_SKY_VIDEO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/mixkit-blue-sky-background-as-the-clouds-travel-blown-by-the-26108-full-hd.mp4'
const MOCK_WAVES_VIDEO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/mixkit-waves-coming-to-the-beach-5016-full-hd.mp4'
const MOCK_AUDIO_COVER_URL = 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png'
const MOCK_MEDIA_TYPE_LABELS = { IMAGE: '图片', VIDEO: '视频', AUDIO: '音频', ANIMATION: '动图' }
const { selectMockWorksFor } = require('./mock-work-media')
const { createMockComponentConfig, validateMockComponentConfig } = require('./mock-portfolio-components')
const CALENDAR_DAY_COUNT = 42
const MOCK_PORTFOLIO_DRAFT_STORAGE_KEY = 'wefolio_mock_portfolio_draft'
const MOCK_PORTFOLIO_ID = 9001
const MOCK_TAG = {
  id: 201,
  name: '风景作品',
  color: '#0f766e',
  count: 9,
  labelText: '风景作品 9',
  filterStyle: 'color: #0f766e; background: #ffffff; border-color: #0f766e;',
  activeStyle: 'color: #ffffff; background: #0f766e; border-color: #0f766e;'
}
const MOCK_WORK_AUDIT_STATUS = 'PASSED'
const MOCK_WORK_AUDIT_STATUS_TEXT = '审核通过'
const MOCK_WORK_AUDIT_STATUS_TONE = 'passed'
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
  DIVIDER: 'DIVIDER', VIDEO_CAROUSEL:'VIDEO_CAROUSEL', STRUCTURED_TEXT_SECTION:'STRUCTURED_TEXT_SECTION', TEXT_GRID:'TEXT_GRID', CONTACT_INFO:'CONTACT_INFO', HYPERLINK:'HYPERLINK'
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
  DIVIDER: '分割线', VIDEO_CAROUSEL:'视频轮播', STRUCTURED_TEXT_SECTION:'结构化文字', TEXT_GRID:'文字网格', CONTACT_INFO:'联系信息', HYPERLINK:'超链接'
}
const MOCK_COMPONENT_DESCRIPTIONS = {
  CAROUSEL: '展示已选择的图片作品',
  PROFILE: '展示个人资料和服务标签',
  SCHEDULE_QUERY: '开放访客查询档期',
  WORK_GRID: '双列展示图片和视频作品',
  WORK_LIST: '单列展示重点图片和视频作品',
  SINGLE_WORK: '突出展示一个图片或视频作品',
  QR_CONTACT: '展示二维码联系方式',
  CONTACT_FORM: '收集访客预留联系信息',
  TEXT_SECTION: '添加服务说明文字',
  DIVIDER: '分隔不同内容区块', VIDEO_CAROUSEL:'滑动展示视频作品', STRUCTURED_TEXT_SECTION:'自由编排文字区块', TEXT_GRID:'合并与编排文字网格', CONTACT_INFO:'复制演示联系信息', HYPERLINK:'图片入口与本地跳转'
}
const MOCK_COMPONENT_SORT_ORDER_STEP = 1000
const MOCK_SINGLE_WORK_MEDIA_WIDTH_RPX = 710
const MOCK_EDITOR_SCHEMA_REVISION = 3
const MOCK_NAVIGATION_MIN_COUNT = 2
const MOCK_NAVIGATION_MAX_COUNT = 4
const MOCK_NAVIGATION_TITLE_MAX_LENGTH = 5
const MOCK_DEFAULT_BACKGROUND_COLOR = '#FFFFFF'
const MOCK_DEFAULT_NAVIGATION_TITLES = ['主页', '作品', '动态', '联系']
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
  SINGLE_WORK: 'mock_single_work',
  QR_CONTACT: 'mock_qr_contact',
  CONTACT_FORM: 'mock_contact_form',
  TEXT_SECTION: 'mock_text_section',
  DIVIDER: 'mock_divider', VIDEO_CAROUSEL:'mock_video_carousel', STRUCTURED_TEXT_SECTION:'mock_structured_text', TEXT_GRID:'mock_text_grid', CONTACT_INFO:'mock_contact_info', HYPERLINK:'mock_hyperlink'
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
  SINGLE_WORK: {
    workId: 0,
    showTitle: true,
    showDescription: false
  },
  SCHEDULE_QUERY: {
    displayMode: 'MODAL_CALENDAR',
    title: '查询我的可约档期',
    description: '体验版仅展示入口，不提交真实档期查询。'
  },
  QR_CONTACT: {
    qrUrlSource: 'PROFILE',
    qrUrl: ''
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
    alignment: 'LEFT',
    fontFamily: 'SYSTEM',
    fontSizeRpx: 26
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
  aspectRatio,
  width,
  height,
  durationMs,
  sortOrder
}) {
  const normalizedAspectRatio = trimText(aspectRatio)
  return {
    id,
    workId: id,
    mediaType,
    isVideo: mediaType === 'VIDEO',
    isAudio: mediaType === 'AUDIO',
    isAnimation: mediaType === 'ANIMATION',
    mediaTypeLabel: MOCK_MEDIA_TYPE_LABELS[mediaType] || '',
    hasCover: Boolean(coverUrl),
    showPlayIndicator: mediaType === 'VIDEO',
    tagText: ['AUDIO', 'ANIMATION'].includes(mediaType) ? '' : MOCK_TAG.name,
    title,
    originalFileName: fileName,
    mediaUrl,
    coverUrl,
    thumbnailUrl: coverUrl || (mediaType === 'VIDEO' ? '' : mediaUrl),
    previewUrl: mediaUrl || coverUrl,
    aspectRatio: normalizedAspectRatio,
    aspectRatioText: normalizedAspectRatio,
    mimeType,
    fileSize: 0,
    durationMs: durationMs || 0,
    width: Number(width) || 0,
    height: Number(height) || 0,
    description: '',
    serviceDate: '',
    sortOrder,
    status: 'ACTIVE',
    auditStatus: MOCK_WORK_AUDIT_STATUS,
    auditStatusText: MOCK_WORK_AUDIT_STATUS_TEXT,
    auditStatusTone: MOCK_WORK_AUDIT_STATUS_TONE,
    referenceCount: 1,
    tags: ['AUDIO', 'ANIMATION'].includes(mediaType) ? [] : [clone(MOCK_TAG)]
  }
}

function filterMockWorks(works = [], keyword = '', tagId = 0) {
  const normalizedKeyword = trimText(keyword).toLowerCase()
  const normalizedTagId = toPositiveId(tagId)
  return works.filter((work = {}) => {
    const tags = Array.isArray(work.tags) ? work.tags : []
    const keywordText = `${trimText(work.title)} ${tags.map((tag) => trimText(tag.name)).join(' ')}`.toLowerCase()
    const matchesKeyword = !normalizedKeyword || keywordText.includes(normalizedKeyword)
    const matchesTag = !normalizedTagId || tags.some((tag) => toPositiveId(tag.id) === normalizedTagId)
    return matchesKeyword && matchesTag
  })
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
    workCount: 0,
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

const mockScheduleToday = new Date()
const mockScheduleLater = new Date(
  mockScheduleToday.getFullYear(),
  mockScheduleToday.getMonth(),
  mockScheduleToday.getDate() + 3
)

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
  schedules: [
    {
      id: 101,
      date: formatDate(mockScheduleToday),
      scheduleDate: formatDate(mockScheduleToday),
      slotName: '午宴',
      timeRangeText: '10:30-14:00',
      contactText: '婚礼跟拍',
      color: '#d98200',
      colorStyle: 'background: #d98200;',
      status: 'AVAILABLE',
      statusText: '可约',
      statusClass: 'schedule-status teal'
    },
    {
      id: 102,
      date: formatDate(mockScheduleLater),
      scheduleDate: formatDate(mockScheduleLater),
      slotName: '晚宴',
      timeRangeText: '17:30-21:30',
      contactText: '已预留咨询',
      color: '#36516e',
      colorStyle: 'background: #36516e;',
      status: 'PENDING',
      statusText: '待确认',
      statusClass: 'schedule-status amber'
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
  tags: [clone(MOCK_TAG)],
  filterTags: [
    {
      id: null,
      name: '全部',
      color: '',
      count: 7,
      labelText: '全部 7',
      filterStyle: '',
      activeStyle: 'color: #ffffff; background: #212529; border-color: #212529;'
    },
    clone(MOCK_TAG)
  ],
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

MOCK_WORK_LIBRARY.works.push(
  buildWork({ id: 108, mediaType: 'AUDIO', title: 'IF YOU - BIGBANG', fileName: 'IF_YOU-BIGBANG.mp3', mediaUrl: MOCK_AUDIO_URL, coverUrl: MOCK_AUDIO_COVER_URL, mimeType: 'audio/mpeg', sortOrder: 8000 }),
  buildWork({ id: 109, mediaType: 'ANIMATION', title: 'OpenAI 透明动图', fileName: 'OpenAI-transparent.gif', mediaUrl: MOCK_ANIMATION_URL, coverUrl: MOCK_ANIMATION_URL, mimeType: 'image/gif', sortOrder: 9000 }),
  buildWork({ id: 110, mediaType: 'VIDEO', title: '蓝天流云', fileName: 'mixkit-blue-sky-background-as-the-clouds-travel-blown-by-the-26108-full-hd.mp4', mediaUrl: MOCK_SKY_VIDEO_URL, coverUrl: '', mimeType: 'video/mp4', sortOrder: 10000 }),
  buildWork({ id: 111, mediaType: 'VIDEO', title: '海浪沙滩', fileName: 'mixkit-waves-coming-to-the-beach-5016-full-hd.mp4', mediaUrl: MOCK_WAVES_VIDEO_URL, coverUrl: '', mimeType: 'video/mp4', sortOrder: 11000 })
)
MOCK_WORK_LIBRARY.total = MOCK_WORK_LIBRARY.works.length
MOCK_DASHBOARD.metrics.workCount = MOCK_WORK_LIBRARY.total
MOCK_WORK_LIBRARY.filterTags[0].count = MOCK_WORK_LIBRARY.total
MOCK_WORK_LIBRARY.filterTags[0].labelText = `全部 ${MOCK_WORK_LIBRARY.total}`
MOCK_WORK_LIBRARY.tags.forEach(tag => {
  tag.count = MOCK_WORK_LIBRARY.works.filter(work => work.tags.some(item => item.id === tag.id)).length
  tag.labelText = `${tag.name} ${tag.count}`
})
MOCK_WORK_LIBRARY.filterTags[1] = clone(MOCK_WORK_LIBRARY.tags[0])

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
      titleScrollable: true,
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

const MOCK_TEAM_PORTFOLIO_LIST = {
  ownerType: 'TEAM',
  ownerTitle: '团队作品集',
  summary: {
    publishedText: '1 已发布',
    draftText: '0 草稿'
  },
  portfolios: [
    {
      portfolioId: 9902,
      ownerType: 'TEAM',
      templateType: 'STANDARD',
      publicationStatus: 'PUBLISHED',
      statusText: '已发布',
      statusTone: 'published',
      title: '城市婚礼影像团队作品集',
      titleScrollable: false,
      teamName: '映期影像工作室',
      currentRole: '摄影师',
      coverUrl: `${MOCK_ASSET_ROOT}/demo-image-2.jpg`,
      coverAlt: '城市婚礼影像团队作品集',
      updatedText: '最近更新 07-08',
      canPreviewDraft: false,
      canPublishedPreview: true,
      canMaintain: false,
      canShare: true
    }
  ]
}

const MOCK_PORTFOLIO_CONFIG = {
  schemaVersion: 'standard-personal-v1',
  editorSchemaRevision: MOCK_EDITOR_SCHEMA_REVISION,
  share: {
    title: '风景标准个人作品集',
    coverUrl: `${MOCK_ASSET_ROOT}/demo-image-1.jpg`,
    avatarUrl: MOCK_AVATAR_URL
  },
  style: {
    backgroundColor: MOCK_DEFAULT_BACKGROUND_COLOR
  },
  bottomNav: {
    enabled: true,
    items: [
      {
        key: 'nav_mock_home',
        title: '主页'
      },
      {
        key: 'nav_mock_works',
        title: '作品',
        components: []
      }
    ]
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


const MOCK_DEMO_HOME_TYPES = ['TEXT_SECTION','STRUCTURED_TEXT_SECTION','TEXT_GRID','DIVIDER','CONTACT_INFO','QR_CONTACT']
const MOCK_DEMO_WORK_TYPES = ['VIDEO_CAROUSEL','SINGLE_WORK','WORK_LIST','HYPERLINK']
function createMockDemoComponent(type,index) {
  const config = clone(MOCK_COMPONENT_DEFAULT_CONFIGS[type] || createMockComponentConfig(type))
  if (type === 'SINGLE_WORK') config.workId = 109
  if (type === 'TEXT_GRID') {
    const labels = ['婚礼纪实', '自然光影', '温暖叙事', '用心记录']
    config.cells.forEach((cell,index) => {cell.blocks[0].runs[0].text = labels[index] || ''})
  }
  return {componentKey:MOCK_COMPONENT_KEY_PREFIXES[type],componentType:type,name:COMPONENT_NAMES[type],sortOrder:(index+1)*1000,enabled:true,config}
}
MOCK_PORTFOLIO_CONFIG.components.push(...MOCK_DEMO_HOME_TYPES.map((type,index)=>createMockDemoComponent(type,index+5)))
const mockDemoGrid = MOCK_PORTFOLIO_CONFIG.components.find(component=>component.componentType === 'WORK_GRID')
MOCK_PORTFOLIO_CONFIG.components = MOCK_PORTFOLIO_CONFIG.components.filter(component=>component !== mockDemoGrid)
MOCK_PORTFOLIO_CONFIG.bottomNav.items[1].components = MOCK_DEMO_WORK_TYPES.map(createMockDemoComponent).concat(Object.assign({},mockDemoGrid,{sortOrder:5000}))
MOCK_PORTFOLIO_CONFIG.style.componentSpacingRpx = 32
MOCK_PORTFOLIO_CONFIG.backgroundAudio = {enabled:false,workId:108,displayStyle:'DISC'}

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
    const now = new Date()
    return new Date(now.getFullYear(), now.getMonth(), 1)
  }
  return new Date(year, month - 1, 1)
}

function shiftMockYearMonth(yearMonth, offset) {
  const base = parseYearMonth(yearMonth)
  return formatYearMonth(new Date(base.getFullYear(), base.getMonth() + offset, 1))
}

function getMockSchedulesForDate(date) {
  return MOCK_SCHEDULE_DATA.schedules
    .filter((schedule) => schedule.scheduleDate === date)
    .map((schedule) => clone(schedule))
}

function buildMockSelectedDateMeta(date) {
  const value = String(date || '')
  const match = value.match(/^\d{4}-(\d{2})-(\d{2})$/)
  const lunarText = formatLunarFullText(toLunarDate(value))
  return {
    titleText: match ? `${match[1]}月${match[2]}日` : '请选择日期',
    lunarTitleText: lunarText ? `农历${lunarText}` : ''
  }
}

function buildMockCalendarMonth(yearMonth = formatYearMonth(new Date()), selectedDate = formatDate(new Date())) {
  const firstDate = parseYearMonth(yearMonth)
  const year = firstDate.getFullYear()
  const month = firstDate.getMonth()
  const calendarStart = new Date(year, month, 1 - firstDate.getDay())
  const rawDays = Array.from({ length: CALENDAR_DAY_COUNT }, (_, index) => {
    const value = new Date(
      calendarStart.getFullYear(),
      calendarStart.getMonth(),
      calendarStart.getDate() + index
    )
    const date = formatDate(value)
    const schedules = getMockSchedulesForDate(date)
    const statusColors = Array.from(new Set(schedules.map((schedule) => schedule.color).filter(Boolean)))
    const classes = ['calendar-day']
    const currentMonth = value.getFullYear() === year && value.getMonth() === month
    if (!currentMonth) {
      classes.push('muted')
    }
    if (date === selectedDate) {
      classes.push('selected')
    }
    if (schedules.length > 0) {
      classes.push('filled')
    }
    return {
      key: date,
      date,
      dayNumber: value.getDate(),
      currentMonth,
      selected: date === selectedDate,
      colors: statusColors,
      statusColors,
      markerColors: statusColors.map((color) => ({
        color,
        style: `background: ${color};`
      })),
      metaText: formatLunarDayMeta(toLunarDate(date)),
      count: schedules.length,
      countText: schedules.length > 0 ? String(schedules.length) : '',
      dayClass: classes.join(' ')
    }
  })

  return {
    yearMonth: formatYearMonth(firstDate),
    title: `${year} 年 ${month + 1} 月`,
    days: rawDays
  }
}

function findWorksByIds(workIds = []) {
  const ids = [...new Set(workIds.map(toPositiveId).filter(Boolean))]
  return ids.map(id => MOCK_WORK_LIBRARY.works.find(work => work.id === id)).filter(Boolean)
    .map((work) => ({
      workId: work.id,
      title: work.title,
      mediaType: work.mediaType,
      isVideo: work.mediaType === 'VIDEO',
      coverUrl: work.coverUrl,
      mediaUrl: work.mediaUrl,
      thumbnailUrl: work.thumbnailUrl,
      previewUrl: work.mediaUrl || work.coverUrl,
      aspectRatioStyle: buildMockWorkAspectRatioStyle(work),
      aspectRatio: work.aspectRatio,
      aspectRatioText: work.aspectRatioText,
      width: work.width,
      height: work.height,
      durationMs: work.durationMs || 0,
      description: work.description || ''
    }))
}

function buildMockWorkAspectRatioStyle(work = {}) {
  const ratioMatch = trimText(work.aspectRatio || work.aspectRatioText).match(/^(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/)
  const width = ratioMatch && Number(ratioMatch[1]) > 0 ? Number(ratioMatch[1]) : 16
  const height = ratioMatch && Number(ratioMatch[2]) > 0 ? Number(ratioMatch[2]) : 9
  const heightRpx = Math.round(MOCK_SINGLE_WORK_MEDIA_WIDTH_RPX * height / width)
  return `height: ${heightRpx}rpx; aspect-ratio: ${width} / ${height};`
}

function normalizeMockSingleWorkConfig(config = {}) {
  return {
    workId: toPositiveId(config.workId),
    showTitle: typeof config.showTitle === 'boolean' ? config.showTitle : true,
    showDescription: config.showDescription === true
  }
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
    works: findWorksByIds(group.workIds || componentConfig.workIds).filter(work => ['IMAGE','VIDEO'].includes(work.mediaType))
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

function buildMockTextSectionConfig(componentConfig = {}, fontContext = {}) {
  const alignment = ['LEFT', 'CENTER', 'RIGHT'].includes(componentConfig.alignment)
    ? componentConfig.alignment
    : MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION.alignment
  const typography = buildPortfolioTextTypography(
    componentConfig,
    MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION.fontSizeRpx,
    fontContext
  )
  return Object.assign({}, MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION, componentConfig, typography, {
    title: trimText(componentConfig.title),
    content: trimText(componentConfig.content) || MOCK_COMPONENT_DEFAULT_CONFIGS.TEXT_SECTION.content,
    alignment,
    alignmentClass: TEXT_SECTION_ALIGNMENT_CLASS_MAP[alignment]
  })
}

function buildMockDividerConfig(componentConfig = {}) {
  const color = /^#[a-fA-F0-9]{6}$/.test(componentConfig.color) ? componentConfig.color : DIVIDER_COLOR_VALUE_MAP[componentConfig.color]
    ? componentConfig.color
    : MOCK_COMPONENT_DEFAULT_CONFIGS.DIVIDER.color
  const heightPx = Number(componentConfig.heightPx)
  const normalizedHeightPx = Number.isFinite(heightPx) && heightPx > 0
    ? Math.round(heightPx)
    : MOCK_COMPONENT_DEFAULT_CONFIGS.DIVIDER.heightPx
  const colorValue = DIVIDER_COLOR_VALUE_MAP[color] || color
  return Object.assign({}, MOCK_COMPONENT_DEFAULT_CONFIGS.DIVIDER, componentConfig, {
    color,
    heightPx: normalizedHeightPx,
    colorValue,
    style: `height: ${normalizedHeightPx}px; background-color: ${colorValue};`
  })
}

function normalizeMockBackgroundColor(value) {
  const color = trimText(value)
  return /^#[0-9A-Fa-f]{6}$/.test(color) ? color.toUpperCase() : MOCK_DEFAULT_BACKGROUND_COLOR
}

function getMockThemeMode(backgroundColor) {
  const color = normalizeMockBackgroundColor(backgroundColor)
  const red = Number.parseInt(color.slice(1, 3), 16)
  const green = Number.parseInt(color.slice(3, 5), 16)
  const blue = Number.parseInt(color.slice(5, 7), 16)
  return ((red * 299 + green * 587 + blue * 114) / 1000) < 128 ? 'dark' : 'light'
}

function normalizeMockNavigationItem(item = {}, index = 0) {
  const title = Array.from(trimText(item.title)).slice(0, MOCK_NAVIGATION_TITLE_MAX_LENGTH).join('')
  const normalized = {
    key: trimText(item.key) || `nav_mock_${Date.now()}_${index + 1}`,
    title: title || MOCK_DEFAULT_NAVIGATION_TITLES[index] || `菜单${index + 1}`
  }
  const iconUrl = trimText(item.iconUrl)
  if (iconUrl) {
    normalized.iconUrl = iconUrl
  }
  if (index > 0) {
    normalized.components = Array.isArray(item.components) ? item.components : []
  }
  return normalized
}

function normalizeMockPortfolioConfig(config = {}) {
  const normalized = Object.assign({}, clone(config || {}))
  if (Number(normalized.editorSchemaRevision) > MOCK_EDITOR_SCHEMA_REVISION) throw new Error('本地体验草稿版本不兼容，已展示默认示例')
  normalized.editorSchemaRevision = MOCK_EDITOR_SCHEMA_REVISION
  normalized.share = Object.assign({}, normalized.share || {})
  normalized.components = Array.isArray(normalized.components) ? normalized.components : []
  normalized.style = Object.assign({}, normalized.style || {}, {
    componentSpacingRpx: Math.max(0,Math.min(96,Number.isFinite(Number(normalized.style && normalized.style.componentSpacingRpx)) ? Number(normalized.style.componentSpacingRpx) : 32)),
    backgroundColor: normalizeMockBackgroundColor(
      normalized.style && normalized.style.backgroundColor
    )
  })
  normalized.backgroundAudio = require('./mock-portfolio-audio').normalizeMockBackgroundAudio(normalized.backgroundAudio,MOCK_WORK_LIBRARY.works)
  const rawBottomNav = normalized.bottomNav || {}
  const rawItems = Array.isArray(rawBottomNav.items) ? rawBottomNav.items : []
  if (rawBottomNav.enabled === true && rawItems.length >= MOCK_NAVIGATION_MIN_COUNT) {
    normalized.bottomNav = {
      enabled: true,
      items: rawItems
        .slice(0, MOCK_NAVIGATION_MAX_COUNT)
        .map((item, index) => normalizeMockNavigationItem(item, index))
    }
  } else {
    normalized.bottomNav = { enabled: false }
  }
  return normalized
}

function getMockMenuComponents(config = {}, menuKey) {
  const normalized = normalizeMockPortfolioConfig(config)
  if (!normalized.bottomNav.enabled || !menuKey || normalized.bottomNav.items[0].key === menuKey) {
    return normalized.components
  }
  const target = normalized.bottomNav.items.find((item) => item.key === menuKey)
  return target && Array.isArray(target.components) ? target.components : normalized.components
}

function replaceMockMenuComponents(config = {}, menuKey, components = []) {
  const normalized = normalizeMockPortfolioConfig(config)
  const nextComponents = Array.isArray(components) ? components : []
  if (!normalized.bottomNav.enabled || !menuKey || normalized.bottomNav.items[0].key === menuKey) {
    normalized.components = nextComponents
    return normalized
  }
  normalized.bottomNav.items = normalized.bottomNav.items.map((item, index) => {
    if (index === 0 || item.key !== menuKey) {
      return item
    }
    return Object.assign({}, item, { components: nextComponents })
  })
  return normalized
}

function listMockPortfolioComponents(config = {}) {
  const normalized = normalizeMockPortfolioConfig(config)
  const allComponents = normalized.components.slice()
  if (normalized.bottomNav.enabled) {
    normalized.bottomNav.items.slice(1).forEach((item) => {
      allComponents.push(...(item.components || []))
    })
  }
  return allComponents
}

function buildMockRenderComponents(sourceComponents = [], allSourceComponents = sourceComponents, themeMode = 'light', fontContext = {}) {
  return sourceComponents
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
          works: findWorksByIds(componentConfig.workIds).filter(work => work.mediaType === 'IMAGE')
        }
      }
      if (component.componentType === COMPONENT_TYPES.PROFILE) {
        const borderVisible = componentConfig.profileBorder === true
        const horizontalMargin = componentConfig.profileHorizontalMarginRpx === undefined ? 32 : Number(componentConfig.profileHorizontalMarginRpx) || 0
        const verticalMargin = Number(componentConfig.profileVerticalMarginRpx) || 0
        const borderColor = /^#[a-fA-F0-9]{6}$/.test(componentConfig.profileBorderColor) ? componentConfig.profileBorderColor : 'var(--portfolio-border)'
        const profile = Object.assign({}, componentConfig.profile || {}, {
          avatarUrl: MOCK_AVATAR_URL,
          layout: componentConfig.layout === 'HORIZONTAL' ? 'HORIZONTAL' : 'VERTICAL',
          borderVisible,
          spacingStyle: borderVisible ? `padding: ${Math.max(0,Math.min(96,verticalMargin))}rpx ${Math.max(0,Math.min(96,horizontalMargin))}rpx;` : '',
          style: borderVisible ? `border-width: ${Math.max(1,Math.min(12,Number(componentConfig.profileBorderWidthRpx)||1))}rpx; border-color: ${borderColor};` : '',
          visibleFields: Object.assign({avatar:true,displayName:true,profession:true,city:true,bio:true,tags:true,wechatQr:false}, componentConfig.visibleFields || {})
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
          layout: component.componentType === COMPONENT_TYPES.WORK_LIST ? 'single' : 'grid',
          showTitle: componentConfig.showTitle !== false, showDescription: componentConfig.showDescription === true
        }
      }
      if (component.componentType === COMPONENT_TYPES.SINGLE_WORK) {
        const singleWorkConfig = normalizeMockSingleWorkConfig(componentConfig)
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          work: findWorksByIds([singleWorkConfig.workId]).find(work => ['IMAGE','VIDEO','ANIMATION'].includes(work.mediaType)) || null,
          showTitle: singleWorkConfig.showTitle,
          showDescription: singleWorkConfig.showDescription
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
          qrContact: buildMockQrContactConfig(componentConfig, allSourceComponents)
        }
      }
      if (component.componentType === COMPONENT_TYPES.TEXT_SECTION) {
        return {
          componentKey: component.componentKey,
          componentType: component.componentType,
          name: component.name,
          sortOrder: component.sortOrder,
          textSection: buildMockTextSectionConfig(componentConfig,fontContext),
          viewModel: require('./mock-portfolio-text').buildMockTextViewModel(componentConfig,MOCK_WORK_LIBRARY.works,{themeMode,fontContext})
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
      const base = { componentKey: component.componentKey, componentType: component.componentType, name: component.name, sortOrder: component.sortOrder, config: clone(componentConfig) }
      if (component.componentType === 'CONTACT_FORM') return Object.assign(base,{contactForm: clone(componentConfig)})
      if (component.componentType === 'VIDEO_CAROUSEL') return Object.assign(base,{works:findWorksByIds(componentConfig.workIds).filter(work=>work.mediaType === 'VIDEO')})
      if (component.componentType === 'STRUCTURED_TEXT_SECTION') return Object.assign(base,{viewModel:require('./mock-portfolio-text').buildMockTextViewModel(componentConfig,MOCK_WORK_LIBRARY.works,{structured:true,themeMode,fontContext})})
      if (component.componentType === 'TEXT_GRID') {
        try { return Object.assign(base,{viewModel:require('./mock-portfolio-text-grid').buildMockGridViewModel(componentConfig,themeMode,undefined,{},fontContext)}) }
        catch(error) { return Object.assign(base,{unsupported:true,errorMessage:'文字网格配置暂不可用，请重新编辑'}) }
      }
      if (component.componentType === 'CONTACT_INFO') return base
      if (component.componentType === 'HYPERLINK') return Object.assign(base,{work:findWorksByIds([componentConfig.workId]).find(work=>['IMAGE','ANIMATION'].includes(work.mediaType)) || null})
      return Object.assign(base,{unsupported:true})
    })
}

function buildMockPortfolioRenderData(config = MOCK_PORTFOLIO_CONFIG, fontContext = {}) {
  const sourceConfig = normalizeMockPortfolioConfig(config)
  const sourceComponents = Array.isArray(sourceConfig.components) ? sourceConfig.components : []
  const allSourceComponents = listMockPortfolioComponents(sourceConfig)
  const backgroundColor = sourceConfig.style.backgroundColor
  const themeMode = getMockThemeMode(backgroundColor)
  const components = buildMockRenderComponents(sourceComponents, allSourceComponents,themeMode,fontContext)
  const bottomNav = sourceConfig.bottomNav.enabled
    ? {
        enabled: true,
        items: sourceConfig.bottomNav.items.map((item, index) => {
          const renderedItem = {
            key: item.key,
            title: item.title
          }
          if (index > 0) {
            renderedItem.components = buildMockRenderComponents(item.components || [], allSourceComponents,themeMode,fontContext)
          }
          return renderedItem
        })
      }
    : { enabled: false, items: [] }

  return {
    preview: true,
    shareCode: 'MOCK9001',
    title: sourceConfig.share && sourceConfig.share.title ? sourceConfig.share.title : '风景标准个人作品集',
    share: Object.assign({}, sourceConfig.share || {}),
    style: Object.assign({},sourceConfig.style,{ backgroundColor }),
    backgroundAudio: clone(sourceConfig.backgroundAudio),
    themeMode,
    components,
    bottomNav,
    activeMenuKey: bottomNav.enabled ? bottomNav.items[0].key : '',
    activeComponents: components
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
        config: clone(MOCK_COMPONENT_DEFAULT_CONFIGS[resolvedType] || createMockComponentConfig(resolvedType))
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
  nextDraft.config = normalizeMockPortfolioConfig(nextDraft.config)
  return nextDraft
}

function refreshMockDraftRenderData(draft) {
  const nextDraft = cloneMockDraft(draft)
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function applyMockComponentOrder(draft, components, menuKey) {
  const nextDraft = cloneMockDraft(draft)
  const orderedComponents = (Array.isArray(components) ? components : []).map((component, index) => {
    return Object.assign({}, component, {
      sortOrder: (index + 1) * MOCK_COMPONENT_SORT_ORDER_STEP
    })
  })
  nextDraft.config = replaceMockMenuComponents(nextDraft.config, menuKey, orderedComponents)
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function addMockComponent(portfolioDraft, componentType, menuKey) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  const components = getMockMenuComponents(nextDraft.config, menuKey).slice()
  const allComponents = listMockPortfolioComponents(nextDraft.config)
  return applyMockComponentOrder(
    nextDraft,
    components.concat(buildMockComponent(componentType, allComponents)),
    menuKey
  )
}

function removeMockComponent(portfolioDraft, componentKey, menuKey) {
  const targetKey = trimText(componentKey)
  const nextDraft = cloneMockDraft(portfolioDraft)
  return applyMockComponentOrder(
    nextDraft,
    getMockMenuComponents(nextDraft.config, menuKey)
      .filter((component) => component.componentKey !== targetKey),
    menuKey
  )
}

function reorderMockComponent(portfolioDraft, fromIndex, toIndex, menuKey) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  const components = getMockMenuComponents(nextDraft.config, menuKey).slice()
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
  return applyMockComponentOrder(nextDraft, components, menuKey)
}

function updateMockPortfolioStyle(portfolioDraft, backgroundColor) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  nextDraft.config.style = {
    backgroundColor: normalizeMockBackgroundColor(backgroundColor)
  }
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function setMockBottomNavigationCount(portfolioDraft, count) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  const targetCount = Math.min(
    MOCK_NAVIGATION_MAX_COUNT,
    Math.max(1, Math.floor(Number(count) || 1))
  )
  if (targetCount < MOCK_NAVIGATION_MIN_COUNT) {
    nextDraft.config.bottomNav = { enabled: false }
    nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
    return nextDraft
  }
  const currentItems = nextDraft.config.bottomNav.enabled
    ? nextDraft.config.bottomNav.items
    : []
  const items = Array.from({ length: targetCount }, (_, index) => {
    if (currentItems[index]) {
      return normalizeMockNavigationItem(currentItems[index], index)
    }
    return normalizeMockNavigationItem({
      key: `nav_mock_${Date.now()}_${index + 1}`,
      title: MOCK_DEFAULT_NAVIGATION_TITLES[index],
      components: []
    }, index)
  })
  nextDraft.config.bottomNav = { enabled: true, items }
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function renameMockNavigationItem(portfolioDraft, menuKey, title) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  if (!nextDraft.config.bottomNav.enabled) {
    return refreshMockDraftRenderData(nextDraft)
  }
  const normalizedTitle = Array.from(trimText(title)).slice(0, MOCK_NAVIGATION_TITLE_MAX_LENGTH).join('')
  if (!normalizedTitle) {
    return refreshMockDraftRenderData(nextDraft)
  }
  nextDraft.config.bottomNav.items = nextDraft.config.bottomNav.items.map((item) => {
    return item.key === menuKey ? Object.assign({}, item, { title: normalizedTitle }) : item
  })
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function removeMockNavigationItem(portfolioDraft, menuKey) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  if (!nextDraft.config.bottomNav.enabled) {
    return refreshMockDraftRenderData(nextDraft)
  }
  const items = nextDraft.config.bottomNav.items.slice()
  const targetIndex = items.findIndex((item) => item.key === menuKey)
  if (targetIndex < 0) {
    return refreshMockDraftRenderData(nextDraft)
  }
  if (targetIndex === 0 && items[1]) {
    nextDraft.config.components = (items[1].components || []).slice()
  }
  items.splice(targetIndex, 1)
  if (items.length < MOCK_NAVIGATION_MIN_COUNT) {
    nextDraft.config.bottomNav = { enabled: false }
  } else {
    nextDraft.config.bottomNav = {
      enabled: true,
      items: items.map((item, index) => normalizeMockNavigationItem(item, index))
    }
  }
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function switchMockPortfolioMenu(portfolio, menuKey) {
  const nextPortfolio = Object.assign({}, clone(portfolio || {}))
  const bottomNav = nextPortfolio.bottomNav || {}
  const items = Array.isArray(bottomNav.items) ? bottomNav.items : []
  const targetIndex = items.findIndex((item) => item.key === menuKey)
  if (!bottomNav.enabled || targetIndex < 0) {
    return nextPortfolio
  }
  nextPortfolio.activeMenuKey = items[targetIndex].key
  nextPortfolio.activeComponents = targetIndex === 0
    ? (nextPortfolio.components || [])
    : (items[targetIndex].components || [])
  return nextPortfolio
}

function getMockPortfolioDraft() {
  const runtimeWx = getRuntimeWx()
  if (!runtimeWx || !runtimeWx.getStorageSync) {
    return clone(MOCK_STANDARD_PORTFOLIO)
  }
  try {
    const storedDraft = runtimeWx.getStorageSync(MOCK_PORTFOLIO_DRAFT_STORAGE_KEY)
    if (storedDraft && storedDraft.config && Array.isArray(storedDraft.config.components)) {
      return refreshMockDraftRenderData(storedDraft)
    }
    if (storedDraft) throw new Error('本地体验草稿无法读取，已展示默认示例')
  } catch (error) {
    return Object.assign(clone(MOCK_STANDARD_PORTFOLIO),{draftWarning: (error && error.message && error.message.includes('版本不兼容') ? error.message : '本地体验草稿无法读取，已展示默认示例')})
  }
  return clone(MOCK_STANDARD_PORTFOLIO)
}

function saveMockPortfolioDraft(draft) {
  const nextDraft = Object.assign({}, clone(draft || MOCK_STANDARD_PORTFOLIO))
  nextDraft.config = normalizeMockPortfolioConfig(nextDraft.config)
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  delete nextDraft.draftWarning
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

function updateComponentConfig(portfolioDraft, componentKey, updater, menuKey) {
  const nextDraft = cloneMockDraft(portfolioDraft)
  const components = getMockMenuComponents(nextDraft.config, menuKey).map((component) => {
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
  nextDraft.config = replaceMockMenuComponents(nextDraft.config, menuKey, components)
  nextDraft.renderData = buildMockPortfolioRenderData(nextDraft.config)
  return nextDraft
}

function updateMockSingleWorkConfig(portfolioDraft, componentKey, config, menuKey) {
  return updateComponentConfig(
    portfolioDraft,
    componentKey,
    () => normalizeMockSingleWorkConfig(config),
    menuKey
  )
}


/** 验证并应用临时组件配置，失败不污染页面草稿。 */
function applyMockComponentConfig(draft,componentKey,nextConfig,menuKey) {
  const component = getMockMenuComponents(draft.config,menuKey).find(item=>item.componentKey === componentKey)
  if (!component) return {valid:false,message:'组件不存在',draft}
  const result = validateMockComponentConfig(component.componentType,nextConfig,MOCK_WORK_LIBRARY.works)
  if (!result.valid) return Object.assign({},result,{draft})
  let config = clone(nextConfig)
  if(component.componentType === 'HYPERLINK') config = require('./mock-portfolio-hyperlink').normalizeMockHyperlinkConfig(config)
  if(component.componentType === 'CONTACT_INFO') config = require('./mock-portfolio-hyperlink').normalizeMockContactInfoConfig(config)
  if (['TEXT_SECTION','STRUCTURED_TEXT_SECTION'].includes(component.componentType) && !config.backgroundEnabled) delete config.backgroundWorkId
  return {valid:true,message:'',draft:updateComponentConfig(draft,componentKey,()=>config,menuKey)}
}

function getWorkIdsFromComponent(component = {}) {
  const config = component.config || {}
  if (component.componentType === COMPONENT_TYPES.SINGLE_WORK) {
    const workId = toPositiveId(config.workId)
    return workId ? [workId] : []
  }
  const groups = Array.isArray(config.groups) ? config.groups : []
  if (groups.length) {
    return groups[0].workIds || []
  }
  return config.workIds || []
}

module.exports = {
  normalizeMockPortfolioConfig, applyMockComponentConfig,
  MOCK_LOGIN_REQUIRED_MESSAGE,
  MOCK_AVATAR_URL,
  MOCK_PORTFOLIO_DRAFT_STORAGE_KEY,
  MOCK_BOTTOM_TABS,
  MOCK_DASHBOARD,
  MOCK_SCHEDULE_DATA,
  MOCK_WORK_LIBRARY,
  MOCK_PORTFOLIO_LIST,
  MOCK_TEAM_PORTFOLIO_LIST,
  MOCK_STANDARD_PORTFOLIO,
  MOCK_COMPONENT_OPTIONS,
  COMPONENT_TYPES,
  clone,
  filterMockWorks,
  getMockTabs,
  showMockLoginRequiredToast,
  navigateMockTab,
  buildMockCalendarMonth,
  buildMockSelectedDateMeta,
  getMockSchedulesForDate,
  shiftMockYearMonth,
  buildMockPortfolioRenderData,
  buildStandardPortfolio,
  getMockPortfolioDraft,
  saveMockPortfolioDraft,
  resetMockPortfolioDraft,
  addMockComponent,
  removeMockComponent,
  reorderMockComponent,
  updateMockPortfolioStyle,
  setMockBottomNavigationCount,
  renameMockNavigationItem,
  removeMockNavigationItem,
  switchMockPortfolioMenu,
  getMockMenuComponents,
  updateComponentConfig,
  updateMockSingleWorkConfig,
  getWorkIdsFromComponent,
  trimText
}
