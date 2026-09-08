const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const TEAM_UTILS_ROOT = path.join(MINIAPP_ROOT, 'pages/team-portfolios/utils')

function loadUtility(name) {
  const modulePath = path.join(TEAM_UTILS_ROOT, name)
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

test('normalizes the unified team portfolio list and preserves role capabilities', () => {
  const { normalizeTeamPortfolioList } = loadUtility('team-portfolios.js')
  const list = normalizeTeamPortfolioList([{ portfolioId: '12', teamId: '7', title: '  夏日婚礼  ', currentRole: 'MANAGER', canMaintain: true, canShare: true }])
  assert.deepEqual(list, [{
    portfolioId: 12,
    shareCode: '',
    title: '夏日婚礼',
    coverUrl: '',
    teamId: 7,
    teamName: '',
    currentRole: 'MANAGER',
    canMaintain: true,
    canShare: true,
    publicationStatus: '',
    draftRevision: 0,
    publishedRevision: 0,
    updatedAt: ''
  }])
})

test('loads all visible team portfolios with one unified request', async () => {
  const { fetchTeamPortfolioList } = loadUtility('team-portfolios.js')
  const calls = []
  const result = await fetchTeamPortfolioList(async (options) => {
    calls.push(options)
    return [{ portfolioId: 1, teamId: 2 }]
  })
  assert.equal(calls.length, 1)
  assert.deepEqual(calls[0], { url: '/api/mine/team-portfolios' })
  assert.equal(result[0].portfolioId, 1)
})

test('routes 0, 1 and many maintainable teams without inferring a team', () => {
  const { resolveTeamCreateRoute } = loadUtility('team-portfolios.js')
  assert.deepEqual(resolveTeamCreateRoute([]), { action: 'UNAVAILABLE', teamId: null, url: '' })
  assert.deepEqual(resolveTeamCreateRoute([{ teamId: 9 }]), { action: 'CREATE', teamId: 9, url: '' })
  assert.deepEqual(resolveTeamCreateRoute([{ teamId: 9 }, { teamId: 10 }]), { action: 'SELECT', teamId: null, url: '/pages/team-portfolios/team-select/team-select' })
})

test('normalizes team theme and menu components while keeping private data opaque', () => {
  const { normalizeTeamPortfolioConfig } = loadUtility('team-portfolios.js')
  const privateConfig = { items: [{ workId: 'not-top-level' }] }
  const config = normalizeTeamPortfolioConfig({
    schemaVersion: 'standard-team-v1',
    editorSchemaRevision: 3,
    share: { title: ' 团队 ', description: ' 简介 ', coverUrl: 'cover' },
    style: { backgroundColor: '#1a2b3c' },
    components: [
      { componentKey: 'b', componentType: 'CAROUSEL', sortOrder: 20, enabled: true, config: privateConfig },
      { componentKey: 'a', componentType: 'TEAM_PROFILE', sortOrder: 10, enabled: false, config: { team: { teamId: 'opaque' } } }
    ],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: ' 首页 ', components: [{ componentKey: 'ignored', componentType: 'DIVIDER' }] },
        { key: 'nav_works', title: ' 作品 ', iconUrl: ' future-icon ', components: [
          { componentKey: 'secondary', componentType: 'TEXT_SECTION', sortOrder: 4, config: { content: '保留' } }
        ] }
      ]
    }
  })
  assert.equal(config.share.title, '团队')
  assert.equal(config.editorSchemaRevision, 4)
  assert.deepEqual(config.style, { backgroundColor: '#1A2B3C' })
  assert.deepEqual(config.components.map((item) => item.componentKey), ['a', 'b'])
  assert.equal(config.components[1].config, privateConfig)
  assert.deepEqual(config.bottomNav.items[0], { key: 'nav_home', title: '首页' })
  assert.equal(config.bottomNav.items[1].iconUrl, 'future-icon')
  assert.equal(config.bottomNav.items[1].components[0].config.content, '保留')
})

test('team color conversion stays independent and round-trips custom colors', () => {
  const { hexToHsv, hsvToHex, normalizeTeamHexColor } = loadUtility('team-portfolio-color.js')
  assert.equal(normalizeTeamHexColor(' #1a2b3c '), '#1A2B3C')
  assert.equal(normalizeTeamHexColor('invalid'), '#FFFFFF')
  assert.equal(hsvToHex(hexToHsv('#1A2B3C')), '#1A2B3C')
  assert.equal(hsvToHex({ hue: 0, saturation: 1, value: 1 }), '#FF0000')
})

test('team menu helpers map the first menu to top-level components and move atomically', () => {
  const {
    getTeamMenuComponentList,
    moveTeamComponent,
    replaceTeamMenuComponentList,
    visitTeamPortfolioComponents
  } = loadUtility('team-portfolios.js')
  const profileConfig = { team: { teamId: 7, teamName: '映期团队' }, localAsset: 'wxfile://avatar' }
  const config = {
    schemaVersion: 'standard-team-v1',
    editorSchemaRevision: 3,
    components: [
      { componentKey: 'component-profile', componentType: 'TEAM_PROFILE', enabled: true, config: profileConfig },
      { componentKey: 'component-divider', componentType: 'DIVIDER', enabled: true, config: {} }
    ],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '首页' },
        { key: 'nav_works', title: '作品', components: [
          { componentKey: 'component-work', componentType: 'SINGLE_WORK', enabled: true, config: { workId: 9 } }
        ] },
        { key: 'nav_contact', title: '联系', components: [] }
      ]
    }
  }
  assert.deepEqual(getTeamMenuComponentList(config, 'nav_home').map((item) => item.componentKey), [
    'component-profile',
    'component-divider'
  ])
  assert.deepEqual(getTeamMenuComponentList(config, 'nav_works').map((item) => item.componentKey), ['component-work'])

  const moved = moveTeamComponent(config, 'component-profile', 'nav_home', 'nav_contact')
  assert.deepEqual(moved.components.map((item) => item.componentKey), ['component-divider'])
  assert.equal(moved.bottomNav.items[2].components[0].componentKey, 'component-profile')
  assert.equal(moved.bottomNav.items[2].components[0].config, profileConfig)
  assert.equal(moved.bottomNav.items[2].components[0].sortOrder, 1000)
  assert.deepEqual(visitTeamPortfolioComponents(moved).map((item) => [item.menuKey, item.component.componentKey]), [
    ['nav_home', 'component-divider'],
    ['nav_works', 'component-work'],
    ['nav_contact', 'component-profile']
  ])

  const replaced = replaceTeamMenuComponentList(moved, 'nav_works', [])
  assert.deepEqual(replaced.bottomNav.items[1].components, [])

  const onlyFirstComponent = replaceTeamMenuComponentList(config, 'nav_home', [
    config.components[0]
  ])
  const blocked = moveTeamComponent(
    onlyFirstComponent,
    'component-profile',
    'nav_home',
    'nav_contact'
  )
  assert.deepEqual(
    getTeamMenuComponentList(blocked, 'nav_home').map((item) => item.componentKey),
    ['component-profile']
  )
  assert.deepEqual(getTeamMenuComponentList(blocked, 'nav_contact'), [])
})

test('team component mutations target the selected menu and keep team component keys', () => {
  const {
    addTeamComponent,
    getTeamMenuComponentList,
    removeTeamComponent,
    reorderTeamComponent,
    updateTeamComponent
  } = loadUtility('team-portfolios.js')
  const config = {
    components: [{ componentKey: 'home', componentType: 'DIVIDER', enabled: true, config: {} }],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '首页' },
        { key: 'nav_works', title: '作品', components: [
          { componentKey: 'first', componentType: 'TEXT_SECTION', enabled: true, config: { content: '一' } },
          { componentKey: 'second', componentType: 'DIVIDER', enabled: true, config: {} }
        ] }
      ]
    }
  }
  const added = addTeamComponent(config, 'QR_CONTACT', 'nav_works')
  const addedComponents = getTeamMenuComponentList(added, 'nav_works')
  assert.equal(addedComponents.length, 3)
  assert.match(addedComponents[2].componentKey, /^component-/)
  assert.equal(added.components[0].componentKey, 'home')

  const updated = updateTeamComponent(added, 'first', {
    config: { content: '更新' }
  }, 'nav_works')
  assert.equal(getTeamMenuComponentList(updated, 'nav_works')[0].config.content, '更新')

  const reordered = reorderTeamComponent(updated, 0, 2, 'nav_works')
  assert.deepEqual(getTeamMenuComponentList(reordered, 'nav_works').map((item) => item.componentKey), [
    'second',
    addedComponents[2].componentKey,
    'first'
  ])
  assert.deepEqual(getTeamMenuComponentList(reordered, 'nav_works').map((item) => item.sortOrder), [1000, 2000, 3000])

  const removed = removeTeamComponent(reordered, 'second', 'nav_works')
  assert.deepEqual(getTeamMenuComponentList(removed, 'nav_works').map((item) => item.componentKey), [
    addedComponents[2].componentKey,
    'first'
  ])
})

test('team text sections keep current revision, unknown fields, and new defaults', () => {
  const {
    addTeamComponent,
    getTeamMenuComponentList,
    normalizeTeamPortfolioConfig,
    TEAM_EDITOR_SCHEMA_REVISION
  } = loadUtility('team-portfolios.js')
  const futureConfig = {
    content: '旧说明',
    fontFamily: 'WECHAT_SANS_SS',
    fontSizeRpx: 30,
    futureField: 'kept'
  }
  const normalized = normalizeTeamPortfolioConfig({
    editorSchemaRevision: 3,
    components: [{
      componentKey: 'legacy-text',
      componentType: 'TEXT_SECTION',
      enabled: true,
      config: futureConfig
    }]
  })

  assert.equal(TEAM_EDITOR_SCHEMA_REVISION, 4)
  assert.equal(normalized.editorSchemaRevision, 4)
  assert.equal(normalized.components[0].config, futureConfig)

  const added = addTeamComponent(normalized, 'TEXT_SECTION')
  const addedText = getTeamMenuComponentList(added).at(-1)
  assert.deepEqual(addedText.config, {
    fontFamily: 'SYSTEM',
    fontSizeRpx: 28
  })
  assert.equal(added.components[0].config.futureField, 'kept')
})

test('team video carousel defaults normalize ordered items and enforce publish count', () => {
  const {
    TEAM_EDITOR_SCHEMA_REVISION,
    addTeamComponent,
    getTeamMenuComponentList,
    normalizeTeamPortfolioConfig,
    validateTeamPortfolioForPublish
  } = loadUtility('team-portfolios.js')
  const added = addTeamComponent({ components: [] }, 'VIDEO_CAROUSEL')
  assert.equal(TEAM_EDITOR_SCHEMA_REVISION, 4)
  assert.deepEqual(getTeamMenuComponentList(added)[0].config, {
    title: '视频作品',
    items: [],
    showTitle: true,
    showSwipeHint: true
  })
  const normalized = normalizeTeamPortfolioConfig({
    components: [{
      componentKey: 'video',
      componentType: 'VIDEO_CAROUSEL',
      enabled: true,
      config: {
        title: '  一二三四五六七八九十😀  ',
        items: [
          { memberUserId: 2, workId: 20 },
          { memberUserId: '3', workId: '30' },
          { memberUserId: 9, workId: 20 },
          { memberUserId: 0, workId: 40 }
        ],
        showSwipeHint: false
      }
    }]
  })
  assert.deepEqual(normalized.components[0].config, {
    title: '一二三四五六七八九十',
    items: [
      { memberUserId: 2, workId: 20 },
      { memberUserId: 3, workId: 30 }
    ],
    showTitle: true,
    showSwipeHint: false
  })
  assert.equal(validateTeamPortfolioForPublish(normalized).message, '视频轮播至少选择3个视频')
  normalized.components[0].config.items.push({ memberUserId: 4, workId: 40 })
  assert.equal(validateTeamPortfolioForPublish(normalized).valid, true)
  normalized.components[0].config.items.push(
    { memberUserId: 5, workId: 50 },
    { memberUserId: 6, workId: 60 },
    { memberUserId: 7, workId: 70 },
    { memberUserId: 8, workId: 80 },
    { memberUserId: 9, workId: 90 },
    { memberUserId: 10, workId: 100 }
  )
  assert.equal(validateTeamPortfolioForPublish(normalized).message, '视频轮播最多选择8个视频')
})

test('team component library request advertises current revision', async () => {
  const { fetchTeamComponentLibrary } = loadUtility('team-portfolios.js')
  const calls = []
  await fetchTeamComponentLibrary(async (options) => {
    calls.push(options)
    return []
  })
  assert.deepEqual(calls, [{
    url: '/api/mine/team-portfolios/component-library',
    data: { editorSchemaRevision: 4 }
  }])
})

test('team navigation helpers add rename and remove menus without losing the promoted first menu', () => {
  const {
    removeTeamNavigationItem,
    renameTeamNavigationItem,
    setTeamBottomNavigationCount
  } = loadUtility('team-portfolios.js')
  const base = {
    components: [{ componentKey: 'home', componentType: 'DIVIDER', enabled: true }],
    bottomNav: { enabled: false }
  }
  const enabled = setTeamBottomNavigationCount(base, 3)
  assert.equal(enabled.bottomNav.enabled, true)
  assert.equal(enabled.bottomNav.items.length, 3)
  assert.ok(enabled.bottomNav.items.every((item) => item.key.startsWith('nav_')))
  assert.deepEqual(
    enabled.bottomNav.items.map((item) => item.title),
    ['主页', '菜单 2', '菜单 3']
  )
  const renamed = renameTeamNavigationItem(enabled, enabled.bottomNav.items[1].key, ' 作品 ')
  assert.equal(renamed.bottomNav.items[1].title, '作品')
  const cleared = renameTeamNavigationItem(renamed, renamed.bottomNav.items[1].key, '')
  assert.equal(cleared.bottomNav.items[1].title, '')
  const secondComponents = [{ componentKey: 'work', componentType: 'SINGLE_WORK', enabled: true }]
  const populated = Object.assign({}, renamed, {
    bottomNav: Object.assign({}, renamed.bottomNav, {
      items: renamed.bottomNav.items.map((item, index) => index === 1
        ? Object.assign({}, item, { components: secondComponents })
        : item)
    })
  })
  const removedFirst = removeTeamNavigationItem(populated, populated.bottomNav.items[0].key)
  assert.equal(removedFirst.bottomNav.items[0].title, '作品')
  assert.equal(removedFirst.components[0].componentKey, 'work')
})

test('team publish validation returns the first local menu and component error', () => {
  const { validateTeamPortfolioForPublish } = loadUtility('team-portfolios.js')
  const duplicate = {
    schemaVersion: 'standard-team-v1',
    editorSchemaRevision: 4,
    style: { backgroundColor: '#FFFFFF' },
    components: [{ componentKey: 'same', componentType: 'DIVIDER', enabled: true, config: {} }],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '首页' },
        { key: 'nav_works', title: '作品', components: [
          { componentKey: 'same', componentType: 'TEXT_SECTION', enabled: true, config: { content: '' } }
        ] }
      ]
    }
  }
  assert.deepEqual(validateTeamPortfolioForPublish(duplicate), {
    valid: false,
    menuKey: 'nav_works',
    componentKey: 'same',
    message: '【作品】组件标识不能重复'
  })

  const emptyMenu = JSON.parse(JSON.stringify(duplicate))
  emptyMenu.bottomNav.items[1].components = []
  assert.deepEqual(validateTeamPortfolioForPublish(emptyMenu), {
    valid: false,
    menuKey: 'nav_works',
    componentKey: '',
    message: '【作品】至少添加一个组件'
  })
})

test('team publish validation rejects raw navigation errors before normalization hides them', () => {
  const {
    normalizeTeamPortfolioConfig,
    validateTeamPortfolioForPublish
  } = loadUtility('team-portfolios.js')
  const config = {
    schemaVersion: 'standard-team-v1',
    editorSchemaRevision: 4,
    style: { backgroundColor: '#FFFFFF' },
    components: [{ componentKey: 'home', componentType: 'DIVIDER', enabled: true, config: {} }],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '主页' },
        { key: 'nav_works', title: '作品', components: [
          { componentKey: 'works', componentType: 'DIVIDER', enabled: true, config: {} }
        ] }
      ]
    }
  }

  const blankTitle = JSON.parse(JSON.stringify(config))
  blankTitle.bottomNav.items[1].title = ' '
  assert.deepEqual(validateTeamPortfolioForPublish(blankTitle), {
    valid: false,
    menuKey: 'nav_works',
    componentKey: '',
    message: '菜单名称不能为空'
  })

  const longTitle = JSON.parse(JSON.stringify(config))
  longTitle.bottomNav.items[1].title = '成员作品展示'
  assert.deepEqual(validateTeamPortfolioForPublish(longTitle), {
    valid: false,
    menuKey: 'nav_works',
    componentKey: '',
    message: '菜单名称不能超过5个字'
  })

  const invalidKey = JSON.parse(JSON.stringify(config))
  invalidKey.bottomNav.items[1].key = 'works'
  assert.deepEqual(validateTeamPortfolioForPublish(invalidKey), {
    valid: false,
    menuKey: 'works',
    componentKey: '',
    message: '菜单标识格式不正确'
  })

  const invalidComponent = JSON.parse(JSON.stringify(config))
  invalidComponent.bottomNav.items[1].components[0].componentKey = ' '
  assert.deepEqual(validateTeamPortfolioForPublish(invalidComponent), {
    valid: false,
    menuKey: 'nav_works',
    componentKey: '',
    message: '【作品】组件信息不完整'
  })

  const unsupportedSchema = JSON.parse(JSON.stringify(config))
  unsupportedSchema.schemaVersion = 'standard-personal-v1'
  assert.deepEqual(validateTeamPortfolioForPublish(unsupportedSchema), {
    valid: false,
    menuKey: '',
    componentKey: '',
    message: '团队作品集配置版本不支持'
  })

  const unsupportedRevision = JSON.parse(JSON.stringify(config))
  unsupportedRevision.editorSchemaRevision = 5
  const normalizedUnsupportedRevision =
    normalizeTeamPortfolioConfig(unsupportedRevision)
  assert.equal(normalizedUnsupportedRevision.editorSchemaRevision, 5)
  assert.deepEqual(validateTeamPortfolioForPublish(normalizedUnsupportedRevision), {
    valid: false,
    menuKey: '',
    componentKey: '',
    message: '当前客户端暂不支持此团队作品集配置'
  })
})

test('creates a standard team portfolio only through the explicit team endpoint', async () => {
  const { createStandardTeamPortfolio } = loadUtility('team-portfolios.js')
  const calls = []
  await createStandardTeamPortfolio((options) => {
    calls.push(options)
    return Promise.resolve({ portfolioId: 33 })
  }, 8, { schemaVersion: 'standard-team-v1' })
  assert.deepEqual(calls, [{
    url: '/api/mine/teams/8/portfolios/standard',
    method: 'POST',
    data: { config: { schemaVersion: 'standard-team-v1' } }
  }])
})

test('draft and publish helpers reject blank idempotency keys before requesting', async () => {
  const { saveTeamPortfolioDraft, publishTeamPortfolio } = loadUtility('team-portfolios.js')
  let requestCount = 0
  const requestFn = async () => { requestCount += 1 }
  await assert.rejects(() => saveTeamPortfolioDraft(requestFn, 1, { schemaVersion: 'standard-team-v1' }, 1, '  '), /幂等键/)
  await assert.rejects(() => publishTeamPortfolio(requestFn, 1, 1, ''), /幂等键/)
  await assert.rejects(() => saveTeamPortfolioDraft(requestFn, 1, {}, 1, 'x'.repeat(65)), /幂等键/)
  assert.equal(requestCount, 0)
})

test('maps exact team portfolio backend messages to unavailable reasons', () => {
  const {
    resolveTeamPortfolioUnavailableMessage,
    resolveTeamPortfolioUnavailableReason,
    showTeamPortfolioUnavailableToast
  } = loadUtility('team-portfolios.js')
  assert.equal(resolveTeamPortfolioUnavailableReason({ message: '团队作品集功能暂未开放' }), 'FEATURE_DISABLED')
  assert.equal(resolveTeamPortfolioUnavailableReason({ message: '当前作品集暂未开放访问' }), 'INVALID')
  assert.equal(resolveTeamPortfolioUnavailableReason({ message: '团队作品集不存在或无访问权限' }), 'INVALID')
  assert.equal(resolveTeamPortfolioUnavailableReason({ message: '团队当前不可用' }), 'TEAM_UNAVAILABLE')
  assert.equal(resolveTeamPortfolioUnavailableReason({ message: '网络异常' }), '')

  assert.equal(resolveTeamPortfolioUnavailableMessage({ message: '团队作品集功能暂未开放' }), '团队作品集功能暂未开放')
  assert.equal(resolveTeamPortfolioUnavailableMessage({ message: '团队当前不可用' }), '当前团队不可用')
  assert.equal(resolveTeamPortfolioUnavailableMessage({ message: '团队作品集不存在或无访问权限' }), '暂无权限访问该团队作品集')
  assert.equal(resolveTeamPortfolioUnavailableMessage({ message: '网络异常' }), '')

  const toasts = []
  assert.equal(showTeamPortfolioUnavailableToast({ message: '团队当前不可用' }, {
    showToast(options) { toasts.push(options) }
  }), true)
  assert.equal(showTeamPortfolioUnavailableToast({ message: '网络异常' }, {
    showToast(options) { toasts.push(options) }
  }), false)
  assert.deepEqual(toasts, [{ title: '当前团队不可用', icon: 'none' }])
})

test('team asset helper requests a team ticket and uploads directly to that ticket', async () => {
  const { uploadTeamPortfolioAsset } = loadUtility('team-portfolio-assets.js')
  const requests = []
  const uploads = []
  const url = await uploadTeamPortfolioAsset({
    portfolioId: 44,
    filePath: '/tmp/qr.png',
    assetType: 'QR_CONTACT',
    mimeType: 'image/png',
    fileSize: 128,
    wxApi: createImageWxApi({ '/tmp/qr.png': 128 }),
    clientId: 'client-1',
    requestFn: async (options) => {
      requests.push(options)
      return { uploadUrl: 'https://cos.example/upload', publicUrl: 'https://cdn.example/qr.png', formData: { key: 'object-key' } }
    },
    uploadFn: async (options) => uploads.push(options)
  })
  assert.equal(url, 'https://cdn.example/qr.png')
  assert.deepEqual(requests[0], {
    url: '/api/mine/team-portfolios/44/asset/upload-ticket',
    method: 'POST',
    data: { clientId: 'client-1', assetType: 'QR_CONTACT', mimeType: 'image/png', fileSize: 128 }
  })
  assert.deepEqual(uploads[0], { url: 'https://cos.example/upload', filePath: '/tmp/qr.png', name: 'file', formData: { key: 'object-key' } })
})

test('team asset ticket uses final stat when caller omits size', async () => {
  const { uploadTeamPortfolioAsset } = loadUtility('team-portfolio-assets.js')
  const requests = []
  await uploadTeamPortfolioAsset({
    portfolioId: 44,
    filePath: '/tmp/qr.png',
    assetType: 'QR_CONTACT',
    clientId: 'client-stat',
    wxApi: createImageWxApi({ '/tmp/qr.png': 256 }),
    requestFn: async (options) => {
      requests.push(options)
      return { uploadUrl: 'https://cos/upload', publicUrl: 'https://cdn/qr.png', formData: {} }
    },
    uploadFn: async () => undefined
  })
  assert.equal(requests[0].data.fileSize, 256)
  assert.equal(requests[0].data.mimeType, 'image/png')
})

test('team asset ticket follows compressed file stat and MIME', async () => {
  const { uploadTeamPortfolioAsset } = loadUtility('team-portfolio-assets.js')
  const requests = []
  const uploads = []
  await uploadTeamPortfolioAsset({
    portfolioId: 44,
    filePath: '/tmp/original.png',
    assetType: 'QR_CONTACT',
    mimeType: 'image/png',
    fileSize: 400 * 1024,
    clientId: 'client-compressed',
    wxApi: createImageWxApi({ '/tmp/original.png': 400 * 1024, '/tmp/compressed.jpg': 120 * 1024 }, '/tmp/compressed.jpg'),
    requestFn: async (options) => {
      requests.push(options)
      return { uploadUrl: 'https://cos/upload', publicUrl: 'https://cdn/qr.jpg', formData: {} }
    },
    uploadFn: async (options) => uploads.push(options)
  })
  assert.equal(requests[0].data.fileSize, 120 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].filePath, '/tmp/compressed.jpg')
})

test('team asset compresses an http tmp image when its original stat is unavailable', async () => {
  const requests = []
  const uploads = []
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          if (filePath === 'http://tmp/cover.png') throw new Error('stat:fail no such file')
          if (filePath === 'http://tmp/compressed.jpg') return { size: 120 * 1024 }
          throw new Error(`unexpected stat: ${filePath}`)
        }
      }
    },
    compressImage({ success }) { success({ tempFilePath: 'http://tmp/compressed.jpg' }) }
  }
  const { uploadTeamPortfolioAsset } = loadUtility('team-portfolio-assets.js')
  const url = await uploadTeamPortfolioAsset({
    portfolioId: 13,
    filePath: 'http://tmp/cover.png',
    assetType: 'COVER',
    clientId: 'cover-http-tmp',
    wxApi,
    requestFn: async (options) => {
      requests.push(options)
      return { uploadUrl: 'https://cos/upload', publicUrl: 'https://cdn/cover.jpg', formData: {} }
    },
    uploadFn: async (options) => uploads.push(options)
  })
  assert.equal(url, 'https://cdn/cover.jpg')
  assert.equal(requests[0].data.fileSize, 120 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].filePath, 'http://tmp/compressed.jpg')
})

test('team asset rejects a compressed file still over 300KB before signing', async () => {
  const { uploadTeamPortfolioAsset } = loadUtility('team-portfolio-assets.js')
  let requestCount = 0
  let uploadCount = 0
  await assert.rejects(() => uploadTeamPortfolioAsset({
    portfolioId: 44,
    filePath: '/tmp/original.png',
    assetType: 'QR_CONTACT',
    wxApi: createImageWxApi({ '/tmp/original.png': 400 * 1024, '/tmp/compressed.jpg': 300 * 1024 + 1 }, '/tmp/compressed.jpg'),
    requestFn: async () => { requestCount += 1 },
    uploadFn: async () => { uploadCount += 1 }
  }), /300KB/)
  assert.equal(requestCount, 0)
  assert.equal(uploadCount, 0)
})

test('team utility tree only imports approved main-package infrastructure', () => {
  const approved = new Set([
    'request.js',
    'session.js',
    'upload-file.js',
    'id.js',
    'lunar.js',
    'portfolio-text-typography.js'
  ])
  const forbidden = [/pages\/portfolios/, /pages\/visitor-(portfolio|schedule)/, /components\/portfolio-/, /utils\/portfolios\.js$/, /utils\/visitor-(portfolio|session)\.js$/]
  const files = fs.readdirSync(TEAM_UTILS_ROOT).filter((name) => name.endsWith('.js'))
  for (const file of files) {
    const fullPath = path.join(TEAM_UTILS_ROOT, file)
    const source = fs.readFileSync(fullPath, 'utf8')
    for (const pattern of forbidden) assert.doesNotMatch(source, pattern, file)
    for (const match of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
      const resolved = path.resolve(path.dirname(fullPath), match[1])
      if (resolved.startsWith(path.join(MINIAPP_ROOT, 'utils') + path.sep)) {
        assert.ok(approved.has(path.basename(resolved)), `${file}: ${match[1]}`)
      }
    }
  }
})

test('recursively rejects personal and cross-component imports in the team package', () => {
  const packageRoot = path.join(MINIAPP_ROOT, 'pages/team-portfolios')
  const jsFiles = []
  function walk(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const fullPath = path.join(directory, entry.name)
      if (entry.isDirectory()) walk(fullPath)
      else if (entry.name.endsWith('.js')) jsFiles.push(fullPath)
    }
  }
  walk(packageRoot)
  const forbidden = [/pages\/portfolios/, /pages\/visitor-(portfolio|schedule)/, /components\/portfolio-/, /utils\/portfolios(?:\.js)?/, /utils\/visitor-(portfolio|session)(?:\.js)?/]
  for (const file of jsFiles) {
    const source = fs.readFileSync(file, 'utf8')
    const relativePath = path.relative(packageRoot, file)
    if (relativePath === 'portfolios.js') {
      assert.match(source, /\/pages\/portfolios\/portfolios\?ownerType=TEAM/)
      assert.doesNotMatch(source, /require\(/)
    } else {
      for (const pattern of forbidden) assert.doesNotMatch(source, pattern, relativePath)
    }
    if (file.includes(`${path.sep}components${path.sep}`)) {
      const componentsRoot = path.join(packageRoot, 'components')
      const currentComponent = path.relative(componentsRoot, file).split(path.sep)[0]
      for (const match of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
        const resolved = path.resolve(path.dirname(file), match[1])
        assert.ok(resolved.startsWith(packageRoot + path.sep), `${relativePath}: ${match[1]}`)
        if (resolved.startsWith(componentsRoot + path.sep)) {
          const importedComponent = path.relative(componentsRoot, resolved).split(path.sep)[0]
          assert.equal(importedComponent, currentComponent, `${relativePath}: ${match[1]}`)
        }
      }
    }
  }
})
