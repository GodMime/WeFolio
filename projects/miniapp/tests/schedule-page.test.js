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
