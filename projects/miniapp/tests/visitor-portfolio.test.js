const assert = require('node:assert/strict')
const fs = require('node:fs')
const test = require('node:test')
const path = require('node:path')

const {
  buildVisitorEventPayload,
  normalizeVisitorPortfolio,
  normalizeVisitorSchedule,
  normalizeVisitorScheduleOptions,
  normalizeVisitorScheduleQueryResult,
  switchDisplayGroup
} = require('../utils/visitor-portfolio')

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    target[key] = patch[key]
  })
}

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function createDeferred() {
  let resolve
  let reject
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

function loadVisitorPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const visitorSessionPath = path.join(__dirname, '../utils/visitor-session.js')
  const requestCacheKey = require.resolve(requestPath)
  const visitorSessionCacheKey = require.resolve(visitorSessionPath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalVisitorSessionCache = require.cache[visitorSessionCacheKey]
  delete require.cache[require.resolve(pagePath)]
  delete require.cache[visitorSessionCacheKey]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest,
      VISITOR_TOKEN_STORAGE_KEY: 'wefolio_visitor_token',
      VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY: 'wefolio_visitor_token_expires_at'
    }
  }

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    getStorageSync() { return 'visitor-a' },
    setStorageSync() {},
    login() {},
    previewImage() {},
    showToast() {}
  }, wxOverrides)
  require(pagePath)
  delete global.Page
  delete global.wx
  if (originalRequestCache) {
    require.cache[requestCacheKey] = originalRequestCache
  } else {
    delete require.cache[requestCacheKey]
  }
  if (originalVisitorSessionCache) {
    require.cache[visitorSessionCacheKey] = originalVisitorSessionCache
  } else {
    delete require.cache[visitorSessionCacheKey]
  }

  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    }
  })
}

function loadScheduleQueryComponent(fakeRequest, wxOverrides = {}) {
  const componentPath = path.join(__dirname, '../components/portfolio-schedule-query/portfolio-schedule-query.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const visitorSessionPath = path.join(__dirname, '../utils/visitor-session.js')
  const requestCacheKey = require.resolve(requestPath)
  const visitorSessionCacheKey = require.resolve(visitorSessionPath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalVisitorSessionCache = require.cache[visitorSessionCacheKey]
  const previousComponent = global.Component
  const previousWx = global.wx
  delete require.cache[require.resolve(componentPath)]
  delete require.cache[visitorSessionCacheKey]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
    }
  }

  let componentDefinition
  try {
    global.Component = (definition) => {
      componentDefinition = definition
    }
    global.wx = Object.assign({
      showToast() {}
    }, wxOverrides)
    require(componentPath)
  } finally {
    if (previousComponent === undefined) {
      delete global.Component
    } else {
      global.Component = previousComponent
    }
    if (previousWx === undefined) {
      delete global.wx
    } else {
      global.wx = previousWx
    }
    if (originalRequestCache) {
      require.cache[requestCacheKey] = originalRequestCache
    } else {
      delete require.cache[requestCacheKey]
    }
    if (originalVisitorSessionCache) {
      require.cache[visitorSessionCacheKey] = originalVisitorSessionCache
    } else {
      delete require.cache[visitorSessionCacheKey]
    }
  }

  const component = {
    data: clone(componentDefinition.data || {}),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    }
  }
  Object.keys(componentDefinition.methods || {}).forEach((methodName) => {
    component[methodName] = componentDefinition.methods[methodName].bind(component)
  })
  return component
}

test('loadScheduleQueryComponent restores globals when component module fails to load', () => {
  const visitorUtilsPath = path.join(__dirname, '../utils/visitor-portfolio.js')
  const visitorUtilsCacheKey = require.resolve(visitorUtilsPath)
  const originalVisitorUtilsCache = require.cache[visitorUtilsCacheKey]
  const previousComponent = global.Component
  const previousWx = global.wx
  require.cache[visitorUtilsCacheKey] = {
    id: visitorUtilsPath,
    filename: visitorUtilsPath,
    loaded: true,
    exports: null
  }

  try {
    assert.throws(() => loadScheduleQueryComponent(() => Promise.resolve({})))
    assert.equal(global.Component, previousComponent)
    assert.equal(global.wx, previousWx)
  } finally {
    if (originalVisitorUtilsCache) {
      require.cache[visitorUtilsCacheKey] = originalVisitorUtilsCache
    } else {
      delete require.cache[visitorUtilsCacheKey]
    }
    if (previousComponent === undefined) {
      delete global.Component
    } else {
      global.Component = previousComponent
    }
    if (previousWx === undefined) {
      delete global.wx
    } else {
      global.wx = previousWx
    }
  }
})

test('normalizes visitor portfolio under maintenance state', () => {
  const result = normalizeVisitorPortfolio({
    shareCode: 'PF001',
    title: '林安婚礼司仪',
    underMaintenance: true,
    maintenanceText: {
      primary: 'UNDER MAINTENANCE',
      secondary: '维护中'
    }
  })

  assert.equal(result.underMaintenance, true)
  assert.equal(result.title, '林安婚礼司仪')
  assert.equal(result.maintenanceText.primary, 'UNDER MAINTENANCE')
  assert.deepEqual(result.components, [])
})

test('normalizes visitor open metadata from backend response', () => {
  const result = normalizeVisitorPortfolio({
    visitorKey: 'server-visitor-key',
    isNewVisitor: true,
    needVisitorProfile: true,
    visitorProfileToken: 'profile-token-1',
    renderData: {
      shareCode: 'PF001',
      title: '林安婚礼司仪',
      components: []
    }
  })

  assert.equal(result.visitorKey, 'server-visitor-key')
  assert.equal(result.isNewVisitor, true)
  assert.equal(result.needVisitorProfile, true)
  assert.equal(result.visitorProfileToken, 'profile-token-1')
})

test('normalizes visitor portfolio components from published config', () => {
  const result = normalizeVisitorPortfolio({
    shareCode: 'PF001',
    publishedRevision: 3,
    config: {
      share: { title: ' 林安婚礼司仪 ' },
      components: [
        { componentKey: 'c_profile', componentType: 'PROFILE', sortOrder: 2000, enabled: true, config: {} },
        { componentKey: 'c_text', componentType: 'TEXT_SECTION', sortOrder: 1000, enabled: true, config: { title: '服务说明' } },
        { componentKey: 'c_carousel', componentType: 'CAROUSEL', sortOrder: 3000, enabled: true, config: { carouselIntervalMs: 4500 } }
      ]
    }
  })

  assert.equal(result.title, '林安婚礼司仪')
  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_text', 'c_profile', 'c_carousel'])
  assert.equal(result.components[2].carouselIntervalMs, 4500)
})

test('normalizes carousel interval with three second default for render pages', () => {
  const defaultResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        { componentKey: 'c_carousel', componentType: 'CAROUSEL', sortOrder: 1000, works: [] }
      ]
    }
  })
  const configuredResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_carousel',
          componentType: 'CAROUSEL',
          sortOrder: 1000,
          config: { carouselIntervalMs: 5200 },
          works: []
        }
      ]
    }
  })

  assert.equal(defaultResult.components[0].carouselIntervalMs, 3000)
  assert.equal(configuredResult.components[0].carouselIntervalMs, 5200)
})

test('normalizes carousel work ratio fields for render pages', () => {
  const result = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_carousel',
          componentType: 'CAROUSEL',
          sortOrder: 1000,
          works: [
            {
              workId: 11,
              title: '横版案例',
              mediaType: 'IMAGE',
              coverUrl: 'https://cdn.example.com/a-thumb.jpg',
              mediaUrl: 'https://cdn.example.com/a.jpg',
              aspectRatio: '16:9',
              aspectRatioText: '16:9',
              width: 1920,
              height: 1080
            }
          ]
        }
      ]
    }
  })

  const work = result.components[0].works[0]
  assert.equal(work.aspectRatio, '16:9')
  assert.equal(work.aspectRatioText, '16:9')
  assert.equal(work.width, 1920)
  assert.equal(work.height, 1080)
})

test('normalizes singular work render data with title switch and safe video ratio', () => {
  const portfolio = normalizeVisitorPortfolio({
    renderData: {
      preview: true,
      components: [
        {
          componentKey: 'c_single',
          componentType: 'SINGLE_WORK',
          sortOrder: 1000,
          showTitle: false,
          work: {
            workId: 12,
            title: '婚礼快剪',
            mediaType: 'VIDEO',
            coverUrl: 'cover.jpg',
            mediaUrl: 'movie.mp4',
            aspectRatio: '9:16'
          }
        },
        {
          componentKey: 'c_fallback',
          componentType: 'SINGLE_WORK',
          sortOrder: 2000,
          work: {
            workId: 13,
            mediaType: 'VIDEO',
            mediaUrl: 'fallback.mp4',
            aspectRatio: 'invalid'
          }
        }
      ]
    }
  })

  assert.equal(portfolio.components[0].showTitle, false)
  assert.equal(portfolio.components[0].work.previewUrl, 'movie.mp4')
  assert.equal(portfolio.components[0].work.aspectRatioStyle, 'height: 1262rpx; aspect-ratio: 9 / 16;')
  assert.equal(portfolio.components[1].showTitle, true)
  assert.equal(portfolio.components[1].work.aspectRatioStyle, 'height: 399rpx; aspect-ratio: 16 / 9;')
})

test('singular work keeps video media and poster urls independent', () => {
  const portfolio = normalizeVisitorPortfolio({
    renderData: {
      preview: true,
      components: [
        {
          componentKey: 'c_cover_only',
          componentType: 'SINGLE_WORK',
          work: { workId: 21, mediaType: 'VIDEO', coverUrl: 'cover.jpg', mediaUrl: '' }
        },
        {
          componentKey: 'c_media_only',
          componentType: 'SINGLE_WORK',
          work: { workId: 22, mediaType: 'VIDEO', coverUrl: '', mediaUrl: 'movie.mp4' }
        },
        {
          componentKey: 'c_image_cover_only',
          componentType: 'SINGLE_WORK',
          work: { workId: 23, mediaType: 'IMAGE', coverUrl: 'thumb.jpg', mediaUrl: '' }
        }
      ]
    }
  })

  const componentMap = Object.fromEntries(portfolio.components.map((component) => [component.componentKey, component]))
  assert.equal(componentMap.c_cover_only.work.previewUrl, '')
  assert.equal(componentMap.c_cover_only.work.thumbnailUrl, 'cover.jpg')
  assert.equal(componentMap.c_media_only.work.previewUrl, 'movie.mp4')
  assert.equal(componentMap.c_media_only.work.thumbnailUrl, '')
  assert.equal(componentMap.c_image_cover_only.work.previewUrl, '')
})

test('visitor render skips unavailable singular work while preview keeps repair context', () => {
  const components = [
    {
      componentKey: 'c_missing',
      componentType: 'SINGLE_WORK',
      sortOrder: 1000,
      work: null
    },
    {
      componentKey: 'c_text',
      componentType: 'TEXT_SECTION',
      sortOrder: 2000,
      textSection: { content: '保留内容' }
    }
  ]

  const visitor = normalizeVisitorPortfolio({ renderData: { preview: false, components } })
  const preview = normalizeVisitorPortfolio({ renderData: { preview: true, components } })

  assert.deepEqual(visitor.components.map((item) => item.componentKey), ['c_text'])
  assert.deepEqual(preview.components.map((item) => item.componentKey), ['c_missing', 'c_text'])
})

test('normalizes contact form display mode for render and config pages', () => {
  const renderResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_contact',
          componentType: 'CONTACT_FORM',
          sortOrder: 1000,
          contactForm: {
            title: '留下联系方式',
            displayMode: 'INLINE_FORM',
            fields: ['contactName', 'phone']
          }
        }
      ]
    }
  })
  const configResult = normalizeVisitorPortfolio({
    config: {
      components: [
        {
          componentKey: 'c_contact',
          componentType: 'CONTACT_FORM',
          sortOrder: 1000,
          config: {
            title: '预约沟通',
            displayMode: 'MODAL_FORM',
            fields: ['contactName', 'wechat']
          }
        }
      ]
    }
  })
  const invalidResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_contact',
          componentType: 'CONTACT_FORM',
          sortOrder: 1000,
          contactForm: { displayMode: 'SIDE_PANEL' }
        }
      ]
    }
  })

  assert.equal(renderResult.components[0].contactForm.displayMode, 'INLINE_FORM')
  assert.equal(renderResult.components[0].contactForm.title, '留下联系方式')
  assert.deepEqual(renderResult.components[0].contactForm.fields, ['contactName', 'phone'])
  assert.equal(configResult.components[0].contactForm.displayMode, 'MODAL_FORM')
  assert.equal(configResult.components[0].contactForm.title, '预约沟通')
  assert.equal(invalidResult.components[0].contactForm.displayMode, 'MODAL_FORM')
})

test('normalizes text section content and alignment for render and config pages', () => {
  const renderResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_text',
          componentType: 'TEXT_SECTION',
          sortOrder: 1000,
          textSection: {
            content: '第一行\n第二行',
            alignment: 'RIGHT'
          }
        }
      ]
    }
  })
  const configResult = normalizeVisitorPortfolio({
    config: {
      components: [
        {
          componentKey: 'c_text',
          componentType: 'TEXT_SECTION',
          sortOrder: 1000,
          config: {
            content: ' 服务说明 ',
            alignment: 'CENTER'
          }
        }
      ]
    }
  })
  const invalidResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_text',
          componentType: 'TEXT_SECTION',
          sortOrder: 1000,
          textSection: {
            content: '说明',
            alignment: 'JUSTIFY'
          }
        }
      ]
    }
  })

  assert.equal(renderResult.components[0].textSection.content, '第一行\n第二行')
  assert.equal(renderResult.components[0].textSection.alignment, 'RIGHT')
  assert.equal(renderResult.components[0].textSection.alignmentClass, 'align-right')
  assert.equal(configResult.components[0].textSection.content, '服务说明')
  assert.equal(configResult.components[0].textSection.alignmentClass, 'align-center')
  assert.equal(invalidResult.components[0].textSection.alignment, 'LEFT')
  assert.equal(invalidResult.components[0].textSection.alignmentClass, 'align-left')
})

test('normalizes divider color and height for render and config pages', () => {
  const renderResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_divider',
          componentType: 'DIVIDER',
          sortOrder: 1000,
          divider: {
            color: 'BLACK',
            heightPx: 24
          }
        }
      ]
    }
  })
  const configResult = normalizeVisitorPortfolio({
    config: {
      components: [
        {
          componentKey: 'c_divider',
          componentType: 'DIVIDER',
          sortOrder: 1000,
          config: {
            color: 'TRANSPARENT',
            heightPx: '32'
          }
        }
      ]
    }
  })
  const invalidResult = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_divider',
          componentType: 'DIVIDER',
          sortOrder: 1000,
          divider: {
            color: 'BLUE',
            heightPx: 0
          }
        }
      ]
    }
  })

  assert.equal(renderResult.components[0].divider.color, 'BLACK')
  assert.equal(renderResult.components[0].divider.heightPx, 24)
  assert.match(renderResult.components[0].divider.style, /height:\s*24px/)
  assert.match(renderResult.components[0].divider.style, /background-color:\s*#000000/)
  assert.equal(configResult.components[0].divider.color, 'TRANSPARENT')
  assert.equal(configResult.components[0].divider.heightPx, 32)
  assert.match(configResult.components[0].divider.style, /background-color:\s*transparent/)
  assert.equal(invalidResult.components[0].divider.color, 'GRAY')
  assert.equal(invalidResult.components[0].divider.heightPx, 16)
})

test('normalizes visitor portfolio from backend render data first', () => {
  const result = normalizeVisitorPortfolio({
    title: '旧标题',
    config: {
      share: { title: '旧配置标题' },
      components: [
        { componentKey: 'c_old', componentType: 'TEXT_SECTION', sortOrder: 1000, config: { title: '旧组件' } }
      ]
    },
    renderData: {
      shareCode: 'PF001',
      portfolioId: 88,
      title: '林安婚礼司仪',
      preview: true,
      share: {
        title: '林安婚礼司仪',
        intro: '温暖沉稳',
        coverUrl: 'https://cdn.example.com/share.jpg'
      },
      components: [
        {
          componentKey: 'c_list',
          componentType: 'WORK_LIST',
          name: '单列作品列表',
          sortOrder: 2000,
          title: '精选案例',
          groups: [
            {
              groupKey: 'g_featured',
              name: '精选',
              sortOrder: 1000,
              works: [
                {
                  workId: 11,
                  title: '迎宾布置',
                  mediaType: 'IMAGE',
                  coverUrl: 'https://cdn.example.com/cover.jpg',
                  mediaUrl: 'https://cdn.example.com/media.jpg'
                }
              ]
            }
          ]
        }
      ]
    }
  })

  assert.equal(result.preview, true)
  assert.equal(result.title, '林安婚礼司仪')
  assert.equal(Object.hasOwn(result.share, 'intro'), false)
  assert.equal(result.share.coverUrl, 'https://cdn.example.com/share.jpg')
  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_list'])
  assert.equal(result.components[0].layout, 'single')
  assert.equal(result.components[0].activeGroup.name, '全部')
  assert.equal(result.components[0].activeGroup.works[0].workId, 11)
})

test('normalizes profile component with visible field switches applied', () => {
  const result = normalizeVisitorPortfolio({
    renderData: {
      title: '丁Sir 个人作品集',
      components: [
        {
          componentKey: 'c_profile',
          componentType: 'PROFILE',
          sortOrder: 1000,
          profile: {
            avatarUrl: 'https://cdn.example.com/avatar.jpg',
            displayName: '丁Sir',
            profession: '全栈',
            city: '杭州、湖州',
            bio: 'OPC',
            wechatQrUrl: 'https://cdn.example.com/wechat-qr.jpg',
            tags: [{ name: '主持', color: '#0f766e' }],
            visibleFields: {
              avatar: true,
              displayName: true,
              profession: false,
              city: false,
              bio: true,
              tags: false,
              wechatQr: true
            }
          }
        }
      ]
    }
  })

  const profile = result.components[0].profile
  assert.equal(profile.avatarUrl, 'https://cdn.example.com/avatar.jpg')
  assert.equal(profile.displayName, '丁Sir')
  assert.equal(profile.profession, '')
  assert.equal(profile.city, '')
  assert.equal(profile.bio, 'OPC')
  assert.deepEqual(profile.tags, [])
  assert.equal(profile.wechatQrUrl, 'https://cdn.example.com/wechat-qr.jpg')
  assert.deepEqual(profile.visibleFields, {
    avatar: true,
    displayName: true,
    profession: false,
    city: false,
    bio: true,
    tags: false,
    wechatQr: true
  })
})

test('normalizes work grid tags and qr contact preview url from render data', () => {
  const result = normalizeVisitorPortfolio({
    renderData: {
      title: '林安婚礼司仪',
      components: [
        {
          componentKey: 'c_grid',
          componentType: 'WORK_GRID',
          sortOrder: 1000,
          groups: [
            { groupKey: 'g_all', name: '全部案例', sortOrder: 1000, works: [] },
            { groupKey: 'g_outdoor', name: '户外案例', sortOrder: 2000, works: [] }
          ]
        },
        {
          componentKey: 'c_qr',
          componentType: 'QR_CONTACT',
          sortOrder: 2000,
          qrContact: {
            title: '微信联系',
            qrUrl: 'https://cdn.example.com/qr.jpg'
          }
        }
      ]
    }
  })

  assert.equal(result.components[0].layout, 'grid')
  assert.deepEqual(result.components[0].displayTags, [
    { groupKey: '__all', name: '全部', active: true },
    { groupKey: 'g_all', name: '全部案例', active: false },
    { groupKey: 'g_outdoor', name: '户外案例', active: false }
  ])
  assert.equal(result.components[1].qrContact.qrUrl, 'https://cdn.example.com/qr.jpg')
  assert.equal(Object.prototype.hasOwnProperty.call(result.components[1].qrContact, 'title'), false)
  assert.equal(Object.prototype.hasOwnProperty.call(result.components[1].qrContact, 'description'), false)
  assert.equal(result.components[1].previewImageUrl, 'https://cdn.example.com/qr.jpg')
})

test('work display groups expose all tab in tag order and dedupe repeated works', () => {
  const result = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_grid',
          componentType: 'WORK_GRID',
          sortOrder: 1000,
          groups: [
            {
              groupKey: 'g_indoor',
              name: '室内案例',
              sortOrder: 2000,
              works: [
                { workId: 22, title: '室内 B', mediaType: 'VIDEO', coverUrl: 'b.jpg', mediaUrl: 'b.mp4' },
                { workId: 11, title: '室内 A', mediaType: 'IMAGE', coverUrl: 'a-thumb.jpg', mediaUrl: 'a.jpg' }
              ]
            },
            {
              groupKey: 'g_outdoor',
              name: '户外案例',
              sortOrder: 1000,
              works: [
                { workId: 33, title: '户外 C', mediaType: 'IMAGE', coverUrl: 'c-thumb.jpg', mediaUrl: 'c.jpg' },
                { workId: 22, title: '室内 B', mediaType: 'VIDEO', coverUrl: 'b.jpg', mediaUrl: 'b.mp4' }
              ]
            }
          ]
        }
      ]
    }
  })

  const component = result.components[0]
  assert.equal(component.activeGroupKey, '__all')
  assert.deepEqual(component.activeGroup.works.map((work) => work.workId), [33, 22, 11])
  assert.deepEqual(component.displayTags.map((tag) => tag.name), ['全部', '户外案例', '室内案例'])
  assert.equal(component.activeGroup.works[1].isVideo, true)
  assert.equal(component.activeGroup.works[1].thumbnailUrl, 'b.jpg')
  assert.equal(component.activeGroup.works[1].previewUrl, 'b.mp4')
})

test('display group switch can return from a tag to all works', () => {
  const portfolio = normalizeVisitorPortfolio({
    renderData: {
      components: [
        {
          componentKey: 'c_list',
          componentType: 'WORK_LIST',
          sortOrder: 1000,
          groups: [
            {
              groupKey: 'g_a',
              name: 'A',
              sortOrder: 1000,
              works: [{ workId: 1, title: 'A1', mediaType: 'IMAGE', mediaUrl: 'a.jpg' }]
            },
            {
              groupKey: 'g_b',
              name: 'B',
              sortOrder: 2000,
              works: [{ workId: 2, title: 'B1', mediaType: 'IMAGE', mediaUrl: 'b.jpg' }]
            }
          ]
        }
      ]
    }
  })

  const tagged = switchDisplayGroup(portfolio, 'c_list', 'g_b')
  assert.equal(tagged.components[0].activeGroupKey, 'g_b')
  assert.deepEqual(tagged.components[0].activeGroup.works.map((work) => work.workId), [2])

  const all = switchDisplayGroup(tagged, 'c_list', '__all')
  assert.equal(all.components[0].activeGroupKey, '__all')
  assert.deepEqual(all.components[0].activeGroup.works.map((work) => work.workId), [1, 2])
})

test('visitor display group switch marks work content as switching briefly', () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const timers = []
  global.setTimeout = (handler, delay) => {
    timers.push({ handler, delay })
    return `timer-${timers.length}`
  }
  global.clearTimeout = () => {}

  try {
    const page = loadVisitorPage(() => Promise.resolve({}))
    page.data.portfolio = normalizeVisitorPortfolio({
      renderData: {
        components: [
          {
            componentKey: 'c_list',
            componentType: 'WORK_LIST',
            groups: [
              {
                groupKey: 'g_a',
                name: 'A',
                sortOrder: 1000,
                works: [{ workId: 1, title: 'A1', mediaType: 'IMAGE', mediaUrl: 'a.jpg' }]
              },
              {
                groupKey: 'g_b',
                name: 'B',
                sortOrder: 2000,
                works: [{ workId: 2, title: 'B1', mediaType: 'IMAGE', mediaUrl: 'b.jpg' }]
              }
            ]
          }
        ]
      }
    })

    page.handleDisplayTagTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_list',
          groupKey: 'g_b'
        }
      }
    })

    assert.equal(page.data.portfolio.components[0].activeGroupKey, 'g_b')
    assert.equal(page.data.displaySwitchingComponentKey, 'c_list')
    assert.equal(timers.length, 1)
    assert.equal(timers[0].delay, 180)

    timers[0].handler()

    assert.equal(page.data.displaySwitchingComponentKey, '')
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
})

test('builds visitor event payload with idempotency key', () => {
  const payload = buildVisitorEventPayload({
    visitorKey: 'visitor-a',
    eventType: 'VIDEO_PLAYED',
    workId: 11,
    mediaType: 'VIDEO',
    durationSeconds: 18,
    metadata: {
      action: 'PREVIEW_QR'
    }
  }, 'event-1')

  assert.deepEqual(payload, {
    visitorKey: 'visitor-a',
    eventType: 'VIDEO_PLAYED',
    workId: 11,
    mediaType: 'VIDEO',
    durationSeconds: 18,
    metadata: {
      action: 'PREVIEW_QR'
    },
    idempotencyKey: 'event-1'
  })
})

test('visitor page uses source type constant for WeChat share card', () => {
  const pageSource = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.js'),
    'utf8'
  )
  const visitorSessionSource = fs.readFileSync(
    path.join(__dirname, '../utils/visitor-session.js'),
    'utf8'
  )

  assert.match(visitorSessionSource, /const SOURCE_TYPE_WECHAT_SHARE_CARD = 'WECHAT_SHARE_CARD'/)
  assert.equal((pageSource.match(/sourceType: SOURCE_TYPE_WECHAT_SHARE_CARD/g) || []).length, 3)
  assert.equal((pageSource.match(/sourceType: 'WECHAT_SHARE_CARD'/g) || []).length, 0)
})

test('visitor page secondary share keeps the new visitor subpackage path', () => {
  const requests = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })
  page.data.shareCode = 'PF 001'
  page.data.portfolio = {
    title: '林安婚礼司仪',
    share: {
      title: '林安婚礼司仪',
      coverUrl: 'https://example.test/cover.jpg'
    }
  }

  const share = page.onShareAppMessage()

  assert.equal(
    share.path,
    '/pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode=PF%20001'
  )
  assert.doesNotMatch(share.path, /shareGuide/)
  page.onShareTimeline()
  assert.deepEqual(requests, [])
})

test('personal visitor shows timeline guide only after displayable content loads', async () => {
  const shareMenus = []
  const requests = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({
      visitorKey: 'visitor-a',
      renderData: {
        shareCode: 'PF 001',
        title: '林安个人作品集',
        share: {
          title: '个人分享标题',
          coverUrl: 'https://example.test/cover.jpg'
        },
        components: [{
          componentKey: 'profile-1',
          componentType: 'PROFILE',
          sortOrder: 1000,
          profile: { displayName: '林安' }
        }]
      }
    })
  })
  global.wx = {
    login(options) { options.success({ code: 'wx-code' }) },
    setStorageSync() {},
    showShareMenu(options) { shareMenus.push(options) },
    showToast() {}
  }

  try {
    const opening = page.onLoad({ shareCode: 'PF 001', shareGuide: 'timeline', sharePortfolioId: '88' })
    assert.equal(page.data.timelineGuideRequested, true)
    assert.equal(page.data.timelineGuideVisible, false)
    await opening
    assert.deepEqual(shareMenus, [{ menus: ['shareAppMessage', 'shareTimeline'] }])
    assert.equal(page.data.timelineGuideVisible, true)
    requests.length = 0
    assert.deepEqual(page.onShareTimeline(), {
      title: '个人分享标题',
      query: 'shareCode=PF%20001',
      imageUrl: 'https://example.test/cover.jpg'
    })
    page.onShareTimeline()
    assert.deepEqual(requests, [{
      url: '/api/mine/portfolios/88/share-records',
      method: 'POST',
      data: {
        shareChannel: 'WECHAT_TIMELINE',
        shareScene: 'PORTFOLIO_LIST'
      }
    }])
    page.handleCloseTimelineGuide()
    assert.equal(page.data.timelineGuideRequested, false)
    assert.equal(page.data.timelineGuideVisible, false)
  } finally {
    delete global.wx
  }
})

test('personal visitor keeps timeline guide hidden for empty, maintenance, and failed responses', async () => {
  const page = loadVisitorPage(() => Promise.reject(new Error('network')))
  global.wx = {
    login(options) { options.success({ code: 'wx-code' }) },
    setStorageSync() {},
    showToast() {}
  }

  try {
    await page.onLoad({ shareCode: 'PF001', shareGuide: 'timeline' })
    assert.equal(page.data.timelineGuideRequested, true)
    assert.equal(page.data.timelineGuideVisible, false)

    page.applyVisitorOpenResponse({
      renderData: {
        shareCode: 'PF001',
        components: []
      }
    })
    assert.equal(page.data.timelineGuideVisible, false)

    page.applyVisitorOpenResponse({
      renderData: {
        shareCode: 'PF001',
        underMaintenance: true,
        components: [{ componentKey: 'profile-1', componentType: 'PROFILE' }]
      }
    })
    assert.equal(page.data.timelineGuideVisible, false)
  } finally {
    delete global.wx
  }
})

test('personal visitor ignores unknown timeline guide values and registers the shared guide', async () => {
  const page = loadVisitorPage(() => Promise.resolve({}))
  page.bootstrap = () => Promise.resolve()
  await page.onLoad({ shareCode: 'PF001', shareGuide: 'true' })

  const pageRoot = path.join(__dirname, '../pages/portfolios/visitor-portfolio')
  const json = JSON.parse(fs.readFileSync(path.join(pageRoot, 'visitor-portfolio.json'), 'utf8'))
  const wxml = fs.readFileSync(path.join(pageRoot, 'visitor-portfolio.wxml'), 'utf8')

  assert.equal(page.data.timelineGuideRequested, false)
  assert.equal(json.usingComponents['timeline-share-guide'], '/components/timeline-share-guide/timeline-share-guide')
  assert.match(wxml, /<timeline-share-guide[^>]*back="\{\{showNavigationBack\}\}"[^>]*bindback="handleTimelineGuideBack"[^>]*bindclose="handleCloseTimelineGuide"/)
})

test('visitor page shows navigation back only when the page stack has a previous page', async () => {
  const previousGetCurrentPages = global.getCurrentPages
  try {
    global.getCurrentPages = () => [{ route: 'pages/portfolios/portfolios' }, { route: 'pages/portfolios/visitor-portfolio/visitor-portfolio' }]
    const internalPage = loadVisitorPage(() => Promise.resolve({}))
    internalPage.bootstrap = () => Promise.resolve()
    await internalPage.onLoad({ shareCode: 'PF001' })
    assert.equal(internalPage.data.showNavigationBack, true)

    global.getCurrentPages = () => [{ route: 'pages/portfolios/visitor-portfolio/visitor-portfolio' }]
    const directSharePage = loadVisitorPage(() => Promise.resolve({}))
    directSharePage.bootstrap = () => Promise.resolve()
    await directSharePage.onLoad({ shareCode: 'PF001', fromTeamPortfolio: '1' })
    assert.equal(directSharePage.data.showNavigationBack, false)
  } finally {
    if (previousGetCurrentPages === undefined) delete global.getCurrentPages
    else global.getCurrentPages = previousGetCurrentPages
  }
})

test('visitor page records image view before opening original image', async () => {
  const requests = []
  const previews = []
  const wxMock = {
    previewImage(options) {
      previews.push(options)
    }
  }
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  }, wxMock)
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  global.wx = Object.assign({
    showToast() {}
  }, wxMock)

  try {
    const promise = page.handleWorkTap({
      currentTarget: {
        dataset: {
          workId: '11',
          mediaType: 'IMAGE',
          mediaUrl: 'https://cdn.example.com/original.jpg',
          coverUrl: 'https://cdn.example.com/thumb.jpg',
          title: '迎宾图'
        }
      }
    })
    await promise
  } finally {
    delete global.wx
  }

  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/events')
  assert.equal(requests[0].authMode, 'visitor')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.eventType, 'WORK_VIEWED')
  assert.equal(requests[0].data.workId, 11)
  assert.equal(requests[0].data.mediaType, 'IMAGE')
  assert.deepEqual(requests[0].data.metadata, {
    workTitle: '迎宾图'
  })
  assert.deepEqual(previews[0], {
    current: 'https://cdn.example.com/original.jpg',
    urls: ['https://cdn.example.com/original.jpg']
  })
})

test('visitor page records qr interaction when previewing contact qr', async () => {
  const requests = []
  const previews = []
  const wxMock = {
    previewImage(options) {
      previews.push(options)
    }
  }
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  }, wxMock)
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  global.wx = Object.assign({
    showToast() {}
  }, wxMock)

  try {
    await page.handlePreviewQr({
      currentTarget: {
        dataset: {
          url: 'https://cdn.example.com/contact-qr.jpg'
        }
      }
    })
    await flushPromises()
  } finally {
    delete global.wx
  }

  assert.deepEqual(previews[0], {
    current: 'https://cdn.example.com/contact-qr.jpg',
    urls: ['https://cdn.example.com/contact-qr.jpg']
  })
  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/events')
  assert.equal(requests[0].authMode, 'visitor')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.visitorKey, 'visitor-a')
  assert.equal(requests[0].data.eventType, 'QR_CODE_INTERACTED')
  assert.deepEqual(requests[0].data.metadata, {
    action: 'PREVIEW_QR'
  })
})

test('visitor page opens portfolio with wx login code and stores backend visitor key', async () => {
  const requests = []
  const storageWrites = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({
      visitorKey: 'server-visitor-key',
      token: 'wf-visitor-v1.server',
      expiresInSeconds: 60,
      isNewVisitor: false,
      renderData: {
        shareCode: 'PF001',
        title: '林安婚礼司仪',
        components: []
      }
    })
  }, {
    login(options) {
      options.success({ code: 'wx-code' })
    }
  })
  global.wx = {
    login(options) {
      options.success({ code: 'wx-code' })
    },
    setStorageSync(key, value) {
      storageWrites.push({ key, value })
    },
    showToast() {}
  }

  try {
    await page.onLoad({ shareCode: 'PF001' })
  } finally {
    delete global.wx
  }

  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/open')
  assert.equal(requests[0].authMode, 'none')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.loginCode, 'wx-code')
  assert.equal(Object.hasOwn(requests[0].data, 'visitorKey'), false)
  assert.equal(page.data.visitorKey, 'server-visitor-key')
  assert.deepEqual(storageWrites[0], {
    key: 'wefolio_visitor_token',
    value: 'wf-visitor-v1.server'
  })
  assert.equal(storageWrites[1].key, 'wefolio_visitor_token_expires_at')
  assert.equal(typeof storageWrites[1].value, 'number')
})

test('visitor page opens timeline single-page mode anonymously without wx login', async () => {
  const requests = []
  let loginCalls = 0
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({
      visitorKey: 'timeline-visitor-key',
      token: 'wf-visitor-v1.timeline',
      expiresInSeconds: 60,
      needVisitorProfile: false,
      renderData: {
        shareCode: 'PF001',
        title: '朋友圈作品集',
        components: []
      }
    })
  })
  global.wx = {
    getEnterOptionsSync() {
      return { scene: 1154 }
    },
    login() {
      loginCalls += 1
    },
    setStorageSync() {},
    showShareMenu() {},
    showToast() {}
  }

  try {
    await page.onLoad({ shareCode: 'PF001' })
  } finally {
    delete global.wx
  }

  assert.equal(loginCalls, 0)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/open')
  assert.equal(Object.hasOwn(requests[0].data, 'loginCode'), false)
  assert.match(requests[0].data.anonymousSessionId, /^timeline-/)
  assert.equal(page.data.visitorKey, 'timeline-visitor-key')
})

test('visitor page ignores visitor token storage failure while opening portfolio', async () => {
  const requests = []
  const toastMessages = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({
      visitorKey: 'server-visitor-key',
      token: 'wf-visitor-v1.server',
      expiresInSeconds: 60,
      renderData: {
        shareCode: 'PF001',
        title: '林安婚礼司仪',
        components: []
      }
    })
  }, {
    login(options) {
      options.success({ code: 'wx-code' })
    }
  })
  global.wx = {
    login(options) {
      options.success({ code: 'wx-code' })
    },
    setStorageSync() {
      throw new Error('storage full')
    },
    showToast(options) {
      toastMessages.push(options.title)
    }
  }

  try {
    await page.onLoad({ shareCode: 'PF001' })
  } finally {
    delete global.wx
  }

  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/open')
  assert.equal(page.data.visitorKey, 'server-visitor-key')
  assert.deepEqual(toastMessages, [])
})

test('visitor page refreshes visitor session with open endpoint and retries visitor request after 401', async () => {
  const requests = []
  const storageWrites = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    if (options.url === '/api/visitor/portfolios/PF001/open') {
      const openCount = requests.filter((requestOptions) => requestOptions.url === options.url).length
      return Promise.resolve({
        visitorKey: openCount === 1 ? 'visitor-initial' : 'visitor-refreshed',
        token: openCount === 1 ? 'wf-visitor-v1.initial' : 'wf-visitor-v1.refreshed',
        expiresInSeconds: 60,
        renderData: {
          shareCode: 'PF001',
          title: '林安婚礼司仪',
          components: []
        }
      })
    }
    if (options.url === '/api/visitor/portfolios/PF001/events') {
      const eventCount = requests.filter((requestOptions) => requestOptions.url === options.url).length
      if (eventCount === 1) {
        const error = new Error('访客未登录')
        error.authRequired = true
        error.authMode = 'visitor'
        return Promise.reject(error)
      }
      return Promise.resolve({ ok: true })
    }
    return Promise.reject(new Error(`unexpected request: ${options.url}`))
  }, {
    login(options) {
      options.success({ code: 'wx-code' })
    }
  })
  global.wx = {
    login(options) {
      options.success({ code: 'wx-code' })
    },
    setStorageSync(key, value) {
      storageWrites.push({ key, value })
    },
    showToast() {}
  }

  try {
    await page.onLoad({ shareCode: 'PF001' })
    await page.recordWorkEvent({
      mediaType: 'IMAGE',
      workId: 301,
      title: '迎宾照'
    })
  } finally {
    delete global.wx
  }

  assert.deepEqual(requests.map((options) => options.url), [
    '/api/visitor/portfolios/PF001/open',
    '/api/visitor/portfolios/PF001/events',
    '/api/visitor/portfolios/PF001/open',
    '/api/visitor/portfolios/PF001/events'
  ])
  assert.equal(requests[2].authMode, 'none')
  assert.equal(requests[3].authMode, 'visitor')
  assert.equal(page.data.visitorKey, 'visitor-refreshed')
  assert.equal(storageWrites.at(-2).key, 'wefolio_visitor_token')
  assert.equal(storageWrites.at(-2).value, 'wf-visitor-v1.refreshed')
})

test('timeline single-page refresh reuses anonymous session id without wx login', async () => {
  const requests = []
  let loginCalls = 0
  const page = loadVisitorPage((options) => {
    requests.push(options)
    if (options.url.endsWith('/open')) {
      return Promise.resolve({
        visitorKey: 'timeline-visitor',
        token: 'wf-visitor-timeline-v1.test',
        expiresInSeconds: 60,
        needVisitorProfile: false,
        renderData: { shareCode: 'PF001', title: '朋友圈作品集', components: [] }
      })
    }
    if (options.url.endsWith('/events')) {
      const eventCalls = requests.filter((item) => item.url.endsWith('/events')).length
      if (eventCalls === 1) {
        return Promise.reject(Object.assign(new Error('访客未登录'), {
          authRequired: true,
          authMode: 'visitor'
        }))
      }
      return Promise.resolve({ ok: true })
    }
    return Promise.reject(new Error(`unexpected request: ${options.url}`))
  })
  global.wx = {
    getEnterOptionsSync() { return { scene: 1154 } },
    login() { loginCalls += 1 },
    setStorageSync() {},
    showToast() {}
  }

  try {
    await page.onLoad({ shareCode: 'PF001' })
    await page.recordWorkEvent({ mediaType: 'IMAGE', workId: 301, title: '迎宾照' })
  } finally {
    delete global.wx
  }

  const openRequests = requests.filter((item) => item.url.endsWith('/open'))
  assert.equal(openRequests.length, 2)
  assert.equal(loginCalls, 0)
  assert.equal(openRequests[0].data.anonymousSessionId, openRequests[1].data.anonymousSessionId)
  assert.equal(Object.hasOwn(openRequests[1].data, 'loginCode'), false)
})

test('visitor page shows profile authorization panel when profile is missing', async () => {
  const page = loadVisitorPage(() => Promise.resolve({
    visitorKey: 'server-visitor-key',
    isNewVisitor: false,
    needVisitorProfile: true,
    visitorProfileToken: 'profile-token-1',
    renderData: {
      shareCode: 'PF001',
      title: '林安婚礼司仪',
      components: []
    }
  }), {
    login(options) {
      options.success({ code: 'wx-code' })
    }
  })
  global.wx = {
    login(options) {
      options.success({ code: 'wx-code' })
    },
    showToast() {}
  }

  try {
    await page.onLoad({ shareCode: 'PF001' })
  } finally {
    delete global.wx
  }

  assert.equal(page.data.visitorProfileAuthVisible, true)
  assert.equal(page.data.visitorProfileToken, 'profile-token-1')
})

test('visitor profile prompt keeps skip and save copy in bottom sheet', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxss'), 'utf8')

  assert.match(wxml, /<root-portal wx:if="\{\{visitorProfileAuthVisible\}\}">/)
  assert.match(wxml, /class="visitor-profile-mask"[^>]*catchtap="handleVisitorProfileMaskTap"[^>]*catchtouchmove="handleVisitorProfileMaskTouchMove"/)
  assert.match(wxml, /class="visitor-profile-panel"[^>]*catchtap="handleVisitorProfilePanelTap"/)
  assert.match(wxml, /class="visitor-profile-desc"[^>]*>选择头像并填写昵称，维护者查看访客记录时能识别你。<\/view>/)
  assert.match(wxml, /class="visitor-avatar-visual/)
  assert.match(wxml, /class="visitor-avatar-label[^>]*>点击选择头像<\/view>/)
  assert.match(wxml, /type="nickname"/)
  assert.match(wxml, /placeholder="输入昵称或选择微信昵称"/)
  assert.match(wxml, /visitorProfileAvatarError[^>]*visitorProfileValidationShaking/)
  assert.match(wxml, /visitorProfileNicknameError[^>]*visitorProfileValidationShaking/)
  assert.match(wxml, /wx:if="\{\{visitorProfileAvatarError\}\}"[^>]*>请点击选择头像<\/view>/)
  assert.match(wxml, /wx:if="\{\{visitorProfileNicknameError\}\}"[^>]*>请点击输入或选择昵称<\/view>/)
  assert.match(wxml, /class="visitor-profile-secondary"[^>]*>跳过<\/button>/)
  assert.match(wxml, /class="visitor-profile-primary"[^>]*>保存<\/button>/)
  assert.match(wxss, /\.visitor-profile-mask\s*\{[\s\S]*left:\s*0;[\s\S]*right:\s*0;[\s\S]*top:\s*0;[\s\S]*bottom:\s*0;[\s\S]*z-index:\s*120;/)
  assert.match(wxss, /\.visitor-profile-panel\s*\{[\s\S]*position:\s*absolute;[\s\S]*left:\s*0;[\s\S]*right:\s*0;[\s\S]*bottom:\s*0;[\s\S]*border-radius:\s*28rpx 28rpx 0 0;/)
  assert.match(wxss, /\.visitor-avatar-picker\s*\{[\s\S]*position:\s*absolute;[\s\S]*left:\s*0;[\s\S]*top:\s*0;[\s\S]*width:\s*116rpx;[\s\S]*height:\s*116rpx;[\s\S]*opacity:\s*0;/)
  assert.match(wxss, /\.visitor-avatar-visual\s*\{[\s\S]*width:\s*116rpx;[\s\S]*height:\s*116rpx;[\s\S]*border-radius:\s*50%;[\s\S]*overflow:\s*hidden;/)
  assert.match(wxss, /\.visitor-avatar-visual\.invalid/)
  assert.match(wxss, /\.visitor-nickname-input\.invalid/)
  assert.match(wxss, /\.visitor-profile-error\s*\{/)
  assert.match(wxss, /\.visitor-avatar-field\.shake[\s\S]*\.visitor-nickname-field\.shake/)
  assert.match(wxss, /@keyframes visitor-profile-shake/)
})

test('visitor profile submit requires both avatar and nickname before upload', async () => {
  const toasts = []
  const requests = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })
  global.wx = {
    showToast(options) {
      toasts.push(options)
    }
  }

  try {
    page.data.visitorProfileForm = {
      avatarUrl: '',
      nickname: ''
    }
    await page.handleVisitorProfileSubmit()
    assert.equal(toasts.at(-1).title, '请完善头像和昵称')

    page.data.visitorProfileForm = {
      avatarUrl: 'wxfile://avatar.jpg',
      nickname: ''
    }
    await page.handleVisitorProfileSubmit()
    assert.equal(toasts.at(-1).title, '请完善头像和昵称')
  } finally {
    delete global.wx
  }

  assert.equal(requests.length, 0)
})

test('visitor profile submit marks each missing field and restarts validation shake', async () => {
  const page = loadVisitorPage(() => Promise.resolve({}))
  const shakeValues = []
  const originalSetData = page.setData.bind(page)
  page.setData = (patch, callback) => {
    if (Object.prototype.hasOwnProperty.call(patch, 'visitorProfileValidationShaking')) {
      shakeValues.push(patch.visitorProfileValidationShaking)
    }
    originalSetData(patch, callback)
  }
  global.wx = { showToast() {} }

  try {
    page.data.visitorProfileForm = { avatarUrl: '', nickname: '' }
    await page.handleVisitorProfileSubmit()
    assert.equal(page.data.visitorProfileAvatarError, true)
    assert.equal(page.data.visitorProfileNicknameError, true)

    await page.handleVisitorProfileSubmit()
    assert.deepEqual(shakeValues, [false, true, false, true])

    page.data.visitorProfileForm = { avatarUrl: 'wxfile://avatar.jpg', nickname: '' }
    await page.handleVisitorProfileSubmit()
    assert.equal(page.data.visitorProfileAvatarError, false)
    assert.equal(page.data.visitorProfileNicknameError, true)

    page.data.visitorProfileForm = { avatarUrl: '', nickname: '访客' }
    await page.handleVisitorProfileSubmit()
    assert.equal(page.data.visitorProfileAvatarError, true)
    assert.equal(page.data.visitorProfileNicknameError, false)
  } finally {
    delete global.wx
  }
})

test('visitor profile fields clear only their resolved validation error', () => {
  const page = loadVisitorPage(() => Promise.resolve({}))
  page.data.visitorProfileAvatarError = true
  page.data.visitorProfileNicknameError = true

  page.handleVisitorAvatarChoose({ detail: { avatarUrl: 'wxfile://avatar.jpg' } })
  assert.equal(page.data.visitorProfileAvatarError, false)
  assert.equal(page.data.visitorProfileNicknameError, true)

  page.handleVisitorNicknameInput({ detail: { value: '   ' } })
  assert.equal(page.data.visitorProfileNicknameError, true)

  page.handleVisitorNicknameInput({ detail: { value: ' 访客 ' } })
  assert.equal(page.data.visitorProfileNicknameError, false)
})

test('visitor video preview uses root portal so native video overlay covers viewport', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxss'), 'utf8')
  const portalStart = wxml.indexOf('<root-portal wx:if="{{videoPreviewVisible}}">')
  const maskStart = wxml.indexOf('class="work-video-mask {{videoPreviewVisible ? \'visible\' : \'\'}}"')
  const portalEnd = wxml.indexOf('</root-portal>', portalStart)
  const maskRuleStart = wxss.indexOf('.work-video-mask {')
  const maskRuleEnd = wxss.indexOf('}', maskRuleStart)
  const maskRule = wxss.slice(maskRuleStart, maskRuleEnd)

  assert.notEqual(portalStart, -1)
  assert.notEqual(maskStart, -1)
  assert.notEqual(maskRuleStart, -1)
  assert.ok(maskStart > portalStart)
  assert.ok(maskStart < portalEnd)
  assert.match(maskRule, /left:\s*0;[\s\S]*right:\s*0;[\s\S]*top:\s*0;[\s\S]*bottom:\s*0;/)
  assert.doesNotMatch(maskRule, /inset:\s*0;/)
})

test('visitor page records video play before showing video overlay', async () => {
  const requests = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  global.wx = { showToast() {} }

  try {
    await page.handleWorkTap({
      currentTarget: {
        dataset: {
          workId: '12',
          mediaType: 'VIDEO',
          mediaUrl: 'https://cdn.example.com/movie.mp4',
          coverUrl: 'https://cdn.example.com/movie.jpg',
          title: '婚礼快剪'
        }
      }
    })
    await flushPromises()
  } finally {
    delete global.wx
  }

  assert.equal(requests[0].data.eventType, 'VIDEO_PLAYED')
  assert.equal(requests[0].authMode, 'visitor')
  assert.equal(requests[0].data.mediaType, 'VIDEO')
  assert.deepEqual(requests[0].data.metadata, {
    workTitle: '婚礼快剪'
  })
  assert.equal(page.data.videoPreviewVisible, true)
  assert.deepEqual(page.data.videoPreview, {
    src: 'https://cdn.example.com/movie.mp4',
    poster: 'https://cdn.example.com/movie.jpg',
    title: '婚礼快剪'
  })

  page.handleCloseVideoPreview()
  assert.equal(page.data.videoPreviewVisible, false)
  assert.equal(page.data.videoPreview, null)
})

test('visitor singular work records event before opening image or starting inline video', async () => {
  const requests = []
  const previews = []
  const deferred = createDeferred()
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return deferred.promise
  }, {
    previewImage(options) {
      previews.push(options)
    },
    createVideoContext() {
      return { pause() {} }
    }
  })
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  global.wx = {
    previewImage(options) {
      previews.push(options)
    },
    showToast() {},
    createVideoContext() {
      return { pause() {} }
    }
  }

  try {
    const videoPromise = page.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_video',
          workId: '18',
          mediaType: 'VIDEO',
          mediaUrl: 'https://cdn.example.com/movie.mp4',
          coverUrl: 'https://cdn.example.com/movie.jpg',
          title: '婚礼快剪'
        }
      }
    })

    assert.equal(page.data.activeSingleWorkVideoKey, '')
    assert.equal(page.data.videoPreviewVisible, false)
    assert.equal(requests[0].data.eventType, 'VIDEO_PLAYED')

    deferred.resolve({})
    assert.equal(await videoPromise, true)
    assert.equal(page.data.activeSingleWorkVideoKey, 'c_video')
    assert.equal(page.data.videoPreviewVisible, false)

    page.requestWithVisitorRefresh = (options) => {
      requests.push(options)
      return Promise.resolve({})
    }
    assert.equal(await page.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_image',
          workId: '19',
          mediaType: 'IMAGE',
          mediaUrl: 'https://cdn.example.com/original.jpg',
          title: '迎宾图'
        }
      }
    }), true)
  } finally {
    delete global.wx
  }

  assert.equal(requests[1].data.eventType, 'WORK_VIEWED')
  assert.deepEqual(previews[0], {
    current: 'https://cdn.example.com/original.jpg',
    urls: ['https://cdn.example.com/original.jpg']
  })
})

test('visitor page clears active single work state through the matching child component', () => {
  const page = loadVisitorPage(() => Promise.resolve({}))
  const calls = []
  page.data.activeSingleWorkVideoKey = 'single/active'
  page.selectAllComponents = (selector) => {
    assert.equal(selector, '.portfolio-single-work-instance')
    return [
      {
        data: { componentKey: 'single/active' },
        pauseVideo() {
          calls.push('active')
        }
      },
      {
        data: { componentKey: 'single/other' },
        pauseVideo() {
          calls.push('other')
        }
      }
    ]
  }

  page.stopActiveSingleWorkVideo()

  assert.deepEqual(calls, ['active'])
  assert.equal(page.data.activeSingleWorkVideoKey, '')
})

test('visitor singular work keeps poster state when event recording fails', async () => {
  const toasts = []
  const page = loadVisitorPage(() => Promise.reject(new Error('埋点失败')))
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  global.wx = {
    showToast(options) {
      toasts.push(options)
    }
  }

  try {
    assert.equal(await page.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_video',
          workId: '18',
          mediaType: 'VIDEO',
          mediaUrl: 'movie.mp4'
        }
      }
    }), false)
  } finally {
    delete global.wx
  }

  assert.equal(page.data.activeSingleWorkVideoKey, '')
  assert.equal(toasts[0].title, '埋点失败')
})

test('visitor singular work blocks missing original media before recording events', async () => {
  const requests = []
  const previews = []
  const toasts = []
  const page = loadVisitorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  global.wx = {
    previewImage(options) {
      previews.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  }

  try {
    assert.equal(await page.handleSingleWorkTap({
      currentTarget: { dataset: {
        componentKey: 'c_video',
        mediaType: 'VIDEO',
        mediaUrl: '',
        coverUrl: 'video-cover.jpg'
      } }
    }), false)
    assert.equal(await page.handleSingleWorkTap({
      currentTarget: { dataset: {
        componentKey: 'c_image',
        mediaType: 'IMAGE',
        mediaUrl: '',
        coverUrl: 'image-thumb.jpg'
      } }
    }), false)
  } finally {
    delete global.wx
  }

  assert.deepEqual(toasts.map((item) => item.title), ['视频地址缺失', '图片地址缺失'])
  assert.equal(requests.length, 0)
  assert.equal(previews.length, 0)
})

test('visitor singular work ignores stale or hidden event completions', async () => {
  const deferredA = createDeferred()
  const deferredB = createDeferred()
  const deferredHidden = createDeferred()
  const requests = [deferredA, deferredB, deferredHidden]
  const paused = []
  const page = loadVisitorPage(() => requests.shift().promise)
  page.data.shareCode = 'PF001'
  page.data.visitorKey = 'visitor-a'
  page.selectAllComponents = () => [
    {
      data: { componentKey: 'c_b' },
      pauseVideo() {
        paused.push('c_b')
      }
    }
  ]
  global.wx = {
    showToast() {}
  }

  const tapVideo = (componentKey, workId) => page.handleSingleWorkTap({
    currentTarget: {
      dataset: {
        componentKey,
        workId,
        mediaType: 'VIDEO',
        mediaUrl: `${componentKey}.mp4`
      }
    }
  })

  try {
    const promiseA = tapVideo('c_a', 31)
    const promiseB = tapVideo('c_b', 32)
    deferredB.resolve({})
    assert.equal(await promiseB, true)
    assert.equal(page.data.activeSingleWorkVideoKey, 'c_b')

    deferredA.resolve({})
    assert.equal(await promiseA, false)
    assert.equal(page.data.activeSingleWorkVideoKey, 'c_b')

    const hiddenPromise = tapVideo('c_hidden', 33)
    page.onHide()
    deferredHidden.resolve({})
    assert.equal(await hiddenPromise, false)
    assert.equal(page.data.activeSingleWorkVideoKey, '')
  } finally {
    delete global.wx
  }

  assert.deepEqual(paused, ['c_b'])
})

test('visitor singular work renders original image and inline video without changing list overlay', () => {
  const pageWxml = fs.readFileSync(path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'), 'utf8')
  const componentWxml = fs.readFileSync(path.join(__dirname, '../pages/portfolios/components/single-work/single-work.wxml'), 'utf8')

  assert.match(pageWxml, /item\.componentType === 'SINGLE_WORK'/)
  assert.match(pageWxml, /<portfolio-single-work[\s\S]*active-video-key="\{\{activeSingleWorkVideoKey\}\}"/)
  assert.match(componentWxml, /class="single-work-image"[\s\S]*mode="widthFix"/)
  assert.match(componentWxml, /id="singleWorkVideo-\{\{componentKey\}\}"/)
  assert.match(componentWxml, /activeVideoKey === componentKey/)
  assert.match(pageWxml, /<root-portal wx:if="\{\{videoPreviewVisible\}\}">/)
})

test('normalizes visitor schedule without internal fields', () => {
  const result = normalizeVisitorSchedule({
    schedules: [
      {
        date: '2026-07-18',
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        statusText: '待定',
        contactName: '内部客户',
        note: '内部备注'
      }
    ]
  })

  assert.equal(result.schedules[0].slotName, '午宴')
  assert.equal(result.schedules[0].contactName, '')
  assert.equal(result.schedules[0].note, '')
})

test('normalizes visitor schedule options without internal fields', () => {
  const result = normalizeVisitorScheduleOptions({
    yearMonth: '2026-07',
    slotDefinitions: [
      { id: 12, name: '午宴', startTime: '10:00', endTime: '14:00', color: '#2d5f9a' }
    ],
    days: [
      { date: '2026-06-19', dayNumber: 19, currentMonth: false, colors: [], count: 0 },
      { date: '2026-07-18', dayNumber: 18, currentMonth: true, colors: ['#2d5f9a'], count: 1, holidayText: '宜嫁娶' }
    ],
    schedules: [
      {
        date: '2026-07-18',
        slotDefinitionId: 12,
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        color: '#2d5f9a',
        status: 'TENTATIVE',
        statusText: '待定',
        contactName: '内部客户',
        note: '内部备注'
      }
    ]
  })

  assert.equal(result.yearMonth, '2026-07')
  assert.equal(result.slotDefinitions[0].timeRangeText, '10:00-14:00')
  assert.equal(result.days[0].metaText, '端午节')
  assert.equal(result.days[1].metaText, '宜嫁娶')
  assert.equal(result.days[1].dayClass, 'schedule-calendar-day filled')
  assert.equal(result.schedules[0].slotDefinitionId, 12)
  assert.equal(result.schedules[0].contactName, '')
  assert.equal(result.schedules[0].note, '')
})

test('normalizes visitor schedule query result', () => {
  const result = normalizeVisitorScheduleQueryResult({
    queriedDate: '2026-07-18',
    slotDefinitionId: 12,
    slotName: '午宴',
    startTime: '10:00',
    endTime: '14:00',
    status: 'BOOKED',
    statusText: '已约',
    available: false,
    message: '该档期已约',
    contactPhone: '13800138000'
  })

  assert.equal(result.slotDefinitionId, 12)
  assert.equal(result.timeRangeText, '10:00-14:00')
  assert.equal(result.available, false)
  assert.equal(result.message, '该档期已约')
  assert.equal(result.contactPhone, '')
})

test('portfolio schedule query component uses visitor endpoints and payload', async () => {
  const requests = []
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    if (options.method === 'POST') {
      return Promise.resolve({
        queriedDate: '2026-07-18',
        slotDefinitionId: 12,
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        available: true,
        message: '档期空闲'
      })
    }
    return Promise.resolve({
      yearMonth: '2026-07',
      slotDefinitions: [
        { id: 12, name: '午宴', startTime: '10:00', endTime: '14:00', color: '#2d5f9a' }
      ],
      days: [],
      schedules: []
    })
  })
  component.setData({
    shareCode: 'PF001',
    visitorKey: 'visitor-a',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  await component.loadScheduleOptions('2026-07')
  component.setData({
    selectedDate: '2026-07-18',
    selectedSlotDefinitionId: 12
  })
  await component.handleSubmitQuery()

  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/schedule-options')
  assert.equal(requests[0].authMode, 'visitor')
  assert.deepEqual(requests[0].data, { month: '2026-07', componentKey: 'c_schedule' })
  assert.equal(requests[1].url, '/api/visitor/portfolios/PF001/schedule-query')
  assert.equal(requests[1].authMode, 'visitor')
  assert.equal(requests[1].method, 'POST')
  assert.equal(requests[1].data.visitorKey, 'visitor-a')
  assert.equal(requests[1].data.componentKey, 'c_schedule')
  assert.equal(requests[1].data.queriedDate, '2026-07-18')
  assert.equal(requests[1].data.slotDefinitionId, 12)
  assert.match(requests[1].data.idempotencyKey, /^schedule-query-c_schedule-2026-07-18-12-/)
  assert.equal(component.data.result.message, '档期空闲')
})

test('portfolio schedule query component refreshes visitor session and retries after visitor 401', async () => {
  const requests = []
  const previousWx = global.wx
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    if (options.url === '/api/visitor/portfolios/PF001/open') {
      return Promise.resolve({
        visitorKey: 'visitor-refreshed',
        token: 'wf-visitor-v1.refreshed',
        expiresInSeconds: 60,
        renderData: {
          shareCode: 'PF001',
          components: []
        }
      })
    }
    if (options.url === '/api/visitor/portfolios/PF001/schedule-options') {
      const optionsCount = requests.filter((requestOptions) => requestOptions.url === options.url).length
      if (optionsCount === 1) {
        const error = new Error('访客未登录')
        error.authRequired = true
        error.authMode = 'visitor'
        return Promise.reject(error)
      }
      return Promise.resolve({
        yearMonth: '2026-07',
        slotDefinitions: [],
        days: [],
        schedules: []
      })
    }
    return Promise.reject(new Error(`unexpected request: ${options.url}`))
  }, {
    login(options) {
      options.success({ code: 'wx-code' })
    },
    setStorageSync() {},
    showToast() {}
  })
  component.setData({
    shareCode: 'PF001',
    visitorKey: 'visitor-a',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  global.wx = {
    login(options) {
      options.success({ code: 'wx-code' })
    },
    setStorageSync() {},
    showToast() {}
  }
  try {
    await component.loadScheduleOptions('2026-07')
  } finally {
    if (previousWx === undefined) {
      delete global.wx
    } else {
      global.wx = previousWx
    }
  }

  assert.deepEqual(requests.map((options) => options.url), [
    '/api/visitor/portfolios/PF001/schedule-options',
    '/api/visitor/portfolios/PF001/open',
    '/api/visitor/portfolios/PF001/schedule-options'
  ])
  assert.equal(requests[1].authMode, 'none')
  assert.equal(requests[2].authMode, 'visitor')
  assert.equal(component.data.errorMessage, '')
  assert.equal(component.data.options.yearMonth, '2026-07')
})

test('portfolio schedule query component hides visitor month schedule marks before submit', async () => {
  const component = loadScheduleQueryComponent(() => Promise.resolve({
    yearMonth: '2026-07',
    slotDefinitions: [
      { id: 12, name: '午宴', startTime: '10:00', endTime: '14:00', color: '#2d5f9a' }
    ],
    days: [
      { date: '2026-07-18', dayNumber: 18, currentMonth: true, colors: ['#2d5f9a'], count: 1 }
    ],
    schedules: [
      {
        date: '2026-07-18',
        slotDefinitionId: 12,
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        color: '#2d5f9a',
        status: 'BOOKED',
        statusText: '已约'
      }
    ]
  }))
  component.setData({
    shareCode: 'PF001',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  await component.loadScheduleOptions('2026-07')
  component.handleDayTap({ currentTarget: { dataset: { date: '2026-07-18' } } })

  const day = component.data.options.days[0]
  assert.equal(day.dayClass, 'schedule-calendar-day')
  assert.deepEqual(day.colors, [])
  assert.equal(day.count, 0)
  assert.deepEqual(component.data.options.schedules, [])
  assert.deepEqual(component.data.selectedDaySchedules, [])
})

test('portfolio schedule query component keeps preview month schedule marks', async () => {
  const component = loadScheduleQueryComponent(() => Promise.resolve({
    yearMonth: '2026-07',
    slotDefinitions: [
      { id: 12, name: '午宴', startTime: '10:00', endTime: '14:00', color: '#2d5f9a' }
    ],
    days: [
      { date: '2026-07-18', dayNumber: 18, currentMonth: true, colors: ['#2d5f9a'], count: 1 }
    ],
    schedules: [
      {
        date: '2026-07-18',
        slotDefinitionId: 12,
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        color: '#2d5f9a',
        status: 'BOOKED',
        statusText: '已约'
      }
    ]
  }))
  component.setData({
    preview: true,
    portfolioId: 88,
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  await component.loadScheduleOptions('2026-07')
  component.handleDayTap({ currentTarget: { dataset: { date: '2026-07-18' } } })

  const day = component.data.options.days[0]
  assert.equal(day.dayClass, 'schedule-calendar-day filled')
  assert.deepEqual(day.colors, ['#2d5f9a'])
  assert.equal(day.count, 1)
  assert.equal(component.data.selectedDaySchedules[0].statusText, '已约')
})

test('portfolio schedule query component ignores month switching while loading', async () => {
  const requests = []
  const pendingOptions = createDeferred()
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    return pendingOptions.promise
  })
  component.setData({
    shareCode: 'PF001',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  const firstLoad = component.loadScheduleOptions('2026-07')
  const secondLoad = component.handleNextMonth()

  assert.equal(requests.length, 1)
  assert.equal(requests[0].data.month, '2026-07')
  pendingOptions.resolve({
    yearMonth: '2026-07',
    slotDefinitions: [],
    days: [],
    schedules: []
  })
  await firstLoad
  await Promise.resolve(secondLoad)
  assert.equal(component.data.selectedMonth, '2026-07')
})

test('portfolio schedule query component loads picker selected month', async () => {
  const requests = []
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    return Promise.resolve({
      yearMonth: options.data.month,
      slotDefinitions: [],
      days: [],
      schedules: []
    })
  })
  component.setData({
    shareCode: 'PF001',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  await component.handleMonthPickerChange({
    detail: { value: '2026-09' }
  })

  assert.equal(requests.length, 1)
  assert.equal(requests[0].data.month, '2026-09')
  assert.equal(component.data.selectedMonth, '2026-09')
})

test('portfolio schedule query component creates a fresh idempotency key for each submit', async () => {
  const requests = []
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    return Promise.resolve({
      queriedDate: '2026-07-18',
      slotDefinitionId: 12,
      slotName: '午宴',
      startTime: '10:00',
      endTime: '14:00',
      available: true,
      message: '档期空闲'
    })
  })
  component.setData({
    shareCode: 'PF001',
    visitorKey: 'visitor-a',
    componentKey: 'c_schedule',
    selectedDate: '2026-07-18',
    selectedSlotDefinitionId: 12,
    scheduleQuery: { displayMode: 'MODAL_CALENDAR' }
  })

  await component.handleSubmitQuery()
  await component.handleSubmitQuery()

  assert.equal(requests.length, 2)
  assert.match(requests[0].data.idempotencyKey, /^schedule-query-c_schedule-2026-07-18-12-/)
  assert.match(requests[1].data.idempotencyKey, /^schedule-query-c_schedule-2026-07-18-12-/)
  assert.notEqual(requests[1].data.idempotencyKey, requests[0].data.idempotencyKey)
})

test('portfolio schedule query component uses preview endpoints and scope', async () => {
  const requests = []
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    if (options.method === 'POST') {
      return Promise.resolve({
        queriedDate: '2026-07-18',
        slotDefinitionId: 12,
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        available: false,
        message: '该档期已约'
      })
    }
    return Promise.resolve({
      yearMonth: '2026-07',
      slotDefinitions: [
        { id: 12, name: '午宴', startTime: '10:00', endTime: '14:00', color: '#2d5f9a' }
      ],
      days: [],
      schedules: []
    })
  })
  component.setData({
    preview: true,
    portfolioId: 88,
    previewScope: 'published',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'INLINE_CALENDAR' }
  })

  await component.loadScheduleOptions('2026-07')
  component.setData({
    selectedDate: '2026-07-18',
    selectedSlotDefinitionId: 12
  })
  await component.handleSubmitQuery()

  assert.equal(requests[0].url, '/api/mine/portfolios/88/schedule-options')
  assert.equal(requests[0].authMode, undefined)
  assert.deepEqual(requests[0].data, { month: '2026-07', componentKey: 'c_schedule', scope: 'published' })
  assert.equal(requests[1].url, '/api/mine/portfolios/88/schedule-query-preview?scope=published')
  assert.equal(requests[1].authMode, undefined)
  assert.equal(requests[1].method, 'POST')
  assert.equal(requests[1].data.componentKey, 'c_schedule')
  assert.equal(requests[1].data.queriedDate, '2026-07-18')
  assert.equal(requests[1].data.slotDefinitionId, 12)
  assert.equal(Object.hasOwn(requests[1].data, 'visitorKey'), false)
  assert.equal(component.data.result.available, false)
})

test('portfolio schedule query component uses nested team member preview endpoints', async () => {
  const requests = []
  const component = loadScheduleQueryComponent((options) => {
    requests.push(options)
    if (options.method === 'POST') {
      return Promise.resolve({
        queriedDate: '2026-07-18',
        slotDefinitionId: 12,
        slotName: '午宴',
        startTime: '10:00',
        endTime: '14:00',
        available: true,
        message: '档期空闲'
      })
    }
    return Promise.resolve({
      yearMonth: '2026-07',
      slotDefinitions: [{ id: 12, name: '午宴', startTime: '10:00', endTime: '14:00' }],
      days: [],
      schedules: []
    })
  })
  component.setData({
    preview: true,
    portfolioId: 88,
    teamPortfolioId: 13,
    teamPreviewScope: 'draft',
    componentKey: 'c_schedule',
    scheduleQuery: { displayMode: 'INLINE_CALENDAR' }
  })

  await component.loadScheduleOptions('2026-07')
  component.setData({ selectedDate: '2026-07-18', selectedSlotDefinitionId: 12 })
  await component.handleSubmitQuery()

  assert.equal(requests[0].url, '/api/mine/team-portfolios/13/member-portfolios/88/schedule-options')
  assert.deepEqual(requests[0].data, {
    month: '2026-07',
    componentKey: 'c_schedule',
    scope: 'draft'
  })
  assert.equal(requests[0].authMode, undefined)
  assert.equal(requests[1].url, '/api/mine/team-portfolios/13/member-portfolios/88/schedule-query-preview?scope=draft')
  assert.equal(requests[1].method, 'POST')
  assert.equal(requests[1].authMode, undefined)
  assert.equal(Object.hasOwn(requests[1].data, 'visitorKey'), false)
})
