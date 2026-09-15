const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const PERSONAL_ROOT = path.join(MINIAPP_ROOT, 'pages/portfolios')
const TEAM_ROOT = path.join(MINIAPP_ROOT, 'pages/team-portfolios')

function loadUtility(root) {
  const modulePath = path.join(root, 'utils/video-carousel.js')
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

function loadDisplayComponentWithMathOverride(root, buildCardStates) {
  const utilityPath = require.resolve(path.join(root, 'utils/video-carousel.js'))
  const componentPath = require.resolve(path.join(root, 'components/video-carousel/video-carousel.js'))
  const actualUtility = loadUtility(root)
  const originalUtilityModule = require.cache[utilityPath]
  const originalComponentModule = require.cache[componentPath]
  const originalComponent = global.Component
  let definition

  require.cache[utilityPath] = {
    id: utilityPath,
    filename: utilityPath,
    loaded: true,
    exports: Object.assign({}, actualUtility, { buildCardStates })
  }
  global.Component = (value) => { definition = value }
  delete require.cache[componentPath]
  try {
    require(componentPath)
  } finally {
    global.Component = originalComponent
    if (originalUtilityModule) require.cache[utilityPath] = originalUtilityModule
    else delete require.cache[utilityPath]
    if (originalComponentModule) require.cache[componentPath] = originalComponentModule
    else delete require.cache[componentPath]
  }
  return definition
}

function createDisplayComponent(definition, works) {
  const events = []
  const component = {
    data: Object.assign({}, definition.data, {
      componentKey: 'vc-runtime',
      works
    }),
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { events.push({ name, detail }) }
  }
  Object.assign(component, definition.methods)
  definition.lifetimes.attached.call(component)
  return { component, events }
}

test('personal and team carousel math use the approved physical dimensions', () => {
  for (const utility of [loadUtility(PERSONAL_ROOT), loadUtility(TEAM_ROOT)]) {
    assert.equal(utility.CARD_WIDTH_RPX, 576)
    assert.equal(utility.CARD_SPACING_RPX, 472)
    assert.equal(utility.STAGE_HEIGHT_RPX, 464)
    assert.equal(utility.CLICK_THRESHOLD_PX, 8)
    assert.equal(utility.resolveWindowWidthPx({ getWindowInfo: () => ({ windowWidth: 390 }) }), 390)
    assert.equal(utility.resolveWindowWidthPx({ getSystemInfoSync: () => ({ windowWidth: 375 }) }), 375)
    assert.equal(utility.cardSpacingPx(375), 236)
    assert.equal(utility.cardSpacingPx(390), 245.44)
    assert.equal(utility.formatDuration(65000), '01:05')
    assert.equal(utility.formatDuration(-1), '00:00')
  }
})

test('continuous drag snaps to the nearest card and can cross several cards', () => {
  const utility = loadUtility(PERSONAL_ROOT)
  assert.equal(utility.dragOffset(0, -590, 236), 2.5)
  assert.equal(utility.snapOffset(2.49), 2)
  assert.equal(utility.snapOffset(2.5), 3)
  assert.equal(utility.dragOffset(4, 472, 236), 2)
  assert.equal(utility.isClickGesture(7.99), true)
  assert.equal(utility.isClickGesture(8), false)
})

test('side cards expose their rounded outer edges without changing the drag distance', () => {
  const works = Array.from({ length: 3 }, (_, index) => ({ workId: index + 1 }))

  for (const utility of [loadUtility(PERSONAL_ROOT), loadUtility(TEAM_ROOT)]) {
    const states = utility.buildCardStates(works, 0)
    const rightCard = states[1]
    const leftCard = states[2]
    const leftOuterEdge = 375 + leftCard.translateX - 576 * leftCard.scale / 2
    const rightOuterEdge = 375 + rightCard.translateX + 576 * rightCard.scale / 2

    assert.equal(Number(leftOuterEdge.toFixed(2)), 24)
    assert.equal(Number(rightOuterEdge.toFixed(2)), 726)
    assert.equal(utility.cardSpacingPx(375), 236)
  }
})

test('loop indices wrap first and last while one or two cards degrade without looping', () => {
  const utility = loadUtility(PERSONAL_ROOT)
  assert.equal(utility.relativeLoopIndex(4, 0, 5), -1)
  assert.equal(utility.relativeLoopIndex(0, 4, 5), 1)
  assert.equal(utility.relativeLoopIndex(2, 0, 5), 2)
  assert.equal(utility.relativeLoopIndex(3, 0, 5), -2)

  const works = Array.from({ length: 5 }, (_, index) => ({ workId: index + 1 }))
  const looping = utility.buildCardStates(works, 0)
  assert.equal(looping[0].isCenter, true)
  assert.equal(looping[1].relativeIndex, 1)
  assert.equal(looping[4].relativeIndex, -1)
  assert.equal(looping[3].hidden, false)

  const pair = utility.buildCardStates(works.slice(0, 2), 0)
  assert.deepEqual(pair.map((item) => item.relativeIndex), [0, 1])
  assert.deepEqual(
    utility.buildCardStates(works.slice(0, 2), 1).map((item) => item.relativeIndex),
    [-1, 0]
  )
  assert.deepEqual(
    utility.buildCardStates(works.slice(0, 1), 0).map((item) => item.relativeIndex),
    [0]
  )
})

test('personal and team package-local math modules stay behaviorally identical', () => {
  const personal = loadUtility(PERSONAL_ROOT)
  const team = loadUtility(TEAM_ROOT)
  assert.deepEqual(Object.keys(personal).sort(), Object.keys(team).sort())
  const works = Array.from({ length: 8 }, (_, index) => ({ workId: index + 1, title: `作品${index + 1}` }))
  for (const offset of [-2.4, 0, 1.25, 7.8]) {
    assert.deepEqual(team.buildCardStates(works, offset), personal.buildCardStates(works, offset))
    assert.equal(team.snapOffset(offset), personal.snapOffset(offset))
  }
})

test('both display components consume their package-local carousel math module', () => {
  for (const root of [PERSONAL_ROOT, TEAM_ROOT]) {
    const works = [{ workId: 17, title: '测试视频' }]
    const marker = [{ marker: path.basename(root) }]
    const calls = []
    const definition = loadDisplayComponentWithMathOverride(root, (actualWorks, offset) => {
      calls.push({ works: actualWorks, offset })
      return marker
    })
    const component = {
      data: { works },
      setData(patch) { Object.assign(this.data, patch) }
    }

    Object.assign(component, definition.methods)
  definition.lifetimes.attached.call(component)

    assert.deepEqual(component.data.cardStates, marker)
    assert.deepEqual(calls, [{ works, offset: 0 }])
  }
})

test('both display components center a side card and play only the centered card', () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  global.setTimeout = () => 'animation-timer'
  global.clearTimeout = () => {}
  try {
    for (const root of [PERSONAL_ROOT, TEAM_ROOT]) {
      const definition = loadDisplayComponentWithMathOverride(root, loadUtility(root).buildCardStates)
      const works = [
        { workId: 1, title: '视频一' },
        { workId: 2, title: '视频二' },
        { workId: 3, title: '视频三' }
      ]
      const side = createDisplayComponent(definition, works)

      definition.methods.handleCardTap.call(side.component, {
        currentTarget: { dataset: { index: 1 } }
      })

      assert.equal(side.events.length, 0)
      assert.ok(side.component.data.offset > 0)

      const center = createDisplayComponent(definition, works)
      definition.methods.handleCardTap.call(center.component, {
        currentTarget: { dataset: { index: 0 } }
      })

      assert.deepEqual(center.events, [{
        name: 'play',
        detail: {
          componentKey: 'vc-runtime',
          work: works[0]
        }
      }])
    }
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
})

test('both display components use a stage-wide drag surface, approved copy and no empty renderer', () => {
  for (const root of [PERSONAL_ROOT, TEAM_ROOT]) {
    const componentRoot = path.join(root, 'components/video-carousel')
    const js = fs.readFileSync(path.join(componentRoot, 'video-carousel.js'), 'utf8')
    const json = JSON.parse(fs.readFileSync(path.join(componentRoot, 'video-carousel.json'), 'utf8'))
    const wxml = fs.readFileSync(path.join(componentRoot, 'video-carousel.wxml'), 'utf8')
    const wxss = fs.readFileSync(path.join(componentRoot, 'video-carousel.wxss'), 'utf8')

    assert.equal(json.component, true)
    assert.equal(json.styleIsolation, 'isolated')
    assert.match(js, /Component\s*\(\s*\{/)
    for (const property of ['componentKey', 'title', 'works', 'showComponentTitle', 'showTitle', 'showSwipeHint', 'themeMode']) {
      assert.match(js, new RegExp(`${property}:\\s*\\{`))
    }
    assert.match(js, /triggerEvent\('play'/)
    assert.match(js, /detached\(\)/)
    assert.doesNotMatch(js, /pages\/(team-)?portfolios\/components\/video-carousel/)
    assert.doesNotMatch(wxml, /<video\b/)
    assert.match(wxml, /class="video-carousel-stage"[\s\S]*bindtouchstart="handleTouchStart"/)
    assert.match(wxml, /class="video-carousel-stage"[\s\S]*bindtouchmove="handleTouchMove"/)
    assert.match(wxml, /class="video-carousel-stage"[\s\S]*bindtouchend="handleTouchEnd"/)
    assert.match(wxml, /class="video-carousel-stage"[\s\S]*bindtouchcancel="handleTouchCancel"/)
    assert.match(wxml, /class="video-carousel-card[\s\S]*bindtap="handleCardTap"/)
    assert.match(wxml, /wx:if="\{\{showComponentTitle && title\}\}"/)
    assert.match(wxml, /wx:if="\{\{showTitle\}\}"/)
    assert.match(wxml, /wx:if="\{\{showSwipeHint && works.length > 1\}\}"/)
    assert.match(wxml, /video-carousel-placeholder/)
    assert.match(wxml, /video-carousel-play/)
    assert.match(wxml, />左右滑动，点击观看视频<\/view>/)
    assert.doesNotMatch(wxml, /video-carousel-empty|暂无视频作品/)
    assert.match(wxss, /width:\s*576rpx/)
    assert.match(wxss, /height:\s*464rpx/)
    assert.match(wxss, /height:\s*360rpx/)
    assert.match(wxss, /\.video-carousel-stage\s*\{[^}]*width:\s*calc\(100%\s*-\s*48rpx\);[^}]*margin:\s*0\s+24rpx;/)
    assert.match(wxss, /\.theme-dark \.video-carousel-hint,\s*\.theme-dark \.video-carousel-empty\s*\{\s*color:\s*#c1c7ce;\s*\}/)
  }
})

test('portrait style follows current selection, measured track and individual description', () => {
  for (const root of [PERSONAL_ROOT, TEAM_ROOT]) {
    const definition = loadDisplayComponentWithMathOverride(root, loadUtility(root).buildCardStates)
    const works = [{ workId: 1, description: '首件' }, { workId: 2, description: '第二件' }, { workId: 3 }]
    const { component, events } = createDisplayComponent(definition, works)
    component.createSelectorQuery = () => ({ select() { return this }, boundingClientRect(callback) { callback({ width: 300 }); return this }, exec() {} })
    component.measurePortraitTrack()
    assert.equal(component.data.portraitNextMargin, 60)
    assert.equal(component.data.portraitHeight, (240 - 6) * 1.25)
    component.handlePortraitChange({ detail: { current: 1 } })
    assert.equal(component.data.currentIndex, 1)
    component.data.displayStyle = 'STACKED'
    definition.properties.displayStyle.observer.call(component)
    assert.equal(component.data.currentIndex, 1)
    definition.properties.works.observer.call(component, works.slice())
    assert.equal(component.data.currentIndex, 1)
    component.handlePortraitPlay({ currentTarget: { dataset: { index: 1 } } })
    assert.equal(events[0].detail.work.description, '第二件')
  }
})

test('portrait exposed next card selects it before playback and fallback follows 48rpx gutters', () => {
  for (const root of [PERSONAL_ROOT, TEAM_ROOT]) {
    const definition = loadDisplayComponentWithMathOverride(root, loadUtility(root).buildCardStates)
    const { component, events } = createDisplayComponent(definition, [{ workId: 1 }, { workId: 2 }])
    assert.equal(component.data.portraitNextMargin, (375 - 24) * 0.2)
    component.handlePortraitPlay({ currentTarget: { dataset: { index: 1 } } })
    assert.equal(component.data.currentIndex, 1)
    assert.equal(events.length, 0)
    component.handlePortraitPlay({ currentTarget: { dataset: { index: 1 } } })
    assert.equal(events.length, 1)
    assert.equal(events[0].detail.work.workId, 2)
    component.handlePortraitPlay({ currentTarget: { dataset: { index: 9 } } })
    assert.equal(events.length, 1)
    const wxml = fs.readFileSync(path.join(root, 'components/video-carousel/video-carousel.wxml'), 'utf8')
    const wxss = fs.readFileSync(path.join(root, 'components/video-carousel/video-carousel.wxss'), 'utf8')
    assert.match(wxml, /<swiper\s+wx:if="\{\{displayStyle === 'PORTRAIT_CARDS'\}\}"/)
    assert.match(wxml, /next-margin="\{\{portraitNextMargin\}\}px"/)
    assert.match(wxml, /bindchange="handlePortraitChange"/)
    assert.match(wxml, /bindtap="handlePortraitPlay"/)
    assert.match(wxml, /showDescription && item.description/)
    assert.match(wxss, /\.video-carousel-portrait-track\s*\{[^}]*width:calc\(100% - 48rpx\)/)
    assert.doesNotMatch(wxss, /\.video-carousel-work-title\s*\{/)
  }
})


test('personal and team component title settings default on and survive saving independently of work titles', async () => {
  const personal = require('../utils/portfolios')
  const team = require('../pages/team-portfolios/utils/team-portfolios')
  for (const value of [undefined, null, true, false, 'false']) {
    const source = { title: '保留的组件标题', showComponentTitle: value, showTitle: false }
    const expected = value !== false
    const personalConfig = personal.normalizeVideoCarouselConfig(Object.assign({}, source, { workIds: [1, 2, 3] }))
    const teamConfig = team.normalizeTeamVideoCarouselConfig(Object.assign({}, source, {
      items: [1, 2, 3].map(workId => ({ memberUserId: 7, workId }))
    }))
    const component = personal.createComponent('VIDEO_CAROUSEL', { componentKey: 'title-toggle', config: personalConfig })
    const updated = personal.updateVideoCarouselConfig({ components: [component] }, 'title-toggle', personalConfig)
    const savedPersonal = JSON.parse(JSON.stringify(personal.buildDraftPayload(updated, 1, 'title-toggle'))).config.components[0].config
    const normalizedTeam = team.normalizeTeamPortfolioConfig({ components: [
      { componentKey: 'title-toggle', componentType: 'VIDEO_CAROUSEL', enabled: true, config: teamConfig }
    ] })
    let savedTeam
    await team.saveTeamPortfolioDraft(async options => {
      savedTeam = JSON.parse(JSON.stringify(options.data)).config.components[0].config
    }, 8, normalizedTeam, 1, 'title-toggle')
    for (const saved of [savedPersonal, savedTeam]) {
      assert.equal(saved.showComponentTitle, expected)
      assert.equal(saved.showTitle, false)
      assert.equal(saved.title, source.title)
    }
    assert.equal(savedPersonal.showComponentTitle, savedTeam.showComponentTitle)
  }
  for (const normalize of [personal.normalizeVideoCarouselConfig, team.normalizeTeamVideoCarouselConfig]) {
    assert.equal(normalize({ title: '', showComponentTitle: false }).title, '视频作品')
  }
})

test('preview and visitor title rendering defaults on and preserves explicit off in primary and secondary menus', () => {
  const { normalizeVisitorPortfolio } = require('../utils/visitor-portfolio')
  const { normalizeTeamVisitorPortfolio } = require('../pages/team-portfolios/utils/team-visitor-portfolio')
  for (const showComponentTitle of [undefined, null, true, false]) {
    const expected = showComponentTitle !== false
    const data = { title: '原组件标题', showComponentTitle, showTitle: false, works: [{ workId: 1, mediaType: 'VIDEO' }] }
    const personalComponent = Object.assign({ componentKey: 'video', componentType: 'VIDEO_CAROUSEL' }, data)
    const teamComponent = { componentKey: 'video', componentType: 'VIDEO_CAROUSEL', data }
    for (const preview of [true, false]) {
      const personal = normalizeVisitorPortfolio({ renderData: { preview, components: [personalComponent],
        bottomNav: { enabled: true, items: [{ key: 'home', title: '首页' }, { key: 'video', title: '视频', components: [personalComponent] }] }
      } })
      const team = normalizeTeamVisitorPortfolio({ renderData: { preview, components: [teamComponent],
        bottomNav: { enabled: true, items: [{ key: 'home', title: '首页' }, { key: 'video', title: '视频', components: [teamComponent] }] }
      } })
      for (const rendered of [personal.components[0], personal.bottomNav.items[1].components[0], team.components[0].data, team.bottomNav.items[1].components[0].data]) {
        assert.equal(rendered.showComponentTitle, expected)
        assert.equal(rendered.showTitle, false)
        assert.equal(rendered.title, data.title)
      }
    }
    const legacy = normalizeVisitorPortfolio({ config: {
      components: [{ componentKey: 'video', componentType: 'VIDEO_CAROUSEL', config: data }]
    } })
    assert.equal(legacy.components[0].showComponentTitle, expected)
  }
  for (const [topLevel, fallback, expected] of [[undefined, false, false], [null, false, false], [false, true, false], [true, false, true]]) {
    const rendered = normalizeVisitorPortfolio({ renderData: { components: [{
      componentKey: 'video', componentType: 'VIDEO_CAROUSEL', works: [{ workId: 1 }],
      showComponentTitle: topLevel, config: { showComponentTitle: fallback }
    }] } }).components[0]
    assert.equal(rendered.showComponentTitle, expected)
  }
})

test('both package components default the component title on and remove its node when disabled', () => {
  for (const root of [PERSONAL_ROOT, TEAM_ROOT]) {
    const definition = loadDisplayComponentWithMathOverride(root, loadUtility(root).buildCardStates)
    assert.equal(definition.properties.showComponentTitle.type, Boolean)
    assert.equal(definition.properties.showComponentTitle.value, true)
    assert.equal(definition.properties.showTitle.value, true)
    const wxml = fs.readFileSync(path.join(root, 'components/video-carousel/video-carousel.wxml'), 'utf8')
    assert.match(wxml, /<view wx:if="\{\{showComponentTitle && title\}\}" class="video-carousel-title">\{\{title\}\}<\/view>/)
    assert.doesNotMatch(wxml, /hidden="[^"\n]*showComponentTitle|visibility:[^"\n]*showComponentTitle/)
  }
  for (const extension of ['js', 'wxml', 'wxss']) {
    assert.equal(
      fs.readFileSync(path.join(PERSONAL_ROOT, `components/video-carousel/video-carousel.${extension}`), 'utf8'),
      fs.readFileSync(path.join(TEAM_ROOT, `components/video-carousel/video-carousel.${extension}`), 'utf8')
    )
  }
})
