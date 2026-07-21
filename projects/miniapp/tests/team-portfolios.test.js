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

test('normalizes only top-level config and keeps component-private data opaque', () => {
  const { normalizeTeamPortfolioConfig } = loadUtility('team-portfolios.js')
  const privateConfig = { items: [{ workId: 'not-top-level' }] }
  const config = normalizeTeamPortfolioConfig({
    schemaVersion: 'standard-team-v1',
    share: { title: ' 团队 ', description: ' 简介 ', coverUrl: 'cover' },
    components: [
      { componentKey: 'b', componentType: 'CAROUSEL', sortOrder: 20, enabled: true, config: privateConfig },
      { componentKey: 'a', componentType: 'TEAM_PROFILE', sortOrder: 10, enabled: false, config: { team: { teamId: 'opaque' } } }
    ]
  })
  assert.equal(config.share.title, '团队')
  assert.deepEqual(config.components.map((item) => item.componentKey), ['a', 'b'])
  assert.equal(config.components[1].config, privateConfig)
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

test('registry contains exactly nine metadata entries and registration paths only', () => {
  const registry = loadUtility('team-portfolio-registry.js')
  assert.equal(registry.TEAM_COMPONENTS.length, 9)
  assert.deepEqual(registry.TEAM_COMPONENTS.map((item) => item.type), [
    'TEAM_PROFILE', 'CAROUSEL', 'DIVIDER', 'MEMBER_PORTFOLIO_GRID', 'MEMBER_PORTFOLIO_LIST',
    'TEXT_SECTION', 'SCHEDULE_QUERY', 'CONTACT_FORM', 'QR_CONTACT'
  ])
  for (const item of registry.TEAM_COMPONENTS) {
    assert.deepEqual(Object.keys(item).sort(), ['name', 'type'])
    assert.match(registry.TEAM_COMPONENT_REGISTRATION[item.type], /^\.\.\/components\//)
  }
  const source = fs.readFileSync(path.join(TEAM_UTILS_ROOT, 'team-portfolio-registry.js'), 'utf8')
  assert.doesNotMatch(source, /default|validate|request|endpoint/i)
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
  const approved = new Set(['request.js', 'session.js', 'upload-file.js', 'id.js', 'lunar.js'])
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
    if (file.includes(`${path.sep}components${path.sep}`)) assert.doesNotMatch(source, /require\(/, path.relative(packageRoot, file))
  }
})
