const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const upload = require('../pages/works/utils/work-upload')
const runtime = require('../pages/works/utils/work-compression-runtime')
const pagePath = path.resolve(__dirname, '../pages/works/work-add/work-add.js')
const MB = 1024 * 1024
const tick = () => new Promise(setImmediate)
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function loadPage(wxApi = {}, overrides = {}) {
  let page
  const localRequire = createRequire(pagePath)
  const toasts = []
  const warnings = []
  const modals = []
  const api = { showToast: ({ title }) => toasts.push(title), showModal: options => modals.push({ ...options }), ...wxApi }
  vm.runInNewContext(fs.readFileSync(pagePath, 'utf8'), {
    Page(value) { page = value }, wx: api, console: { ...console, warn: (...args) => warnings.push(args) }, Date, setTimeout, clearTimeout,
    require(name) {
      if (Object.hasOwn(overrides, name)) return overrides[name]
      if (name.endsWith('/session')) return { hasLocalToken: () => true }
      if (name.endsWith('/request')) return { request() { assert.fail('选择阶段禁止网络') } }
      return localRequire(name)
    }
  })
  page.patches = []
  page.setData = (patch, callback) => {
    page.patches.push(patch)
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part]
      target[parts.at(-1)] = value
    }
    if (callback) callback()
  }
  page.onLoad()
  return { page, api, toasts, warnings, modals }
}
function pipeline(overrides = {}, wxOverrides = {}) {
  const trace = []
  const deleted = []
  const real = upload
  const methods = {
    ...real,
    normalizeChosenMediaFiles(files, options) { trace.push('normalize'); return real.normalizeChosenMediaFiles(files, options) },
    measureChosenMediaFiles(files, options) { trace.push('measure'); return real.measureChosenMediaFiles(files, options) },
    async classifyChosenMediaFiles(files, options) { trace.push('classify'); return real.classifyChosenMediaFiles(files, options) },
    async prepareWorkMainFiles(files) {
      trace.push('prepare')
      assert.deepEqual(files.map(file => file.size), [12 * MB, 110 * MB])
      return { files: files.map(file => ({ ...file, tempFilePath: `/prepared/${file.mediaType}`, size: MB, compressed: true })), ownedPathsByClientId: Object.fromEntries(files.map(file => [file.clientId, [`/prepared/${file.mediaType}`]])) }
    },
    async enrichVideoFileMetadata(files, options) { trace.push('enrich'); return real.enrichVideoFileMetadata(files, options) },
    validateChosenMediaFiles(files) { trace.push('validate'); return real.validateChosenMediaFiles(files) },
    ...overrides
  }
  const harness = loadPage({
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/original.jpg', size: 1, fileType: 'image' }, { tempFilePath: '/original.mp4', size: 1, fileType: 'video', width: 1280, height: 720, duration: 2 }] }) },
    getFileSystemManager() { return { statSync: filePath => ({ size: filePath.endsWith('.jpg') ? 12 * MB : 110 * MB }), unlinkSync: filePath => deleted.push(filePath) } },
    ...wxOverrides
  }, { '../utils/work-upload': methods })
  return { ...harness, trace, deleted }
}

test('真实页面按实测、分类、预处理、补信息、校验顺序整批入列且选择阶段不算指纹', async () => {
  const { page, trace, toasts } = pipeline()
  page.calculateFileSha256 = () => assert.fail('选择阶段不读取SHA')
  const setData = page.setData
  page.setData = (patch, callback) => { if (patch.files) trace.push('files'); setData(patch, callback) }
  await page.handleChooseMedia()
  assert.deepEqual(toasts, [])
  assert.deepEqual(trace, ['normalize', 'measure', 'classify', 'prepare', 'enrich', 'enrich', 'validate', 'files'])
  assert.equal(page.data.files.length, 2)
  assert.equal(page.data.files[0].tempFilePath, '/prepared/IMAGE')
  assert.equal(page.data.choosing, false)
})

test('分批选择和删除后追加继续命名序号，压缩保留已生成标题', async () => {
  const { page } = pipeline({
    normalizeChosenMediaFiles(files, options) {
      return upload.normalizeChosenMediaFiles(files, { ...options, now: new Date(2026, 8, 16) })
    }
  })
  await page.handleChooseMedia()
  assert.deepEqual(Array.from(page.data.files, file => file.title), ['图片 2026-09-16 00:00:00 01', '视频 2026-09-16 00:00:00 02'])
  page.handleRemoveFile({ currentTarget: { dataset: { index: 0 } } })
  await page.handleChooseMedia()
  assert.deepEqual(Array.from(page.data.files, file => file.title), [
    '视频 2026-09-16 00:00:00 02', '图片 2026-09-16 00:00:00 03', '视频 2026-09-16 00:00:00 04'
  ])
})

test('读取和图片视频压缩分别展示标题与数量，完成后清空处理提示', async () => {
  const gate = deferred()
  let progress
  let preparedFiles
  const { page } = pipeline({ prepareWorkMainFiles(files, options) {
    progress = options.onProgress
    preparedFiles = files.map(file => ({ ...file, size: MB }))
    return gate.promise
  } })
  assert.equal(page.data.choosingTitle, '')
  assert.equal(page.data.choosingHint, '')
  assert.equal(page.data.choosingCountText, '')
  assert.equal(page.data.preparationCancelled, false)
  const pending = page.handleChooseMedia()
  assert.equal(page.data.choosingTitle, '正在读取文件')
  assert.equal(page.data.choosingHint, '正在准备作品信息')
  assert.equal(page.data.choosingCountText, '准备中')
  await tick()
  progress({ mediaType: 'IMAGE', index: 1, total: 2, stage: 'compressing' })
  assert.equal(page.data.choosingTitle, '正在压缩图片')
  assert.equal(page.data.choosingHint, '图片压缩可能需要一点时间')
  assert.equal(page.data.choosingCountText, '第 1/2 个')
  assert.equal(page.data.choosingText, '正在压缩图片，第 1/2 个')
  progress({ mediaType: 'VIDEO', index: 2, total: 2, stage: 'reading' })
  assert.equal(page.data.choosingTitle, '正在读取文件')
  assert.equal(page.data.choosingHint, '正在准备作品信息')
  assert.equal(page.data.choosingCountText, '准备中')
  assert.equal(page.data.choosingText, '正在读取文件')
  progress({ mediaType: 'VIDEO', index: 2, total: 2, stage: 'compressing' })
  assert.equal(page.data.choosingTitle, '正在压缩视频')
  assert.equal(page.data.choosingHint, '视频压缩可能需要一点时间')
  assert.equal(page.data.choosingCountText, '第 2/2 个')
  assert.equal(page.data.choosingText, '正在压缩视频，第 2/2 个')
  gate.resolve({ files: preparedFiles, ownedPathsByClientId: {} })
  await pending
  assert.equal(page.data.files.length, 2)
  assert.equal(page.data.choosingTitle, '')
  assert.equal(page.data.choosingHint, '')
  assert.equal(page.data.choosingCountText, '')
  assert.equal(page.data.choosingText, '')
  assert.equal(page.data.preparationCancelled, false)
})

for (const stage of ['picker', 'classify', 'prepare', 'enrich']) {
  test(`${stage}等待中离页与迟到回调不能写界面或toast`, async () => {
    const gate = deferred()
    let progress
    const overrides = {}
    if (stage === 'classify') overrides.classifyChosenMediaFiles = () => gate.promise
    if (stage === 'prepare') overrides.prepareWorkMainFiles = (files, options) => { progress = options.onProgress; return gate.promise }
    if (stage === 'enrich') overrides.enrichVideoFileMetadata = () => gate.promise
    const { page, toasts, warnings, modals } = pipeline(overrides, stage === 'picker' ? { chooseMedia: options => gate.promise.then(options.success) } : {})
    const pending = page.handleChooseMedia()
    await tick()
    page.onUnload()
    const count = page.patches.length
    if (progress) progress({ mediaType: 'IMAGE', index: 1, total: 2, stage: 'compressing' })
    gate.resolve(stage === 'picker' ? { tempFiles: [] } : stage === 'prepare' ? { files: [], ownedPathsByClientId: {} } : [])
    await pending
    assert.equal(page.patches.length, count)
    assert.deepEqual(toasts, [])
    assert.deepEqual(warnings, [])
    assert.deepEqual(modals, [])
    assert.equal(page.data.files.length, 0)
  })
}

test('取消立即保留列表，新选择状态不被旧finally和迟到进度覆盖', async () => {
  const gate = deferred()
  let progress
  let count = 0
  const { page, toasts, warnings, modals } = pipeline({ prepareWorkMainFiles(files, options) { progress = options.onProgress; count++; return gate.promise } })
  const existing = { id: 'existing', clientId: 'existing', title: '已有', mediaType: 'IMAGE', size: 1 }
  page.data.files = [existing]
  const first = page.handleChooseMedia()
  await tick()
  page.handleCancelPreparation()
  assert.equal(page.data.choosing, false)
  assert.equal(page.data.choosingText, '')
  assert.equal(page.data.choosingTitle, '')
  assert.equal(page.data.choosingHint, '')
  assert.equal(page.data.choosingCountText, '')
  assert.equal(page.data.preparationCancelled, true)
  assert.equal(page.data.files.length, 1)
  assert.strictEqual(page.data.files[0], existing)
  const lateProgress = progress
  const second = page.handleChooseMedia()
  assert.equal(page.data.preparationCancelled, false)
  await tick()
  lateProgress({ mediaType: 'VIDEO', index: 9, total: 9, stage: 'compressing' })
  assert.equal(page.data.choosingText, '正在读取文件')
  assert.equal(page.data.choosingTitle, '正在读取文件')
  assert.equal(page.data.choosingHint, '正在准备作品信息')
  assert.equal(page.data.choosingCountText, '准备中')
  gate.reject(Object.assign(new Error('已取消处理'), { code: runtime.COMPRESSION_CANCELLED }))
  await Promise.all([first, second])
  assert.equal(count, 2)
  assert.deepEqual(toasts, [])
  assert.deepEqual(warnings, [])
  assert.deepEqual(modals, [])
})

test('内部非取消code即使message包含cancel仍展示错误', async () => {
  const { page, toasts, modals } = pipeline({ prepareWorkMainFiles() { throw Object.assign(new Error('native cancel encoder failure'), { code: 'ENCODER_ERROR' }) } })
  await page.handleChooseMedia()
  assert.deepEqual(toasts, [])
  assert.deepEqual(modals, [{ title: '作品处理失败', content: 'native cancel encoder failure', showCancel: false }])
})

test('真实页面在原片视频信息缺失时使用picker元数据完成压缩并入列', async () => {
  const sourcePath = '/iphone-original.mov'
  const outputPath = '/iphone-compressed.mp4'
  const events = [], deleted = []
  const { page, toasts, modals } = loadPage({
    chooseMedia(options) {
      options.success({ tempFiles: [{ tempFilePath: sourcePath, fileType: 'video',
        size: 120 * MB, width: 1920, height: 1080, duration: 60 }] })
    },
    getVideoInfo(options) {
      events.push(['info', options.src])
      // 复现原片信息字段不完整；压缩成品必须独立读取并使用自己的尺寸。
      options.success(options.src === sourcePath
        ? { fps: 30, bitrate: 18000, type: 'mov' }
        : { width: 1280, height: 720, duration: 60, fps: 30, type: 'mp4' })
    },
    compressVideo(options) {
      events.push(['compress', options.src])
      options.success({ tempFilePath: outputPath })
    },
    getFileSystemManager: () => ({
      statSync: filePath => ({ size: filePath === sourcePath ? 120 * MB : 97 * MB }),
      unlinkSync: filePath => deleted.push(filePath)
    })
  })
  page.calculateFileSha256 = () => assert.fail('选择阶段不读取SHA')
  try {
    await page.handleChooseMedia()
    assert.equal(page.data.errorMessage, '')
    assert.deepEqual(toasts, [])
    assert.deepEqual(modals, [])
    assert.equal(page.data.files.length, 1)
    const selected = page.data.files[0]
    assert.equal(selected.tempFilePath, outputPath)
    assert.equal(selected.size, 97 * MB)
    assert.equal(selected.width, 1280)
    assert.equal(selected.height, 720)
    assert.equal(selected.durationMs, 60000)
    assert.equal(selected.mimeType, 'video/mp4')
    assert.equal(selected.compressionText, '已压缩：120.0MB → 97.0MB')
    assert.ok(page.patches.some(patch => patch.choosingTitle === '正在压缩视频'))
    assert.equal(page.data.choosing, false)
    assert.deepEqual(events, [['info', sourcePath], ['compress', sourcePath], ['info', outputPath]])
    assert.deepEqual(deleted, [])
  } finally {
    page.onUnload()
  }
})

for (const stage of ['chooseMedia', 'getVideoInfo', 'compressVideo']) {
  test(`真实视频流程${stage}原生失败保留具体原因和错误码并允许重新选择`, async () => {
    let failing = true
    const sourcePath = '/private/source/DJI_20260517171115_0202_D.MP4'
    const nativeError = { errMsg: `${stage}:fail codec unavailable`, errno: 1103003 }
    const { page, toasts, warnings, modals } = loadPage({
      chooseMedia(options) {
        if (failing && stage === 'chooseMedia') return options.fail(nativeError)
        options.success({ tempFiles: [{ tempFilePath: sourcePath, fileType: 'video',
          size: failing ? 277 * MB : MB, width: 3840, height: 2160, duration: 40 }] })
      },
      getFileSystemManager() {
        return { statSync: () => ({ size: failing ? 277 * MB : MB }), unlinkSync() {} }
      },
      getVideoInfo(options) {
        if (failing && stage === 'getVideoInfo') return options.fail(nativeError)
        options.success({ width: 3840, height: 2160, duration: 40, fps: 60, bitrate: 58000 })
      },
      compressVideo(options) { options.fail(nativeError) }
    })
    const existing = { id: 'existing', clientId: 'existing', mediaType: 'IMAGE', size: 1 }
    page.data.files = [existing]
    await page.handleChooseMedia()
    assert.match(page.data.errorMessage, /codec unavailable/)
    assert.match(page.data.errorMessage, /1103003/)
    assert.deepEqual(modals, [{ title: '作品处理失败', content: page.data.errorMessage, showCancel: false }])
    assert.deepEqual(toasts, [])
    assert.deepEqual(warnings, [['[work-add] media preparation failed', page.data.errorMessage]])
    assert.ok(!JSON.stringify(warnings).includes(sourcePath))
    assert.strictEqual(page.data.files[0], existing)
    assert.equal(page.data.files.length, 1)
    assert.equal(page.data.choosing, false)
    assert.equal(page.data.choosingText, '')
    assert.equal(page.data.choosingTitle, '')
    assert.equal(page.data.choosingHint, '')
    assert.equal(page.data.choosingCountText, '')
    assert.equal(page.data.preparationCancelled, false)
    assert.equal(page.data.canCancelPreparation, false)
    assert.equal(page.compressionSession, null)
    failing = false
    await page.handleChooseMedia()
    assert.equal(page.data.errorMessage, '')
    assert.equal(page.data.files.length, 2)
    assert.equal(warnings.length, 1)
    assert.equal(modals.length, 1)
  })
}

test('原生选择取消不记录失败、不覆盖已有错误和列表', async () => {
  const { page, toasts, warnings, modals } = loadPage({
    chooseMedia(options) { options.fail({ errMsg: 'chooseMedia:fail cancel' }) }
  })
  const existing = { id: 'existing' }
  page.data.files = [existing]
  page.data.errorMessage = '原有错误'
  await page.handleChooseMedia()
  assert.deepEqual(toasts, [])
  assert.deepEqual(warnings, [])
  assert.deepEqual(modals, [])
  assert.equal(page.data.errorMessage, '原有错误')
  assert.strictEqual(page.data.files[0], existing)
  assert.equal(page.data.choosing, false)
  assert.equal(page.data.choosingTitle, '')
  assert.equal(page.data.choosingHint, '')
  assert.equal(page.data.choosingCountText, '')
  assert.equal(page.data.preparationCancelled, false)
})

function canvasPage(getCanvasNode) {
  const canvas = { width: 1, height: 1 }
  const { page, api } = loadPage({}, { '../utils/work-thumbnail-crop': { getCanvasNode: getCanvasNode || (() => Promise.resolve(canvas)) } })
  const session = runtime.createCompressionSession({ wxApi: api })
  page.compressionSession = session
  return { page, canvas, session }
}

test('同图片三个尺寸复用同一lease，最初release恢复实际和CSS尺寸且幂等', async () => {
  const { page, canvas, session } = canvasPage()
  const options = { ownerToken: Symbol('a'), timeoutMs: 1000 }
  const first = await page.getCompressionCanvas({ ...options, width: 3000, height: 2000 }, session)
  const second = await page.getCompressionCanvas({ ...options, width: 2048, height: 1365 }, session)
  const third = await page.getCompressionCanvas({ ...options, width: 1536, height: 1024 }, session)
  assert.strictEqual(second, first); assert.strictEqual(third, first)
  assert.deepEqual([canvas.width, canvas.height], [1536, 1024])
  first.release()
  assert.equal(page.compressionCanvasLease, null)
  assert.deepEqual([canvas.width, canvas.height, page.data.compressionCanvasWidth, page.data.compressionCanvasHeight], [1, 1, 1, 1])
  const count = page.patches.length; first.release(); assert.equal(page.patches.length, count)
  session.dispose()
})

for (const stage of ['setData', 'query']) {
  test(`Canvas ${stage}等待取消撤销预留，迟到回调不能重占`, async () => {
    const gate = deferred(); let callback
    const { page, canvas, session } = canvasPage(() => gate.promise)
    if (stage === 'setData') {
      const setData = page.setData
      page.setData = (patch, cb) => { setData(patch); if (cb) callback = cb }
    }
    const pending = page.getCompressionCanvas({ ownerToken: Symbol('a'), timeoutMs: 1000, width: 900, height: 600 }, session)
    await tick()
    assert.deepEqual([canvas.width, canvas.height], [1, 1])
    session.cancel()
    await assert.rejects(pending, { code: runtime.COMPRESSION_CANCELLED })
    assert.equal(page.compressionCanvasLease, null)
    if (callback) callback()
    gate.resolve(canvas); await tick()
    assert.deepEqual([canvas.width, canvas.height], [1, 1])
  })
}

test('A占用时B失败不修改A，旧release不影响后续B', async () => {
  const { page, canvas, session } = canvasPage()
  const a = await page.getCompressionCanvas({ ownerToken: Symbol('a'), timeoutMs: 1000, width: 900, height: 600 }, session)
  const tokenB = Symbol('b')
  const count = page.patches.length
  await assert.rejects(page.getCompressionCanvas({ ownerToken: tokenB, timeoutMs: 1000, width: 800, height: 500 }, session), { code: 'MEDIA_COMPRESSION_CANVAS_BUSY' })
  assert.equal(page.patches.length, count)
  assert.strictEqual(page.compressionCanvasLease, a)
  a.release()
  const b = await page.getCompressionCanvas({ ownerToken: tokenB, timeoutMs: 1000, width: 800, height: 500 }, session)
  a.release()
  assert.strictEqual(page.compressionCanvasLease, b)
  assert.deepEqual([canvas.width, canvas.height], [800, 500])
  b.release(); session.dispose()
})

test('保存和选择期间所有编辑、标签、删除、重复保存入口均无副作用', async () => {
  const names = ['handleFileInput', 'handleOpenFileEditor', 'handleEditInput', 'handleConfirmFileEdit', 'handleRemoveFile', 'handleOpenTagPicker', 'handleToggleUnifiedTag', 'handleClearUnifiedTags', 'handleConfirmTagPicker', 'handleSubmit', 'handleChooseMedia']
  for (const state of ['saving', 'choosing']) {
    const { page } = loadPage()
    page.data[state] = true
    for (const name of names) await page[name]()
    assert.equal(page.patches.length, 0)
  }
})

test('成品SHA读取结束前离页不删除，完成后释放且不写回嵌套字段', async () => {
  const deleted = []
  const gate = deferred()
  const { page } = loadPage({ getFileSystemManager: () => ({ unlinkSync: p => deleted.push(p) }) })
  const file = { id: 'a', clientId: 'a', tempFilePath: '/prepared', size: 1 }
  page.data.files = [file]
  page.compressionOwnedPaths = { a: ['/prepared'] }
  page.calculateFileSha256 = () => gate.promise
  const pending = page.ensureFileSha256([file])
  await tick(); page.onUnload()
  const count = page.patches.length
  assert.deepEqual(deleted, [])
  gate.resolve('a'.repeat(64))
  await assert.rejects(pending, { code: runtime.COMPRESSION_CANCELLED })
  assert.deepEqual(deleted, ['/prepared'])
  assert.equal(page.patches.length, count)
})

for (const stage of ['setData', 'query']) {
  for (const outcome of ['failure', 'cancel', 'timeout']) {
    test(`同token第二次尺寸调整在${stage}阶段${outcome}仍撤销原lease`, async t => {
      t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
      let query = () => Promise.resolve(canvas)
      const { page, canvas, session } = canvasPage(() => query())
      const ownerToken = Symbol('a')
      const lease = await page.getCompressionCanvas({ ownerToken, timeoutMs: 1000, width: 900, height: 600 }, session)
      const originalSetData = page.setData
      const gate = deferred()
      if (stage === 'query') query = () => outcome === 'failure' ? Promise.reject(new Error('node missing')) : gate.promise
      else page.setData = (patch, callback) => {
        originalSetData(patch)
        if (callback && outcome === 'failure') throw new Error('setData failed')
      }
      const pending = page.getCompressionCanvas({ ownerToken, timeoutMs: 1000, width: 700, height: 400 }, session)
      const rejected = assert.rejects(pending, outcome === 'cancel' ? { code: runtime.COMPRESSION_CANCELLED } : outcome === 'timeout' ? { code: runtime.COMPRESSION_TIMEOUT } : /图片处理失败/)
      await tick()
      if (outcome === 'cancel') session.cancel()
      if (outcome === 'timeout') t.mock.timers.tick(1001)
      await rejected
      assert.equal(page.compressionCanvasLease, null)
      assert.deepEqual([canvas.width, canvas.height, page.data.compressionCanvasWidth, page.data.compressionCanvasHeight], [1, 1, 1, 1])
      const count = page.patches.length; lease.release(); assert.equal(page.patches.length, count)
      gate.resolve(canvas); await tick()
      assert.equal(page.compressionCanvasLease, null)
      session.dispose()
    })
  }
}

test('Canvas缺失节点显示图片处理文案，CSS回调之前不查询实际节点', async () => {
  let queries = 0, callback
  const { page, session } = canvasPage(() => { queries++; return Promise.resolve(null) })
  const originalSetData = page.setData
  page.setData = (patch, cb) => { originalSetData(patch); if (cb) callback = cb }
  const pending = page.getCompressionCanvas({ ownerToken: Symbol('a'), timeoutMs: 1000, width: 900, height: 600 }, session)
  assert.equal(queries, 0)
  callback()
  await assert.rejects(pending, /图片处理失败，请选择较小文件后重试/)
  assert.equal(queries, 1)
  assert.equal(page.compressionCanvasLease, null)
  session.dispose()
})

test('旧A查询迟到不能修改新会话B节点', async () => {
  const gate = deferred()
  let first = true
  const { page, api } = loadPage({}, { '../utils/work-thumbnail-crop': { getCanvasNode() { if (first) { first = false; return gate.promise }; return Promise.resolve(canvasB) } } })
  const canvasA = { width: 1, height: 1 }, canvasB = { width: 1, height: 1 }
  const a = runtime.createCompressionSession({ wxApi: api }); page.compressionSession = a
  const pending = page.getCompressionCanvas({ ownerToken: Symbol('a'), timeoutMs: 1000, width: 900, height: 600 }, a)
  await tick(); a.cancel()
  await assert.rejects(pending, { code: runtime.COMPRESSION_CANCELLED })
  const b = runtime.createCompressionSession({ wxApi: api }); page.compressionSession = b
  const lease = await page.getCompressionCanvas({ ownerToken: Symbol('b'), timeoutMs: 1000, width: 700, height: 400 }, b)
  gate.resolve(canvasA); await tick()
  assert.strictEqual(page.compressionCanvasLease, lease)
  assert.deepEqual([canvasB.width, canvasB.height], [700, 400])
  assert.deepEqual([canvasA.width, canvasA.height], [1, 1])
  lease.release(); b.dispose()
})

test('整批最终校验失败释放结果所有权而保留已有列表', async () => {
  const { page, deleted } = pipeline({ validateChosenMediaFiles: () => ({ valid: false, message: '批次校验失败' }) })
  const existing = { id: 'existing', clientId: 'existing', tempFilePath: '/existing' }
  page.data.files = [existing]
  await page.handleChooseMedia()
  assert.strictEqual(page.data.files[0], existing)
  assert.equal(page.data.files.length, 1)
  assert.deepEqual(deleted.sort(), ['/prepared/IMAGE', '/prepared/VIDEO'])
})

function realImageBatch({ invalidSecond = false, failSecond = false } = {}) {
  const deleted = [], encodes = []
  let serial = 0
  const { page, toasts, modals } = loadPage({
    chooseMedia(options) { options.success({ tempFiles: [1, 2].map(n => ({ tempFilePath: `/original${n}.jpg`, size: 1, fileType: 'image' })) }) },
    getFileSystemManager: () => ({
      statSync: p => ({ size: p.startsWith('/original') ? 12 * MB : 9 * MB }),
      unlinkSync: p => deleted.push(p)
    }),
    getImageInfo(options) {
      options.success({ width: invalidSecond && options.src === '/original2.jpg' ? 0 : 3000, height: 2000, type: 'jpg' })
    },
    compressImage(options) {
      encodes.push(options.src)
      if (failSecond && options.src === '/original2.jpg') options.fail({ errMsg: 'encoder failed' })
      else options.success({ tempFilePath: `/compressed${++serial}.jpg` })
    }
  })
  return { page, deleted, encodes, toasts, modals }
}

test('真实入口第二项预检失败时第一项编码为零', async () => {
  const { page, encodes, toasts, modals } = realImageBatch({ invalidSecond: true })
  await page.handleChooseMedia()
  assert.equal(page.data.files.length, 0)
  assert.equal(encodes.length, 0)
  assert.equal(toasts.length, 0)
  assert.deepEqual(modals, [{ title: '作品处理失败', content: page.data.errorMessage, showCancel: false }])
})

test('真实入口第二项运行期失败清第一项成品，重选重新编码并保留已有项', async () => {
  const { page, encodes, deleted } = realImageBatch({ failSecond: true })
  const existing = { id: 'existing', tempFilePath: '/existing', clientId: 'existing', mediaType: 'IMAGE', title: '已有', size: 1 }
  page.data.files = [existing]
  await page.handleChooseMedia()
  assert.deepEqual(deleted, ['/compressed1.jpg'])
  assert.equal(encodes.filter(p => p === '/original1.jpg').length, 1)
  assert.strictEqual(page.data.files[0], existing)
  await page.handleChooseMedia()
  assert.equal(encodes.filter(p => p === '/original1.jpg').length, 2)
  assert.deepEqual(deleted, ['/compressed1.jpg', '/compressed2.jpg'])
  assert.equal(page.data.files.length, 1)
})

test('移除仅释放对应clientId的自有成品', () => {
  const { page, deleted } = realImageBatch()
  page.data.files = [{ clientId: 'a', tempFilePath: '/owned-a' }, { clientId: 'b', tempFilePath: '/original-b' }]
  page.compressionOwnedPaths = { a: ['/owned-a'], b: [] }
  page.handleRemoveFile({ currentTarget: { dataset: { index: 0 } } })
  assert.deepEqual(deleted, ['/owned-a'])
  assert.equal(page.data.files[0].clientId, 'b')
  page.onUnload()
  assert.deepEqual(deleted, ['/owned-a'])
})

for (const kind of ['coverSha', 'coverPrepare']) {
  test(`离页等待${kind}读取完成后再删成品`, async () => {
    const deleted = [], gate = deferred()
    const overrides = kind === 'coverPrepare' ? { '../utils/work-upload': { ...upload, prepareCoverUploadFiles: () => gate.promise } } : {}
    const { page } = loadPage({ getFileSystemManager: () => ({ unlinkSync: p => deleted.push(p) }) }, overrides)
    const file = { id: 'a', clientId: 'a', tempFilePath: '/main', coverPath: '/owned', mediaType: 'IMAGE' }
    page.data.files = [file]; page.compressionOwnedPaths = { a: ['/owned', '/main'] }
    page.calculateFileSha256 = () => gate.promise
    const pending = kind === 'coverSha' ? page.ensureCoverSha256([file]) : page.uploadCustomCoverFiles()
    const rejected = assert.rejects(pending, { code: runtime.COMPRESSION_CANCELLED })
    await tick(); page.onUnload()
    assert.equal(deleted.includes('/owned'), false)
    if (kind === 'coverPrepare') assert.deepEqual(deleted, [])
    const count = page.patches.length
    gate.resolve(kind === 'coverPrepare' ? [file] : 'b'.repeat(64))
    await rejected
    assert.deepEqual(deleted.sort(), ['/main', '/owned'])
    assert.equal(page.patches.length, count)
  })
}

test('并发上传一项先失败，离页只清已完成项，另一项真实终态后才删除', async () => {
  const deleted = [], calls = [], aborted = []
  const { page } = loadPage({
    getFileSystemManager: () => ({ unlinkSync: p => deleted.push(p) }),
    uploadFile(options) { calls.push(options); return { abort() { aborted.push(options.filePath) }, onProgressUpdate() {} } }
  })
  const files = ['a', 'b'].map(id => ({ id, clientId: id, mediaType: 'IMAGE', tempFilePath: `/${id}`, uploadTicket: { uploadUrl: 'https://cos.invalid' } }))
  page.data.files = files; page.compressionOwnedPaths = { a: ['/a'], b: ['/b'] }
  const pending = upload.runWorkUploadQueue(files, file => page.uploadSingleFile(file))
  const rejected = assert.rejects(pending, /first failed/)
  calls[0].fail({ errMsg: 'first failed' }); await rejected
  page.onUnload()
  assert.equal(deleted.includes('/a'), true)
  assert.equal(deleted.includes('/b'), false)
  assert.ok(aborted.includes('/b'))
  const count = page.patches.length
  calls[1].success({ statusCode: 204 }); await tick()
  assert.deepEqual(deleted.sort(), ['/a', '/b'])
  assert.equal(page.patches.length, count)
})

test('没有可观察终态的上传离页后保持成品供微信生命周期回收', async () => {
  const deleted = []
  const { page } = loadPage({
    getFileSystemManager: () => ({ unlinkSync: p => deleted.push(p) }),
    uploadFile() { return undefined }
  })
  const file = { id: 'a', clientId: 'a', tempFilePath: '/a', uploadTicket: { uploadUrl: 'https://cos.invalid' } }
  page.data.files = [file]; page.compressionOwnedPaths = { a: ['/a'] }
  page.uploadSingleFile(file)
  await tick(); page.onUnload()
  assert.deepEqual(deleted, [])
})

test('真实保存链SHA、票据大小/MIME和COS路径均使用成品，失败重试不再压缩', async () => {
  const events = [], requests = [], uploads = []
  let encodeCount = 0, failUpload = true
  const { page, toasts } = loadPage({
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/original.jpg', size: 1, fileType: 'image' }] }) },
    getImageInfo(options) { options.success({ width: 3000, height: 2000, type: 'jpg' }) },
    compressImage(options) { encodeCount++; options.success({ tempFilePath: '/prepared.jpg' }) },
    getFileSystemManager: () => ({
      statSync: p => ({ size: p === '/original.jpg' ? 12 * MB : 90 * 1024 }),
      getFileInfo(options) { events.push(['sha', options.filePath]); options.success({ digest: 'a'.repeat(64) }) }
    }),
    uploadFile(options) {
      events.push(['upload', options.filePath]); uploads.push(options)
      if (failUpload) options.fail({ errMsg: 'network down' })
      else options.success({ statusCode: 204 })
      return { abort() {}, onProgressUpdate() {} }
    },
    redirectTo() {}
  }, { '../../../utils/request': { async request(options) {
    requests.push(options)
    if (options.url.endsWith('upload-tickets')) {
      events.push(['ticket', options.data.files[0].fileSize])
      return { items: options.data.files.map(file => ({ clientId: file.clientId, taskId: 't1', uploadUrl: 'https://cos.invalid', formData: { 'Content-Type': file.mimeType } })) }
    }
    return { items: [{ taskId: 't1', success: true, workId: 1 }] }
  } } })
  await page.handleChooseMedia()
  assert.equal(page.data.files[0].tempFilePath, '/prepared.jpg')
  assert.equal(encodeCount, 1)
  assert.deepEqual(events, [])
  await page.handleSubmit()
  assert.match(page.data.errorMessage, /network down/)
  assert.equal(page.data.files[0].sha256, 'a'.repeat(64))
  assert.deepEqual(events, [['sha', '/prepared.jpg'], ['ticket', 90 * 1024], ['upload', '/prepared.jpg']])
  const ticket = requests[0].data.files[0]
  assert.equal(ticket.mimeType, 'image/jpeg')
  assert.equal(ticket.fileName, 'original.jpg')
  assert.equal(uploads[0].formData['Content-Type'], ticket.mimeType)
  failUpload = false
  await page.handleSubmit()
  assert.equal(encodeCount, 1)
  assert.equal(events.filter(event => event[0] === 'sha').length, 1)
  assert.equal(uploads[1].filePath, '/prepared.jpg')
  assert.equal(page.data.files[0].status, 'CONFIRMED')
  assert.equal(toasts.at(-1), '已保存作品')
})

test('上传未结束时移除先保留成品，终态后即使页面未离开也释放', async () => {
  const deleted = []; let native
  const { page } = loadPage({
    getFileSystemManager: () => ({ unlinkSync: p => deleted.push(p) }),
    uploadFile(options) { native = options; return { abort() {}, onProgressUpdate() {} } }
  })
  const file = { id: 'a', clientId: 'a', tempFilePath: '/a', uploadTicket: { uploadUrl: 'https://cos.invalid' } }
  page.data.files = [file]; page.compressionOwnedPaths = { a: ['/a'] }
  const pending = page.uploadSingleFile(file)
  page.handleRemoveFile({ currentTarget: { dataset: { index: 0 } } })
  assert.deepEqual(deleted, [])
  native.success({ statusCode: 204 }); await pending
  assert.deepEqual(deleted, ['/a'])
})

test('上传失败后清除已结束task，旧请求结束不能移除同id的新上传task', async () => {
  const calls = [], tasks = []
  const { page } = loadPage({ uploadFile(options) {
    calls.push(options)
    const task = { abort() {}, onProgressUpdate() {} }; tasks.push(task); return task
  } })
  const file = { id: 'a', clientId: 'a', tempFilePath: '/a', uploadTicket: { uploadUrl: 'https://cos.invalid' } }
  page.data.files = [file]
  const first = page.uploadSingleFile(file)
  const second = page.uploadSingleFile(file)
  calls[0].success({ statusCode: 204 }); await first
  assert.strictEqual(page.uploadTasks.a, tasks[1])
  const rejected = assert.rejects(second, /failed/)
  calls[1].fail({ errMsg: 'failed' }); await rejected
  assert.equal(page.uploadTasks.a, undefined)
})

test('真实视频预处理成品格式、字节数、SHA与保存上传契约一致', async () => {
  const events = [], tickets = [], uploads = []
  const { page, toasts } = loadPage({
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/original.mp4', size: 1, fileType: 'video', duration: 1 }] }) },
    getVideoInfo(options) { options.success({ width: 1920, height: 1080, duration: 10, fps: 30, bitrate: 100000, type: options.src === '/result.mov' ? 'mov' : 'mp4' }) },
    compressVideo(options) { events.push(['compress', options.src]); options.success({ tempFilePath: '/result.mov' }) },
    getFileSystemManager: () => ({
      statSync: p => ({ size: p === '/original.mp4' ? 110 * MB : 98 * MB }),
      getFileInfo(options) { events.push(['sha', options.filePath]); options.success({ digest: 'b'.repeat(64) }) }
    }),
    uploadFile(options) { uploads.push(options); events.push(['upload', options.filePath]); options.success({ statusCode: 204 }); return { abort() {}, onProgressUpdate() {} } },
    redirectTo() {}
  }, { '../../../utils/request': { async request(options) {
    if (options.url.endsWith('upload-tickets')) {
      tickets.push(...options.data.files)
      events.push(['ticket', options.data.files[0].fileSize])
      return { items: options.data.files.map(file => ({ clientId: file.clientId, taskId: 'video-task', uploadUrl: 'https://cos.invalid', formData: { 'Content-Type': file.mimeType } })) }
    }
    return { items: [{ taskId: 'video-task', success: true, workId: 2 }] }
  } } })
  await page.handleChooseMedia()
  assert.deepEqual(toasts, [])
  assert.equal(page.data.files[0].tempFilePath, '/result.mov')
  assert.equal(page.data.files[0].durationMs, 10000)
  assert.deepEqual(events, [['compress', '/original.mp4']])
  await page.handleSubmit()
  assert.deepEqual(events, [['compress', '/original.mp4'], ['sha', '/result.mov'], ['ticket', 98 * MB], ['upload', '/result.mov']])
  assert.equal(tickets[0].mimeType, 'video/quicktime')
  assert.equal(tickets[0].fileName, 'original.mov')
  assert.equal(tickets[0].durationMs, 10000)
  assert.equal(tickets[0].sha256, 'b'.repeat(64))
  assert.equal(uploads[0].formData['Content-Type'], tickets[0].mimeType)
  assert.equal(page.data.files[0].status, 'CONFIRMED')
})

test('旧取消批次finally结束时新批次仍保持choosing与可取消状态', async () => {
  const gates = [deferred(), deferred()]
  let attempt = 0
  const { page } = pipeline({ prepareWorkMainFiles: () => gates[attempt++].promise })
  const first = page.handleChooseMedia()
  await tick(); page.handleCancelPreparation()
  const second = page.handleChooseMedia()
  await tick()
  gates[0].resolve({ files: [], ownedPathsByClientId: {} })
  await first
  assert.equal(page.data.choosing, true)
  assert.equal(page.data.canCancelPreparation, true)
  assert.equal(page.data.choosingText, '正在读取文件')
  assert.equal(page.data.choosingTitle, '正在读取文件')
  assert.equal(page.data.choosingHint, '正在准备作品信息')
  assert.equal(page.data.choosingCountText, '准备中')
  assert.equal(page.data.preparationCancelled, false)
  page.handleCancelPreparation()
  gates[1].resolve({ files: [], ownedPathsByClientId: {} })
  await second
})

test('prepare迟到返回清理已转移结果，不删除picker原件或已有文件', async () => {
  const gate = deferred(), deleted = []
  const { page } = pipeline({ prepareWorkMainFiles: () => gate.promise }, {
    getFileSystemManager: () => ({ statSync: () => ({ size: 1 }), unlinkSync: p => deleted.push(p) })
  })
  const first = page.handleChooseMedia()
  await tick(); page.onUnload()
  gate.resolve({ files: [], ownedPathsByClientId: { generated: ['/new-only'] } })
  await first
  assert.deepEqual(deleted, ['/new-only'])
})

test('复用真实getCanvasNode按页面和唯一Canvas ID查询节点', async () => {
  const canvas = { width: 1, height: 1 }, calls = []
  const { page, api } = loadPage({ createSelectorQuery() {
    const query = {
      in(target) { calls.push(target); return query },
      select(selector) { calls.push(selector); return query },
      fields(options) { assert.equal(options.node, true); return query },
      exec(callback) { callback([{ node: canvas }]) }
    }
    return query
  } })
  const session = runtime.createCompressionSession({ wxApi: api }); page.compressionSession = session
  const lease = await page.getCompressionCanvas({ width: 900, height: 600, ownerToken: Symbol('a'), timeoutMs: 1000 }, session)
  assert.strictEqual(calls[0], page)
  assert.equal(calls[1], '#workCompressionCanvas')
  assert.strictEqual(lease.canvas, canvas)
  lease.release(); session.dispose()
})

test('封面SHA结果返回与后续申请票据之间离页也不请求网络', async () => {
  const gate = deferred()
  const file = { id: 'a', clientId: 'a', mediaType: 'IMAGE', tempFilePath: '/a', coverPath: '/cover', coverSize: 100, coverFileName: 'cover.jpg', coverSha256: 'a'.repeat(64) }
  const { page } = loadPage({}, { '../utils/work-upload': { ...upload, prepareCoverUploadFiles: async () => [file] } })
  page.data.files = [file]
  page.ensureCoverSha256 = () => gate.promise
  const pending = page.uploadCustomCoverFiles()
  const rejected = assert.rejects(pending, { code: runtime.COMPRESSION_CANCELLED })
  await tick(); page.onUnload(); gate.resolve([file])
  await rejected
})

function pendingPngPage() {
  const exports = [], deleted = [], draws = []
  const canvas = {
    width: 1, height: 1,
    getContext: () => ({ clearRect() {}, drawImage: (...args) => draws.push(args) }),
    createImage() { return { set src(value) { if (this.onload) this.onload() } } }
  }
  const { page, api, toasts, modals } = loadPage({
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/original.png', fileType: 'image', size: 12 * MB }] }) },
    getFileSystemManager: () => ({ statSync: p => ({ size: p === '/original.png' ? 12 * MB : 9.8 * MB | 0 }), unlinkSync: p => deleted.push(p) }),
    getImageInfo(options) { options.success({ width: 3000, height: 2000, type: 'png' }) },
    canvasToTempFilePath(options) { exports.push(options) },
    createSelectorQuery() {
      const query = { in: () => query, select: () => query, fields: () => query, exec: callback => callback([{ node: canvas }]) }
      return query
    }
  })
  return { page, api, canvas, exports, deleted, draws, toasts, modals }
}

test('真实PNG取消及编码租约到期均保留旧节点，迟到原生终态才释放', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
  const { page, canvas, exports, deleted, modals } = pendingPngPage()
  const pending = page.handleChooseMedia()
  await tick()
  assert.equal(exports.length, 1)
  const lease = page.compressionCanvasLease
  page.handleCancelPreparation(); await pending
  assert.strictEqual(page.compressionCanvasLease, lease)
  assert.deepEqual([canvas.width, canvas.height], [3000, 2000])
  const sizePatches = () => page.patches.filter(patch => patch.compressionCanvasWidth !== undefined).length
  const before = sizePatches()
  await page.handleChooseMedia()
  assert.equal(modals.at(-1).content, '画布处理尚未结束，请返回后重新进入')
  assert.equal(sizePatches(), before)
  t.mock.timers.tick(runtime.IMAGE_ATTEMPT_TIMEOUT_MS * 2 + 1)
  assert.strictEqual(page.compressionCanvasLease, lease)
  await page.handleChooseMedia()
  assert.equal(exports.length, 1)
  assert.equal(sizePatches(), before)
  exports[0].success({ tempFilePath: '/late.png' }); await tick()
  assert.deepEqual(deleted, ['/late.png'])
  assert.equal(page.compressionCanvasLease, null)
  assert.deepEqual([canvas.width, canvas.height], [1, 1])
  const next = page.handleChooseMedia(); await tick()
  assert.equal(exports.length, 2)
  exports[1].success({ tempFilePath: '/success.png' }); await next
  assert.equal(page.data.files.length, 1)
  assert.equal(page.data.files[0].tempFilePath, '/success.png')
  assert.equal(page.compressionCanvasLease, null)
  page.onUnload()
})

test('真实PNG离页后旧导出终态不再写setData或改动实际节点，重进使用独立Canvas', async () => {
  const old = pendingPngPage()
  const pending = old.page.handleChooseMedia(); await tick()
  assert.equal(old.exports.length, 1)
  old.page.onUnload(); await pending
  const patches = old.page.patches.length
  assert.deepEqual([old.canvas.width, old.canvas.height], [3000, 2000])
  old.exports[0].success({ tempFilePath: '/late-old.png' }); await tick()
  assert.equal(old.page.patches.length, patches)
  assert.deepEqual([old.canvas.width, old.canvas.height], [3000, 2000])
  // 原生锁真实结束后重新进入，旧节点和新页面节点相互独立。
  const next = pendingPngPage()
  const second = next.page.handleChooseMedia(); await tick()
  assert.equal(next.exports.length, 1)
  assert.notStrictEqual(next.canvas, old.canvas)
  old.exports[0].success({ tempFilePath: '/late-old.png' }); await tick()
  assert.strictEqual(next.page.compressionCanvasLease.canvas, next.canvas)
  next.exports[0].success({ tempFilePath: '/new.png' }); await second
  assert.equal(next.page.data.files.length, 1)
  assert.deepEqual(old.deleted, ['/late-old.png'])
  next.page.onUnload()
})

test('同批第一张PNG精调超时有best时，下一图片也不能修改仍在导出的节点', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
  const { page, api, canvas, exports, deleted, draws, modals } = pendingPngPage()
  api.chooseMedia = options => options.success({ tempFiles: [1, 2].map(n => ({ tempFilePath: `/original${n}.png`, fileType: 'image', size: 12 * MB })) })
  api.getFileSystemManager = () => ({ statSync: p => ({ size: p === '/best.png' ? 9 * MB : 12 * MB }), unlinkSync: p => deleted.push(p) })
  const existing = { id: 'existing', clientId: 'existing', title: '已有', mediaType: 'IMAGE', size: 1 }
  page.data.files = [existing]
  const pending = page.handleChooseMedia(); await tick()
  assert.equal(exports.length, 1)
  exports[0].success({ tempFilePath: '/oversize.png' }); await tick()
  assert.equal(exports.length, 2)
  exports[1].success({ tempFilePath: '/best.png' }); await tick()
  assert.equal(exports.length, 3)
  const lease = page.compressionCanvasLease
  const size = [canvas.width, canvas.height], drawingCount = draws.length
  t.mock.timers.tick(runtime.IMAGE_ATTEMPT_TIMEOUT_MS + 1)
  await pending
  assert.equal(modals.at(-1).content, '画布处理尚未结束，请返回后重新进入')
  assert.strictEqual(page.compressionCanvasLease, lease)
  assert.deepEqual([canvas.width, canvas.height], size)
  assert.equal(draws.length, drawingCount)
  assert.equal(page.data.files.length, 1)
  assert.strictEqual(page.data.files[0], existing)
  assert.deepEqual(deleted.sort(), ['/best.png', '/oversize.png'])
  exports[2].success({ tempFilePath: '/late-refinement.png' }); await tick()
  assert.equal(page.compressionCanvasLease, null)
  assert.deepEqual([canvas.width, canvas.height], [1, 1])
  assert.ok(deleted.includes('/late-refinement.png'))
})

test('失败上传批次不再启动已移除的排队文件，重新保存可上传当前列表', async () => {
  const calls = [], deleted = [], confirmations = []
  const { page } = loadPage({
    getFileSystemManager: () => ({ unlinkSync: p => deleted.push(p) }),
    uploadFile(options) { calls.push(options); return { abort() {}, onProgressUpdate() {} } },
    redirectTo() {}
  }, { '../../../utils/request': { async request(options) {
    assert.equal(options.url, '/api/mine/works/upload-complete')
    confirmations.push(options.data.items)
    return { items: options.data.items.map(item => ({ taskId: item.taskId, success: true, workId: item.taskId })) }
  } } })
  page.data.files = ['a', 'b', 'c'].map(id => ({
    id, clientId: id, tempFilePath: `/${id}`, fileName: `${id}.jpg`, title: id,
    mediaType: 'IMAGE', mimeType: 'image/jpeg', size: 1, sha256: 'a'.repeat(64),
    taskId: `task-${id}`, status: 'READY', uploadTicket: { uploadUrl: 'https://cos.invalid' }
  }))
  page.compressionOwnedPaths = { a: ['/a'], b: ['/b'], c: ['/c'] }
  const first = page.handleSubmit(); await tick()
  assert.deepEqual(calls.map(call => call.filePath), ['/a', '/b'])
  calls[0].fail({ errMsg: 'first upload failed' }); await first
  assert.equal(page.data.saving, false)
  assert.match(page.data.errorMessage, /first upload failed/)
  page.handleRemoveFile({ currentTarget: { dataset: { index: 2 } } })
  assert.deepEqual(deleted, ['/c'])
  calls[1].success({ statusCode: 204 }); await tick()
  assert.deepEqual(calls.map(call => call.filePath), ['/a', '/b'])
  const retry = page.handleSubmit(); await tick()
  assert.deepEqual(calls.map(call => call.filePath), ['/a', '/b', '/a'])
  calls[2].success({ statusCode: 204 }); await retry
  assert.deepEqual(Array.from(page.data.files, file => file.id), ['a', 'b'])
  assert.ok(page.data.files.every(file => file.status === 'CONFIRMED'))
  assert.deepEqual(Array.from(confirmations[0], item => item.taskId), ['task-a', 'task-b'])
  assert.deepEqual(deleted, ['/c'])
  page.onUnload()
})
