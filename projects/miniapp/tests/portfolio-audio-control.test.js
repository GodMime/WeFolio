const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function audioControl(folder, properties = {}) {
  const entry = path.join(__dirname, `../pages/${folder}/components/background-audio-control/background-audio-control.js`)
  const previous = global.Component
  let definition
  global.Component = value => { definition = value }
  try { delete require.cache[entry]; require(entry) }
  finally { if (previous === undefined) delete global.Component; else global.Component = previous }
  const defaults = Object.fromEntries(Object.entries(definition.properties).map(([key, value]) => [key, value.value]))
  const events = []
  const instance = {
    ...definition.methods,
    data: { ...defaults, ...definition.data, ...properties },
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name) { events.push(name) }
  }
  return { definition, instance, events }
}

function withWindowInfo(callback, windowInfo = {}, legacy = false) {
  const previous = global.wx
  const info = { windowWidth: 375, windowHeight: 812, screenTop: 0, safeArea: { bottom: 778 }, ...windowInfo }
  global.wx = legacy ? { getSystemInfoSync: () => info } : { getWindowInfo: () => info }
  try { callback() }
  finally { if (previous === undefined) delete global.wx; else global.wx = previous }
}

function touch(x, y, ended = false) {
  const point = { identifier: 1, clientX: x, clientY: y, pageX: x, pageY: y }
  return { touches: ended ? [] : [point], changedTouches: [point] }
}

for (const folder of ['portfolios', 'team-portfolios']) {
  test(`${folder} 内联视频将原生播放事件交给页面，两个展示页均绑定暂停`, () => {
    const root = path.join(__dirname, `../pages/${folder}`)
    const entry = path.join(root, 'components/single-work/single-work.js')
    const previous = global.Component
    let definition
    global.Component = (value) => { definition = value }
    try { delete require.cache[entry]; require(entry) }
    finally { if (previous === undefined) delete global.Component; else global.Component = previous }
    const events = []
    definition.methods.handleVideoPlay.call({ data: { componentKey: 'video-1' }, triggerEvent: (...args) => events.push(args) })
    assert.deepEqual(events, [['videoplay', { componentKey: 'video-1' }]])
    assert.match(fs.readFileSync(path.join(root, 'components/single-work/single-work.wxml'), 'utf8'), /bindplay="handleVideoPlay"/)
    const names = folder === 'portfolios'
      ? ['standard-preview/portfolio-standard-preview', 'visitor-portfolio/visitor-portfolio']
      : ['standard-preview/team-portfolio-standard-preview', 'visitor-portfolio/team-visitor-portfolio']
    for (const name of names) {
      const wxml = fs.readFileSync(path.join(root, `${name}.wxml`), 'utf8')
      const script = fs.readFileSync(path.join(root, `${name}.js`), 'utf8')
      assert.match(wxml, /bindvideoplay="pauseBackgroundAudio"/)
      assert.match(script, /openVideoPreview\(work = \{\}\)[\s\S]*?this\.pauseBackgroundAudio\(\)[\s\S]*?return openVideoPlayer\(/)
      assert.match(wxml, /backgroundAudioResource.enabled && backgroundAudioResource.mediaUrl/)
    }
  })
  test(`${folder} 控件只发播放意图，样例不播放；封面失败只回退一次`, () => {
    const entry = path.join(__dirname, `../pages/${folder}/components/background-audio-control/background-audio-control.js`)
    assert.ok(fs.existsSync(entry), '需要三款展示控件')
    let definition
    global.Component = (value) => { definition = value }
    delete require.cache[entry]
    require(entry)
    delete global.Component
    const events = []
    const instance = { data: { ...definition.data, sample: false }, setData(patch) { Object.assign(this.data, patch) }, triggerEvent(name) { events.push(name) }, ...definition.methods }
    definition.properties.coverUrl.observer.call(instance, 'https://cdn/custom.jpg')
    instance.handleTap()
    assert.deepEqual(events, ['toggle'])
    instance.data.sample = true
    instance.handleTap()
    assert.deepEqual(events, ['toggle'])
    instance.handleCoverError()
    assert.equal(instance.data.coverSource, 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png')
    instance.handleCoverError()
    assert.equal(instance.data.coverFailed, true)
    definition.properties.coverUrl.observer.call(instance, 'https://cdn/new.jpg')
    assert.equal(instance.data.coverFailed, false)
    assert.equal(instance.data.coverSource, 'https://cdn/new.jpg')
  })

  test(`${folder} 访客与预览启用拖动，编辑器样例保留原有展示`, () => {
    const root = path.join(__dirname, `../pages/${folder}`)
    const prefix = folder === 'portfolios' ? 'portfolio' : 'team-portfolio'
    const visitor = folder === 'portfolios' ? 'visitor-portfolio' : 'team-visitor-portfolio'
    for (const name of [`standard-preview/${prefix}-standard-preview`, `visitor-portfolio/${visitor}`]) {
      const wxml = fs.readFileSync(path.join(root, `${name}.wxml`), 'utf8')
      const control = wxml.match(/<background-audio-control\b[^>]*>/)[0]
      assert.match(control, /draggable="\{\{true\}\}"/)
      assert.match(control, /anchor-top="\{\{backgroundAudioTop\}\}"/)
      assert.match(control, /bindtoggle="handleToggleBackgroundAudio"/)
    }
    const editor = fs.readFileSync(path.join(root, `standard-edit/${prefix}-standard-edit.wxml`), 'utf8')
    const sample = editor.match(/<background-audio-control\b[^>]*>/)[0]
    assert.match(sample, /sample="\{\{true\}\}"/)
    assert.doesNotMatch(sample, /draggable="\{\{true\}\}"/)
    const controlWxml = fs.readFileSync(path.join(root, 'components/background-audio-control/background-audio-control.wxml'), 'utf8')
    assert.match(controlWxml, /catchtouchmove="[^"]*handleTouchMove/)
    assert.match(controlWxml, /(?:bind|catch)touchcancel="[^"]*handleTouchCancel/)
  })

  test(`${folder} 自由拖动后始终靠右并保留高度，连续拖动不回到初始高度`, () => withWindowInfo(() => {
    const { instance, events } = audioControl(folder, { draggable: true, anchorTop: 76 })
    instance.handleTouchStart(touch(340, 100))
    instance.handleTouchMove(touch(240, 300))
    assert.equal(instance.data.offsetX, -100)
    assert.equal(instance.data.offsetY, 200)
    assert.equal(instance.data.dragging, true)
    instance.handleTouchEnd(touch(240, 300, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 200)
    assert.equal(instance.data.dragging, false)
    instance.handleTap()
    assert.deepEqual(events, [], '拖动结束产生的 tap 不能切换播放')

    instance.handleTouchStart(touch(340, 300))
    instance.handleTouchMove(touch(260, 400))
    instance.handleTouchEnd(touch(260, 400, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 300)
    instance.handleTouchStart(touch(340, 400))
    instance.handleTouchEnd(touch(340, 400, true))
    instance.handleTap()
    assert.deepEqual(events, ['toggle'], '下一次正常点击仍能切换播放')
  }))

  test(`${folder} 点击轻微抖动不移动控件，关闭拖动与编辑器样例都不能被拖走`, () => withWindowInfo(() => {
    const { instance, events } = audioControl(folder, { draggable: true, anchorTop: 76 })
    instance.handleTouchStart(touch(340, 100))
    instance.handleTouchMove(touch(342, 103))
    instance.handleTouchEnd(touch(342, 103, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 0)
    instance.handleTap()
    assert.deepEqual(events, ['toggle'])
    for (const properties of [{}, { draggable: true, sample: true }]) {
      const fixture = audioControl(folder, properties)
      fixture.instance.handleTouchStart(touch(340, 100))
      fixture.instance.handleTouchMove(touch(150, 400))
      fixture.instance.handleTouchEnd(touch(150, 400, true))
      assert.equal(fixture.instance.data.offsetX, 0)
      assert.equal(fixture.instance.data.offsetY, 0)
      assert.equal(fixture.instance.data.dragging, false)
    }
  }))

  test(`${folder} 松手最后坐标参与定位，取消拖动也靠右且不误播放`, () => withWindowInfo(() => {
    const { instance, events } = audioControl(folder, { draggable: true, anchorTop: 76 })
    instance.handleTouchStart(touch(340, 100))
    instance.handleTouchMove(touch(200, 200))
    instance.handleTouchEnd(touch(180, 260, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 160)
    instance.handleTouchStart(touch(340, 260))
    instance.handleTouchMove(touch(220, 360))
    instance.handleTouchCancel(touch(220, 360, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 260)
    assert.equal(instance.data.dragging, false)
    instance.handleTap()
    assert.deepEqual(events, [])
  }))

  test(`${folder} 拖动回原点与第二根手指打断都不会被当成播放点击`, () => withWindowInfo(() => {
    const { instance, events } = audioControl(folder, { draggable: true, anchorTop: 76 })
    instance.handleTouchStart(touch(340, 100))
    instance.handleTouchMove(touch(200, 200))
    instance.handleTouchEnd(touch(340, 100, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 0)
    instance.handleTap()
    assert.deepEqual(events, [])

    instance.handleTouchStart(touch(340, 100))
    instance.handleTouchMove(touch(200, 250))
    const secondFinger = touch(200, 250)
    secondFinger.touches.push({ identifier: 2, clientX: 260, clientY: 260 })
    instance.handleTouchStart(secondFinger)
    instance.handleTouchMove(secondFinger)
    instance.handleTouchEnd(touch(200, 250, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 150)
    assert.equal(instance.data.dragging, false)
    instance.handleTap()
    assert.deepEqual(events, [])
  }))

  test(`${folder} 三种控件拖动都限制在可见区域内并避让顶部导航和底部安全区`, () => withWindowInfo(() => {
    for (const [displayStyle, width, height] of [['DISC', 46, 46], ['SLEEVE', 68, 48], ['MINI_PLAYER', 180, 48]]) {
      const { instance } = audioControl(folder, { draggable: true, anchorTop: 76, displayStyle })
      instance.handleTouchStart(touch(340, 100))
      instance.handleTouchMove(touch(-1000, 2000))
      const controlLeft = 375 - 12 - width + instance.data.offsetX
      const controlBottom = 76 + instance.data.offsetY + height
      assert.ok(controlLeft >= 0, `${displayStyle} 左侧不能移出屏幕`)
      assert.equal(controlBottom, 778 - 12, `${displayStyle} 底部保留安全间距`)
      instance.handleTouchEnd(touch(-1000, 2000, true))
      assert.equal(instance.data.offsetX, 0)
      instance.handleTouchStart(touch(340, 750))
      instance.handleTouchMove(touch(2000, -1000))
      assert.equal(instance.data.offsetY, 0)
      assert.ok(instance.data.offsetX <= 12, `${displayStyle} 右侧不能移出屏幕`)
      instance.handleTouchEnd(touch(2000, -1000, true))
      assert.equal(instance.data.offsetX, 0)
    }
  }))

  test(`${folder} 旧版窗口 API 与屏幕坐标安全区也能正确限制底部`, () => withWindowInfo(() => {
    const { instance } = audioControl(folder, { draggable: true, anchorTop: 76 })
    instance.handleTouchStart(touch(340, 100))
    instance.handleTouchEnd(touch(240, 2000, true))
    assert.equal(instance.data.offsetX, 0)
    assert.equal(76 + instance.data.offsetY + 46, 778 - 44 - 12)
  }, { screenTop: 44 }, true))
}

test('三款控件分包副本一致，触摸区域至少 44px', () => {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    const suffix = `components/background-audio-control/background-audio-control.${extension}`
    const personal = fs.readFileSync(path.join(__dirname, `../pages/portfolios/${suffix}`), 'utf8')
    assert.equal(personal, fs.readFileSync(path.join(__dirname, `../pages/team-portfolios/${suffix}`), 'utf8'))
    if (extension === 'wxss') {
      assert.match(personal, /min-width: 44px/)
      assert.match(personal, /min-height: 44px/)
      assert.match(personal, /animation-play-state: paused/)
    }
  }
})
