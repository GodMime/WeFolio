const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../pages/team-portfolios/components')
const COMPONENTS = [
  'team-profile', 'carousel', 'divider', 'member-portfolio-grid', 'member-portfolio-list',
  'text-section', 'schedule-query', 'contact-form', 'qr-contact'
]

function loadComponent(name) {
  const modulePath = path.join(ROOT, name, `${name}.js`)
  const previous = global.Component
  let definition
  global.Component = (value) => { definition = value }
  delete require.cache[require.resolve(modulePath)]
  try {
    const exports = require(modulePath)
    return { definition, exports }
  } finally {
    if (previous === undefined) delete global.Component
    else global.Component = previous
  }
}

function propertyDefault(definition, key) {
  const property = definition.properties[key]
  return Object.prototype.hasOwnProperty.call(property, 'value') ? property.value : undefined
}

function clone(value) {
  if (value === undefined || value === null || typeof value !== 'object') return value
  return JSON.parse(JSON.stringify(value))
}

function createComponentHarness(definition, initialProperties = {}) {
  const events = []
  const properties = {}
  for (const [key, descriptor] of Object.entries(definition.properties || {})) {
    properties[key] = Object.prototype.hasOwnProperty.call(initialProperties, key)
      ? initialProperties[key]
      : clone(descriptor.value)
  }
  const instance = {
    properties,
    data: clone(definition.data || {}),
    setData(patch) {
      for (const [key, value] of Object.entries(patch || {})) {
        const parts = key.split('.')
        let target = this.data
        for (let index = 0; index < parts.length - 1; index += 1) {
          target[parts[index]] = target[parts[index]] || {}
          target = target[parts[index]]
        }
        target[parts[parts.length - 1]] = value
      }
    },
    triggerEvent(name, detail) {
      events.push({ name, detail: clone(detail) })
    }
  }
  for (const [name, method] of Object.entries(definition.methods || {})) instance[name] = method.bind(instance)

  function callDefinitionObservers(changedKeys) {
    for (const [expression, observer] of Object.entries(definition.observers || {})) {
      const keys = expression.split(',').map((key) => key.trim())
      if (keys.some((key) => changedKeys.includes(key))) observer.apply(instance, keys.map((key) => instance.properties[key]))
    }
  }

  function setProperties(patch) {
    const changedKeys = Object.keys(patch)
    const oldValues = {}
    for (const key of changedKeys) oldValues[key] = instance.properties[key]
    Object.assign(instance.properties, patch)
    for (const key of changedKeys) {
      const descriptor = definition.properties[key]
      if (descriptor && typeof descriptor.observer === 'function') descriptor.observer.call(instance, instance.properties[key], oldValues[key])
    }
    callDefinitionObservers(changedKeys)
  }

  if (definition.lifetimes && typeof definition.lifetimes.attached === 'function') definition.lifetimes.attached.call(instance)
  return {
    instance,
    events,
    setProperties,
    eventsByName(name) { return events.filter((event) => event.name === name) }
  }
}

const EDIT_LIFECYCLE_CASES = [
  {
    name: 'team-profile', sourceProperty: 'team', draftKey: 'draft',
    initial: { teamId: 1, teamName: '初始团队', avatarUrl: 'a.jpg', intro: '初始' },
    latest: { teamId: 2, teamName: '最新团队', avatarUrl: 'b.jpg', intro: '最新' },
    expectedDraft(exports, source) { return exports.normalizeTeamProfile({ team: source }) },
    expectedConfig(exports, source) { return exports.buildTeamProfileConfig(source) }
  },
  {
    name: 'carousel', sourceProperty: 'items', draftKey: 'draftItems', entryEvent: 'loadmembers', extraProperties: { portfolioId: 9 },
    initial: [{ memberUserId: 1, workId: 10, title: '初始' }],
    latest: [{ memberUserId: 2, workId: 20, title: '最新', width: 800, height: 1200 }],
    expectedDraft(exports, source) { return source.map(exports.normalizeCarouselItem) },
    expectedConfig(exports, source) { return exports.buildCarouselConfig(source) }
  },
  {
    name: 'divider', sourceProperty: 'config', draftKey: 'draft',
    initial: { color: 'GRAY', heightPx: 16 }, latest: { color: 'BLACK', heightPx: 32 },
    expectedDraft(exports, source) { return exports.normalizeDividerConfig(source) },
    expectedConfig(exports, source) { return exports.normalizeDividerConfig(source) }
  },
  {
    name: 'member-portfolio-grid', sourceProperty: 'items', draftKey: 'draftItems', entryEvent: 'loadmembers', extraProperties: { portfolioId: 9 },
    initial: [{ memberUserId: 1, portfolioId: 10, title: '初始' }],
    latest: [{ memberUserId: 2, portfolioId: 20, title: '最新' }],
    expectedDraft(exports, source) { return source.map(exports.normalizeGridItem) },
    expectedConfig(exports, source) { return exports.buildGridConfig(source) }
  },
  {
    name: 'member-portfolio-list', sourceProperty: 'items', draftKey: 'draftItems', entryEvent: 'loadmembers', extraProperties: { portfolioId: 9 },
    initial: [{ memberUserId: 1, portfolioId: 10, title: '初始' }],
    latest: [{ memberUserId: 2, portfolioId: 20, title: '最新' }],
    expectedDraft(exports, source) { return source.map(exports.normalizeListItem) },
    expectedConfig(exports, source) { return exports.buildListConfig(source) }
  },
  {
    name: 'text-section', sourceProperty: 'config', draftKey: 'draft',
    initial: { content: '初始', alignment: 'LEFT' }, latest: { content: '最新😀', alignment: 'CENTER' },
    expectedDraft(exports, source) { return exports.createDefaultTextSectionConfig(source) },
    expectedConfig(exports, source) { return exports.createDefaultTextSectionConfig(source) }
  },
  {
    name: 'schedule-query', sourceProperty: 'config', draftKey: 'draft',
    initial: { title: '初始', description: '', displayMode: 'MODAL_CALENDAR', queryRange: { type: 'UNLIMITED' } },
    latest: { title: '最新', description: '说明', displayMode: 'INLINE_CALENDAR', queryRange: { type: 'FUTURE_DAYS', futureDays: 30 } },
    expectedDraft(exports, source) { return exports.createDefaultScheduleQueryConfig(source) },
    expectedConfig(exports, source) { return exports.createDefaultScheduleQueryConfig(source) }
  },
  {
    name: 'contact-form', sourceProperty: 'config', draftKey: 'draftConfig',
    initial: { title: '初始', description: '', displayMode: 'MODAL_FORM', fields: ['contactName', 'phone'] },
    latest: { title: '最新', description: '说明', displayMode: 'INLINE_FORM', fields: ['contactName', 'wechat', 'needs'] },
    expectedDraft(exports, source) { return exports.createDefaultContactConfig(source) },
    expectedConfig(exports, source) { return exports.createDefaultContactConfig(source) }
  },
  {
    name: 'qr-contact', sourceProperty: 'config', draftKey: 'draft',
    initial: { title: '初始', description: '初始说明', qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/initial.png' },
    latest: { title: '联系我们', description: '长按识别', qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/latest.png' },
    expectedDraft(exports, source) { return exports.createDefaultQrContactConfig(source) },
    expectedConfig(exports, source) { return exports.createDefaultQrContactConfig(source) }
  }
]

for (const lifecycleCase of EDIT_LIFECYCLE_CASES) {
  test(`${lifecycleCase.name} enters edit mode from properties and saves an isolated complete payload`, () => {
    const { definition, exports } = loadComponent(lifecycleCase.name)
    const initialProperties = Object.assign({ editMode: false, [lifecycleCase.sourceProperty]: lifecycleCase.initial }, lifecycleCase.extraProperties || {})
    const harness = createComponentHarness(definition, initialProperties)
    assert.deepEqual(harness.instance.data[lifecycleCase.draftKey], lifecycleCase.expectedDraft(exports, lifecycleCase.initial))

    const latestSnapshot = clone(lifecycleCase.latest)
    harness.setProperties({ [lifecycleCase.sourceProperty]: lifecycleCase.latest })
    assert.deepEqual(harness.instance.data[lifecycleCase.draftKey], lifecycleCase.expectedDraft(exports, lifecycleCase.latest))
    harness.setProperties({ editMode: true })
    assert.deepEqual(harness.instance.data[lifecycleCase.draftKey], lifecycleCase.expectedDraft(exports, lifecycleCase.latest))
    if (lifecycleCase.entryEvent) assert.equal(harness.eventsByName(lifecycleCase.entryEvent).length, 1)

    harness.instance.saveEdit()
    const saveEvents = harness.eventsByName('save')
    assert.equal(saveEvents.length, 1)
    assert.deepEqual(saveEvents[0].detail.config, lifecycleCase.expectedConfig(exports, lifecycleCase.latest))
    assert.deepEqual(lifecycleCase.latest, latestSnapshot)

    harness.instance.data[lifecycleCase.draftKey] = clone(lifecycleCase.expectedDraft(exports, lifecycleCase.initial))
    harness.instance.cancelEdit()
    assert.deepEqual(harness.instance.data[lifecycleCase.draftKey], lifecycleCase.expectedDraft(exports, lifecycleCase.latest))
    assert.equal(harness.eventsByName('cancel').length, 1)

    const attachedInEdit = createComponentHarness(definition, Object.assign({ editMode: true, [lifecycleCase.sourceProperty]: lifecycleCase.latest }, lifecycleCase.extraProperties || {}))
    assert.deepEqual(attachedInEdit.instance.data[lifecycleCase.draftKey], lifecycleCase.expectedDraft(exports, lifecycleCase.latest))
  })
}

test('all nine components are independent four-file Component packages', () => {
  for (const name of COMPONENTS) {
    const directory = path.join(ROOT, name)
    for (const extension of ['js', 'json', 'wxml', 'wxss']) {
      assert.ok(fs.existsSync(path.join(directory, `${name}.${extension}`)), `${name}.${extension}`)
    }
    const json = JSON.parse(fs.readFileSync(path.join(directory, `${name}.json`), 'utf8'))
    assert.equal(json.component, true)
    const source = fs.readFileSync(path.join(directory, `${name}.js`), 'utf8')
    assert.match(source, /Component\s*\(/)
    assert.doesNotMatch(source, /require\([^)]*components\//)
    assert.doesNotMatch(source, /switch\s*\([^)]*componentType/)
  }
})

test('team profile owns safe defaults and validates a team snapshot', () => {
  const { definition, exports } = loadComponent('team-profile')
  assert.deepEqual(propertyDefault(definition, 'team'), {})
  assert.equal(propertyDefault(definition, 'editMode'), false)
  assert.equal(exports.validateTeamProfile({ teamId: 1, teamName: '映期团队' }).valid, true)
  assert.equal(exports.validateTeamProfile({ teamId: null, teamName: '' }).valid, false)
  assert.deepEqual(exports.createDefaultTeamProfileConfig(), { team: {} })
  assert.deepEqual(exports.buildTeamProfileConfig({ teamId: 3, teamName: '只读快照', avatarUrl: 'avatar' }), { team: { teamId: 3 } })
  const wxml = fs.readFileSync(path.join(ROOT, 'team-profile/team-profile.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /bindinput|chooseAvatar/)
})

test('carousel loads members before approved image works and preserves selection order', async () => {
  const { exports } = loadComponent('carousel')
  assert.deepEqual(exports.createDefaultCarouselConfig(), { items: [] })
  const calls = []
  const requestFn = async (options) => { calls.push(options); return options.url.endsWith('/members') ? [{ memberUserId: 2 }] : [{ workId: 9, mediaType: 'IMAGE' }] }
  await exports.fetchCarouselMembers(requestFn, 5)
  await exports.fetchCarouselWorks(requestFn, 5, 2)
  assert.deepEqual(calls.map((item) => item.url), [
    '/api/mine/team-portfolios/5/components/carousel/members',
    '/api/mine/team-portfolios/5/components/carousel/members/2/works'
  ])
  assert.deepEqual(exports.toggleCarouselWork([], { memberUserId: 2, workId: 9 }), [{ memberUserId: 2, workId: 9 }])
  assert.deepEqual(exports.buildCarouselConfig([{ memberUserId: 2, workId: 9, title: '仅展示' }]), { items: [{ memberUserId: 2, workId: 9 }] })
})

test('carousel follows the current image ratio for horizontal, vertical, single and switched items', () => {
  const { definition, exports } = loadComponent('carousel')
  const horizontal = { memberUserId: 2, workId: 9, width: 1600, height: 900, mediaUrl: 'horizontal.jpg' }
  const vertical = { memberUserId: 3, workId: 10, aspectRatio: '9:16', mediaUrl: 'vertical.jpg' }
  assert.equal(exports.resolveCarouselAspectRatio(horizontal), '16 / 9')
  assert.equal(exports.resolveCarouselAspectRatio(vertical), '9 / 16')
  assert.equal(exports.resolveCarouselAspectRatio({ aspectRatio: 'invalid' }), '4 / 3')

  const single = createComponentHarness(definition, { items: [horizontal], editMode: false })
  assert.equal(single.instance.data.frameAspectRatio, '16 / 9')
  assert.equal(single.instance.data.autoplay, false)
  assert.equal(single.instance.data.circular, false)

  const switched = createComponentHarness(definition, { items: [horizontal, vertical], editMode: false })
  assert.equal(switched.instance.data.frameAspectRatio, '16 / 9')
  assert.equal(switched.instance.data.autoplay, true)
  switched.instance.handleChange({ detail: { current: 1 } })
  assert.equal(switched.instance.data.current, 1)
  assert.equal(switched.instance.data.frameAspectRatio, '9 / 16')
  assert.equal(switched.instance.data.autoplay, true)

  const wxml = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxml'), 'utf8')
  assert.match(wxml, /aspect-ratio:\s*\{\{frameAspectRatio\}\}/)
  assert.match(wxml, /mode="aspectFit"/)
})

test('divider owns color and positive height validation', () => {
  const { exports } = loadComponent('divider')
  assert.deepEqual(exports.createDefaultDividerConfig(), { color: 'GRAY', heightPx: 16 })
  assert.equal(exports.validateDividerConfig({ color: 'BLACK', heightPx: 1 }).valid, true)
  assert.equal(exports.validateDividerConfig({ color: 'RED', heightPx: 0 }).valid, false)
})

test('grid independently loads a member before that member published portfolios', async () => {
  const { exports } = loadComponent('member-portfolio-grid')
  assert.deepEqual(exports.createDefaultGridConfig(), { items: [] })
  const calls = []
  const requestFn = async (options) => { calls.push(options); return [] }
  await exports.fetchGridMembers(requestFn, 6)
  await exports.fetchGridPortfolios(requestFn, 6, 3)
  assert.deepEqual(calls.map((item) => item.url), [
    '/api/mine/team-portfolios/6/components/member-portfolio-grid/members',
    '/api/mine/team-portfolios/6/components/member-portfolio-grid/members/3/portfolios'
  ])
  const selected = [{ memberUserId: 2, portfolioId: 8 }]
  assert.deepEqual(exports.changeGridMember(selected, 3), selected)
  assert.deepEqual(exports.pruneGridItemsByMember(selected, 2), [])
  assert.deepEqual(exports.buildGridConfig([{ memberUserId: 2, portfolioId: 8, title: '仅展示' }]), { items: selected })
})

test('list independently loads a member before published portfolios and keeps list metadata', async () => {
  const { exports } = loadComponent('member-portfolio-list')
  assert.deepEqual(exports.createDefaultListConfig(), { items: [] })
  const calls = []
  const requestFn = async (options) => { calls.push(options); return [] }
  await exports.fetchListMembers(requestFn, 6)
  await exports.fetchListPortfolios(requestFn, 6, 4)
  assert.deepEqual(calls.map((item) => item.url), [
    '/api/mine/team-portfolios/6/components/member-portfolio-list/members',
    '/api/mine/team-portfolios/6/components/member-portfolio-list/members/4/portfolios'
  ])
  const selected = [{ memberUserId: 2, portfolioId: 8 }]
  assert.deepEqual(exports.changeListMember(selected, 4), selected)
  assert.deepEqual(exports.pruneListItemsByMember(selected, 2), [])
  assert.deepEqual(exports.buildListConfig([{ memberUserId: 2, portfolioId: 8, coverUrl: '仅展示' }]), { items: selected })
  const wxml = fs.readFileSync(path.join(ROOT, 'member-portfolio-list/member-portfolio-list.wxml'), 'utf8')
  assert.match(wxml, /description|memberDisplayName/)
})

test('text section trims required body, limits 200 chars and owns alignment', () => {
  const { definition, exports } = loadComponent('text-section')
  assert.deepEqual(exports.createDefaultTextSectionConfig(), { content: '', alignment: 'LEFT' })
  assert.equal(exports.validateTextSectionConfig({ content: ' 正文 ', alignment: 'CENTER' }).valid, true)
  assert.equal(exports.validateTextSectionConfig({ content: 'x'.repeat(201), alignment: 'LEFT' }).valid, false)
  assert.equal(exports.validateTextSectionConfig({ content: '😀'.repeat(200), alignment: 'LEFT' }).valid, true)
  assert.equal(exports.validateTextSectionConfig({ content: '😀'.repeat(201), alignment: 'LEFT' }).valid, false)
  assert.equal(exports.countTextCodePoints('😀a'), 2)
  assert.equal(exports.validateTextSectionConfig({ content: '正文', alignment: 'JUSTIFY' }).valid, false)

  const harness = createComponentHarness(definition, { config: { content: '', alignment: 'LEFT' }, editMode: true })
  harness.instance.handleInput({ currentTarget: { dataset: { field: 'content' } }, detail: { value: '😀a' } })
  assert.equal(harness.instance.data.count, 2)
})

test('schedule maps members to three display states and deduplicates loading', async () => {
  const { exports } = loadComponent('schedule-query')
  assert.deepEqual(exports.createDefaultScheduleQueryConfig(), {
    title: '', description: '', displayMode: 'MODAL_CALENDAR',
    queryRange: { type: 'UNLIMITED', futureDays: null, startDate: null, endDate: null }
  })
  assert.equal(exports.validateScheduleQueryConfig({ displayMode: 'MODAL', queryRange: { type: 'UNLIMITED' } }).valid, false)
  assert.equal(exports.validateScheduleQueryConfig({ displayMode: 'INLINE_CALENDAR', queryRange: { type: 'FUTURE_DAYS', futureDays: 30 } }).valid, true)
  assert.equal(exports.validateScheduleQueryConfig({ displayMode: 'MODAL_CALENDAR', queryRange: { type: 'DATE_RANGE', startDate: '2026-08-02', endDate: '2026-08-01' } }).valid, false)
  assert.deepEqual(exports.resolveScheduleDateBounds({ queryRange: { type: 'FUTURE_DAYS', futureDays: 2 } }, '2026-08-01'), { startDate: '2026-08-01', endDate: '2026-08-03' })
  assert.equal(exports.mapMemberScheduleState({ totalSlotCount: 0, availableSlotCount: 0, emptySlotDefinition: true }).text, '已满')
  assert.equal(exports.mapMemberScheduleState({ totalSlotCount: 3, availableSlotCount: 1 }).text, '部分档期空闲')
  assert.equal(exports.mapMemberScheduleState({ totalSlotCount: 3, availableSlotCount: 0 }).text, '已满')
  assert.equal(exports.mapMemberScheduleState({ slotStatuses: ['BOOKED', 'TENTATIVE', 'REST'] }).text, '已满')
  assert.equal(exports.mapMemberScheduleState({ slotStatuses: ['AVAILABLE', 'BOOKED'] }).text, '部分档期空闲')
  let resolveRequest
  const calls = []
  const runner = exports.createTeamScheduleQueryRunner((options) => {
    calls.push(options)
    return new Promise((resolve) => { resolveRequest = resolve })
  })
  const first = runner({ shareCode: 'TEAM', componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'q1' })
  const duplicate = await runner({ shareCode: 'TEAM', componentKey: 'schedule-1', queriedDate: '2026-08-02', idempotencyKey: 'q2' })
  assert.equal(calls.length, 1)
  assert.equal(duplicate, null)
  resolveRequest({ status: 'TEAM_PARTIAL_AVAILABLE', members: [] })
  await first
  assert.equal(calls[0].url, '/api/visitor/team-portfolios/TEAM/schedule-query')
})

test('schedule component and runner generate and reuse one valid key across a failed retry', async () => {
  const { definition, exports } = loadComponent('schedule-query')
  const harness = createComponentHarness(definition, {
    shareCode: 'TEAM',
    componentKey: 'schedule-1',
    config: exports.createDefaultScheduleQueryConfig(),
    editMode: false
  })
  harness.instance.setData({ selectedDate: '2026-08-01' })
  harness.instance.submitQuery()
  const firstDetail = harness.eventsByName('schedulequery')[0].detail
  assert.ok(firstDetail.idempotencyKey)
  assert.ok(firstDetail.idempotencyKey.length <= 64)
  harness.instance.rejectQuery({ detail: { message: '网络失败' } })
  harness.instance.submitQuery()
  const retryDetail = harness.eventsByName('schedulequery')[1].detail
  assert.equal(retryDetail.idempotencyKey, firstDetail.idempotencyKey)
  harness.instance.resolveQuery({ detail: { status: 'TEAM_AVAILABLE', members: [] } })
  harness.instance.submitQuery()
  const nextDetail = harness.eventsByName('schedulequery')[2].detail
  assert.notEqual(nextDetail.idempotencyKey, firstDetail.idempotencyKey)

  const requests = []
  const runner = exports.createTeamScheduleQueryRunner(async (options) => {
    requests.push(options)
    if (requests.length === 1) throw new Error('网络失败')
    return { status: 'TEAM_AVAILABLE', members: [] }
  })
  const params = { shareCode: 'TEAM', componentKey: 'schedule-1', queriedDate: '2026-08-01' }
  await assert.rejects(() => runner(params), /网络失败/)
  await runner(params)
  assert.ok(requests[0].data.idempotencyKey)
  assert.ok(requests[0].data.idempotencyKey.length <= 64)
  assert.equal(requests[1].data.idempotencyKey, requests[0].data.idempotencyKey)

  let invalidRequestCount = 0
  const invalidRunner = exports.createTeamScheduleQueryRunner(async () => { invalidRequestCount += 1 })
  await assert.rejects(() => invalidRunner(Object.assign({}, params, { idempotencyKey: '   ' })), /幂等键/)
  await assert.rejects(() => invalidRunner(Object.assign({}, params, { idempotencyKey: 'x'.repeat(65) })), /幂等键/)
  assert.equal(invalidRequestCount, 0)
})

test('schedule component discards its idempotency key only for a confirmed rejection', () => {
  const { definition, exports } = loadComponent('schedule-query')
  const harness = createComponentHarness(definition, {
    shareCode: 'TEAM',
    componentKey: 'schedule-1',
    config: exports.createDefaultScheduleQueryConfig(),
    editMode: false
  })
  harness.instance.setData({ selectedDate: '2026-08-01' })
  harness.instance.submitQuery()
  const firstKey = harness.eventsByName('schedulequery')[0].detail.idempotencyKey
  harness.instance.rejectQuery({ detail: { message: '参数无效', clearPendingIdempotencyKey: true } })
  harness.instance.submitQuery()
  const confirmedRetryKey = harness.eventsByName('schedulequery')[1].detail.idempotencyKey
  assert.notEqual(confirmedRetryKey, firstKey)
  harness.instance.rejectQuery({ detail: { message: '网络失败', clearPendingIdempotencyKey: false } })
  harness.instance.submitQuery()
  assert.equal(harness.eventsByName('schedulequery')[2].detail.idempotencyKey, confirmedRetryKey)
})

test('contact form validates plaintext input and clears only after success', () => {
  const { exports } = loadComponent('contact-form')
  assert.deepEqual(exports.createDefaultContactConfig(), {
    title: '', description: '', displayMode: 'MODAL_FORM', fields: ['contactName', 'phone', 'wechat', 'needs']
  })
  assert.equal(exports.validateContactConfig({ displayMode: 'MODAL', fields: ['contactName', 'phone'] }).valid, false)
  assert.equal(exports.validateContactConfig({ displayMode: 'INLINE_FORM', fields: ['contactName', 'wechat'] }).valid, true)
  assert.equal(exports.validateContactConfig({ displayMode: 'MODAL_FORM', fields: ['phone'] }).valid, false)
  assert.deepEqual(exports.createFieldVisibility(['contactName', 'wechat']), { contactName: true, phone: false, wechat: true, needs: false })
  const form = exports.createDefaultContactForm()
  form.contactName = '客户'
  form.phone = '13800138000'
  assert.equal(exports.validateContactForm(form).valid, true)
  assert.equal(exports.validateContactForm({ contactName: '客户', phone: '13800138000', wechat: '' }, ['contactName', 'wechat']).valid, false)
  assert.deepEqual(exports.buildVisibleContactForm({ contactName: '客户', phone: '13800138000', wechat: 'wx', desiredSchedule: '八月', needs: '主持' }, ['contactName', 'wechat']), {
    contactName: '客户', phone: '', wechat: 'wx', desiredSchedule: '八月', needs: ''
  })
  assert.equal(exports.reduceContactSubmit(form, false).phone, '13800138000')
  assert.deepEqual(exports.reduceContactSubmit(form, true), exports.createDefaultContactForm())
  assert.equal(exports.CONTACT_SUCCESS_TEXT, '已提交给团队')
  const wxml = fs.readFileSync(path.join(ROOT, 'contact-form/contact-form.wxml'), 'utf8')
  assert.match(wxml, /displayConfig\.description/)
  assert.match(wxml, /fieldVisibility\.phone/)
  assert.doesNotMatch(wxml, /submitText/)
})

test('QR is CUSTOM-only and distinguishes maintainer preview from visitor interaction', () => {
  const { definition, exports } = loadComponent('qr-contact')
  assert.deepEqual(exports.createDefaultQrContactConfig(), { title: '', description: '', qrUrlSource: 'CUSTOM', qrUrl: '' })
  assert.deepEqual(exports.createDefaultQrContactConfig({ title: '联系我们', description: '长按识别', qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/qr.png' }), {
    title: '联系我们', description: '长按识别', qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/qr.png'
  })
  assert.equal(exports.validateQrContactConfig({ qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/qr.png' }).valid, true)
  assert.equal(exports.validateQrContactConfig({ qrUrlSource: 'PROFILE', qrUrl: 'https://cdn/qr.png' }).valid, false)
  assert.equal(propertyDefault(definition, 'visitorMode'), false)
  const events = []
  const previews = []
  const context = { properties: { visitorMode: false, componentKey: 'qr-1', config: { qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/qr.png' } }, triggerEvent: (name, detail) => events.push([name, detail]) }
  exports.handleQrTap(context, { previewImage: (options) => previews.push(options) })
  assert.equal(previews.length, 1)
  assert.deepEqual(events, [['preview', { componentKey: 'qr-1', qrUrl: 'https://cdn/qr.png' }]])
  context.properties.visitorMode = true
  exports.handleQrTap(context, { previewImage: () => undefined })
  assert.deepEqual(events[1], ['interact', { eventType: 'QR_CODE_INTERACTED', componentKey: 'qr-1', action: 'CLICK' }])
  exports.handleQrLongPress(context)
  assert.deepEqual(events[2], ['interact', { eventType: 'QR_CODE_INTERACTED', componentKey: 'qr-1', action: 'LONG_PRESS' }])
})

test('component styles constrain cards and long text without decorative gradients', () => {
  for (const name of COMPONENTS) {
    const source = fs.readFileSync(path.join(ROOT, name, `${name}.wxss`), 'utf8')
    assert.doesNotMatch(source, /linear-gradient|radial-gradient/)
    assert.doesNotMatch(source, /border-radius:\s*(?:[9-9]|[1-9]\d+)px/)
    assert.match(source, /overflow-wrap|word-break|text-overflow/)
  }
})
