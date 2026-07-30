const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const UTILS_ROOT = path.resolve(__dirname, '../pages/team-portfolios/utils')

function load(name) {
  const modulePath = path.join(UTILS_ROOT, name)
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

function createImageWxApi(fileSizes, compressedPath) {
  return {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          if (!Object.prototype.hasOwnProperty.call(fileSizes, filePath)) throw new Error(`missing stat: ${filePath}`)
          return { size: fileSizes[filePath] }
        }
      }
    },
    compressImage({ success }) {
      success({ tempFilePath: compressedPath })
    }
  }
}

test('resolves shareCode directly and decodes scene compatibility values', () => {
  const { resolveTeamShareCode } = load('team-visitor-session.js')
  assert.equal(resolveTeamShareCode({ shareCode: ' TEAM-1 ' }), 'TEAM-1')
  assert.equal(resolveTeamShareCode({ scene: encodeURIComponent('shareCode=TEAM-2') }), 'TEAM-2')
  assert.equal(resolveTeamShareCode({ scene: 'TEAM-3' }), 'TEAM-3')
})

test('opens and refreshes only the team visitor session endpoint', async () => {
  const { openTeamVisitorSession, requestWithTeamVisitorSessionRefresh } = load('team-visitor-session.js')
  const calls = []
  const requestFn = async (options) => {
    calls.push(options)
    if (options.url.endsWith('/schedule-options') && calls.filter((item) => item.url.endsWith('/schedule-options')).length === 1) {
      throw Object.assign(new Error('expired'), { authRequired: true })
    }
    if (options.url.endsWith('/open')) return { token: 'visitor-token', expiresInSeconds: 3600, visitRecordId: 6 }
    return { ok: true }
  }
  const wxApi = { login: ({ success }) => success({ code: 'wx-code' }), setStorageSync() {} }
  await openTeamVisitorSession({ shareCode: 'TEAM-4', requestFn, wxApi, sourceType: 'WECHAT_SHARE_CARD', idempotencyKey: 'open-1' })
  const refreshed = []
  const result = await requestWithTeamVisitorSessionRefresh({
    shareCode: 'TEAM-4',
    requestFn,
    wxApi,
    onRefresh: (session) => refreshed.push(session),
    requestOptions: { url: '/api/visitor/team-portfolios/TEAM-4/schedule-options', data: { componentKey: 'schedule-1' } }
  })
  assert.deepEqual(result, { ok: true })
  assert.equal(calls.filter((item) => item.url.endsWith('/open')).length, 2)
  assert.ok(calls.filter((item) => item.url.endsWith('/open')).every((item) => item.data.idempotencyKey))
  assert.equal(refreshed.length, 1)
  assert.equal(refreshed[0].visitRecordId, 6)
  assert.ok(calls.every((item) => item.url.startsWith('/api/visitor/team-portfolios/')))
})

test('team timeline refresh reuses anonymous session id without wx login', async () => {
  const { openTeamVisitorSession, requestWithTeamVisitorSessionRefresh } = load('team-visitor-session.js')
  const calls = []
  let loginCalls = 0
  const anonymousSessionId = 'timeline-abc123def456ghi789jkl012mno345pqr678'
  const requestFn = async (options) => {
    calls.push(options)
    if (options.url.endsWith('/schedule-options') && calls.filter((item) => item.url.endsWith('/schedule-options')).length === 1) {
      throw Object.assign(new Error('expired'), { authRequired: true })
    }
    if (options.url.endsWith('/open')) return { token: 'timeline-token', expiresInSeconds: 3600 }
    return { ok: true }
  }
  const wxApi = { login() { loginCalls += 1 }, setStorageSync() {} }

  await openTeamVisitorSession({ shareCode: 'TEAM-4', anonymousSessionId, requestFn, wxApi })
  await requestWithTeamVisitorSessionRefresh({
    shareCode: 'TEAM-4',
    anonymousSessionId,
    requestFn,
    wxApi,
    requestOptions: { url: '/api/visitor/team-portfolios/TEAM-4/schedule-options' }
  })

  const openCalls = calls.filter((item) => item.url.endsWith('/open'))
  assert.equal(openCalls.length, 2)
  assert.equal(loginCalls, 0)
  assert.ok(openCalls.every((item) => item.data.anonymousSessionId === anonymousSessionId))
  assert.ok(openCalls.every((item) => !Object.hasOwn(item.data, 'loginCode')))
})

test('team timeline open ignores unavailable local storage', async () => {
  const { openTeamVisitorSession } = load('team-visitor-session.js')

  const session = await openTeamVisitorSession({
    shareCode: 'TEAM-4',
    anonymousSessionId: 'timeline-abc123def456ghi789jkl012mno345pqr678',
    requestFn: async () => ({ token: 'timeline-token', expiresInSeconds: 3600 }),
    wxApi: {
      setStorageSync() {
        throw new Error('single-page storage unavailable')
      }
    }
  })

  assert.equal(session.token, 'timeline-token')
})

test('refresh hook rebuilds visitor request options with a new profile token', async () => {
  const { requestWithTeamVisitorSessionRefresh } = load('team-visitor-session.js')
  const calls = []
  await requestWithTeamVisitorSessionRefresh({
    shareCode: 'TEAM-PROFILE', sourceType: 'WECHAT_SHARE_CARD',
    wxApi: { login: ({ success }) => success({ code: 'wx-code' }), setStorageSync() {} },
    requestFn: async (options) => {
      calls.push(options)
      if (options.url.endsWith('/visitor-profile') && calls.filter((item) => item.url.endsWith('/visitor-profile')).length === 1) throw { authRequired: true }
      if (options.url.endsWith('/open')) return { token: 'new-token', visitorProfileToken: 'new-profile-token' }
      return { ok: true }
    },
    requestOptions: { url: '/api/visitor/team-portfolios/TEAM-PROFILE/visitor-profile', method: 'PUT', data: { visitorProfileToken: 'old-profile-token' } },
    refreshRequestOptions: (session, original) => Object.assign({}, original, { data: Object.assign({}, original.data, { visitorProfileToken: session.visitorProfileToken }) })
  })
  assert.equal(calls.filter((item) => item.url.endsWith('/visitor-profile'))[1].data.visitorProfileToken, 'new-profile-token')
})

test('generates a non-empty idempotency key when opening has no explicit key', async () => {
  const { openTeamVisitorSession } = load('team-visitor-session.js')
  const calls = []
  await openTeamVisitorSession({
    shareCode: 'TEAM-KEY',
    requestFn: async (options) => { calls.push(options); return { token: 'token' } },
    wxApi: { login: ({ success }) => success({ code: 'wx-code' }), setStorageSync() {} }
  })
  assert.match(calls[0].data.idempotencyKey, /^team-open-/)
  await assert.rejects(() => openTeamVisitorSession({
    shareCode: 'TEAM-KEY',
    idempotencyKey: 'x'.repeat(65),
    requestFn: async () => { throw new Error('不应请求') },
    wxApi: { login: ({ success }) => success({ code: 'wx-code' }), setStorageSync() {} }
  }), /幂等键/)
})

test('visitor normalization keeps team theme and menu components independent', () => {
  const { normalizeTeamVisitorPortfolio, switchTeamPortfolioMenu } = load('team-visitor-portfolio.js')
  const privateData = { members: [{ status: 'AVAILABLE' }], arbitrary: { nested: true } }
  const normalized = normalizeTeamVisitorPortfolio({
    shareCode: 'A', portfolioId: '4', teamId: '5', title: ' 团队页 ', visitRecordId: '6',
    renderData: {
      style: { backgroundColor: '#151515', themeMode: 'dark' },
      components: [
        { componentKey: 'later', componentType: 'SCHEDULE_QUERY', sortOrder: 2, data: privateData },
        { componentKey: 'first', componentType: 'TEAM_PROFILE', sortOrder: 1, data: { team: {} } }
      ],
      bottomNav: {
        enabled: true,
        items: [
          { key: 'nav_home', title: '首页' },
          { key: 'nav_contact', title: '联系', components: [
            { componentKey: 'contact', componentType: 'CONTACT_FORM', sortOrder: 1, data: { title: '联系' } }
          ] }
        ]
      }
    }
  })
  assert.equal(normalized.portfolioId, 4)
  assert.equal(normalized.title, '团队页')
  assert.equal(normalized.themeMode, 'dark')
  assert.equal(normalized.style.backgroundColor, '#151515')
  assert.equal(normalized.activeMenuKey, 'nav_home')
  assert.deepEqual(normalized.components.map((item) => item.componentKey), ['first', 'later'])
  assert.equal(normalized.components[1].data, privateData)
  const contact = switchTeamPortfolioMenu(normalized, 'nav_contact')
  assert.equal(contact.activeMenuKey, 'nav_contact')
  assert.deepEqual(contact.activeComponents.map((item) => item.componentKey), ['contact'])
})

test('team single-work animation uses work-viewed semantics', () => {
  const { buildTeamSingleWorkViewEvent } = load('team-visitor-portfolio.js')

  assert.deepEqual(buildTeamSingleWorkViewEvent({
    componentKey: 'single-1',
    work: { workId: 21, mediaType: 'ANIMATION' }
  }), {
    eventType: 'WORK_VIEWED',
    componentKey: 'single-1',
    workId: 21,
    mediaType: 'ANIMATION'
  })
  assert.equal(buildTeamSingleWorkViewEvent({
    componentKey: 'single-1',
    work: { workId: 21, mediaType: 'VIDEO' }
  }), null)
  assert.equal(buildTeamSingleWorkViewEvent({
    componentKey: 'single-1',
    work: { workId: 21, mediaType: 'AUDIO' }
  }), null)
})

test('event and schedule helpers call team visitor endpoints with visitor auth', async () => {
  const { submitTeamVisitorEvent, queryTeamVisitorSchedule } = load('team-visitor-portfolio.js')
  const calls = []
  const requestFn = async (options) => { calls.push(options); return { status: 'TEAM_PARTIAL_AVAILABLE' } }
  await submitTeamVisitorEvent(requestFn, 'SHARE', { eventType: 'QR_CODE_INTERACTED', componentKey: 'qr-1', action: 'CLICK', idempotencyKey: 'event-1' })
  await queryTeamVisitorSchedule(requestFn, 'SHARE', { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'query-1' })
  assert.deepEqual(calls.map((item) => [item.url, item.method, item.authMode]), [
    ['/api/visitor/team-portfolios/SHARE/events', 'POST', 'visitor'],
    ['/api/visitor/team-portfolios/SHARE/schedule-query', 'POST', 'visitor']
  ])
  await assert.rejects(
    () => submitTeamVisitorEvent(requestFn, 'SHARE', { eventType: 'QR_CODE_INTERACTED', componentKey: 'qr-1', action: 'PREVIEW', idempotencyKey: 'event-2' }),
    /二维码交互动作无效/
  )
  const beforeInvalidCount = calls.length
  await assert.rejects(() => submitTeamVisitorEvent(requestFn, 'SHARE', { eventType: 'QR_CODE_INTERACTED', componentKey: 'qr-1', action: 'CLICK', idempotencyKey: ' ' }), /幂等键/)
  await assert.rejects(() => queryTeamVisitorSchedule(requestFn, 'SHARE', { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'x'.repeat(65) }), /幂等键/)
  assert.equal(calls.length, beforeInvalidCount)
})

test('visitor profile uses team ticket and profile endpoints', async () => {
  const { uploadTeamVisitorProfile } = load('team-visitor-profile.js')
  const calls = []
  const uploaded = await uploadTeamVisitorProfile({
    shareCode: 'TEAM-5',
    avatarPath: '/tmp/avatar.jpg',
    nickname: '  新访客  ',
    visitorProfileToken: 'profile-token',
    wxApi: createImageWxApi({ '/tmp/avatar.jpg': 100 }),
    requestFn: async (options) => {
      calls.push(options)
      if (options.url.endsWith('/upload-ticket')) return { uploadUrl: 'https://cos/upload', publicUrl: 'https://cdn/avatar.jpg', formData: {} }
      return null
    },
    uploadFn: async () => undefined,
    fileInfo: { mimeType: 'image/jpeg', fileSize: 100 }
  })
  assert.deepEqual(uploaded, { nickname: '新访客', avatarUrl: 'https://cdn/avatar.jpg' })
  assert.deepEqual(calls.map((item) => [item.url, item.method]), [
    ['/api/visitor/team-portfolios/TEAM-5/visitor-avatar/upload-ticket', 'POST'],
    ['/api/visitor/team-portfolios/TEAM-5/visitor-profile', 'PUT']
  ])
  assert.deepEqual(calls[0].data, {
    visitorProfileToken: 'profile-token',
    mimeType: 'image/jpeg',
    fileSize: 100
  })
  assert.deepEqual(calls[1].data, {
    visitorProfileToken: 'profile-token',
    nickname: '新访客',
    avatarUrl: 'https://cdn/avatar.jpg'
  })
  assert.equal(calls[1].data.nickname, '新访客')
})

test('visitor avatar ticket uses final stat when caller omits fileInfo size', async () => {
  const { uploadTeamVisitorProfile } = load('team-visitor-profile.js')
  const calls = []
  await uploadTeamVisitorProfile({
    shareCode: 'TEAM-STAT',
    avatarPath: '/tmp/avatar.png',
    nickname: '访客',
    visitorProfileToken: 'profile-token',
    wxApi: createImageWxApi({ '/tmp/avatar.png': 256 }),
    requestFn: async (options) => {
      calls.push(options)
      return options.url.endsWith('/upload-ticket')
        ? { uploadUrl: 'https://cos/upload', publicUrl: 'https://cdn/avatar.png', formData: {} }
        : null
    },
    uploadFn: async () => undefined
  })
  assert.equal(calls[0].data.fileSize, 256)
  assert.equal(calls[0].data.mimeType, 'image/png')
})

test('visitor avatar ticket follows compressed file stat and MIME', async () => {
  const { uploadTeamVisitorProfile } = load('team-visitor-profile.js')
  const calls = []
  const uploads = []
  await uploadTeamVisitorProfile({
    shareCode: 'TEAM-COMPRESS',
    avatarPath: '/tmp/original.png',
    nickname: '访客',
    visitorProfileToken: 'profile-token',
    fileInfo: { mimeType: 'image/png', fileSize: 240 * 1024 },
    wxApi: createImageWxApi({ '/tmp/original.png': 240 * 1024, '/tmp/compressed.jpg': 80 * 1024 }, '/tmp/compressed.jpg'),
    requestFn: async (options) => {
      calls.push(options)
      return options.url.endsWith('/upload-ticket')
        ? { uploadUrl: 'https://cos/upload', publicUrl: 'https://cdn/avatar.jpg', formData: {} }
        : null
    },
    uploadFn: async (options) => uploads.push(options)
  })
  assert.equal(calls[0].data.fileSize, 80 * 1024)
  assert.equal(calls[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].filePath, '/tmp/compressed.jpg')
})

test('visitor avatar rejects a compressed file still over 200KB before signing', async () => {
  const { uploadTeamVisitorProfile } = load('team-visitor-profile.js')
  let requestCount = 0
  let uploadCount = 0
  await assert.rejects(() => uploadTeamVisitorProfile({
    shareCode: 'TEAM-LARGE',
    avatarPath: '/tmp/original.png',
    nickname: '访客',
    visitorProfileToken: 'profile-token',
    wxApi: createImageWxApi({ '/tmp/original.png': 240 * 1024, '/tmp/compressed.jpg': 200 * 1024 + 1 }, '/tmp/compressed.jpg'),
    requestFn: async () => { requestCount += 1 },
    uploadFn: async () => { uploadCount += 1 }
  }), /200KB/)
  assert.equal(requestCount, 0)
  assert.equal(uploadCount, 0)
})
