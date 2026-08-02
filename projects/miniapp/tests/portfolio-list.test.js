const assert = require('node:assert/strict')
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

function loadPortfolioListPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolios/portfolios.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const teamPortfolioListPath = path.join(__dirname, '../pages/portfolios/utils/team-portfolio-list.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const teamPortfolioListCacheKey = require.resolve(teamPortfolioListPath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalTeamPortfolioListCache = require.cache[teamPortfolioListCacheKey]
  const originalPage = global.Page
  const originalWx = global.wx
  let pageDefinition

  delete require.cache[require.resolve(pagePath)]
  delete require.cache[teamPortfolioListCacheKey]
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
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    navigateTo() {},
    redirectTo() {},
    showToast() {},
    showModal(options) {
      options.success({ confirm: true })
    }
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
    if (originalTeamPortfolioListCache) {
      require.cache[teamPortfolioListCacheKey] = originalTeamPortfolioListCache
    } else {
      delete require.cache[teamPortfolioListCacheKey]
    }
  }

  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch) {
      applyData(this.data, patch)
    },
    cleanup() {
      if (typeof this.onUnload === 'function') {
        this.onUnload()
      }
      global.wx = originalWx
    }
  })
}

test('creating a standard personal portfolio opens an unsaved editor draft', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88 })
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.handleCreateStandardPersonal()
  await flushPromises()

  try {
    assert.deepEqual(requests, [])
    assert.deepEqual(navigations, [
      { url: '/pages/portfolios/standard-edit/portfolio-standard-edit' }
    ])
  } finally {
    page.cleanup()
  }
})

test('TEAM entry switches in place, loads once, and keeps cached team state', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    if (options.url === '/api/mine/team-portfolios') {
      return [{ portfolioId: 9, teamId: 7, title: '团队作品集' }]
    }
    if (options.url.endsWith('/maintainable-teams')) {
      return [{ teamId: 7, currentRole: 'OWNER' }]
    }
    return { portfolios: [] }
  }, {
    navigateTo(options) { navigations.push(options) },
    navigateBack(options) { navigations.push(options) },
    redirectTo(options) { navigations.push(options) }
  })

  try {
    page.handleOwnerTypeTap({ currentTarget: { dataset: { type: 'TEAM' } } })
    await flushPromises()

    assert.equal(page.data.ownerType, 'TEAM')
    assert.equal(page.data.teamLoaded, true)
    assert.equal(page.data.teamDisplayPortfolios[0].title, '团队作品集')
    assert.deepEqual(navigations, [])

    page.setData({
      switching: false,
      revealedTeamPortfolioId: 9,
      teamPortfolioTouchStart: { portfolioId: 9, x: 100, y: 20 }
    })
    page.handleOwnerTypeTap({ currentTarget: { dataset: { type: 'USER' } } })
    assert.equal(page.data.revealedTeamPortfolioId, null)
    assert.equal(page.data.teamPortfolioTouchStart, null)
    page.setData({ switching: false })
    page.handleOwnerTypeTap({ currentTarget: { dataset: { type: 'TEAM' } } })
    await flushPromises()

    assert.equal(requests.filter((item) => item.url === '/api/mine/team-portfolios').length, 1)
  } finally {
    page.cleanup()
  }
})

test('TEAM route opens the team panel directly and onShow only refreshes team data', async () => {
  const requests = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    if (options.url.endsWith('/maintainable-teams')) {
      return []
    }
    return []
  })

  try {
    assert.equal(typeof page.onLoad, 'function')
    page.onLoad({ ownerType: 'TEAM' })
    await page.onShow()
    assert.equal(page.data.ownerType, 'TEAM')
    assert.equal(page.data.switching, false)
    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/team-portfolios',
      '/api/mine/team-portfolios/maintainable-teams'
    ])
  } finally {
    page.cleanup()
  }
})

test('team cards choose draft or published preview from publication status', async () => {
  const page = loadPortfolioListPage(async (options) => {
    if (options.url.endsWith('/maintainable-teams')) {
      return [{ teamId: 7, currentRole: 'OWNER' }]
    }
    return [
      {
        portfolioId: 33,
        teamId: 7,
        publicationStatus: 'DRAFT_ONLY',
        canMaintain: true,
        canShare: false
      },
      {
        portfolioId: 34,
        teamId: 7,
        publicationStatus: 'PUBLISHED',
        canMaintain: true,
        canShare: true
      }
    ]
  })

  try {
    await page.bootstrapTeam()
    assert.equal(page.data.teamDisplayPortfolios[0].canPreviewDraft, true)
    assert.equal(page.data.teamDisplayPortfolios[0].canPublishedPreview, false)
    assert.equal(page.data.teamDisplayPortfolios[1].canPreviewDraft, false)
    assert.equal(page.data.teamDisplayPortfolios[1].canPublishedPreview, true)
  } finally {
    page.cleanup()
  }
})

test('ordinary team members preview drafts and published versions without maintenance access', async () => {
  const navigations = []
  const page = loadPortfolioListPage(async (options) => {
    if (options.url.endsWith('/maintainable-teams')) {
      return []
    }
    return [
      {
        portfolioId: 33,
        teamId: 7,
        currentRole: 'MEMBER',
        publicationStatus: 'DRAFT_ONLY',
        canMaintain: false,
        canShare: false
      },
      {
        portfolioId: 34,
        teamId: 7,
        currentRole: 'MEMBER',
        publicationStatus: 'PUBLISHED',
        canMaintain: false,
        canShare: true,
        shareCode: 'TEAM-MEMBER-SHARE'
      }
    ]
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  try {
    await page.bootstrapTeam()
    const [draft, published] = page.data.teamDisplayPortfolios

    assert.equal(draft.canPreviewDraft, true)
    assert.equal(draft.canPublishedPreview, false)
    assert.equal(draft.canDelete, false)
    assert.equal(published.canPreviewDraft, false)
    assert.equal(published.canPublishedPreview, true)
    assert.equal(published.canShare, true)
    assert.equal(published.canDelete, false)

    page.handleTeamPortfolioCardTap({ currentTarget: { dataset: { item: draft } } })
    page.handleTeamPreviewTap({ currentTarget: { dataset: { item: draft, scope: 'draft' } } })
    page.handleTeamPreviewTap({ currentTarget: { dataset: { item: published, scope: 'published' } } })
    assert.deepEqual(navigations.map((item) => item.url), [
      '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=33&scope=draft',
      '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=34&scope=published'
    ])

    page.handleShareTap({
      currentTarget: { dataset: { ownerType: 'TEAM', id: published.portfolioId } }
    })
    assert.equal(page.data.shareSheetVisible, true)
    assert.deepEqual(page.data.shareTarget, { ownerType: 'TEAM', portfolioId: 34 })
  } finally {
    page.cleanup()
  }
})

test('creating a standard team portfolio opens an unsaved team editor', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    return { portfolioId: 33 }
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.setData({
    maintainableTeams: [{ teamId: 7, teamName: '甲', currentRole: 'OWNER' }],
    maintainableTeamsLoaded: true
  })

  try {
    assert.equal(typeof page.handleCreateStandardTeam, 'function')
    await page.handleCreateStandardTeam()
    assert.deepEqual(requests, [])
    assert.equal(page.data.teamSelectSheetVisible, false)
    assert.deepEqual(navigations, [{
      url: '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit?teamId=7'
    }])
  } finally {
    page.cleanup()
  }
})

test('team picker opens before creating a portfolio for multiple maintainable teams', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.setData({
    maintainableTeams: [
      { teamId: 7, teamName: '甲', currentRole: 'OWNER' },
      { teamId: 8, teamName: '乙', currentRole: 'MANAGER' }
    ],
    maintainableTeamsLoaded: true
  })

  try {
    page.handleCreateStandardTeam()
    assert.equal(page.data.teamSelectSheetVisible, true)
    assert.equal(page.data.selectedCreateTeamId, null)
    assert.equal(page.data.teamSelectSheetListHeight, 238)
    assert.deepEqual(navigations, [])
  } finally {
    page.cleanup()
  }
})

test('team picker confirms a valid selection and clears its state', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.setData({
    maintainableTeams: [
      { teamId: 7, teamName: '甲', currentRole: 'OWNER' },
      { teamId: 8, teamName: '乙', currentRole: 'MANAGER' }
    ],
    teamSelectSheetVisible: true
  })

  try {
    page.handleTeamSelectTap({ currentTarget: { dataset: { id: 8 } } })
    page.handleConfirmTeamSelect()
    assert.equal(page.data.teamSelectSheetVisible, false)
    assert.equal(page.data.selectedCreateTeamId, null)
    assert.deepEqual(navigations, [{
      url: '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit?teamId=8'
    }])
  } finally {
    page.cleanup()
  }
})

test('team picker ignores confirmation without a valid selection', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.setData({
    maintainableTeams: [
      { teamId: 7, teamName: '甲', currentRole: 'OWNER' },
      { teamId: 8, teamName: '乙', currentRole: 'MANAGER' }
    ],
    teamSelectSheetVisible: true,
    selectedCreateTeamId: 99
  })

  try {
    page.handleConfirmTeamSelect()
    assert.deepEqual(navigations, [])
    assert.equal(page.data.teamSelectSheetVisible, true)
  } finally {
    page.cleanup()
  }
})

test('closing the team picker clears its selection', () => {
  const page = loadPortfolioListPage(() => Promise.resolve({}))
  page.setData({
    teamSelectSheetVisible: true,
    selectedCreateTeamId: 7
  })

  try {
    page.handleCloseTeamSelectSheet()
    assert.equal(page.data.teamSelectSheetVisible, false)
    assert.equal(page.data.selectedCreateTeamId, null)
  } finally {
    page.cleanup()
  }
})

test('unified team cards keep edit and preview routes in the team subpackage', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  const draftItem = {
    portfolioId: 33,
    teamId: 7,
    teamName: '甲团队',
    currentRole: 'OWNER',
    canMaintain: true,
    canShare: false
  }
  const publishedItem = {
    portfolioId: 34,
    teamId: 7,
    teamName: '甲团队',
    currentRole: 'OWNER',
    canMaintain: true,
    canShare: true
  }

  try {
    assert.equal(typeof page.handleTeamPortfolioCardTap, 'function')
    page.handleTeamPortfolioCardTap({ currentTarget: { dataset: { item: draftItem } } })
    page.handleTeamPreviewTap({ currentTarget: { dataset: { item: draftItem, scope: 'draft' } } })
    page.handleTeamPreviewTap({ currentTarget: { dataset: { item: publishedItem, scope: 'published' } } })
    assert.deepEqual(navigations.map((entry) => entry.url), [
      '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit?portfolioId=33',
      '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=33&scope=draft',
      '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=34&scope=published'
    ])
  } finally {
    page.cleanup()
  }
})

test('deleting a team portfolio from the unified page refreshes only team data', async () => {
  const requests = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    if (options.url === '/api/mine/team-portfolios/33/delete') return {}
    if (options.url === '/api/mine/team-portfolios') return []
    if (options.url.endsWith('/maintainable-teams')) return []
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    showModal(options) {
      options.success({ confirm: true })
    }
  })

  try {
    assert.equal(typeof page.handleTeamDeleteTap, 'function')
    page.setData({ revealedTeamPortfolioId: 33 })
    await page.handleTeamDeleteTap({
      currentTarget: { dataset: { item: { portfolioId: 33, canMaintain: true } } }
    })
    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/team-portfolios/33/delete',
      '/api/mine/team-portfolios',
      '/api/mine/team-portfolios/maintainable-teams'
    ])
    assert.equal(page.data.deletingTeamPortfolioId, null)
    assert.equal(page.data.revealedTeamPortfolioId, null)
  } finally {
    page.cleanup()
  }
})

test('left swiping a team portfolio reveals its own delete action', () => {
  const page = loadPortfolioListPage(() => Promise.resolve({}))
  page.setData({
    ownerType: 'TEAM',
    revealedPortfolioId: 88,
    teamDisplayPortfolios: [{ portfolioId: 33, canDelete: true }]
  })

  try {
    page.handleTeamPortfolioTouchStart({
      currentTarget: { dataset: { id: 33 } },
      touches: [{ clientX: 180, clientY: 20 }]
    })
    page.handleTeamPortfolioTouchEnd({
      changedTouches: [{ clientX: 120, clientY: 22 }]
    })

    assert.equal(page.data.revealedTeamPortfolioId, 33)
    assert.equal(page.data.revealedPortfolioId, 88)
  } finally {
    page.cleanup()
  }
})

test('tapping a revealed team card closes delete before opening the editor', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  const item = { portfolioId: 33, canMaintain: true }
  page.setData({ revealedTeamPortfolioId: 33 })

  try {
    page.handleTeamPortfolioCardTap({ currentTarget: { dataset: { item } } })
    assert.equal(page.data.revealedTeamPortfolioId, null)
    assert.deepEqual(navigations, [])

    page.handleTeamPortfolioCardTap({ currentTarget: { dataset: { item } } })
    assert.deepEqual(navigations, [{
      url: '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit?portfolioId=33'
    }])
  } finally {
    page.cleanup()
  }
})

test('tapping preview on a revealed team card closes delete before navigating', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  const item = { portfolioId: 33, canMaintain: true }
  page.setData({ revealedTeamPortfolioId: 33 })

  try {
    page.handleTeamPreviewTap({ currentTarget: { dataset: { item, scope: 'draft' } } })
    assert.equal(page.data.revealedTeamPortfolioId, null)
    assert.deepEqual(navigations, [])

    page.handleTeamPreviewTap({ currentTarget: { dataset: { item, scope: 'draft' } } })
    assert.deepEqual(navigations, [{
      url: '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=33&scope=draft'
    }])
  } finally {
    page.cleanup()
  }
})

test('publishing a draft team portfolio from the list refreshes only team data', async () => {
  const requests = []
  const toasts = []
  const modals = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    if (options.url.endsWith('/publish')) {
      return { publicationStatus: 'PUBLISHED', publishedRevision: 4 }
    }
    return []
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  const item = {
    portfolioId: 33,
    canMaintain: true,
    publicationStatus: 'DRAFT_ONLY',
    draftRevision: 4
  }

  try {
    await page.handleTeamPublishTap({ currentTarget: { dataset: { item } } })

    assert.deepEqual(requests.map((entry) => entry.url), [
      '/api/mine/team-portfolios/33/publish',
      '/api/mine/team-portfolios',
      '/api/mine/team-portfolios/maintainable-teams'
    ])
    assert.equal(requests[0].data.draftRevision, 4)
    assert.match(requests[0].data.idempotencyKey, /^team-publish-/)
    assert.equal(modals.length, 1)
    assert.equal(modals[0].title, '发布免责声明')
    assert.deepEqual(toasts, [{ title: '已发布', icon: 'success' }])
    assert.equal(page.data.publishingTeamPortfolioId, null)
  } finally {
    page.cleanup()
  }
})

test('canceling team publish disclaimer from the list does not call publish api', async () => {
  const requests = []
  const modals = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    return {}
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: false })
    }
  })
  const item = {
    portfolioId: 33,
    canMaintain: true,
    publicationStatus: 'DRAFT_ONLY',
    draftRevision: 4
  }

  try {
    await page.handleTeamPublishTap({ currentTarget: { dataset: { item } } })

    assert.equal(modals.length, 1)
    assert.equal(modals[0].title, '发布免责声明')
    assert.equal(requests.some((entry) => entry.url.endsWith('/publish')), false)
    assert.equal(page.data.publishingTeamPortfolioId, null)
  } finally {
    page.cleanup()
  }
})

test('publishing a draft team portfolio reports an ordinary failure without refreshing', async () => {
  const requests = []
  const toasts = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    throw new Error('network failed')
  }, {
    showToast(options) {
      toasts.push(options)
    }
  })
  const item = {
    portfolioId: 33,
    canMaintain: true,
    publicationStatus: 'DRAFT_ONLY',
    draftRevision: 4
  }

  try {
    await page.handleTeamPublishTap({ currentTarget: { dataset: { item } } })
    assert.deepEqual(requests.map((entry) => entry.url), [
      '/api/mine/team-portfolios/33/publish'
    ])
    assert.deepEqual(toasts, [{ title: '发布失败，请重试', icon: 'none' }])
    assert.equal(page.data.publishingTeamPortfolioId, null)
  } finally {
    page.cleanup()
  }
})

test('team sharing from the unified sheet uses shareTarget and consumes it once', async () => {
  const requests = []
  let resolveRecord
  const recordPending = new Promise((resolve) => {
    resolveRecord = resolve
  })
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return recordPending
  })
  const item = {
    portfolioId: 33,
    publicationStatus: 'PUBLISHED',
    canShare: true,
    shareCode: 'team-share',
    title: '甲团队作品集',
    teamName: '甲团队'
  }
  page.data.teamDisplayPortfolios = [item]

  try {
    page.handleShareTap({ currentTarget: { dataset: { ownerType: 'TEAM', id: 33 } } })
    const first = page.onShareAppMessage({ target: { dataset: { ownerType: 'USER', id: 999 } } })
    const second = page.onShareAppMessage({ target: { dataset: { ownerType: 'TEAM', item } } })
    assert.equal(first.path, '/pages/team-portfolios/visitor-portfolio/team-visitor-portfolio?shareCode=team-share')
    assert.equal(second, undefined)
    assert.equal(requests.length, 1)
    assert.deepEqual(requests[0].data, {
      shareChannel: 'WECHAT_CARD',
      shareScene: 'TEAM_PORTFOLIO_LIST'
    })
    assert.equal(page.data.sharingTeamPortfolioId, 33)
    assert.equal(page.data.shareSheetVisible, false)
    assert.equal(page.data.shareTarget, null)
    resolveRecord({})
    await flushPromises()
    assert.equal(page.data.sharingTeamPortfolioId, null)
  } finally {
    page.cleanup()
  }
})

test('team pull-down refresh reloads only team data and clears its indicator', async () => {
  const requests = []
  const page = loadPortfolioListPage(async (options) => {
    requests.push(options)
    return []
  })

  try {
    assert.equal(typeof page.handleTeamPullDownRefresh, 'function')
    page.setData({ revealedTeamPortfolioId: 33 })
    await page.handleTeamPullDownRefresh()
    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/team-portfolios',
      '/api/mine/team-portfolios/maintainable-teams'
    ])
    assert.equal(page.data.teamPullDownRefreshing, false)
    assert.equal(page.data.revealedTeamPortfolioId, null)
  } finally {
    page.cleanup()
  }
})

test('published portfolio share uses shareTarget, ignores event dataset, and closes the sheet', () => {
  const requests = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })
  page.data.displayPortfolios = [{
    portfolioId: 88,
    title: '林安婚礼司仪',
    publicationStatus: 'PUBLISHED',
    shareCode: 'PF 001',
    coverUrl: 'https://example.test/cover.jpg'
  }]

  try {
    page.handleShareTap({
      currentTarget: { dataset: { id: 88, ownerType: 'USER' } }
    })
    const share = page.onShareAppMessage({
      target: { dataset: { id: 999, ownerType: 'TEAM' } }
    })

    assert.equal(
      share.path,
      '/pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode=PF%20001'
    )
    assert.equal(page.data.shareSheetVisible, false)
    assert.equal(page.data.shareTarget, null)
    assert.equal(requests.length, 1)
    assert.deepEqual(requests[0], {
      url: '/api/mine/portfolios/88/share-records',
      method: 'POST',
      data: {
        shareChannel: 'WECHAT_CARD',
        shareScene: 'PORTFOLIO_LIST'
      }
    })
  } finally {
    page.cleanup()
  }
})

test('share callback without a valid shareTarget returns undefined and does not record', () => {
  const requests = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })

  try {
    const share = page.onShareAppMessage({
      target: { dataset: { id: 88, ownerType: 'USER' } }
    })

    assert.equal(share, undefined)
    assert.deepEqual(requests, [])
    assert.equal(page.data.shareSheetVisible, false)
    assert.equal(page.data.shareTarget, null)
  } finally {
    page.cleanup()
  }
})

test('timeline share hides the sheet and navigates only once with encoded shareCode', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.data.displayPortfolios = [{
    portfolioId: 88,
    publicationStatus: 'PUBLISHED',
    shareCode: 'PF 001',
    title: '林安婚礼司仪'
  }]

  try {
    page.handleShareTap({ currentTarget: { dataset: { id: 88, ownerType: 'USER' } } })
    page.handleTimelineShare()
    page.handleTimelineShare()

    assert.equal(navigations.length, 1)
    assert.equal(
      navigations[0].url,
      '/pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode=PF%20001&shareGuide=timeline&sharePortfolioId=88'
    )
    assert.equal(page.data.shareSheetVisible, false)
    assert.equal(page.data.shareTarget, null)
    assert.equal(page.data.shareActionPending, true)
  } finally {
    page.cleanup()
  }
})

test('team timeline share carries only the current portfolio id into the guide page', () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.data.teamDisplayPortfolios = [{
    portfolioId: 33,
    publicationStatus: 'PUBLISHED',
    canShare: true,
    shareCode: 'TEAM 1',
    title: '甲团队作品集'
  }]

  try {
    page.handleShareTap({ currentTarget: { dataset: { id: 33, ownerType: 'TEAM' } } })
    page.handleTimelineShare()

    assert.equal(
      navigations[0].url,
      '/pages/team-portfolios/visitor-portfolio/team-visitor-portfolio?shareCode=TEAM%201&shareGuide=timeline&sharePortfolioId=33'
    )
  } finally {
    page.cleanup()
  }
})

test('list refresh clears an open share sheet before replacing data', async () => {
  const page = loadPortfolioListPage(() => Promise.resolve({ portfolios: [] }))
  page.setData({
    shareSheetVisible: true,
    shareActionPending: true,
    shareTarget: { ownerType: 'USER', portfolioId: 88 }
  })

  try {
    await page.handlePullDownRefresh()
    assert.equal(page.data.shareSheetVisible, false)
    assert.equal(page.data.shareActionPending, false)
    assert.equal(page.data.shareTarget, null)
  } finally {
    page.cleanup()
  }
})

test('unified portfolio page registers one share sheet for personal and team actions', () => {
  const fs = require('node:fs')
  const pageRoot = path.join(__dirname, '../pages/portfolios')
  const json = JSON.parse(fs.readFileSync(path.join(pageRoot, 'portfolios.json'), 'utf8'))
  const wxml = fs.readFileSync(path.join(pageRoot, 'portfolios.wxml'), 'utf8')

  assert.equal(
    json.usingComponents['share-channel-sheet'],
    '/components/share-channel-sheet/share-channel-sheet'
  )
  assert.match(wxml, /data-owner-type="USER"[^>]*catchtap="handleShareTap"/)
  assert.match(wxml, /data-owner-type="TEAM"[^>]*catchtap="handleShareTap"/)
  assert.doesNotMatch(wxml, /class="portfolio-action-button share"[^>]*data-item="\{\{item\}\}"/)
  assert.match(wxml, /<share-channel-sheet[^>]*bindtimeline="handleTimelineShare"/)
})

test('normalizes long portfolio titles for marquee display', async () => {
  const page = loadPortfolioListPage((options) => {
    if (options.url === '/api/mine/portfolios') {
      return Promise.resolve({
        portfolios: [
          {
            portfolioId: 88,
            ownerType: 'USER',
            templateType: 'STANDARD',
            publicationStatus: 'DRAFT',
            title: '风景标准个人作品集超长标题用于列表滚动展示',
            coverUrl: 'https://example.test/cover.jpg'
          },
          {
            portfolioId: 89,
            ownerType: 'USER',
            templateType: 'STANDARD',
            publicationStatus: 'DRAFT',
            title: '风景作品集',
            coverUrl: 'https://example.test/cover.jpg'
          }
        ]
      })
    }
    return Promise.resolve({})
  })

  page.bootstrap()
  await flushPromises()

  try {
    assert.equal(page.data.displayPortfolios[0].titleScrollable, true)
    assert.equal(page.data.displayPortfolios[1].titleScrollable, false)
  } finally {
    page.cleanup()
  }
})

test('unavailable portfolio creation buttons show toast without navigation', async () => {
  const navigations = []
  const toasts = []
  const page = loadPortfolioListPage(() => Promise.resolve({ portfolios: [] }), {
    navigateTo(options) {
      navigations.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  })

  ;['advanced-personal', 'standard-team', 'advanced-team'].forEach((type) => {
    page.handleUnavailableTap({
      currentTarget: {
        dataset: { type }
      }
    })
  })

  try {
    assert.deepEqual(navigations, [])
    assert.deepEqual(toasts, [
      { title: '暂未开放，即将发布', icon: 'none' },
      { title: '暂未开放，即将发布', icon: 'none' },
      { title: '暂未开放，即将发布', icon: 'none' }
    ])
  } finally {
    page.cleanup()
  }
})

test('left swiping a personal portfolio reveals delete and confirm delete refreshes list', async () => {
  const requests = []
  const toasts = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios') {
      return Promise.resolve({
        portfolios: [
          {
            portfolioId: 88,
            ownerType: 'USER',
            templateType: 'STANDARD',
            publicationStatus: 'PUBLISHED',
            title: '林安婚礼司仪',
            shareCode: 'PF001',
            coverUrl: 'https://example.test/cover.jpg'
          }
        ]
      })
    }
    if (options.url === '/api/mine/portfolios/delete/88') {
      return Promise.resolve({})
    }
    return Promise.resolve({})
  }, {
    showModal(options) {
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  page.handlePortfolioTouchStart({
    currentTarget: { dataset: { id: 88 } },
    touches: [{ clientX: 180, clientY: 20 }]
  })
  page.handlePortfolioTouchEnd({
    changedTouches: [{ clientX: 120, clientY: 22 }]
  })

  await page.handleDeletePortfolioTap({
    currentTarget: { dataset: { id: 88 } }
  })
  await flushPromises()
  await flushPromises()

  try {
    assert.equal(page.data.revealedPortfolioId, null)
    assert.equal(page.data.deletingPortfolioId, null)
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET'],
      ['/api/mine/portfolios/delete/88', 'POST'],
      ['/api/mine/portfolios', 'GET']
    ])
    assert.equal(toasts[0].title, '作品集已删除')
  } finally {
    page.cleanup()
  }
})

test('tapping a revealed portfolio card closes delete action instead of opening editor', async () => {
  const navigations = []
  const page = loadPortfolioListPage(() => Promise.resolve({ portfolios: [] }), {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.data.displayPortfolios = [
    {
      portfolioId: 88,
      title: '林安婚礼司仪'
    }
  ]
  page.data.revealedPortfolioId = 88

  page.handlePortfolioCardTap({
    currentTarget: { dataset: { id: 88 } }
  })

  try {
    assert.equal(page.data.revealedPortfolioId, null)
    assert.deepEqual(navigations, [])
  } finally {
    page.cleanup()
  }
})

test('publishing a draft portfolio from list confirms disclaimer before posting publish api', async () => {
  const requests = []
  const navigations = []
  const toasts = []
  const modals = []
  let listLoadCount = 0
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios') {
      listLoadCount += 1
      return Promise.resolve({
        portfolios: [
          {
            portfolioId: 88,
            ownerType: 'USER',
            templateType: 'STANDARD',
            publicationStatus: listLoadCount > 1 ? 'PUBLISHED' : 'DRAFT',
            title: '林安婚礼司仪',
            draftRevision: 3,
            publishedRevision: listLoadCount > 1 ? 4 : 0,
            shareCode: 'PF001'
          }
        ]
      })
    }
    if (options.url === '/api/mine/portfolios/88/publish') {
      return Promise.resolve({ portfolioId: 88, publishedRevision: 4 })
    }
    return Promise.resolve({})
  }, {
    navigateTo(options) {
      navigations.push(options)
    },
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  await page.handlePrimaryActionTap({
    currentTarget: {
      dataset: {
        id: 88,
        action: page.data.displayPortfolios[0].actionType
      }
    }
  })
  await flushPromises()
  await flushPromises()

  try {
    assert.deepEqual(navigations, [])
    assert.equal(modals.length, 1)
    assert.equal(modals[0].title, '发布免责声明')
    assert.match(modals[0].content, /肖像/)
    assert.match(modals[0].content, /侵权/)
    assert.equal(modals[0].confirmText, '确认发布')
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET'],
      ['/api/mine/portfolios/88/publish', 'POST'],
      ['/api/mine/portfolios', 'GET']
    ])
    assert.equal(requests[1].data.draftRevision, 3)
    assert.match(requests[1].data.idempotencyKey, /^publish-/)
    assert.equal(toasts[0].title, '已发布')
    assert.equal(page.data.displayPortfolios[0].statusText, '已发布')
  } finally {
    page.cleanup()
  }
})

test('canceling publish disclaimer from list does not call publish api', async () => {
  const requests = []
  const modals = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: false })
    }
  })
  page.data.displayPortfolios = [
    {
      portfolioId: 88,
      title: '林安婚礼司仪',
      draftRevision: 3,
      actionType: 'PUBLISH'
    }
  ]

  await page.handlePrimaryActionTap({
    currentTarget: {
      dataset: {
        id: 88,
        action: 'PUBLISH'
      }
    }
  })
  await flushPromises()

  try {
    assert.equal(modals.length, 1)
    assert.deepEqual(requests, [])
  } finally {
    page.cleanup()
  }
})

test('previewing a published portfolio from list opens published preview without sharing', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolios: [
        {
          portfolioId: 88,
          ownerType: 'USER',
          templateType: 'STANDARD',
          publicationStatus: 'PUBLISHED',
          title: '林安婚礼司仪',
          draftRevision: 5,
          publishedRevision: 4,
          shareCode: 'PF001'
        }
      ]
    })
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  page.handlePublishedPreviewTap({
    currentTarget: { dataset: { id: 88 } }
  })

  try {
    assert.equal(page.data.displayPortfolios[0].showPublishedPreview, true)
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET']
    ])
    assert.deepEqual(navigations, [
      { url: '/pages/portfolios/standard-preview/portfolio-standard-preview?portfolioId=88&scope=published' }
    ])
  } finally {
    page.cleanup()
  }
})

test('previewing a draft portfolio from list opens draft preview without publishing', async () => {
  const requests = []
  const navigations = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolios: [
        {
          portfolioId: 88,
          ownerType: 'USER',
          templateType: 'STANDARD',
          publicationStatus: 'DRAFT',
          title: '林安婚礼司仪',
          draftRevision: 5,
          publishedRevision: 0,
          shareCode: 'PF001'
        }
      ]
    })
  }, {
    navigateTo(options) {
      navigations.push(options)
    }
  })

  page.bootstrap()
  await flushPromises()

  page.handleDraftPreviewTap({
    currentTarget: { dataset: { id: 88 } }
  })

  try {
    assert.equal(page.data.displayPortfolios[0].showDraftPreview, true)
    assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
      ['/api/mine/portfolios', 'GET']
    ])
    assert.deepEqual(navigations, [
      { url: '/pages/portfolios/standard-preview/portfolio-standard-preview?portfolioId=88' }
    ])
  } finally {
    page.cleanup()
  }
})

test('bubbled primary action tap does not open portfolio editor', async () => {
  const requests = []
  const navigations = []
  const modals = []
  const page = loadPortfolioListPage((options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, publishedRevision: 4 })
  }, {
    navigateTo(options) {
      navigations.push(options)
    },
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    }
  })
  page.data.displayPortfolios = [
    {
      portfolioId: 88,
      title: '林安婚礼司仪',
      draftRevision: 3,
      actionType: 'PUBLISH'
    }
  ]

  const publishPromise = page.handlePrimaryActionTap({
    currentTarget: {
      dataset: {
        id: 88,
        action: 'PUBLISH'
      }
    }
  })
  page.handlePortfolioCardTap({
    currentTarget: { dataset: { id: 88 } },
    target: {
      dataset: {
        id: 88,
        action: 'PUBLISH'
      }
    }
  })
  await publishPromise
  await flushPromises()

  try {
    assert.deepEqual(navigations, [])
    assert.equal(modals.length, 1)
    assert.equal(requests[0].url, '/api/mine/portfolios/88/publish')
  } finally {
    page.cleanup()
  }
})
