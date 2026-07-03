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
