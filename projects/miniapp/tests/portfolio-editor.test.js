const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const {
  COMPONENT_TYPES,
  createComponent,
  normalizePortfolioConfig
} = require('../utils/portfolios')

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    target[key] = patch[key]
  })
}

function createSelectorQuery(rects) {
  const query = {
    in() {
      return query
    },
    selectAll() {
      return query
    },
    boundingClientRect(callback) {
      callback(rects)
      return query
    },
    exec() {}
  }
  return query
}

function loadPortfolioEditorPage(fakeRequest, wxOverrides = {}, coverOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolio-standard-edit/portfolio-standard-edit.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const coverPath = path.join(__dirname, '../utils/portfolio-cover.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const coverCacheKey = coverPath
  const originalRequestCache = require.cache[requestCacheKey]
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalCoverCache = require.cache[coverCacheKey]

  delete require.cache[require.resolve(pagePath)]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
    }
  }
  require.cache[sessionCacheKey] = {
    id: sessionPath,
    filename: sessionPath,
    loaded: true,
    exports: {
      handleAuthRequired() {},
      hasLocalToken() {
        return true
      }
    }
  }
  require.cache[coverCacheKey] = {
    id: coverPath,
    filename: coverPath,
    loaded: true,
    exports: Object.assign({
      createChoosePortfolioCoverOptions() {
        return { count: 1, mediaType: ['image'], sourceType: ['album'] }
      },
      uploadPortfolioCover(portfolioId, filePath) {
        return Promise.resolve(filePath)
      }
    }, coverOverrides)
  }

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    createSelectorQuery() {
      return createSelectorQuery([
        { top: 0, height: 100 },
        { top: 100, height: 100 },
        { top: 200, height: 100 }
      ])
    },
    navigateTo() {},
    navigateBack() {},
    redirectTo() {},
    showToast() {}
  }, wxOverrides)

  require(pagePath)
  delete global.Page
  if (originalRequestCache) {
    require.cache[requestCacheKey] = originalRequestCache
  } else {
    delete require.cache[requestCacheKey]
  }
  if (originalSessionCache) {
    require.cache[sessionCacheKey] = originalSessionCache
  } else {
    delete require.cache[sessionCacheKey]
  }
  if (originalCoverCache) {
    require.cache[coverCacheKey] = originalCoverCache
  } else {
    delete require.cache[coverCacheKey]
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

function touchEvent(dataset, point) {
  return {
    currentTarget: { dataset },
    touches: [point],
    changedTouches: [point]
  }
}

test('standard portfolio component row reveals delete only after left swipe', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))

  page.handleComponentTouchStart(touchEvent({ key: 'c_carousel', index: 1 }, { clientX: 220, clientY: 40 }))
  page.handleComponentTouchEnd(touchEvent({ key: 'c_carousel', index: 1 }, { clientX: 150, clientY: 44 }))

  assert.equal(page.data.revealedComponentKey, 'c_carousel')

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_carousel', type: COMPONENT_TYPES.CAROUSEL } } })

  assert.equal(page.data.revealedComponentKey, '')
  assert.equal(page.data.componentWorkSheetVisible, false)
})

test('standard portfolio component drag moves row with animated style before reordering', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 }),
      createComponent(COMPONENT_TYPES.CAROUSEL, { componentKey: 'c_carousel', sortOrder: 2000 }),
      createComponent(COMPONENT_TYPES.QR_CONTACT, { componentKey: 'c_qr', sortOrder: 3000 })
    ]
  })

  page.handleComponentDragStart(touchEvent({ key: 'c_profile', index: 0 }, { clientY: 100 }))
  page.handleComponentTouchMove(touchEvent({ key: 'c_profile', index: 0 }, { clientY: 130 }))

  assert.equal(page.data.dragTargetIndex, 1)
  assert.match(page.data.componentDragStyle, /translate3d\(0, 30px, 0\)/)
  assert.match(page.data.componentDragStyle, /transition: transform 80ms linear/)

  page.handleComponentTouchEnd(touchEvent({ key: 'c_profile', index: 0 }, { clientY: 130 }))

  assert.deepEqual(page.data.config.components.map((item) => item.componentKey), ['c_carousel', 'c_profile', 'c_qr'])
  assert.equal(page.data.componentDragStyle, '')
})

test('tapping carousel component edits selected image works', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({
      works: [
        { id: 11, mediaType: 'IMAGE', title: '仪式合影', coverUrl: 'https://example.com/11.jpg' },
        { id: 12, mediaType: 'VIDEO', title: '婚礼快剪', coverUrl: 'https://example.com/12.jpg' },
        { id: 13, mediaType: 'IMAGE', title: '迎宾布置', coverUrl: 'https://example.com/13.jpg' }
      ]
    })
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CAROUSEL, {
        componentKey: 'c_carousel',
        sortOrder: 1000,
        config: { workIds: [13] }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_carousel', type: COMPONENT_TYPES.CAROUSEL } } })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/works')
  assert.deepEqual(requests[0].data, { page: 1, pageSize: 100 })
  assert.equal(page.data.componentWorkSheetVisible, true)
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [11, 13])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selected), [false, true])

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 11 } } })
  page.handleConfirmComponentWorks()

  assert.deepEqual(page.data.config.components[0].config.workIds, [13, 11])
  assert.equal(page.data.componentWorkSheetVisible, false)
})

test('saving draft in create mode creates portfolio before saving draft', async () => {
  const requests = []
  const toasts = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios/standard-personal') {
      return Promise.resolve({ portfolioId: 88, draftRevision: 0, publishedRevision: 0 })
    }
    return Promise.resolve({ portfolioId: 88, draftRevision: 1, publishedRevision: 0 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.portfolioId = null
  page.data.config = normalizePortfolioConfig({
    share: { title: '新建作品集' },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  await page.handleSaveDraft()

  assert.equal(requests[0].url, '/api/mine/portfolios/standard-personal')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.config.share.title, '新建作品集')
  assert.equal(requests[1].url, '/api/mine/portfolios/88/draft')
  assert.equal(requests[1].method, 'PUT')
  assert.equal(requests[1].data.clientRevision, 0)
  assert.equal(page.data.portfolioId, 88)
  assert.equal(page.data.draftRevision, 1)
  assert.equal(toasts[0].title, '草稿已保存')
})

test('saving draft in edit mode updates current portfolio directly', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.portfolioId = 88
  page.data.draftRevision = 3

  await page.handleSaveDraft()

  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(requests[0].method, 'PUT')
  assert.equal(requests[0].data.clientRevision, 3)
  assert.equal(page.data.draftRevision, 4)
})

test('saving draft uploads local portfolio cover before saving config', async () => {
  const requests = []
  const uploads = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioCover(portfolioId, filePath) {
      uploads.push({ portfolioId, filePath })
      return Promise.resolve('https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg')
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪',
      coverUrl: 'wxfile://tmp/local-cover.jpg'
    },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  await page.handleSaveDraft()

  assert.deepEqual(uploads, [
    { portfolioId: 88, filePath: 'wxfile://tmp/local-cover.jpg' }
  ])
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(
    requests[0].data.config.share.coverUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg'
  )
  assert.equal(
    page.data.config.share.coverUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg'
  )
})

test('saving draft returns to portfolio list so list onShow reloads data', async (t) => {
  const navigations = []
  const originalGetCurrentPages = global.getCurrentPages
  global.getCurrentPages = () => [
    { route: 'pages/portfolios/portfolios' },
    { route: 'pages/portfolio-standard-edit/portfolio-standard-edit' }
  ]
  t.after(() => {
    if (originalGetCurrentPages) {
      global.getCurrentPages = originalGetCurrentPages
    } else {
      delete global.getCurrentPages
    }
  })
  const page = loadPortfolioEditorPage(() => Promise.resolve({ portfolioId: 88, draftRevision: 4 }), {
    navigateBack(options) {
      navigations.push({ type: 'back', options })
    },
    redirectTo(options) {
      navigations.push({ type: 'redirect', options })
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3

  await page.handleSaveDraft()

  assert.deepEqual(navigations, [
    { type: 'back', options: { delta: 1 } }
  ])
})

test('saving draft redirects to portfolio list when opened without list stack', async (t) => {
  const navigations = []
  const originalGetCurrentPages = global.getCurrentPages
  global.getCurrentPages = () => [
    { route: 'pages/portfolio-standard-edit/portfolio-standard-edit' }
  ]
  t.after(() => {
    if (originalGetCurrentPages) {
      global.getCurrentPages = originalGetCurrentPages
    } else {
      delete global.getCurrentPages
    }
  })
  const page = loadPortfolioEditorPage(() => Promise.resolve({ portfolioId: 88, draftRevision: 4 }), {
    navigateBack(options) {
      navigations.push({ type: 'back', options })
    },
    redirectTo(options) {
      navigations.push({ type: 'redirect', options })
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3

  await page.handleSaveDraft()

  assert.deepEqual(navigations, [
    { type: 'redirect', options: { url: '/pages/portfolios/portfolios' } }
  ])
})
