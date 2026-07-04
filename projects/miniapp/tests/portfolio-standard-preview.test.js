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

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
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

test('portfolio preview and visitor pages render miniapp brand footer', () => {
  const logoUrl = 'https://cdn2.we-folio.dingchenyong.top/system/folio-logo.png'
  const brandName = '映期Folio'
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )

  ;[previewWxml, visitorWxml].forEach((wxml) => {
    assert.match(wxml, /class="folio-brand-footer"/)
    assert.match(wxml, new RegExp(`src="${logoUrl.replace(/\./g, '\\.')}"`))
    assert.match(wxml, new RegExp(`class="folio-brand-name">${brandName}</view>`))
  })
})

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

test('preview page requests published preview endpoint for published scope', async () => {
  const requests = []
  const page = loadPreviewPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolioId: 88,
      publishedRevision: 4,
      renderData: {
        preview: true,
        title: '已发布作品集',
        components: []
      }
    })
  })

  page.onLoad({ portfolioId: '88', scope: 'published' })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/portfolios/88/published-preview')
  assert.equal(page.data.portfolio.title, '已发布作品集')
  assert.equal(page.data.portfolio.preview, true)
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

test('profile component can render selected wechat qr in actual pages', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )

  assert.match(previewWxml, /wx:if="\{\{item\.profile\.wechatQrUrl\}\}"[\s\S]*src="\{\{item\.profile\.wechatQrUrl\}\}"[\s\S]*bindtap="handlePreviewQr"/)
  assert.match(visitorWxml, /wx:if="\{\{item\.profile\.wechatQrUrl\}\}"[\s\S]*src="\{\{item\.profile\.wechatQrUrl\}\}"[\s\S]*bindtap="handlePreviewQr"/)
})

test('actual portfolio pages do not render share intro as page content', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )

  assert.doesNotMatch(previewWxml, /portfolio\.share\.intro/)
  assert.doesNotMatch(visitorWxml, /portfolio\.share\.intro/)
  assert.doesNotMatch(previewWxml, /class="share-intro"/)
  assert.doesNotMatch(visitorWxml, /class="share-intro"/)
})

test('portfolio user-authored text preserves line breaks in actual pages', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )
  const appWxss = fs.readFileSync(
    path.join(__dirname, '../app.wxss'),
    'utf8'
  )
  const previewWxss = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxss'),
    'utf8'
  )
  const visitorWxss = fs.readFileSync(
    path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.wxss'),
    'utf8'
  )

  ;[previewWxml, visitorWxml].forEach((wxml) => {
    assert.match(wxml, /<text wx:if="\{\{item\.profile\.bio\}\}" class="profile-bio" space="nbsp">\{\{item\.profile\.bio\}\}<\/text>/)
    assert.doesNotMatch(wxml, /<view wx:if="\{\{item\.profile\.bio\}\}" class="profile-bio">/)
  })
  ;[previewWxss, visitorWxss].forEach((wxss) => {
    assert.match(wxss, /\.profile-bio,\s*\.section-desc,\s*\.text-content,\s*\.work-desc\s*\{[^}]*white-space:\s*pre-wrap;/)
    assert.match(readRule(wxss, '.profile-bio'), /display:\s*block;/)
  })
  assert.match(appWxss, /\.user-authored-text,[\s\S]*\.message-content,[\s\S]*\.team-summary,[\s\S]*\.member-summary,[\s\S]*\.candidate-summary,[\s\S]*\.visit-summary\s*\{[^}]*white-space:\s*pre-wrap;/)
})

test('portfolio profile avatar is centered in actual pages', () => {
  const previewWxss = fs.readFileSync(
    path.join(__dirname, '../pages/portfolio-standard-preview/portfolio-standard-preview.wxss'),
    'utf8'
  )
  const visitorWxss = fs.readFileSync(
    path.join(__dirname, '../pages/visitor-portfolio/visitor-portfolio.wxss'),
    'utf8'
  )

  ;[previewWxss, visitorWxss].forEach((wxss) => {
    const profileAvatarRule = readRule(wxss, '.profile-avatar')
    assert.match(profileAvatarRule, /display:\s*block;/)
    assert.match(profileAvatarRule, /margin:\s*0 auto;/)
  })
})
