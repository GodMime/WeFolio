const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../pages/team-portfolios/components')
const COMPONENTS = [
  'team-profile', 'carousel', 'single-work', 'divider', 'member-portfolio-grid', 'member-portfolio-list',
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

function resolveClassBackground(sources, ancestorClass, elementClass) {
  let winner = null
  let order = 0
  for (const source of sources) {
    for (const match of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const background = match[2].match(/(?:^|;)\s*background\s*:\s*([^;]+)/)
      if (!background) continue
      for (const rawSelector of match[1].split(',')) {
        const selectorClasses = Array.from(rawSelector.matchAll(/\.([A-Za-z0-9_-]+)/g))
          .map((classMatch) => classMatch[1])
        const matchesElement = selectorClasses.includes(elementClass)
        const matchesContext = selectorClasses.every((className) => {
          return className === elementClass || className === ancestorClass
        })
        if (!matchesElement || !matchesContext) continue
        const candidate = {
          value: background[1].trim(),
          specificity: selectorClasses.length,
          order
        }
        if (!winner ||
            candidate.specificity > winner.specificity ||
            (candidate.specificity === winner.specificity && candidate.order > winner.order)) {
          winner = candidate
        }
      }
      order += 1
    }
  }
  return winner && winner.value
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
    name: 'single-work', sourceProperty: 'config', draftKey: 'draft', entryEvent: 'loadmembers', extraProperties: { portfolioId: 9 },
    initial: { memberUserId: 1, workId: 10, showTitle: true, showDescription: false },
    latest: { memberUserId: 2, workId: 20, showTitle: false, showDescription: true },
    expectedDraft(exports, source) { return exports.normalizeSingleWorkConfig(source) },
    expectedConfig(exports, source) { return exports.normalizeSingleWorkConfig(source) }
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
    initial: { qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/initial.png' },
    latest: { qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/latest.png' },
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

test('all ten components are independent four-file Component packages', () => {
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

test('single work selects member before work and keeps one 16rpx copy gap', () => {
  const { definition, exports } = loadComponent('single-work')
  const harness = createComponentHarness(definition, {
    portfolioId: 9,
    config: { memberUserId: 1, workId: 10, showTitle: true, showDescription: false },
    editMode: true
  })

  harness.instance.selectMember({ currentTarget: { dataset: { id: 2 } } })
  assert.deepEqual(harness.instance.data.draft, {
    memberUserId: 2, workId: null, showTitle: true, showDescription: false
  })
  harness.instance.selectWork({ currentTarget: { dataset: { item: { workId: 20, mediaType: 'VIDEO' } } } })
  harness.instance.handleShowDescriptionChange({ detail: { value: true } })
  harness.instance.saveEdit()
  assert.deepEqual(harness.eventsByName('save')[0].detail.config, {
    memberUserId: 2, workId: 20, showTitle: true, showDescription: true
  })
  assert.equal(exports.validateSingleWorkConfig(harness.eventsByName('save')[0].detail.config).valid, true)

  const wxml = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxss'), 'utf8')
  assert.match(wxml, /mode="widthFix"/)
  assert.match(wxml, /autoplay="\{\{true\}\}"/)
  assert.match(wxml, /showTitle && work\.title/)
  assert.match(wxml, /showDescription && work\.description/)
  assert.match(wxss, /single-work-copy[\s\S]*min-height:\s*16rpx[\s\S]*padding-top:\s*16rpx/)
  assert.match(wxss, /#59636f/)
})

test('single work restores a saved member only once when sources are rebound', () => {
  const { definition } = loadComponent('single-work')
  const harness = createComponentHarness(definition, {
    portfolioId: 9,
    config: { memberUserId: 1, workId: 10, showTitle: true, showDescription: false },
    editMode: true
  })
  const members = [{ memberUserId: 1, displayName: '成员一' }]

  harness.setProperties({ members })
  assert.equal(harness.eventsByName('memberchange').length, 1)

  harness.setProperties({
    members,
    works: [{ workId: 10, title: '作品一', mediaType: 'IMAGE' }]
  })
  assert.equal(harness.eventsByName('memberchange').length, 1)
})

test('single work keeps the complete video and cover inside a black player', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxss'), 'utf8')

  assert.match(wxml, /<video[^>]*class="single-work-video"[^>]*object-fit="contain"/)
  assert.match(wxml, /<image[^>]*class="single-work-video-cover"[^>]*mode="aspectFit"/)
  assert.match(wxss, /\.single-work-video,\s*\.single-work-video-poster\s*\{[^}]*background:\s*#000;/)
})

test('single work keeps transparent image media transparent', () => {
  const wxss = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxss'), 'utf8')

  assert.match(wxss, /\.single-work-image\s*\{[^}]*background:\s*transparent;/)
  assert.match(wxss, /\.single-work-video,\s*\.single-work-video-poster\s*\{[^}]*background:\s*#000;/)
})

test('single work keeps transparent image media transparent in the dark theme', () => {
  const themeWxss = fs.readFileSync(
    path.resolve(ROOT, '../styles/team-portfolio-theme.wxss'),
    'utf8'
  )
  const componentWxss = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxss'), 'utf8')

  assert.equal(
    resolveClassBackground([themeWxss, componentWxss], 'theme-dark', 'single-work-image'),
    'transparent'
  )
})

test('single work renders animation media and falls back to its static cover', () => {
  const { definition } = loadComponent('single-work')
  const harness = createComponentHarness(definition, {
    componentKey: 'single-animation',
    work: {
      workId: 21,
      mediaType: 'ANIMATION',
      mediaUrl: 'animation.webp',
      coverUrl: 'animation-cover.jpg'
    }
  })
  const wxml = fs.readFileSync(path.join(ROOT, 'single-work/single-work.wxml'), 'utf8')

  assert.match(wxml, /work\.mediaType === 'ANIMATION' && !animationLoadFailed/)
  assert.match(wxml, /src="\{\{work\.mediaUrl\}\}"[\s\S]*webp="\{\{true\}\}"[\s\S]*binderror="handleAnimationLoadError"/)
  assert.match(wxml, /wx:elif="\{\{work\.mediaType === 'ANIMATION'\}\}"[\s\S]*src="\{\{work\.coverUrl\}\}"/)

  harness.instance.handleAnimationLoadError()
  harness.instance.handleMediaTap()
  const preview = harness.eventsByName('preview')[0]
  assert.equal(preview.detail.work.mediaType, 'ANIMATION')
  assert.equal(preview.detail.work.mediaUrl, 'animation-cover.jpg')
})

test('team profile owns safe defaults and validates a team snapshot', () => {
  const { definition, exports } = loadComponent('team-profile')
  assert.deepEqual(propertyDefault(definition, 'team'), {})
  assert.equal(propertyDefault(definition, 'editMode'), false)
  assert.equal(exports.validateTeamProfile({ teamId: 1, teamName: '映期团队' }).valid, true)
  assert.equal(exports.validateTeamProfile({ teamId: null, teamName: '' }).valid, false)
  assert.deepEqual(exports.createDefaultTeamProfileConfig(), {
    team: {},
    visibleFields: { avatar: true, teamName: true, intro: true }
  })
  assert.deepEqual(
    exports.buildTeamProfileConfig({ teamId: 3, teamName: '作品集团队', avatarUrl: 'avatar', intro: '作品集简介' }),
    {
      team: { teamId: 3, avatarUrl: 'avatar', teamName: '作品集团队', intro: '作品集简介' },
      visibleFields: { avatar: true, teamName: true, intro: true }
    }
  )
  const wxml = fs.readFileSync(path.join(ROOT, 'team-profile/team-profile.wxml'), 'utf8')
  assert.match(wxml, /bindinput="handleInput"/)
  assert.match(wxml, /bindtap="chooseAvatar"/)
  assert.match(wxml, /bindtap="refreshFromTeam"/)
  assert.match(wxml, /bindchange="handleVisibleFieldChange"/)
  assert.match(wxml, /draft\.visibleFields\.avatar/)
  assert.match(wxml, /draft\.visibleFields\.teamName/)
  assert.match(wxml, /draft\.visibleFields\.intro/)
})

test('team profile edits an isolated draft and emits avatar and refresh intents', () => {
  const { definition } = loadComponent('team-profile')
  const team = { teamId: 3, teamName: '初始团队', avatarUrl: 'initial.png', intro: '初始简介' }
  const harness = createComponentHarness(definition, { team, editMode: true, refreshing: false })

  harness.instance.handleInput({ currentTarget: { dataset: { field: 'teamName' } }, detail: { value: '作品集团队' } })
  harness.instance.handleInput({ currentTarget: { dataset: { field: 'intro' } }, detail: { value: '作品集简介' } })
  harness.instance.chooseAvatar()
  harness.instance.refreshFromTeam()
  harness.instance.applyAvatar({ detail: { avatarUrl: 'wxfile://tmp/team-profile.jpg' } })
  harness.instance.handleVisibleFieldChange({ currentTarget: { dataset: { field: 'intro' } }, detail: { value: false } })
  harness.instance.saveEdit()

  assert.deepEqual(team, { teamId: 3, teamName: '初始团队', avatarUrl: 'initial.png', intro: '初始简介' })
  assert.equal(harness.eventsByName('chooseavatar').length, 1)
  assert.equal(harness.eventsByName('refresh').length, 1)
  assert.deepEqual(harness.eventsByName('save')[0].detail.config, {
    team: {
      teamId: 3,
      avatarUrl: 'wxfile://tmp/team-profile.jpg',
      teamName: '作品集团队',
      intro: '作品集简介'
    },
    visibleFields: { avatar: true, teamName: true, intro: false }
  })
})

test('team profile keeps display switches when refreshing the team snapshot', () => {
  const { definition } = loadComponent('team-profile')
  const harness = createComponentHarness(definition, {
    team: { teamId: 3, teamName: '初始团队', avatarUrl: 'initial.png', intro: '初始简介' },
    visibleFields: { avatar: false, teamName: true, intro: false },
    editMode: true
  })

  harness.instance.applyTeamSnapshot({
    detail: { team: { teamId: 3, teamName: '最新团队', avatarUrl: 'latest.png', intro: '最新简介' } }
  })

  assert.deepEqual(harness.instance.data.draft, {
    team: { teamId: 3, avatarUrl: 'latest.png', teamName: '最新团队', intro: '最新简介' },
    visibleFields: { avatar: false, teamName: true, intro: false }
  })
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

test('carousel uses the personal-style progress indicator and only rotates multiple items', () => {
  const { definition } = loadComponent('carousel')
  const first = { memberUserId: 2, workId: 9, mediaUrl: 'first.jpg', coverUrl: 'first-cover.jpg' }
  const second = { memberUserId: 3, workId: 10, mediaUrl: 'second.jpg', coverUrl: 'second-cover.jpg' }

  const single = createComponentHarness(definition, { items: [first], editMode: false })
  assert.equal(single.instance.data.autoplay, false)
  assert.equal(single.instance.data.circular, false)
  assert.deepEqual(single.instance.data.progressSegments.map((item) => item.state), ['current'])
  assert.equal(single.instance.data.progressStyle, 'animation-duration: 5000ms;')

  const carousel = createComponentHarness(definition, { items: [first, second], editMode: false })
  assert.equal(carousel.instance.data.autoplay, true)
  assert.equal(carousel.instance.data.circular, true)
  assert.deepEqual(carousel.instance.data.progressSegments.map((item) => item.state), ['current', 'pending'])
  carousel.instance.handleChange({ detail: { current: 1 } })
  assert.equal(carousel.instance.data.current, 1)
  assert.deepEqual(carousel.instance.data.progressSegments.map((item) => item.state), ['done', 'current'])

  const wxml = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxss'), 'utf8')
  assert.match(wxml, /interval="\{\{safeInterval\}\}"/)
  assert.match(wxml, /mode="aspectFill"/)
  assert.match(wxml, /src="\{\{item\.mediaUrl \|\| item\.coverUrl\}\}"/)
  assert.match(wxml, /class="carousel-progress-bar"/)
  assert.doesNotMatch(wxml, /class="progress"/)
  assert.match(wxss, /\.carousel\.editor-mode\s*\{[^}]*height:\s*auto;[^}]*overflow:\s*visible;/)
  assert.match(wxss, /\.carousel\.editor-mode\s*\{[^}]*width:\s*100%;[^}]*margin-left:\s*0;/)
})

test('carousel spans preview and visitor pages without losing its rounded frame', () => {
  const wxss = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxss'), 'utf8')

  assert.match(wxss, /\.carousel\s*\{[^}]*width:\s*100%;[^}]*margin-left:\s*0;[^}]*height:\s*563rpx;/)
  assert.match(wxss, /\.carousel\s*\{[^}]*overflow:\s*hidden;[^}]*border-radius:\s*40rpx;/)
})

test('shared WXS selection helpers avoid unavailable String and preserve numeric ID matching', () => {
  const wxsSource = fs.readFileSync(path.join(ROOT, 'editor-selection.wxs'), 'utf8')
  const selectionModule = { exports: {} }

  assert.doesNotMatch(wxsSource, /\bString\s*\(/)
  new Function('module', wxsSource)(selectionModule)

  assert.equal(selectionModule.exports.order([{ workId: 9 }], 'workId', '9'), 1)
  assert.equal(selectionModule.exports.order([{ workId: 9 }], 'workId', 10), 0)
  assert.equal(selectionModule.exports.count([
    { memberUserId: 2, workId: 9 },
    { memberUserId: 2, workId: 10 },
    { memberUserId: 3, workId: 11 }
  ], 'memberUserId', '2'), 2)
  assert.equal(selectionModule.exports.count([], 'memberUserId', 2), 0)
})

test('carousel removal compacts draft order and visible WXS order', () => {
  const { exports } = loadComponent('carousel')
  const wxsSource = fs.readFileSync(path.join(ROOT, 'editor-selection.wxs'), 'utf8')
  const selectionModule = { exports: {} }
  new Function('module', wxsSource)(selectionModule)
  const original = [
    { memberUserId: 2, workId: 9 },
    { memberUserId: 2, workId: 10 },
    { memberUserId: 3, workId: 11 }
  ]

  const remaining = exports.toggleCarouselWork(original, original[1])

  assert.deepEqual(remaining.map((item) => item.workId), [9, 11])
  assert.equal(selectionModule.exports.order(remaining, 'workId', 9), 1)
  assert.equal(selectionModule.exports.order(remaining, 'workId', 11), 2)
})

test('carousel selection restarts at one after every work is removed', () => {
  const { exports } = loadComponent('carousel')
  const wxsSource = fs.readFileSync(path.join(ROOT, 'editor-selection.wxs'), 'utf8')
  const selectionModule = { exports: {} }
  new Function('module', wxsSource)(selectionModule)
  const first = { memberUserId: 2, workId: 9 }
  const cleared = exports.toggleCarouselWork([first], first)
  const reselected = exports.toggleCarouselWork(cleared, { memberUserId: 3, workId: 11 })

  assert.deepEqual(cleared, [])
  assert.deepEqual(reselected, [{ memberUserId: 3, workId: 11 }])
  assert.equal(selectionModule.exports.order(reselected, 'workId', 11), 1)
})

test('carousel member buttons hug their content and show every selected work count', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxss'), 'utf8')

  assert.match(wxml, /class="editor-member-name">\{\{item\.displayName\}\}<\/view>/)
  assert.match(wxml, /class="editor-member-count">\{\{selection\.count\(draftItems, 'memberUserId', item\.memberUserId\)\}\}<\/view>/)
  assert.match(wxss, /\.editor-member-choice\s*\{[^}]*display:\s*inline-flex;[^}]*width:\s*fit-content;/)
  assert.match(wxss, /\.editor-member-name\s*\{[^}]*max-width:\s*180rpx;[^}]*text-overflow:\s*ellipsis;/)
  assert.match(wxss, /\.editor-member-count\s*\{[^}]*flex:\s*none;/)
})

test('member-first editors give Skyline horizontal lists an explicit viewport height', () => {
  for (const name of ['single-work', 'member-portfolio-grid', 'member-portfolio-list']) {
    const wxss = fs.readFileSync(path.join(ROOT, name, `${name}.wxss`), 'utf8')
    const memberScrollRule = wxss.match(/\.editor-member-scroll\s*\{([^}]*)\}/)
    const memberRowRule = wxss.match(/\.editor-member-row\s*\{([^}]*)\}/)

    assert.ok(memberScrollRule, `${name} defines the member scroll viewport`)
    assert.match(memberScrollRule[1], /height:\s*56rpx;/, `${name} keeps the Skyline viewport visible`)
    assert.ok(memberRowRule, `${name} defines the member content row`)
    assert.match(memberRowRule[1], /height:\s*56rpx;/, `${name} keeps the member row measurable`)
  }
})

test('member-first editors size view capsules from each nickname', () => {
  for (const name of ['single-work', 'member-portfolio-grid', 'member-portfolio-list']) {
    const wxml = fs.readFileSync(path.join(ROOT, name, `${name}.wxml`), 'utf8')
    const wxss = fs.readFileSync(path.join(ROOT, name, `${name}.wxss`), 'utf8')
    const memberChoiceRule = wxss.match(/\.editor-member-choice\s*\{([^}]*)\}/)

    assert.match(wxml, /<view wx:for="\{\{members\}\}"[^>]*class="editor-member-choice/)
    assert.doesNotMatch(wxml, /<button wx:for="\{\{members\}\}"[^>]*class="editor-member-choice/)
    assert.ok(memberChoiceRule, `${name} defines member choice styles`)
    assert.match(memberChoiceRule[1], /display:\s*inline-flex;/, `${name} uses a stable inline flex box`)
    assert.match(memberChoiceRule[1], /width:\s*auto;/, `${name} follows the nickname width`)
    assert.match(memberChoiceRule[1], /flex:\s*0 0 auto;/, `${name} prevents capsules from growing equally`)
    assert.match(memberChoiceRule[1], /white-space:\s*nowrap;/, `${name} keeps the nickname on one line`)
    assert.doesNotMatch(memberChoiceRule[1], /width:\s*fit-content;/, `${name} avoids unsupported fit-content sizing`)
  }
})

test('carousel work picker matches the personal vertical work list visual language', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(ROOT, 'carousel/carousel.wxss'), 'utf8')

  assert.match(wxml, /class="editor-option-list"/)
  assert.match(wxml, /class="editor-option-thumb"/)
  assert.match(wxml, /class="editor-option-meta-row"/)
  assert.match(wxml, /wx:if="\{\{item\.aspectRatio\}\}" class="editor-option-ratio"/)
  assert.match(wxml, /<view wx:for="\{\{works\}\}" wx:key="workId" class="editor-option \{\{selection\.order\(draftItems, 'workId', item\.workId\) \? 'selected' : ''\}\}" data-item="\{\{item\}\}" catchtap="toggleWork"/)
  assert.match(wxml, /class="editor-option-check">\{\{selection\.order\(draftItems, 'workId', item\.workId\) \|\| ''\}\}<\/view>/)
  assert.doesNotMatch(wxml, /editor-option-grid/)

  assert.match(wxss, /\.editor-option-list\s*\{[^}]*width:\s*100%;[^}]*flex-direction:\s*column;[^}]*align-self:\s*stretch;/)
  assert.match(wxss, /\.editor-option\s*\{[^}]*width:\s*100%;[^}]*min-height:\s*112rpx;/)
  assert.match(wxss, /\.editor-option-thumb\s*\{[^}]*width:\s*92rpx;[^}]*height:\s*76rpx;/)
  assert.match(wxss, /\.editor-option\.selected\s*\{[^}]*border-color:\s*#212529;[^}]*background:\s*#fff;/)
  assert.match(wxss, /\.editor-option-check\s*\{[^}]*width:\s*44rpx;[^}]*height:\s*44rpx;/)
  assert.match(wxss, /\.editor-option\.selected \.editor-option-check\s*\{[^}]*background:\s*#212529;/)
  assert.doesNotMatch(wxss, /width:\s*calc\(33\.333%/)
})

test('divider owns color and positive height validation', () => {
  const { exports } = loadComponent('divider')
  assert.deepEqual(exports.createDefaultDividerConfig(), { color: 'GRAY', heightPx: 16 })
  assert.equal(exports.validateDividerConfig({ color: 'BLACK', heightPx: 1 }).valid, true)
  assert.equal(exports.validateDividerConfig({ color: 'RED', heightPx: 0 }).valid, false)

  const wxss = fs.readFileSync(path.join(ROOT, 'divider/divider.wxss'), 'utf8')
  assert.match(wxss, /\.divider-component\s*\{[^}]*width:\s*calc\(100% \+ 56rpx\);[^}]*margin-left:\s*-28rpx;[^}]*padding:\s*16rpx 0;/)
  assert.match(wxss, /\.divider-component\.editor-mode\s*\{[^}]*width:\s*100%;[^}]*margin-left:\s*0;[^}]*padding:\s*0;/)
})

test('grid independently loads a member before that member published portfolios', async () => {
  const { definition, exports } = loadComponent('member-portfolio-grid')
  assert.equal(propertyDefault(definition, 'showMemberName'), true)
  assert.deepEqual(exports.createDefaultGridConfig(), { items: [], showMemberName: true })
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
  assert.deepEqual(exports.buildGridConfig([{ memberUserId: 2, portfolioId: 8, title: '仅展示' }], false), {
    items: selected,
    showMemberName: false
  })
})

test('list independently loads a member before published portfolios and keeps list metadata', async () => {
  const { definition, exports } = loadComponent('member-portfolio-list')
  assert.equal(propertyDefault(definition, 'showMemberName'), true)
  assert.deepEqual(exports.createDefaultListConfig(), { items: [], showMemberName: true })
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
  assert.deepEqual(exports.buildListConfig([{ memberUserId: 2, portfolioId: 8, coverUrl: '仅展示' }], false), {
    items: selected,
    showMemberName: false
  })
  const wxml = fs.readFileSync(path.join(ROOT, 'member-portfolio-list/member-portfolio-list.wxml'), 'utf8')
  assert.match(wxml, /description|memberDisplayName/)
})

test('member portfolio editors preserve selected items while toggling member names', () => {
  for (const name of ['member-portfolio-grid', 'member-portfolio-list']) {
    const { definition } = loadComponent(name)
    const harness = createComponentHarness(definition, {
      editMode: true,
      items: [{ memberUserId: 2, portfolioId: 8, title: '婚礼纪实' }],
      showMemberName: false,
      portfolioId: 9
    })

    assert.equal(harness.instance.data.draftShowMemberName, false)
    harness.instance.handleShowMemberNameChange({ detail: { value: true } })
    assert.equal(harness.instance.data.draftShowMemberName, true)
    assert.equal(harness.instance.data.draftItems.length, 1)

    harness.instance.saveEdit()
    const saved = harness.eventsByName('save')[0].detail.config
    assert.equal(saved.showMemberName, true)
    assert.deepEqual(saved.items, [{ memberUserId: 2, portfolioId: 8 }])
  }
})

test('text section trims required body, limits 200 chars and owns safe typography', () => {
  const { definition, exports } = loadComponent('text-section')
  assert.deepEqual(exports.createDefaultTextSectionConfig(), {
    content: '',
    alignment: 'LEFT',
    fontFamily: 'SYSTEM',
    fontSizeRpx: 32,
    fontClass: 'font-system',
    fontSizeStyle: 'font-size: 32rpx;'
  })
  assert.equal(exports.validateTextSectionConfig({ content: ' 正文 ', alignment: 'CENTER' }).valid, true)
  assert.equal(exports.validateTextSectionConfig({ content: 'x'.repeat(201), alignment: 'LEFT' }).valid, false)
  assert.equal(exports.validateTextSectionConfig({ content: '😀'.repeat(200), alignment: 'LEFT' }).valid, true)
  assert.equal(exports.validateTextSectionConfig({ content: '😀'.repeat(201), alignment: 'LEFT' }).valid, false)
  assert.equal(exports.countTextCodePoints('😀a'), 2)
  assert.equal(exports.validateTextSectionConfig({ content: '正文', alignment: 'JUSTIFY' }).valid, false)

  assert.deepEqual(definition.data, {
    normalizedConfig: exports.createDefaultTextSectionConfig()
  })

  const harness = createComponentHarness(definition)
  harness.setProperties({
    config: {
      content: '  团队说明\n',
      alignment: 'CENTER',
      fontFamily: 'WECHAT_SANS_SS',
      fontSizeRpx: 36
    }
  })
  assert.equal(harness.instance.data.normalizedConfig.content, '  团队说明\n')
  assert.equal(harness.instance.data.normalizedConfig.fontClass, 'font-wechat-sans-ss')
  assert.equal(harness.instance.data.normalizedConfig.fontSizeStyle, 'font-size: 36rpx;')

  harness.setProperties({
    config: {
      content: '更新后的团队说明',
      alignment: 'RIGHT',
      fontFamily: 'WECHAT_SANS_STD',
      fontSizeRpx: 20
    }
  })
  assert.equal(harness.instance.data.normalizedConfig.content, '更新后的团队说明')
  assert.equal(harness.instance.data.normalizedConfig.alignment, 'RIGHT')
  assert.equal(harness.instance.data.normalizedConfig.fontClass, 'font-system')
  assert.equal(harness.instance.data.normalizedConfig.fontSizeStyle, 'font-size: 20rpx;')

  const wxml = fs.readFileSync(
    path.join(ROOT, 'text-section/text-section.wxml'),
    'utf8'
  )
  const wxss = fs.readFileSync(
    path.join(ROOT, 'text-section/text-section.wxss'),
    'utf8'
  )
  assert.match(
    wxml,
    /class="content \{\{normalizedConfig\.fontClass\}\}"[^>]*style="\{\{normalizedConfig\.fontSizeStyle\}\}"/
  )
  assert.match(
    wxss,
    /^@import "\.\.\/\.\.\/\.\.\/\.\.\/styles\/portfolio-text-typography\.wxss";/m
  )
})

test('schedule calendar builds six stable weeks and applies query range bounds', () => {
  const { exports } = loadComponent('schedule-query')
  const days = exports.buildCalendarDays('2026-08', {
    startDate: '2026-08-03',
    endDate: '2026-08-31'
  })

  assert.equal(days.length, 42)
  assert.equal(days[0].date, '2026-07-26')
  assert.equal(days[41].date, '2026-09-05')
  assert.equal(new Set(days.map((item) => item.key)).size, 42)
  assert.equal(days.find((item) => item.date === '2026-08-02').disabled, true)
  assert.equal(days.find((item) => item.date === '2026-08-03').disabled, false)
  assert.match(days.find((item) => item.date === '2026-07-31').dayClass, /muted/)
  assert.match(days.find((item) => item.date === '2026-08-02').dayClass, /disabled/)
  assert.equal(exports.formatYearMonthTitle('2026-08'), '2026 年 8 月')
  assert.equal(exports.shiftMonth('2026-01', -1), '2025-12')
  assert.equal(exports.shiftMonth('2026-12', 1), '2027-01')
  assert.equal(
    exports.resolveInitialMonth({ startDate: '2026-10-01', endDate: '2026-10-31' }, '2026-08-01'),
    '2026-10'
  )
  assert.equal(
    exports.resolveInitialMonth({ startDate: '2026-06-01', endDate: '2026-06-30' }, '2026-08-01'),
    '2026-06'
  )
})

test('schedule calendar changes months and selects only enabled dates', () => {
  const { definition } = loadComponent('schedule-query')
  const harness = createComponentHarness(definition, {
    config: {
      displayMode: 'INLINE_CALENDAR',
      queryRange: { type: 'DATE_RANGE', startDate: '2026-08-03', endDate: '2026-09-10' }
    },
    editMode: false
  })

  assert.equal(harness.instance.data.selectedMonth, '2026-08')
  assert.equal(harness.instance.data.selectedMonthText, '2026 年 8 月')
  assert.equal(harness.instance.data.calendarDays.length, 42)

  harness.instance.setData({ selectedDate: '2026-08-04', pendingIdempotencyKey: 'retry-key', result: { status: 'TEAM_AVAILABLE' } })
  harness.instance.handleDayTap({ currentTarget: { dataset: { date: '2026-08-02', disabled: true } } })
  assert.equal(harness.instance.data.selectedDate, '2026-08-04')
  assert.equal(harness.instance.data.pendingIdempotencyKey, 'retry-key')

  harness.instance.handleDayTap({ currentTarget: { dataset: { date: '2026-08-03', disabled: false } } })
  assert.equal(harness.instance.data.selectedDate, '2026-08-03')
  assert.equal(harness.instance.data.pendingIdempotencyKey, '')
  assert.equal(harness.instance.data.result, null)

  harness.instance.setData({ pendingIdempotencyKey: 'august-key', result: { status: 'TEAM_AVAILABLE' } })
  harness.instance.handleNextMonth()
  assert.equal(harness.instance.data.selectedMonth, '2026-09')
  assert.equal(harness.instance.data.selectedMonthText, '2026 年 9 月')
  assert.equal(harness.instance.data.calendarDays.find((item) => item.date === '2026-09-11').disabled, true)
  assert.equal(harness.instance.data.selectedDate, '')
  assert.equal(harness.instance.data.pendingIdempotencyKey, '')
  assert.equal(harness.instance.data.result, null)

  harness.instance.handlePrevMonth()
  assert.equal(harness.instance.data.selectedMonth, '2026-08')
  harness.instance.handleMonthPickerChange({ detail: { value: 'invalid' } })
  assert.equal(harness.instance.data.selectedMonth, '2026-08')
  harness.instance.handleMonthPickerChange({ detail: { value: '2026-09' } })
  assert.equal(harness.instance.data.selectedMonth, '2026-09')
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
  const { definition, exports } = loadComponent('contact-form')
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
  assert.match(wxml, /<picker mode="date"[^>]*value="\{\{localForm\.desiredSchedule\}\}"[^>]*data-field="desiredSchedule"[^>]*bindchange="handleDateChange"/)
  assert.match(wxml, /请选择档期（选填）/)
  assert.doesNotMatch(wxml, /submitText/)

  const harness = createComponentHarness(definition, {
    form: { contactName: '客户', phone: '13800138000', wechat: '', desiredSchedule: '', needs: '' },
    config: exports.createDefaultContactConfig({ displayMode: 'INLINE_FORM' })
  })
  harness.instance.handleDateChange({
    currentTarget: { dataset: { field: 'desiredSchedule' } },
    detail: { value: '2026-08-01' }
  })
  assert.equal(harness.instance.data.localForm.desiredSchedule, '2026-08-01')
  assert.deepEqual(harness.eventsByName('contactinput')[0].detail, {
    field: 'desiredSchedule',
    value: '2026-08-01',
    form: { contactName: '客户', phone: '13800138000', wechat: '', desiredSchedule: '2026-08-01', needs: '' }
  })
})

test('contact form treats a null form binding as an empty form', () => {
  const { definition, exports } = loadComponent('contact-form')
  const emptyForm = exports.createDefaultContactForm()
  const harness = createComponentHarness(definition, { form: null })

  assert.deepEqual(exports.createDefaultContactForm(null), emptyForm)
  assert.deepEqual(harness.instance.data.localForm, emptyForm)

  harness.setProperties({ form: null })
  assert.deepEqual(harness.instance.data.localForm, emptyForm)
})

test('QR is CUSTOM-only and distinguishes maintainer preview from visitor interaction', () => {
  const { definition, exports } = loadComponent('qr-contact')
  assert.deepEqual(exports.createDefaultQrContactConfig(), { qrUrlSource: 'CUSTOM', qrUrl: '' })
  assert.deepEqual(exports.createDefaultQrContactConfig({ title: '移除', description: '移除', qrUrlSource: 'CUSTOM', qrUrl: 'wxfile://tmp/qr.png' }), {
    qrUrlSource: 'CUSTOM', qrUrl: 'wxfile://tmp/qr.png'
  })
  assert.equal(exports.validateQrContactConfig({ qrUrlSource: 'CUSTOM', qrUrl: 'https://cdn/qr.png' }).valid, true)
  assert.equal(exports.validateQrContactConfig({ qrUrlSource: 'CUSTOM', qrUrl: 'wxfile://tmp/qr.png' }).valid, true)
  assert.equal(exports.validateQrContactConfig({ qrUrlSource: 'CUSTOM', qrUrl: '' }).valid, false)
  assert.equal(exports.validateQrContactConfig({ qrUrlSource: 'PROFILE', qrUrl: 'https://cdn/qr.png' }).valid, false)
  const wxml = fs.readFileSync(path.join(ROOT, 'qr-contact/qr-contact.wxml'), 'utf8')
  const css = fs.readFileSync(path.join(ROOT, 'qr-contact/qr-contact.wxss'), 'utf8')
  assert.doesNotMatch(wxml, /draft\.title|draft\.description|config\.title|config\.description/)
  assert.doesNotMatch(wxml, /二维码标题|二维码说明/)
  assert.match(css, /\.qr-image\s*\{[^}]*width:\s*280rpx[^}]*height:\s*280rpx/)
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
