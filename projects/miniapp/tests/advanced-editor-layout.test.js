const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const read = file => fs.readFileSync(path.join(MINIAPP_ROOT, file), 'utf8')
// 来自本机微信基础库 3.16.2 的 WAWebview.js；普通 button 会以 wx-button 元素参与样式层叠。
const NATIVE_BUTTON_WIDTH_STYLE = 'wx-button:not([size=mini]){margin-left:auto;margin-right:auto;width:184px}'

/** 核对本回归涉及的类型、类、属性与 :not 选择器优先级，不将源码顺序误当覆盖能力。 */
function selectorSpecificity(selector) {
  const negated = []
  let remaining = selector.replace(/:not\(([^()]*)\)/g, (_, argument) => {
    negated.push(selectorSpecificity(argument))
    return ''
  })
  const ids = (remaining.match(/#[\w-]+/g) || []).length
  const classes = (remaining.match(/\.[\w-]+|\[[^\]]+\]|:[\w-]+/g) || []).length
  remaining = remaining.replace(/#[\w-]+|\.[\w-]+|\[[^\]]+\]|:[\w-]+/g, '')
  const types = (remaining.match(/[a-zA-Z][\w-]*/g) || []).length
  return negated.reduce((total, value) => total.map((count, index) => count + value[index]), [ids, classes, types])
}

function compareSpecificity(left, right) {
  return left[0] - right[0] || left[1] - right[1] || left[2] - right[2]
}

/** 小型层叠验证只匹配提示卡和按钮两层，真实文本行数仍由渲染核查证明。 */
function matchesButtonSelector(selector, disabled) {
  const matchesPart = (part, tag, classes, attributes) => {
    let matched = true
    const remaining = part.replace(/:not\(([^()]*)\)/g, (_, argument) => {
      if (matchesPart(argument, tag, classes, attributes)) matched = false
      return ''
    })
    for (const [, className] of remaining.matchAll(/\.([\w-]+)/g)) if (!classes.includes(className)) matched = false
    for (const [, attribute, value] of remaining.matchAll(/\[([\w-]+)(?:=([^\]]+))?\]/g)) {
      if (!Object.hasOwn(attributes, attribute) || (value && attributes[attribute] !== value)) matched = false
    }
    const type = remaining.match(/^[a-zA-Z][\w-]*/)
    return matched && (!type || type[0] === tag)
  }
  const parts = selector.trim().split(/\s+/)
  const attributes = disabled ? { disabled: '' } : {}
  if (!matchesPart(parts.pop(), 'wx-button', ['advanced-ai-button'], attributes)) return false
  return parts.length === 0 || parts.length === 1 && matchesPart(parts[0], 'wx-view', ['advanced-tools', 'panel', 'pe-page-card'], {})
}

function resolveButtonProperty(sources, property, disabled = false) {
  let winner
  let order = 0
  for (const source of sources) {
    const css = source.replace(/\/\*[\s\S]*?\*\//g, '')
    for (const [, selectors, declarations] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const declaration = declarations.match(new RegExp(`(?:^|;)\\s*${property}\\s*:\\s*([^;]+)`))
      if (!declaration) continue
      for (const selector of selectors.split(',')) {
        if (!matchesButtonSelector(selector, disabled)) continue
        const candidate = { value: declaration[1].trim(), specificity: selectorSpecificity(selector), order: order++ }
        if (!winner || compareSpecificity(candidate.specificity, winner.specificity) > 0
          || compareSpecificity(candidate.specificity, winner.specificity) === 0 && candidate.order > winner.order) winner = candidate
      }
    }
  }
  return winner && winner.value
}

for (const [folder, filename] of [['portfolios', 'portfolio-standard-edit'], ['team-portfolios', 'team-portfolio-standard-edit']]) {
  test(`${folder} AI 按钮宽度在真实基础库普通按钮规则前后均保持 144rpx`, () => {
    const styles = read(`pages/${folder}/styles/advanced-editor.wxss`)
    assert.deepEqual(selectorSpecificity('wx-button:not([size=mini])'), [0, 1, 1])
    // 单类宽度声明优先级不足，即使后加载也会恢复基础库的 184px。
    assert.equal(resolveButtonProperty([NATIVE_BUTTON_WIDTH_STYLE, '.advanced-ai-button{width:144rpx}'], 'width'), '184px')
    for (const sources of [[NATIVE_BUTTON_WIDTH_STYLE, styles], [styles, NATIVE_BUTTON_WIDTH_STYLE]]) {
      for (const disabled of [false, true]) {
        assert.equal(resolveButtonProperty(sources, 'width', disabled), '144rpx')
        assert.equal(resolveButtonProperty(sources, 'box-sizing', disabled), 'border-box')
      }
    }
  })

  test(`${folder} 高级编辑仅增加整体 AI 修改和已选内容，共用标准表单与参数`, () => {
    const wxml = read(`pages/${folder}/standard-edit/${filename}.wxml`)
    const styles = read(`pages/${folder}/styles/advanced-editor.wxss`)
    const share = wxml.slice(wxml.indexOf('<view class="panel share-panel'), wxml.indexOf('<view class="panel page-setting-panel'))
    const settings = wxml.slice(wxml.indexOf('<view class="panel page-setting-panel'), wxml.indexOf('<view class="panel component-panel'))
    const components = wxml.slice(wxml.indexOf('<view class="panel component-panel'), wxml.indexOf('<view wx:if="{{advancedEditing}}" class="advanced-source-card'))
    const actions = wxml.slice(wxml.indexOf('<view class="bottom-actions'), wxml.indexOf('<view wx:if="{{hasRemoteFonts}}"'))

    for (const section of [share, settings, components, actions]) {
      assert.ok(section.length > 0)
      assert.doesNotMatch(section, /advancedEditing|advanced-share|advanced-settings|advanced-component|advanced-editor-actions/)
    }
    assert.equal((share.match(/bindinput="handleShareInput"/g) || []).length, 1)
    assert.match(share, /class="form-row cover-form-row"/)
    assert.match(share, /class="field-limit">\{\{(?:shareFieldCounters.title|shareTitleCounter)\}\}/)
    assert.match(share, /class="pe-page-field"[^>]*maxlength="50"/)
    assert.match(settings, /bindchange="handleBackgroundAudioSwitch"/)
    assert.match(settings, /bindtap="handleBottomNav(?:igation)?CountTap"/)
    assert.match(components, /class="component-order pe-component-index">\{\{index \+ 1\}\}/)
    assert.match(components, /catchtap="handleComponentTap"/)
    assert.match(wxml, /data-mode="MODIFY_PAGE" bindtap="handleAdvancedAi"/)
    assert.match(wxml, /wx:for="\{\{advancedSourceItems\}\}" wx:key="sourceNo"/)
    assert.match(wxml, /class="advanced-source-no">\{\{item.sourceNo\}\}/)
    assert.match(wxml, /data-mode="MANAGE_SOURCES" bindtap="handleAdvancedAi"/)
    assert.deepEqual(Array.from(new Set(Array.from(wxml.matchAll(/data-mode="([^"]+)"/g), match => match[1]))), ['MODIFY_PAGE', 'MANAGE_SOURCES'])
    assert.doesNotMatch(styles, /advanced-editor-content|advanced-share|advanced-settings|advanced-component|advanced-editor-actions/)
    assert.match(styles, /\.advanced-tools\s*\{[^}]*display:\s*flex;/)
  })
}

test('高级个人与团队编辑样式在各分包内保持一致', () => {
  assert.equal(read('pages/portfolios/styles/advanced-editor.wxss'), read('pages/team-portfolios/styles/advanced-editor.wxss'))
})
