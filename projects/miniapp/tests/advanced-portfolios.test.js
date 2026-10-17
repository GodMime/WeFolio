const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const personal = require('../pages/portfolios/utils/advanced-portfolios')
const team = require('../pages/team-portfolios/utils/advanced-portfolios')
const { EDITOR_SCHEMA_REVISION } = require('../pages/portfolios/utils/portfolios')
const { TEAM_EDITOR_SCHEMA_REVISION } = require('../pages/team-portfolios/utils/team-portfolios')

function readTaskMessage(moduleName) {
  const file = path.resolve(__dirname, '../../java', moduleName, 'src/main/java/com/jxc/wefolio/message/PortfolioAiTaskMessage.java')
  const source = fs.readFileSync(file, 'utf8')
  const match = source.match(/String\s+TASK_FAILED_MESSAGE\s*=\s*"([^"]+)"/)
  assert.ok(match, `${moduleName} 必须定义 TASK_FAILED_MESSAGE`)
  return { source, taskFailedMessage: match[1] }
}

test('高级编辑分包共享同一会话规则且不跨分包引用', () => {
  for (const name of ['advanced-portfolios', 'advanced-ai-page', 'advanced-editor-page']) {
    const read = root => fs.readFileSync(path.join(__dirname, '..', 'pages', root, `utils/${name}.js`), 'utf8')
    assert.equal(read('portfolios'), read('team-portfolios'))
  }
})

for (const [name, api] of [['个人', personal], ['团队', team]]) {
  test(`${name}分包 AI 任务只支持创建和整体修改，提交字段符合现有协议`, () => {
    const current = { ownerType: 'USER', ownerId: '1', config: { components: [] }, aiContext: api.normalizeContext() }
    assert.deepEqual(api.AI_TASK_MODES, ['CREATE', 'MODIFY_PAGE'])
    for (const mode of api.AI_TASK_MODES) {
      const payload = api.buildTaskPayload(current, { mode, prompt: '整体更简洁' }, 'overall')
      assert.equal(payload.mode, mode)
      assert.deepEqual(Object.keys(payload).sort(), ['aiContext', 'aiContractVersion', 'baseConfig', 'baseDraftRevision',
        'clientCapabilities', 'editorSchemaRevision', 'idempotencyKey', 'localEditRevision', 'mode', 'ownerId', 'ownerType',
        'portfolioId', 'prompt', 'targetMenuKey'])
      assert.deepEqual(payload.baseConfig, mode === 'CREATE' ? null : current.config)
    }
    assert.equal(api.buildTaskPayload({ ...current, config: null }, { prompt: '整体需求' }, 'create').mode, 'CREATE')
    assert.equal(api.buildTaskPayload(current, { prompt: '整体需求' }, 'modify').mode, 'MODIFY_PAGE')
    assert.throws(() => api.buildTaskPayload(current, { mode: 'MANAGE_SOURCES' }, 'local'), /AI 操作参数无效/)
    assert.throws(() => api.buildTaskPayload(current, { mode: 'INVALID_MODE' }, 'invalid'), /AI 操作参数无效/)
  })

  test(`${name}分包 AI 请求的个人与团队版本始终对齐普通编辑器常量`, () => {
    for (const [ownerType, revision] of [['USER', EDITOR_SCHEMA_REVISION], ['TEAM', TEAM_EDITOR_SCHEMA_REVISION]]) {
      const payload = api.buildTaskPayload({ ownerType, ownerId: '1', config: null, aiContext: api.normalizeContext() },
        { mode: 'CREATE', prompt: '编排当前来源' }, 'schema-revision')
      assert.equal(payload.editorSchemaRevision, revision)
    }
  })
  test(`${name}分包任务失败兜底与 Runtime、Job 公共文案一致`, () => {
    const runtime = readTaskMessage('wefolio-java-runtime')
    const job = readTaskMessage('wefolio-java-job')
    assert.equal(runtime.source, job.source)
    assert.equal(api.TASK_FAILED_MESSAGE, runtime.taskFailedMessage)
    assert.equal(api.statusMessage({ status: 'FAILED' }), runtime.taskFailedMessage)
  })
  test(`${name}来源编号移除不回收，重复选择去重`, () => {
    let context = api.addSource(null, { kind: 'WORK', sourceId: '100' })
    context = api.addSource(context, { kind: 'WORK', sourceId: '200' })
    assert.equal(api.addSource(context, { kind: 'WORK', sourceId: '100' }).nextSourceNo, 3)
    context = api.removeSource(context, 2)
    context = api.addSource(context, { kind: 'WORK', sourceId: '200' })
    assert.deepEqual(context.sources.map(x => x.sourceNo), [1, 3])
    assert.equal(context.nextSourceNo, 4)
  })
  test(`${name}同一来源可恢复旧编号，冲突或无效旧号不覆盖其他来源`, () => {
    let context = api.addSource(null, { kind: 'WORK', sourceId: '100' })
    context = api.addSource(context, { kind: 'WORK', sourceId: '200' })
    context = api.removeSource(context, 1)
    const restored = api.addSource(context, { kind: 'WORK', sourceId: '100' }, 1)
    assert.deepEqual(restored.sources.map(source => [source.sourceId, source.sourceNo]), [['200', 2], ['100', 1]])
    assert.equal(restored.nextSourceNo, 3)
    assert.equal(api.validatePrompt('1号放顶部，2号放下面', restored), '')
    for (const invalidNo of [2, 0, -1, 1.5, 3, 99]) {
      const added = api.addSource(context, { kind: 'WORK', sourceId: '300' }, invalidNo)
      assert.deepEqual(added.sources.map(source => source.sourceNo), [2, 3])
      assert.equal(added.nextSourceNo, 4)
    }
  })
  test(`${name}明确失效号码拒绝生成，日期字号不误报`, () => {
    const context = api.addSource(null, { kind: 'WORK', sourceId: '100' })
    assert.equal(api.validatePrompt('1号放顶部，2026年10月9日，字号32', context), '')
    assert.match(api.validatePrompt('2号放顶部', context), /2 号/)
    assert.match(api.validatePrompt('😀'.repeat(2001), context), /2,000/)
  })
  test(`${name}候选必须保持任务提交时的身份、配置、来源与修订`, () => {
    const current = { ownerType: 'USER', ownerId: '1', portfolioId: null, draftRevision: 0, config: { components: [] }, aiContext: api.normalizeContext(), localEditRevision: 2 }
    const basis = api.captureBasis(current)
    const response = { status: 'SUCCEEDED', baseDraftRevision: 0, localEditRevision: 2, result: { kind: 'CANDIDATE', available: true, hasChanges: true, candidate: { components: [{ componentKey: 'x' }] } } }
    assert.equal(api.candidateError(response, basis, current), '')
    assert.match(api.candidateError(response, basis, { ...current, portfolioId: '9' }), /变化/)
    assert.match(api.candidateError(response, basis, { ...current, config: { components: [{ componentKey: 'manual' }] } }), /变化/)
    assert.match(api.candidateError(response, basis, { ...current, aiContext: api.addSource(current.aiContext, { kind: 'WORK', sourceId: '5' }) }), /变化/)
    assert.match(api.candidateError({ ...response, result: { ...response.result, hasChanges: false } }, basis, current), /已满足/)
  })
  test(`${name}任务提交网络重放保留同一个幂等键`, () => {
    const current = { ownerType: 'TEAM', ownerId: '1', portfolioId: '4', config: { schemaVersion: 'standard-team-v1' }, draftRevision: 7, localEditRevision: 4, aiContext: api.normalizeContext() }
    const payload = api.buildTaskPayload(current, { prompt: '精简已有文字', mode: 'MODIFY_PAGE' }, 'retry-same')
    assert.equal(payload.idempotencyKey, 'retry-same')
    assert.equal(payload.baseDraftRevision, 7)
    assert.equal(payload.targetMenuKey, '__root__')
    assert.deepEqual(payload.baseConfig, current.config)
    assert.equal(payload.editorSchemaRevision, TEAM_EDITOR_SCHEMA_REVISION)
  })
}
