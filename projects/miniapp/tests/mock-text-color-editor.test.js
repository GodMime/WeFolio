const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')

function loadComponent(relativePath, properties = {}) {
  const entry = path.join(MINIAPP_ROOT, relativePath)
  let definition
  const previousComponent = global.Component
  global.Component = value => { definition = value }
  try {
    if (fs.existsSync(entry)) {
      delete require.cache[require.resolve(entry)]
      require(entry)
    }
  } finally {
    if (previousComponent === undefined) delete global.Component
    else global.Component = previousComponent
  }

  assert.ok(definition, `组件必须注册：${relativePath}`)
  const defaults = Object.fromEntries(Object.entries(definition.properties || {}).map(([key, value]) => [key, value.value]))
  const events = []
  const timers = new Map()
  let nextTimer = 1
  const instance = {
    properties: { ...defaults, ...properties },
    data: { ...defaults, ...(definition.data || {}), ...properties },
    setData(values, callback) { Object.assign(this.data, values); if (callback) callback() },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    ...(definition.methods || {})
  }

  return {
    definition,
    instance,
    events,
    run(callback) {
      const previousSetTimeout = global.setTimeout
      const previousClearTimeout = global.clearTimeout
      global.setTimeout = handler => { const id = nextTimer++; timers.set(id, handler); return id }
      global.clearTimeout = id => { timers.delete(id) }
      try { return callback() } finally {
        global.setTimeout = previousSetTimeout
        global.clearTimeout = previousClearTimeout
      }
    },
    tick() {
      const callbacks = [...timers.values()]
      timers.clear()
      callbacks.forEach(callback => callback())
    },
    property(name, value) {
      instance.properties[name] = value
      instance.data[name] = value
      const observer = definition.observers && definition.observers[name]
      if (observer) this.run(() => observer.call(instance, value))
    }
  }
}

test('mock 颜色编辑器默认可用且可选择正式预设并确认完整 HEX 自定义颜色', () => {
  const harness = loadComponent('components/mock/text-color-editor/text-color-editor.js', { color: 'AUTO' })
  const { definition, instance, events } = harness
  definition.lifetimes.attached.call(instance)

  assert.equal(instance.data.active, true)
  assert.equal(instance.data.inline, false)
  assert.deepEqual(instance.data.presets, [
    { color: '#000000', label: '黑' },
    { color: '#FFFFFF', label: '白' },
    { color: '#CED4DA', label: '浅灰' }
  ])
  harness.run(() => instance.handlePreset({ currentTarget: { dataset: { color: '#FFFFFF' } } }))
  assert.deepEqual(events, [{ name: 'change', detail: { color: '#FFFFFF' } }])

  harness.property('color', '#123456')
  harness.run(() => instance.handleOpenCustom())
  harness.tick()
  instance.handleHexInput({ detail: { value: '#abcdef' } })
  harness.run(() => instance.handleConfirmCustom())
  assert.deepEqual(events.at(-1), { name: 'change', detail: { color: '#ABCDEF' } })
})

test('mock 颜色编辑器拒绝非法值并在失活时清理未确认颜色', () => {
  const harness = loadComponent('components/mock/text-color-editor/text-color-editor.js', { color: '#123456', active: true, inline: true })
  const { definition, instance, events } = harness
  definition.lifetimes.attached.call(instance)

  harness.run(() => instance.handleOpenCustom())
  harness.tick()
  instance.handleHexInput({ detail: { value: '#123' } })
  harness.run(() => instance.handleConfirmCustom())
  assert.equal(instance.data.errorMessage, '请输入正确的颜色值')
  assert.deepEqual(events, [])

  harness.property('active', false)
  assert.equal(instance.data.pickerMounted, false)
  assert.equal(instance.data.pickerVisible, false)
  harness.run(() => instance.handleConfirmCustom())
  assert.deepEqual(events, [])
})

test('mock 颜色编辑器只依赖自身目录的纯颜色工具', () => {
  const sourcePath = path.join(MINIAPP_ROOT, 'components/mock/text-color-editor/text-color-editor.js')
  assert.ok(fs.existsSync(sourcePath), 'mock 颜色编辑器脚本必须存在')
  const source = fs.readFileSync(sourcePath, 'utf8')
  const dependencies = [...source.matchAll(/require\(['"]([^'"]+)['"]\)/g)].map(match => match[1])

  assert.deepEqual(dependencies, ['./text-color'])
})

test('mock 联系信息编辑器用颜色组件映射边框颜色并忽略关闭后的迟到事件', () => {
  const markupPath = path.join(MINIAPP_ROOT, 'components/mock/contact-info-editor/contact-info-editor.wxml')
  const manifestPath = path.join(MINIAPP_ROOT, 'components/mock/contact-info-editor/contact-info-editor.json')
  const markup = fs.readFileSync(markupPath, 'utf8')
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  const { instance, events } = loadComponent('components/mock/contact-info-editor/contact-info-editor.js', {
    config: { contactBorder: true, contactBorderColor: 'AUTO' }
  })

  assert.equal(manifest.usingComponents['mock-text-color-editor'], '../text-color-editor/text-color-editor')
  assert.match(markup, /<mock-text-color-editor\b[^>]*label="边框颜色"[^>]*inline="\{\{true\}\}"[^>]*bindchange="handleBorderColor"[^>]*\/>/)
  instance.handleBorderColor({ detail: { color: '#ABCDEF' } })
  assert.deepEqual(events, [{ name: 'configchange', detail: { config: { contactBorder: true, contactBorderColor: '#ABCDEF' } } }])

  instance.data.config = { contactBorder: false, contactBorderColor: 'AUTO' }
  instance.handleBorderColor({ detail: { color: '#123456' } })
  assert.equal(events.length, 1)
})
