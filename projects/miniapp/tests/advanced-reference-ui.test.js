const test = require('node:test')
const assert = require('node:assert/strict')
const { createAdvancedAiPage } = require('../pages/portfolios/utils/advanced-ai-page')
const { createAdvancedAiPage: createTeamPage } = require('../pages/team-portfolios/utils/advanced-ai-page')

const CAPS = { enabled: true, supportedProtocols: [{ aiContractVersion: 'ai-portfolio-v2', sourceModes: ['SELECTED_SOURCES'] }], referenceAttachments: { enabled: true } }
const reference = (patch = {}) => ({ localKey: 'one', attachmentId: 'a'.repeat(32), attachmentToken: 'receipt',
  referenceNo: 'A1', status: 'READY', mediaType: 'IMAGE', sizeBytes: 3200000, note: '只参考留白', ...patch })
const eventFor = key => ({ currentTarget: { dataset: { key } } })
function pageFor(team, patch = {}, requestFn = async () => ({})) {
  const definition = (team ? createTeamPage : createAdvancedAiPage)(team, { requestFn })
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(value) { Object.assign(this.data, value) } }
  Object.assign(page.data, { capabilities: CAPS, ownerId: '1', mode: 'MODIFY_PAGE', prompt: '调整留白',
    config: { components: [] }, ...patch })
  page.schedulePoll = () => {}
  return page
}
async function until(check) {
  for (let attempt = 0; attempt < 50; attempt++) {
    if (check()) return
    await new Promise(resolve => setImmediate(resolve))
  }
  assert.fail('附件处理未进入预期状态')
}

for (const team of [false, true]) {
  test(`${team ? '团队' : '个人'}能力缺失或关闭时点击重查，仍未开放明确提示且不打开选择面板`, async () => {
    const previousWx = global.wx
    const messages = []
    global.wx = { showToast(options) { messages.push(options.title) } }
    try {
      for (const capabilities of [{ enabled: true }, { enabled: true, referenceAttachments: { enabled: false } }]) {
        const calls = []
        const page = pageFor(team, { capabilities }, async options => { calls.push(options.url); return capabilities })
        await page.handleOpenReferencePicker()
        assert.deepEqual(calls, ['/api/mine/portfolio-ai/capabilities'])
        assert.equal(page.data.referenceSheet, '')
        assert.equal(page.data.referenceUi.canAdd, false)
        assert.equal(page.data.referenceCapabilityChecking, false)
        assert.equal(page.data.capabilityError, '')
      }
      assert.deepEqual(messages, Array(2).fill('当前服务暂未开放参考附件，请稍后重试'))
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}重查能力防止重复请求，网络失败保留原能力和全部编辑状态`, async () => {
    const previousWx = global.wx
    const messages = []; let rejectRequest; let requests = 0
    global.wx = { showToast(options) { messages.push(options.title) } }
    try {
      const page = pageFor(team, { capabilities: { enabled: true, referenceAttachments: { enabled: false } },
        activeTaskId: 'previous-task', aiReferenceAttachments: [reference()], aiReferenceNextNo: 8,
        result: { kind: 'CANDIDATE', summary: '保留候选' }, canAdopt: true }, () => {
        requests++
        return new Promise((resolve, reject) => { rejectRequest = reject })
      })
      const before = JSON.stringify(page.data)
      const pending = page.handleOpenReferencePicker()
      assert.equal(page.data.referenceCapabilityChecking, true)
      await page.handleOpenReferencePicker()
      assert.equal(requests, 1)
      rejectRequest(new Error('temporary timeout'))
      await pending
      assert.equal(page.data.referenceCapabilityChecking, false)
      assert.equal(page.data.capabilityError, '暂时无法检查附件服务，请重试')
      assert.equal(JSON.stringify({ ...page.data, capabilityError: '' }), before)
      assert.deepEqual(messages, ['暂时无法检查附件服务，请重试'])
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}重查返回401沿用登录处理，已启用与满额点击不额外查能力`, async () => {
    const previousWx = global.wx
    const messages = []; const removed = []; const redirects = []
    global.wx = { showToast(options) { messages.push(options.title) }, removeStorageSync(key) { removed.push(key) },
      redirectTo(options) { redirects.push(options.url) } }
    try {
      const page = pageFor(team, { capabilities: { enabled: true } }, async () => {
        throw Object.assign(new Error('登录已过期'), { authRequired: true })
      })
      await page.handleOpenReferencePicker()
      assert.deepEqual(removed, ['wefolio_token'])
      assert.deepEqual(redirects, ['/pages/login/login'])
      assert.equal(page.data.referenceSheet, '')
      assert.equal(page.data.referenceCapabilityChecking, false)
      assert.deepEqual(messages, ['登录已过期'])
      const enabled = pageFor(team, {}, () => assert.fail('已启用时不能多发能力请求'))
      await enabled.handleOpenReferencePicker()
      assert.equal(enabled.data.referenceSheet, 'picker')
      enabled.handleCloseReferenceSheet()
      enabled.setData({ aiReferenceAttachments: Array.from({ length: 6 }, (_, index) => reference({ localKey: `item-${index}`, referenceNo: `A${index + 1}` })) })
      await enabled.handleOpenReferencePicker()
      assert.equal(enabled.data.referenceSheet, '')
      assert.equal(messages.at(-1), '最多添加6个参考附件')
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}首次能力读取失败后点击附件可独立恢复，不清空描述、来源和候选`, async () => {
    const previousWx = global.wx
    const calls = []; let capabilityReads = 0
    const updatedCapabilities = { ...CAPS, activeTask: { taskId: 'task-9' }, limits: { maxSourceCount: 10 } }
    global.wx = { getStorageSync: () => 'test-session', showToast() {} }
    try {
      const page = pageFor(team, {}, async options => {
        calls.push(options.url)
        if (options.url.endsWith('/capabilities')) {
          capabilityReads++
          if (capabilityReads === 1) throw new Error('temporary timeout')
          return updatedCapabilities
        }
        return []
      })
      await page.bootstrap()
      assert.equal(page.data.capabilityError, '暂时无法检查附件服务，请重试')
      page.setData({ prompt: '保留我的描述', promptCount: 6, selectedSources: [{ sourceNo: 3, sourceId: '11' }],
        sourceOptions: [{ sourceId: '11', selected: true }], aiReferenceAttachments: [reference()], aiReferenceNextNo: 4,
        result: { kind: 'CANDIDATE', summary: '保留候选' }, canAdopt: true, localEditRevision: 7 })
      const before = JSON.stringify([page.currentState(), page.data.prompt, page.data.selectedSources, page.data.sourceOptions, page.data.result, page.data.canAdopt])
      const requestCount = calls.length
      await page.handleOpenReferencePicker()
      assert.equal(capabilityReads, 2)
      assert.deepEqual(calls.slice(requestCount), ['/api/mine/portfolio-ai/capabilities'])
      assert.equal(page.data.referenceSheet, 'picker')
      assert.equal(page.data.capabilityError, '')
      assert.equal(page.data.referenceCapabilityChecking, false)
      assert.deepEqual(page.data.capabilities, updatedCapabilities)
      assert.equal(page.data.activeTaskId, 'task-9')
      assert.equal(page.data.referenceUi.canAdd, true)
      assert.equal(JSON.stringify([page.currentState(), page.data.prompt, page.data.selectedSources, page.data.sourceOptions, page.data.result, page.data.canAdopt]), before)
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}独立视频选择只开放剩余视频额度，已有视频时不再次打开相册`, async () => {
    const previousWx = global.wx
    const choices = []
    global.wx = { showToast() {}, chooseMedia(options) {
      choices.push(options)
      options.success({ tempFiles: [{ fileType: 'video', tempFilePath: '/one.mp4', size: 82000000, duration: 60 }] })
    } }
    try {
      const page = pageFor(team)
      page.referenceUpload = { add() {} }
      await page.handleChooseReference({ currentTarget: { dataset: { mediaType: 'video' } } })
      assert.equal(choices[0].count, 1)
      assert.deepEqual(choices[0].mediaType, ['video'])
      assert.equal(page.data.aiReferenceAttachments[0].mediaType, 'VIDEO')
      await page.handleChooseReference({ currentTarget: { dataset: { mediaType: 'video' } } })
      assert.equal(choices.length, 1)
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}上传中展示真实成品大小并阻止生成，确认就绪后解除阻断`, async () => {
    const previousWx = global.wx
    let uploadOptions; let progress; let confirm
    global.wx = { showToast() {}, chooseMedia(options) { options.success({ tempFiles: [{ fileType: 'image', tempFilePath: '/source.jpg', size: 8000000 }] }) },
      getFileSystemManager: () => ({ statSync(file) { return { size: file === '/source.jpg' ? 8000000 : 3200000 } }, unlinkSync() {} }),
      getImageInfo(options) { options.success({ width: 800, height: 600, type: 'jpeg' }) },
      compressImage(options) { options.success({ tempFilePath: '/compressed.jpg' }) },
      uploadFile(options) { uploadOptions = options; return { abort() { options.fail() }, onProgressUpdate(callback) { progress = callback } } } }
    const page = pageFor(team, {}, options => options.url.endsWith('upload-tickets')
      ? Promise.resolve({ uploadToken: 'upload', uploadUrl: 'https://cos.invalid', formData: {} })
      : new Promise(resolve => { confirm = resolve }))
    try {
      await page.handleChooseReference({ currentTarget: { dataset: { mediaType: 'image' } } })
      await until(() => Boolean(uploadOptions))
      assert.equal(page.data.referenceUi.totalMB, 3.2)
      assert.equal(page.data.referenceUi.pendingCount, 1)
      assert.equal(page.data.referenceUi.blocked, true)
      progress({ progress: 150 })
      assert.equal(page.data.aiReferenceAttachments[0].progress, 100)
      uploadOptions.success({ statusCode: 204 })
      await until(() => Boolean(confirm))
      confirm(reference({ sizeBytes: 3200000 }))
      await until(() => page.data.aiReferenceAttachments[0].status === 'READY')
      assert.equal(page.data.referenceUi.blocked, false)
      assert.equal(page.data.referenceUi.pendingCount, 0)
      assert.equal(page.data.referenceUi.totalMB, 3.2)
    } finally { if (page.referenceUpload) page.referenceUpload.dispose(); global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}恢复、状态变化与回执刷新同步生成阻断，批量移除只取消未就绪项`, async () => {
    const previousWx = global.wx
    global.wx = { showToast() {} }
    try {
      const page = pageFor(team)
      let initialize
      page.getOpenerEventChannel = () => ({ on(name, handler) { initialize = handler } })
      page.bootstrap = () => {}
      page.onLoad({ mode: 'MODIFY_PAGE' })
      initialize({ aiReferenceAttachments: [reference(), reference({ localKey: 'failed', attachmentId: '', referenceNo: 'A4', status: 'FAILED' })], aiReferenceNextNo: 9 })
      assert.equal(page.data.referenceUi.blocked, true)
      assert.equal(page.data.referenceUi.pendingCount, 1)
      const cancelled = []
      page.referenceUpload = { cancel(key) { cancelled.push(key) } }
      page.handleRemovePendingReferences()
      assert.deepEqual(cancelled, ['failed'])
      assert.deepEqual(page.data.aiReferenceAttachments.map(item => item.referenceNo), ['A1'])
      assert.equal(page.data.aiReferenceNextNo, 9)
      assert.equal(page.data.referenceUi.blocked, false)
      const revision = page.data.localEditRevision
      page.refreshReferenceTokens({ referenceAttachments: [{ attachmentId: 'a'.repeat(32), available: false, unavailableReason: '文件已失效' }] })
      assert.equal(page.data.referenceUi.blocked, true)
      assert.equal(page.data.referenceUi.pendingCount, 1)
      assert.equal(page.data.localEditRevision, revision)
      page.refreshReferenceTokens({ referenceAttachments: [{ attachmentId: 'a'.repeat(32), available: true, attachmentToken: 'fresh' }] })
      assert.equal(page.data.referenceUi.blocked, false)
      assert.equal(page.data.referenceUi.pendingCount, 0)
      page.setData({ capabilities: { enabled: true, referenceAttachments: { enabled: false } } })
      page.refreshReferenceUi()
      assert.equal(page.data.referenceUi.blocked, true)
      page.handleRemoveReference(eventFor('one'))
      assert.equal(page.data.referenceUi.blocked, false)
      assert.equal(page.data.referenceUi.canAdd, false)
      assert.equal(page.referenceGenerationError(), '')
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}满额失败视频可保号替换，取消不删除，成功保留位置、重点和编号高水位`, async () => {
    const previousWx = global.wx
    const choices = []; let cancel = true
    global.wx = { showToast() {}, chooseMedia(options) {
      choices.push(options)
      if (cancel) options.fail({ errMsg: 'chooseMedia:fail cancel' })
      else options.success({ tempFiles: [{ fileType: 'video', tempFilePath: '/replacement.mp4', size: 82000000, duration: 60 }] })
    } }
    try {
      const items = [reference(), reference({ localKey: 'failed-video', referenceNo: 'A4', attachmentId: '', mediaType: 'VIDEO', status: 'FAILED' }),
        ...[5, 6, 7, 8].map(no => reference({ localKey: `image-${no}`, referenceNo: `A${no}` }))]
      const page = pageFor(team, { aiReferenceAttachments: items, aiReferenceNextNo: 10 })
      const added = []; const cancelled = []
      page.referenceUpload = { cancel(key) { cancelled.push(key) }, add(key, file) { added.push([key, file]) } }
      const before = JSON.stringify(page.currentState())
      page.handleReplaceReference(eventFor('failed-video'))
      assert.equal(page.data.referenceSheet, 'picker')
      assert.equal(page.data.referenceUi.canChooseVideo, true)
      await page.handleChooseReference({ currentTarget: { dataset: { mediaType: 'video' } } })
      assert.equal(JSON.stringify(page.currentState()), before)
      assert.deepEqual(cancelled, [])
      cancel = false
      page.handleReplaceReference(eventFor('failed-video'))
      await page.handleChooseReference({ currentTarget: { dataset: { mediaType: 'video' } } })
      assert.equal(choices[1].count, 1)
      assert.deepEqual(choices[1].mediaType, ['video'])
      assert.equal(page.data.aiReferenceAttachments.length, 6)
      assert.equal(page.data.aiReferenceAttachments[1].referenceNo, 'A4')
      assert.equal(page.data.aiReferenceAttachments[1].note, '只参考留白')
      assert.equal(page.data.aiReferenceAttachments[1].localPath, '/replacement.mp4')
      assert.equal(page.data.aiReferenceNextNo, 10)
      assert.deepEqual(cancelled, ['failed-video'])
      assert.equal(added[0][0], 'failed-video')
      assert.equal(page.data.referenceUi.pendingCount, 1)
      assert.equal(page.data.referenceUi.canChooseVideo, false)
    } finally { global.wx = previousWx }
  })

  test(`${team ? '团队' : '个人'}引用附件和文字示例走描述编辑流程，清除候选且不生成悬空编号`, () => {
    const page = pageFor(team, { aiReferenceAttachments: [reference()], result: { kind: 'CANDIDATE' }, canAdopt: true,
      tokenUsageText: '本次消耗 100 tokens', localEditRevision: 4 })
    page.handleQuoteReference(eventFor('one'))
    assert.equal(page.data.prompt, '调整留白\n参考A1的')
    assert.equal(page.data.promptCount, 10)
    assert.equal(page.data.result, null)
    assert.equal(page.data.canAdopt, false)
    assert.equal(page.data.tokenUsageText, '')
    assert.equal(page.data.localEditRevision, 4)
    page.handleOpenReferenceExample()
    const exampleText = page.data.referenceExampleText
    page.handleUseReferenceExample()
    assert.equal(page.data.referenceSheet, '')
    assert.equal(page.data.prompt, exampleText)
    assert.match(page.data.prompt, /参考A1/)
    assert.doesNotMatch(page.data.prompt, /\d+号/)
    page.setData({ aiReferenceAttachments: [reference({ status: 'FAILED' })], selectedSources: [] })
    page.handleUseReferenceExample()
    assert.doesNotMatch(page.data.prompt, /A\d+|\d+号/)
    const prompt = '字'.repeat(page.data.promptMaxLength)
    page.setData({ prompt })
    page.handleQuoteReference(eventFor('one'))
    assert.equal(page.data.prompt, prompt)
  })

  test(`${team ? '团队' : '个人'}选择说明和参考重点只更新界面状态，取消相册保持附件与编号`, async () => {
    const previousWx = global.wx
    const choices = []
    global.wx = { showToast() {}, chooseMedia(options) { choices.push(options); options.fail({ errMsg: 'chooseMedia:fail cancel' }) } }
    try {
      const page = pageFor(team, { aiReferenceAttachments: [reference()], aiReferenceNextNo: 3, localEditRevision: 5 })
      const before = JSON.stringify(page.currentState())
      page.handleOpenReferencePicker()
      assert.equal(page.data.referenceSheet, 'picker')
      assert.equal(choices.length, 0)
      assert.equal(page.data.referenceUi.maxCount, 6)
      assert.equal(page.data.referenceUi.totalMaxMB, 120)
      assert.equal(page.data.referenceUi.totalMB, 3.2)
      page.handleToggleReferenceNote(eventFor('one'))
      assert.equal(page.data.referenceNoteOpen.one, true)
      await page.handleChooseReference({ currentTarget: { dataset: { mediaType: 'image' } } })
      assert.deepEqual(choices[0].mediaType, ['image'])
      assert.equal(page.data.referenceSheet, '')
      assert.equal(JSON.stringify(page.currentState()), before)
      page.handleOpenReferenceExample()
      assert.equal(page.data.referenceSheet, 'example')
      page.handleCloseReferenceSheet()
      assert.equal(page.data.referenceSheet, '')
      assert.equal(page.data.localEditRevision, 5)
    } finally { global.wx = previousWx }
  })
}
