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
    handleMaintainerAuthRequired() {},
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

test('visit page loads statistics and first record page from split endpoints', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    if (options.url === '/api/mine/visits/statistics') {
      return Promise.resolve({
        summary: {
          totalVisitCount: 8,
          todayVisitCount: 2,
          scheduleQueryCount: 1,
          contactLeadCount: 3
        },
        trend: {
          changeText: '持平',
          points: [{ label: '周日', value: 2 }]
        }
      })
    }
    return Promise.resolve({
      pageNo: 1,
      pageSize: 20,
      hasMore: true,
      records: [
        {
          id: 102,
          visitorLabel: '微信访客 C19F',
          followStatus: 'NOT_FOLLOWED_UP'
        }
      ]
    })
  })

  await page.loadVisits()

  assert.deepEqual(requests.map((item) => item.url), [
    '/api/mine/visits/statistics',
    '/api/mine/visits/records'
  ])
  assert.deepEqual(requests[1].data, { pageNo: 1, pageSize: 20 })
  assert.equal(page.data.visitData.metrics[0].value, '8')
  assert.equal(page.data.visitData.trend.changeText, '持平')
  assert.deepEqual(page.data.visitData.records.map((item) => item.id), [102])
  assert.equal(page.data.visitRecordPageNo, 1)
  assert.equal(page.data.visitRecordHasMore, true)
  assert.equal(page.data.loading, false)
})

test('visit page appends next record page when main scroller reaches bottom', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    return Promise.resolve({
      pageNo: 2,
      pageSize: 20,
      hasMore: false,
      records: [
        { id: 102, visitorLabel: '重复访客 C19F' },
        { id: 101, visitorLabel: '微信访客 8A21' }
      ]
    })
  })
  page.setData({
    loading: false,
    visitData: normalizeVisitRecords({
      records: [{ id: 102, visitorLabel: '微信访客 C19F' }]
    }),
    visitRecordPageNo: 1,
    visitRecordPageSize: 20,
    visitRecordHasMore: true,
    visitRecordLoadingMore: false
  })

  await page.handleVisitRecordScrollToLower()

  assert.deepEqual(requests.map((item) => item.url), ['/api/mine/visits/records'])
  assert.deepEqual(requests[0].data, { pageNo: 2, pageSize: 20 })
  assert.deepEqual(page.data.visitData.records.map((item) => item.id), [102, 101])
  assert.equal(page.data.visitData.records[0].visitorLabel, '微信访客 C19F')
  assert.equal(page.data.visitRecordPageNo, 2)
  assert.equal(page.data.visitRecordHasMore, false)
  assert.equal(page.data.visitRecordLoadingMore, false)
})

test('visit page does not load more records without next page or during request', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })

  page.setData({ visitRecordHasMore: false, visitRecordLoadingMore: false })
  await page.handleVisitRecordScrollToLower()
  page.setData({ visitRecordHasMore: true, visitRecordLoadingMore: true })
  await page.handleVisitRecordScrollToLower()

  assert.equal(requests.length, 0)
})

test('visit page keeps loaded records and retries same page after load-more failure', async () => {
  let attempt = 0
  const page = loadVisitsPage(() => {
    attempt += 1
    if (attempt === 1) {
      return Promise.reject(new Error('网络繁忙'))
    }
    return Promise.resolve({
      pageNo: 2,
      pageSize: 20,
      hasMore: false,
      records: [{ id: 101, visitorLabel: '微信访客 8A21' }]
    })
  })
  page.setData({
    loading: false,
    visitData: normalizeVisitRecords({
      records: [{ id: 102, visitorLabel: '微信访客 C19F' }]
    }),
    visitRecordPageNo: 1,
    visitRecordPageSize: 20,
    visitRecordHasMore: true,
    visitRecordLoadingMore: false
  })
  const toastTitles = []
  const originalWx = global.wx
  global.wx = {
    showToast(options) {
      toastTitles.push(options.title)
    }
  }

  try {
    await page.handleVisitRecordScrollToLower()
    assert.deepEqual(page.data.visitData.records.map((item) => item.id), [102])
    assert.equal(page.data.visitRecordPageNo, 1)
    assert.equal(page.data.visitRecordHasMore, true)
    assert.equal(page.data.visitRecordLoadingMore, false)

    await page.handleVisitRecordScrollToLower()
  } finally {
    global.wx = originalWx
  }

  assert.equal(attempt, 2)
  assert.deepEqual(toastTitles, ['网络繁忙'])
  assert.deepEqual(page.data.visitData.records.map((item) => item.id), [102, 101])
  assert.equal(page.data.visitRecordPageNo, 2)
  assert.equal(page.data.visitRecordHasMore, false)
})

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

test('schedule query metric opens paged detail sheet and appends next page', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    const pageNo = options.data && options.data.pageNo
    if (pageNo === 2) {
      return Promise.resolve({
        pageNo: 2,
        pageSize: 20,
        hasMore: false,
        items: [
          {
            id: 301,
            visitorLabel: '微信访客 C19F',
            queriedDateText: '2026-07-17',
            slotText: '晚宴 17:00-21:00',
            resultStatusText: '档期空闲',
            available: true,
            resultMessage: '档期空闲',
            portfolioTitle: '林安婚礼司仪',
            sourceText: '来自分享卡片',
            createdTimeText: '07-05 13:58'
          }
        ]
      })
    }
    return Promise.resolve({
      pageNo: 1,
      pageSize: 20,
      hasMore: true,
      items: [
        {
          id: 302,
          visitorLabel: '小陈',
          queriedDateText: '2026-07-18',
          slotText: '午宴 10:00-14:00',
          resultStatusText: '已约',
          available: false,
          resultMessage: '该档期已约',
          portfolioTitle: '林安婚礼司仪',
          sourceText: '来自分享卡片',
          createdTimeText: '07-05 14:18'
        }
      ]
    })
  })

  page.handleMetricTap({ currentTarget: { dataset: { action: 'scheduleQueries' } } })
  await flushPromises()
  page.handleVisitDetailScrollToLower()
  await flushPromises()

  assert.deepEqual(requests.map((item) => item.url), [
    '/api/mine/visits/schedule-queries',
    '/api/mine/visits/schedule-queries'
  ])
  assert.deepEqual(requests.map((item) => item.data), [
    { pageNo: 1, pageSize: 20 },
    { pageNo: 2, pageSize: 20 }
  ])
  assert.equal(page.data.detailSheetVisible, true)
  assert.equal(page.data.detailSheet.title, '查询档期')
  assert.deepEqual(page.data.detailSheet.items.map((item) => item.id), [302, 301])
  assert.equal(page.data.detailSheet.hasMore, false)
  assert.equal(page.data.detailSheetLoadingMore, false)
})

test('contact lead metric opens contact lead detail endpoint', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    return Promise.resolve({
      pageNo: 1,
      pageSize: 20,
      hasMore: false,
      items: [
        {
          id: 401,
          contactName: '王小姐',
          phone: '13800108899',
          phoneLast4: '8899',
          wechat: 'wx-full-99',
          wechatMaskHint: 'wx***99',
          desiredSchedule: '2026-10-03 午宴',
          needs: '想了解主持和摄影套餐',
          portfolioTitle: '林安婚礼司仪',
          portfolioType: 'PERSONAL',
          portfolioTypeText: '个人作品集',
          sourceText: '来自分享卡片',
          canMarkFollowed: true,
          followStatusText: '未跟进',
          submittedTimeText: '07-05 13:30'
        }
      ]
    })
  })

  page.handleMetricTap({ currentTarget: { dataset: { action: 'contactLeads' } } })
  await flushPromises()

  assert.deepEqual(requests.map((item) => item.url), [
    '/api/mine/visits/contact-leads'
  ])
  assert.equal(page.data.detailSheetVisible, true)
  assert.equal(page.data.detailSheet.title, '预留信息')
  assert.equal(page.data.detailSheet.items[0].contactName, '王小姐')
  assert.equal(page.data.detailSheet.items[0].phoneText, '13800108899')
  assert.equal(page.data.detailSheet.items[0].wechatText, 'wx-full-99')
  assert.equal(page.data.detailSheet.items[0].desiredScheduleText, '2026-10-03 午宴')
  assert.equal(page.data.detailSheet.items[0].portfolioTypeText, '个人作品集')
  assert.equal(page.data.detailSheet.items[0].canMarkFollowed, true)
})

test('contact lead detail button marks lead followed', async () => {
  const requests = []
  const page = loadVisitsPage((options) => {
    requests.push(options)
    if (options.method === 'PUT') {
      return Promise.resolve({
        id: 401,
        followStatus: 'CONTACTED',
        followStatusText: '已跟进'
      })
    }
    return Promise.resolve({})
  })
  page.setData({
    detailSheetVisible: true,
    detailSheetType: 'contactLeads',
    detailSheet: {
      type: 'contactLeads',
      title: '预留信息',
      pageNo: 1,
      pageSize: 20,
      hasMore: false,
      items: [
        {
          id: 401,
          contactName: '王小姐',
          phoneText: '13800108899',
          wechatText: 'wx-full-99',
          followStatus: 'NOT_FOLLOWED_UP',
          followStatusText: '未跟进',
          followToneClass: 'follow-pill rose',
          canMarkFollowed: true
        }
      ]
    }
  })

  await page.handleMarkContactLeadFollowedTap({
    currentTarget: { dataset: { leadId: 401 } }
  })

  assert.equal(page.data.followingContactLeadId, null)
  assert.deepEqual(requests.map((item) => ({ url: item.url, method: item.method })), [
    { url: '/api/mine/visits/contact-leads/401/followed', method: 'PUT' }
  ])
  assert.equal(page.data.detailSheet.items[0].followStatus, 'CONTACTED')
  assert.equal(page.data.detailSheet.items[0].followStatusText, '已跟进')
  assert.equal(page.data.detailSheet.items[0].followToneClass, 'follow-pill teal')
  assert.equal(page.data.detailSheet.items[0].canMarkFollowed, false)
})

test('contact lead copy buttons copy full phone and wechat values', () => {
  const page = loadVisitsPage(() => Promise.resolve({}))
  const copiedValues = []
  const originalWx = global.wx
  global.wx = {
    setClipboardData(options) {
      copiedValues.push(options.data)
      if (typeof options.success === 'function') {
        options.success()
      }
    },
    showToast() {}
  }

  try {
    page.handleCopyContactValueTap({
      currentTarget: { dataset: { copyText: '13800108899' } }
    })
    page.handleCopyContactValueTap({
      currentTarget: { dataset: { copyText: 'wx-full-99' } }
    })
  } finally {
    global.wx = originalWx
  }

  assert.deepEqual(copiedValues, ['13800108899', 'wx-full-99'])
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

test('visit event sheet closes without redrawing a native chart layer', () => {
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

  page.handleCloseEventSheet()

  assert.equal(page.data.eventSheetVisible, false)
  assert.equal(selectorQueryCount, 0)
  global.wx = originalWx
})
