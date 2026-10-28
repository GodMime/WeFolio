const assert = require('node:assert/strict')
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
    if (!key.includes('.')) {
      target[key] = patch[key]
      return
    }
    const parts = key.split('.')
    let current = target
    parts.slice(0, -1).forEach((part) => {
      if (!current[part]) {
        current[part] = {}
      }
      current = current[part]
    })
    current[parts[parts.length - 1]] = patch[key]
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function loadSchedulePage(fakeRequest, options = {}) {
  const pagePath = path.join(__dirname, '../pages/schedule/schedule.js')
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
      handleMaintainerAuthRequired: options.handleMaintainerAuthRequired || (() => {}),
      hasLocalToken() {
        return true
      }
    }
  }
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = {
    redirectTo() {},
    showToast: options.showToast || (() => {})
  }

  require(pagePath)

  const page = Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    }
  })

  return {
    page,
    restore() {
      delete require.cache[require.resolve(pagePath)]
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
      if (originalPage === undefined) {
        delete global.Page
      } else {
        global.Page = originalPage
      }
      if (originalWx === undefined) {
        delete global.wx
      } else {
        global.wx = originalWx
      }
    }
  }
}

function createToggleEvent(id) {
  return {
    currentTarget: {
      dataset: { id }
    }
  }
}

function createSlotTimeChangeEvent(field, value) {
  return {
    currentTarget: {
      dataset: { field }
    },
    detail: { value }
  }
}

function createTouchEvent(id, x = 160, y = 80) {
  return Object.assign(createToggleEvent(String(id)), {
    touches: [{ clientX: x, clientY: y }],
    changedTouches: [{ clientX: x, clientY: y }]
  })
}

function loadGesturePage(context) {
  const harness = loadSchedulePage(() => {
    assert.fail('展开删除操作区不应发起请求')
  })
  context.after(harness.restore)
  const { page } = harness
  page.data.overview.selectedDate.schedules = [
    { id: 7, slotDefinitionId: 7, scheduleDate: '2026-10-28', status: 'BOOKED' },
    { id: 8, slotDefinitionId: 8, scheduleDate: '2026-10-28', status: 'TENTATIVE' }
  ]
  page.data.overview.slotDefinitions = [
    { id: 7, name: '午宴档', enabled: false, status: 'DISABLED', color: '#c28f4b' },
    { id: 8, name: '晚宴档', enabled: false, status: 'DISABLED', color: '#6f8cb5' },
    { id: 9, name: '全天档', enabled: true, status: 'ACTIVE', color: '#5f999b' }
  ]
  return page
}

const gestureCases = [
  {
    name: 'schedule',
    touchStart: 'handleScheduleTouchStart',
    touchEnd: 'handleScheduleTouchEnd',
    longPress: 'handleScheduleLongPress',
    tap: 'handleScheduleTap',
    revealed: 'revealedScheduleId',
    formVisible: 'scheduleFormVisible',
    editing: 'editingScheduleId'
  },
  {
    name: 'disabled slot',
    touchStart: 'handleSlotTouchStart',
    touchEnd: 'handleSlotTouchEnd',
    longPress: 'handleSlotLongPress',
    tap: 'handleSlotEdit',
    revealed: 'revealedSlotId',
    formVisible: 'slotFormVisible',
    editing: 'editingSlotId'
  }
]

gestureCases.forEach((gesture) => {
  test(`${gesture.name} long press stays revealed after release without opening the editor`, (context) => {
    const page = loadGesturePage(context)
    const event = createTouchEvent(7)

    page[gesture.touchStart](event)
    page[gesture.longPress](event)
    assert.equal(page.data[gesture.revealed], 7)
    page[gesture.touchEnd](event)
    page[gesture.tap](event)

    assert.equal(page.data[gesture.revealed], 7)
    assert.equal(page.data[gesture.formVisible], false)
    assert.equal(page.data[gesture.editing], null)

    page[gesture.touchStart](event)
    page[gesture.touchEnd](event)
    page[gesture.tap](event)

    assert.equal(page.data[gesture.formVisible], true)
    assert.equal(page.data[gesture.editing], 7)
  })

  test(`${gesture.name} repeated long press keeps one row revealed and ignores missing items`, (context) => {
    const page = loadGesturePage(context)

    for (const id of [7, 7, 8]) {
      const event = createTouchEvent(id)
      page[gesture.touchStart](event)
      page[gesture.longPress](event)
      page[gesture.touchEnd](event)
      assert.equal(page.data[gesture.revealed], id)
      assert.equal(page.data[gesture.formVisible], false)
    }

    page[gesture.longPress](createTouchEvent(999))
    assert.equal(page.data[gesture.revealed], 8)
  })

  test(`${gesture.name} still opens on a left swipe and closes on a right swipe`, (context) => {
    const page = loadGesturePage(context)

    page[gesture.touchStart](createTouchEvent(7))
    page[gesture.touchEnd](createTouchEvent(7, 100))
    assert.equal(page.data[gesture.revealed], 7)

    page[gesture.touchStart](createTouchEvent(7, 100))
    page[gesture.touchEnd](createTouchEvent(7))
    assert.equal(page.data[gesture.revealed], null)

    page[gesture.touchStart](createTouchEvent(7))
    page[gesture.touchEnd](createTouchEvent(7, 100, 180))
    assert.equal(page.data[gesture.revealed], null)
    assert.equal(page.data[gesture.formVisible], false)
  })
})

test('enabled slots cannot reveal delete actions by long press or swipe', (context) => {
  const page = loadGesturePage(context)
  const event = createTouchEvent(9)

  page.handleSlotTouchStart(event)
  page.handleSlotLongPress(event)
  page.handleSlotTouchEnd(event)
  assert.equal(page.data.revealedSlotId, null)

  page.handleSlotTouchStart(event)
  page.handleSlotTouchEnd(createTouchEvent(9, 100))
  assert.equal(page.data.revealedSlotId, null)
  assert.equal(page.data.slotFormVisible, false)
})

test('new slot moves end time two hours after a later start time', (context) => {
  const harness = loadSchedulePage(() => Promise.resolve({}))
  context.after(harness.restore)
  const { page } = harness
  page.data.editingSlotId = null
  page.data.slotForm = {
    startTime: '07:30',
    endTime: '09:30'
  }

  page.handleSlotTimeChange(createSlotTimeChangeEvent('startTime', '10:00'))

  assert.equal(page.data.slotForm.startTime, '10:00')
  assert.equal(page.data.slotForm.endTime, '12:00')
})

test('new slot keeps end time when start time is not later', (context) => {
  const harness = loadSchedulePage(() => Promise.resolve({}))
  context.after(harness.restore)
  const { page } = harness
  page.data.editingSlotId = null
  page.data.slotForm = {
    startTime: '07:30',
    endTime: '09:30'
  }

  page.handleSlotTimeChange(createSlotTimeChangeEvent('startTime', '09:30'))

  assert.equal(page.data.slotForm.startTime, '09:30')
  assert.equal(page.data.slotForm.endTime, '09:30')
})

test('editing slot does not move end time after a later start time', (context) => {
  const harness = loadSchedulePage(() => Promise.resolve({}))
  context.after(harness.restore)
  const { page } = harness
  page.data.editingSlotId = 7
  page.data.slotForm = {
    startTime: '07:30',
    endTime: '09:30'
  }

  page.handleSlotTimeChange(createSlotTimeChangeEvent('startTime', '10:00'))

  assert.equal(page.data.slotForm.startTime, '10:00')
  assert.equal(page.data.slotForm.endTime, '09:30')
})

test('slot toggle keeps button loading until the silent definition refresh completes', async (context) => {
  const requests = []
  const fakeRequest = (requestOptions) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, requestOptions))
    return pending.promise
  }
  const harness = loadSchedulePage(fakeRequest)
  context.after(harness.restore)
  const { page } = harness
  page.data.slotDefinitionsLoading = false
  page.data.overview.slotDefinitions = [
    { id: 7, name: '午宴档', enabled: true }
  ]

  const togglePromise = page.handleToggleSlotStatus(createToggleEvent('7'))

  assert.equal(page.data.togglingSlotId, 7)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/mine/schedule/slot-definitions/status/7')
  assert.deepEqual(requests[0].data, { status: 'DISABLED' })

  await page.handleToggleSlotStatus(createToggleEvent('7'))
  assert.equal(requests.length, 1)

  requests[0].pending.resolve({})
  await flushPromises()

  assert.equal(requests.length, 2)
  assert.equal(requests[1].url, '/api/mine/schedule/slot-definitions')
  assert.equal(page.data.slotDefinitionsLoading, false)
  assert.equal(page.data.togglingSlotId, 7)

  requests[1].pending.resolve([
    {
      id: 7,
      name: '午宴档',
      startTime: '07:30',
      endTime: '13:30',
      color: '#c99a55',
      status: 'DISABLED'
    }
  ])
  await togglePromise

  assert.equal(page.data.togglingSlotId, null)
  assert.equal(page.data.overview.slotDefinitions[0].enabled, false)
})

test('slot toggle clears button loading when the status request fails', async (context) => {
  const toasts = []
  const harness = loadSchedulePage(
    () => Promise.reject(new Error('状态更新失败')),
    {
      showToast(options) {
        toasts.push(options)
      }
    }
  )
  context.after(harness.restore)
  const { page } = harness
  page.data.overview.slotDefinitions = [
    { id: 8, name: '晚宴档', enabled: false }
  ]

  await page.handleToggleSlotStatus(createToggleEvent(8))

  assert.equal(page.data.togglingSlotId, null)
  assert.deepEqual(toasts, [
    { title: '状态更新失败', icon: 'none' }
  ])
})

test('slot toggle clears button loading after maintainer authentication expires', async (context) => {
  const authMessages = []
  const harness = loadSchedulePage(
    () => Promise.reject({ authRequired: true, message: '登录已失效' }),
    {
      handleMaintainerAuthRequired(message) {
        authMessages.push(message)
      }
    }
  )
  context.after(harness.restore)
  const { page } = harness
  page.data.overview.slotDefinitions = [
    { id: 9, name: '全天档', enabled: true }
  ]

  await page.handleToggleSlotStatus(createToggleEvent(9))

  assert.equal(page.data.togglingSlotId, null)
  assert.deepEqual(authMessages, ['登录已失效'])
})
