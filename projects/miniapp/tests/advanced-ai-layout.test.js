const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '..')
const read = (folder, extension) => fs.readFileSync(path.join(root, 'pages', folder, 'advanced-ai', `advanced-ai.${extension}`), 'utf8')
const readRule = (wxss, selector) => wxss.match(new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`))[1]
const readRpx = (rule, property) => Number.parseFloat(rule.match(new RegExp(`(?:^|;)\\s*${property}:\\s*([\\d.]+rpx|0)\\s*;`))?.[1])

for (const folder of ['portfolios', 'team-portfolios']) {
  test(`${folder} 已选内容横向滚动区完整容纳缩略图和编号，避免真机提示文字与下张卡片重叠`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    const scrollRule = readRule(wxss, '.selected-scroll')
    const stripRule = readRule(wxss, '.selected-strip')
    const footerStripRule = readRule(wxss, '.ai-footer .selected-strip')
    const coverRule = readRule(wxss, '.selected-cover')
    const labelRule = readRule(wxss, '.selected-label')
    const [, paddingTop, , paddingBottom] = stripRule.match(/padding:\s*([\d.]+)rpx\s+(\d+)\s+([\d.]+)rpx;/)
    const labelHeight = readRpx(labelRule, 'font-size') * Number(labelRule.match(/line-height:\s*([\d.]+);/)[1])
    const itemHeight = readRpx(coverRule, 'height') + readRpx(labelRule, 'margin-top') + labelHeight
    const contentHeight = Math.max(
      Number(paddingTop) + itemHeight + Number(paddingBottom),
      readRpx(footerStripRule, 'padding-top') + itemHeight + readRpx(footerStripRule, 'padding-bottom')
    )

    assert.match(wxml, /<scroll-view class="selected-scroll"[^>]*scroll-x/)
    assert.match(wxml, /<scroll-view class="selected-scroll"[^>]*wx:if="\{\{selectedSources.length\}\}"/, '空来源修改只显示提示，不保留空白缩略图高度')
    // Skyline 横向滚动区不能依赖子节点撑高；按真实封面、编号与内边距核对可视高度。
    assert.ok(readRpx(scrollRule, 'height') >= Math.ceil(contentHeight), `横向滚动区须明确预留至少 ${Math.ceil(contentHeight)}rpx，否则缩略图会溢出并覆盖提示文字`)
    assert.match(scrollRule, /flex:\s*none;/, '卡片与底部栏均不得压缩已选内容滚动区')
  })

  test(`${folder} token 用量仅放在结果与终态失败卡片的右下角浅灰小字`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    const candidateCard = wxml.match(/<view class="panel candidate-panel">([\s\S]*?)\n          <\/view>/)[1]
    const failedCard = wxml.match(/<view wx:if="\{\{task.status === 'FAILED' && taskError\}\}" class="panel failure-panel">([\s\S]*?)<\/view>\n/)[1]
    const tokenNode = '<view wx:if="{{tokenUsageText}}" class="token-usage">{{tokenUsageText}}</view>'
    assert.ok(candidateCard.trim().endsWith(tokenNode), '成功、无变化、不可用候选共用结果卡片尾部')
    assert.ok(failedCard.trim().endsWith(tokenNode), '失败用量放在失败说明之后')
    assert.equal(wxml.split(tokenNode).length - 1, 2)
    const generationCard = wxml.match(/<view wx:if="\{\{waiting\}\}" class="panel generation-panel">([^\n]*)/)[1]
    assert.doesNotMatch(generationCard, /tokenUsageText|token-usage/)
    assert.match(wxss, /\.token-usage\s*\{[^}]*margin-top:\s*24rpx;[^}]*color:\s*#8b939b;[^}]*font-size:\s*22rpx;[^}]*font-weight:\s*400;[^}]*text-align:\s*right;/)
  })

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
  const readUtils = folder => fs.readFileSync(path.join(root, 'pages', folder, 'utils', 'advanced-ai-page.js'), 'utf8')
  assert.equal(readUtils('portfolios'), readUtils('team-portfolios'))
})
