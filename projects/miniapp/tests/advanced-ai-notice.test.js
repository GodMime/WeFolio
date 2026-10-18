const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const { getRegisteredPageRoutes } = require('./helpers/app-pages')

const ROOT = path.resolve(__dirname, '..')
const PACKAGES = [['portfolios', 'portfolio-standard-edit'], ['team-portfolios', 'team-portfolio-standard-edit']]
const COMPONENT = 'components/advanced-ai-notice/advanced-ai-notice'
const read = relative => fs.readFileSync(path.join(ROOT, relative), 'utf8')

/** 使用真实组件定义验证局部状态，禁止通过页面事件触发保存或草稿修改。 */
function loadNotice(folder) {
  let definition
  vm.runInNewContext(read(`pages/${folder}/${COMPONENT}.js`), { Component(value) { definition = value } })
  const patches = []
  const instance = {
    ...definition.methods,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) {
      patches.push(JSON.parse(JSON.stringify(patch)))
      Object.assign(this.data, patch)
    },
    triggerEvent() { assert.fail('说明弹层不能向编辑页发送业务变更事件') }
  }
  return { definition, instance, patches }
}

for (const [folder, page] of PACKAGES) {
  test(`${folder} 提示开关仅更新组件状态，离开页面后不残留遮罩`, () => {
    const { definition, instance, patches } = loadNotice(folder)
    assert.equal(instance.data.visible, false)
    instance.handleOpen()
    assert.equal(instance.data.visible, true)
    instance.noop()
    assert.equal(instance.data.visible, true, '面板内部点击不能误关弹层')
    instance.handleClose()
    assert.equal(instance.data.visible, false)
    instance.handleOpen()
    definition.pageLifetimes.hide.call(instance)
    assert.equal(instance.data.visible, false)
    assert.deepEqual(patches, [{ visible: true }, { visible: false }, { visible: true }, { visible: false }])
  })

  test(`${folder} 提示只接入高级编辑工具卡，第三步采用后复用同一编辑入口`, () => {
    const template = read(`pages/${folder}/standard-edit/${page}.wxml`)
    const config = JSON.parse(read(`pages/${folder}/standard-edit/${page}.json`))
    const toolsStart = template.indexOf('<view wx:if="{{advancedEditing}}" class="panel advanced-tools')
    const toolsEnd = template.indexOf('<view wx:if="{{advancedEditing && aiReferenceAttachments.length}}"')
    const tools = template.slice(toolsStart, toolsEnd)
    assert.ok(toolsStart >= 0 && toolsEnd > toolsStart)
    assert.equal((template.match(/<advanced-ai-notice\b/g) || []).length, 1)
    assert.match(tools, /<advanced-ai-notice class="advanced-ai-copy"><\/advanced-ai-notice>/)
    assert.match(tools, /data-mode="MODIFY_PAGE" bindtap="handleAdvancedAi"/)
    assert.equal(config.usingComponents['advanced-ai-notice'], `../${COMPONENT}`)
    const aiPage = read(`pages/${folder}/utils/advanced-ai-page.js`)
    assert.match(aiPage, /standard-edit\/\$\{editor\}\?templateType=ADVANCED/, '创建第三步沿现有路由进入高级编辑页')
  })

  test(`${folder} 两行同边界且提示不增加独立卡片，弹层阻断背景触摸`, () => {
    const template = read(`pages/${folder}/${COMPONENT}.wxml`)
    const styles = read(`pages/${folder}/${COMPONENT}.wxss`)
    assert.match(template, /部分内容由AI生成/)
    assert.match(template, /<root-portal wx:if="\{\{visible\}\}">/)
    assert.match(template, /class="notice-mask" catchtap="handleClose" catchtouchmove="noop"/)
    assert.match(template, /class="notice-sheet" catchtap="noop"/)
    assert.match(template, /class="notice-confirm" catchtap="handleClose"/)
    assert.match(template, /class="notice-close" catchtap="handleClose"/)
    assert.doesNotMatch(template, /<button\b/, '文本入口不用原生按钮，避免默认内边距导致两行错位')
    assert.match(styles, /\.notice-trigger\s*\{[^}]*align-items:\s*flex-start;[^}]*margin:\s*0;[^}]*padding:\s*0;/)
    assert.match(styles, /\.notice-caption\s*\{[^}]*padding:\s*0;/)
    assert.match(styles, /env\(safe-area-inset-bottom\)/)
  })
}

test('两个业务分包的提示组件保持完全一致', () => {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.equal(read(`pages/portfolios/${COMPONENT}.${extension}`), read(`pages/team-portfolios/${COMPONENT}.${extension}`))
  }
})

test('提示组件不进入标准访客、候选预览、生成前两步或 mock 页面', () => {
  const registered = getRegisteredPageRoutes(JSON.parse(read('app.json'))).filter(route => {
    const components = JSON.parse(read(`${route}.json`)).usingComponents || {}
    return Object.values(components).some(value => value.includes('advanced-ai-notice'))
  })
  assert.deepEqual(registered.sort(), PACKAGES.map(([folder, page]) => `pages/${folder}/standard-edit/${page}`).sort())
})
