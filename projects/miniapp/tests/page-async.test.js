const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { normalizePointTransactions } = require('../utils/points')
const { buildWorkThumbnailCropState } = require('../pages/works/utils/work-thumbnail-crop')

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

function createEventDrivenVideoDecoder(calls) {
  const handlers = {}
  let started = false
  let seeked = false
  return {
    on(eventName, callback) {
      handlers[eventName] = callback
    },
    off(eventName, callback) {
      if (handlers[eventName] === callback) {
        delete handlers[eventName]
      }
    },
    start(options) {
      calls.push({ type: 'start', options })
      setImmediate(() => {
        started = true
        if (handlers.start) {
          handlers.start({ width: 1920, height: 1080 })
        }
      })
    },
    seek(position) {
      calls.push({ type: 'seek', position })
      if (!started) {
        throw new Error('seek before decoder start event')
      }
      setImmediate(() => {
        seeked = true
        if (handlers.seek) {
          handlers.seek()
        }
      })
    },
    getFrameData() {
      calls.push({ type: 'frame' })
      if (!seeked) {
        throw new Error('read before decoder seek event')
      }
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    },
    stop() {
      calls.push({ type: 'stop' })
    },
    remove() {
      calls.push({ type: 'remove' })
    }
  }
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
      handleMaintainerAuthRequired() {},
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

test('works page ignores stale load-more failure after refreshed list succeeds', async () => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.loading = false
  page.data.list.page = 1
  page.data.list.pageSize = 20
  page.data.list.hasMore = true

  const staleLoadMore = page.loadWorks(false)
  const refresh = page.loadWorks(true)
  requests[1].pending.resolve({ page: 1, pageSize: 20, hasMore: false, works: [] })
  await refresh
  requests[0].pending.reject(new Error('旧请求失败'))
  await staleLoadMore

  assert.equal(page.data.errorMessage, '')
  assert.equal(page.data.loading, false)
  assert.equal(page.data.loadingMore, false)
})

test('team creation remains successful when only the avatar upload fails', async () => {
  const requests = []
  const toasts = []
  const page = loadPage('pages/teams/teams.js', (options) => {
    requests.push(options)
    if (options.url === '/api/mine/teams/v2' && options.method === 'POST') {
      return Promise.resolve({ team: { teamId: 101, name: '星曜司仪团' } })
    }
    return Promise.resolve({ teams: [] })
  }, {
    getStorageSync() {
      return 'maintainer-token'
    },
    getFileSystemManager() {
      return { statSync() { return { size: 100 * 1024 } } }
    },
    uploadFile(options) {
      options.fail({ errMsg: 'uploadFile:fail timeout' })
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.loading = false
  page.data.createFormVisible = true
  page.data.form = {
    name: '星曜司仪团',
    intro: '婚礼主持团队',
    avatarUrl: '/tmp/team-avatar.jpg'
  }

  await page.handleCreateTeam()
  await flushPromises()

  const createRequests = requests.filter((item) => item.url === '/api/mine/teams/v2' && item.method === 'POST')
  assert.equal(createRequests.length, 1)
  assert.match(createRequests[0].data.idempotencyKey, /^team-create-/)
  assert.equal(page.data.createFormVisible, false)
  assert.equal(page.data.saving, false)
  assert.equal(page.data.form.name, '')
  assert.deepEqual(toasts.at(-1), {
    title: '团队已创建，图标上传失败，可稍后在团队资料中重试',
    icon: 'none'
  })
})

test('team creation retries reuse the pending idempotency key', async () => {
  const requests = []
  let attempt = 0
  const page = loadPage('pages/teams/teams.js', (options) => {
    requests.push(options)
    if (options.url === '/api/mine/teams/v2' && options.method === 'POST') {
      attempt += 1
      if (attempt === 1) return Promise.reject(new Error('网络超时'))
      return Promise.resolve({ team: { teamId: 102, name: '星曜司仪团' } })
    }
    return Promise.resolve({ teams: [] })
  }, {
    getStorageSync() { return 'maintainer-token' },
    showToast() {}
  })
  page.data.loading = false
  page.data.createFormVisible = true
  page.data.form = { name: '星曜司仪团', intro: '', avatarUrl: '' }

  await page.handleCreateTeam()
  await page.handleCreateTeam()

  const creates = requests.filter((item) => item.url === '/api/mine/teams/v2' && item.method === 'POST')
  assert.equal(creates.length, 2)
  assert.equal(creates[0].data.idempotencyKey, creates[1].data.idempotencyKey)
  assert.equal(page.pendingCreateIdempotencyKey, '')
})

test('editing the team form after a failed create starts a new idempotency operation', async () => {
  const page = loadPage('pages/teams/teams.js', (options) => {
    if (options.url === '/api/mine/teams/v2' && options.method === 'POST') {
      return Promise.reject(new Error('网络超时'))
    }
    return Promise.resolve({ teams: [] })
  }, {
    getStorageSync() { return 'maintainer-token' },
    showToast() {}
  })
  page.data.form = { name: '星曜司仪团', intro: '', avatarUrl: '' }

  await page.handleCreateTeam()
  assert.match(page.pendingCreateIdempotencyKey, /^team-create-/)

  page.handleInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '新团队名' } })
  assert.equal(page.pendingCreateIdempotencyKey, '')
})

test('delayed team navigation timers are cancelled when their page unloads', () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const cleared = []
  let nextTimer = 0
  global.setTimeout = () => {
    nextTimer += 1
    return nextTimer
  }
  global.clearTimeout = (timer) => {
    cleared.push(timer)
  }
  try {
    const invitationPage = loadPage('pages/team-invitations/team-invitations.js', () => Promise.resolve({}))
    invitationPage.navigateBackAfterToast()
    invitationPage.onUnload()

    const memberAddPage = loadPage('pages/team-member-add/team-member-add.js', () => Promise.resolve({}))
    memberAddPage.navigateBackAfterToast()
    memberAddPage.onUnload()

    assert.deepEqual(cleared, [1, 2])
    assert.equal(invitationPage._navigateBackTimer, null)
    assert.equal(memberAddPage._navigateBackTimer, null)
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
})

test('visitor schedule formats its initial date with local calendar fields', () => {
  let definition
  const pagePath = path.join(__dirname, '../pages/portfolios/visitor-schedule/visitor-schedule.js')
  delete require.cache[require.resolve(pagePath)]
  global.Page = (value) => { definition = value }
  const { todayText } = require(pagePath)
  delete global.Page

  const localDate = {
    getFullYear() { return 2026 },
    getMonth() { return 7 },
    getDate() { return 7 },
    toISOString() { return '2026-08-06T16:30:00.000Z' }
  }

  assert.equal(todayText(localDate), '2026-08-07')
  assert.ok(definition)
  delete require.cache[require.resolve(pagePath)]
  delete require.cache[require.resolve('../utils/visitor-session.js')]
})

test('points page enables automatic marquee only for overflowing ledger descriptions', async () => {
  const page = loadPage('pages/points/points.js', (options) => {
    if (options.url === '/api/mine/points') {
      return Promise.resolve({ balance: 100 })
    }
    return Promise.resolve({
      page: 1,
      pageSize: 20,
      hasMore: false,
      records: [
        { transactionId: 1, sceneText: '超长流水', remark: '超长说明' },
        { transactionId: 2, sceneText: '短流水', remark: '短说明' }
      ]
    })
  })
  page.createSelectorQuery = () => {
    const selectors = []
    const query = {
      selectAll(selector) {
        selectors.push(selector)
        return {
          boundingClientRect() {
            return query
          }
        }
      },
      exec(callback) {
        assert.deepEqual(selectors, ['.ledger-desc-viewport', '.ledger-desc-text'])
        callback([
          [{ width: 200 }, { width: 200 }],
          [{ width: 280 }, { width: 180 }]
        ])
      }
    }
    return query
  }

  await page.loadPoints()

  assert.deepEqual(
    page.data.transactionData.records.map((item) => ({
      marqueeEnabled: item.marqueeEnabled,
      marqueeDistance: item.marqueeDistance,
      marqueeDuration: item.marqueeDuration
    })),
    [
      { marqueeEnabled: true, marqueeDistance: 80, marqueeDuration: 6 },
      { marqueeEnabled: false, marqueeDistance: 0, marqueeDuration: 0 }
    ]
  )
})

test('visitor portfolio sends wx login code when opening share', async () => {
  const requests = []
  let loginCalled = false
  const page = loadPage('pages/portfolios/visitor-portfolio/visitor-portfolio.js', (options) => {
    requests.push(options)
    return Promise.resolve({
      shareCode: 'PF001',
      visitorKey: 'visitor-a',
      title: '访客作品集',
      config: { components: [] }
    })
  }, {
    login({ success }) {
      loginCalled = true
      success({ code: 'wx-code' })
    },
    previewImage() {}
  })

  await page.onLoad({ shareCode: 'PF001' })
  await flushPromises()

  assert.equal(loginCalled, true)
  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/open')
  assert.equal(requests[0].data.visitorKey, undefined)
  assert.equal(requests[0].data.loginCode, 'wx-code')
  assert.equal(page.data.visitorKey, 'visitor-a')
})

test('visitor portfolio stops loading when wx login returns empty code', async () => {
  const requests = []
  const toasts = []
  const page = loadPage('pages/portfolios/visitor-portfolio/visitor-portfolio.js', (options) => {
    requests.push(options)
    return Promise.resolve({})
  }, {
    getStorageSync() {
      return 'visitor-a'
    },
    setStorageSync() {},
    login({ success }) {
      success({})
    },
    showToast(options) {
      toasts.push(options)
    },
    previewImage() {}
  })

  await page.onLoad({ shareCode: 'PF001' })
  await flushPromises()

  assert.equal(requests.length, 0)
  assert.equal(toasts[0].title, '微信登录凭证为空')
  assert.equal(toasts[0].icon, 'none')
})

test('visitor portfolio stops loading when wx login fails', async () => {
  const requests = []
  const toasts = []
  const page = loadPage('pages/portfolios/visitor-portfolio/visitor-portfolio.js', (options) => {
    requests.push(options)
    return Promise.resolve({})
  }, {
    getStorageSync() {
      return 'visitor-a'
    },
    setStorageSync() {},
    login({ fail }) {
      fail({ errMsg: 'login:fail network unavailable' })
    },
    showToast(options) {
      toasts.push(options)
    },
    previewImage() {}
  })

  await page.onLoad({ shareCode: 'PF001' })
  await flushPromises()

  assert.equal(requests.length, 0)
  assert.equal(toasts[0].title, 'login:fail network unavailable')
  assert.equal(toasts[0].icon, 'none')
})

test('visitor portfolio stops loading when wx login times out', async () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const scheduled = []
  const requests = []
  const toasts = []
  global.setTimeout = (callback, delay) => {
    scheduled.push({ callback, delay })
    return scheduled.length
  }
  global.clearTimeout = () => {}
  try {
    const page = loadPage('pages/portfolios/visitor-portfolio/visitor-portfolio.js', (options) => {
      requests.push(options)
      return Promise.resolve({})
    }, {
      getStorageSync() {
        return 'visitor-a'
      },
      setStorageSync() {},
      login() {},
      showToast(options) {
        toasts.push(options)
      },
      previewImage() {}
    })

    const loadPromise = page.onLoad({ shareCode: 'PF001' })
    assert.equal(scheduled.length, 1)
    assert.equal(scheduled[0].delay, 5000)
    scheduled[0].callback()
    await loadPromise
    await flushPromises()

    assert.equal(requests.length, 0)
    assert.equal(toasts[0].title, '微信登录超时，请重试')
    assert.equal(toasts[0].icon, 'none')
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
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

test('work add retry skips upload tickets for already ticketed files', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/upload-complete') {
      return Promise.resolve({
        items: [
          { taskId: 100, success: true, workId: 220, message: '上传成功' }
        ]
      })
    }
    return Promise.resolve({ items: [] })
  }
  const page = loadPage('pages/works/work-add/work-add.js', fakeRequest)
  page.data.files = [
    {
      id: 'saved-a',
      clientId: 'saved-a',
      taskId: 99,
      confirmedWorkId: 219,
      status: 'CONFIRMED',
      mediaType: 'IMAGE',
      fileType: 'image',
      fileName: 'saved.jpg',
      mimeType: 'image/jpeg',
      size: 1024,
      title: '已保存',
      description: '',
      tags: [],
      progress: 100
    },
    {
      id: 'retry-b',
      clientId: 'retry-b',
      taskId: 100,
      status: 'UPLOADED',
      mediaType: 'IMAGE',
      fileType: 'image',
      fileName: 'retry.jpg',
      mimeType: 'image/jpeg',
      size: 1024,
      title: '待重试',
      description: '',
      tags: [],
      progress: 100,
      confirmIdempotencyKey: 'confirm-100'
    }
  ]

  await page.handleSubmit()

  assert.deepEqual(requests.map((request) => request.url), ['/api/mine/works/upload-complete'])
  assert.deepEqual(requests[0].data.items.map((item) => item.taskId), [100])
  assert.equal(page.data.files[0].status, 'CONFIRMED')
  assert.equal(page.data.files[1].status, 'CONFIRMED')
  assert.equal(page.data.files[1].confirmedWorkId, 220)
})

test('work add gives loaded tag options an explicit Skyline list height', async () => {
  const fakeRequest = () => Promise.resolve({
    tags: [
      { id: 1, name: '户外仪式' },
      { id: 2, name: '室内迎宾' },
      { id: 3, name: '晚宴快剪' }
    ]
  })
  const page = loadPage('pages/works/work-add/work-add.js', fakeRequest)

  await page.loadTagPickerTags()

  assert.deepEqual(page.data.tagPickerTags.map((tag) => tag.name), ['户外仪式', '室内迎宾', '晚宴快剪'])
  assert.equal(page.data.tagOptionListHeight, 224)
})

test('work add keeps video cover generation on backend first frame', async () => {
  const fakeRequest = () => Promise.resolve({})
  const page = loadPage('pages/works/work-add/work-add.js', fakeRequest)
  page.data.files = [{
    id: 'video-a',
    title: '旧标题',
    description: '',
    customCoverPath: 'wxfile://tmp/old-cover.jpg'
  }]
  page.data.editForm = {
    index: 0,
    id: 'video-a',
    isVideo: true,
    title: ' 新标题 ',
    description: ' 新说明 ',
    coverFrameTimeMs: 1200,
    customCoverPath: 'wxfile://tmp/new-cover.jpg'
  }

  page.handleConfirmFileEdit()

  const patchedFile = page.data['files[0]']
  assert.equal(patchedFile.title, '新标题')
  assert.equal(patchedFile.description, '新说明')
  assert.equal(patchedFile.customCoverPath, 'wxfile://tmp/old-cover.jpg')
  assert.equal(patchedFile.coverFrameTimeMs, undefined)
})

test('works page opens image edit sheet and saves image text directly', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.method === 'PUT') {
      return Promise.resolve({
        work: {
          id: 17,
          title: '海边仪式',
          description: '新说明'
        },
        references: []
      })
    }
    return Promise.resolve({ works: [], tags: [], summary: {} })
  }
  let navigateToCount = 0
  const page = loadPage('pages/works/works.js', fakeRequest, {
    navigateTo() {
      navigateToCount += 1
    }
  })
  page.data.list.works = [
    {
      id: 17,
      mediaType: 'IMAGE',
      title: '旧标题',
      description: '旧说明',
      originalFileName: 'photo.jpg',
      mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/image/photo.jpg',
      coverUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/image/photo-thumb.jpg'
    }
  ]

  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '17'
      }
    }
  })
  assert.equal(page.data.imageEditSheetVisible, true)
  assert.equal(page.data.videoEditSheetVisible, false)
  assert.equal(
    page.data.imageEditForm.previewPath,
    'https://cos.we-folio.dingchenyong.top/WF/work/image/photo.jpg'
  )
  assert.equal(
    page.data.imageEditForm.thumbnailPreviewPath,
    'https://cos.we-folio.dingchenyong.top/WF/work/image/photo-thumb.jpg'
  )
  assert.equal(
    page.data.imageEditForm.thumbnailSourcePath,
    'https://cos.we-folio.dingchenyong.top/WF/work/image/photo.jpg'
  )
  assert.deepEqual(page.data.imageEditFieldCounters, {
    title: '3/30',
    description: '3/1000'
  })
  page.handleImageEditInput({
    currentTarget: {
      dataset: {
        field: 'title'
      }
    },
    detail: {
      value: ' 海边仪式 '
    }
  })
  page.handleImageEditInput({
    currentTarget: {
      dataset: {
        field: 'description'
      }
    },
    detail: {
      value: ' 新说明 '
    }
  })
  assert.deepEqual(page.data.imageEditFieldCounters, {
    title: '6/30',
    description: '5/1000'
  })
  await page.handleConfirmImageEdit()

  assert.equal(navigateToCount, 0)
  assert.equal(requests[0].url, '/api/mine/works/17')
  assert.equal(requests[0].method, 'PUT')
  assert.deepEqual(requests[0].data, {
    title: '海边仪式',
    description: '新说明'
  })
  assert.equal(page.data.imageEditSheetVisible, false)
})

test('works page uploads edited image thumbnail before saving image work', async () => {
  const requests = []
  const uploadCalls = []
  const digest = 'a'.repeat(64)
  let statCount = 0
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/17/thumbnail-upload-ticket') {
      return Promise.resolve({
        taskId: 88,
        uploadUrl: 'https://cos.example.com',
        objectKey: 'WFA3B1E7A2/work/image/photo-thumb.jpg',
        formData: {
          key: 'WFA3B1E7A2/work/image/photo-thumb.jpg'
        }
      })
    }
    if (options.method === 'PUT') {
      return Promise.resolve({
        work: {
          id: 17,
          title: '海边仪式',
          description: '新缩略图',
          updatedAt: '2026-07-08T17:30:00+08:00'
        },
        references: []
      })
    }
    return Promise.resolve({
      works: [{
        id: 17,
        mediaType: 'IMAGE',
        title: '海边仪式',
        description: '新缩略图',
        mediaUrl: 'https://cos.example.com/WFA3B1E7A2/work/image/photo.jpg',
        coverUrl: 'https://cos.example.com/WFA3B1E7A2/work/image/photo-thumb.jpg',
        updatedAt: '2026-07-08T17:30:00+08:00'
      }],
      tags: [],
      summary: {}
    })
  }
  const page = loadPage('pages/works/works.js', fakeRequest, {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          assert.equal(filePath, 'wxfile://tmp/cropped-thumb.jpg')
          statCount += 1
          return { size: 96000 }
        },
        getFileInfo(options) {
          assert.equal(options.filePath, 'wxfile://tmp/cropped-thumb.jpg')
          assert.equal(options.digestAlgorithm, 'sha256')
          options.success({ digest })
        }
      }
    },
    uploadFile(options) {
      uploadCalls.push(options)
      options.success({ statusCode: 204 })
      return {
        onProgressUpdate(callback) {
          callback({ progress: 64 })
        }
      }
    }
  })
  page.data.list.works = [
    {
      id: 17,
      mediaType: 'IMAGE',
      title: '旧标题',
      description: '旧说明',
      originalFileName: 'photo.jpg',
      mediaUrl: 'https://cos.example.com/WFA3B1E7A2/work/image/photo.jpg',
      coverUrl: 'https://cos.example.com/WFA3B1E7A2/work/image/photo-thumb.jpg',
      width: 1600,
      height: 900
    }
  ]

  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '17'
      }
    }
  })
  page.setData({
    'imageEditForm.title': ' 海边仪式 ',
    'imageEditForm.description': ' 新缩略图 ',
    'imageEditForm.thumbnailEdited': true,
    'imageEditForm.thumbnailEditedPath': 'wxfile://tmp/cropped-thumb.jpg',
    'imageEditForm.thumbnailPreparedFile': {
      filePath: 'wxfile://tmp/cropped-thumb.jpg',
      fileSize: 96000,
      mimeType: 'image/jpeg'
    },
    'imageEditForm.thumbnailWidth': 960,
    'imageEditForm.thumbnailHeight': 540,
    'imageEditForm.thumbnailIdempotencyKey': 'thumbnail-ticket-17'
  })

  await page.handleConfirmImageEdit()

  assert.equal(requests[0].url, '/api/mine/works/17/thumbnail-upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.deepEqual(requests[0].data, {
    clientId: 'edit-work-17-thumbnail',
    fileName: 'photo-thumb.jpg',
    mimeType: 'image/jpeg',
    fileSize: 96000,
    sha256: digest,
    width: 960,
    height: 540,
    ratio: '16:9',
    idempotencyKey: 'thumbnail-ticket-17'
  })
  assert.equal(uploadCalls.length, 1)
  assert.equal(uploadCalls[0].filePath, 'wxfile://tmp/cropped-thumb.jpg')
  assert.equal(statCount, 0)
  assert.equal(requests[1].url, '/api/mine/works/17')
  assert.equal(requests[1].method, 'PUT')
  assert.deepEqual(requests[1].data, {
    title: '海边仪式',
    description: '新缩略图',
    thumbnailTaskId: 88
  })
  assert.equal(page.data.imageEditSheetVisible, false)
  assert.equal(page.data.imageThumbnailUploadProgress, 0)
  assert.match(page.data.list.works[0].coverUrl, /\?v=/)
})

test('works page refreshes existing remote cover after edited local thumbnail is saved', () => {
  const page = loadPage('pages/works/works.js', () => Promise.resolve({ works: [], tags: [], summary: {} }))
  page.data.list.works = [{
    id: 17,
    mediaType: 'IMAGE',
    title: '旧标题',
    description: '旧说明',
    coverUrl: 'https://cos.example.com/old-thumb.jpg'
  }]

  page.patchWorkInList({
    id: 17,
    title: '新标题',
    description: '新说明',
    thumbnailEdited: true,
    thumbnailPreviewPath: 'wxfile://tmp/local-preview.jpg'
  }, {
    title: '新标题',
    description: '新说明',
    updatedAt: '2026-07-08T17:30:00+08:00'
  })

  assert.equal(page.data.list.works[0].coverUrl, 'https://cos.example.com/old-thumb.jpg?v=2026-07-08T17%3A30%3A00%2B08%3A00')
})

test('works page pinch zooms thumbnail crop inside the image editor sheet', () => {
  const page = loadPage('pages/works/works.js', () => Promise.resolve({ works: [], tags: [], summary: {} }))
  const cropState = buildWorkThumbnailCropState({
    path: 'wxfile://tmp/photo.jpg',
    width: 1600,
    height: 900
  }, {
    key: '1:1',
    width: 1,
    height: 1
  }, {
    cropBoxWidth: 320
  })
  page.setData({
    imageEditSheetVisible: true,
    thumbnailCropVisible: true,
    thumbnailCropState: cropState
  })

  page.handleThumbnailCropTouchStart({
    touches: [
      { clientX: 100, clientY: 140 },
      { clientX: 220, clientY: 140 }
    ]
  })
  page.handleThumbnailCropTouchMove({
    touches: [
      { clientX: 70, clientY: 140 },
      { clientX: 250, clientY: 140 }
    ]
  })

  assert.equal(page.data.imageEditSheetVisible, true)
  assert.equal(page.data.thumbnailCropVisible, true)
  assert.ok(page.data.thumbnailCropState.scale > cropState.scale)
  assert.ok(page.data.thumbnailCropState.displayWidth > cropState.displayWidth)
})

test('works page uses current thumbnail ratio for original-ratio crop option', async () => {
  const imageInfoCalls = []
  const page = loadPage('pages/works/works.js', () => Promise.resolve({ works: [], tags: [], summary: {} }), {
    getWindowInfo() {
      return { windowWidth: 375 }
    },
    getSystemInfoSync() {
      throw new Error('不应调用已废弃的 wx.getSystemInfoSync')
    },
    getImageInfo(options) {
      imageInfoCalls.push(options.src)
      options.success({
        path: options.src,
        width: 400,
        height: 300
      })
    }
  })
  page.data.list.works = [
    {
      id: 17,
      mediaType: 'IMAGE',
      title: '露营',
      description: '',
      originalFileName: 'photo.jpg',
      mediaUrl: 'https://cos.example.com/WF/work/image/photo.jpg',
      coverUrl: 'https://cos.example.com/WF/work/image/photo-thumb.jpg',
      width: 1600,
      height: 900
    }
  ]

  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '17'
      }
    }
  })
  await page.handleOpenThumbnailCrop()

  assert.deepEqual(imageInfoCalls, ['https://cos.example.com/WF/work/image/photo-thumb.jpg'])
  assert.equal(page.data.thumbnailRatioOptions[0].key, 'original')
  assert.equal(page.data.thumbnailRatioOptions[0].width, 4)
  assert.equal(page.data.thumbnailRatioOptions[0].height, 3)
  assert.equal(page.data.thumbnailCropState.ratioWidth, 4)
  assert.equal(page.data.thumbnailCropState.ratioHeight, 3)
  assert.equal(
    page.data.thumbnailCropState.cropBoxHeight,
    page.data.thumbnailCropState.cropBoxWidth * 3 / 4
  )
})

test('works page writes applied thumbnail crop to a unique local preview file', async () => {
  const copyCalls = []
  const drawCalls = []
  const canvas = {
    getContext() {
      return {
        clearRect() {},
        drawImage(...args) {
          drawCalls.push(args)
        }
      }
    },
    createImage() {
      const image = {}
      setImmediate(() => {
        image.onload()
      })
      return image
    }
  }
  const page = loadPage('pages/works/works.js', () => Promise.resolve({ works: [], tags: [], summary: {} }), {
    env: {
      USER_DATA_PATH: 'wxfile://user'
    },
    getFileSystemManager() {
      return {
        statSync(filePath) {
          assert.equal(filePath, 'wxfile://tmp/cropped-thumb.jpg')
          return { size: 96000 }
        },
        copyFile(options) {
          copyCalls.push(options)
          options.success()
        }
      }
    },
    createSelectorQuery() {
      return {
        in() {
          return this
        },
        select(selector) {
          assert.equal(selector, '#workThumbnailCropCanvas')
          return this
        },
        fields(options) {
          assert.deepEqual(options, { node: true, size: true })
          return this
        },
        exec(callback) {
          callback([{ node: canvas }])
        }
      }
    },
    canvasToTempFilePath(options) {
      assert.equal(options.canvas, canvas)
      options.success({ tempFilePath: 'wxfile://tmp/cropped-thumb.jpg' })
    }
  })
  page.setData({
    imageEditForm: {
      id: 17,
      clientId: 'edit-work-17',
      originalFileName: 'photo.jpg'
    },
    thumbnailPreviewRenderFlip: false,
    thumbnailCropVisible: true,
    thumbnailCropState: buildWorkThumbnailCropState({
      path: 'wxfile://tmp/photo.jpg',
      width: 1600,
      height: 900
    }, {
      key: '1:1',
      width: 1,
      height: 1
    }, {
      cropBoxWidth: 320
    })
  })

  await page.handleApplyThumbnailCrop()

  assert.equal(drawCalls.length, 1)
  assert.equal(copyCalls.length, 1)
  assert.equal(copyCalls[0].srcPath, 'wxfile://tmp/cropped-thumb.jpg')
  assert.match(copyCalls[0].destPath, /^wxfile:\/\/user\/work-thumbnail-preview-17-\d+\.jpg$/)
  assert.equal(page.data.imageEditForm.thumbnailPreviewPath, copyCalls[0].destPath)
  assert.equal(page.data.imageEditForm.thumbnailEditedPath, copyCalls[0].destPath)
  assert.equal(page.data.imageEditForm.thumbnailPreparedFile.filePath, copyCalls[0].destPath)
  assert.equal(page.data.imageEditForm.thumbnailEdited, true)
  assert.equal(page.data.thumbnailPreviewRenderFlip, true)
  assert.equal(page.data.thumbnailCropVisible, false)
})

test('works page opens video edit sheet and saves video text without downloading remote media', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.method === 'PUT') {
      return Promise.resolve({
        work: {
          id: 18,
          title: '新标题',
          description: '新说明'
        },
        references: []
      })
    }
    return Promise.resolve({ works: [], tags: [], summary: {} })
  }
  let navigateToCount = 0
  let downloadCount = 0
  const page = loadPage('pages/works/works.js', fakeRequest, {
    navigateTo() {
      navigateToCount += 1
    },
    downloadFile() {
      downloadCount += 1
    }
  })
  page.data.list.works = [
    {
      id: 18,
      mediaType: 'VIDEO',
      title: '旧标题',
      description: '旧说明',
      originalFileName: 'film.mp4',
      mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
      coverUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film-thumb.jpg',
      durationMs: 60000,
      durationText: '01:00'
    }
  ]

  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '18'
      }
    }
  })
  assert.equal(page.data.videoEditSheetVisible, true)
  assert.equal(page.data.imageEditSheetVisible, false)
  assert.equal(page.data.videoEditForm.coverEditorReady, false)
  assert.deepEqual(page.data.videoEditFieldCounters, {
    title: '3/30',
    description: '3/1000'
  })
  page.handleVideoEditInput({
    currentTarget: {
      dataset: {
        field: 'title'
      }
    },
    detail: {
      value: ' 新标题 '
    }
  })
  page.handleVideoEditInput({
    currentTarget: {
      dataset: {
        field: 'description'
      }
    },
    detail: {
      value: ' 新说明 '
    }
  })
  assert.deepEqual(page.data.videoEditFieldCounters, {
    title: '5/30',
    description: '5/1000'
  })
  await page.handleConfirmVideoEdit()

  assert.equal(navigateToCount, 0)
  assert.equal(downloadCount, 0)
  assert.equal(requests[0].url, '/api/mine/works/18')
  assert.equal(requests[0].method, 'PUT')
  assert.deepEqual(requests[0].data, {
    title: '新标题',
    description: '新说明'
  })
  assert.equal(page.data.videoEditSheetVisible, false)
})

test('works page previews video from remote url without downloading full file', async () => {
  const fakeRequest = () => Promise.resolve({ works: [], tags: [], summary: {} })
  let downloadCount = 0
  const page = loadPage('pages/works/works.js', fakeRequest, {
    downloadFile() {
      downloadCount += 1
    }
  })
  page.data.list.works = [
    {
      id: 18,
      mediaType: 'VIDEO',
      title: '片头快剪',
      mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
      coverUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film-thumb.jpg'
    }
  ]

  page.handlePlayVideoTap({
    currentTarget: {
      dataset: {
        id: '18'
      }
    }
  })

  assert.equal(downloadCount, 0)
  assert.equal(page.data.videoPreviewVisible, true)
  assert.deepEqual(page.data.videoPreview, {
    src: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
    poster: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film-thumb.jpg',
    title: '片头快剪'
  })
  assert.equal(page.data.videoEditSheetVisible, false)

  page.handleCloseVideoPreview()

  assert.equal(page.data.videoPreviewVisible, false)
  assert.equal(page.data.videoPreview, null)
})

test('works page previews original media from cover tap without opening edit sheet', () => {
  const fakeRequest = () => Promise.resolve({ works: [], tags: [], summary: {} })
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.list.works = [
    {
      id: 17,
      mediaType: 'IMAGE',
      title: '海边仪式',
      mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/image/photo.jpg',
      coverUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/image/photo-thumb.jpg'
    },
    {
      id: 18,
      mediaType: 'IMAGE',
      title: '山谷晨雾',
      mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/image/mist.jpg',
      coverUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/image/mist-thumb.jpg'
    },
    {
      id: 19,
      mediaType: 'VIDEO',
      title: '片头快剪',
      mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
      coverUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film-thumb.jpg'
    }
  ]

  assert.equal(typeof page.handleWorkPreviewTap, 'function')
  page.handleWorkPreviewTap({
    currentTarget: {
      dataset: {
        id: '17'
      }
    }
  })

  assert.equal(page.data.imagePreviewVisible, true)
  assert.deepEqual(page.data.imagePreview, {
    src: 'https://cos.we-folio.dingchenyong.top/WF/work/image/photo.jpg',
    title: '海边仪式'
  })
  assert.equal(page.data.imageEditSheetVisible, false)
  assert.equal(page.data.videoEditSheetVisible, false)

  page.handleCloseImagePreview()

  assert.equal(page.data.imagePreviewVisible, false)
  assert.equal(page.data.imagePreview, null)

  page.handleWorkPreviewTap({
    currentTarget: {
      dataset: {
        id: '19'
      }
    }
  })

  assert.equal(page.data.videoPreviewVisible, true)
  assert.deepEqual(page.data.videoPreview, {
    src: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
    poster: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film-thumb.jpg',
    title: '片头快剪'
  })
  assert.equal(page.data.imageEditSheetVisible, false)
  assert.equal(page.data.videoEditSheetVisible, false)
})

test('mock works page locks card body while cover tap previews original media', () => {
  const fakeRequest = () => Promise.resolve({})
  const toasts = []
  const page = loadPage('pages/mock/works/works.js', fakeRequest, {
    showToast(options) {
      toasts.push(options)
    }
  })

  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '101'
      }
    }
  })

  assert.equal(toasts[0].title, '请去“我的”页面注册登录')
  assert.equal(typeof page.handleWorkPreviewTap, 'function')

  page.handleWorkPreviewTap({
    currentTarget: {
      dataset: {
        id: '103'
      }
    }
  })

  assert.equal(page.data.imagePreviewVisible, true)
  assert.match(page.data.imagePreview.src, /demo-image-3\.jpg$/)
  assert.equal(page.data.imagePreview.title, '风景图片 3')
  assert.equal(page.data.videoPreviewVisible, false)

  page.handleCloseImagePreview()

  assert.equal(page.data.imagePreviewVisible, false)
  assert.equal(page.data.imagePreview, null)

  page.handleWorkPreviewTap({
    currentTarget: {
      dataset: {
        id: '107'
      }
    }
  })

  assert.equal(page.data.videoPreviewVisible, true)
  assert.deepEqual(page.data.videoPreview, {
    title: '风景视频 1',
    src: 'https://cdn2.we-folio.dingchenyong.top/demo/demo-video-1.mp4',
    poster: 'https://cdn2.we-folio.dingchenyong.top/demo/demo-video-1-thumb.jpg'
  })
})

test('works page starts remote video frame selection without downloading the video', async () => {
  const fakeRequest = () => Promise.resolve({})
  const downloadUrls = []
  const page = loadPage('pages/works/works.js', fakeRequest, {
    downloadFile(options) {
      downloadUrls.push(options.url)
      options.success({
        statusCode: 200,
        tempFilePath: 'wxfile://tmp/film.mp4'
      })
    }
  })
  page.data.videoEditForm = {
    id: 18,
    isVideo: true,
    mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
    tempFilePath: '',
    localVideoPath: ''
  }

  await page.handleStartVideoCoverEdit()

  assert.equal(page.data.videoEditForm.coverEditorReady, true)
  assert.deepEqual(downloadUrls, [])
  assert.equal(page.data.videoEditForm.tempFilePath, 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4')
  assert.equal(page.data.videoEditForm.localVideoPath, '')
})

test('works page marks current frame time for backend snapshot without local decoding', async () => {
  const fakeRequest = () => Promise.resolve({})
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.videoEditForm = {
    id: 18,
    clientId: 'edit-work-18',
    isVideo: true,
    mediaType: 'VIDEO',
    fileName: 'film.mp4',
    mediaUrl: 'wxfile://tmp/film.mp4',
    tempFilePath: 'wxfile://tmp/film.mp4',
    localVideoPath: 'wxfile://tmp/film.mp4',
    durationMs: 5000,
    coverFrameTimeMs: 1800
  }
  page.data.videoFrameTimeMs = 1800

  await page.handleExportVideoCover()

  assert.equal(page.data.videoEditErrorText, '')
  assert.equal(page.data.videoEditForm.coverFrameSelected, true)
  assert.equal(page.data.videoEditForm.coverFrameTimeMs, 1800)
  assert.equal(page.data.videoEditForm.customCoverPath, undefined)
})

test('works page saves selected video cover frame time directly', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({
      work: {
        id: 18,
        title: '片头快剪',
        description: '新封面'
      },
      references: []
    })
  }
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.videoEditForm = {
    id: 18,
    clientId: 'edit-work-18',
    isVideo: true,
    mediaType: 'VIDEO',
    title: ' 片头快剪 ',
    description: ' 新封面 ',
    originalFileName: 'film.mp4',
    fileName: 'film.mp4',
    mediaUrl: 'https://cos.we-folio.dingchenyong.top/WF/work/video/film.mp4',
    coverFrameSelected: true,
    coverFrameTimeMs: 5200,
    width: 1080,
    height: 1920
  }

  await page.handleConfirmVideoEdit()

  assert.deepEqual(requests.map((request) => request.url), [
    '/api/mine/works/18',
    '/api/mine/works'
  ])
  assert.deepEqual(requests[0].data, {
    title: '片头快剪',
    description: '新封面',
    coverFrameTimeMs: 5200,
    width: 1080,
    height: 1920
  })
})

test('works page blocks delete when backend reports portfolio references', async () => {
  const requests = []
  const modals = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/18/delete-check') {
      return Promise.resolve({
        canDelete: false,
        referenceCount: 2,
        message: '作品已被 2 个作品集引用，请先从作品集中移除'
      })
    }
    return Promise.resolve({})
  }
  const page = loadPage('pages/works/works.js', fakeRequest, {
    showModal(options) {
      modals.push(options)
    }
  })
  page.data.list.works = [
    {
      id: 18,
      mediaType: 'VIDEO',
      title: '片头快剪',
      referenceCount: 2
    }
  ]
  page.data.revealedWorkId = 18

  await page.handleDeleteWorkTap({
    currentTarget: {
      dataset: {
        id: '18'
      }
    }
  })

  assert.deepEqual(requests.map((request) => request.url), [
    '/api/mine/works/18/delete-check'
  ])
  assert.equal(modals[0].title, '无法删除')
  assert.equal(modals[0].content, '作品已被 2 个作品集引用，请先从作品集中移除')
  assert.equal(page.data.revealedWorkId, null)
})

test('works page confirms delete then refreshes work list', async () => {
  const requests = []
  const modals = []
  const toasts = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/18/delete-check') {
      return Promise.resolve({
        canDelete: true,
        referenceCount: 0,
        message: '作品未被作品集引用，可以删除'
      })
    }
    if (options.url === '/api/mine/works/delete/18') {
      return Promise.resolve({})
    }
    return Promise.resolve({
      works: [],
      tags: [],
      summary: {},
      page: 1,
      pageSize: 20,
      hasMore: false
    })
  }
  const page = loadPage('pages/works/works.js', fakeRequest, {
    showModal(options) {
      modals.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.loading = false
  page.data.list.works = [
    {
      id: 18,
      mediaType: 'IMAGE',
      title: '海边仪式',
      referenceCount: 0
    }
  ]

  await page.handleDeleteWorkTap({
    currentTarget: {
      dataset: {
        id: '18'
      }
    }
  })
  assert.equal(modals[0].title, '删除作品')
  assert.equal(modals[0].confirmText, '删除')
  await modals[0].success({ confirm: true })
  await flushPromises()

  assert.deepEqual(requests.map((request) => `${request.method || 'GET'} ${request.url}`), [
    'GET /api/mine/works/18/delete-check',
    'POST /api/mine/works/delete/18',
    'GET /api/mine/works'
  ])
  assert.equal(toasts[0].title, '作品已删除')
  assert.equal(page.data.deletingWorkId, null)
  assert.equal(page.data.revealedWorkId, null)
  assert.equal(page.data.list.empty, true)
})

test('works page confirms audit resubmit, prevents duplicate requests, and refreshes list', async () => {
  const requests = []
  const modals = []
  const toasts = []
  const resubmit = deferred()
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/18/audit-resubmit') {
      return resubmit.promise
    }
    return Promise.resolve({
      works: [],
      tags: [],
      summary: {},
      page: 1,
      pageSize: 20,
      hasMore: false
    })
  }
  const page = loadPage('pages/works/works.js', fakeRequest, {
    showModal(options) {
      modals.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.list.works = [{
    id: 18,
    mediaType: 'IMAGE',
    title: '海边仪式',
    auditStatus: 'REJECTED',
    canResubmitAudit: true,
    remainingAuditResubmitCount: 2
  }]

  page.handleAuditResubmitTap({ currentTarget: { dataset: { id: '18' } } })

  assert.equal(modals[0].title, '重新提交审核')
  assert.equal(modals[0].confirmText, '重新审核')
  const submitPromise = modals[0].success({ confirm: true })
  await flushPromises()
  assert.equal(page.data.resubmittingWorkId, 18)
  assert.deepEqual(requests.map((item) => `${item.method || 'GET'} ${item.url}`), [
    'POST /api/mine/works/18/audit-resubmit'
  ])

  page.handleAuditResubmitTap({ currentTarget: { dataset: { id: '18' } } })
  assert.equal(modals.length, 1)

  resubmit.resolve({ auditStatus: 'AUDITING', auditRound: 3 })
  await submitPromise
  await flushPromises()

  assert.deepEqual(requests.map((item) => `${item.method || 'GET'} ${item.url}`), [
    'POST /api/mine/works/18/audit-resubmit',
    'GET /api/mine/works'
  ])
  assert.equal(toasts[0].title, '已重新提交审核')
  assert.equal(page.data.resubmittingWorkId, null)
  assert.equal(page.data.list.empty, true)
})

test('works page cancels audit resubmit and refreshes after backend rejection', async () => {
  const requests = []
  const modals = []
  const toasts = []
  let rejectResubmit = false
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/19/audit-resubmit' && rejectResubmit) {
      return Promise.reject(new Error('作品审核状态已变化，请刷新后重试'))
    }
    return Promise.resolve({ works: [], tags: [], summary: {}, page: 1, pageSize: 20, hasMore: false })
  }
  const page = loadPage('pages/works/works.js', fakeRequest, {
    showModal(options) {
      modals.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.list.works = [{
    id: 19,
    mediaType: 'VIDEO',
    title: '晚宴快剪',
    auditStatus: 'FAILED',
    canResubmitAudit: true,
    remainingAuditResubmitCount: 1
  }]

  page.handleAuditResubmitTap({ currentTarget: { dataset: { id: '19' } } })
  await modals[0].success({ confirm: false })
  assert.equal(requests.length, 0)

  rejectResubmit = true
  page.handleAuditResubmitTap({ currentTarget: { dataset: { id: '19' } } })
  await modals[1].success({ confirm: true })
  await flushPromises()

  assert.deepEqual(requests.map((item) => `${item.method || 'GET'} ${item.url}`), [
    'POST /api/mine/works/19/audit-resubmit',
    'GET /api/mine/works'
  ])
  assert.equal(toasts[0].title, '作品审核状态已变化，请刷新后重试')
  assert.equal(page.data.resubmittingWorkId, null)
})

test('works page batch mode toggles selection instead of opening editors', async () => {
  const fakeRequest = () => Promise.resolve({})
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.list.works = [
    {
      id: 17,
      mediaType: 'IMAGE',
      title: '海边仪式'
    }
  ]

  page.handleBatchTap()
  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '17'
      }
    }
  })

  assert.equal(page.data.batchMode, true)
  assert.deepEqual(page.data.selectedWorkIds, [17])
  assert.equal(page.data.imageEditSheetVisible, false)
  assert.equal(page.data.videoEditSheetVisible, false)

  page.handleWorkTap({
    currentTarget: {
      dataset: {
        id: '17'
      }
    }
  })

  assert.deepEqual(page.data.selectedWorkIds, [])
})

test('works page batch select all toggles loaded works', async () => {
  const fakeRequest = () => Promise.resolve({})
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.batchMode = true
  page.data.list.works = [
    {
      id: 17,
      mediaType: 'IMAGE',
      title: '海边仪式'
    },
    {
      id: 18,
      mediaType: 'VIDEO',
      title: '片头快剪'
    }
  ]

  page.handleSelectAllBatchTap()

  assert.deepEqual(page.data.selectedWorkIds, [17, 18])
  assert.equal(page.data.batchSelectedCountText, '2 已选')
  assert.equal(page.data.batchSelectAllText, '取消全选')
  assert.equal(page.data.list.works[0].selected, true)
  assert.equal(page.data.list.works[1].selected, true)

  page.handleSelectAllBatchTap()

  assert.deepEqual(page.data.selectedWorkIds, [])
  assert.equal(page.data.batchSelectedCountText, '0 已选')
  assert.equal(page.data.batchSelectAllText, '全选')
  assert.equal(page.data.list.works[0].selected, false)
  assert.equal(page.data.list.works[1].selected, false)
})

test('works page batch delete checks references then deletes allowed works', async () => {
  const requests = []
  const modals = []
  const toasts = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/delete-check') {
      return Promise.resolve({
        total: 2,
        deletableCount: 1,
        blockedCount: 1,
        items: [
          { workId: 17, canDelete: true, referenceCount: 0, message: '作品未被作品集引用，可以删除' },
          { workId: 18, canDelete: false, referenceCount: 2, message: '作品已被 2 个作品集引用，请先从作品集中移除' }
        ]
      })
    }
    if (options.url === '/api/mine/works/delete') {
      return Promise.resolve({
        successCount: 1,
        failedCount: 1,
        items: [
          { workId: 17, success: true, message: '已删除' },
          { workId: 18, success: false, message: '作品已被 2 个作品集引用，请先从作品集中移除' }
        ]
      })
    }
    return Promise.resolve({
      works: [],
      tags: [],
      summary: {},
      page: 1,
      pageSize: 20,
      hasMore: false
    })
  }
  const page = loadPage('pages/works/works.js', fakeRequest, {
    showModal(options) {
      modals.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.loading = false
  page.data.batchMode = true
  page.data.selectedWorkIds = [17, 18]

  await page.handleBatchDeleteTap()
  assert.equal(modals[0].title, '部分作品无法删除')
  assert.equal(modals[0].confirmText, '删除可删项')
  await modals[0].success({ confirm: true })
  await flushPromises()

  assert.deepEqual(requests.map((request) => `${request.method || 'GET'} ${request.url}`), [
    'POST /api/mine/works/delete-check',
    'POST /api/mine/works/delete',
    'GET /api/mine/works'
  ])
  assert.deepEqual(requests[0].data, { workIds: [17, 18] })
  assert.deepEqual(requests[1].data, { workIds: [17] })
  assert.equal(toasts[0].title, '已删除 1 个作品')
  assert.equal(page.data.batchMode, false)
  assert.deepEqual(page.data.selectedWorkIds, [])
})

test('works page drags tag scoped sort order then saves', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/sort-items') {
      return Promise.resolve({
        scope: 'TAG',
        tagId: 31,
        total: 2,
        works: [
          { id: 12, title: '片头快剪', mediaType: 'VIDEO', sortOrder: 1000 },
          { id: 11, title: '草坪婚礼', mediaType: 'IMAGE', sortOrder: 3000 }
        ]
      })
    }
    if (options.url === '/api/mine/works/sort') {
      return Promise.resolve({})
    }
    return Promise.resolve({
      works: [],
      tags: [{ id: 31, name: '高端婚礼', color: '#0f766e', count: 2 }],
      summary: {},
      page: 1,
      pageSize: 20,
      hasMore: false
    })
  }
  const page = loadPage('pages/works/works.js', fakeRequest)
  page.data.loading = false
  page.data.keyword = ''
  page.data.selectedTagId = 31
  page.data.list.tags = [{ id: 31, name: '高端婚礼', color: '#0f766e', count: 2 }]

  await page.handleOpenSortMode()
  page.handleSortDragStart({
    currentTarget: {
      dataset: {
        index: '1'
      }
    },
    touches: [
      { clientY: 220 }
    ]
  })
  page.handleSortDragMove({
    currentTarget: {
      dataset: {
        index: '0'
      }
    },
    touches: [
      { clientY: 120 }
    ]
  })
  assert.equal(page.data.sortDraggingWorkId, 11)
  assert.match(page.data.sortDragStyle, /translate3d\(0,\s*-100px,\s*0\)/)
  page.handleSortDragEnd()
  assert.equal(page.data.sortDragStyle, '')
  await page.handleSaveSort()
  await flushPromises()

  assert.deepEqual(requests.map((request) => `${request.method || 'GET'} ${request.url}`), [
    'GET /api/mine/works/sort-items',
    'POST /api/mine/works/sort',
    'GET /api/mine/works'
  ])
  assert.deepEqual(requests[0].data, { scope: 'TAG', tagId: 31 })
  assert.deepEqual(requests[1].data, {
    scope: 'TAG',
    tagId: 31,
    items: [
      { workId: 11, sortOrder: 1000 },
      { workId: 12, sortOrder: 2000 }
    ]
  })
  assert.deepEqual(page.data.sortWorks.map((work) => work.id), [])
  assert.equal(page.data.sortDraggingWorkId, null)
  assert.equal(page.data.sortMode, false)
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
