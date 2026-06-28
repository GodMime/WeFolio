const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { normalizePointTransactions } = require('../utils/points')

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

function loadPage(pageRelativePath, fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '..', pageRelativePath)
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalSessionCache = require.cache[sessionCacheKey]
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

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    redirectTo() {},
    navigateBack() {},
    navigateTo() {},
    showToast() {}
  }, wxOverrides)
  require(pagePath)
  delete global.Page
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

test('points page ignores stale load-more response after reloading first page', async () => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadPage('pages/points/points.js', fakeRequest)
  page.data.loading = false
  page.data.transactionData = normalizePointTransactions({
    page: 1,
    pageSize: 20,
    hasMore: true,
    records: [
      { transactionId: 1, sceneText: '旧第一页' }
    ]
  })

  const loadMorePromise = page.handleLoadMore()
  assert.deepEqual(requests[0].data, { page: 2, pageSize: 20 })

  const reloadPromise = page.loadPoints()
  assert.equal(requests[1].url, '/api/mine/points')
  assert.deepEqual(requests[2].data, { page: 1, pageSize: 20 })
  requests[1].pending.resolve({ balance: 100 })
  requests[2].pending.resolve({
    page: 1,
    pageSize: 20,
    hasMore: false,
    records: [
      { transactionId: 10, sceneText: '刷新第一页' }
    ]
  })
  await reloadPromise
  await flushPromises()

  requests[0].pending.resolve({
    page: 2,
    pageSize: 20,
    hasMore: false,
    records: [
      { transactionId: 2, sceneText: '旧第二页' }
    ]
  })
  await loadMorePromise
  await flushPromises()

  assert.deepEqual(page.data.transactionData.records.map((item) => item.id), [10])
  assert.equal(page.data.loading, false)
  assert.equal(page.data.loadingMore, false)
})

test('team member candidate search ignores stale response after unique code changes', async () => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadPage('pages/team-member-add/team-member-add.js', fakeRequest)
  page.data.teamId = 100
  page.data.loading = false
  page.data.uniqueCode = 'WFOLD0001'
  page.data.form.uniqueCode = 'WFOLD0001'

  const searchPromise = page.handleSearchCandidate()
  assert.deepEqual(requests[0].data, { uniqueCode: 'WFOLD0001' })

  page.handleUniqueCodeInput({
    detail: {
      value: 'WFNEW0002'
    }
  })
  requests[0].pending.resolve({
    userId: 7,
    uniqueCode: 'WFOLD0001',
    displayName: '旧候选人',
    canInvite: true
  })
  await searchPromise
  await flushPromises()

  assert.equal(page.data.loading, false)
  assert.equal(page.data.candidate, null)
  assert.equal(page.data.candidateVisible, false)
  assert.equal(page.data.form.uniqueCode, 'WFNEW0002')
})

test('team member invite waits for success toast before navigating back', async () => {
  const scheduled = []
  const originalSetTimeout = global.setTimeout
  const fakeRequest = () => Promise.resolve({})
  const toasts = []
  let navigateBackCount = 0
  global.setTimeout = (callback, delay) => {
    scheduled.push({ callback, delay })
    return scheduled.length
  }
  global.getCurrentPages = () => []
  try {
    const page = loadPage('pages/team-member-add/team-member-add.js', fakeRequest, {
      showToast(options) {
        toasts.push(options)
      },
      navigateBack() {
        navigateBackCount += 1
      }
    })
    page.data.teamId = 100
    page.data.saving = false
    page.data.candidate = {
      canInvite: true
    }
    page.data.form = {
      uniqueCode: 'WF1186',
      role: 'MEMBER',
      profession: '化妆师',
      allowPortfolio: true,
      allowProfile: true,
      allowWorks: false
    }

    await page.handleInviteMember()

    assert.equal(toasts[0].title, '邀请已发送')
    assert.equal(navigateBackCount, 0)
    assert.equal(scheduled.length, 1)
    assert.equal(scheduled[0].delay, 1200)
    scheduled[0].callback()
    assert.equal(navigateBackCount, 1)
  } finally {
    global.setTimeout = originalSetTimeout
    delete global.getCurrentPages
  }
})

test('team maintenance opens member add sheet instead of navigating to add page', () => {
  const fakeRequest = () => Promise.resolve({})
  let navigateToCount = 0
  const page = loadPage('pages/team-maintenance/team-maintenance.js', fakeRequest, {
    navigateTo() {
      navigateToCount += 1
    }
  })
  page.data.teamId = 100
  page.data.detail.team.canManageMembers = true

  page.handleAddMember()

  assert.equal(navigateToCount, 0)
  assert.equal(page.data.memberAddVisible, true)
})

test('team maintenance member invite closes sheet and refreshes current detail', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.method === 'POST') {
      return Promise.resolve({})
    }
    return Promise.resolve({
      teamId: 100,
      name: '映期团队',
      canManageMembers: true,
      members: []
    })
  }
  const toasts = []
  let navigateBackCount = 0
  const page = loadPage('pages/team-maintenance/team-maintenance.js', fakeRequest, {
    showToast(options) {
      toasts.push(options)
    },
    navigateBack() {
      navigateBackCount += 1
    }
  })
  page.data.teamId = 100
  page.data.memberAddVisible = true
  page.data.candidate = {
    canInvite: true
  }
  page.data.memberInviteForm = {
    uniqueCode: 'WF1186',
    role: 'MEMBER',
    profession: '化妆师',
    allowPortfolio: true,
    allowProfile: true,
    allowWorks: false
  }

  await page.handleInviteMember()

  assert.equal(requests[0].url, '/api/mine/teams/100/members')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[1].url, '/api/mine/teams/100')
  assert.equal(toasts[0].title, '邀请已发送')
  assert.equal(navigateBackCount, 0)
  assert.equal(page.data.memberAddVisible, false)
  assert.equal(page.data.saving, false)
})

test('team invitation response waits for toast before navigating back', async () => {
  const scheduled = []
  const originalSetTimeout = global.setTimeout
  const fakeRequest = () => Promise.resolve({
    memberId: 31,
    teamName: '星曜司仪团',
    joinStatus: 'JOINED',
    joinStatusText: '已加入',
    canRespond: false
  })
  const toasts = []
  let navigateBackCount = 0
  global.setTimeout = (callback, delay) => {
    scheduled.push({ callback, delay })
    return scheduled.length
  }
  try {
    const page = loadPage('pages/team-invitations/team-invitations.js', fakeRequest, {
      showToast(options) {
        toasts.push(options)
      },
      navigateBack() {
        navigateBackCount += 1
      }
    })
    page.data.memberId = 31
    page.data.saving = false
    page.data.invitation.canRespond = true

    await page.handleAccept()

    assert.equal(toasts[0].title, '已加入团队')
    assert.equal(navigateBackCount, 0)
    assert.equal(scheduled.length, 1)
    assert.equal(scheduled[0].delay, 1200)
    scheduled[0].callback()
    assert.equal(navigateBackCount, 1)
  } finally {
    global.setTimeout = originalSetTimeout
  }
})
