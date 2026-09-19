const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const utilityPath = '../pages/portfolios/utils/portfolio-miniapp-code'
const resources = (extra = {}) => Object.assign({ codeUrl: 'https://cdn.example.test/code.png', avatarUrl: '', contentVersion: 'v1', ownerType: 'USER', displayName: '小映', subtitle: '主持人 · 上海', shareTitle: '我们的故事', width: 1080, height: 1440, codeSize: 800, avatarSize: 120 }, extra)
const tick = () => new Promise(resolve => setImmediate(resolve))
function deferred() { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function harness(overrides = {}) {
  const { createPortfolioMiniappCodeController } = require(utilityPath)
  const calls = []; const states = []; const diagnostics = []; let time = 1000
  const wxApi = Object.assign({
    getRealtimeLogManager() { return { error(tag, details) { diagnostics.push(['realtime', tag, details]) } } },
    downloadFile(o) { calls.push(['download', o.url]); o.success({ statusCode: 200, tempFilePath: '/tmp/card.png' }) },
    getSetting(o) { calls.push(['permission']); o.success({ authSetting: {} }) },
    saveImageToPhotosAlbum(o) { calls.push(['save', o.filePath]); o.success({}) },
    previewImage(o) { calls.push(['preview', { current: o.current, urls: o.urls }]) },
    getFileSystemManager() { return { access(o) { o.success({}) } } },
    showLoading(o) { calls.push(['show-loading', o]) },
    hideLoading(o) { calls.push(['hide-loading', o]) },
    showToast(o) { calls.push(['toast', o.title]) },
    showModal(o) { calls.push(['modal', o]); o.success({ confirm: false }) },
    openSetting(o) { calls.push(['settings']); o.success({ authSetting: { 'scope.writePhotosAlbum': true } }) }
  }, overrides.wxApi)
  const requestFn = async o => { calls.push(['request', o]); return overrides.requestFn ? overrides.requestFn(o) : resources() }
  const renderFn = async input => { calls.push(['render', input]); return overrides.renderFn ? overrides.renderFn(input) : '/tmp/card.png' }
  const consoleApi = overrides.consoleApi || { error(tag, details) { diagnostics.push(['console', tag, details]) } }
  const controller = createPortfolioMiniappCodeController({ wxApi, requestFn, renderFn, consoleApi, now: () => time, onChange: s => states.push(s), onAuthRequired: m => calls.push(['auth', m]) })
  return { controller, calls, states, diagnostics, state: () => states.at(-1), advance: () => { time += 6 * 60 * 1000 } }
}

test('code controller exists and validates owner/id before contacting the server', async () => {
  assert.ok(fs.existsSync(path.join(__dirname, `${utilityPath}.js`)), 'portfolio code lifecycle must be implemented')
  const h = harness()
  await h.controller.start({ ownerType: 'TEAM', portfolioId: 'not-an-id' })
  assert.equal(h.state().status, 'error')
  assert.equal(h.calls.length, 0)
})

test('personal and team generation defaults to the original logo without downloading an avatar', async () => {
  for (const [ownerType, route] of [['USER', 'portfolios'], ['TEAM', 'team-portfolios']]) {
    const h = harness({ requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/avatar.png' }) })
    await h.controller.start({ ownerType, portfolioId: '42' })
    assert.equal(h.state().status, 'ready')
    assert.equal(h.state().tempFilePath, '/tmp/card.png')
    assert.deepEqual(h.calls.filter(c => c[0] !== 'render'), [['request', { url: `/api/mine/${route}/42/miniapp-code`, method: 'POST' }], ['download', 'https://cdn.example.test/code.png']])
    assert.equal(h.state().useAvatar, false)
    assert.equal(h.state().canToggleAvatar, true)
    assert.equal(h.calls.find(c => c[0] === 'render')[1].useAvatar, false)
    assert.equal(h.calls.find(c => c[0] === 'render')[1].avatarPath, '')
    await h.controller.preview()
    assert.deepEqual(h.calls.at(-1), ['preview', { current: '/tmp/card.png', urls: ['/tmp/card.png'] }])
  }
})

test('avatar toggle recomposes locally and preview and save follow the selected logo for both owners', async () => {
  for (const ownerType of ['USER', 'TEAM']) {
    const h = harness({ renderFn: input => input.useAvatar ? '/tmp/avatar-card.png' : '/tmp/original-card.png' })
    await h.controller.start({ ownerType, portfolioId: 42 })
    await h.controller.setUseAvatar(true)
    const avatarRender = h.calls.filter(c => c[0] === 'render').at(-1)[1]
    assert.equal(avatarRender.useAvatar, true)
    assert.equal(avatarRender.avatarPath, ownerType === 'TEAM' ? '/assets/system/wefolio-team-icon.png' : '/assets/system/wefolio-default-avatar-512.jpg')
    await h.controller.preview(); await h.controller.save()
    assert.deepEqual(h.calls.find(c => c[0] === 'preview')[1].urls, ['/tmp/avatar-card.png'])
    assert.deepEqual(h.calls.find(c => c[0] === 'save'), ['save', '/tmp/avatar-card.png'])
    await h.controller.setUseAvatar(false)
    assert.equal(h.state().useAvatar, false)
    await h.controller.preview(); await h.controller.save()
    assert.deepEqual(h.calls.filter(c => c[0] === 'preview').at(-1)[1].urls, ['/tmp/original-card.png'])
    assert.deepEqual(h.calls.filter(c => c[0] === 'save').at(-1), ['save', '/tmp/original-card.png'])
    assert.equal(h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('miniapp-code')).length, 1)
    assert.equal(h.calls.filter(c => c[0] === 'download').length, 1)
    await h.controller.setUseAvatar(false)
    assert.equal(h.calls.filter(c => c[0] === 'render').length, 3)
    await h.controller.start({ ownerType, portfolioId: 43 })
    assert.equal(h.state().useAvatar, false)
  }
})

test('avatar download failure can recover by turning the overlay off without another resource request', async () => {
  const h = harness({
    requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/broken-avatar.png' }),
    wxApi: { downloadFile(o) { h.calls.push(['download', o.url]); o.url.includes('avatar') ? o.fail({ errMsg: 'network error' }) : o.success({ statusCode: 200, tempFilePath: '/tmp/code.png' }) } }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  await h.controller.setUseAvatar(true)
  assert.equal(h.state().status, 'download-error')
  assert.equal(h.state().tempFilePath, '')
  assert.equal(h.state().canToggleAvatar, true)
  await h.controller.setUseAvatar(false)
  assert.equal(h.state().status, 'ready')
  assert.equal(h.state().useAvatar, false)
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 2)
})

test('avatar choice cannot change during rendering or native saving and discarded renders cannot update a new target', async () => {
  const rendering = deferred(); let nativeSave
  const h = harness({ renderFn: input => input.useAvatar ? rendering.promise : '/tmp/plain.png', wxApi: { saveImageToPhotosAlbum(o) { nativeSave = o } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  const toggling = h.controller.setUseAvatar(true); await tick()
  assert.equal(h.state().canToggleAvatar, false)
  await h.controller.setUseAvatar(false); await h.controller.save()
  assert.equal(h.state().useAvatar, true); assert.equal(nativeSave, undefined)
  await h.controller.start({ ownerType: 'TEAM', portfolioId: 2 })
  const count = h.states.length
  rendering.resolve('/tmp/obsolete-avatar.png'); await toggling
  assert.equal(h.states.length, count); assert.equal(h.state().tempFilePath, '/tmp/plain.png')
  const saving = h.controller.save(); await tick()
  assert.equal(h.state().canToggleAvatar, false)
  await h.controller.setUseAvatar(true)
  assert.equal(h.state().useAvatar, false)
  nativeSave.success({}); await saving
  assert.equal(h.state().canToggleAvatar, true)
})

test('both avatar switch directions retain the displayed image until replacement without native loading', async () => {
  let pending
  const h = harness({ renderFn: () => pending ? pending.promise : '/tmp/plain.png' })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  assert.equal(h.calls.some(c => c[0] === 'show-loading'), false)
  for (const useAvatar of [true, false]) {
    pending = deferred()
    const before = h.calls.length
    const previousPath = h.state().tempFilePath
    const switching = h.controller.setUseAvatar(useAvatar)
    assert.equal(h.state().status, 'downloading')
    assert.equal(h.state().displayFilePath, previousPath)
    assert.equal(h.state().tempFilePath, '')
    await tick()
    assert.equal(h.state().status, 'rendering')
    assert.equal(h.state().displayFilePath, previousPath)
    assert.equal(h.state().canToggleAvatar, false)
    await h.controller.setUseAvatar(!useAvatar); await h.controller.preview(); await h.controller.save()
    assert.equal(h.calls.slice(before).filter(c => c[0] === 'render').length, 1)
    assert.equal(h.calls.slice(before).some(c => c[0] === 'save' || c[0] === 'preview'), false)
    pending.resolve(useAvatar ? '/tmp/avatar.png' : '/tmp/plain.png'); await switching
    assert.equal(h.state().status, 'ready'); assert.equal(h.state().useAvatar, useAvatar)
    assert.equal(h.state().displayFilePath, useAvatar ? '/tmp/avatar.png' : '/tmp/plain.png')
    await h.controller.setUseAvatar(useAvatar)
    assert.equal(h.calls.slice(before).filter(c => c[0] === 'render').length, 1)
  }
  assert.equal(h.calls.some(c => c[0] === 'show-loading' || c[0] === 'hide-loading'), false)
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
})

test('avatar switch failures preserve the previous image for display only and remain recoverable', async () => {
  for (const failStage of ['download', 'render']) {
    const h = harness({
      requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/avatar.png' }),
      wxApi: { downloadFile(o) { if (failStage === 'download' && o.url.includes('avatar')) o.fail({ errMsg: 'network error' }); else o.success({ statusCode: 200, tempFilePath: '/tmp/resource.png' }) } },
      renderFn(input) { if (failStage === 'render' && input.useAvatar) throw new Error('图片绘制失败，请重试'); return '/tmp/plain.png' }
    })
    await h.controller.start({ ownerType: 'TEAM', portfolioId: 2 })
    await h.controller.setUseAvatar(true)
    assert.equal(h.state().status, `${failStage}-error`)
    assert.equal(h.state().canToggleAvatar, true)
    assert.equal(h.state().displayFilePath, '/tmp/plain.png')
    assert.equal(h.state().tempFilePath, '')
    await h.controller.preview(); await h.controller.save()
    assert.equal(h.calls.some(c => c[0] === 'save' || c[0] === 'preview'), false)
    await h.controller.setUseAvatar(false)
    assert.equal(h.state().status, 'ready')
    assert.equal(h.state().displayFilePath, '/tmp/plain.png')
    assert.equal(h.calls.some(c => c[0] === 'show-loading' || c[0] === 'hide-loading'), false)
  }
})

test('changing target clears the displayed image and obsolete work cannot replace the current image', async () => {
  const first = deferred(); const second = deferred(); let avatarRenders = 0
  const h = harness({ renderFn: input => input.useAvatar ? (++avatarRenders === 1 ? first.promise : second.promise) : '/tmp/plain.png' })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  const firstSwitch = h.controller.setUseAvatar(true); await tick()
  const restarting = h.controller.start({ ownerType: 'TEAM', portfolioId: 2 })
  assert.equal(h.state().displayFilePath, '')
  await restarting
  const secondSwitch = h.controller.setUseAvatar(true); await tick()
  first.resolve('/tmp/obsolete.png'); await firstSwitch
  assert.equal(h.state().displayFilePath, '/tmp/plain.png')
  assert.equal(h.state().status, 'rendering')
  h.controller.dispose()
  const count = h.states.length
  second.resolve('/tmp/closed.png'); await secondSwitch
  assert.equal(h.calls.some(c => c[0] === 'show-loading' || c[0] === 'hide-loading'), false)
  assert.equal(h.states.length, count)
})

test('download failure retries the same image without regeneration', async () => {
  let attempts = 0
  const h = harness({ wxApi: { downloadFile(o) { attempts++; attempts === 1 ? o.success({ statusCode: 403 }) : o.success({ statusCode: 200, tempFilePath: '/tmp/retry.png' }) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  assert.equal(h.state().status, 'download-error')
  await h.controller.retry()
  assert.equal(h.state().tempFilePath, '/tmp/card.png')
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
})

test('generation errors preserve server messages and retry generation', async () => {
  let attempts = 0
  const h = harness({ requestFn() { if (++attempts === 1) throw new Error('作品集已下架'); return resources({ contentVersion: 'new' }) } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  assert.equal(h.state().errorMessage, '作品集已下架')
  assert.equal(h.state().tempFilePath, '')
  await h.controller.save()
  assert.equal(h.calls.filter(c => c[0] === 'save').length, 0)
  await h.controller.retry()
  assert.equal(h.state().status, 'ready')
})

test('late responses after unload or a different target cannot display/download obsolete images', async () => {
  const pending = deferred()
  const h = harness({ requestFn(o) { return o.url.includes('/1/') ? pending.promise : resources({ codeUrl: 'https://cdn.example.test/two.png' }) } })
  const first = h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  await h.controller.start({ ownerType: 'TEAM', portfolioId: 2 })
  pending.resolve(resources({ codeUrl: 'https://cdn.example.test/one.png' })); await first
  assert.equal(h.state().codeUrl, 'https://cdn.example.test/two.png')
  assert.deepEqual(h.calls.filter(c => c[0] === 'download'), [['download', 'https://cdn.example.test/two.png']])
  const pending2 = deferred(); const h2 = harness({ requestFn: () => pending2.promise })
  const opening = h2.controller.start({ ownerType: 'USER', portfolioId: 1 }); h2.controller.dispose()
  const count = h2.states.length; pending2.resolve(resources({ codeUrl: 'https://cdn.example.test/late.png' })); await opening
  assert.equal(h2.states.length, count)
  assert.equal(h2.calls.filter(c => c[0] === 'download').length, 0)
})

test('save prompts only after a click, suppresses duplicates, and records exactly one actual save despite callback reentry', async () => {
  let saveOptions
  const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { saveOptions = o } }, requestFn(o) { if (o.url.endsWith('share-records')) throw new Error('记录失败'); return resources() } })
  await h.controller.start({ ownerType: 'TEAM', portfolioId: 2 })
  assert.equal(h.calls.some(c => c[0] === 'permission'), false)
  const saving = h.controller.save(); await h.controller.save(); await tick()
  assert.equal(h.state().saving, true)
  saveOptions.success({}); saveOptions.success({}); await saving; await tick()
  const records = h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('share-records'))
  assert.deepEqual(records, [['request', { url: '/api/mine/team-portfolios/2/share-records', method: 'POST', data: { shareChannel: 'QR_CODE', shareScene: 'PORTFOLIO_QR_SAVE' } }]])
  assert.ok(h.calls.some(c => c[0] === 'toast' && c[1] === '已保存到相册'))
  assert.equal(h.state().status, 'ready'); assert.equal(h.state().saving, false)
})

test('denied permission offers settings and never auto saves after returning', async () => {
  const h = harness({ wxApi: {
    getSetting(o) { o.success({ authSetting: { 'scope.writePhotosAlbum': false } }) },
    showModal(o) { h.calls.push(['modal', o]); o.success({ confirm: true }) }
  } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 3 }); await h.controller.save()
  assert.equal(h.calls.filter(c => c[0] === 'settings').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'save').length, 0)
  const modal = h.calls.find(c => c[0] === 'modal')[1]
  assert.equal(modal.cancelText, '暂不'); assert.equal(modal.confirmText, '去设置')
  assert.equal(h.state().tempFilePath, '/tmp/card.png'); assert.equal(h.state().saving, false)
})

test('permission cancellation and system save failures keep the image and never record success', async () => {
  for (const errMsg of ['saveImageToPhotosAlbum:fail cancel', 'saveImageToPhotosAlbum:fail system error']) {
    const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { o.fail({ errMsg }) } } })
    await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
    assert.equal(h.state().status, 'ready'); assert.equal(h.state().tempFilePath, '/tmp/card.png')
    assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
    assert.equal(h.calls.some(c => c[1] === '已保存到相册'), false)
  }
})

test('long-stay save revalidates server state and blocks stale unavailable images', async () => {
  let generations = 0
  const h = harness({ requestFn() { if (++generations > 1) throw new Error('暂无权限访问该团队作品集'); return resources() } })
  await h.controller.start({ ownerType: 'TEAM', portfolioId: 2 }); h.advance(); await h.controller.save()
  assert.equal(h.state().status, 'error'); assert.equal(h.state().tempFilePath, '')
  assert.equal(h.state().errorMessage, '暂无权限访问该团队作品集')
  assert.equal(h.state().displayFilePath, '')
  assert.equal(h.calls.some(c => c[0] === 'save'), false)
})

test('long-stay unchanged image reuses its downloaded file after validation', async () => {
  const h = harness()
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); h.advance(); await h.controller.save()
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'save').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('miniapp-code')).length, 2)
})

test('result page generates onLoad only, retains its image after preview/settings, and stays inside the portfolio package', async () => {
  const pageFile = path.join(__dirname, '../pages/portfolios/share-code/portfolio-share-code.js')
  assert.ok(fs.existsSync(pageFile), 'result page must exist')
  let definition
  const previousPage = global.Page; const previousWx = global.wx
  let requests = 0; let previews = 0
  const { canvasRuntime } = require('./helpers/miniapp-code-canvas')
  const runtime = canvasRuntime()
  global.wx = Object.assign(runtime.wxApi, {
    getFileSystemManager() { return { access(o) { o.success({}) } } },
    getStorageSync() { return 'maintainer-token' },
    request(o) { requests++; assert.match(o.url, /\/api\/mine\/portfolios\/42\/miniapp-code$/); o.success({ statusCode: 200, data: { success: true, data: resources() } }) },
    downloadFile(o) { o.success({ statusCode: 200, tempFilePath: '/tmp/page.png' }) },
    previewImage(o) { previews++; assert.deepEqual(o.urls, ['/tmp/rendered.png']) }
  })
  global.Page = value => { definition = value }
  try {
    delete require.cache[require.resolve(pageFile)]; require(pageFile)
    const page = Object.assign({}, definition, { data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch) } })
    const loading = page.onLoad({ ownerType: 'USER', portfolioId: '42' })
    await tick()
    assert.equal(runtime.calls.some(c => c[0] === 'query'), false)
    page.onReady(); await loading
    assert.equal(page.data.tempFilePath, '/tmp/rendered.png')
    await page.handlePreview(); if (page.onShow) page.onShow()
    assert.equal(previews, 1); assert.equal(requests, 1); assert.equal(page.data.tempFilePath, '/tmp/rendered.png')
    assert.equal(page.data.useAvatar, false)
    assert.equal(page.data.canToggleAvatar, true)
    assert.equal(runtime.calls.some(c => c[0] === 'arc'), false)
    await page.handleUseAvatarChange({ detail: { value: true } })
    assert.equal(page.data.useAvatar, true)
    assert.ok(runtime.calls.some(c => c[0] === 'arc'))
    await page.handleUseAvatarChange({ detail: { value: false } })
    assert.equal(page.data.useAvatar, false)
    assert.equal(requests, 1)
    page.onUnload(); page.handlePreview(); assert.equal(previews, 1)
    const app = JSON.parse(fs.readFileSync(path.join(__dirname, '../app.json'), 'utf8'))
    assert.ok(app.subPackages.find(p => p.root === 'pages/portfolios').pages.includes('share-code/portfolio-share-code'))
  } finally { global.Page = previousPage; global.wx = previousWx }
})

test('page keeps action buttons unchanged while either avatar switch direction replaces the image', async () => {
  const pageFile = path.join(__dirname, '../pages/portfolios/share-code/portfolio-share-code.js')
  const previousPage = global.Page; const previousWx = global.wx
  const { canvasRuntime } = require('./helpers/miniapp-code-canvas')
  let definition; let exportImage; let holdExport = false; let saved = 0; let previews = 0
  const patches = []
  const runtime = canvasRuntime({ canvasToTempFilePath(o) { if (holdExport) exportImage = o; else o.success({ tempFilePath: '/tmp/plain.png' }) } })
  global.wx = Object.assign(runtime.wxApi, {
    getFileSystemManager() { return { access(o) { o.success({}) } } },
    getStorageSync() { return 'maintainer-token' },
    request(o) { o.success({ statusCode: 200, data: { success: true, data: resources() } }) },
    downloadFile(o) { o.success({ statusCode: 200, tempFilePath: '/tmp/code.png' }) },
    previewImage() { previews++ },
    getSetting(o) { o.success({ authSetting: {} }) },
    saveImageToPhotosAlbum(o) { saved++; o.success({}) },
    showToast() {}
  })
  global.Page = value => { definition = value }
  let page
  try {
    delete require.cache[require.resolve(pageFile)]; require(pageFile)
    page = Object.assign({}, definition, {
      data: structuredClone(definition.data),
      setData(patch) { patches.push({ ...patch }); Object.assign(this.data, patch) }
    })
    assert.equal(page.data.actionsDisabled, true)
    const loading = page.onLoad({ ownerType: 'USER', portfolioId: '42' })
    page.onReady(); await loading
    assert.equal(page.data.actionsDisabled, false)
    for (const useAvatar of [true, false]) {
      holdExport = true; exportImage = null; patches.length = 0
      const switching = page.handleUseAvatarChange({ detail: { value: useAvatar } })
      await tick()
      assert.ok(exportImage, '切换必须在图片区域完成绘制再解除处理中状态')
      assert.equal(page.data.status, 'rendering')
      assert.equal(page.data.actionsDisabled, false)
      await page.handlePreview(); await page.handleSave()
      assert.equal(previews, 0); assert.equal(saved, 0)
      exportImage.success({ tempFilePath: useAvatar ? '/tmp/avatar.png' : '/tmp/plain.png' })
      await switching
      assert.equal(page.data.status, 'ready')
      assert.equal(page.data.actionsDisabled, false)
      assert.equal(patches.some(p => Object.hasOwn(p, 'saving') || Object.hasOwn(p, 'actionsDisabled')), false,
        '图片切换不应向视图重复发送按钮禁用、loading 或文案绑定数据')
    }
    await page.handlePreview(); await page.handleSave()
    assert.equal(previews, 1); assert.equal(saved, 1)
    assert.ok(patches.some(p => p.actionsDisabled === true && p.saving === true), '真正保存时仍禁用按钮')
    const failedSwitch = page.handleUseAvatarChange({ detail: { value: true } })
    await tick()
    exportImage.fail({ errMsg: 'canvasToTempFilePath:fail' }); await failedSwitch
    assert.equal(page.data.status, 'render-error')
    assert.equal(page.data.actionsDisabled, true, '图片失败后明确禁用操作，不能让按钮看似可用却无响应')
    await page.handlePreview(); await page.handleSave()
    assert.equal(previews, 1); assert.equal(saved, 1)
    holdExport = false
    await page.handleUseAvatarChange({ detail: { value: false } })
    assert.equal(page.data.status, 'ready'); assert.equal(page.data.actionsDisabled, false)
  } finally {
    if (page) page.onUnload()
    global.Page = previousPage; global.wx = previousWx
  }
})

test('permission denied by the first native save opens guidance, and a later click can save', async () => {
  let attempts = 0
  const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { if (++attempts === 1) o.fail({ errMsg: 'saveImageToPhotosAlbum:fail auth deny' }); else o.success({}) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  await h.controller.save()
  assert.equal(h.calls.filter(c => c[0] === 'modal').length, 1)
  assert.equal(h.calls.some(c => c[0] === 'settings'), false)
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
  await h.controller.save(); await tick()
  assert.equal(h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('share-records')).length, 1)
})

test('each separately completed save records once while preserving the downloaded image', async () => {
  const h = harness()
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  await h.controller.save(); await h.controller.save(); await tick()
  assert.equal(h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('share-records')).length, 2)
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 1)
})

test('changed image after long-stay validation is downloaded before saving the new file', async () => {
  let generation = 0
  const h = harness({
    requestFn(o) { return o.url.endsWith('miniapp-code') ? resources({ contentVersion: String(++generation) }) : {} },
    renderFn() { return `/tmp/${generation}.png` }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); h.advance(); await h.controller.save()
  assert.deepEqual(h.calls.find(c => c[0] === 'save'), ['save', '/tmp/2.png'])
  assert.equal(h.state().tempFilePath, '/tmp/2.png')
})

test('unload during permission lookup prevents native save and late UI updates', async () => {
  let settings
  const h = harness({ wxApi: { getSetting(o) { settings = o } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  const pending = h.controller.save(); await tick(); h.controller.dispose(); const count = h.states.length
  settings.success({ authSetting: {} }); await pending
  assert.equal(h.states.length, count); assert.equal(h.calls.some(c => c[0] === 'save'), false)
})

test('a temporary file reclaimed during native save offers local recomposition without generation', async () => {
  const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { o.fail({ errMsg: 'saveImageToPhotosAlbum:fail no such file' }) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
  assert.equal(h.state().status, 'render-error'); await h.controller.retry()
  assert.equal(h.state().status, 'ready')
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'render').length, 2)
})

test('native save completed after unload still records once without a toast or page update', async () => {
  let nativeSave
  const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { nativeSave = o } } })
  await h.controller.start({ ownerType: 'TEAM', portfolioId: 42 })
  const saving = h.controller.save(); await tick()
  assert.ok(nativeSave, 'native save must already be in progress')
  h.controller.dispose()
  const count = h.states.length
  nativeSave.success({}); nativeSave.success({}); await saving; await tick()
  assert.deepEqual(h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('share-records')), [
    ['request', { url: '/api/mine/team-portfolios/42/share-records', method: 'POST', data: { shareChannel: 'QR_CODE', shareScene: 'PORTFOLIO_QR_SAVE' } }]
  ])
  assert.equal(h.calls.some(c => c[0] === 'toast'), false)
  assert.equal(h.states.length, count)
})


test('failed rendering retries downloaded resources without another server call', async () => {
  let renders = 0
  const h = harness({ requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/avatar.png?v=1' }), renderFn() { if (++renders === 1) throw new Error('图片绘制失败，请重试'); return '/tmp/recovered.png' } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  assert.equal(h.state().status, 'render-error')
  await Promise.all([h.controller.retry(), h.controller.retry()])
  assert.equal(h.state().tempFilePath, '/tmp/recovered.png')
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 1)
  assert.equal(renders, 2)
})

test('unload while decoding or exporting suppresses the result and invalidates renderer work', async () => {
  const pending = deferred(); let input
  const h = harness({ renderFn(value) { input = value; return pending.promise } })
  const opening = h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await tick()
  assert.ok(input.isActive())
  h.controller.dispose(); const count = h.states.length
  assert.equal(input.isActive(), false)
  pending.resolve('/tmp/late.png'); await opening
  assert.equal(h.states.length, count)
})

test('unload after code download never starts avatar download or rendering', async () => {
  let download
  const h = harness({ requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/avatar.png' }), wxApi: { downloadFile(o) { download = o } } })
  const loading = h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await tick()
  h.controller.dispose(); download.success({ statusCode: 200, tempFilePath: '/tmp/code.png' }); await loading
  assert.equal(h.calls.some(c => c[0] === 'render'), false)
})

test('expired local preview file offers local rendering retry without generation', async () => {
  const h = harness({ wxApi: { previewImage(o) { o.fail({ errMsg: 'previewImage:fail no such file' }) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  await h.controller.preview()
  assert.equal(h.state().status, 'render-error')
  await h.controller.retry()
  assert.equal(h.state().status, 'ready')
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'render').length, 2)
})

test('a reclaimed decoded resource is downloaded again while other resources are reused', async () => {
  let avatarRenders = 0
  const h = harness({
    requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/avatar.png' }),
    wxApi: { downloadFile(o) { h.calls.push(['download', o.url]); o.success({ statusCode: 200, tempFilePath: o.url.includes('avatar') ? '/tmp/avatar.png' : '/tmp/code.png' }) } },
    renderFn(input) { if (input.useAvatar && ++avatarRenders === 1) throw Object.assign(new Error('图片加载失败，请重试'), { imagePath: '/tmp/avatar.png' }); return '/tmp/new.png' }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.setUseAvatar(true)
  assert.equal(h.state().status, 'render-error')
  await h.controller.retry()
  assert.equal(h.state().status, 'ready')
  assert.deepEqual(h.calls.filter(c => c[0] === 'download').map(c => c[1]), ['https://cdn.example.test/code.png', 'https://cdn.example.test/avatar.png', 'https://cdn.example.test/avatar.png'])
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
})

test('avatar download retries keep the already downloaded original code', async () => {
  let attempts = 0
  const h = harness({
    requestFn: () => resources({ avatarUrl: 'https://cdn.example.test/avatar.png' }),
    wxApi: { downloadFile(o) { h.calls.push(['download', o.url]); if (o.url.includes('avatar') && ++attempts === 1) o.fail({ errMsg: 'network error' }); else o.success({ statusCode: 200, tempFilePath: o.url.includes('avatar') ? '/tmp/avatar.png' : '/tmp/code.png' }) } }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.setUseAvatar(true)
  assert.equal(h.state().status, 'download-error')
  await h.controller.retry()
  assert.equal(h.state().status, 'ready')
  assert.equal(h.calls.filter(c => c[0] === 'download' && c[1].includes('code.png')).length, 1)
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
})

test('explicit code or avatar HTTP 404/410 refreshes resources once and downloads the new URL', async () => {
  for (const [failedAsset, statusCode] of [['code', 404], ['avatar', 410]]) {
    let generation = 0
    const h = harness({
      requestFn: () => resources({ codeUrl: `https://cdn.example.test/code-${++generation}.png`, avatarUrl: `https://cdn.example.test/avatar-${generation}.png`, contentVersion: String(generation) }),
      wxApi: { downloadFile(o) { h.calls.push(['download', o.url]); o.success(o.url.includes(`${failedAsset}-1`) ? { statusCode } : { statusCode: 200, tempFilePath: `/tmp/${o.url.split('/').at(-1)}` }) } }
    })
    await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
    if (failedAsset === 'avatar') await h.controller.setUseAvatar(true)
    assert.equal(h.state().status, 'ready')
    assert.equal(generation, 2)
    assert.equal(h.calls.filter(c => c[0] === 'render').length, failedAsset === 'avatar' ? 2 : 1)
    assert.ok(h.calls.some(c => c[0] === 'download' && c[1].includes(`${failedAsset}-2`)))
  }
})

test('repeated asset 404 is bounded to one refresh per user action', async () => {
  const h = harness({ wxApi: { downloadFile(o) { h.calls.push(['download', o.url]); o.success({ statusCode: 404 }) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
  assert.equal(h.state().status, 'download-error')
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 2)
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 2)
  await h.controller.retry()
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 3)
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 4)
})

test('network and non-missing HTTP failures never refresh resources', async () => {
  for (const statusCode of [403, 500, null]) {
    const h = harness({ wxApi: { downloadFile(o) { statusCode ? o.success({ statusCode }) : o.fail({ errMsg: 'network timeout' }) } } })
    await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.retry()
    assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
    assert.equal(h.state().status, 'download-error')
  }
})

test('five-minute validation with unchanged content still recreates a missing final local file before saving', async () => {
  let renders = 0
  const h = harness({
    renderFn: () => `/tmp/card-${++renders}.png`,
    wxApi: { getFileSystemManager() { return { access(o) { o.path === '/tmp/card-1.png' ? o.fail({ errMsg: 'no such file' }) : o.success({}) } } } }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); h.advance(); await h.controller.save()
  assert.deepEqual(h.calls.find(c => c[0] === 'save'), ['save', '/tmp/card-2.png'])
  assert.equal(h.calls.filter(c => c[0] === 'request' && c[1].url.endsWith('miniapp-code')).length, 2)
  assert.equal(h.calls.filter(c => c[0] === 'download').length, 1)
})

test('missing local card recovers during preview without a server request', async () => {
  let renders = 0
  const h = harness({
    renderFn: () => `/tmp/card-${++renders}.png`,
    wxApi: { getFileSystemManager() { return { access(o) { o.path === '/tmp/card-1.png' ? o.fail({}) : o.success({}) } } } }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.preview()
  assert.deepEqual(h.calls.find(c => c[0] === 'preview'), ['preview', { current: '/tmp/card-2.png', urls: ['/tmp/card-2.png'] }])
  assert.equal(h.calls.filter(c => c[0] === 'request').length, 1)
})

test('401 during resource refresh uses maintainer authentication and prevents save', async () => {
  let generations = 0
  const h = harness({ requestFn() { if (++generations === 2) throw Object.assign(new Error('登录已过期'), { authRequired: true }); return resources() } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); h.advance(); await h.controller.save()
  assert.deepEqual(h.calls.find(c => c[0] === 'auth'), ['auth', '登录已过期'])
  assert.equal(h.calls.some(c => c[0] === 'save'), false)
})

test('unload during local file existence check starts no native preview or save', async () => {
  for (const action of ['preview', 'save']) {
    let accessing
    const h = harness({ wxApi: { getFileSystemManager() { return { access(o) { accessing = o } } } } })
    await h.controller.start({ ownerType: 'USER', portfolioId: 1 })
    const pending = h.controller[action](); await tick(); h.controller.dispose()
    assert.ok(accessing)
    accessing.success({}); await pending
    assert.equal(h.calls.some(c => c[0] === 'preview' || c[0] === 'permission' || c[0] === 'save'), false)
  }
})

test('album failures log distinct native stages safely while preserving the ready file and no share record', async () => {
  for (const stage of ['getSetting', 'saveImageToPhotosAlbum', 'openSetting']) {
    const wxApi = { [stage](o) { o.fail({ errMsg: `${stage}:fail platform unavailable`, errCode: 20003, extra: { access_token: 'must-not-log' } }) } }
    if (stage === 'openSetting') {
      wxApi.getSetting = o => o.success({ authSetting: { 'scope.writePhotosAlbum': false } })
      wxApi.showModal = o => o.success({ confirm: true })
    }
    const h = harness({ wxApi })
    await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
    assert.deepEqual(h.diagnostics, [
      ['console', 'portfolio-miniapp-code-save-failed', { stage, errMsg: `${stage}:fail platform unavailable`, errCode: 20003, errno: null }],
      ['realtime', 'portfolio-miniapp-code-save-failed', { stage, errMsg: `${stage}:fail platform unavailable`, errCode: 20003, errno: null }]
    ])
    assert.equal(h.state().status, 'ready'); assert.equal(h.state().tempFilePath, '/tmp/card.png'); assert.equal(h.state().saving, false)
    assert.equal(h.calls.some(c => c[0] === 'request' && c[1].url.endsWith('share-records')), false)
  }
})

test('album diagnostic strips URLs, full local paths and identity values from native error text', async () => {
  const secretMessage = 'saveImageToPhotosAlbum:fail file="/private/user folder/card.png" src=wxfile://tmp/private.png remote=https://cdn.example.invalid/WFSECRET/others/a.png?token=secret openid=oPrivate123 phone=13800138000 userId=42 token=BearerSecret /tmp/private.png'
  const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { o.fail({ errMsg: secretMessage, errCode: '40003' }) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
  assert.equal(h.diagnostics.length, 2)
  const diagnostic = h.diagnostics[0][2]
  assert.equal(diagnostic.stage, 'saveImageToPhotosAlbum'); assert.equal(diagnostic.errCode, '40003')
  assert.match(diagnostic.errMsg, /^saveImageToPhotosAlbum:fail/)
  assert.doesNotMatch(JSON.stringify(h.diagnostics), /cdn\.example|WFSECRET|others|oPrivate123|13800138000|BearerSecret|private\.png|user folder|card\.png|userId=42/)
  assert.equal(h.state().status, 'ready')
})

test('native album cancellation is not logged as an error and never records a share', async () => {
  for (const stage of ['saveImageToPhotosAlbum', 'openSetting']) {
    const wxApi = { [stage](o) { o.fail({ errMsg: `${stage}:fail cancel` }) } }
    if (stage === 'openSetting') {
      wxApi.getSetting = o => o.success({ authSetting: { 'scope.writePhotosAlbum': false } })
      wxApi.showModal = o => o.success({ confirm: true })
    }
    const h = harness({ wxApi })
    await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
    assert.deepEqual(h.diagnostics, [])
    assert.equal(h.state().status, 'ready'); assert.equal(h.state().saving, false)
    assert.equal(h.calls.some(c => c[0] === 'request' && c[1].url.endsWith('share-records')), false)
  }
})

test('unavailable diagnostic sinks cannot interfere with native failure handling', async () => {
  const h = harness({
    consoleApi: { error() { throw new Error('console unavailable') } },
    wxApi: { getRealtimeLogManager() { throw new Error('unsupported') }, saveImageToPhotosAlbum(o) { o.fail({ errMsg: 'saveImageToPhotosAlbum:fail system error' }) } }
  })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
  assert.equal(h.state().status, 'ready'); assert.equal(h.state().saving, false)
  assert.ok(h.calls.some(c => c[0] === 'toast' && c[1] === '保存失败，请重试'))
})

test('undeclared album privacy scope shows administrator guidance without opening user settings or recording a save', async () => {
  const errMsg = 'saveImageToPhotosAlbum:fail api scope is not declared in the privacy agreement'
  const h = harness({ wxApi: { saveImageToPhotosAlbum(o) { o.fail({ errMsg, errno: 112 }) } } })
  await h.controller.start({ ownerType: 'USER', portfolioId: 1 }); await h.controller.save()
  assert.deepEqual(h.calls.filter(c => c[0] === 'toast'), [['toast', '保存功能暂不可用，请联系管理员']])
  assert.equal(h.state().status, 'ready'); assert.equal(h.state().saving, false)
  assert.equal(h.state().tempFilePath, '/tmp/card.png')
  assert.equal(h.calls.some(c => c[0] === 'modal' || c[0] === 'settings'), false)
  assert.equal(h.calls.some(c => c[0] === 'request' && c[1].url.endsWith('share-records')), false)
  assert.equal(h.diagnostics.length, 2)
  for (const entry of h.diagnostics) {
    assert.equal(entry[2].stage, 'saveImageToPhotosAlbum')
    assert.equal(entry[2].errMsg, errMsg)
    assert.equal(entry[2].errno, 112)
  }
  await h.controller.preview()
  assert.deepEqual(h.calls.at(-1), ['preview', { current: '/tmp/card.png', urls: ['/tmp/card.png'] }])
})
