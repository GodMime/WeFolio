const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function cssRule(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = css.match(new RegExp(`(?:^|})\\s*${escaped}\\s*\\{([^}]*)}`, 'm'))
  assert.ok(match, `缺少 ${selector}`)
  return Object.fromEntries(match[1].split(';').map(value => value.trim().split(/:\s*/)).filter(([key, value]) => key && value))
}

for (const directory of ['portfolios', 'team-portfolios', 'mock']) {
  const base = directory === 'mock'
    ? path.join(__dirname, '../components/mock/timeline-editor/timeline-editor')
    : path.join(__dirname, '../pages', directory, 'components/timeline-editor/timeline-editor')
  const wxml = fs.readFileSync(base + '.wxml', 'utf8')
  const css = fs.readFileSync(base + '.wxss', 'utf8')

  test(`${directory} 时间轴内容页先编辑标题与节点，完整预览只位于样式页末尾`, () => {
    const contentStart = wxml.indexOf('wx:if="{{!styleDetail && tab === \'content\'}}"')
    const styleStart = wxml.indexOf('wx:elif="{{!styleDetail}}"')
    const preview = wxml.indexOf('class="timeline-preview"')
    assert.ok(contentStart > 0 && styleStart > contentStart)
    const content = wxml.slice(contentStart, styleStart)
    assert.match(content, /时间轴标题/)
    assert.match(content, /时间节点/)
    assert.doesNotMatch(content, /portfolio-timeline|timeline-preview/)
    assert.ok(preview > wxml.indexOf('统一文字样式', styleStart))
    assert.equal((wxml.match(/<portfolio-timeline\b/g) || []).length, 1)
    const previewRule = cssRule(css, '.timeline-preview')
    assert.equal(previewRule.height, undefined)
    assert.equal(previewRule['max-height'], undefined)
  })

  test(`${directory} 时间轴节点有稳定身份与完整拖动事件，拖动期间禁用滚动和整组件操作`, () => {
    assert.match(wxml, /wx:for="{{nodeRows}}" wx:key="nodeKey" class="timeline-node-row[^>]*data-key="{{item.nodeKey}}"/)
    const handle = wxml.match(/<view class="timeline-drag-handle[^>]*>/)[0]
    for (const [binding, handler] of [['catchtouchstart', 'handleDragStart'], ['catchtouchmove', 'handleDragMove'], ['catchtouchend', 'handleDragEnd'], ['catchtouchcancel', 'handleDragCancel'], ['catchtap', 'noop']]) {
      assert.ok(handle.includes(`${binding}="${handler}"`))
    }
    assert.match(wxml, /<scroll-view[^>]*scroll-y="{{!draggingKey}}"[^>]*scroll-top="{{editorScrollTop}}"[^>]*bindscroll="handleEditorScroll"/)
    assert.match(wxml, /style="{{draggingKey === item.nodeKey \? dragStyle : ''}}"/)
    assert.doesNotMatch(wxml, /handleMoveNode|>上移<|>下移</)
    assert.match(wxml, /bindtap="handleCancel" disabled="{{!!draggingKey}}"/)
    assert.match(wxml, /bindtap="handleConfirm" disabled="{{!!draggingKey}}"/)
    assert.equal(cssRule(css, '.timeline-node-main.dragging').opacity, '1')
    assert.equal(cssRule(css, '.timeline-node-main.dragging').transition, 'none')
  })

  test(`${directory} 时间轴输入框、滚动容器和底栏具有 Skyline 所需显式尺寸`, () => {
    const input = cssRule(css, '.timeline-input')
    assert.equal(input.height, '88rpx')
    assert.equal(input['min-height'], '88rpx')
    assert.equal(input['box-sizing'], 'border-box')
    assert.equal(input.padding, '0 24rpx', '单行输入由原生控件垂直居中，避免上下内边距使标题和自定义标签下移')
    assert.equal(cssRule(css, '.timeline-date-part .timeline-input').padding, '0 16rpx', '年月日输入同样仅保留左右内边距')
    assert.equal(cssRule(css, '.timeline-textarea').padding, '20rpx 24rpx', '多行描述保留文字留白')
    const scroll = cssRule(css, '.timeline-editor-scroll')
    assert.equal(scroll.height, '0')
    assert.equal(scroll['min-height'], '0')
    assert.equal(scroll.flex, '1')
    const footer = cssRule(css, '.timeline-editor-footer')
    assert.equal(footer['flex-shrink'], '0')
    assert.match(footer.padding, /safe-area-inset-bottom/)
    const button = cssRule(css, '.timeline-editor-footer button')
    assert.equal(button.width, '0')
    assert.equal(button['min-width'], '0')
    assert.equal(button.flex, '1 1 0')
    const nodeHeader = cssRule(css, '.timeline-node-header')
    assert.equal(nodeHeader['flex-shrink'], '0')
    assert.equal(cssRule(css, '.timeline-node-header-title')['text-align'], 'center')
    const back = cssRule(css, '.timeline-header-back')
    assert.equal(back['min-width'], '44px')
    assert.equal(back['min-height'], '44px')
  })

  test(`${directory} 时间轴进退保留两个滚动面板，隐藏表单不可交互且过渡不挤占底栏`, () => {
    assert.match(wxml, /class="timeline-editor-stage {{animateNodes \? 'is-node-navigation' : ''}}"/)
    const panels = [...wxml.matchAll(/<view\b[^>]*class="timeline-editor-panel\b[^>]*>/g)].map(match => match[0])
    assert.equal(panels.length, 2)
    for (const panel of panels) {
      assert.doesNotMatch(panel, /\s(?:wx:if|hidden)=/)
      assert.match(panel, /aria-hidden=/)
    }
    assert.doesNotMatch(wxml, /wx:if="{{!?nodeDetail}}"|wx:if="{{node.nodeKey && nodeDetail}}"/)
    assert.match(wxml, /scroll-top="{{editorScrollTop}}" data-panel="main"/)
    assert.match(wxml, /scroll-top="{{nodeScrollTop}}" data-panel="node"/)
    const main = wxml.slice(wxml.indexOf('class="timeline-editor-panel timeline-list-panel'), wxml.indexOf('class="timeline-editor-panel timeline-node-panel'))
    const mainChrome = main.slice(0, main.indexOf('<scroll-view'))
    assert.match(mainChrome, /class="timeline-editor-header"/)
    assert.match(mainChrome, /class="timeline-editor-tabs"/)
    const detail = wxml.slice(wxml.indexOf('class="timeline-editor-panel timeline-node-panel'))
    const detailChrome = detail.slice(0, detail.indexOf('<scroll-view'))
    assert.match(detailChrome, /class="timeline-node-header"/)
    assert.match(detailChrome, /class="timeline-header-back"[^>]*bindtap="handleBackNodes"[^>]*aria-label="返回节点列表"/)
    assert.match(detailChrome, /class="timeline-node-header-title">编辑节点{{node.nodeKey \? ' ' \+ \(nodeIndex \+ 1\) : ''}}/)
    assert.match(detailChrome, /<text wx:if="{{node.nodeKey}}" class="timeline-meta">{{nodeIndex \+ 1}} \/ {{draft.nodes.length}}/)
    assert.doesNotMatch(detailChrome.match(/<view class="timeline-node-header"[^>]*>/)[0], /wx:if|hidden=/)
    assert.doesNotMatch(detailChrome, /‹ 返回|timeline-editor-tabs/)
    assert.doesNotMatch(detail.slice(detail.indexOf('<scroll-view')), /handleBackNodes|timeline-detail-heading/)
    for (const field of detail.match(/<(?:input|textarea)\b[^>]*>/g)) {
      assert.match(field, /disabled="{{!nodeDetail \|\| styleDetail \|\| tab !== 'content'}}"/)
    }
    const stage = cssRule(css, '.timeline-editor-stage')
    assert.equal(stage.overflow, 'hidden')
    assert.equal(stage['min-height'], '0')
    assert.equal(stage.flex, '1')
    const panel = cssRule(css, '.timeline-editor-panel')
    assert.equal(panel.position, 'absolute')
    assert.equal(panel.height, '100%')
    assert.equal(panel['pointer-events'], 'none')
    assert.equal(cssRule(css, '.timeline-editor-panel.is-active')['pointer-events'], 'auto')
    const transition = cssRule(css, '.timeline-editor-stage.is-node-navigation .timeline-editor-panel').transition
    assert.match(transition, /transform/)
    assert.match(transition, /opacity/)
  })

  test(`${directory} 时间轴仅调整当前方向间距，文字入口进入独立保存取消详情`, () => {
    assert.equal((wxml.match(/<slider\b/g) || []).length, 1)
    const slider = wxml.match(/<slider\b[^>]*>/)[0]
    assert.ok(slider.includes("value=\"{{draft.direction === 'HORIZONTAL' ? draft.minGapRpx.horizontal : draft.minGapRpx.vertical}}\""))
    assert.ok(slider.includes("data-direction=\"{{draft.direction === 'HORIZONTAL' ? 'horizontal' : 'vertical'}}\""))
    assert.match(slider, /bindchange="handleGap"/)
    assert.match(wxml, /wx:for="{{styleEntries}}"[^>]*data-scope="component"[^>]*data-role="{{item.value}}"[^>]*bindtap="handleEditTextStyle"/)
    assert.match(wxml, /wx:for="{{nodeStyleEntries}}"[^>]*data-scope="node"[^>]*data-role="{{item.value}}"[^>]*bindtap="handleEditTextStyle"/)
    assert.match(wxml, /<text-color-editor[^>]*active="{{visible && styleDetail}}"/)
    assert.match(wxml, /bindtap="handleCancelTextStyle"/)
    assert.match(wxml, /bindtap="handleSaveTextStyle"/)
    assert.match(wxml, /class="timeline-editor-mask[^>]*catchtap="{{styleDetail \? 'handleCancelTextStyle' : 'handleCancel'}}"/)
    assert.match(wxml, /<view wx:if="{{styleDetail}}" class="timeline-editor-footer">[\s\S]*?bindtap="handleSaveTextStyle"/)
  })
}
