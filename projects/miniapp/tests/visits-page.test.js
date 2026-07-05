const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { normalizeVisitRecords } = require('../utils/visits')

function setDataPath(target, key, value) {
  const segments = String(key).split('.')
  let current = target
  segments.slice(0, -1).forEach((segment) => {
    if (!current[segment] || typeof current[segment] !== 'object') {
      current[segment] = {}
    }
    current = current[segment]
  })
  current[segments[segments.length - 1]] = value
}

function installModuleStub(modulePath, exportsValue) {
  const resolvedPath = require.resolve(modulePath)
  const originalCache = require.cache[resolvedPath]
  require.cache[resolvedPath] = {
    id: resolvedPath,
    filename: resolvedPath,
    loaded: true,
    exports: exportsValue
  }
  return () => {
    if (originalCache) {
      require.cache[resolvedPath] = originalCache
      return
    }
    delete require.cache[resolvedPath]
  }
}

function loadVisitsPage(fakeRequest) {
  const pagePath = path.join(__dirname, '../pages/visits/visits.js')
  const restoreRequest = installModuleStub('../utils/request', { request: fakeRequest })
  const restoreSession = installModuleStub('../utils/session', {
    handleAuthRequired() {},
    hasLocalToken() {
      return true
    }
  })
  const originalPage = global.Page
  const originalWx = global.wx
  let pageDefinition = null

  global.wx = {
    redirectTo() {},
    showToast() {},
    createSelectorQuery() {
      return {
        in() {
          return this
        },
        select() {
          return this
        },
        fields() {
          return this
        },
        exec(callback) {
          callback([])
        }
      }
    }
  }
  global.Page = (definition) => {
    const page = Object.assign({}, definition, {
      data: JSON.parse(JSON.stringify(definition.data || {}))
    })
    page.setData = function setData(patch, callback) {
      Object.keys(patch || {}).forEach((key) => {
        setDataPath(this.data, key, patch[key])
      })
      if (typeof callback === 'function') {
        callback()
      }
    }
    pageDefinition = page
  }

  delete require.cache[require.resolve(pagePath)]
  require(pagePath)

  global.Page = originalPage
  global.wx = originalWx
  restoreRequest()
  restoreSession()
  delete require.cache[require.resolve(pagePath)]
  return pageDefinition
}

async function flushPromises() {
  await new Promise((resolve) => setImmediate(resolve))
}

test('visit event sheet loads first page and appends next page when scrolled to bottom', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    const pageNo = options.data && options.data.pageNo
    if (pageNo === 2) {
      return Promise.resolve({
        recordId: 101,
        pageNo: 2,
        pageSize: 20,
        hasMore: false,
        events: [
          {
            eventId: 9001,
            title: '打开作品集',
            occurredDateText: '2026-07-05',
            occurredTimeText: '11:00'
          }
        ]
      })
    }
    return Promise.resolve({
      recordId: 101,
      pageNo: 1,
      pageSize: 20,
      hasMore: true,
      events: [
        {
          eventId: 9002,
          title: '查询档期',
          occurredDateText: '2026-07-05',
          occurredTimeText: '11:05'
        }
      ]
    })
  })
  page.setData({
    visitData: normalizeVisitRecords({
      records: [
        {
          id: 101,
          visitorLabel: 'Clone',
          sourceText: '来自分享卡片'
        }
      ]
    })
  })

  page.handleVisitRowTap({ currentTarget: { dataset: { recordId: 101 } } })
  await flushPromises()
  page.handleVisitEventScrollToLower()
  await flushPromises()

  assert.deepEqual(requests.map((item) => item.url), [
    '/api/mine/visits/101/events',
    '/api/mine/visits/101/events'
  ])
  assert.deepEqual(requests.map((item) => item.data), [
    { pageNo: 1, pageSize: 20 },
    { pageNo: 2, pageSize: 20 }
  ])
  assert.deepEqual(page.data.selectedVisitRecord.events.map((item) => item.id), [9002, 9001])
  assert.equal(page.data.eventSheetHasMore, false)
  assert.equal(page.data.eventSheetLoadingMore, false)
})

test('visit detail row reveals follow action and marks record followed', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    if (options.method === 'PUT') {
      return Promise.resolve({
        id: 101,
        followStatus: 'CONTACTED',
        followStatusText: '已跟进',
        followTone: 'teal'
      })
    }
    return Promise.resolve({})
  })
  page.setData({
    visitData: normalizeVisitRecords({
      records: [
        {
          id: 101,
          visitorLabel: 'Clone',
          sourceText: '来自分享卡片',
          followStatus: 'NOT_FOLLOWED_UP',
          followStatusText: '未跟进',
          followTone: 'rose'
        }
      ]
    })
  })

  page.handleVisitTouchStart({
    currentTarget: { dataset: { recordId: 101 } },
    touches: [{ clientX: 160, clientY: 42 }]
  })
  page.handleVisitTouchEnd({
    changedTouches: [{ clientX: 90, clientY: 44 }]
  })
  await page.handleMarkVisitFollowedTap({
    currentTarget: { dataset: { recordId: 101 } }
  })

  assert.equal(page.data.revealedVisitRecordId, null)
  assert.equal(page.data.followingVisitRecordId, null)
  assert.deepEqual(requests.map((item) => ({ url: item.url, method: item.method })), [
    { url: '/api/mine/visits/101/followed', method: 'PUT' }
  ])
  assert.equal(page.data.visitData.records[0].followStatus, 'CONTACTED')
  assert.equal(page.data.visitData.records[0].followStatusText, '已跟进')
  assert.equal(page.data.visitData.records[0].followToneClass, 'follow-pill teal')
  assert.equal(page.data.visitData.records[0].canMarkFollowed, false)
})

test('visit event sheet removes native chart layer while open and redraws after close', () => {
  const page = loadVisitsPage(() => Promise.resolve({}))
  let selectorQueryCount = 0
  const originalWx = global.wx
  global.wx = {
    createSelectorQuery() {
      selectorQueryCount += 1
      return {
        in() {
          return this
        },
        select() {
          return this
        },
        fields() {
          return this
        },
        exec(callback) {
          callback([])
        }
      }
    }
  }
  page.setData({
    eventSheetVisible: true,
    visitData: normalizeVisitRecords({
      trend: {
        points: [
          { label: '周一', value: 1 }
        ]
      }
    })
  })

  page.drawTrendLineChart()

  assert.equal(selectorQueryCount, 0)

  let redrawCount = 0
  page.drawTrendLineChart = () => {
    redrawCount += 1
  }
  page.handleCloseEventSheet()

  assert.equal(page.data.eventSheetVisible, false)
  assert.equal(redrawCount, 1)
  global.wx = originalWx
})
