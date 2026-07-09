const assert = require('node:assert/strict')
const fs = require('node:fs')
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

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8'))
}

function readText(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function loadMiniappPage(relativePagePath, fakeRequest, wxOverrides = {}, sessionOverrides = {}) {
  const pagePath = path.join(__dirname, '..', relativePagePath)
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const pageCacheKey = require.resolve(pagePath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalPage = global.Page
  const originalWx = global.wx
  let pageDefinition

  delete require.cache[pageCacheKey]
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
    exports: Object.assign({
      handleMaintainerAuthRequired() {},
      hasLocalToken() {
        return true
      }
    }, sessionOverrides)
  }
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    navigateTo() {},
    redirectTo() {},
    showToast() {},
    stopPullDownRefresh() {}
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

test('works and portfolio list pages enable native pull-down refresh', () => {
  assert.equal(readJson('pages/works/works.json').enablePullDownRefresh, true)
  assert.equal(readJson('pages/portfolios/portfolios.json').enablePullDownRefresh, true)
})

test('works and portfolio list scroll containers bind refresher refresh', () => {
  const worksWxml = readText('pages/works/works.wxml')
  const workListScroll = worksWxml.match(/<scroll-view[\s\S]*?class="work-list-scroll"[\s\S]*?>/)[0]
  const portfoliosWxml = readText('pages/portfolios/portfolios.wxml')
  const portfolioScroll = portfoliosWxml.match(/<scroll-view[\s\S]*?class="portfolio-scroll"[\s\S]*?>/)[0]

  ;[workListScroll, portfolioScroll].forEach((scrollView) => {
    assert.match(scrollView, /refresher-enabled="\{\{true\}\}"/)
    assert.match(scrollView, /refresher-triggered="\{\{pullDownRefreshing\}\}"/)
    assert.match(scrollView, /bindrefresherrefresh="handlePullDownRefresh"/)
  })
})

test('pulling down on works list reloads first page and stops refresh indicator', async () => {
  const requests = []
  const stops = []
  const page = loadMiniappPage('pages/works/works.js', (options) => {
    requests.push(options)
    return Promise.resolve({
      page: 1,
      pageSize: 15,
      total: 1,
      hasMore: false,
      summary: { totalCount: 1, imageCount: 1, videoCount: 0 },
      tags: [{ id: 12, name: '婚礼', count: 1, color: '#2f6bff' }],
      works: [{ id: 88, title: '婚礼作品', mediaType: 'IMAGE' }]
    })
  }, {
    stopPullDownRefresh() {
      stops.push('stop')
    }
  })

  page.data.keyword = '婚礼'
  page.data.selectedTagId = 12
  page.data.list.page = 4
  page.data.list.pageSize = 15
  page.data.list.hasMore = true

  try {
    await page.handlePullDownRefresh()
    await flushPromises()

    assert.deepEqual(requests.map((item) => [item.url, item.data]), [
      ['/api/mine/works', {
        keyword: '婚礼',
        tagId: 12,
        page: 1,
        pageSize: 15
      }]
    ])
    assert.equal(stops.length, 1)
    assert.equal(page.data.pullDownRefreshing, false)
    assert.equal(page.data.loading, false)
    assert.equal(page.data.list.page, 1)
    assert.equal(page.data.list.works[0].title, '婚礼作品')
  } finally {
    page.cleanup()
  }
})

test('pulling down on works list keeps current scroll container mounted while request is pending', async () => {
  const page = loadMiniappPage('pages/works/works.js', () => new Promise(() => {}))
  page.data.loading = false
  page.data.list = {
    page: 3,
    pageSize: 20,
    hasMore: true,
    works: [{ id: 88, title: '婚礼作品' }]
  }

  try {
    page.handlePullDownRefresh()
    await flushPromises()

    assert.equal(page.data.pullDownRefreshing, true)
    assert.equal(page.data.loading, false)
    assert.deepEqual(page.data.list.works, [{ id: 88, title: '婚礼作品' }])
  } finally {
    page.cleanup()
  }
})

test('pulling down on portfolio list reloads current owner type and stops refresh indicator', async () => {
  const requests = []
  const stops = []
  const page = loadMiniappPage('pages/portfolios/portfolios.js', (options) => {
    requests.push(options)
    return Promise.resolve({
      portfolios: [{
        portfolioId: 88,
        ownerType: 'TEAM',
        templateType: 'STANDARD',
        publicationStatus: 'DRAFT',
        title: '团队作品集'
      }]
    })
  }, {
    stopPullDownRefresh() {
      stops.push('stop')
    }
  })
  page.data.ownerType = 'TEAM'
  page.data.ownerTitle = '团队作品集'

  try {
    await page.handlePullDownRefresh()
    await flushPromises()

    assert.deepEqual(requests.map((item) => [item.url, item.data]), [
      ['/api/mine/portfolios', { ownerType: 'TEAM' }]
    ])
    assert.equal(stops.length, 1)
    assert.equal(page.data.pullDownRefreshing, false)
    assert.equal(page.data.loading, false)
    assert.equal(page.data.displayPortfolios[0].title, '团队作品集')
  } finally {
    page.cleanup()
  }
})
