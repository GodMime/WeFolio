const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function deferred() {
  let resolve
  let reject
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    target[key] = patch[key]
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function loadPreviewPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.js')
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
    navigateBack() {},
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

test('preview page keeps recoverable loading and error states when request fails', async () => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadPreviewPage(fakeRequest)

  page.onLoad({ portfolioId: '88' })

  assert.equal(page.data.loading, true)
  assert.equal(page.data.errorMessage, '')
  assert.equal(requests[0].url, '/api/mine/portfolios/88/preview')

  requests[0].pending.reject(new Error('网络异常'))
  await flushPromises()

  assert.equal(page.data.loading, false)
  assert.equal(page.data.errorMessage, '网络异常')

  const retryPromise = page.handleRetryPreview()
  assert.equal(page.data.loading, true)
  assert.equal(page.data.errorMessage, '')
  assert.equal(requests[1].url, '/api/mine/portfolios/88/preview')

  requests[1].pending.resolve({
    renderData: {
      title: '预览成功',
      preview: true,
      components: []
    }
  })
  await retryPromise

  assert.equal(page.data.loading, false)
  assert.equal(page.data.errorMessage, '')
  assert.equal(page.data.portfolio.title, '预览成功')
})

test('preview display group switch tolerates unnormalized component arrays', () => {
  const page = loadPreviewPage(() => Promise.resolve({}))
  page.data.portfolio = {
    components: [
      {
        componentKey: 'c_grid',
        componentType: 'WORK_GRID',
        activeGroupKey: 'g_existing',
        activeGroup: {
          groupKey: 'g_existing',
          name: '已有标签',
          works: []
        }
      }
    ]
  }

  assert.doesNotThrow(() => {
    page.handleDisplayTagTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_grid',
          groupKey: 'g_missing'
        }
      }
    })
  })
  assert.equal(page.data.portfolio.components[0].activeGroupKey, 'g_existing')
})

test('preview markup exposes loading skeleton and retryable error state', () => {
  const wxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )

  assert.match(wxml, /wx:if="\{\{loading\}\}"/)
  assert.match(wxml, /wx:elif="\{\{errorMessage\}\}"/)
  assert.match(wxml, /bindtap="handleRetryPreview"/)
})
