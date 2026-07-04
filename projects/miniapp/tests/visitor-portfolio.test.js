const assert = require('node:assert/strict')
const test = require('node:test')
const path = require('node:path')

const {
  buildVisitorEventPayload,
  normalizeVisitorPortfolio,
  normalizeVisitorSchedule,
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

function loadVisitorPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const requestCacheKey = require.resolve(requestPath)
  const originalRequestCache = require.cache[requestCacheKey]
  delete require.cache[require.resolve(pagePath)]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
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

test('builds visitor event payload with idempotency key', () => {
  const payload = buildVisitorEventPayload({
    visitorKey: 'visitor-a',
    eventType: 'VIDEO_PLAYED',
    workId: 11,
    mediaType: 'VIDEO',
    durationSeconds: 18
  }, 'event-1')

  assert.deepEqual(payload, {
    visitorKey: 'visitor-a',
    eventType: 'VIDEO_PLAYED',
    workId: 11,
    mediaType: 'VIDEO',
    durationSeconds: 18,
    idempotencyKey: 'event-1'
  })
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
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.eventType, 'WORK_VIEWED')
  assert.equal(requests[0].data.workId, 11)
  assert.equal(requests[0].data.mediaType, 'IMAGE')
  assert.deepEqual(previews[0], {
    current: 'https://cdn.example.com/original.jpg',
    urls: ['https://cdn.example.com/original.jpg']
  })
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
  assert.equal(requests[0].data.mediaType, 'VIDEO')
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
