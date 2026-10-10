const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '..')
const read = (folder, extension) => fs.readFileSync(path.join(root, 'pages', folder, 'advanced-ai', `advanced-ai.${extension}`), 'utf8')

for (const folder of ['portfolios', 'team-portfolios']) {
  test(`${folder} AI 修改只显示整体描述，不展示单组件和整页重新生成模式`, () => {
    const wxml = read(folder, 'wxml')
    assert.match(wxml, /描述整体修改需求/)
    assert.deepEqual(Array.from(new Set(Array.from(wxml.matchAll(/mode === '([^']+)'/g), match => match[1]))), ['CREATE'])
  })

  test(`${folder} 选择页保留双列封面、稳定编号与独立底部操作`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    assert.ok(wxml.indexOf('class="ai-footer"') > wxml.lastIndexOf('</scroll-view>'))
    assert.match(wxml, /wx:for="\{\{sourceOptions\}\}" wx:key="key" class="source-cell/)
    assert.match(wxml, /index % 2 === 0 \? 'source-cell-left' : 'source-cell-right'/)
    assert.match(wxml, /class="source-card[^>]*data-index="\{\{index\}\}"[^>]*bindtap="handleSourceTap"/)
    assert.match(wxml, /class="selection-circle">\{\{item.selected \? item.sourceNo : ''\}\}/)
    assert.match(wxml, /class="type-tag">\{\{item.mediaTypeLabel\}\}/)
    assert.match(wxml, /class="selected-thumbnail"[^>]+mode="aspectFill"/)
    assert.match(wxss, /\.source-grid\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;/)
    assert.match(wxss, /\.source-cell\s*\{[^}]*flex:\s*0 0 50%;[^}]*width:\s*50%;[^}]*box-sizing:\s*border-box;/)
    assert.match(wxss, /\.source-card\s*\{[^}]*width:\s*100%;[^}]*box-sizing:\s*border-box;/)
    const gridRule = wxss.match(/\.source-grid\s*\{([^}]*)\}/)[1]
    assert.doesNotMatch(gridRule, /(?:gap|column-gap):/, '列宽与列间距分别转换 rpx 后可能多出 1px，禁止靠 gap 与 calc 凑齐双列')
    assert.match(wxss, /\.source-cell-left\s*\{[^}]*padding-right:\s*10rpx;/)
    assert.match(wxss, /\.source-cell-right\s*\{[^}]*padding-left:\s*10rpx;/)
    assert.match(wxss, /\.source-title\s*\{[^}]*overflow:\s*hidden;[^}]*text-overflow:\s*ellipsis;/)
    assert.match(wxss, /\.filter-chip\s*\{[^}]*flex:\s*none;[^}]*width:\s*auto;[^}]*height:\s*58rpx;/)
    assert.match(wxss, /\.ai-footer\s*\{[^}]*flex:\s*none;[^}]*safe-area-inset-bottom/)
    const page = require(`../pages/${folder}/utils/advanced-ai-page`).createAdvancedAiPage(folder === 'team-portfolios')
    for (const [, handler] of wxml.matchAll(/(?:bind|catch)(?:tap|input|change|confirm|scrolltolower)="([\w]+)"/g)) {
      assert.equal(typeof page[handler], 'function', `页面必须实现 ${handler}`)
    }
  })

  test(`${folder} 不可用候选显示失败原因并可修改描述或重新生成`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    assert.match(wxml, /result && \(result.kind === 'CANDIDATE' \|\| result.available === false\)/)
    assert.match(wxml, /class="selection-title">\{\{candidateTitle\}\}/)
    const failureBlock = wxml.match(/<block wx:if="\{\{result.available === false\}\}">([\s\S]*?)<\/block>/)[1]
    assert.match(failureBlock, /失败原因/)
    assert.match(failureBlock, /\{\{taskError\}\}/)
    assert.doesNotMatch(failureBlock, /notice success|候选已准备好|summaryText/)
    assert.match(wxml, /<block wx:else>\s*<view class="notice success">/)
    assert.match(wxml, /bindtap="handleDiscard"[^>]*>\{\{result.available === false \? '修改描述' : '放弃结果'\}\}/)
    assert.match(wxml, /wx:if="\{\{result.available === false\}\}"[^>]*bindtap="handleGenerate"[^>]*>重新生成<\/button>/)
    assert.ok(wxml.indexOf('class="panel failure-panel"') < wxml.indexOf('class="panel selected-panel"'), '执行失败原因应先于来源与输入展示，首次进入即可看到')
    assert.match(wxss, /\.notice\.error\s*\{[^}]*white-space:\s*pre-wrap;[^}]*overflow-wrap:\s*break-word;[^}]*word-break:\s*break-word;/)
  })
}

test('高级流程两分包的结构与样式保持一致', () => {
  for (const extension of ['wxml', 'wxss']) assert.equal(read('portfolios', extension), read('team-portfolios', extension))
})
