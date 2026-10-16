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

  test(`${folder} A 版描述卡先写描述，再以单行示例和选填附件入口补充`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    const markers = ['<textarea class="prompt-input"', 'class="counter ', 'class="prompt-example"', 'class="reference-section"']
    const positions = markers.map(marker => wxml.indexOf(marker))
    assert.ok(positions.every(position => position >= 0), '描述、字数、示例与附件入口均应保留')
    assert.ok(positions.every((position, index) => index === 0 || position > positions[index - 1]), '顺序应为描述输入、字数、单行示例、选填附件')
    const example = wxml.slice(positions[2], positions[3])
    assert.match(example, /查看示例/)
    assert.doesNotMatch(example, /class="example-copy"|createPromptExample/, '长示例只放在示例弹层中，避免默认描述卡被撑高')
    const exampleRule = readRule(wxss, '.prompt-example')
    assert.match(exampleRule, /display:\s*flex;/)
    assert.match(exampleRule, /justify-content:\s*space-between;/)
    const attachmentRule = readRule(wxss, '.reference-section')
    assert.match(attachmentRule, /border-top:\s*(?!0(?:;|\s))[^;]+;/, '附件入口与描述区通过细分隔线连接')
    assert.doesNotMatch(attachmentRule, /border-bottom:\s*(?!0(?:;|\s))[^;]+;/)
    const addButton = wxml.match(/<button\b[^>]*bindtap="handleOpenReferencePicker"[^>]*>/)?.[0]
    assert.ok(addButton, '附件入口应先打开规格说明与选择面板')
    assert.doesNotMatch(addButton, /class="[^"]*(?:full-width|secondary-button)/)
    assert.match(wxml, /添加参考附件/)
    assert.match(example, /bindtap="handleOpenReferenceExample"/)
  })

  test(`${folder} A 版附件卡提供编号角标、可收起参考重点和引用入口`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    assert.match(wxml, /class="reference-number"[^>]*>\{\{item\.referenceNo\}\}/)
    assert.match(readRule(wxss, '.reference-number'), /position:\s*absolute;/)
    const itemRule = readRule(wxss, '.reference-item')
    assert.match(itemRule, /border:\s*(?!0(?:;|\s))[^;]+;/)
    assert.match(itemRule, /border-radius:\s*(?!0(?:;|\s))[^;]+;/)
    const note = wxml.match(/<textarea\b[^>]*class="reference-note"[^>]*>/)?.[0]
    assert.ok(note, '参考重点采用可换行输入，不常驻占用附件卡空间')
    assert.match(note, /wx:if="\{\{referenceNoteOpen\[item\.localKey\]\}\}"/)
    assert.match(wxml, /bindtap="handleToggleReferenceNote"/)
    assert.match(wxml, /bindtap="handleQuoteReference"/)
  })

  test(`${folder} 参考附件选择面板说明规格并分别选择图片或视频`, () => {
    const wxml = read(folder, 'wxml')
    assert.match(wxml, /class="reference-overlay"/)
    assert.match(wxml, /class="reference-sheet"/)
    assert.match(wxml, /referenceSheet/)
    const choiceButtons = Array.from(wxml.matchAll(/<button\b[^>]*bindtap="handleChooseReference"[^>]*>/g), match => match[0])
    assert.equal(choiceButtons.length, 2, '选择面板应包含图片和视频两个入口')
    assert.deepEqual(choiceButtons.map(button => button.match(/data-media-type="([^"]+)"/)?.[1]).sort(), ['image', 'video'])
    assert.match(wxml, /referenceUi\.blocked/)
  })

  test(`${folder} 选择页保留双列封面、稳定编号与独立底部操作`, () => {
    const wxml = read(folder, 'wxml')
    const wxss = read(folder, 'wxss')
    assert.match(wxml, /<\/scroll-view>\s*<view class="ai-footer">/, '底部操作位于主滚动区外；附件面板可以有独立滚动区')
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
