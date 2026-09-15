const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const personal = require('../pages/portfolios/utils/portfolio-work-detail')
const team = require('../pages/team-portfolios/utils/portfolio-work-detail')
const flush = () => new Promise(resolve => setImmediate(resolve))
function options(mediaType = 'IMAGE', overrides = {}) {
  return Object.assign({ componentKey: 'single', components: [{ componentKey: 'single', componentType: 'SINGLE_WORK',
    openMode: 'DETAIL_PAGE', detailOptions: { showTitle: false, showDescription: true },
    work: { workId: 7, title: '作品', description: '完整\n描述', mediaType, mediaUrl: 'https://example.test/media', coverUrl: 'https://example.test/cover' } }],
    theme: { backgroundColor: '#111111' }, browserContext: { idempotencyKey: 'original-open-key' } }, overrides)
}
function harness(utility) {
  let receive, detached = 0, pause = 0
  const timers = new Map()
  global.wx = { hideShareMenu() {}, createVideoContext() { return { pause() { pause++ } } }, showToast() {} }
  const definition = utility.createWorkDetailPage({ fallbackUrl: '/pages/index/index',
    setTimer(callback) { timers.set(1, callback); return 1 }, clearTimer(id) { timers.delete(id) } })
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done() },
    getOpenerEventChannel() { return { on(name, handler) { assert.equal(name, utility.DETAIL_EVENT); receive = handler }, off() { detached++ } } } })
  page.onLoad()
  return { page, receive: payload => receive(payload), detached: () => detached, pause: () => pause,
    expire() { const callback = timers.get(1); if (callback) callback() }, timers }
}
test('two subpackages keep identical snapshot and lifecycle contract', () => {
  assert.equal(fs.readFileSync(path.join(__dirname, '../pages/portfolios/utils/portfolio-work-detail.js'), 'utf8'),
    fs.readFileSync(path.join(__dirname, '../pages/team-portfolios/utils/portfolio-work-detail.js'), 'utf8'))
})
for (const [name, utility] of [['personal', personal], ['team', team]]) {
  test(`${name} missing, blank, mismatched or expired browser context cannot display a detail snapshot`, () => {
    for (const browserContext of [null, {}, { idempotencyKey: '' }, { idempotencyKey: '   ' }, { idempotencyKey: 'old', isInvalid: () => true }, { idempotencyKey: 'old', isDisposed: () => true }]) {
      assert.equal(utility.createWorkDetailPayload(options('IMAGE', { browserContext })), null)
    }
    const legal = utility.createWorkDetailPayload(options())
    for (const invalid of [{ ...legal, contextId: '' }, { ...legal, contextId: 'wrong-key' }, { ...legal, browserContext: null }]) {
      assert.equal(utility.validPayload(invalid), false)
      const state = harness(utility)
      state.receive(invalid)
      assert.equal(state.page.data.available, false)
      assert.equal(state.page.data.loadingContext, false)
      state.page.onUnload()
    }
    const preview = utility.createWorkDetailPayload(options('IMAGE', { preview: true, browserContext: null }))
    assert.equal(preview.contextId, 'preview'); assert.equal(utility.validPayload(preview), true)
    assert.equal(utility.validPayload({ ...preview, contextId: '' }), false)
    delete global.wx
  })
  test(`${name} waits for event channel without an unavailable flash and resolves timeout or unload`, () => {
    const state = harness(utility)
    assert.equal(state.page.data.loadingContext, true)
    state.receive(utility.createWorkDetailPayload(options()))
    assert.equal(state.page.data.loadingContext, false); assert.equal(state.page.data.available, true)
    assert.equal(state.timers.size, 0); state.page.onUnload()
    const missing = harness(utility)
    missing.expire()
    assert.equal(missing.page.data.loadingContext, false); assert.equal(missing.page.data.available, false)
    missing.receive(utility.createWorkDetailPayload(options()))
    assert.equal(missing.page.data.available, false)
    missing.page.onUnload()
    const unloaded = harness(utility)
    unloaded.page.onUnload(); assert.equal(unloaded.timers.size, 0)
    delete global.wx
  })
  test(`${name} resolves only matching legal single work and snapshots full copy`, () => {
    const source = options()
    const payload = utility.createWorkDetailPayload(source)
    assert.equal(payload.theme.themeMode, 'dark')
    assert.equal(payload.contextId, 'original-open-key')
    assert.deepEqual(payload.detailOptions, { showTitle: false, showDescription: true })
    source.components[0].work.description = '修改来源'
    assert.equal(payload.work.description, '完整\n描述')
    assert.equal(utility.createWorkDetailPayload(options('IMAGE', { componentKey: 'unknown' })), null)
    source.components[0].componentType = 'VIDEO_CAROUSEL'
    assert.equal(utility.createWorkDetailPayload(source), null)
    source.components[0].componentType = 'SINGLE_WORK'; source.components[0].openMode = 'INLINE'
    assert.equal(utility.createWorkDetailPayload(source), null)
    assert.equal(utility.createWorkDetailPayload(options('AUDIO')), null)
    const teamSource = options(); teamSource.components[0] = { componentKey: 'single', componentType: 'SINGLE_WORK', data: teamSource.components[0] }
    assert.equal(utility.createWorkDetailPayload(teamSource).work.workId, 7)
  })
  test(`${name} missing cold-start context remains unavailable and ignores late events`, () => {
    const state = harness(utility); state.page.onShow()
    assert.equal(state.page.data.available, false)
    state.receive({ workId: 7 })
    assert.equal(state.page.data.available, false)
    state.page.onUnload(); state.receive(utility.createWorkDetailPayload(options()))
    assert.equal(state.page.data.available, false)
    assert.equal(state.detached(), 1)
    delete global.wx
  })
  test(`${name} transfers one timer owner and records one real image/video event`, async () => {
    for (const mediaType of ['IMAGE', 'VIDEO']) {
      const owners = [], events = []
      const context = { idempotencyKey: 'session', show(owner) { owners.push(['show', owner]) }, hide(owner) { owners.push(['hide', owner]) }, dispose() { assert.fail('详情不能释放来源会话') } }
      const state = harness(utility)
      state.receive(utility.createWorkDetailPayload(options(mediaType, { browserContext: context, onWorkEvent: work => events.push(work) })))
      state.page.onShow()
      assert.deepEqual(events, [])
      if (mediaType === 'VIDEO') { state.page.handleVideoPlay(); state.page.handleVideoPlay() }
      else { state.page.handleImageLoaded(); state.page.handleImageLoaded() }
      await flush(); assert.equal(events.length, 1)
      state.page.onHide(); state.page.onShow(); state.page.onUnload()
      assert.deepEqual(owners.map(item => item[0]), ['show', 'hide', 'show', 'hide'])
      assert.ok(owners.every(item => item[1] === state.page)); assert.equal(state.pause(), 2)
    }
    delete global.wx
  })
  test(`${name} preview does not capture activity or work events`, async () => {
    const state = harness(utility)
    const payload = utility.createWorkDetailPayload(options('IMAGE', { preview: true, onWorkEvent() { assert.fail('preview事件') } }))
    assert.equal(payload.browserContext, null)
    state.receive(payload); state.page.onShow(); state.page.handleImageLoaded(); await flush()
    state.page.onUnload(); delete global.wx
  })
  test(`${name} late media callbacks after hide or unload never report work events`, async () => {
    for (const mediaType of ['IMAGE', 'VIDEO']) {
      const events = [], state = harness(utility)
      state.receive(utility.createWorkDetailPayload(options(mediaType, { onWorkEvent: work => events.push(work) })))
      const handleMedia = mediaType === 'IMAGE' ? () => state.page.handleImageLoaded() : () => state.page.handleVideoPlay()
      state.page.onShow(); state.page.onHide(); handleMedia(); await flush()
      assert.equal(events.length, 0)
      state.page.onShow(); handleMedia(); await flush()
      assert.equal(events.length, 1)
      state.page.onUnload(); handleMedia(); await flush()
      assert.equal(events.length, 1)
    }
    delete global.wx
  })
  test(`${name} media dispatch queued before hide or unload rechecks lifecycle`, async () => {
    for (const lifecycle of ['onHide', 'onUnload']) {
      for (const mediaType of ['IMAGE', 'VIDEO']) {
        const events = [], state = harness(utility)
        state.receive(utility.createWorkDetailPayload(options(mediaType, { onWorkEvent: work => events.push(work) })))
        const handleMedia = mediaType === 'IMAGE' ? () => state.page.handleImageLoaded() : () => state.page.handleVideoPlay()
        state.page.onShow(); handleMedia(); state.page[lifecycle](); await flush()
        assert.equal(events.length, 0)
        if (lifecycle === 'onHide') {
          state.page.onShow(); handleMedia(); await flush()
          assert.equal(events.length, 1)
          state.page.onUnload()
        }
      }
    }
    delete global.wx
  })
  test(`${name} navigation carries event channel payload once and never routes by work id`, () => {
    let call, emitted
    global.wx = { navigateTo(options) { call = options }, showToast() {} }
    const source = {}; const opts = options('IMAGE', { route: '/pages/test/detail' })
    assert.equal(utility.openWorkDetail(source, opts), true)
    assert.equal(utility.openWorkDetail(source, opts), false)
    assert.equal(call.url, '/pages/test/detail'); assert.equal(call.url.includes('workId'), false)
    call.success({ eventChannel: { emit(name, payload) { emitted = [name, payload] } } }); call.complete()
    assert.equal(emitted[0], utility.DETAIL_EVENT); assert.equal(emitted[1].work.workId, 7)
    assert.equal(source._openingWorkDetail, false); delete global.wx
  })
}

test('detail choices round trip independently from card text and remain when switching inline', () => {
  const { normalizeSingleWorkConfig, createComponent, COMPONENT_TYPES, updateSingleWorkConfig, normalizePortfolioConfig } = require('../pages/portfolios/utils/portfolios')
  const detailOptions = { showTitle: false, showDescription: true }
  const config = normalizeSingleWorkConfig({ workId: 7, showTitle: true, showDescription: false, openMode: 'DETAIL_PAGE', detailOptions })
  assert.equal(config.showTitle, true); assert.equal(config.showDescription, false)
  assert.deepEqual(config.detailOptions, detailOptions)
  const portfolio = normalizePortfolioConfig({ components: [createComponent(COMPONENT_TYPES.SINGLE_WORK, { componentKey: 'single', config })] })
  const saved = updateSingleWorkConfig(portfolio, 'single', Object.assign({}, config, { openMode: 'INLINE' }))
  assert.deepEqual(saved.components[0].config.detailOptions, detailOptions)
  assert.equal(saved.components[0].config.openMode, 'INLINE')
})

test('both single work components emit only detail for a detail click', () => {
  for (const packageName of ['portfolios', 'team-portfolios']) {
    const file = path.join(__dirname, `../pages/${packageName}/components/single-work/single-work.js`)
    let definition
    global.Component = value => { definition = value }; delete require.cache[require.resolve(file)]; require(file); delete global.Component
    const events = [], props = { openMode: 'DETAIL_PAGE', componentKey: 'single', work: { workId: 7, mediaType: 'VIDEO', mediaUrl: 'https://example.test/video' } }
    const instance = { data: props, properties: props, triggerEvent(name, detail) { events.push([name, detail]) } }
    const handler = definition.methods.handleSingleWorkTap || definition.methods.handleMediaTap
    handler.call(instance, { currentTarget: { dataset: { workId: 7 } } })
    assert.deepEqual(events, [['detail', { componentKey: 'single' }]])
  }
})

test('authorization refresh delegates source update then returns from stale detail and restores callback', () => {
  for (const utility of [personal, team]) {
    const state = harness(utility)
    let sourceRefreshes = 0, backs = 0
    const originalRefresh = () => { sourceRefreshes++ }
    const context = { idempotencyKey: 'same-context', onRefresh: originalRefresh, show() {}, hide() {} }
    state.receive(utility.createWorkDetailPayload(options('IMAGE', { browserContext: context })))
    state.page.handleBack = () => { backs++ }
    state.page.onShow()
    context.onRefresh({ visitorKey: 'original' })
    assert.equal(sourceRefreshes, 1); assert.equal(backs, 1); assert.equal(state.page.data.available, false)
    state.page.onUnload(); assert.equal(context.onRefresh, originalRefresh)
    delete global.wx
  }
})

test('single work picker emits open mode and independent detail options without changing card settings', () => {
  const file = require.resolve('../pages/portfolios/components/single-work-picker/single-work-picker.js')
  let definition
  global.Component = value => { definition = value }
  delete require.cache[file]; require(file); delete global.Component
  const events = []
  const props = { openMode: 'INLINE', detailOptions: { showTitle: false, showDescription: true }, showTitle: true, showDescription: false }
  const component = { properties: props, triggerEvent(name, detail) { events.push({ name, detail }) } }
  definition.methods.handleOpenModeChange.call(component, { detail: { value: true } })
  assert.deepEqual(events[0], { name: 'detailoptionschange', detail: { openMode: 'DETAIL_PAGE', detailOptions: props.detailOptions } })
  props.openMode = 'DETAIL_PAGE'
  definition.methods.handleDetailOptionChange.call(component, { currentTarget: { dataset: { field: 'showDescription' } }, detail: { value: false } })
  assert.deepEqual(events[1].detail, { openMode: 'DETAIL_PAGE', detailOptions: { showTitle: false, showDescription: false } })
  assert.deepEqual(props.detailOptions, { showTitle: false, showDescription: true })
  definition.methods.handleOpenModeChange.call(component, { detail: { value: false } })
  assert.deepEqual(events[2].detail, { openMode: 'INLINE', detailOptions: props.detailOptions })
  assert.equal(props.showTitle, true); assert.equal(props.showDescription, false)
})
