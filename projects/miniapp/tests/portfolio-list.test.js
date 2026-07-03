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
      handleAuthRequired() {},
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
