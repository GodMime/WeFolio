const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')

const WORKS = [
  { id: 107, mediaType: 'VIDEO', title: '风景', coverUrl: 'cover.jpg', mediaUrl: 'first.mp4', description: '视频一' },
  { id: 110, mediaType: 'VIDEO', title: '蓝天', coverUrl: '', mediaUrl: 'sky.mp4' },
  { id: 111, mediaType: 'VIDEO', title: '海浪', coverUrl: 'waves.mp4', mediaUrl: 'waves.mp4' }
]
const CONFIG = { workIds: [107, 110, 111], title: '视频作品', displayStyle: 'STACKED', showTitle: true, showDescription: false, showComponentTitle: true, showSwipeHint: true }

function load(properties = {}) {
  let definition
  const timers = new Map()
  let sequence = 0
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../components/mock/video-carousel/video-carousel.js'), 'utf8'), {
    Component(value) { definition = value },
    wx: { getWindowInfo: () => ({ windowWidth: 375 }) },
    setTimeout(callback) { const id = ++sequence; timers.set(id, callback); return id },
    clearTimeout(id) { timers.delete(id) }
  })
  const events = []
  const instance = { data: { ...definition.data }, setData(value) { Object.assign(this.data, value) }, triggerEvent(name, detail) { events.push({ name, detail }) } }
  for (const [key, property] of Object.entries(definition.properties)) instance.data[key] = property.value
  Object.assign(instance.data, { works: WORKS, config: CONFIG, componentKey: 'videos' }, properties)
  for (const [key, method] of Object.entries(definition.methods)) instance[key] = method.bind(instance)
  definition.lifetimes.attached.call(instance)
  return { instance, definition, timers, events, flush() { for (const [id, callback] of timers) { timers.delete(id); callback() } } }
}

const touch = (x, y = 100) => ({ touches: [{ clientX: x, clientY: y }] })
const indexEvent = index => ({ currentTarget: { dataset: { index } } })

test('video carousel preserves configured order and supplies safe missing-cover placeholders', () => {
  const { instance } = load({ config: { ...CONFIG, workIds: [111, 107, 110] } })
  assert.deepEqual(Array.from(instance.data.items, item => item.workId), [111, 107, 110])
  assert.equal(instance.data.items[0].coverUrl, '')
  assert.equal(instance.data.items[1].coverUrl, 'cover.jpg')
  assert.equal(instance.data.items[2].coverUrl, '')
  assert.equal(instance.data.cards.filter(card => card.active).length, 1)
})

test('dragging stacked cards snaps and suppresses accidental play, next tap plays selected work', () => {
  const { instance, events, timers, flush } = load()
  instance.handleTouchStart(touch(300))
  instance.handleTouchMove(touch(100))
  assert.notEqual(instance.data.cards[0].transformStyle, '')
  instance.handleTouchEnd()
  assert.ok(timers.size > 0)
  instance.handleCardTap(indexEvent(1))
  assert.equal(events.length, 0)
  flush()
  assert.equal(instance.data.currentIndex, 1)
  instance.handleTouchStart(touch(100))
  instance.handleTouchEnd()
  instance.handleCardTap(indexEvent(1))
  assert.equal(events.length, 1)
  assert.equal(events[0].name, 'worktap')
  assert.equal(events[0].detail.workId, 110)
  assert.equal(events[0].detail.componentKey, 'videos')
})

test('vertical scroll gesture does not change the selected video', () => {
  const { instance, flush } = load()
  instance.handleTouchStart(touch(200, 100))
  instance.handleTouchMove(touch(190, 350))
  instance.handleTouchEnd()
  flush()
  assert.equal(instance.data.currentIndex, 0)
})

test('tapping a stacked neighbor selects it before emitting play', () => {
  const { instance, events, flush } = load()
  instance.handleCardTap(indexEvent(1))
  assert.equal(events.length, 0)
  flush()
  instance.handleCardTap(indexEvent(1))
  assert.equal(events[0].detail.workId, 110)
})

test('portrait swiper reserves neighbor space and follows changed index', () => {
  const { instance, events } = load({ config: { ...CONFIG, displayStyle: 'PORTRAIT_CARDS', showTitle: false, showDescription: true, showComponentTitle: false, showSwipeHint: false } })
  assert.equal(instance.data.displayStyle, 'PORTRAIT_CARDS')
  assert.ok(instance.data.portraitNextMargin > 0)
  assert.ok(instance.data.portraitHeight > 0)
  assert.equal(instance.data.showTitle, false)
  assert.equal(instance.data.showDescription, true)
  assert.equal(instance.data.showComponentTitle, false)
  assert.equal(instance.data.showSwipeHint, false)
  instance.handlePortraitChange({ detail: { current: 2 } })
  instance.handlePortraitTap(indexEvent(2))
  assert.equal(events[0].detail.workId, 111)
})

test('cover failure keeps video playable and explicit retry restores the original image', () => {
  const { instance, events } = load()
  const event = { currentTarget: { dataset: { workId: 107 } } }
  instance.handleCoverError(event)
  assert.equal(instance.data.items[0].coverUrl, '')
  assert.equal(instance.data.items[0].coverFailed, true)
  instance.handleCardTap(indexEvent(0))
  assert.equal(events[0].detail.workId, 107)
  instance.handleRetryCover(event)
  assert.equal(instance.data.items[0].coverUrl, 'cover.jpg')
  assert.equal(instance.data.items[0].coverFailed, false)
})

test('style and works updates cancel animation, hide and detach leave no pending timers', () => {
  const { instance, definition, timers, events } = load()
  function drag() { instance.handleTouchStart(touch(300)); instance.handleTouchMove(touch(100)); instance.handleTouchEnd() }
  drag()
  assert.ok(timers.size > 0)
  instance.data.config = { ...CONFIG, displayStyle: 'PORTRAIT_CARDS' }
  definition.observers['config, works'].call(instance)
  assert.equal(timers.size, 0)
  instance.data.config = CONFIG
  definition.observers['config, works'].call(instance)
  drag()
  definition.pageLifetimes.hide.call(instance)
  assert.equal(timers.size, 0)
  definition.pageLifetimes.show.call(instance)
  drag()
  definition.lifetimes.detached.call(instance)
  assert.equal(timers.size, 0)
  instance.handleCardTap(indexEvent(0))
  assert.equal(events.length, 0)
})

test('empty and invalid video lists are inert and never use an audio record', () => {
  const { instance, events } = load({ works: [{ id: 108, mediaType: 'AUDIO', mediaUrl: 'audio.mp3' }], config: { ...CONFIG, workIds: [108] } })
  assert.equal(instance.data.items.length, 0)
  instance.handleCardTap(indexEvent(0))
  instance.handlePortraitChange({ detail: { current: -1 } })
  assert.equal(instance.data.currentIndex, 0)
  assert.equal(events.length, 0)
})
