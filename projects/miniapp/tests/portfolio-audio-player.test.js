const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function playerModule() {
  try { return require('../pages/portfolios/utils/portfolio-audio-player') } catch (error) {
    if (error.code !== 'MODULE_NOT_FOUND') throw error
    return {}
  }
}

function fixture() {
  const instances = [], states = [], order = []
  const wxApi = { createInnerAudioContext() {
    const events = {}
    const context = { events, plays: 0, pauses: 0, destroyed: false,
      play() { this.plays++; order.push('audio') }, pause() { this.pauses++ }, destroy() { this.destroyed = true } }
    for (const event of ['Play', 'Pause', 'Stop', 'Ended', 'Error']) context[`on${event}`] = (fn) => { events[event] = fn }
    instances.push(context)
    return context
  } }
  const { createPortfolioAudio } = playerModule()
  assert.equal(typeof createPortfolioAudio, 'function', '需要页面级播放控制')
  const player = createPortfolioAudio({ wxApi, onPlaying: (state) => states.push(state), beforePlay: () => order.push('pause-videos') })
  return { player, instances, states, order }
}
const source = { workId: 19, mediaUrl: 'https://cdn/music.mp3', enabled: true }

test('初次自动尝试一次，状态跟随真实事件，失败或刷新不重试', () => {
  const f = fixture()
  f.player.setResource(source, true)
  assert.equal(f.instances[0].plays, 1)
  assert.notEqual(f.states.at(-1), true)
  f.instances[0].events.Play()
  assert.equal(f.states.at(-1), true)
  f.instances[0].events.Error()
  assert.equal(f.states.at(-1), false)
  f.player.setResource(source, true)
  assert.equal(f.instances[0].plays, 1)
  f.player.toggle()
  assert.deepEqual(f.order.slice(-2), ['pause-videos', 'audio'])
  assert.equal(f.instances[0].plays, 2)
})

test('编辑器仅手动试听且不受配置开关限制，切换作品销毁旧实例', () => {
  const f = fixture()
  f.player.setResource({ ...source, enabled: false })
  assert.equal(f.instances.length, 0)
  f.player.toggle()
  const old = f.instances[0]
  f.player.setResource({ ...source, workId: 20, mediaUrl: 'https://cdn/other.mp3' })
  assert.equal(old.destroyed, true)
  f.player.toggle()
  old.events.Play()
  assert.notEqual(f.states.at(-1), true)
  assert.equal(f.instances.length, 2)
})

test('隐藏或视频抢占后忽略迟到 play；返回不恢复，退出后不创建新实例', () => {
  const f = fixture()
  f.player.setResource(source, true)
  f.player.hide()
  const audio = f.instances[0]
  audio.events.Play()
  assert.notEqual(f.states.at(-1), true)
  f.player.show()
  f.player.setResource(source, true)
  assert.equal(audio.plays, 1)
  f.player.toggle()
  f.player.pause()
  audio.events.Play()
  assert.notEqual(f.states.at(-1), true)
  f.player.destroy()
  f.player.setResource(source, true)
  f.player.toggle()
  assert.equal(audio.destroyed, true)
  assert.equal(f.instances.length, 1)
})

test('隐藏前数据尚未返回也不能迟到自动播放', () => {
  const f = fixture()
  f.player.hide()
  f.player.setResource(source, true)
  f.player.show()
  assert.equal(f.instances.length, 0)
  f.player.toggle()
  assert.equal(f.instances.length, 1)
})

test('关闭或无可用地址不创建实例；系统暂停与自然结束均不自动恢复', () => {
  for (const resource of [{ ...source, enabled: false }, { ...source, mediaUrl: '' }, null]) {
    const f = fixture()
    f.player.setResource(resource, true)
    assert.equal(f.instances.length, 0)
  }
  for (const event of ['Pause', 'Stop', 'Ended']) {
    const f = fixture()
    f.player.setResource(source, true)
    f.instances[0].events.Play()
    f.instances[0].events[event]()
    f.player.show()
    f.player.setResource(source, true)
    assert.equal(f.states.at(-1), false)
    assert.equal(f.instances[0].plays, 1)
  }
})

test('个人和团队播放器副本一致，不跨分包依赖', () => {
  const personal = path.join(__dirname, '../pages/portfolios/utils/portfolio-audio-player.js')
  assert.ok(fs.existsSync(personal))
  assert.equal(fs.readFileSync(personal, 'utf8'), fs.readFileSync(path.join(__dirname, '../pages/team-portfolios/utils/portfolio-audio-player.js'), 'utf8'))
})
