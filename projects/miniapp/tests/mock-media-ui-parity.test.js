const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '..')
const DEFAULT_AUDIO_COVER = 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png'

function loadComponent(relativePath) {
  const entry = path.join(ROOT, relativePath)
  const previous = global.Component
  let definition
  global.Component = value => { definition = value }
  try {
    delete require.cache[require.resolve(entry)]
    require(entry)
  } finally {
    if (previous === undefined) delete global.Component
    else global.Component = previous
  }
  return definition
}

function createInstance(definition, properties = {}) {
  const defaults = Object.fromEntries(Object.entries(definition.properties).map(([key, value]) => [key, value.value]))
  const events = []
  const instance = {
    data: { ...defaults, ...definition.data, ...properties },
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { events.push({ name, detail }) }
  }
  Object.assign(instance, definition.methods)
  return { instance, events }
}

function touch(x, y) {
  return { touches: [{ identifier: 1, clientX: x, clientY: y }], changedTouches: [{ identifier: 1, clientX: x, clientY: y }] }
}

test('mock 背景音频样例与正式控件一样不可播放、不可拖动，并回退默认封面', () => {
  const previousWx = global.wx
  global.wx = { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812, safeArea: { bottom: 778 } }) }
  try {
    const definition = loadComponent('components/mock/background-audio-control/background-audio-control.js')
    const { instance, events } = createInstance(definition, {
      sample: true,
      floating: false,
      draggable: true,
      coverUrl: 'https://cdn.example/custom.jpg'
    })
    definition.lifetimes.attached.call(instance)

    assert.equal(instance.data.title, '背景音频')
    assert.equal(instance.data.coverSource, 'https://cdn.example/custom.jpg')
    instance.handleToggle()
    instance.handleTouchStart(touch(300, 100))
    instance.handleTouchMove(touch(100, 400))
    instance.handleTouchEnd(touch(100, 400))
    assert.deepEqual(events, [])
    assert.equal(instance.data.offsetX, 0)
    assert.equal(instance.data.offsetY, 0)

    instance.handleCoverError()
    assert.equal(instance.data.coverSource, DEFAULT_AUDIO_COVER)
    assert.equal(instance.data.coverFailed, false)
    instance.handleCoverError()
    assert.equal(instance.data.coverFailed, true)
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('mock 背景音频三种控件采用正式尺寸，迷你播放器使用正式暂停文案', () => {
  const previousWx = global.wx
  global.wx = { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812, safeArea: { bottom: 778 } }) }
  try {
    const definition = loadComponent('components/mock/background-audio-control/background-audio-control.js')
    for (const [displayStyle, width, height] of [
      ['DISC', 46, 46],
      ['SLEEVE', 68, 48],
      ['MINI_PLAYER', 180, 48]
    ]) {
      const { instance } = createInstance(definition, { displayStyle })
      definition.lifetimes.attached.call(instance)
      assert.equal(instance.data.controlWidth, width)
      assert.equal(instance.data.controlHeight, height)
    }
    const wxml = fs.readFileSync(path.join(ROOT, 'components/mock/background-audio-control/background-audio-control.wxml'), 'utf8')
    assert.match(wxml, /title \|\| '背景音频'/)
    assert.match(wxml, /playing \? '正在播放' : '背景音频'/)
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('mock 背景音频样例不吞掉父级样式卡的点击和滚动事件', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'components/mock/background-audio-control/background-audio-control.wxml'), 'utf8')
  assert.match(wxml, /bindtap="handleToggle"/)
  assert.match(wxml, /bindtouchstart="handleTouchStart"/)
  assert.match(wxml, /catchtouchmove="\{\{floating && draggable && !sample \? 'handleTouchMove' : ''\}\}"/)
  assert.match(wxml, /bindtouchend="handleTouchEnd"/)
  assert.match(wxml, /bindtouchcancel="handleTouchCancel"/)
  assert.doesNotMatch(wxml, /catchtap="handleToggle"/)
  assert.doesNotMatch(wxml, /catchtouchstart="handleTouchStart"/)
  assert.doesNotMatch(wxml, /catchtouchend="handleTouchEnd"/)
  assert.doesNotMatch(wxml, /catchtouchcancel="handleTouchCancel"/)
})

test('mock 视频轮播初始叠卡状态与正式个人组件一致', () => {
  const previousWx = global.wx
  global.wx = { getWindowInfo: () => ({ windowWidth: 375 }) }
  try {
    const works = [
      { workId: 107, mediaType: 'VIDEO', title: '风景', coverUrl: 'first.jpg' },
      { workId: 110, mediaType: 'VIDEO', title: '蓝天', coverUrl: 'sky.jpg' },
      { workId: 111, mediaType: 'VIDEO', title: '海浪', coverUrl: 'waves.jpg' }
    ]
    const formalDefinition = loadComponent('pages/portfolios/components/video-carousel/video-carousel.js')
    const formal = createInstance(formalDefinition, { works, componentKey: 'formal' }).instance
    formalDefinition.lifetimes.attached.call(formal)

    const mockDefinition = loadComponent('components/mock/video-carousel/video-carousel.js')
    const mock = createInstance(mockDefinition, {
      works,
      componentKey: 'mock',
      config: { workIds: [107, 110, 111], displayStyle: 'STACKED' }
    }).instance
    mockDefinition.lifetimes.attached.call(mock)

    const formalCards = formal.data.cardStates.map(card => ({
      workId: card.work.workId,
      translateX: card.translateX,
      scale: card.scale,
      opacity: card.opacity,
      brightness: card.brightness,
      isCenter: card.isCenter,
      hidden: card.hidden
    }))
    const mockCards = mock.data.cards.map(card => ({
      workId: card.work.workId,
      translateX: card.translateX,
      scale: card.scale,
      opacity: card.opacity,
      brightness: card.brightness,
      isCenter: card.isCenter,
      hidden: card.hidden
    }))
    assert.deepEqual(mockCards, formalCards)
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('mock 视频轮播沿用正式标题入口与圆点显示条件', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'components/mock/video-carousel/video-carousel.wxml'), 'utf8')
  assert.match(wxml, /video-carousel-caption-rule/)
  assert.match(wxml, /观看视频/)
  assert.match(wxml, /切换视频/)
  assert.match(wxml, /displayStyle === 'PORTRAIT_CARDS' && items\.length > 1/)
  assert.doesNotMatch(wxml, /wx:if="\{\{items\.length > 1\}\}" class="[^"]*(?:dots|indicators)/)
})

test('mock 单作品动图加载失败后回退缩略图并在切换作品时复位', () => {
  const definition = loadComponent('components/mock/portfolio-renderer/portfolio-renderer.js')
  const { instance } = createInstance(definition, {
    component: {
      componentKey: 'single-animation',
      componentType: 'SINGLE_WORK',
      work: {
        isAnimation: true,
        previewUrl: 'animation.gif',
        thumbnailUrl: 'animation-cover.jpg'
      }
    }
  })

  instance.handleSingleWorkAnimationError()
  assert.equal(instance.data.singleWorkAnimationFailed, true)
  definition.observers.component.call(instance)
  assert.equal(instance.data.singleWorkAnimationFailed, false)

  const wxml = fs.readFileSync(path.join(ROOT, 'components/mock/portfolio-renderer/portfolio-renderer.wxml'), 'utf8')
  assert.match(wxml, /component\.work\.isAnimation && !singleWorkAnimationFailed/)
  assert.match(wxml, /binderror="handleSingleWorkAnimationError"/)
  assert.match(wxml, /wx:elif="\{\{component\.work\.isAnimation\}\}"[\s\S]*src="\{\{component\.work\.thumbnailUrl\}\}"/)
})
