const test = require('node:test')
const assert = require('node:assert/strict')
const { createAdvancedAiPage, normalizeSource } = require('../pages/portfolios/utils/advanced-ai-page')
const { createAdvancedAiPage: createTeamAdvancedAiPage } = require('../pages/team-portfolios/utils/advanced-ai-page')
const { captureBasis, normalizeContext, TASK_FAILED_MESSAGE } = require('../pages/portfolios/utils/advanced-portfolios')
const { installAdvancedEditor, markAdvancedSaved, advancedEditorPageMethods } = require('../pages/portfolios/utils/advanced-editor-page')

function pageFor(requestFn, patch = {}, team = false) {
  const definition = (team ? createTeamAdvancedAiPage : createAdvancedAiPage)(team, { requestFn })
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(value) { Object.assign(this.data, value) } }
  Object.assign(page.data, { ownerId: '101', prompt: '精简已有文字', capabilities: { enabled: true, limits: { maxSourceCount: 10 } }, mode: 'MODIFY_PAGE',
    config: { schemaVersion: 'standard-personal-v1', components: [] }, aiContext: normalizeContext(), ...patch })
  page.schedulePoll = () => {}
  return page
}

for (const team of [false, true]) {
  test(`${team ? '团队' : '个人'}生成结果保留真实累计 token 用量，无变化与不可用候选同样显示`, () => {
    const page = pageFor(async () => {}, {}, team)
    for (const result of [
      { kind: 'CANDIDATE', available: true, hasChanges: true },
      { kind: 'CANDIDATE', available: true, hasChanges: false },
      { kind: 'CANDIDATE', available: false, error: { message: '候选无法使用' } }
    ]) {
      page.applyTask({ status: 'SUCCEEDED', result, usage: { complete: true, totalTokens: 12480 } })
      assert.equal(page.data.tokenUsageText, '本次消耗 12,480 tokens')
    }
    page.applyTask({ status: 'FAILED', usage: { complete: true, totalTokens: 0 } })
    assert.equal(page.data.tokenUsageText, '本次消耗 0 tokens')
  })

  test(`${team ? '团队' : '个人'}部分用量与未返回用量明确区分，不把缺失值显示为零`, () => {
    const page = pageFor(async () => {}, {}, team)
    page.applyTask({ status: 'FAILED', usage: { complete: false, totalTokens: 12480 } })
    assert.equal(page.data.tokenUsageText, '已记录 12,480 tokens · 部分用量未返回')
    page.applyTask({ status: 'FAILED', usage: { complete: false, totalTokens: null } })
    assert.equal(page.data.tokenUsageText, 'Token 用量未返回')
  })

  test(`${team ? '团队' : '个人'}旧接口和非法用量隐藏 token 提示，不做类型转换或不安全整数计算`, () => {
    const page = pageFor(async () => {}, {}, team)
    for (const usage of [undefined, null, {}, [], { complete: 'true', totalTokens: 12480 },
      { totalTokens: 12480 }, { complete: true, totalTokens: null }, { complete: false },
      { complete: true, totalTokens: '12480' }, { complete: true, totalTokens: -1 },
      { complete: false, totalTokens: 1.5 }, { complete: true, totalTokens: Number.MAX_SAFE_INTEGER + 1 }]) {
      page.applyTask({ status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true },
        usage: { complete: true, totalTokens: 12480 } })
      page.applyTask({ status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true }, usage })
      assert.equal(page.data.tokenUsageText, '', `非法用量不得沿用上一结果：${JSON.stringify(usage)}`)
    }
  })

  test(`${team ? '团队' : '个人'}执行中与取消状态不展示累计 token`, () => {
    const page = pageFor(async () => {}, {}, team)
    for (const status of ['PENDING', 'RUNNING', 'CANCELLED']) {
      page.setData({ tokenUsageText: '本次消耗 12,480 tokens' })
      page.applyTask({ status, usage: { complete: true, totalTokens: 12480 } })
      assert.equal(page.data.tokenUsageText, '')
    }
  })

  test(`${team ? '团队' : '个人'}放弃结果及修改描述不残留上一任务 token`, () => {
    const page = pageFor(async () => {}, {}, team)
    for (const action of [
      () => page.handleDiscard(),
      () => page.handlePromptInput({ detail: { value: '调整留白' } }),
      () => page.handleInsertNumber({ currentTarget: { dataset: { no: 1 } } }),
      () => page.handleUseReferenceExample()
    ]) {
      page.applyTask({ status: 'FAILED', usage: { complete: false, totalTokens: 12480 } })
      action()
      assert.equal(page.data.tokenUsageText, '')
    }
  })

  test(`${team ? '团队' : '个人'}新任务提交与取消立即清空旧用量，并忽略取消后的在途查询`, async () => {
    let resolveQuery
    const page = pageFor(options => options.method === 'POST'
      ? Promise.resolve({ taskId: '9', status: 'PENDING' })
      : new Promise(resolve => { resolveQuery = resolve }), {}, team)
    page.applyTask({ status: 'FAILED', usage: { complete: true, totalTokens: 12480 } })
    await page.handleGenerate()
    assert.equal(page.data.tokenUsageText, '')
    const pending = page.queryTask()
    page.setData({ tokenUsageText: '本次消耗 12,480 tokens' })
    await page.handleCancel()
    assert.equal(page.data.tokenUsageText, '')
    resolveQuery({ taskId: '9', status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true },
      usage: { complete: true, totalTokens: 500 } })
    await pending
    assert.equal(page.data.tokenUsageText, '')
    assert.equal(page.data.task.status, 'CANCELLED')
  })

  test(`${team ? '团队' : '个人'}重新生成后旧任务的延迟查询不能覆盖新任务或恢复旧用量`, async () => {
    let resolveOldQuery
    const page = pageFor(options => options.method === 'POST'
      ? Promise.resolve({ taskId: '9', status: 'PENDING' })
      : new Promise(resolve => { resolveOldQuery = resolve }), {}, team)
    page.applyTask({ taskId: '7', status: 'FAILED', usage: { complete: true, totalTokens: 12480 } })
    const oldQuery = page.queryTask()
    await page.handleGenerate()
    resolveOldQuery({ taskId: '7', status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true },
      usage: { complete: true, totalTokens: 12480 } })
    await oldQuery
    assert.equal(page.data.task.taskId, '9')
    assert.equal(page.data.waiting, true)
    assert.equal(page.data.result, null)
    assert.equal(page.data.tokenUsageText, '')
  })

  test(`${team ? '团队' : '个人'}手动重试完成后取消旧轮询计划，放弃结果不会被定时查询恢复`, async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    let queryCount = 0
    const page = pageFor(async () => {
      queryCount += 1
      if (queryCount === 1) throw new Error('query timeout')
      return { taskId: '7', status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true },
        usage: { complete: true, totalTokens: 12480 } }
    }, { waiting: true, step: 'prompt', task: { taskId: '7', status: 'RUNNING' } }, team)
    page.schedulePoll = (team ? createTeamAdvancedAiPage : createAdvancedAiPage)(team).schedulePoll
    await page.queryTask()
    assert.equal(page.data.waiting, true)
    page.onShow()
    assert.ok(page.pollTimer)
    await page.queryTask()
    page.handleDiscard()
    t.mock.timers.tick(5000)
    await Promise.resolve()
    assert.equal(queryCount, 2)
    assert.equal(page.pollTimer, null)
    assert.equal(page.data.result, null)
    assert.equal(page.data.tokenUsageText, '')
  })

  test(`${team ? '团队' : '个人'}放弃或编辑后在途终态查询不能恢复旧结果和 token`, async () => {
    const oldWx = global.wx
    global.wx = { showToast() {} }
    try {
      for (const action of [
        page => page.handleDiscard(),
        page => page.handlePromptInput({ detail: { value: '调整留白' } }),
        page => page.handleInsertNumber({ currentTarget: { dataset: { no: 1 } } }),
        page => page.handleUseReferenceExample(),
        page => page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } }),
        page => page.handleRemoveSelected({ currentTarget: { dataset: { no: 1 } } })
      ]) {
        let resolveOldQuery
        const page = pageFor(() => new Promise(resolve => { resolveOldQuery = resolve }), {
          sourceOptions: [normalizeSource({ id: '11', title: '作品一', mediaType: 'IMAGE' }, team)],
          aiContext: normalizeContext({ nextSourceNo: 2, sources: [{ sourceNo: 1, kind: 'WORK', sourceId: '11' }] })
        }, team)
        page.applyTask({ taskId: '7', status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true, summary: '原结果' },
          usage: { complete: true, totalTokens: 12480 } })
        const oldQuery = page.queryTask()
        action(page)
        const afterEdit = JSON.stringify([page.data.result, page.data.prompt, page.data.aiContext])
        resolveOldQuery({ taskId: '7', status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true, summary: '延迟旧结果' },
          usage: { complete: true, totalTokens: 12480 } })
        await oldQuery
        assert.equal(JSON.stringify([page.data.result, page.data.prompt, page.data.aiContext]), afterEdit)
        assert.equal(page.data.tokenUsageText, '')
      }
    } finally { global.wx = oldWx }
  })

  test(`${team ? '团队' : '个人'}整体 AI 修改使用完整编辑配置和描述提交`, async () => {
    const calls = []
    const page = pageFor(async options => { calls.push(options); return { taskId: '7', status: 'PENDING' } }, {}, team)
    let initialize
    page.getOpenerEventChannel = () => ({ on(name, handler) { initialize = handler }, emit() {} })
    page.bootstrap = () => {}
    page.onLoad({ mode: 'MODIFY_PAGE' })
    const state = page.currentState()
    initialize({ ...state, mode: 'MODIFY_PAGE', prompt: '整体更简洁', targetMenuKey: 'menu-1' })
    assert.equal(page.data.mode, 'MODIFY_PAGE')
    assert.equal(page.data.step, 'prompt')
    assert.equal(page.data.managingSources, false)
    await page.handleGenerate()
    assert.equal(calls[0].data.mode, 'MODIFY_PAGE')
    assert.equal(calls[0].data.targetMenuKey, 'menu-1')
    assert.deepEqual(calls[0].data.baseConfig, state.config)
    assert.equal(calls[0].data.prompt, '整体更简洁')
  })

  test(`${team ? '团队' : '个人'}无效 AI 操作阻止初始化`, () => {
    const page = pageFor(async () => {}, {}, team)
    let initialized = false
    page.getOpenerEventChannel = () => ({ on() { initialized = true } })
    page.onLoad({ mode: 'INVALID_MODE' })
    assert.equal(initialized, false)
    assert.equal(page.data.loading, false)
    assert.equal(page.data.errorMessage, 'AI 操作参数无效')
  })

  test(`${team ? '团队' : '个人'}管理来源保持独立选择流程并委派完成结果`, () => {
    const page = pageFor(async () => {}, {}, team)
    let initialize
    const emitted = []
    page.getOpenerEventChannel = () => ({ on(name, handler) { initialize = handler }, emit(name, value) { emitted.push([name, value]) } })
    page.bootstrap = () => {}
    page.onLoad({ mode: 'MANAGE_SOURCES' })
    initialize({ ...page.currentState(), mode: 'MANAGE_SOURCES', prompt: '整体需求' })
    assert.equal(page.data.managingSources, true)
    assert.equal(page.data.step, 'sources')
    assert.equal(page.data.mode, 'MODIFY_PAGE')
    const oldWx = global.wx
    let navigatedBack = false
    global.wx = { navigateBack() { navigatedBack = true } }
    try { page.handleNext() } finally { global.wx = oldWx }
    assert.equal(navigatedBack, true)
    assert.equal(emitted[0][0], 'contextChange')
    assert.equal(emitted[0][1].prompt, '整体需求')
  })

  if (!team) test('重开个人来源正确读取作品详情包装与封面', async () => {
    const page = pageFor(async () => ({ work: { id: '11', title: '婚礼纪实', mediaType: 'IMAGE', coverUrl: 'https://example.test/cover.jpg', auditStatus: 'PASSED', status: 'NORMAL' } }),
      { aiContext: { nextSourceNo: 4, sources: [{ sourceNo: 3, kind: 'WORK', sourceId: '11' }] } })
    await page.hydrateSelectedSources()
    page.refreshSelection()
    assert.equal(page.data.selectedSources[0].available, true)
    assert.equal(page.data.selectedSources[0].thumbnailUrl, 'https://example.test/cover.jpg')
    assert.equal(page.data.selectedSources[0].title, '婚礼纪实')
    assert.equal(page.data.selectedSources[0].sourceNo, 3)
  })
  test(`${team ? '团队' : '个人'}筛选后卡片保留稳定编号，取消后重新选入恢复原号`, () => {
    const page = pageFor(async () => {}, { sourceOptions: [normalizeSource({ id: '11', title: '作品一', mediaType: 'VIDEO' })],
      aiContext: { version: 1, nextSourceNo: 5, sources: [{ sourceNo: 3, kind: 'WORK', sourceId: '11' }] } }, team)
    page.sourceFacts = { 'WORK:11': page.data.sourceOptions[0] }
    page.refreshSelection()
    assert.equal(page.data.sourceOptions[0].sourceNo, 3)
    assert.equal(page.data.sourceOptions[0].mediaTypeLabel, '视频')
    const oldWx = global.wx
    global.wx = { showToast() {} }
    try {
      for (let attempt = 0; attempt < 3; attempt += 1) {
        page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
        page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
      }
      assert.equal(page.data.sourceOptions[0].sourceNo, 3)
      assert.equal(page.data.selectedSources[0].sourceNo, 3)
      assert.equal(page.data.aiContext.nextSourceNo, 5)
      page.handleInsertNumber({ currentTarget: { dataset: { no: page.data.selectedSources[0].sourceNo } } })
      assert.match(page.data.prompt, /3号/)
      assert.equal(page.data.promptCount, Array.from(page.data.prompt).length)
    } finally { global.wx = oldWx }
  })

  test(`${team ? '团队' : '个人'}底部移除后跨筛选重选恢复原号，新作品不占用旧号`, async () => {
    const first = normalizeSource({ id: '11', title: '作品一', mediaType: 'IMAGE' }, team)
    const second = normalizeSource({ id: '12', title: '作品二', mediaType: 'VIDEO' }, team)
    let records = [second]
    const page = pageFor(async () => ({ records }), { sourceOptions: [first],
      aiContext: { version: 1, nextSourceNo: 4, sources: [{ sourceNo: 3, kind: 'WORK', sourceId: '11' }] } }, team)
    page.sourceFacts = { 'WORK:11': first }
    page.refreshSelection()
    const oldWx = global.wx
    global.wx = { showToast() {} }
    try {
      page.handleRemoveSelected({ currentTarget: { dataset: { no: 3 } } })
      await page.loadSources(false)
      page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
      assert.equal(page.data.selectedSources[0].sourceNo, 4)
      records = [first, second]
      await page.loadSources(false)
      page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
      assert.deepEqual(page.data.selectedSources.map(source => [source.sourceId, source.sourceNo]), [['12', 4], ['11', 3]])
      assert.equal(page.data.aiContext.nextSourceNo, 5)
      assert.deepEqual(Object.keys(page.data.aiContext), ['version', 'nextSourceNo', 'sources'])
    } finally { global.wx = oldWx }
  })

  test(`${team ? '团队' : '个人'}新初始化会话不恢复此前已移除的来源旧号`, () => {
    const source = normalizeSource({ id: '11', title: '作品一', mediaType: 'IMAGE' }, team)
    const page = pageFor(async () => {}, {}, team)
    let initialize
    page.getOpenerEventChannel = () => ({ on(name, handler) { initialize = handler }, emit() {} })
    page.bootstrap = () => {}
    page.onLoad({ mode: 'MANAGE_SOURCES' })
    const oldWx = global.wx
    global.wx = { showToast() {} }
    try {
      initialize({ mode: 'MANAGE_SOURCES', sourceOptions: [source],
        aiContext: { nextSourceNo: 5, sources: [{ sourceNo: 3, kind: 'WORK', sourceId: '11' }] } })
      page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
      initialize({ mode: 'MANAGE_SOURCES', sourceOptions: [source], aiContext: { nextSourceNo: 5, sources: [] } })
      page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
      assert.equal(page.data.selectedSources[0].sourceNo, 5)
      assert.equal(page.data.aiContext.nextSourceNo, 6)
    } finally { global.wx = oldWx }
  })

  test(`${team ? '团队' : '个人'}空新建保留选择步骤，已有作品集仍可空来源修改`, () => {
    const page = pageFor(async () => {}, { mode: 'CREATE', step: 'sources', selectedSources: [] }, team)
    const oldWx = global.wx
    global.wx = { showToast() {} }
    try {
      page.handleNext()
      assert.equal(page.data.step, 'sources')
      page.setData({ mode: 'MODIFY_PAGE' })
      page.handleNext()
      assert.equal(page.data.step, 'prompt')
    } finally { global.wx = oldWx }
  })

  test(`${team ? '团队' : '个人'}任务缺少错误说明时状态和操作区共用失败兜底`, () => {
    const page = pageFor(async () => {}, {}, team)
    page.applyTask({ taskId: '7', status: 'FAILED' })
    assert.equal(page.data.statusText, TASK_FAILED_MESSAGE)
    assert.equal(page.data.taskError, TASK_FAILED_MESSAGE)
    page.applyTask({ taskId: '7', status: 'FAILED', error: { message: '后端明确的失败说明' } })
    assert.equal(page.data.taskError, '后端明确的失败说明')
  })

  test(`${team ? '团队' : '个人'}查询到不可用候选展示具体校验原因并保留当前编辑`, async () => {
    const reason = '文字区域背景仅支持图片作品，请调整来源后重试'
    const page = pageFor(async () => ({ taskId: '7', status: 'SUCCEEDED', result: {
      kind: 'CANDIDATE', available: false, candidate: null, hasChanges: null,
      unavailableReason: '生成结果暂不可用，现有内容已保留，请重新生成',
      error: { code: 'CONFIG_VALIDATION_FAILED', message: reason }
    } }), { mode: 'CREATE', task: { taskId: '7', status: 'RUNNING' }, waiting: true }, team)
    const before = JSON.stringify([page.data.config, page.data.aiContext, page.data.prompt, page.data.localEditRevision])
    await page.queryTask()
    assert.equal(page.data.taskError, reason)
    assert.equal(page.data.candidateTitle, '生成失败')
    assert.equal(page.data.waiting, false)
    assert.equal(page.data.canAdopt, false)
    assert.equal(JSON.stringify([page.data.config, page.data.aiContext, page.data.prompt, page.data.localEditRevision]), before)
  })

  test(`${team ? '团队' : '个人'}编译前失败的空候选也显示失败，可用原描述提交新任务`, async () => {
    const calls = []
    const page = pageFor(async options => {
      calls.push(options)
      return options.method === 'POST' ? { taskId: '8', status: 'PENDING' } : { taskId: '7', status: 'SUCCEEDED', result: {
        available: false, candidate: null, hasChanges: null,
        unavailableReason: '所选来源已移除或不可用'
      } }
    }, { step: 'prompt', task: { taskId: '7', status: 'RUNNING' }, waiting: true }, team)
    await page.queryTask()
    assert.equal(page.data.candidateTitle, '生成失败')
    assert.equal(page.data.taskError, '所选来源已移除或不可用')
    await page.handleGenerate()
    assert.equal(calls[1].method, 'POST')
    assert.equal(calls[1].data.prompt, '精简已有文字')
    assert.equal(calls[1].data.baseConfig.components.length, 0)
    assert.equal(page.data.task.taskId, '8')
    assert.equal(page.data.waiting, true)
    assert.equal(page.data.result, null)
    assert.equal(page.data.taskError, '')
  })

  test(`${team ? '团队' : '个人'}失败任务兼容顶层原因字段，具体错误优先于旧通用提示`, () => {
    const page = pageFor(async () => {}, {}, team)
    page.applyTask({ taskId: '7', status: 'FAILED', errorCode: 'REMOTE_TIMEOUT', errorMessage: '生成超时，请重试' })
    assert.equal(page.data.taskError, '生成超时，请重试')
    page.applyTask({ taskId: '7', status: 'FAILED', errorMessage: TASK_FAILED_MESSAGE,
      error: { code: 'OUTPUT_TRUNCATED', message: 'AI 返回的内容不完整，请重新生成' } })
    assert.equal(page.data.taskError, 'AI 返回的内容不完整，请重新生成')
  })

  test(`${team ? '团队' : '个人'}媒体失败分类沿用既有任务结构展示安全原因`, () => {
    const page = pageFor(async () => {}, {}, team)
    const reasons = [
      ['MEDIA_INVALID', '所选素材不符合 AI 处理要求，请调整素材后重新生成'],
      ['SOURCE_UNAVAILABLE', '所选素材已失效或无权访问，请重新选择素材后生成'],
      ['MEDIA_NOT_PREPARED', '所选视频尚未完成素材准备，请稍后重新生成'],
      ['MEDIA_BUDGET_EXHAUSTED', 'AI 媒体处理额度不足，请减少素材或稍后重试'],
      ['MEDIA_BUDGET_OVERRUN', '本次 AI 媒体处理超出额度，请减少素材后重新生成'],
      ['MEDIA_BUDGET_INVALID', 'AI 媒体处理费用记录异常，请联系管理员核对'],
      ['MEDIA_ACCESS_RETRY', '媒体对象服务暂时不可用，请稍后重新生成'],
      ['INVALID_MEDIA_SUMMARY', 'AI 返回的素材分析结果不符合要求，请重新生成']
    ]
    for (const [code, message] of reasons) {
      page.applyTask({ taskId: '7', status: 'FAILED', errorCode: code, errorMessage: TASK_FAILED_MESSAGE,
        error: { code, message } })
      assert.equal(page.data.taskError, message)
      assert.equal(page.data.canAdopt, false)
    }
  })

  test(`${team ? '团队' : '个人'}采用前候选失效同步失败原因并保留当前编辑`, async () => {
    const page = pageFor(async () => ({ taskId: '7', status: 'SUCCEEDED', baseDraftRevision: 0, localEditRevision: 0,
      result: { kind: 'CANDIDATE', available: false, candidate: null,
        unavailableReason: '生成结果暂不可用，现有内容已保留，请重新生成',
        error: { code: 'SOURCE_UNAVAILABLE', message: '所选来源已失效，请重新选择' } }
    }), {}, team)
    page.taskBasis = captureBasis(page.currentState())
    page.applyTask({ taskId: '7', status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true,
      hasChanges: true, candidate: { components: [{ componentKey: 'generated' }] } } })
    const before = JSON.stringify([page.data.config, page.data.aiContext, page.data.prompt, page.data.localEditRevision])
    await page.handleAdopt()
    assert.equal(page.data.candidateTitle, '生成失败')
    assert.equal(page.data.taskError, '所选来源已失效，请重新选择')
    assert.equal(page.data.canAdopt, false)
    assert.equal(page.data.adopting, false)
    assert.equal(page.data.result.available, false)
    assert.equal(JSON.stringify([page.data.config, page.data.aiContext, page.data.prompt, page.data.localEditRevision]), before)
  })
}

test('团队同 ID 的作品与成员作品集各自恢复自己的编号', () => {
  const options = ['WORK', 'MEMBER_PORTFOLIO'].map(kind => normalizeSource({ kind, sourceId: '7', title: kind }, true))
  const page = pageFor(async () => {}, { sourceOptions: options, aiContext: { nextSourceNo: 8, sources: [
    { sourceNo: 3, kind: 'WORK', sourceId: '7' }, { sourceNo: 7, kind: 'MEMBER_PORTFOLIO', sourceId: '7' }
  ] } }, true)
  for (const index of [0, 1, 1, 0]) page.handleSourceTap({ currentTarget: { dataset: { index } } })
  assert.deepEqual(page.data.selectedSources.map(source => [source.kind, source.sourceNo]), [['MEMBER_PORTFOLIO', 7], ['WORK', 3]])
  assert.equal(page.data.aiContext.nextSourceNo, 8)
})

test('提交响应丢失后复用同一请求；尚未拿到ID时不创建任何草稿', async () => {
  const calls = []
  const page = pageFor(async options => {
    calls.push(options)
    if (calls.length === 1) throw new Error('network timeout')
    return { taskId: '7', status: 'PENDING' }
  }, { mode: 'CREATE', config: null })
  await page.handleGenerate()
  assert.equal(page.data.submissionUncertain, true)
  await page.handleGenerate()
  assert.deepEqual(calls[1].data, calls[0].data)
  assert.equal(calls.every(call => call.url === '/api/mine/portfolio-ai/tasks'), true)
  assert.equal(page.data.waiting, true)
})

test('幂等重放返回已成功任务状态后查询候选，查询失败可重试同一任务', async () => {
  const calls = []
  const candidate = { components: [{ componentKey: 'ai', componentType: 'TEXT_SECTION' }] }
  const page = pageFor(async options => {
    calls.push(options)
    if (options.method === 'POST') return { taskId: '7', status: 'SUCCEEDED', pollAfterMs: 2000 }
    if (calls.length === 2) throw new Error('query timeout')
    return { taskId: '7', status: 'SUCCEEDED', baseDraftRevision: 0, localEditRevision: 0,
      result: { kind: 'CANDIDATE', available: true, hasChanges: true, candidate,
        summary: ['已调整页面内容'], renderData: { preview: true, components: [] } } }
  })
  await page.handleGenerate()
  assert.equal(calls.length, 2)
  assert.equal(calls[1].url, '/api/mine/portfolio-ai/tasks/7')
  assert.equal(page.data.waiting, true)
  assert.equal(page.data.canAdopt, false)
  assert.match(page.data.taskError, /query timeout/)
  await page.queryTask()
  assert.equal(calls.filter(call => call.method === 'POST').length, 1)
  assert.equal(page.data.waiting, false)
  assert.equal(page.data.canAdopt, true)
  assert.deepEqual(page.data.result.candidate, candidate)
  assert.equal(page.data.summaryText, '已调整页面内容')
})

test('澄清回答用显式问题与选项构造新任务，不自动选答案', async () => {
  const calls = []
  const oldWx = global.wx
  global.wx = { showToast() {} }
  const page = pageFor(async options => { calls.push(options); return { taskId: '2', status: 'PENDING' } })
  page.setData({ questions: [{ id: 'q1', prompt: '移动哪一处？', options: [{ id: 'o1', label: '第一处视频', answerText: '移动第一处视频' }] }] })
  try {
    await page.handleGenerate()
    assert.equal(calls.length, 0)
    page.handleQuestionOption({ currentTarget: { dataset: { id: 'q1', option: 'o1', value: '移动第一处视频' } } })
    await page.handleGenerate()
    assert.deepEqual(calls[0].data.clarificationAnswer, { questionId: 'q1', optionId: 'o1', answerText: '移动第一处视频' })
  } finally { global.wx = oldWx }
})

test('无改动结果不更改配置、编辑版本或采用状态', () => {
  const page = pageFor(async () => {})
  const before = JSON.stringify([page.data.config, page.data.localEditRevision])
  page.applyTask({ status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true, hasChanges: false, candidate: page.data.config } })
  assert.equal(page.data.canAdopt, false)
  assert.equal(page.data.summaryText, '当前内容已满足要求')
  assert.equal(JSON.stringify([page.data.config, page.data.localEditRevision]), before)
})

test('采用前刷新发现本地手改，保留手改并禁止采用旧候选', async () => {
  const page = pageFor(async () => ({ status: 'SUCCEEDED', baseDraftRevision: 0, localEditRevision: 0,
    result: { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: { components: [{ componentKey: 'ai' }] } } }))
  page.taskBasis = captureBasis(page.currentState())
  page.setData({ task: { taskId: '8' }, canAdopt: true, config: { components: [{ componentKey: 'manual' }] } })
  await page.handleAdopt()
  assert.equal(page.data.config.components[0].componentKey, 'manual')
  assert.match(page.data.taskError, /基础已变化/)
  assert.equal(page.data.canAdopt, false)
})

test('取消后忽略在途任务查询的成功候选', async () => {
  let resolveQuery
  const page = pageFor(options => options.method === 'POST' ? Promise.resolve({}) : new Promise(resolve => { resolveQuery = resolve }))
  page.setData({ waiting: true, task: { taskId: '9', status: 'RUNNING' } })
  const pending = page.queryTask()
  await page.handleCancel()
  resolveQuery({ status: 'SUCCEEDED', result: { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: {} } })
  await pending
  assert.equal(page.data.task.status, 'CANCELLED')
  assert.equal(page.data.result, null)
  assert.equal(page.data.canAdopt, false)
})

test('打开AI或无改动结果不会把已保存内容标成未保存；后续手改取消撤销', () => {
  const page = { data: { config: { components: [] }, portfolioId: '1', draftRevision: 2 },
    setData(patch) { Object.assign(this.data, patch) }, applyEditorConfig(config) { this.setData({ config }) } }
  installAdvancedEditor(page, { templateType: 'ADVANCED', ownerId: '101' })
  markAdvancedSaved(page)
  page.setData({ localEditRevision: page.data.localEditRevision + 1 })
  assert.equal(page.data.advancedDirty, false)
  page.advancedUndo = { config: {} }
  page.setData({ advancedCanUndo: true })
  page.setData({ config: { components: [{ componentKey: 'manual' }] } })
  assert.equal(page.data.advancedDirty, true)
  assert.equal(page.data.advancedCanUndo, false)
  assert.equal(page.advancedUndo, null)
})

test('高级团队创建响应丢失后禁止自动重建，已有ID保存失败继续同一草稿', async () => {
  let requests = 0
  const page = { ...advancedEditorPageMethods, data: { advancedEditing: true, advancedOwnerType: 'TEAM', teamId: '3' },
    setData(patch) { Object.assign(this.data, patch) }, advancedRequest: async () => { requests += 1; throw new Error('timeout') } }
  await assert.rejects(page.createAdvancedDraft(), /timeout/)
  await assert.rejects(page.createAdvancedDraft(), /核对列表/)
  assert.equal(requests, 1)
  assert.equal(page.data.advancedCreationUncertain, true)
})
