const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {
  normalizeMockContactInfoConfig, validateMockContactInfoConfig,
  normalizeMockHyperlinkConfig, validateMockHyperlinkConfig,
  resolveMockHyperlinkTarget, getMockHyperlinkTargetUrl, createMockCopyController
} = require('../pages/mock/utils/mock-portfolio-hyperlink')

const works = [{ id: 101, mediaType: 'IMAGE' }, { id: 109, mediaType: 'ANIMATION' }, { id: 108, mediaType: 'AUDIO' }]
const external = { workId: 109, actionType: 'EXTERNAL_LINK', externalContent: 'https://example.com/', promptText: '请在浏览器打开' }

test('联系内容不截断，拒绝控制字符、超长及空内容，关闭边框保留设置', () => {
  const source = { contactPhone: '示例电话', contactWechat: 'demo_wefolio', contactBorder: false, contactBorderWidthRpx: 6, contactBorderColor: '#abcdef', horizontalMarginRpx: 36, verticalMarginRpx: 24 }
  const result = normalizeMockContactInfoConfig(source)
  assert.equal(result.contactBorderColor, '#ABCDEF')
  assert.equal(result.contactBorderWidthRpx, 6)
  assert.equal(result.horizontalMarginRpx, 36)
  assert.equal(validateMockContactInfoConfig(result).valid, true)
  for (const config of [{}, { contactPhone: 'a'.repeat(33) }, { contactWechat: 'a'.repeat(65) }, { contactPhone: '电话\n' }, { contactWechat: '\u0000demo' }, { contactPhone: '示例', contactBorderWidthRpx: 13 }]) {
    assert.equal(validateMockContactInfoConfig(config).valid, false)
  }
  assert.equal(validateMockContactInfoConfig({ contactPhone: 'a'.repeat(32), contactWechat: 'a'.repeat(64) }).valid, true)
  assert.equal(normalizeMockContactInfoConfig({ contactPhone: 'a'.repeat(33) }).contactPhone.length, 33)
})

test('超链接仅允许图片动图、已注册内部目标，动作切换移除互斥字段', () => {
  assert.equal(validateMockHyperlinkConfig(external, works).valid, true)
  assert.equal(validateMockHyperlinkConfig({ ...external, workId: 108 }, works).valid, false)
  for (const field of ['externalContent', 'promptText']) {
    assert.equal(validateMockHyperlinkConfig({ ...external, [field]: '' }, works).valid, false)
    assert.equal(validateMockHyperlinkConfig({ ...external, [field]: 'a'.repeat(field === 'promptText' ? 31 : 2049) }, works).valid, false)
  }
  assert.equal(validateMockHyperlinkConfig({ ...external, externalContent: 'a'.repeat(2048), promptText: 'a'.repeat(30) }, works).valid, true)
  assert.equal(validateMockHyperlinkConfig({ ...external, iconPosition: 'INVALID' }, works).valid, false)
  const internal = normalizeMockHyperlinkConfig({ ...external, actionType: 'INTERNAL_PORTFOLIO', targetPortfolioId: 9002 })
  assert.equal('externalContent' in internal, false)
  assert.equal('promptText' in internal, false)
  assert.equal(validateMockHyperlinkConfig(internal, works).valid, true)
  assert.equal(validateMockHyperlinkConfig({ ...internal, targetPortfolioId: 42 }, works).valid, false)
  assert.equal('targetPortfolioId' in normalizeMockHyperlinkConfig({ ...external, targetPortfolioId: 9002 }), false)
})

test('本地目标只返回独立无递归快照，URL 固定 mock 既有预览页', () => {
  const target = resolveMockHyperlinkTarget(9002)
  assert.ok(target.config.components.length)
  assert.equal(target.config.components.some(item => item.componentType === 'HYPERLINK'), false)
  target.config.components.length = 0
  assert.ok(resolveMockHyperlinkTarget(9002).config.components.length)
  assert.equal(resolveMockHyperlinkTarget(123456), null)
  assert.equal(getMockHyperlinkTargetUrl(9002), '/pages/mock/portfolio-standard-preview/portfolio-standard-preview?demoTarget=9002')
  assert.equal(getMockHyperlinkTargetUrl('9002&url=other'), '')
})

function clock() {
  let time = 0
  let next = 0
  const jobs = new Map()
  return {
    now: () => time,
    setTimeout(fn, delay) { const id = ++next; jobs.set(id, { fn, at: time + delay }); return id },
    clearTimeout(id) { jobs.delete(id) },
    tick(ms) {
      const end = time + ms
      while (true) {
        const job = [...jobs].filter(([, value]) => value.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
        if (!job) break
        time = job[1].at; jobs.delete(job[0]); job[1].fn()
      }
      time = end
    },
    pending: () => jobs.size
  }
}

test('复制成功回调后联系状态保持两秒，失败不显示成功，可重试', () => {
  const timer = clock()
  const states = [], errors = [], requests = []
  const controller = createMockCopyController({ wxApi: { setClipboardData: options => requests.push(options) }, timers: timer, now: timer.now, onContactState: value => states.push(value), onError: value => errors.push(value) })
  controller.copyContact('contactPhone', '示例电话')
  assert.equal(requests[0].data, '示例电话')
  assert.deepEqual(states, [])
  requests[0].fail()
  assert.deepEqual(states, [])
  assert.equal(errors.length, 1)
  controller.copyContact('contactPhone', '示例电话')
  requests[1].success()
  assert.equal(states.at(-1), 'contactPhone')
  timer.tick(1999)
  assert.equal(states.at(-1), 'contactPhone')
  timer.tick(1)
  assert.equal(states.at(-1), '')
  controller.destroy()
})

test('外链提示延迟 1700ms 持续 2500ms，隐藏暂停与卸载抑制迟到回调', () => {
  const timer = clock()
  const prompts = [], requests = []
  const controller = createMockCopyController({ wxApi: { setClipboardData: options => requests.push(options) }, timers: timer, now: timer.now, onPrompt: value => prompts.push(value) })
  controller.copyHyperlink(external)
  requests[0].success()
  timer.tick(1000)
  controller.hide()
  timer.tick(5000)
  assert.ok(!prompts.includes(external.promptText))
  controller.show()
  timer.tick(699)
  assert.ok(!prompts.includes(external.promptText))
  timer.tick(1)
  assert.equal(prompts.at(-1), external.promptText)
  timer.tick(2499)
  assert.equal(prompts.at(-1), external.promptText)
  timer.tick(1)
  assert.equal(prompts.at(-1), '')
  controller.copyHyperlink(external)
  controller.destroy()
  requests[1].success()
  assert.equal(timer.pending(), 0)
})

test('后台成功回调等待前台、可见提示在后台暂停，失败与旧回调不覆盖新结果', () => {
  const timer = clock()
  const prompts = [], requests = [], errors = []
  const controller = createMockCopyController({ wxApi: { setClipboardData: options => requests.push(options) }, timers: timer, now: timer.now, onPrompt: value => prompts.push(value), onError: value => errors.push(value) })
  controller.copyHyperlink(external)
  controller.hide()
  requests[0].success()
  timer.tick(10000)
  assert.deepEqual(prompts, [])
  assert.equal(timer.pending(), 0)
  controller.show()
  timer.tick(1700)
  assert.equal(prompts.at(-1), external.promptText)
  timer.tick(1000)
  controller.hide()
  assert.equal(prompts.at(-1), '')
  timer.tick(10000)
  controller.show()
  assert.equal(prompts.at(-1), external.promptText)
  timer.tick(1500)
  assert.equal(prompts.at(-1), '')
  controller.copyHyperlink(external)
  controller.copyHyperlink({ ...external, promptText: '新提示' })
  requests[1].success()
  assert.equal(timer.pending(), 0)
  requests[2].fail()
  assert.equal(errors.length, 1)
  assert.equal(timer.pending(), 0)
  controller.copyHyperlink(external)
  requests[3].success()
  controller.destroy()
  timer.tick(10000)
  assert.equal(prompts.at(-1), '')
})

function component(name) {
  let definition
  const filename = path.join(__dirname, '../components/mock', name, `${name}.js`)
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Component: value => { definition = value } })
  const instance = { data: JSON.parse(JSON.stringify(definition.data || {})), events: [], setData(value) { Object.assign(this.data, value) }, triggerEvent(name, detail) { this.events.push({ name, detail }) } }
  Object.assign(instance, definition.methods)
  return { definition, instance }
}

test('组件只发复制及跳转事件，编辑器取消前不改写输入配置', () => {
  const contact = component('contact-info').instance
  contact.data.config = { contactPhone: '示例电话' }
  contact.handleCopy({ currentTarget: { dataset: { field: 'contactPhone' } } })
  assert.equal(contact.events[0].name, 'copy')
  assert.equal(contact.events[0].detail.value, '示例电话')
  const link = component('hyperlink').instance
  link.data.config = external
  link.handleTap()
  assert.equal(link.events[0].name, 'hyperlink')
  const editor = component('contact-info-editor').instance
  editor.data.config = { contactPhone: '原内容' }
  editor.handleInput({ currentTarget: { dataset: { field: 'contactPhone' } }, detail: { value: '新内容' } })
  assert.equal(editor.data.config.contactPhone, '原内容')
  assert.equal(editor.events[0].name, 'configchange')
  assert.equal(editor.events[0].detail.config.contactPhone, '新内容')
})

test('超链接编辑器切换动作时去除互斥字段，图标与作品选择采用页面事件', () => {
  const editor = component('hyperlink-editor').instance
  editor.data.config = { ...external, showClickIcon: true, iconPosition: 'BELOW' }
  editor.handleAction({ currentTarget: { dataset: { value: 'INTERNAL_PORTFOLIO' } } })
  const internal = editor.events.at(-1).detail.config
  assert.equal(internal.targetPortfolioId, 9002)
  assert.equal('externalContent' in internal, false)
  assert.equal('promptText' in internal, false)
  assert.equal(editor.data.config.actionType, 'EXTERNAL_LINK')
  editor.data.config = internal
  editor.handleAction({ currentTarget: { dataset: { value: 'EXTERNAL_LINK' } } })
  assert.equal('targetPortfolioId' in editor.events.at(-1).detail.config, false)
  assert.equal(editor.events.at(-1).detail.config.iconPosition, 'BELOW')
  editor.handleSelectWork()
  assert.equal(editor.events.at(-1).name, 'selectwork')
  editor.handleIconPosition({ currentTarget: { dataset: { value: 'OVERLAY_CENTER' } } })
  assert.equal(editor.events.at(-1).detail.config.iconPosition, 'OVERLAY_CENTER')
  editor.handleIconToggle({ detail: { value: false } })
  assert.equal(editor.events.at(-1).detail.config.showClickIcon, false)
})
