const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOTS = ['portfolios', 'team-portfolios']
const VIDEO_URL = 'https://example.test/background.mp4'
const POSTER_URL = 'https://example.test/background.jpg'

function player(root, t) {
  const { textBackgroundVideo } = require(`../pages/${root}/utils/portfolio-text-background-video`)
  const previousWx = global.wx
  const calls = []
  global.wx = {
    createVideoContext(id, scope) {
      assert.equal(id, 'textBackgroundVideo')
      assert.equal(scope, instance)
      return { play() { calls.push('play') }, pause() { calls.push('pause') } }
    }
  }
  t.after(() => { if (previousWx === undefined) delete global.wx; else global.wx = previousWx })
  const observers = []
  const instance = {
    properties: { backgroundVideoPaused: false },
    data: { ...textBackgroundVideo.data, background: { enabled: true, videoUrl: VIDEO_URL, posterUrl: POSTER_URL } },
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    createIntersectionObserver() {
      const observer = {
        disconnected: false,
        relativeToViewport() { return this },
        observe(selector, callback) { assert.equal(selector, '.text-background-video'); this.callback = callback },
        disconnect() { this.disconnected = true }
      }
      observers.push(observer)
      return observer
    }
  }
  for (const [name, method] of Object.entries(textBackgroundVideo.methods)) instance[name] = method.bind(instance)
  return {
    instance, calls, observers,
    ready() { textBackgroundVideo.lifetimes.ready.call(instance) },
    hide() { textBackgroundVideo.pageLifetimes.hide.call(instance) },
    show() { textBackgroundVideo.pageLifetimes.show.call(instance) },
    detach() { textBackgroundVideo.lifetimes.detached.call(instance) },
    pauseFromHost(paused) {
      instance.properties.backgroundVideoPaused = paused
      textBackgroundVideo.observers.backgroundVideoPaused.call(instance, paused)
    },
    intersect(ratio, observer = observers.at(-1)) { observer.callback({ intersectionRatio: ratio }) }
  }
}

for (const root of ROOTS) {
  test(`${root} 主动视频弹层暂停背景，关闭后重新检测可见区域`, t => {
    const view = player(root, t)
    view.ready()
    view.intersect(1)
    const observer = view.observers.at(-1)
    view.pauseFromHost(true)
    assert.equal(view.calls.at(-1), 'pause')
    view.intersect(1, observer)
    assert.equal(view.calls.at(-1), 'pause')
    view.pauseFromHost(false)
    assert.equal(view.instance.data.backgroundVideoVisible, false)
    view.intersect(1)
    assert.equal(view.calls.at(-1), 'play')
  })

  test(`${root} 无控件视频通过播放进度收起加载封面，旧视频事件不影响新封面`, t => {
    const view = player(root, t)
    view.ready()
    view.intersect(1)
    view.instance.handleBackgroundVideoPlay()
    assert.equal(view.instance.data.backgroundVideoStarted, false)
    const update = (url, currentTime) => view.instance.handleBackgroundVideoTimeUpdate({ currentTarget: { dataset: { src: url } }, detail: { currentTime } })
    update(VIDEO_URL, 0)
    assert.equal(view.instance.data.backgroundVideoStarted, false)
    update(VIDEO_URL, 0.2)
    assert.equal(view.instance.data.backgroundVideoStarted, true)
    view.instance.data.background = { enabled: true, videoUrl: 'https://example.test/next.mp4' }
    view.instance.syncBackgroundVideo()
    assert.equal(view.instance.data.backgroundVideoStarted, false)
    update(VIDEO_URL, 1)
    assert.equal(view.instance.data.backgroundVideoStarted, false)
  })

  test(`${root} 视频背景仅在可见时播放，离屏暂停后再次进入恢复`, t => {
    const view = player(root, t)
    view.ready()
    assert.equal(view.instance.data.backgroundVideoVisible, false)
    assert.equal(view.calls.includes('play'), false)
    view.intersect(1)
    assert.equal(view.instance.data.backgroundVideoVisible, true)
    assert.equal(view.calls.at(-1), 'play')
    view.intersect(0)
    assert.equal(view.instance.data.backgroundVideoVisible, false)
    assert.equal(view.calls.at(-1), 'pause')
    view.intersect(0.5)
    assert.equal(view.calls.at(-1), 'play')
  })

  test(`${root} 页面隐藏和销毁会暂停，过期可见回调不能重启播放`, t => {
    const view = player(root, t)
    view.ready()
    view.intersect(1)
    const firstObserver = view.observers[0]
    view.hide()
    assert.equal(view.calls.at(-1), 'pause')
    view.intersect(1, firstObserver)
    assert.equal(view.calls.at(-1), 'pause')
    view.show()
    assert.equal(firstObserver.disconnected, true)
    view.intersect(1)
    assert.equal(view.calls.at(-1), 'play')
    const lastObserver = view.observers.at(-1)
    view.detach()
    assert.equal(lastObserver.disconnected, true)
    assert.equal(view.calls.at(-1), 'pause')
    view.intersect(1, lastObserver)
    assert.equal(view.calls.at(-1), 'pause')
  })

  test(`${root} 换视频及关闭背景清理旧播放状态，旧错误不污染新背景`, t => {
    const view = player(root, t)
    view.ready()
    view.intersect(1)
    const firstObserver = view.observers[0]
    view.instance.data.background = { enabled: true, videoUrl: 'https://example.test/next.mp4' }
    view.instance.syncBackgroundVideo()
    assert.equal(firstObserver.disconnected, true)
    assert.equal(view.instance.data.backgroundVideoVisible, false)
    view.intersect(1, firstObserver)
    assert.equal(view.instance.data.backgroundVideoVisible, false)
    view.instance.handleBackgroundVideoError({ currentTarget: { dataset: { src: VIDEO_URL } } })
    assert.equal(view.instance.data.backgroundVideoFailed, false)
    view.intersect(1)
    view.instance.data.background = { enabled: false, videoUrl: '' }
    view.instance.syncBackgroundVideo()
    assert.equal(view.calls.at(-1), 'pause')
    assert.equal(view.instance.data.backgroundVideoVisible, false)
  })

  test(`${root} 播放失败保留封面且停止尝试，换资源后允许重新播放`, t => {
    const view = player(root, t)
    view.ready()
    view.intersect(1)
    view.instance.handleBackgroundVideoError({ currentTarget: { dataset: { src: VIDEO_URL } } })
    assert.equal(view.instance.data.backgroundVideoFailed, true)
    assert.equal(view.instance.data.background.posterUrl, POSTER_URL)
    assert.equal(view.calls.at(-1), 'pause')
    view.instance.syncBackgroundVideo()
    assert.equal(view.instance.data.backgroundVideoFailed, true)
    view.instance.data.background = { enabled: true, videoUrl: 'https://example.test/next.mp4' }
    view.instance.syncBackgroundVideo()
    assert.equal(view.instance.data.backgroundVideoFailed, false)
    view.intersect(1)
    assert.equal(view.calls.at(-1), 'play')
  })

  for (const name of ['text-section', 'structured-text-section']) {
    test(`${root}/${name} 视频层静音循环且不接管交互，元数据沿用背景尺寸计算`, () => {
      const componentPath = path.join(__dirname, `../pages/${root}/components/${name}/${name}`)
      const template = fs.readFileSync(`${componentPath}.wxml`, 'utf8')
      const styles = fs.readFileSync(`${componentPath}.wxss`, 'utf8')
      const video = template.match(/<video\b[^>]*>/)?.[0]
      assert.ok(video, '需要实际的视频节点')
      assert.match(video, /src="\{\{background.videoUrl\}\}"/)
      assert.match(video, /poster="\{\{background.posterUrl\}\}"/)
      assert.match(video, /autoplay="\{\{backgroundVideoVisible\}\}"/)
      for (const property of ['loop', 'muted']) assert.match(video, new RegExp(`${property}="\\{\\{true\\}\\}"`))
      for (const property of ['controls', 'show-center-play-btn', 'show-play-btn', 'show-fullscreen-btn', 'enable-progress-gesture', 'enable-play-gesture']) {
        assert.match(video, new RegExp(`${property}="\\{\\{false\\}\\}"`))
      }
      assert.match(video, /bindloadedmetadata="handleBackgroundLoad"/)
      assert.match(video, /binderror="handleBackgroundVideoError"/)
      assert.match(video, /bindtimeupdate="handleBackgroundVideoTimeUpdate"/)
      assert.match(video, /object-fit="cover"/)
      assert.match(template, /<image\b[^>]*backgroundVideoFailed[^>]*src="\{\{background.posterUrl\}\}"/)
      assert.match(template, /!backgroundVideoStarted \|\| backgroundVideoFailed/)
      assert.match(styles, /\.text-background-video[^{}]*\{[^}]*pointer-events:\s*none/)
      const previousComponent = global.Component
      let definition
      global.Component = value => { definition = value }
      try {
        delete require.cache[require.resolve(componentPath)]
        require(componentPath)
      } finally {
        if (previousComponent === undefined) delete global.Component
        else global.Component = previousComponent
      }
      assert.equal(typeof definition.lifetimes.ready, 'function')
      assert.equal(typeof definition.lifetimes.detached, 'function')
      assert.equal(typeof definition.pageLifetimes.hide, 'function')
      assert.equal(typeof definition.pageLifetimes.show, 'function')
    })
  }
}

test('个人和团队视频背景播放生命周期保持一致', () => {
  assert.equal(
    fs.readFileSync(path.join(__dirname, '../pages/portfolios/utils/portfolio-text-background-video.js'), 'utf8'),
    fs.readFileSync(path.join(__dirname, '../pages/team-portfolios/utils/portfolio-text-background-video.js'), 'utf8')
  )
})

test('预览和访客页在作品视频弹层打开时暂停两种文字背景', () => {
  for (const relative of ['portfolios/standard-preview/portfolio-standard-preview', 'portfolios/visitor-portfolio/visitor-portfolio',
    'team-portfolios/standard-preview/team-portfolio-standard-preview', 'team-portfolios/visitor-portfolio/team-visitor-portfolio']) {
    const template = fs.readFileSync(path.join(__dirname, `../pages/${relative}.wxml`), 'utf8')
    const components = template.match(/<(?:portfolio|team)-(?:structured-)?text-section\b[^>]*>/g)
    assert.equal(components.length, 2)
    for (const component of components) assert.match(component, /background-video-paused="\{\{videoPreviewVisible \|\| fontOpening \|\| loading\}\}"/)
  }
})
