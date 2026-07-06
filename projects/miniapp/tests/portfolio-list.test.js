const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

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

function loadPortfolioListPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolios/portfolios.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalPage = global.Page
  const originalWx = global.wx
  let pageDefinition

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
      handleMaintainerAuthRequired() {},
      hasLocalToken() {
        return true
      }
    }
  }
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    navigateTo() {},
    redirectTo() {},
    showToast() {}
  }, wxOverrides)

  try {
    require(pagePath)
  } finally {
    global.Page = originalPage
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
  }

  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch) {
      applyData(this.data, patch)
    },
    cleanup() {
      global.wx = originalWx
    }
  })
}

test('creating a standard personal portfolio opens an unsaved editor draft', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88 })
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.handleCreateStandardPersonal()
  await flushPromises()

  try {
    assert.deepEqual(requests, [])
    assert.deepEqual(navigations, [
      { url: '/pages/portfolio-standard-edit/portfolio-standard-edit' }
    ])
  } finally {
    page.cleanup()
  }
})

test('unavailable portfolio creation buttons show toast without navigation', async () => {
  const navigations = []
  const toasts = []
  const page = loadPortfolioListPage(() => Promise.resolve({ portfolios: [] }), {
    navigateTo(options) {
      navigations.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  })

  ;['advanced-personal', 'standard-team', 'advanced-team'].forEach((type) => {
    page.handleUnavailableTap({
      currentTarget: {
        dataset: { type }
      }
    })
  })

  try {
    assert.deepEqual(navigations, [])
    assert.deepEqual(toasts, [
      { title: '暂未开放，即将发布', icon: 'none' },
      { title: '暂未开放，即将发布', icon: 'none' },
      { title: '暂未开放，即将发布', icon: 'none' }
    ])
  } finally {
    page.cleanup()
  }
})

test('left swiping a personal portfolio reveals delete and confirm delete refreshes list', async () => {
  const requests = []
  const toasts = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios') {
      return Promise.resolve({
        portfolios: [
          {
            portfolioId: 88,
            ownerType: 'USER',
            templateType: 'STANDARD',
            publicationStatus: 'PUBLISHED',
            title: '林安婚礼司仪',
            shareCode: 'PF001',
            coverUrl: 'https://example.test/cover.jpg'
          }
        ]
      })
    }
    if (options.url === '/api/mine/portfolios/delete/88') {
      return Promise.resolve({})
    }
    return Promise.resolve({})
  }, {
    showModal(options) {
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  page.handlePortfolioTouchStart({
    currentTarget: { dataset: { id: 88 } },
    touches: [{ clientX: 180, clientY: 20 }]
  })
  page.handlePortfolioTouchEnd({
    changedTouches: [{ clientX: 120, clientY: 22 }]
  })

  await page.handleDeletePortfolioTap({
    currentTarget: { dataset: { id: 88 } }
  })
  await flushPromises()
  await flushPromises()

  try {
    assert.equal(page.data.revealedPortfolioId, null)
    assert.equal(page.data.deletingPortfolioId, null)
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET'],
      ['/api/mine/portfolios/delete/88', 'POST'],
      ['/api/mine/portfolios', 'GET']
    ])
    assert.equal(toasts[0].title, '作品集已删除')
  } finally {
    page.cleanup()
  }
})

test('tapping a revealed portfolio card closes delete action instead of opening editor', async () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({ portfolios: [] }), {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.data.displayPortfolios = [
    {
      portfolioId: 88,
      title: '林安婚礼司仪'
    }
  ]
  page.data.revealedPortfolioId = 88

  page.handlePortfolioCardTap({
    currentTarget: { dataset: { id: 88 } }
  })

  try {
    assert.equal(page.data.revealedPortfolioId, null)
    assert.deepEqual(navigations, [])
  } finally {
    page.cleanup()
  }
})

test('publishing a draft portfolio from list confirms disclaimer before posting publish api', async () => {
  const requests = []
  const navigations = []
  const toasts = []
  const modals = []
  let listLoadCount = 0
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios') {
      listLoadCount += 1
      return Promise.resolve({
        portfolios: [
          {
            portfolioId: 88,
            ownerType: 'USER',
            templateType: 'STANDARD',
            publicationStatus: listLoadCount > 1 ? 'PUBLISHED' : 'DRAFT',
            title: '林安婚礼司仪',
            draftRevision: 3,
            publishedRevision: listLoadCount > 1 ? 4 : 0,
            shareCode: 'PF001'
          }
        ]
      })
    }
    if (options.url === '/api/mine/portfolios/88/publish') {
      return Promise.resolve({ portfolioId: 88, publishedRevision: 4 })
    }
    return Promise.resolve({})
  }, {
    navigateTo(options) {
      navigations.push(options)
    },
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  await page.handlePrimaryActionTap({
    currentTarget: {
      dataset: {
        id: 88,
        action: page.data.displayPortfolios[0].actionType
      }
    }
  })
  await flushPromises()
  await flushPromises()

  try {
    assert.deepEqual(navigations, [])
    assert.equal(modals.length, 1)
    assert.equal(modals[0].title, '发布免责声明')
    assert.match(modals[0].content, /肖像/)
    assert.match(modals[0].content, /侵权/)
    assert.equal(modals[0].confirmText, '确认发布')
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET'],
      ['/api/mine/portfolios/88/publish', 'POST'],
      ['/api/mine/portfolios', 'GET']
    ])
    assert.equal(requests[1].data.draftRevision, 3)
    assert.match(requests[1].data.idempotencyKey, /^publish-/)
    assert.equal(toasts[0].title, '已发布')
    assert.equal(page.data.displayPortfolios[0].statusText, '已发布')
  } finally {
    page.cleanup()
  }
})

test('canceling publish disclaimer from list does not call publish api', async () => {
  const requests = []
  const modals = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: false })
    }
  })
  page.data.displayPortfolios = [
    {
      portfolioId: 88,
      title: '林安婚礼司仪',
      draftRevision: 3,
      actionType: 'PUBLISH'
    }
  ]

  await page.handlePrimaryActionTap({
    currentTarget: {
      dataset: {
        id: 88,
        action: 'PUBLISH'
      }
    }
  })
  await flushPromises()

  try {
    assert.equal(modals.length, 1)
    assert.deepEqual(requests, [])
  } finally {
    page.cleanup()
  }
})

test('previewing a published portfolio from list opens published preview without sharing', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolios: [
        {
          portfolioId: 88,
          ownerType: 'USER',
          templateType: 'STANDARD',
          publicationStatus: 'PUBLISHED',
          title: '林安婚礼司仪',
          draftRevision: 5,
          publishedRevision: 4,
          shareCode: 'PF001'
        }
      ]
    })
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  page.handlePublishedPreviewTap({
    currentTarget: { dataset: { id: 88 } }
  })

  try {
    assert.equal(page.data.displayPortfolios[0].showPublishedPreview, true)
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET']
    ])
    assert.deepEqual(navigations, [
      { url: '/pages/portfolio-standard-preview/portfolio-standard-preview?portfolioId=88&scope=published' }
    ])
  } finally {
    page.cleanup()
  }
})

test('previewing a draft portfolio from list opens draft preview without publishing', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolios: [
        {
          portfolioId: 88,
          ownerType: 'USER',
          templateType: 'STANDARD',
          publicationStatus: 'DRAFT',
          title: '林安婚礼司仪',
          draftRevision: 5,
          publishedRevision: 0,
          shareCode: 'PF001'
        }
      ]
    })
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  page.handleDraftPreviewTap({
    currentTarget: { dataset: { id: 88 } }
  })

  try {
    assert.equal(page.data.displayPortfolios[0].showDraftPreview, true)
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET']
    ])
    assert.deepEqual(navigations, [
      { url: '/pages/portfolio-standard-preview/portfolio-standard-preview?portfolioId=88' }
    ])
  } finally {
    page.cleanup()
  }
})

test('bubbled primary action tap does not open portfolio editor', async () => {
  const requests = []
  const navigations = []
  const modals = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, publishedRevision: 4 })
  }, {
    navigateTo(options) {
      navigations.push(options)
    },
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    }
  })
  page.data.displayPortfolios = [
    {
      portfolioId: 88,
      title: '林安婚礼司仪',
      draftRevision: 3,
      actionType: 'PUBLISH'
    }
  ]

  const publishPromise = page.handlePrimaryActionTap({
    currentTarget: {
      dataset: {
        id: 88,
        action: 'PUBLISH'
      }
    }
  })
  page.handlePortfolioCardTap({
    currentTarget: { dataset: { id: 88 } },
    target: {
      dataset: {
        id: 88,
        action: 'PUBLISH'
      }
    }
  })
  await publishPromise
  await flushPromises()

  try {
    assert.deepEqual(navigations, [])
    assert.equal(modals.length, 1)
    assert.equal(requests[0].url, '/api/mine/portfolios/88/publish')
  } finally {
    page.cleanup()
  }
})
