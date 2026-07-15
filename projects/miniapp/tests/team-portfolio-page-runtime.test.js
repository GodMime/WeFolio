const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../pages/team-portfolios')
const REQUEST_PATH = path.resolve(ROOT, '../../utils/request.js')
const TEAM_LIST_UTILITY_PATH = path.resolve(ROOT, 'utils/team-portfolio-list.js')
const TEAM_UTILITY_PATH = path.resolve(ROOT, 'utils/team-portfolios.js')

function flush() { return new Promise((resolve) => setImmediate(resolve)) }
function clone(value) { return JSON.parse(JSON.stringify(value)) }

function loadPage(relativePath, requestFn, wxOverrides = {}, globals = {}) {
  const pagePath = path.join(ROOT, relativePath)
  const requestKey = require.resolve(REQUEST_PATH)
  const teamListUtilityKey = require.resolve(TEAM_LIST_UTILITY_PATH)
  const teamUtilityKey = require.resolve(TEAM_UTILITY_PATH)
  const oldRequest = require.cache[requestKey]
  const oldTeamListUtility = require.cache[teamListUtilityKey]
  const oldTeamUtility = require.cache[teamUtilityKey]
  const oldPage = global.Page
  const oldWx = global.wx
  const oldGetCurrentPages = global.getCurrentPages
  let definition
  require.cache[requestKey] = { id: REQUEST_PATH, filename: REQUEST_PATH, loaded: true, exports: { request: requestFn } }
  delete require.cache[teamListUtilityKey]
  delete require.cache[teamUtilityKey]
  global.Page = (value) => { definition = value }
  global.wx = Object.assign({ navigateTo() {}, navigateBack() {}, redirectTo() {}, showToast() {}, showModal({ success }) { success({ confirm: true }) }, stopPullDownRefresh() {}, previewImage() {}, login({ success }) { success({ code: 'code' }) }, setStorageSync() {}, chooseMedia() {} }, wxOverrides)
  global.getCurrentPages = globals.getCurrentPages || (() => [])
  delete require.cache[require.resolve(pagePath)]
  try { require(pagePath) } finally {
    global.Page = oldPage
    if (oldRequest) require.cache[requestKey] = oldRequest
    else delete require.cache[requestKey]
    if (oldTeamListUtility) require.cache[teamListUtilityKey] = oldTeamListUtility
    else delete require.cache[teamListUtilityKey]
    if (oldTeamUtility) require.cache[teamUtilityKey] = oldTeamUtility
    else delete require.cache[teamUtilityKey]
  }
  const page = Object.assign({}, definition, {
    data: clone(definition.data),
    setData(patch) { Object.assign(this.data, patch) },
    selectComponent() { return null },
    cleanup() { global.wx = oldWx; global.getCurrentPages = oldGetCurrentPages }
  })
  return page
}

test('component library precomputes disabled state and its WXML contains no method invocation', async () => {
  const page = loadPage('component-library/team-portfolio-component-library.js', async () => [{ componentType: 'TEAM_PROFILE', name: '团队资料' }, { componentType: 'TEXT_SECTION', name: '文字' }])
  const channel = { on(name, callback) { if (name === 'existingTypes') callback(['TEAM_PROFILE']) } }
  page.getOpenerEventChannel = () => channel
  try {
    page.onLoad()
    await flush()
    assert.deepEqual(page.data.components.map((item) => item.disabled), [true, false])
    assert.doesNotMatch(fs.readFileSync(path.join(ROOT, 'component-library/team-portfolio-component-library.wxml'), 'utf8'), /\{\{[^}]*\.[A-Za-z]+\(/)
  } finally { page.cleanup() }
})

test('team picker confirms an unsaved editor without creating a portfolio', async () => {
  const requests = []
  const navigations = []
  const page = loadPage('team-select/team-select.js', async (options) => {
    requests.push(options)
    return { portfolioId: 41 }
  }, { navigateTo(value) { navigations.push(value) } })
  page.setData({ selectedTeamId: 7 })
  try {
    await page.handleConfirm()
    assert.deepEqual(requests, [])
    assert.deepEqual(navigations, [{
      url: '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit?teamId=7'
    }])
  } finally { page.cleanup() }
})

test('new editor creates only on first save and then uses draft save', async () => {
  const requests = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/teams/3') {
      return { team: { teamId: 3, teamName: '甲团队', avatarUrl: 'https://cdn.example/team.png', intro: '团队简介' } }
    }
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return {
        portfolioId: 41,
        ownerId: 3,
        draftRevision: 1,
        publicationStatus: 'DRAFT_ONLY',
        config: options.data.config
      }
    }
    if (options.url === '/api/mine/team-portfolios/41/draft') {
      return { portfolioId: 41, draftRevision: requests.filter((item) => item.url === options.url).length + 1, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  })
  try {
    page.onLoad({ teamId: '3' })
    await flush()
    await flush()
    assert.deepEqual(requests.map((item) => item.url), ['/api/mine/teams/3'])
    assert.equal(page.data.portfolioId, 0)
    assert.equal(page.data.canMaintain, true)
    assert.equal(page.data.config.components.length, 1)
    assert.equal(page.data.config.components[0].componentType, 'TEAM_PROFILE')
    assert.deepEqual(page.data.config.components[0].config.team, {
      teamId: 3,
      teamName: '甲团队',
      avatarUrl: 'https://cdn.example/team.png',
      intro: '团队简介'
    })
    assert.equal(page.data.componentValidation[page.data.config.components[0].componentKey], true)

    await page.handleSaveTap()
    assert.equal(requests[1].url, '/api/mine/teams/3/portfolios/standard')
    assert.equal(requests[1].method, 'POST')
    assert.equal(requests[1].data.config.schemaVersion, 'standard-team-v1')
    assert.equal(requests[1].data.config.share.coverUrl, '')
    assert.equal(requests[2].url, '/api/mine/team-portfolios/41/draft')
    assert.equal(requests[2].data.clientRevision, 1)
    assert.equal(page.data.portfolioId, 41)
    assert.equal(page.data.draftRevision, 2)

    await page.handleSaveTap()
    assert.equal(requests[3].url, '/api/mine/team-portfolios/41/draft')
    assert.equal(requests[3].method, 'POST')
    assert.equal(requests[3].data.clientRevision, 2)
    assert.equal(page.data.draftRevision, 3)
  } finally { page.cleanup() }
})

test('save draft returns to the portfolio list after a new portfolio is created', async () => {
  const navigations = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return { portfolioId: 41, ownerId: 3, draftRevision: 1, publicationStatus: 'DRAFT_ONLY', config: options.data.config }
    }
    if (options.url === '/api/mine/team-portfolios/41/draft') {
      return { portfolioId: 41, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    navigateBack(options) { navigations.push({ type: 'back', options }) },
    redirectTo(options) { navigations.push({ type: 'redirect', options }) }
  }, {
    getCurrentPages: () => [
      { route: 'pages/portfolios/portfolios' },
      { route: 'pages/team-portfolios/team-select/team-select' },
      { route: 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit' }
    ]
  })
  page.setData({ teamId: 3, canMaintain: true })
  try {
    await page.handleSaveTap()
    assert.deepEqual(navigations, [{ type: 'back', options: { delta: 2 } }])
  } finally { page.cleanup() }
})

test('save draft returns to the portfolio list after an existing portfolio is updated', async () => {
  const navigations = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url === '/api/mine/team-portfolios/41/draft') {
      return { portfolioId: 41, draftRevision: 3, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    navigateBack(options) { navigations.push({ type: 'back', options }) },
    redirectTo(options) { navigations.push({ type: 'redirect', options }) }
  }, {
    getCurrentPages: () => [
      { route: 'pages/portfolios/portfolios' },
      { route: 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit' }
    ]
  })
  page.setData({ portfolioId: 41, teamId: 3, draftRevision: 2, canMaintain: true })
  try {
    await page.handleSaveTap()
    assert.deepEqual(navigations, [{ type: 'back', options: { delta: 1 } }])
  } finally { page.cleanup() }
})

test('save draft redirects to the team portfolio list when no list page exists in the stack', async () => {
  const navigations = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url === '/api/mine/team-portfolios/41/draft') {
      return { portfolioId: 41, draftRevision: 3, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    navigateBack(options) { navigations.push({ type: 'back', options }) },
    redirectTo(options) { navigations.push({ type: 'redirect', options }) }
  }, {
    getCurrentPages: () => [
      { route: 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit' }
    ]
  })
  page.setData({ portfolioId: 41, teamId: 3, draftRevision: 2, canMaintain: true })
  try {
    await page.handleSaveTap()
    assert.deepEqual(navigations, [{
      type: 'redirect',
      options: { url: '/pages/team-portfolios/portfolios' }
    }])
  } finally { page.cleanup() }
})

test('save draft remains in the editor when the request fails', async () => {
  const navigations = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => {
    throw new Error('network')
  }, {
    navigateBack(options) { navigations.push({ type: 'back', options }) },
    redirectTo(options) { navigations.push({ type: 'redirect', options }) }
  }, {
    getCurrentPages: () => [
      { route: 'pages/portfolios/portfolios' },
      { route: 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit' }
    ]
  })
  page.setData({ portfolioId: 41, teamId: 3, draftRevision: 2, canMaintain: true })
  try {
    await page.handleSaveTap()
    assert.deepEqual(navigations, [])
  } finally { page.cleanup() }
})

test('new editor publish creates before publishing', async () => {
  const requests = []
  const modals = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return { portfolioId: 41, ownerId: 3, draftRevision: 1, publicationStatus: 'DRAFT_ONLY', config: options.data.config }
    }
    if (options.url === '/api/mine/team-portfolios/41/draft') {
      return { portfolioId: 41, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    }
    if (options.url === '/api/mine/team-portfolios/41/publish') {
      return { publicationStatus: 'PUBLISHED', publishedRevision: 1 }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    }
  })
  page.setData({ teamId: 3, canMaintain: true })
  try {
    await page.handlePublishTap()
    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/teams/3/portfolios/standard',
      '/api/mine/team-portfolios/41/draft',
      '/api/mine/team-portfolios/41/publish'
    ])
    assert.equal(requests[2].data.draftRevision, 2)
    assert.equal(modals.length, 1)
    assert.equal(modals[0].title, '发布免责声明')
    assert.equal(page.data.portfolioId, 41)
    assert.equal(page.data.publicationStatus, 'PUBLISHED')
  } finally { page.cleanup() }
})

test('canceling editor publish disclaimer does not create save or publish', async () => {
  const requests = []
  const modals = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    return {}
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: false })
    }
  })
  page.setData({ teamId: 3, canMaintain: true })

  try {
    await page.handlePublishTap()

    assert.equal(modals.length, 1)
    assert.equal(modals[0].title, '发布免责声明')
    assert.deepEqual(requests, [])
    assert.equal(page.data.publishing, false)
  } finally { page.cleanup() }
})

test('unsaved editor blocks preview and member-source requests that need a portfolio id', async () => {
  const requests = []
  const navigations = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    return []
  }, { navigateTo(value) { navigations.push(value) } })
  page.setData({ teamId: 3, canMaintain: true, portfolioId: 0 })
  try {
    page.handlePreviewTap()
    await page.handleCarouselLoadMembers({ currentTarget: { dataset: { key: 'carousel-1' } } })
    assert.deepEqual(navigations, [])
    assert.deepEqual(requests, [])

    const wxml = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.wxml'), 'utf8')
    assert.match(wxml, /class="cover-preview \{\{!canMaintain \|\| shareCoverUploading \? 'disabled' : ''\}\}"[^>]*bindtap="handleCoverChoose"/)
    assert.match(wxml, /handlePreviewTap[^>]*disabled="\{\{!canMaintain \|\| !portfolioId \|\| saving \|\| publishing\}\}"/)
  } finally { page.cleanup() }
})

test('new editor keeps a selected cover local until create, upload, and draft save', async () => {
  const requests = []
  const uploads = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return { portfolioId: 51, ownerId: 3, draftRevision: 1, publicationStatus: 'DRAFT_ONLY', config: options.data.config }
    }
    if (options.url === '/api/mine/team-portfolios/51/asset/upload-ticket') {
      return { uploadUrl: 'https://cos.example/upload', publicUrl: 'https://cdn.example/team-cover.jpg', formData: { key: 'team-cover' } }
    }
    if (options.url === '/api/mine/team-portfolios/51/draft') {
      return { portfolioId: 51, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'http://tmp/team-cover.jpg' }] }) },
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile(options) { uploads.push(clone({ url: options.url, filePath: options.filePath, name: options.name, formData: options.formData })); options.success({ statusCode: 204 }) }
  })
  page.setData({ teamId: 3, canMaintain: true })
  try {
    await page.handleCoverChoose()
    assert.equal(page.data.config.share.coverUrl, 'http://tmp/team-cover.jpg')
    assert.deepEqual(requests, [])

    await page.handleSaveTap()
    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/teams/3/portfolios/standard',
      '/api/mine/team-portfolios/51/asset/upload-ticket',
      '/api/mine/team-portfolios/51/draft'
    ])
    assert.equal(requests[0].data.config.share.coverUrl, '')
    assert.equal(requests[2].data.config.share.coverUrl, 'https://cdn.example/team-cover.jpg')
    assert.equal(page.data.config.share.coverUrl, 'https://cdn.example/team-cover.jpg')
    assert.deepEqual(uploads, [{ url: 'https://cos.example/upload', filePath: 'http://tmp/team-cover.jpg', name: 'file', formData: { key: 'team-cover' } }])
  } finally { page.cleanup() }
})

test('new editor keeps a QR local until create, COS upload, and draft save', async () => {
  const requests = []
  const uploads = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return { portfolioId: 81, ownerId: 3, draftRevision: 1, publicationStatus: 'DRAFT_ONLY', config: options.data.config }
    }
    if (options.url === '/api/mine/team-portfolios/81/asset/upload-ticket') {
      return { uploadUrl: 'https://cos.example/team-qr', publicUrl: 'https://cdn.example/team-qr.png', formData: { key: 'team-qr' } }
    }
    if (options.url === '/api/mine/team-portfolios/81/draft') {
      return { portfolioId: 81, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile(options) { uploads.push(clone({ url: options.url, filePath: options.filePath, name: options.name, formData: options.formData })); options.success({ statusCode: 204 }) }
  })
  page.setData({
    teamId: 3,
    canMaintain: true,
    config: {
      schemaVersion: 'standard-team-v1',
      share: {},
      components: [{ componentKey: 'qr-1', componentType: 'QR_CONTACT', sortOrder: 0, enabled: true, config: { qrUrlSource: 'CUSTOM', qrUrl: 'wxfile://tmp/team-qr.png' } }]
    },
    componentValidation: { 'qr-1': true }
  })
  try {
    await page.handleSaveTap()
    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/teams/3/portfolios/standard',
      '/api/mine/team-portfolios/81/asset/upload-ticket',
      '/api/mine/team-portfolios/81/draft'
    ])
    assert.deepEqual(requests[0].data.config.components, [])
    assert.equal(requests[1].data.assetType, 'QR_CONTACT')
    assert.equal(requests[2].data.config.components[0].config.qrUrl, 'https://cdn.example/team-qr.png')
    assert.equal(page.data.config.components[0].config.qrUrl, 'https://cdn.example/team-qr.png')
    assert.deepEqual(uploads, [{ url: 'https://cos.example/team-qr', filePath: 'wxfile://tmp/team-qr.png', name: 'file', formData: { key: 'team-qr' } }])
  } finally { page.cleanup() }
})

test('existing editor skips a remote QR when saving', async () => {
  const requests = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/team-portfolios/82/draft') return { portfolioId: 82, draftRevision: 3, publicationStatus: 'DRAFT_ONLY' }
    throw new Error(`unexpected request: ${options.url}`)
  })
  page.setData({
    portfolioId: 82,
    teamId: 3,
    canMaintain: true,
    draftRevision: 2,
    config: { schemaVersion: 'standard-team-v1', share: {}, components: [{ componentKey: 'qr-1', componentType: 'QR_CONTACT', sortOrder: 0, enabled: true, config: { qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn.example/remote-qr.png' } }] },
    componentValidation: { 'qr-1': true }
  })
  try {
    await page.handleSaveTap()
    assert.deepEqual(requests.map((item) => item.url), ['/api/mine/team-portfolios/82/draft'])
  } finally { page.cleanup() }
})

test('QR upload failure keeps the local image and does not save the draft', async () => {
  const requests = []
  const toasts = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url.endsWith('/asset/upload-ticket')) throw new Error('network')
    if (options.url.endsWith('/draft')) return { portfolioId: 83, draftRevision: 3, publicationStatus: 'DRAFT_ONLY' }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    showToast(value) { toasts.push(value) }
  })
  page.setData({
    portfolioId: 83,
    teamId: 3,
    canMaintain: true,
    draftRevision: 2,
    config: { schemaVersion: 'standard-team-v1', share: {}, components: [{ componentKey: 'qr-1', componentType: 'QR_CONTACT', sortOrder: 0, enabled: true, config: { qrUrlSource: 'CUSTOM', qrUrl: 'wxfile://tmp/retry-qr.png' } }] },
    componentValidation: { 'qr-1': true }
  })
  try {
    await page.handleSaveTap()
    assert.deepEqual(requests.map((item) => item.url), ['/api/mine/team-portfolios/83/asset/upload-ticket'])
    assert.equal(page.data.config.components[0].config.qrUrl, 'wxfile://tmp/retry-qr.png')
    assert.equal(toasts.at(-1).title, '素材上传失败，请重试')
  } finally { page.cleanup() }
})

test('team profile avatar selection stays local until create, upload, and draft save', async () => {
  const requests = []
  const appliedAvatars = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return { portfolioId: 71, ownerId: 3, draftRevision: 1, publicationStatus: 'DRAFT_ONLY', config: options.data.config }
    }
    if (options.url === '/api/mine/team-portfolios/71/asset/upload-ticket') {
      return { uploadUrl: 'https://cos.example/profile-avatar', publicUrl: 'https://cdn.example/team-profile-avatar.jpg', formData: {} }
    }
    if (options.url === '/api/mine/team-portfolios/71/draft') {
      return { portfolioId: 71, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'wxfile://tmp/team-profile-avatar.jpg' }] }) },
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  page.selectComponent = () => ({ applyAvatar(event) { appliedAvatars.push(event.detail.avatarUrl) } })
  page.setData({
    teamId: 3,
    canMaintain: true,
    activeComponentKey: 'profile-1',
    config: {
      schemaVersion: 'standard-team-v1',
      share: {},
      components: [{
        componentKey: 'profile-1',
        componentType: 'TEAM_PROFILE',
        sortOrder: 0,
        enabled: true,
        config: { team: { teamId: 3, avatarUrl: 'team.png', teamName: '甲团队', intro: '简介' } }
      }]
    },
    componentValidation: { 'profile-1': true }
  })
  try {
    await page.handleTeamProfileChooseAvatar({ currentTarget: { dataset: { key: 'profile-1' } } })
    assert.deepEqual(appliedAvatars, ['wxfile://tmp/team-profile-avatar.jpg'])
    assert.deepEqual(requests, [])

    page.handleComponentSave({
      currentTarget: { dataset: { key: 'profile-1' } },
      detail: { config: { team: { teamId: 3, avatarUrl: 'wxfile://tmp/team-profile-avatar.jpg', teamName: '作品集团队', intro: '作品集简介' } } }
    })
    await page.handleSaveTap()

    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/teams/3/portfolios/standard',
      '/api/mine/team-portfolios/71/asset/upload-ticket',
      '/api/mine/team-portfolios/71/draft'
    ])
    assert.equal(requests[0].data.config.components[0].config.team.avatarUrl, '')
    assert.equal(requests[1].data.assetType, 'TEAM_PROFILE_AVATAR')
    assert.equal(requests[2].data.config.components[0].config.team.avatarUrl, 'https://cdn.example/team-profile-avatar.jpg')
    assert.equal(page.data.config.components[0].config.team.avatarUrl, 'https://cdn.example/team-profile-avatar.jpg')
  } finally { page.cleanup() }
})

test('team profile refresh updates only the child draft and preserves it on failure', async () => {
  let shouldFail = false
  const snapshots = []
  const errors = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url !== '/api/mine/teams/3') throw new Error(`unexpected request: ${options.url}`)
    if (shouldFail) throw new Error('network')
    return { team: { teamId: 3, name: '最新团队', avatarUrl: 'latest.png', intro: '最新简介' } }
  })
  page.selectComponent = () => ({
    applyTeamSnapshot(event) { snapshots.push(event.detail.team) },
    applyRefreshError(event) { errors.push(event.detail.message) }
  })
  page.setData({ teamId: 3, activeComponentKey: 'profile-1' })
  try {
    await page.handleTeamProfileRefresh({ currentTarget: { dataset: { key: 'profile-1' } } })
    assert.deepEqual(snapshots, [{ teamId: 3, teamName: '最新团队', avatarUrl: 'latest.png', intro: '最新简介' }])
    assert.equal(page.data.config.components.length, 0)

    shouldFail = true
    await page.handleTeamProfileRefresh({ currentTarget: { dataset: { key: 'profile-1' } } })
    assert.deepEqual(snapshots, [{ teamId: 3, teamName: '最新团队', avatarUrl: 'latest.png', intro: '最新简介' }])
    assert.deepEqual(errors, ['团队资料刷新失败，请重试'])
  } finally { page.cleanup() }
})

test('existing editor uploads a local team profile avatar on save and skips a remote avatar', async () => {
  const localRequests = []
  const localPage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    localRequests.push(clone(options))
    if (options.url.endsWith('/asset/upload-ticket')) {
      return { uploadUrl: 'https://cos.example/existing-profile', publicUrl: 'https://cdn.example/existing-profile.jpg', formData: {} }
    }
    if (options.url.endsWith('/draft')) return { portfolioId: 72, draftRevision: 4, publicationStatus: 'DRAFT_ONLY' }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  localPage.setData({
    portfolioId: 72,
    teamId: 3,
    canMaintain: true,
    draftRevision: 3,
    config: {
      schemaVersion: 'standard-team-v1',
      share: {},
      components: [{ componentKey: 'profile-1', componentType: 'TEAM_PROFILE', sortOrder: 0, enabled: true, config: { team: { teamId: 3, avatarUrl: 'wxfile://tmp/existing-profile.jpg', teamName: '作品集团队', intro: '' } } }]
    },
    componentValidation: { 'profile-1': true }
  })
  try {
    await localPage.handleSaveTap()
    assert.deepEqual(localRequests.map((item) => item.url), [
      '/api/mine/team-portfolios/72/asset/upload-ticket',
      '/api/mine/team-portfolios/72/draft'
    ])
    assert.equal(localRequests[0].data.assetType, 'TEAM_PROFILE_AVATAR')
    assert.equal(localRequests[1].data.config.components[0].config.team.avatarUrl, 'https://cdn.example/existing-profile.jpg')
  } finally { localPage.cleanup() }

  const remoteRequests = []
  const remotePage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    remoteRequests.push(clone(options))
    return { portfolioId: 73, draftRevision: 5, publicationStatus: 'DRAFT_ONLY' }
  })
  remotePage.setData({
    portfolioId: 73,
    teamId: 3,
    canMaintain: true,
    draftRevision: 4,
    config: {
      schemaVersion: 'standard-team-v1',
      share: {},
      components: [{ componentKey: 'profile-1', componentType: 'TEAM_PROFILE', sortOrder: 0, enabled: true, config: { team: { teamId: 3, avatarUrl: 'https://cdn.example/remote-profile.jpg', teamName: '作品集团队', intro: '' } } }]
    },
    componentValidation: { 'profile-1': true }
  })
  try {
    await remotePage.handleSaveTap()
    assert.deepEqual(remoteRequests.map((item) => item.url), ['/api/mine/team-portfolios/73/draft'])
  } finally { remotePage.cleanup() }
})

test('team cover crop resolves a renderable image path before opening the sheet', async () => {
  const imageInfoSources = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), {
    chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'http://tmp/raw-cover.png' }] }) },
    getImageInfo({ src, success }) {
      imageInfoSources.push(src)
      success({ path: 'wxfile://resolved-cover.png', width: 1200, height: 900 })
    },
    cropImage() {}
  })
  page.setData({ teamId: 3, canMaintain: true })
  try {
    await page.handleCoverChoose()
    assert.deepEqual(imageInfoSources, ['http://tmp/raw-cover.png'])
    assert.equal(page.data.shareCoverCropVisible, true)
    assert.equal(page.data.shareCoverCropPath, 'wxfile://resolved-cover.png')
  } finally { page.cleanup() }
})

test('new editor preserves a local cover and reuses the created portfolio when upload retries', async () => {
  const requests = []
  let ticketAttempts = 0
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url === '/api/mine/teams/3/portfolios/standard') {
      return { portfolioId: 52, ownerId: 3, draftRevision: 1, publicationStatus: 'DRAFT_ONLY', config: options.data.config }
    }
    if (options.url === '/api/mine/team-portfolios/52/asset/upload-ticket') {
      ticketAttempts += 1
      if (ticketAttempts === 1) throw new Error('network')
      return { uploadUrl: 'https://cos.example/retry', publicUrl: 'https://cdn.example/retry.jpg', formData: {} }
    }
    if (options.url === '/api/mine/team-portfolios/52/draft') {
      return { portfolioId: 52, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  page.setData({ teamId: 3, canMaintain: true, config: { schemaVersion: 'standard-team-v1', share: { coverUrl: 'wxfile://tmp/retry.jpg' }, components: [] } })
  try {
    await page.handleSaveTap()
    assert.equal(page.data.portfolioId, 52)
    assert.equal(page.data.config.share.coverUrl, 'wxfile://tmp/retry.jpg')
    assert.equal(requests.filter((item) => item.url.endsWith('/draft')).length, 0)

    await page.handleSaveTap()
    assert.equal(requests.filter((item) => item.url === '/api/mine/teams/3/portfolios/standard').length, 1)
    assert.equal(requests.filter((item) => item.url.endsWith('/asset/upload-ticket')).length, 2)
    assert.equal(requests.filter((item) => item.url.endsWith('/draft')).length, 1)
    assert.equal(page.data.config.share.coverUrl, 'https://cdn.example/retry.jpg')
  } finally { page.cleanup() }
})

test('editor skips remote cover upload and publishes only after local cover upload and draft save', async () => {
  const remoteRequests = []
  const remotePage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    remoteRequests.push(clone(options))
    return { portfolioId: 61, draftRevision: 4, publicationStatus: 'DRAFT_ONLY' }
  })
  remotePage.setData({ portfolioId: 61, teamId: 3, canMaintain: true, draftRevision: 3, config: { schemaVersion: 'standard-team-v1', share: { coverUrl: 'https://cdn.example/existing.jpg' }, components: [] } })
  try {
    await remotePage.handleSaveTap()
    assert.deepEqual(remoteRequests.map((item) => item.url), ['/api/mine/team-portfolios/61/draft'])
  } finally { remotePage.cleanup() }

  const publishRequests = []
  const publishPage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    publishRequests.push(clone(options))
    if (options.url.endsWith('/asset/upload-ticket')) return { uploadUrl: 'https://cos.example/publish', publicUrl: 'https://cdn.example/publish.jpg', formData: {} }
    if (options.url.endsWith('/draft')) return { portfolioId: 62, draftRevision: 5, publicationStatus: 'DRAFT_ONLY' }
    if (options.url.endsWith('/publish')) return { portfolioId: 62, draftRevision: 5, publishedRevision: 5, publicationStatus: 'PUBLISHED' }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  publishPage.setData({ portfolioId: 62, teamId: 3, canMaintain: true, draftRevision: 4, config: { schemaVersion: 'standard-team-v1', share: { coverUrl: 'wxfile://tmp/publish.jpg' }, components: [] } })
  try {
    await publishPage.handlePublishTap()
    assert.deepEqual(publishRequests.map((item) => item.url), [
      '/api/mine/team-portfolios/62/asset/upload-ticket',
      '/api/mine/team-portfolios/62/draft',
      '/api/mine/team-portfolios/62/publish'
    ])
  } finally { publishPage.cleanup() }
})

test('editor keeps the remote cover after draft save fails and does not upload it again', async () => {
  const requests = []
  let draftAttempts = 0
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(clone(options))
    if (options.url.endsWith('/asset/upload-ticket')) return { uploadUrl: 'https://cos.example/once', publicUrl: 'https://cdn.example/once.jpg', formData: {} }
    if (options.url.endsWith('/draft')) {
      draftAttempts += 1
      if (draftAttempts === 1) throw new Error('network')
      return { portfolioId: 63, draftRevision: 6, publicationStatus: 'DRAFT_ONLY' }
    }
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  page.setData({ portfolioId: 63, teamId: 3, canMaintain: true, draftRevision: 5, config: { schemaVersion: 'standard-team-v1', share: { coverUrl: 'wxfile://tmp/once.jpg' }, components: [] } })
  try {
    await page.handleSaveTap()
    assert.equal(page.data.config.share.coverUrl, 'https://cdn.example/once.jpg')
    await page.handleSaveTap()
    assert.equal(requests.filter((item) => item.url.endsWith('/asset/upload-ticket')).length, 1)
    assert.equal(requests.filter((item) => item.url.endsWith('/draft')).length, 2)
  } finally { page.cleanup() }
})

test('editor loads member-first sources, blocks a new component until save, and releases library lock on return', async () => {
  const requests = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    if (options.url === '/api/mine/team-portfolios/7') return { portfolioId: 7, ownerId: 3, draftRevision: 2, config: { components: [{ componentKey: 'carousel-1', componentType: 'CAROUSEL', sortOrder: 0, config: { items: [] } }] } }
    if (options.url.endsWith('/components/carousel/members')) return [{ memberUserId: 8, displayName: '甲' }]
    if (options.url.endsWith('/components/carousel/members/8/works')) return [{ workId: 9, title: '作品' }]
    return { draftRevision: 3 }
  })
  try {
    page.onLoad({ portfolioId: '7' }); await flush(); await flush()
    await page.handleCarouselLoadMembers({ currentTarget: { dataset: { key: 'carousel-1' } } })
    await page.handleCarouselMemberChange({ currentTarget: { dataset: { key: 'carousel-1' } }, detail: { memberUserId: 8 } })
    assert.equal(page.data.componentSources['carousel-1'].members[0].memberUserId, 8)
    assert.equal(page.data.componentSources['carousel-1'].works[0].workId, 9)
    page.addComponent('TEXT_SECTION')
    const newKey = page.data.config.components[1].componentKey
    assert.equal(page.data.componentValidation[newKey], false)
    page.handleComponentSave({ currentTarget: { dataset: { key: newKey } }, detail: { config: { content: '可保存', alignment: 'LEFT' } } })
    assert.equal(page.data.componentValidation[newKey], true)
    page.setData({ openingLibrary: true }); page.onShow(); assert.equal(page.data.openingLibrary, false)
  } finally { page.cleanup() }
})

test('visitor modal contact opens, completes child submission, and schedule success resolves the child', async () => {
  const calls = []
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async (options) => { calls.push(options); if (options.url.endsWith('/contact-leads')) return { leadId: 1 }; if (options.url.endsWith('/schedule-query')) return { status: 'TEAM_AVAILABLE' }; return {} })
  const completed = []
  const schedule = []
  page.selectComponent = (selector) => selector.includes('contact-form') ? { completeSubmit(value) { completed.push(value) } } : { resolveQuery(value) { schedule.push(['resolve', value]) }, rejectQuery(value) { schedule.push(['reject', value]) } }
  page.setData({ shareCode: 'S', visitRecordId: 1, componentBuckets: { contact: [{ componentKey: 'contact-1' }] }, contactModalVisible: {}, contactSubmitting: {}, contactForms: {} })
  try {
    page.handleContactOpen({ currentTarget: { dataset: { key: 'contact-1' } } })
    assert.equal(page.data.contactModalVisible['contact-1'], true)
    await page.handleContactSubmit({ currentTarget: { dataset: { key: 'contact-1' } }, detail: { form: { contactName: '客户', phone: '1' } } })
    assert.deepEqual(completed, [{ detail: { success: true } }])
    await page.handleScheduleQuery({ currentTarget: { dataset: { key: 'schedule-1' } }, detail: { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'query-1' } })
    assert.equal(schedule[0][0], 'resolve')
  } finally { page.cleanup() }
})

test('visitor schedule failure performs a request and rejects the child', async () => {
  const requests = []
  const rejected = []
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async (options) => { requests.push(options); throw new Error('network') })
  page.selectComponent = () => ({ rejectQuery(value) { rejected.push(value) } })
  page.setData({ shareCode: 'S', sourceType: 'WECHAT_SHARE_CARD', scheduleResults: {} })
  try {
    await page.handleScheduleQuery({ detail: { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'query-1' } })
    assert.equal(requests.length, 1)
    assert.match(requests[0].url, /\/schedule-query$/)
    assert.deepEqual(rejected, [{ detail: { message: '档期查询失败，请重试', clearPendingIdempotencyKey: false } }])
  } finally { page.cleanup() }
})

test('visitor schedule marks a 400 rejection as safe to abandon while preserving uncertain retry state', async () => {
  const rejected = []
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async () => {
    const error = new Error('invalid')
    error.statusCode = 400
    throw error
  })
  page.selectComponent = () => ({ rejectQuery(value) { rejected.push(value) } })
  page.setData({ shareCode: 'S', sourceType: 'WECHAT_SHARE_CARD', scheduleResults: {} })
  try {
    await page.handleScheduleQuery({ detail: { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'query-1' } })
    assert.deepEqual(rejected, [{ detail: { message: '档期查询失败，请重试', clearPendingIdempotencyKey: true } }])
  } finally { page.cleanup() }
})

test('schedule component triggerEvent detail crosses the visitor WXML binding into the Page handler', async () => {
  const componentPath = path.join(ROOT, 'components/schedule-query/schedule-query.js')
  const oldComponent = global.Component
  let definition
  global.Component = (value) => { definition = value }
  delete require.cache[require.resolve(componentPath)]
  try { require(componentPath) } finally { global.Component = oldComponent }

  const details = []
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async () => ({ status: 'TEAM_AVAILABLE', members: [] }))
  const component = {
    data: Object.assign({}, clone(definition.data), { selectedDate: '2026-08-01', pendingIdempotencyKey: '' }),
    properties: { shareCode: 'S', portfolioId: 0, componentKey: 'schedule-1', preview: false, previewScope: '', config: {} },
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { if (name === 'schedulequery') { details.push(detail); return page.handleScheduleQuery({ detail }) } }
  }
  page.selectComponent = () => ({ resolveQuery(event) { definition.methods.resolveQuery.call(component, event) }, rejectQuery(event) { definition.methods.rejectQuery.call(component, event) } })
  page.setData({ shareCode: 'S', sourceType: 'WECHAT_SHARE_CARD', scheduleResults: {} })
  try {
    assert.match(fs.readFileSync(path.join(ROOT, 'visitor-portfolio/team-visitor-portfolio.wxml'), 'utf8'), /<team-schedule-query[^>]*bindschedulequery="handleScheduleQuery"/)
    definition.methods.submitQuery.call(component)
    await flush()
    assert.equal(details.length, 1)
    assert.equal(details[0].componentKey, 'schedule-1')
    assert.equal(component.data.loading, false)
    assert.equal(component.data.result.status, 'TEAM_AVAILABLE')
  } finally { page.cleanup() }
})

test('legacy team portfolio list redirects to the unified TEAM panel without requesting data', () => {
  const requests = []
  const redirects = []
  const page = loadPage('portfolios.js', async (options) => {
    requests.push(options)
    throw new Error('兼容入口禁止请求接口')
  }, {
    redirectTo(options) {
      redirects.push(options)
    }
  })
  try {
    page.onLoad()
    assert.deepEqual(redirects, [{ url: '/pages/portfolios/portfolios?ownerType=TEAM' }])
    assert.deepEqual(requests, [])
  } finally { page.cleanup() }
})

test('editor invalidates text changes, routes TEAM_PROFILE maintenance, and keeps publish retry on one revision', async () => {
  const requests = []
  const navigations = []
  let publishAttempts = 0
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    if (options.url.endsWith('/draft')) return { draftRevision: 8 }
    if (options.url.endsWith('/publish')) { publishAttempts += 1; if (publishAttempts === 1) throw new Error('network'); return { publicationStatus: 'PUBLISHED', publishedRevision: 8 } }
    return {}
  }, { navigateTo(value) { navigations.push(value) } })
  page.setData({ portfolioId: 7, teamId: 5, canMaintain: true, draftRevision: 7, config: { share: {}, components: [{ componentKey: 'text-1', componentType: 'TEXT_SECTION', sortOrder: 0, config: { content: 'ok' } }] }, componentValidation: { 'text-1': true }, hasInvalidComponents: false })
  try {
    page.handleComponentConfigChange({ currentTarget: { dataset: { key: 'text-1' } }, detail: { field: 'content', value: '' } })
    assert.equal(page.data.componentValidation['text-1'], false)
    page.handleMaintainTeam({ currentTarget: { dataset: { teamId: 5 } } })
    assert.equal(navigations[0].url, '/pages/team-maintenance/team-maintenance?teamId=5')
    page.handleComponentSave({ currentTarget: { dataset: { key: 'text-1' } }, detail: { config: { content: 'ok' } } })
    await page.handlePublishTap(); await page.handlePublishTap()
    const publish = requests.filter((item) => item.url.endsWith('/publish'))
    assert.equal(publish.length, 2); assert.equal(publish[0].data.draftRevision, publish[1].data.draftRevision); assert.equal(publish[0].data.idempotencyKey, publish[1].data.idempotencyKey)
  } finally { page.cleanup() }
})

test('visitor missing share code shows an unavailable toast without redirecting', () => {
  const redirects = []
  const toasts = []
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async () => ({}), { redirectTo(value) { redirects.push(value) }, showToast(value) { toasts.push(value) } })
  try { page.onLoad({}); assert.deepEqual(toasts, [{ title: '暂无权限访问该团队作品集', icon: 'none' }]); assert.deepEqual(redirects, []) } finally { page.cleanup() }
})

test('visitor toasts unavailable event and profile failures but keeps ordinary event failures silent', async () => {
  const redirects = []
  const toasts = []
  const unavailable = async () => { throw new Error('团队当前不可用') }
  const eventPage = loadPage('visitor-portfolio/team-visitor-portfolio.js', unavailable, {
    redirectTo(value) { redirects.push(value) },
    showToast(value) { toasts.push(value) }
  })
  eventPage.setData({ shareCode: 'S', sourceType: 'WECHAT_SHARE_CARD' })
  try {
    await eventPage.sendEvent({ eventType: 'WORK_VIEWED', componentKey: 'carousel-1' })
    assert.deepEqual(toasts.shift(), { title: '当前团队不可用', icon: 'none' })
    assert.deepEqual(redirects, [])
  } finally { eventPage.cleanup() }

  const ordinaryPage = loadPage('visitor-portfolio/team-visitor-portfolio.js', async () => { throw new Error('network') }, {
    redirectTo(value) { redirects.push(value) },
    showToast(value) { toasts.push(value) }
  })
  ordinaryPage.setData({ shareCode: 'S', sourceType: 'WECHAT_SHARE_CARD' })
  try {
    await ordinaryPage.sendEvent({ eventType: 'WORK_VIEWED', componentKey: 'carousel-1' })
    assert.equal(redirects.length, 0)
    assert.equal(toasts.length, 0)
  } finally { ordinaryPage.cleanup() }

  for (const failureStage of ['ticket', 'upload', 'save']) {
    const profileRedirects = []
    const profileToasts = []
    const profilePage = loadPage('visitor-portfolio/team-visitor-portfolio.js', async (options) => {
      if (failureStage === 'ticket' && options.url.endsWith('/visitor-avatar/upload-ticket')) throw new Error('团队不可用')
      if (options.url.endsWith('/visitor-avatar/upload-ticket')) return { uploadUrl: 'https://upload.example/avatar', publicUrl: 'https://cdn.example/avatar.png' }
      if (failureStage === 'save' && options.url.endsWith('/visitor-profile')) throw new Error('团队不可用')
      return {}
    }, {
      getFileSystemManager() { return { statSync() { return { size: 100 } } } },
      uploadFile({ success, fail }) { failureStage === 'upload' ? fail({ errMsg: '团队不可用' }) : success({ statusCode: 204 }) },
      redirectTo(value) { profileRedirects.push(value) },
      showToast(value) { profileToasts.push(value) }
    })
    profilePage.setData({ shareCode: 'S', sourceType: 'WECHAT_SHARE_CARD', visitorProfileToken: 'T', visitorProfileForm: { nickname: '访客', avatarPath: '/tmp/avatar.png' } })
    try {
      await profilePage.handleProfileSave()
      assert.deepEqual(profileToasts, [{ title: '当前团队不可用', icon: 'none' }], failureStage)
      assert.deepEqual(profileRedirects, [], failureStage)
    } finally { profilePage.cleanup() }
  }
})

test('editor preserves a draft team snapshot and reloads a trusted snapshot before re-adding TEAM_PROFILE', async () => {
  const draftPage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url === '/api/mine/team-portfolios/7') return { portfolioId: 7, ownerId: 3, config: { components: [{ componentKey: 'profile-1', componentType: 'TEAM_PROFILE', sortOrder: 0, config: { team: { teamId: 3, teamName: '草稿团队', avatarUrl: 'draft.png', intro: '草稿简介' } } }] } }
    throw new Error(`unexpected request: ${options.url}`)
  })
  draftPage.setData({ portfolioId: 7 })
  try {
    await draftPage.bootstrap()
    draftPage.handleComponentDelete({ currentTarget: { dataset: { key: 'profile-1' } } })
    draftPage.addComponent('TEAM_PROFILE')
    assert.deepEqual(draftPage.data.config.components[0].config.team, { teamId: 3, teamName: '草稿团队', avatarUrl: 'draft.png', intro: '草稿简介' })
  } finally { draftPage.cleanup() }

  const remotePage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url === '/api/mine/team-portfolios/7') return { portfolioId: 7, ownerId: 3, config: { components: [] } }
    if (options.url === '/api/mine/teams/3') return { team: { name: '可信团队', avatarUrl: 'trusted.png', intro: '可信简介' } }
    throw new Error(`unexpected request: ${options.url}`)
  })
  remotePage.setData({ portfolioId: 7 })
  try {
    await remotePage.bootstrap()
    remotePage.addComponent('TEAM_PROFILE')
    const snapshot = remotePage.data.config.components[0].config.team
    assert.deepEqual(snapshot, { teamId: 3, teamName: '可信团队', avatarUrl: 'trusted.png', intro: '可信简介' })
    const componentPath = path.join(ROOT, 'components/team-profile/team-profile.js')
    const previous = global.Component
    let profileExports
    global.Component = () => {}
    delete require.cache[require.resolve(componentPath)]
    try { profileExports = require(componentPath) } finally { if (previous === undefined) delete global.Component; else global.Component = previous }
    assert.equal(profileExports.validateTeamProfile(snapshot).valid, true)
  } finally { remotePage.cleanup() }
})

test('team editor picker, component sheet, swipe delete, and drag reorder use isolated page state', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.setData({
    canMaintain: true,
    config: {
      schemaVersion: 'standard-team-v1',
      share: {},
      components: [
        { componentKey: 'profile-1', componentType: 'TEAM_PROFILE', sortOrder: 0, config: { team: { teamId: 3, teamName: '甲团队' } } },
        { componentKey: 'text-1', componentType: 'TEXT_SECTION', sortOrder: 1, config: { content: '说明', alignment: 'LEFT' } }
      ]
    },
    componentValidation: { 'profile-1': true, 'text-1': true }
  })
  try {
    page.handleOpenComponentSheet()
    assert.equal(page.data.componentSheetVisible, true)
    page.handleSelectComponent({ currentTarget: { dataset: { type: 'DIVIDER' } } })
    assert.equal(page.data.componentSheetVisible, false)
    assert.equal(page.data.config.components[2].componentType, 'DIVIDER')

    page.handleComponentTap({ currentTarget: { dataset: { key: 'text-1', type: 'TEXT_SECTION' } } })
    assert.equal(page.data.textSectionSheetVisible, true)
    assert.equal(page.data.textSectionEditingComponentKey, 'text-1')
    assert.equal(page.data.componentEditorVisible, false)
    page.handleCloseTextSectionSheet()
    assert.equal(page.data.textSectionSheetVisible, false)

    page.handleComponentDragStart({ currentTarget: { dataset: { index: 0, key: 'profile-1' } }, touches: [{ clientY: 100 }] })
    page.handleComponentTouchMove({ currentTarget: { dataset: { index: 0, key: 'profile-1' } }, touches: [{ clientX: 30, clientY: 220 }] })
    page.handleComponentTouchEnd({ currentTarget: { dataset: { index: 0, key: 'profile-1' } }, changedTouches: [{ clientX: 30, clientY: 220 }] })
    assert.equal(page.data.config.components[1].componentKey, 'profile-1')

    page.setData({ revealedComponentKey: 'text-1' })
    page.handleRemoveComponent({ currentTarget: { dataset: { key: 'text-1' } } })
    assert.equal(page.data.config.components.some((item) => item.componentKey === 'text-1'), false)
    assert.equal(page.data.revealedComponentKey, '')
  } finally { page.cleanup() }
})

test('team page level component editor confirms through the active child component', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  let saveCount = 0
  page.selectComponent = (selector) => {
    assert.equal(selector, '#active-component-editor')
    return { saveEdit() { saveCount += 1 } }
  }
  try {
    page.handleConfirmComponentEditor()
    assert.equal(saveCount, 1)
  } finally { page.cleanup() }
})

test('team contact form sheet edits display mode without replacing team-only config', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.data.config = {
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{
      componentKey: 'contact-1',
      componentType: 'CONTACT_FORM',
      sortOrder: 0,
      enabled: true,
      config: { title: '团队咨询', description: '请留下联系方式', fields: ['contactName', 'phone'], displayMode: 'MODAL_FORM' }
    }]
  }

  try {
    page.openContactFormSheet('contact-1')
    assert.equal(page.data.contactFormSheetVisible, true)
    assert.deepEqual(page.data.contactFormConfigForm, { displayMode: 'MODAL_FORM' })

    page.handleContactFormDisplayModeTap({ currentTarget: { dataset: { value: 'INLINE_FORM' } } })
    page.handleConfirmContactFormConfig()

    assert.equal(page.data.contactFormSheetVisible, false)
    assert.deepEqual(page.data.config.components[0].config, {
      title: '团队咨询',
      description: '请留下联系方式',
      fields: ['contactName', 'phone'],
      displayMode: 'INLINE_FORM'
    })
  } finally {
    page.cleanup()
  }
})

test('team schedule and divider sheets mark their components valid after confirmation', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.data.config = {
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [
      { componentKey: 'schedule-1', componentType: 'SCHEDULE_QUERY', sortOrder: 0, enabled: true, config: {} },
      { componentKey: 'divider-1', componentType: 'DIVIDER', sortOrder: 1, enabled: true, config: {} }
    ]
  }
  page.data.componentValidation = { 'schedule-1': false, 'divider-1': false }
  page.data.hasInvalidComponents = true

  try {
    page.openScheduleQuerySheet('schedule-1')
    page.handleConfirmScheduleQueryConfig()
    assert.equal(page.data.componentValidation['schedule-1'], true)

    page.openDividerSheet('divider-1')
    page.handleConfirmDividerConfig()
    assert.equal(page.data.componentValidation['divider-1'], true)
    assert.equal(page.data.hasInvalidComponents, false)
  } finally {
    page.cleanup()
  }
})

test('team editor hides publish for a draft-only portfolio and shows it after publication', async () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url === '/api/mine/team-portfolios/7') return { portfolioId: 7, ownerId: 3, publicationStatus: 'DRAFT_ONLY', config: { share: {}, components: [] } }
    if (options.url === '/api/mine/teams/3') return { team: { teamId: 3, teamName: '甲团队' } }
    if (options.url === '/api/mine/team-portfolios/7/draft') return { portfolioId: 7, draftRevision: 2, publicationStatus: 'DRAFT_ONLY' }
    if (options.url === '/api/mine/team-portfolios/7/publish') return { publicationStatus: 'PUBLISHED', publishedRevision: 2 }
    throw new Error(`unexpected request: ${options.url}`)
  })
  try {
    page.setData({ portfolioId: 7 })
    await page.bootstrap()
    assert.equal(page.data.showPublishAction, false)
    await page.handlePublishTap()
    assert.equal(page.data.showPublishAction, true)
  } finally { page.cleanup() }
})
