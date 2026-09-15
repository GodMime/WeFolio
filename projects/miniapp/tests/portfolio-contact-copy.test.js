const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const SCOPES = ['portfolios', 'team-portfolios']
const CONTACT = { contactPhone: '123456', contactWechat: 'example-wechat' }
const IDLE_FIELDS = { contactPhone: false, contactWechat: false }
const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value))
const copyEvent = field => ({ currentTarget: { dataset: { field } } })

// 每个组件使用独立的虚拟时钟，不替换全局计时器，也不等待真实时间。
function createClock() {
  let now = 0
  let sequence = 0
  const timers = new Map()
  return {
    setTimeout(callback, delay) {
      const id = ++sequence
      timers.set(id, { callback, at: now + delay })
      return id
    },
    clearTimeout(id) { timers.delete(id) },
    advance(milliseconds) {
      const end = now + milliseconds
      while (true) {
        const next = [...timers.entries()].filter(([, timer]) => timer.at <= end)
          .sort((left, right) => left[1].at - right[1].at)[0]
        if (!next) break
        const [id, timer] = next
        now = timer.at
        timers.delete(id)
        timer.callback()
      }
      now = end
    },
    pendingCount() { return timers.size }
  }
}

function loadContact(scope, config = CONTACT) {
  const filename = path.join(__dirname, '../pages', scope, 'components/contact-info/contact-info.js')
  const clock = createClock()
  const clipboardCalls = []
  const toasts = []
  const updates = []
  let definition
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Component: value => { definition = value },
    require: relative => require(path.resolve(path.dirname(filename), relative)),
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    wx: {
      setClipboardData: options => clipboardCalls.push(options),
      showToast: options => toasts.push(clone(options))
    }
  }, { filename })
  const properties = { config: clone(config), themeMode: 'light' }
  const component = {
    properties,
    data: { ...clone(definition.data), ...clone(properties) },
    ...definition.methods,
    setData(patch) {
      updates.push(clone(patch))
      for (const [key, value] of Object.entries(patch)) {
        const segments = key.split('.')
        const last = segments.pop()
        let target = this.data
        for (const segment of segments) target = target[segment]
        target[last] = clone(value)
      }
    }
  }
  definition.lifetimes.attached.call(component)
  return {
    component, clock, clipboardCalls, toasts, updates,
    detach() { definition.lifetimes.detached.call(component) },
    changeConfig(next) {
      component.properties.config = clone(next)
      component.data.config = clone(next)
      definition.observers['config, themeMode'].call(component)
    }
  }
}

for (const scope of SCOPES) {
  test(`${scope}: copy feedback appears only after clipboard success and resets after two seconds`, () => {
    const { component, clock, clipboardCalls, toasts } = loadContact(scope)
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
    component.handleCopy(copyEvent('contactPhone'))
    assert.equal(clipboardCalls.length, 1)
    assert.equal(clipboardCalls[0].data, CONTACT.contactPhone)
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
    assert.deepEqual(toasts, [])
    clipboardCalls[0].success()
    assert.deepEqual(clone(component.data.copiedFields), { contactPhone: true, contactWechat: false })
    assert.deepEqual(toasts, [{ title: '已复制', icon: 'success' }])
    clock.advance(1999)
    assert.equal(component.data.copiedFields.contactPhone, true)
    clock.advance(1)
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
    assert.equal(clock.pendingCount(), 0)
  })

  test(`${scope}: phone and WeChat have independent copy feedback deadlines`, () => {
    const { component, clock, clipboardCalls } = loadContact(scope)
    component.handleCopy(copyEvent('contactPhone'))
    clipboardCalls[0].success()
    clock.advance(1000)
    component.handleCopy(copyEvent('contactWechat'))
    assert.equal(clipboardCalls[1].data, CONTACT.contactWechat)
    clipboardCalls[1].success()
    assert.deepEqual(clone(component.data.copiedFields), { contactPhone: true, contactWechat: true })
    clock.advance(1000)
    assert.deepEqual(clone(component.data.copiedFields), { contactPhone: false, contactWechat: true })
    clock.advance(1000)
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
  })

  test(`${scope}: copying the same field again renews its success feedback deadline`, () => {
    const { component, clock, clipboardCalls } = loadContact(scope)
    component.handleCopy(copyEvent('contactPhone'))
    clipboardCalls[0].success()
    clock.advance(1500)
    component.handleCopy(copyEvent('contactPhone'))
    clipboardCalls[1].success()
    assert.equal(clock.pendingCount(), 1)
    clock.advance(500)
    assert.equal(component.data.copiedFields.contactPhone, true)
    clock.advance(1499)
    assert.equal(component.data.copiedFields.contactPhone, true)
    clock.advance(1)
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
  })

  test(`${scope}: clipboard failure keeps the button idle and preserves the retry toast`, () => {
    const { component, clock, clipboardCalls, toasts } = loadContact(scope)
    component.handleCopy(copyEvent('contactWechat'))
    clipboardCalls[0].fail()
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
    assert.deepEqual(toasts, [{ title: '复制失败，请重试', icon: 'none' }])
    assert.equal(clock.pendingCount(), 0)
  })

  test(`${scope}: unsupported fields and empty contact values do not write the clipboard`, () => {
    const { component, clipboardCalls, toasts, clock } = loadContact(scope, { contactPhone: '', contactWechat: null })
    for (const field of ['contactPhone', 'contactWechat', 'contactName', '__proto__', '', undefined]) {
      component.handleCopy(copyEvent(field))
    }
    assert.equal(clipboardCalls.length, 0)
    assert.deepEqual(toasts, [])
    assert.equal(clock.pendingCount(), 0)
    assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
  })

  test(`${scope}: detaching clears timers and ignores late clipboard callbacks`, () => {
    for (const outcome of ['success', 'fail']) {
      const { component, clock, clipboardCalls, toasts, updates, detach } = loadContact(scope)
      component.handleCopy(copyEvent('contactPhone'))
      clipboardCalls[0].success()
      component.handleCopy(copyEvent('contactWechat'))
      assert.equal(clock.pendingCount(), 1)
      detach()
      assert.equal(clock.pendingCount(), 0)
      const updateCount = updates.length
      const toastCount = toasts.length
      clipboardCalls[1][outcome]()
      clock.advance(5000)
      assert.equal(updates.length, updateCount)
      assert.equal(toasts.length, toastCount)
      assert.equal(clock.pendingCount(), 0)
    }
  })

  test(`${scope}: contact replacement clears feedback and invalidates old clipboard callbacks`, () => {
    for (const outcome of ['success', 'fail']) {
      const { component, clock, clipboardCalls, toasts, updates, changeConfig } = loadContact(scope)
      component.handleCopy(copyEvent('contactPhone'))
      clipboardCalls[0].success()
      component.handleCopy(copyEvent('contactWechat'))
      changeConfig({ contactPhone: '654321', contactWechat: 'replacement-wechat' })
      assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
      assert.equal(clock.pendingCount(), 0)
      const updateCount = updates.length
      const toastCount = toasts.length
      clipboardCalls[1][outcome]()
      clock.advance(5000)
      assert.equal(updates.length, updateCount)
      assert.equal(toasts.length, toastCount)
      assert.deepEqual(clone(component.data.copiedFields), IDLE_FIELDS)
      component.handleCopy(copyEvent('contactWechat'))
      assert.equal(clipboardCalls[2].data, 'replacement-wechat')
      clipboardCalls[2].success()
      assert.equal(component.data.copiedFields.contactWechat, true)
    }
  })
}

test('contact copy components remain identical across the isolated personal and team subpackages', () => {
  for (const extension of ['js', 'wxml', 'wxss']) {
    const sources = SCOPES.map(scope => fs.readFileSync(path.join(__dirname, '../pages', scope, `components/contact-info/contact-info.${extension}`), 'utf8'))
    assert.equal(sources[0], sources[1], `contact-info.${extension}`)
  }
})
