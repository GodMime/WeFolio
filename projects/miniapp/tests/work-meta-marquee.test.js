const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const componentPath = path.join(__dirname, '../pages/works/components/work-meta-marquee/work-meta-marquee.js')

function createMarquee() {
  assert.ok(fs.existsSync(componentPath), '作品信息需要独立的自动滚动组件')
  let definition
  vm.runInNewContext(fs.readFileSync(componentPath, 'utf8'), {
    Component(value) { definition = value }
  })
  const measurements = []
  const component = {
    data: { ...definition.data, text: '视频 · 01:24 / 很长的作品标签 / 长宽比 16:9', compact: false },
    setData(patch, callback) {
      Object.assign(this.data, patch)
      if (callback) callback()
    },
    createSelectorQuery() {
      const query = {
        select() { return query },
        boundingClientRect() { return query },
        exec(callback) { measurements.push(callback) }
      }
      return query
    },
    ...definition.methods
  }
  return {
    component,
    definition,
    measurements,
    ready() { definition.lifetimes.ready.call(component) },
    measure(viewport, content) {
      assert.ok(measurements.length, '组件应在布局完成后测量实际宽度')
      measurements.shift()([{ width: viewport }, { width: content }])
    }
  }
}

test('短信息和无有效尺寸时保持静止', () => {
  for (const [viewport, content] of [[200, 120], [200, 200], [200, 201], [0, 280], [200, NaN]]) {
    const fixture = createMarquee()
    fixture.ready()
    fixture.measure(viewport, content)
    assert.equal(fixture.component.data.animationEnabled, false)
    assert.equal(fixture.component.data.distance, 0)
  }
})

test('超宽信息无需触摸便启动动画，移动距离足够露出完整末尾', () => {
  const fixture = createMarquee()
  fixture.ready()
  fixture.measure(200, 280.4)
  assert.equal(fixture.component.data.animationEnabled, true)
  assert.equal(fixture.component.data.distance, 81)
  assert.ok(fixture.component.data.duration >= 6)
})

test('信息更长时增加播放时长，避免快速掠过', () => {
  const fixture = createMarquee()
  fixture.ready()
  fixture.measure(200, 280)
  const initialDuration = fixture.component.data.duration
  fixture.component.refreshMarquee()
  fixture.measure(200, 680)
  assert.equal(fixture.component.data.distance, 480)
  assert.ok(fixture.component.data.duration > initialDuration)
})

test('内容或批量模式变化后重新测量，过期布局回调不能覆盖新结果', () => {
  const fixture = createMarquee()
  fixture.ready()
  fixture.definition.observers['text, compact'].call(fixture.component)
  fixture.measure(200, 600)
  assert.equal(fixture.component.data.animationEnabled, false)
  fixture.measure(200, 120)
  assert.equal(fixture.component.data.animationEnabled, false)
  fixture.definition.observers['text, compact'].call(fixture.component)
  fixture.measure(100, 120)
  assert.equal(fixture.component.data.distance, 20)
})

test('页面隐藏时暂停，重新显示和尺寸变化时重新计算滚动范围', () => {
  const fixture = createMarquee()
  fixture.ready()
  fixture.measure(200, 400)
  fixture.definition.pageLifetimes.hide.call(fixture.component)
  assert.equal(fixture.component.data.visible, false)
  fixture.definition.pageLifetimes.show.call(fixture.component)
  assert.equal(fixture.component.data.visible, true)
  fixture.measure(250, 400)
  assert.equal(fixture.component.data.distance, 150)
  fixture.definition.pageLifetimes.resize.call(fixture.component)
  fixture.measure(500, 400)
  assert.equal(fixture.component.data.animationEnabled, false)
})

test('组件移除后不再接收未完成的测量回调', () => {
  const fixture = createMarquee()
  fixture.ready()
  fixture.definition.lifetimes.detached.call(fixture.component)
  fixture.component.setData = () => assert.fail('已移除组件不能更新视图')
  fixture.measure(200, 600)
})
