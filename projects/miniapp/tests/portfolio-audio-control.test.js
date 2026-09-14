const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

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
