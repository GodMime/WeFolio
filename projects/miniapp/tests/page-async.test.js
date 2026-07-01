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
  const page = loadPage('pages/work-add/work-add.js', fakeRequest)
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

test('work add keeps video cover generation on backend first frame', async () => {
  const fakeRequest = () => Promise.resolve({})
  const page = loadPage('pages/work-add/work-add.js', fakeRequest)
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
    '/api/mine/works/18'
  ])
  assert.deepEqual(requests[0].data, {
    title: '片头快剪',
    description: '新封面',
    coverFrameTimeMs: 5200,
    width: 1080,
    height: 1920
  })
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
