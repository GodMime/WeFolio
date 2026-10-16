const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const personal = require('../pages/portfolios/utils/advanced-reference-attachments')
const team = require('../pages/team-portfolios/utils/advanced-reference-attachments')
const { captureBasis, buildTaskPayload, validatePrompt, normalizeContext } = require('../pages/portfolios/utils/advanced-portfolios')
const { createAdvancedAiPage } = require('../pages/portfolios/utils/advanced-ai-page')
const { createAdvancedAiPage: createTeamPage } = require('../pages/team-portfolios/utils/advanced-ai-page')
const { installAdvancedEditor, editorState, advancedEditorPageMethods, loadAdvancedMetadata, markAdvancedSaved } = require('../pages/portfolios/utils/advanced-editor-page')
const ID1 = 'a'.repeat(32)
const ID2 = 'b'.repeat(32)
const caps = { enabled: true, referenceAttachments: { enabled: true, ...personal.LIMITS } }
const reference = (patch = {}) => ({ attachmentId: ID1, referenceNo: 'A1', mediaType: 'IMAGE', note: '参考留白',
  attachmentToken: 'receipt', status: 'READY', originalFileName: '参考.jpg', sizeBytes: 1000, ...patch })
const state = (patch = {}) => ({ ownerType: 'USER', ownerId: '1', portfolioId: null, draftRevision: 0, localEditRevision: 0,
  config: { components: [] }, aiContext: normalizeContext(), aiReferenceAttachments: [], aiReferenceNextNo: 1, ...patch })
const tick = () => new Promise(resolve => setImmediate(resolve))
async function until(check) { for (let index = 0; index < 100; index++) { if (check()) return; await tick() }; assert.fail('异步附件处理未进入预期状态') }
function uploaderFixture(module = personal, overrides = {}) {
  const changes = new Map(); const calls = []; const uploads = []; const compressed = []; const deleted = []
  const sizes = { '/one.jpg': 1000, '/two.jpg': 1000, '/three.jpg': 1000, '/one.mp4': 120000000, '/out1.mp4': 105000000, '/out2.mp4': 95000000 }
  const wxApi = {
    getFileSystemManager: () => ({ statSync: file => ({ size: sizes[file] }), unlinkSync: file => deleted.push(file) }),
    getImageInfo: options => options.success({ width: 100, height: 100, type: 'jpeg' }),
    getVideoInfo: options => options.success({ width: 1280, height: 720, duration: 200, fps: 25, type: 'mp4', videoCodec: 'h264' }),
    compressVideo: options => { compressed.push(options); options.success({ tempFilePath: `/out${compressed.length}.mp4` }) },
    uploadFile: options => { uploads.push(options); options.success({ statusCode: 204 }); return { abort() {}, onProgressUpdate() {} } },
    ...overrides
  }
  const request = async options => {
    calls.push(options)
    if (options.url.endsWith('upload-tickets')) return { uploadToken: 'upload', uploadUrl: 'https://cos.invalid', formData: { key: 'server-key' } }
    return reference({ attachmentToken: 'confirmed', sizeBytes: 1000 })
  }
  const uploader = module.createReferenceUploader({ request, wxApi, owner: { ownerType: 'TEAM', ownerId: '2' }, limits: personal.LIMITS,
    onChange: (key, patch) => changes.set(key, { ...(changes.get(key) || {}), ...patch }) })
  return { uploader, wxApi, request, changes, calls, uploads, compressed, sizes, deleted }
}

for (const [name, module] of [['个人', personal], ['团队', team]]) {
  test(`${name}无附件不增加保存字段，显式移除保留高水位`, () => {
    assert.deepEqual(module.referenceSaveFields(state()), {})
    assert.deepEqual(module.referenceSaveFields(state({ aiReferenceNextNo: 5 })), { aiReferenceAttachments: [], aiReferenceNextNo: 5 })
    assert.equal(module.referenceNextNo([reference({ referenceNo: 'A8' })], 4), 9)
    assert.equal(module.referenceNextNo([], 9), 9)
    assert.equal(module.referenceLimits({ referenceAttachments: { enabled: true, maxVideos: 0 } }).maxVideos, 0)
  })
  test(`${name}保存和任务只发送业务条目及回执，不发送key或文件事实`, () => {
    const item = reference({ objectKey: 'private', previewUrl: 'https://signed.invalid', width: 400, nextReferenceNo: 999 })
    assert.deepEqual(module.referencePayload([item]), [{ attachmentId: ID1, referenceNo: 'A1', note: '参考留白', attachmentToken: 'receipt' }])
    assert.throws(() => module.referencePayload([reference({ status: 'COMPRESSING' })]), /等待参考附件/)
    assert.throws(() => module.referencePayload([reference({ note: '🙂'.repeat(201) })]), /说明无效/)
    assert.equal(module.referencePayload([reference({ note: '🙂'.repeat(200) })])[0].note.length, 400)
    assert.match(module.validateReferenceInput([item], {}), /暂未开放/)
    assert.equal(module.validateReferenceInput([], {}), '')
  })
  test(`${name}图片上传按stat申请精确类型票据，确认后才READY`, async () => {
    const f = uploaderFixture(module)
    f.uploader.add('one', { tempFilePath: '/one.jpg', fileType: 'image', size: 9000000 })
    await until(() => f.changes.get('one')?.status === 'READY')
    assert.deepEqual(f.calls[0].data, { ownerType: 'TEAM', ownerId: '2', originalFileName: 'one.jpg', mimeType: 'image/jpeg', sizeBytes: 1000 })
    assert.equal(f.uploads[0].formData.key, 'server-key')
    assert.equal(f.uploads[0].timeout, 180000, '原生上传超时与本地后备计时器一致')
    assert.equal(f.uploads[0].header, undefined, 'COS上传不附维护者token')
    assert.deepEqual(f.calls[1].data, { uploadToken: 'upload', mediaInfo: {
      mimeType: 'image/jpeg', codec: '', sizeBytes: 1000, width: 100, height: 100, durationMs: null
    } })
    assert.equal(f.calls[1].timeout, 150000, '确认请求保留现有上传超时预算')
    assert.equal(f.changes.get('one').attachmentToken, 'confirmed')
    f.uploader.dispose(); assert.deepEqual(f.deleted, [])
  })
  test(`${name}视频最多两次编码，成品stat仍超限不得上传`, async () => {
    const f = uploaderFixture(module)
    f.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
    await until(() => f.changes.get('video')?.status === 'READY')
    assert.equal(f.compressed.length, 2)
    assert.equal(f.uploads[0].filePath, '/out2.mp4')
    assert.equal(f.calls[0].data.sizeBytes, 95000000)
    assert.equal(f.compressed[0].quality, undefined)
    assert.ok(f.compressed[1].bitrate < f.compressed[0].bitrate)
    f.uploader.dispose()
    assert.equal(f.deleted.includes('/one.mp4'), false)
    const bad = uploaderFixture(module); bad.sizes['/out2.mp4'] = 100000001
    bad.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
    await until(() => bad.changes.get('video')?.status === 'FAILED')
    assert.equal(bad.uploads.length, 0); assert.equal(bad.compressed.length, 2)
    bad.uploader.dispose()
  })
  for (const videoCodec of ['h264', 'H.264', 'AVC1', 'hevc', 'H265', 'H.265', 'hvc1', 'HEV1']) {
    test(`${name}合规小体积MP4 ${videoCodec}视频直接上传原文件`, async () => {
      const f = uploaderFixture(module, { getVideoInfo: options => options.success({ width: 888, height: 1920,
        duration: 41.029, type: 'mp4', videoCodec }) })
      f.sizes['/one.mp4'] = 3814642
      try {
        f.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
        await until(() => ['READY', 'FAILED'].includes(f.changes.get('video')?.status))
        assert.equal(f.changes.get('video').status, 'READY')
        assert.equal(f.compressed.length, 0, '已符合格式和大小要求的视频无需重新编码')
        assert.equal(f.uploads[0].filePath, '/one.mp4')
        assert.equal(f.calls[0].data.mimeType, 'video/mp4')
        assert.equal(f.calls[0].data.sizeBytes, 3814642)
        assert.deepEqual(f.calls[1].data.mediaInfo, { mimeType: 'video/mp4', codec: videoCodec,
          sizeBytes: 3814642, width: 888, height: 1920, durationMs: 41029 })
      } finally { f.uploader.dispose() }
    })
  }
  test(`${name}MP4缺少编码信息时原样上传并如实上报空编码`, async () => {
    const f = uploaderFixture(module, { getVideoInfo: options => options.success({ width: 888, height: 1920,
      duration: 41.029, type: 'mp4' }) })
    f.sizes['/one.mp4'] = 3814642
    try {
      f.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
      await until(() => f.changes.get('video')?.status === 'READY')
      assert.equal(f.compressed.length, 0)
      assert.equal(f.uploads[0].filePath, '/one.mp4')
      assert.deepEqual(f.calls.find(item => item.url.endsWith('complete')).data.mediaInfo, {
        mimeType: 'video/mp4', codec: '', sizeBytes: 3814642, width: 888, height: 1920, durationMs: 41029
      })
    } finally { f.uploader.dispose() }
  })
  test(`${name}超限HEVC视频仍压缩且接受合规H265成品`, async () => {
    const f = uploaderFixture(module, { getVideoInfo: options => options.success(options.src === '/one.mp4'
      ? { width: 1920, height: 1080, duration: 201, type: 'mp4', videoCodec: 'hevc' }
      : { width: 1280, height: 720, duration: 200, type: 'mp4', videoCodec: 'hvc1' }) })
    f.sizes['/out1.mp4'] = 90000000
    try {
      f.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
      await until(() => f.changes.get('video')?.status === 'READY')
      assert.equal(f.compressed.length, 1)
      assert.equal(f.uploads[0].filePath, '/out1.mp4')
      assert.equal(f.calls[0].data.sizeBytes, 90000000)
      assert.deepEqual(f.calls[1].data.mediaInfo, { mimeType: 'video/mp4', codec: 'hvc1',
        sizeBytes: 90000000, width: 1280, height: 720, durationMs: 200000 })
    } finally { f.uploader.dispose() }
  })
  for (const videoCodec of ['av1', 'vp9']) {
    test(`${name}转换后仍为${videoCodec}的视频不申请票据且提示支持格式`, async () => {
      const f = uploaderFixture(module, { getVideoInfo: options => options.success({ width: 888, height: 1920,
        duration: 41.029, type: 'mp4', videoCodec }) })
      f.sizes['/one.mp4'] = 3814642
      f.sizes['/out1.mp4'] = 3000000
      try {
        f.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
        await until(() => f.changes.get('video')?.status === 'FAILED')
        assert.equal(f.compressed.length, 1, '明确不支持的编码即使体积小也尝试转换')
        assert.equal(f.calls.length, 0)
        assert.equal(f.uploads.length, 0)
        assert.equal(f.changes.get('video').errorMessage, '请转换为 MP4（H.264 或 H.265/HEVC）视频后重新选择')
      } finally { f.uploader.dispose() }
    })
  }
  test(`${name}五分钟和500MB源边界在模型和上传之前检查`, async () => {
    const tooLong = uploaderFixture(module, { getVideoInfo: options => options.success({ width: 1280, height: 720, duration: 300.001 }) })
    tooLong.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
    await until(() => tooLong.changes.get('video')?.status === 'FAILED')
    assert.equal(tooLong.compressed.length, 0); assert.equal(tooLong.calls.length, 0)
    const tooLarge = uploaderFixture(module); tooLarge.sizes['/one.mp4'] = 500000001
    tooLarge.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
    await until(() => tooLarge.changes.get('video')?.status === 'FAILED')
    assert.equal(tooLarge.calls.length, 0)
    const limit = uploaderFixture(module, { getVideoInfo: options => options.success({ width: 1280, height: 720, duration: 300, type: 'mp4', videoCodec: 'h264' }) })
    limit.sizes['/one.mp4'] = 100000000
    limit.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
    await until(() => limit.changes.get('video')?.status === 'READY')
    assert.equal(limit.compressed.length, 0)
  })
  test(`${name}上传并发最多2，取消不让迟到回调确认或添加附件`, async () => {
    let activeUploads = 0; let maximum = 0; const pending = []
    const f = uploaderFixture(module, { uploadFile: options => {
      activeUploads++; maximum = Math.max(maximum, activeUploads); pending.push(options)
      return { abort() { activeUploads--; options.fail() }, onProgressUpdate() {} }
    } })
    for (const name of ['one','two','three']) f.uploader.add(name, { tempFilePath: `/${name}.jpg`, fileType: 'image' })
    await until(() => pending.length === 2)
    assert.equal(maximum, 2)
    f.uploader.cancel('one')
    await until(() => pending.length === 3)
    pending[0].success({ statusCode: 204 })
    for (const item of pending.slice(1)) { activeUploads--; item.success({ statusCode: 204 }) }
    await until(() => f.changes.get('three')?.status === 'READY')
    assert.notEqual(f.changes.get('one').status, 'READY')
    assert.equal(f.calls.filter(item => item.url.endsWith('complete')).length, 2)
    assert.equal(maximum, 2)
  })
}

test('逻辑取消后跨分包等待真实原生编码回调，不并发编码或上传被移除视频', async () => {
  const encodes = []
  const first = uploaderFixture(personal, { compressVideo: options => encodes.push(options) })
  const secondChanges = []
  const second = team.createReferenceUploader({ request: first.request, wxApi: first.wxApi, owner: { ownerType: 'USER', ownerId: '1' }, limits: personal.LIMITS,
    onChange: (key, patch) => secondChanges.push(patch) })
  first.sizes['/out1.mp4'] = 90000000
  first.uploader.add('first', { tempFilePath: '/one.mp4', fileType: 'video' })
  await until(() => encodes.length === 1)
  first.uploader.cancel('first')
  second.add('second', { tempFilePath: '/one.mp4', fileType: 'video' })
  await tick(); await tick(); assert.equal(encodes.length, 1)
  encodes[0].success({ tempFilePath: '/out1.mp4' })
  await until(() => encodes.length === 2)
  assert.equal(first.uploads.length, 0)
  assert.equal(first.deleted.includes('/out1.mp4'), true, '迟到的已取消编码输出也应清理')
  encodes[1].success({ tempFilePath: '/out1.mp4' })
  await until(() => secondChanges.some(item => item.status === 'READY'))
  assert.equal(first.uploads.length, 1)
  first.uploader.dispose(); second.dispose()
})

for (const [name, module] of [['个人', personal], ['团队', team]]) {
test(`${name}确认失败重试沿用已上传对象和原媒体信息，不重新上传`, async () => {
  const f = uploaderFixture(module); const confirmations = []
  const changes = []
  const uploader = module.createReferenceUploader({ request: async options => {
    if (options.url.endsWith('complete')) {
      confirmations.push(options.data)
      if (confirmations.length === 1) throw new Error('确认暂时失败')
    }
    return f.request(options)
  }, wxApi: f.wxApi, owner: { ownerType: 'USER', ownerId: '1' }, limits: module.LIMITS, onChange: (key, patch) => changes.push(patch) })
  uploader.add('item', { tempFilePath: '/one.jpg', fileType: 'image' })
  await until(() => changes.some(item => item.status === 'FAILED'))
  f.sizes['/one.jpg'] = 1234
  f.wxApi.getImageInfo = () => assert.fail('确认重试不能重新读取或改变已上传文件的信息')
  uploader.retry('item')
  await until(() => changes.some(item => item.status === 'READY'))
  assert.equal(f.uploads.length, 1)
  assert.equal(f.calls.filter(item => item.url.endsWith('upload-tickets')).length, 1)
  assert.equal(f.calls.find(item => item.url.endsWith('complete')).timeout, 150000)
  assert.equal(confirmations.length, 2)
  assert.deepEqual(confirmations[0], { uploadToken: 'upload', mediaInfo: {
    mimeType: 'image/jpeg', codec: '', sizeBytes: 1000, width: 100, height: 100, durationMs: null
  } })
  assert.deepEqual(confirmations[1], confirmations[0])
  uploader.dispose(); f.uploader.dispose()
})

test(`${name}上传响应未知时携带原媒体信息确认已存在对象，禁止覆盖重传`, async () => {
  const f = uploaderFixture(module, { uploadFile: options => { f.uploads.push(options); options.fail(); return { abort() {} } } })
  f.uploader.add('item', { tempFilePath: '/one.jpg', fileType: 'image' })
  await until(() => f.changes.get('item')?.status === 'FAILED')
  f.uploader.retry('item')
  await until(() => f.changes.get('item')?.status === 'READY')
  assert.equal(f.uploads.length, 1)
  assert.equal(f.calls.filter(item => item.url.endsWith('upload-tickets')).length, 1)
  assert.equal(f.calls.find(item => item.url.endsWith('complete')).timeout, 150000)
  assert.deepEqual(f.calls.find(item => item.url.endsWith('complete')).data, { uploadToken: 'upload', mediaInfo: {
    mimeType: 'image/jpeg', codec: '', sizeBytes: 1000, width: 100, height: 100, durationMs: null
  } })
  f.uploader.dispose()
})

test(`${name}确认期间取消仍保留已发出的媒体信息，迟到回执不恢复附件`, async () => {
  const f = uploaderFixture(module); const changes = []; const confirmations = []; let finish
  const uploader = module.createReferenceUploader({ request: options => {
    if (!options.url.endsWith('complete')) return f.request(options)
    confirmations.push(options.data)
    return new Promise(resolve => { finish = resolve })
  }, wxApi: f.wxApi, owner: { ownerType: 'USER', ownerId: '1' }, limits: module.LIMITS, onChange: (key, patch) => changes.push(patch) })
  uploader.add('item', { tempFilePath: '/one.jpg', fileType: 'image' })
  await until(() => confirmations.length === 1)
  uploader.cancel('item')
  finish(reference())
  await tick(); await tick()
  assert.equal(confirmations.length, 1)
  assert.deepEqual(confirmations[0], { uploadToken: 'upload', mediaInfo: {
    mimeType: 'image/jpeg', codec: '', sizeBytes: 1000, width: 100, height: 100, durationMs: null
  } })
  assert.equal(changes.some(item => item.status === 'READY'), false)
  uploader.dispose(); f.uploader.dispose()
})
}

test('附件业务变更使候选失效，回执和预览刷新不改变基准；A编号不被作品解析器误读', () => {
  const initial = state({ aiReferenceAttachments: [reference()], aiReferenceNextNo: 2 })
  const basis = captureBasis(initial)
  assert.equal(captureBasis({ ...initial, aiReferenceAttachments: [reference({ attachmentToken: 'new', previewUrl: 'new' })] }), basis)
  for (const item of [reference({ attachmentId: ID2 }), reference({ referenceNo: 'A3' }), reference({ note: '另一种风格' })]) {
    assert.notEqual(captureBasis({ ...initial, aiReferenceAttachments: [item] }), basis)
  }
  assert.equal(validatePrompt('参考A1号的配色', normalizeContext()), '')
  assert.match(validatePrompt('参考A1号，将2号设为封面', normalizeContext()), /2 号/)
  assert.equal(buildTaskPayload(state(), { mode: 'MODIFY_PAGE', prompt: '调整' }, 'key').referenceAttachments, undefined)
  assert.deepEqual(buildTaskPayload(initial, { mode: 'MODIFY_PAGE', prompt: '调整' }, 'key').referenceAttachments, personal.referencePayload(initial.aiReferenceAttachments))
})

function pageFor(teamMode, request, patch = {}) {
  const definition = (teamMode ? createTeamPage : createAdvancedAiPage)(teamMode, { requestFn: request })
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch) } }
  Object.assign(page.data, state(), { mode: 'MODIFY_PAGE', prompt: '调整配色', capabilities: caps }, patch)
  page.schedulePoll = () => {}
  return page
}
for (const teamMode of [false, true]) {
  test(`${teamMode ? '团队' : '个人'}修改附件退出追问、保留高水位，未知提交期间不许改附件`, () => {
    const page = pageFor(teamMode, async () => {}, { aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2,
      questions: [{ id: 'q' }], clarificationAnswer: { questionId: 'q', answerText: '好' }, canAdopt: true })
    page.handleReferenceNote({ currentTarget: { dataset: { key: ID1 } }, detail: { value: '🙂'.repeat(201) } })
    assert.equal(Array.from(page.data.aiReferenceAttachments[0].note).length, 200)
    assert.deepEqual(page.data.questions, []); assert.equal(page.data.clarificationAnswer, null); assert.equal(page.data.canAdopt, false)
    page.data.submissionUncertain = true
    page.handleRemoveReference({ currentTarget: { dataset: { key: ID1 } } })
    assert.equal(page.data.aiReferenceAttachments.length, 1)
    page.data.submissionUncertain = false
    page.handleRemoveReference({ currentTarget: { dataset: { key: ID1 } } })
    assert.equal(page.data.aiReferenceAttachments.length, 0); assert.equal(page.data.aiReferenceNextNo, 2)
  })
  test(`${teamMode ? '团队' : '个人'}未知提交重试相同附件回执和幂等键`, async () => {
    const oldWx = global.wx; global.wx = { showToast() {} }
    try {
      const calls = []
      const page = pageFor(teamMode, async options => { calls.push(options.data); if (calls.length === 1) throw new Error('timeout'); return { taskId: '1', status: 'PENDING' } },
        { aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2 })
      await page.handleGenerate(); assert.equal(page.data.submissionUncertain, true)
      page.data.aiReferenceAttachments[0].attachmentToken = 'refreshed'
      await page.handleGenerate(); assert.deepEqual(calls[1], calls[0])
    } finally { global.wx = oldWx }
  })
  test(`${teamMode ? '团队' : '个人'}新建采用把完整维护清单及高水位交给编辑器，不自动保存`, async () => {
    const oldWx = global.wx; let received; let requestCount = 0
    global.wx = { navigateTo: options => options.success({ eventChannel: { emit: (name, value) => { received = { name, value } } } }) }
    try {
      const page = pageFor(teamMode, async () => { requestCount++; return { status: 'SUCCEEDED', baseDraftRevision: 0, localEditRevision: 0,
        result: { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: { components: [] } }, referenceAttachments: [reference({ attachmentToken: 'new-receipt' })] } },
        { mode: 'CREATE', task: { taskId: '1' }, canAdopt: true, aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 4 })
      page.taskBasis = captureBasis(page.currentState())
      await page.handleAdopt()
      assert.equal(requestCount, 1)
      assert.equal(received.name, 'advancedInitial')
      assert.equal(received.value.aiReferenceNextNo, 4)
      assert.equal(received.value.aiReferenceAttachments[0].attachmentToken, 'new-receipt')
      assert.equal(received.value.portfolioId, null)
    } finally { global.wx = oldWx }
  })
}

test('编辑器附件改变进入dirty和保存，回执刷新不影响修订；撤销恢复附件且高水位不回退', () => {
  const oldWx = global.wx; global.wx = { enableAlertBeforeUnload() {}, disableAlertBeforeUnload() {} }
  try {
    const page = { ...advancedEditorPageMethods, data: { config: { components: [] } }, setData(patch) { Object.assign(this.data, patch) },
      applyEditorConfig(config) { this.setData({ config }) } }
    installAdvancedEditor(page, { templateType: 'ADVANCED', ownerId: '1' })
    page.setData({ aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2 })
    markAdvancedSaved(page)
    const revision = page.data.localEditRevision
    page.setData({ aiReferenceAttachments: personal.normalizeReferenceItems([reference({ attachmentToken: 'renewed' })]) })
    assert.equal(page.data.advancedDirty, false); assert.equal(page.data.localEditRevision, revision)
    page.setData({ aiReferenceAttachments: personal.normalizeReferenceItems([reference({ note: '新配色' })]) })
    assert.equal(page.data.advancedDirty, true); assert.ok(page.data.localEditRevision > revision)
    const payload = page.advancedDraftPayload({ config: {} })
    assert.equal(payload.aiReferenceAttachments[0].note, '新配色')
    page.setData({ aiReferenceAttachments: [], aiReferenceNextNo: 5, advancedCanUndo: true })
    page.setData({ advancedCanUndo: true })
    page.advancedUndo = { config: { components: [] }, aiContext: normalizeContext(), prompt: '', aiReferenceAttachments: [reference()], aiReferenceNextNo: 2 }
    page.handleAdvancedUndo()
    assert.equal(page.data.aiReferenceAttachments[0].attachmentId, ID1)
    assert.equal(page.data.aiReferenceNextNo, 5)
    assert.ok(editorState(page).aiReferenceAttachments.length)
  } finally { global.wx = oldWx }
})

test('两分包参考附件实现逐字一致且均可从真实高级页面到达', () => {
  for (const name of ['advanced-reference-attachments.js','advanced-reference-page.js']) {
    const read = folder => fs.readFileSync(path.join(__dirname, '../pages', folder, 'utils', name), 'utf8')
    assert.equal(read('portfolios'), read('team-portfolios'))
  }
})

for (const teamMode of [false, true]) {
  test(`${teamMode ? '团队' : '个人'}历史附件不可用时保留回执并阻止采用，移除后可继续`, () => {
    const page = pageFor(teamMode, async () => {}, { aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2 })
    page.applyTask({ status: 'SUCCEEDED', referenceAttachments: [reference({ available: false, attachmentToken: undefined, unavailableReason: '文件已过期' })],
      result: { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: { components: [] } } })
    assert.equal(page.data.aiReferenceAttachments[0].attachmentToken, 'receipt')
    assert.equal(page.data.aiReferenceAttachments[0].status, 'READY')
    assert.equal(page.data.canAdopt, false)
    assert.equal(page.data.taskError, '文件已过期')
    page.handleRemoveReference({ currentTarget: { dataset: { key: ID1 } } })
    assert.equal(page.referenceGenerationError(), '')
    assert.equal(page.data.aiReferenceNextNo, 2)
  })
  test(`${teamMode ? '团队' : '个人'}附件开关关闭后仍以原请求找回未知提交`, async () => {
    const oldWx = global.wx; global.wx = { showToast() {} }
    try {
      const calls = []
      const page = pageFor(teamMode, async options => { calls.push(options.data); if (calls.length === 1) throw new Error('timeout'); return { taskId: '1', status: 'PENDING' } },
        { aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2 })
      await page.handleGenerate()
      page.data.capabilities = { enabled: true, referenceAttachments: { enabled: false } }
      await page.handleGenerate()
      assert.equal(calls.length, 2)
      assert.deepEqual(calls[1], calls[0])
    } finally { global.wx = oldWx }
  })
  test(`${teamMode ? '团队' : '个人'}离页终止未完成附件并向编辑器回传可移除状态`, () => {
    const events = []; let cancelled = false
    const page = pageFor(teamMode, async () => {}, { aiReferenceAttachments: personal.normalizeReferenceItems([
      reference(), reference({ attachmentId: '', attachmentToken: '', localKey: 'pending', referenceNo: 'A2', status: 'COMPRESSING' })]), aiReferenceNextNo: 3 })
    page.referenceUpload = { dispose() { cancelled = true } }
    page.channel = { emit: (name, data) => events.push({ name, data }) }
    page.onUnload()
    assert.equal(cancelled, true)
    assert.equal(events.at(-1).data.aiReferenceAttachments[0].status, 'READY')
    assert.equal(events.at(-1).data.aiReferenceAttachments[1].status, 'FAILED')
    assert.match(events.at(-1).data.aiReferenceAttachments[1].errorMessage, /重新选择/)
    assert.equal(events.at(-1).data.aiReferenceNextNo, 3)
  })
}

for (const [name, module] of [['个人', personal], ['团队', team]]) {
test(`${name}图片缩放后按成品类型尺寸和stat确认，GIF不申请上传票据`, async () => {
  const compressed = []
  const f = uploaderFixture(module, {
    getImageInfo: options => options.success(options.src === '/one.jpg' ? { width: 8192, height: 4096, type: 'jpeg' } : { width: 4096, height: 2048, type: 'png' }),
    compressImage: options => { compressed.push(options); options.success({ tempFilePath: '/scaled.png' }) }
  })
  f.sizes['/one.jpg'] = 8000000; f.sizes['/scaled.png'] = 3999999
  f.uploader.add('image', { tempFilePath: '/one.jpg', fileType: 'image' })
  await until(() => f.changes.get('image')?.status === 'READY')
  assert.equal(compressed[0].compressedWidth, 4096)
  assert.equal(compressed[0].compressedHeight, 2048)
  assert.equal(f.calls[0].data.sizeBytes, 3999999)
  assert.equal(f.calls[0].data.mimeType, 'image/png')
  assert.equal(f.uploads[0].filePath, '/scaled.png')
  assert.deepEqual(f.calls[1].data.mediaInfo, { mimeType: 'image/png', codec: '', sizeBytes: 3999999,
    width: 4096, height: 2048, durationMs: null })
  const gif = uploaderFixture(module, { getImageInfo: options => options.success({ width: 200, height: 200, type: 'gif' }) })
  gif.uploader.add('image', { tempFilePath: '/one.jpg', fileType: 'image' })
  await until(() => gif.changes.get('image')?.status === 'FAILED')
  assert.equal(gif.calls.length, 0)
  f.uploader.dispose(); gif.uploader.dispose()
})
}

test('同时选中两个视频不消耗编号，移除处理中项目也不复用编号', async () => {
  const oldWx = global.wx; const messages = []; let files = [1, 2].map(index => ({ tempFilePath: `/${index}.mp4`, fileType: 'video' }))
  global.wx = { showToast: options => messages.push(options.title), chooseMedia: options => options.success({ tempFiles: files }) }
  try {
    const page = pageFor(false, async () => {})
    page.referenceUploader = () => ({ add() {} })
    await page.handleAddReference()
    assert.equal(page.data.aiReferenceAttachments.length, 0); assert.equal(page.data.aiReferenceNextNo, 1)
    assert.match(messages[0], /视频最多1个/)
    files = [files[0]]; await page.handleAddReference()
    assert.equal(page.data.aiReferenceAttachments[0].referenceNo, 'A1')
    page.handleRemoveReference({ currentTarget: { dataset: { key: 'local-A1' } } })
    await page.handleAddReference()
    assert.equal(page.data.aiReferenceAttachments[0].referenceNo, 'A2')
  } finally { global.wx = oldWx }
})

test('详情恢复维护回执和编号高水位，附件不进入公开配置', async () => {
  const oldWx = global.wx; global.wx = { enableAlertBeforeUnload() {}, disableAlertBeforeUnload() {} }
  try {
    const config = { components: [] }
    const page = { ...advancedEditorPageMethods, data: { config }, setData(patch) { Object.assign(this.data, patch) } }
    installAdvancedEditor(page, { templateType: 'ADVANCED', ownerId: '1' })
    await loadAdvancedMetadata(page, { ownerId: '1', aiReferenceAttachments: [reference({ attachmentToken: 'fresh' })], aiReferenceNextNo: 7 })
    assert.equal(page.data.aiReferenceAttachments[0].attachmentToken, 'fresh')
    assert.equal(page.data.aiReferenceNextNo, 7)
    assert.deepEqual(page.data.config, config)
    assert.equal(page.data.advancedDirty, false)
    assert.equal(personal.normalizeReferenceItems([reference({ available: false })])[0].attachmentToken, 'receipt')
  } finally { global.wx = oldWx }
})


test('压缩接口返回原路径时不得删除源文件', async () => {
  const f = uploaderFixture(personal, { compressVideo: options => { f.sizes['/one.mp4'] = 90000000; options.success({ tempFilePath: '/one.mp4' }) } })
  f.uploader.add('video', { tempFilePath: '/one.mp4', fileType: 'video' })
  await until(() => f.changes.get('video')?.status === 'READY')
  f.uploader.dispose()
  assert.equal(f.deleted.includes('/one.mp4'), false)
})

test('个人和团队编辑器附件入口使用有效WXML绑定，仅高级模式显示', () => {
  for (const [folder, name] of [['portfolios', 'portfolio-standard-edit'], ['team-portfolios', 'team-portfolio-standard-edit']]) {
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', folder, 'standard-edit', `${name}.wxml`), 'utf8')
    assert.match(wxml, /wx:if="\{\{advancedEditing && aiReferenceAttachments\.length\}\}"/)
    assert.match(wxml, /参考附件 \{\{aiReferenceAttachments\.length\}\} 个/)
  }
})

test('描述页附件轻入口可重新检查能力，关闭或缺失时仍禁止实际选择上传', async () => {
  const previousWx = global.wx
  let choices = 0
  const messages = []
  global.wx = { chooseMedia() { choices++ }, showToast(options) { messages.push(options.title) } }
  try {
    for (const [folder, teamMode] of [['portfolios', false], ['team-portfolios', true]]) {
      const wxml = fs.readFileSync(path.join(__dirname, '../pages', folder, 'advanced-ai/advanced-ai.wxml'), 'utf8')
      const section = wxml.match(/<view\b[^>]*class="reference-section"[^>]*>/)
      assert.ok(section)
      assert.doesNotMatch(section[0], /wx:if/)
      assert.ok(section.index > wxml.indexOf('<textarea class="prompt-input"'), '附件是描述的补充，应置于描述输入区之后')
      const button = wxml.match(/<button\b[^>]*bindtap="handleOpenReferencePicker"[^>]*>/)?.[0]
      assert.ok(button, '附件轻入口先打开选择说明，再选择本地文件')
      assert.doesNotMatch(button, /wx:if/)
      assert.doesNotMatch(button, /class="[^"]*(?:full-width|secondary-button)/, '无附件时仅保留轻入口，不使用整行大按钮')
      assert.doesNotMatch(button, /disabled="[^"]*(?:!capabilities|!referenceUi\.canAdd)/, '能力未开放时仍允许点击重新检查并获得反馈')
      assert.match(button, /disabled="[^"]*referenceCapabilityChecking/, '能力查询中避免重复点击')
      assert.match(wxml, /参考附件暂未开放，可点击 ＋ 重新检查/)
      assert.match(wxml, /capabilityError/)
      for (const capabilities of [{ enabled: true }, { enabled: true, referenceAttachments: { enabled: false } }]) {
        const page = pageFor(teamMode, async () => assert.fail('能力关闭时不得发送附件请求'), { capabilities })
        await page.handleAddReference()
      }
    }
    assert.equal(choices, 0)
    assert.deepEqual(messages, Array(4).fill('参考附件暂未开放'))
  } finally { global.wx = previousWx }
})

for (const teamMode of [false, true]) {
  test(`${teamMode ? '团队' : '个人'}任务重新查询确认附件恢复可用后恢复采用，业务基准保持不变`, () => {
    const page = pageFor(teamMode, async () => {}, { aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2 })
    const basis = captureBasis(page.currentState())
    const result = { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: { components: [] } }
    page.applyTask({ status: 'SUCCEEDED', result, referenceAttachments: [reference({ available: false, attachmentToken: undefined })] })
    assert.equal(page.data.canAdopt, false)
    page.applyTask({ status: 'SUCCEEDED', result, referenceAttachments: [reference({ available: true, attachmentToken: 'renewed' })] })
    const item = page.data.aiReferenceAttachments[0]
    assert.equal(item.available, true)
    assert.equal(item.status, 'READY')
    assert.equal(item.attachmentToken, 'renewed')
    assert.equal(item.errorMessage, '')
    assert.equal(page.data.canAdopt, true)
    assert.equal(page.referenceReadyError(), '')
    assert.equal(captureBasis(page.currentState()), basis)
  })
}

for (const teamMode of [false, true]) {
  test(`${teamMode ? '团队' : '个人'}成功任务查询抖动保留结果和回执，重试查询恢复采用及保存截止提示`, async () => {
    let attempts = 0
    const result = { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: { components: [] } }
    const page = pageFor(teamMode, async () => {
      if (++attempts === 1) throw new Error('参考附件暂时无法读取，请稍后重试')
      return { taskId: '7', status: 'SUCCEEDED', result, referenceSaveUntil: '2099-10-17T15:00:00',
        referenceAttachments: [reference({ available: true, attachmentToken: 'renewed' })] }
    }, { aiReferenceAttachments: personal.normalizeReferenceItems([reference()]), aiReferenceNextNo: 2 })
    page.applyTask({ taskId: '7', status: 'SUCCEEDED', result, referenceAttachments: [reference({ available: false, attachmentToken: undefined })] })
    await page.queryTask()
    assert.deepEqual(page.data.result, result)
    assert.equal(page.data.aiReferenceAttachments[0].attachmentToken, 'receipt')
    assert.equal(page.data.aiReferenceAttachments[0].status, 'READY')
    await page.queryTask()
    assert.equal(page.data.canAdopt, true)
    const item = page.data.aiReferenceAttachments[0]
    assert.equal(item.referenceSaveUntil, '2099-10-17T15:00:00')
    assert.match(item.saveDeadlineText, /2099-10-17 15:00/)
    assert.match(item.saveDeadlineText, /保存草稿/)
    assert.equal(page.currentState().aiReferenceAttachments[0].referenceSaveUntil, item.referenceSaveUntil)
  })
}

test('成功但附件暂不可用的候选有重试查询入口，两页均展示附件保存截止时间', () => {
  for (const folder of ['portfolios', 'team-portfolios']) {
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', folder, 'advanced-ai/advanced-ai.wxml'), 'utf8')
    assert.match(wxml, /wx:elif="\{\{!canAdopt[^"\n]*\}\}"[^>]*bindtap="queryTask"/)
    assert.match(wxml, /item.saveDeadlineText/)
  }
})

test('已保存附件不受旧任务保存窗口影响，未保存附件到期仅延后附件写入', () => {
  const expired = reference({ referenceSaveUntil: '2000-01-01T00:00:00' })
  assert.deepEqual(personal.referenceSaveFields(state({ aiReferenceAttachments: [expired] })), {})
  assert.equal(personal.referenceSaveFields(state({ aiReferenceAttachments: [{ ...expired, saved: true }] })).aiReferenceAttachments.length, 1)
  const normalized = personal.normalizeReferenceItems([{ ...expired, saved: true }])[0]
  assert.equal(normalized.saveDeadlineText, '')
})
