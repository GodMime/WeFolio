const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const personal = require('../pages/portfolios/utils/portfolio-text-grid')
const team = require('../pages/team-portfolios/utils/portfolio-text-grid')
const clone = value => JSON.parse(JSON.stringify(value))
const goldenCases = require('../../../tests/fixtures/portfolio-text-grid-golden.json').cases
function filled(rows = 2, columns = 2) { const grid = personal.createTextGrid(rows, columns); grid.cells.forEach((cell, i) => { cell.blocks[0].runs[0].text = String(i) }); return grid }

test('two subpackages use identical grid rules and component rendering implementations', () => {
  const files = ['utils/portfolio-text-grid.js', 'utils/portfolio-component-platform.js']
  for (const name of ['text-grid', 'text-grid-editor']) for (const extension of ['js', 'json', 'wxml', 'wxss']) files.push(`components/${name}/${name}.${extension}`)
  for (const file of files)
    assert.equal(fs.readFileSync(path.join(__dirname, '../pages/portfolios', file), 'utf8'), fs.readFileSync(path.join(__dirname, '../pages/team-portfolios', file), 'utf8'))
})
for (const [scope, api] of [['personal', personal], ['team', team]]) {
  test(`${scope}: paragraph line heights validate without injecting a value into legacy grids`, () => {
    const grid = filled(1, 1)
    const legacy = api.normalizeTextGrid(grid)
    assert.equal(Object.hasOwn(legacy.cells[0].blocks[0], 'lineHeight'), false)
    assert.equal(api.presentTextGrid(legacy, api.layoutTextGrid(legacy, 375, 0.5))[0].blocks[0].lineStyle, '')
    for (const value of [0.5, 0.6, 1, 1.5, 2.7, 3]) {
      grid.cells[0].blocks[0].lineHeight = value
      const normalized = api.normalizeTextGrid(grid)
      assert.equal(api.validateTextGrid(normalized), '')
      assert.equal(normalized.cells[0].blocks[0].lineHeight, value)
    }
    for (const value of [0.4, 3.1, 1.55, '1.5', '', null, undefined, false, {}, NaN, Infinity]) {
      grid.cells[0].blocks[0].lineHeight = value
      assert.match(api.validateTextGrid(grid), /行间距/)
      assert.match(api.validateTextGrid(api.normalizeTextGrid(grid)), /行间距/)
    }
  })
  test(`${scope}: paragraph line heights survive merge, split, resize and undo alongside mixed font sizes`, () => {
    const grid = filled(1, 2)
    grid.cells[0].blocks[0].lineHeight = 0.5
    grid.cells[0].blocks[0].runs[0].fontSizeRpx = 64
    grid.cells[0].blocks[0].runs.push({ ...api.createRun(), text: '年\\n经验', fontSizeRpx: 24 })
    grid.cells[1].blocks[0].lineHeight = 3
    const history = api.createGridHistory(grid)
    const merged = history.apply(api.mergeCells(grid, grid.cells.map(cell => cell.cellKey)))
    assert.deepEqual(merged.cells[0].blocks.map(block => block.lineHeight), [0.5, 3])
    const split = history.apply(api.splitCell(merged, merged.cells[0].cellKey))
    assert.deepEqual(split.cells[0].blocks, merged.cells[0].blocks)
    assert.equal(Object.hasOwn(split.cells[1].blocks[0], 'lineHeight'), false)
    const saved = api.normalizeTextGrid(clone(api.resizeGrid(split, 2, 2)))
    assert.deepEqual(saved.cells[0].blocks, merged.cells[0].blocks)
    const blocks = api.presentTextGrid(saved, api.layoutTextGrid(saved, 375, 0.5))[0].blocks
    assert.match(blocks[0].lineStyle, /line-height: 0.5;/)
    assert.match(blocks[0].lineStyle, /font-size:64rpx;/)
    assert.ok(blocks[0].runs.every(run => run.style.includes('line-height: 0.5;')))
    assert.match(blocks[1].lineStyle, /line-height: 3;/)
    assert.deepEqual(history.undo(), merged)
    assert.deepEqual(history.undo(), grid)
  })
  test(`${scope}: outer spacing defaults preserve legacy grids and explicit invalid values are rejected`, () => {
    const legacy = filled()
    delete legacy.horizontalMarginRpx; delete legacy.verticalMarginRpx
    assert.equal(api.validateTextGrid(legacy), '')
    const normalized = api.normalizeTextGrid(legacy)
    assert.equal(normalized.horizontalMarginRpx, 0); assert.equal(normalized.verticalMarginRpx, 0)
    assert.equal(api.gridSpacingStyle(normalized), 'padding:0rpx 0rpx;')
    for (const field of ['horizontalMarginRpx', 'verticalMarginRpx']) {
      for (const value of [0, 96]) assert.equal(api.validateTextGrid({ ...normalized, [field]: value }), '')
      for (const value of [-1, 97, 1.5, '2', null, undefined, false]) {
        assert.match(api.validateTextGrid(api.normalizeTextGrid({ ...normalized, [field]: value })), /外侧留白/)
      }
    }
  })
  test(`${scope}: outer spacing reduces available width and survives grid operations and JSON roundtrip`, () => {
    const grid = api.createTextGrid(8, 2)
    grid.horizontalMarginRpx = 32; grid.verticalMarginRpx = 24
    grid.cells[0].blocks[0].runs[0].text = '标题'
    const merged = api.mergeCells(grid, grid.cells.slice(0, 2).map(cell => cell.cellKey))
    const saved = api.normalizeTextGrid(clone(merged))
    assert.equal(api.gridSpacingStyle(saved), 'padding:24rpx 32rpx;')
    assert.equal(api.gridContentWidth(saved, 375, 0.5), 343)
    const layout = api.layoutTextGrid(saved, api.gridContentWidth(saved, 375, 0.5), 0.5)
    assert.equal(layout.cells[0].width, 343)
    assert.equal(layout.cells[1].width, 167.5)
    assert.deepEqual(api.splitCell(saved, saved.cells[0].cellKey).rowMinHeightsRpx, grid.rowMinHeightsRpx)
    const history = api.createGridHistory(saved)
    const resized = history.apply(api.resizeGrid(saved, 2, 2))
    assert.equal(resized.horizontalMarginRpx, 32); assert.equal(resized.verticalMarginRpx, 24)
    assert.deepEqual(history.undo(), saved)
    assert.equal(grid.cells.length, 16)
  })
  test(`${scope}: consumes the same repository golden cases as the Java normalizer`, () => {
    for (const sample of goldenCases) {
      assert.equal(api.validateTextGrid(sample.grid) === '', sample.valid, `${sample.name}: draft`)
      assert.equal(api.validateTextGrid(sample.grid, { requireText: true }) === '', sample.publishable, `${sample.name}: publish`)
    }
  })
  test(`${scope}: default grid immediately has an editable first run with font size and weight`, () => {
    const grid = api.createTextGrid()
    assert.equal(api.validateTextGrid(grid), ''); assert.equal(api.validateTextGrid(grid, { requireText: true }), '请至少添加一处文字')
    assert.deepEqual(grid.rowMinHeightsRpx, [180, 180]); assert.equal(grid.cells.length, 4)
    assert.equal(grid.cells[0].blocks[0].runs[0].fontSizeRpx, 28); assert.equal(grid.cells[0].blocks[0].runs[0].fontWeight, 'NORMAL')
  })
  test(`${scope}: font sizes accept every integer from 10 to 96 and survive normalization and rendering`, () => {
    const grid = filled(1, 1), run = grid.cells[0].blocks[0].runs[0]
    for (let size = 10; size <= 96; size++) {
      run.fontSizeRpx = size
      assert.equal(api.validateTextGrid(grid), '')
      const normalized = api.normalizeTextGrid(grid)
      assert.equal(normalized.cells[0].blocks[0].runs[0].fontSizeRpx, size)
      const presented = api.presentTextGrid(normalized, api.layoutTextGrid(normalized, 375, 0.5))
      assert.ok(presented[0].blocks[0].runs[0].style.includes(`font-size:${size}rpx;`))
    }
    for (const size of [9, 97, 10.5, '10', null]) {
      run.fontSizeRpx = size
      assert.match(api.validateTextGrid(grid), /样式/)
    }
  })
  test(`${scope}: eight rows and five columns retain all 40 cells through resize and layout`, () => {
    const original = filled(5, 5)
    for (const rows of [6, 7, 8]) {
      const grid = api.resizeGrid(original, rows, 5)
      assert.equal(api.validateTextGrid(grid, { requireText: true }), '')
      assert.equal(grid.cells.length, rows * 5)
      assert.deepEqual(grid.cells.slice(0, 25), original.cells)
      assert.deepEqual(grid.rowMinHeightsRpx, Array(rows).fill(180))
      const normalized = api.normalizeTextGrid(grid)
      assert.deepEqual(normalized, grid)
      const layout = api.layoutTextGrid(normalized, 375, 0.5)
      assert.equal(layout.cells.length, rows * 5)
      assert.equal(layout.height, rows * 90 + (rows - 1) * 8)
      assert.equal(layout.cells.at(-1).top + layout.cells.at(-1).height, layout.height)
      assert.equal(api.presentTextGrid(normalized, layout).length, rows * 5)
    }
    for (const [rows, columns] of [[9, 5], [8, 6], [0, 5], [8, 0], [8.5, 5], [8, 5.5]]) {
      assert.throws(() => api.resizeGrid(original, rows, columns), /行数须为 1–8，列数须为 1–5/)
      assert.match(api.validateTextGrid({ ...original, rows, columns }), /行数须为 1–8，列数须为 1–5/)
    }
  })
  test(`${scope}: eight-row merges, splits and undo preserve text in the last row`, () => {
    const grid = api.createTextGrid(8, 5)
    grid.cells[35].blocks[0].runs[0].text = '第八行'
    const history = api.createGridHistory(grid)
    const merged = history.apply(api.mergeCells(grid, grid.cells.filter(cell => cell.column === 0).map(cell => cell.cellKey)))
    assert.equal(merged.cells[0].rowSpan, 8)
    assert.equal(merged.cells[0].blocks[0].runs[0].text, '第八行')
    const split = history.apply(api.splitCell(merged, merged.cells[0].cellKey))
    assert.equal(split.cells.length, 40)
    assert.equal(split.cells[0].blocks[0].runs[0].text, '第八行')
    assert.deepEqual(history.undo(), merged)
    assert.deepEqual(history.undo(), grid)
    assert.throws(() => api.resizeGrid(grid, 7, 5), /移走或清空/)
  })
  test(`${scope}: 5×4 first row becomes 17 cells and split keeps rich text at upper left`, () => {
    const grid = filled(5, 4), first = grid.cells[0].blocks[0]
    first.runs[0].fontSizeRpx = 64; first.runs[0].fontWeight = 'BOLD'; first.runs.push({ ...api.createRun(), text: '年', fontSizeRpx: 24 })
    const next = api.mergeCells(grid, grid.cells.slice(0, 4).map(c => c.cellKey))
    assert.equal(next.cells.length, 17); assert.equal(next.cells[0].columnSpan, 4); assert.equal(next.cells[0].blocks.length, 4)
    assert.deepEqual(next.cells[0].blocks[0], first)
    const split = api.splitCell(next, next.cells[0].cellKey)
    assert.equal(split.cells.length, 20); assert.equal(split.cells[0].blocks.length, 4); assert.equal(split.cells[1].blocks[0].runs[0].text, '')
    assert.equal(grid.cells.length, 20)
  })
  test(`${scope}: left merged column has three cells and nonrectangular selection is atomic`, () => {
    const grid = filled(), history = api.createGridHistory(grid)
    assert.throws(() => history.apply(api.mergeCells(grid, [grid.cells[0].cellKey, grid.cells[3].cellKey])), /矩形/)
    assert.equal(history.size(), 0); assert.deepEqual(history.get(), grid)
    const result = history.apply(api.mergeCells(grid, [grid.cells[0].cellKey, grid.cells[2].cellKey]))
    assert.equal(result.cells.length, 3); assert.equal(result.cells[0].rowSpan, 2); assert.deepEqual(history.undo(), grid)
  })
  test(`${scope}: merge checks paragraph and run limits before changing history`, () => {
    let grid = filled(); grid.cells[0].blocks = Array.from({ length: 8 }, () => ({ ...api.createBlock(), runs: [{ ...api.createRun(), text: '段' }] }))
    const history = api.createGridHistory(grid)
    assert.throws(() => history.apply(api.mergeCells(grid, [grid.cells[0].cellKey, grid.cells[1].cellKey])), /8 段/)
    assert.equal(history.size(), 0)
    grid = filled(); grid.cells[0].blocks[0].runs = Array.from({ length: 9 }, () => api.createRun())
    assert.throws(() => api.mergeCells(grid, [grid.cells[0].cellKey, grid.cells[1].cellKey]), /8 个文字片段/)
  })
  test(`${scope}: reduction cannot silently remove text and added rows use 180 rpx`, () => {
    const grid = filled(); assert.throws(() => api.resizeGrid(grid, 1, 2), /移走或清空/)
    const empty = api.createTextGrid(); assert.equal(api.resizeGrid(empty, 1, 1).cells.length, 1)
    assert.deepEqual(api.resizeGrid(empty, 5, 4).rowMinHeightsRpx, [180, 180, 180, 180, 180])
  })
  test(`${scope}: coverage, key uniqueness, value ranges and Unicode counts are strict`, () => {
    const grid = api.createTextGrid(); grid.cells[0].blocks[0].runs[0].text = '😀'.repeat(1998) + ' \n'
    assert.equal(api.countGridText(grid), 2000); assert.equal(api.validateTextGrid(grid), '')
    grid.cells[0].blocks[0].runs[0].text += '😀'; assert.match(api.validateTextGrid(grid), /2000/)
    const overlap = filled(); overlap.cells[1].column = 0; assert.match(api.validateTextGrid(overlap), /重叠/)
    const duplicate = filled(); duplicate.cells[1].cellKey = duplicate.cells[0].cellKey; assert.match(api.validateTextGrid(duplicate), /标识/)
    const bad = filled(); bad.cells[0].blocks[0].runs[0].fontSizeRpx = 97; assert.match(api.validateTextGrid(bad), /样式/)
  })
  test(`${scope}: layout satisfies both single-row and spanning content without reordering`, () => {
    const source = filled(); const grid = api.mergeCells(source, [source.cells[0].cellKey, source.cells[2].cellKey])
    const heights = { [grid.cells[0].cellKey]: 500, [grid.cells[1].cellKey]: 300 }
    const layout = api.layoutTextGrid(grid, 350, 0.5, heights)
    assert.ok(layout.cells[0].height >= 524); assert.ok(layout.cells[1].height >= 324)
    assert.equal(layout.cells[0].left, 0); assert.ok(layout.cells[1].left > 0)
    assert.equal(api.layoutsEqual(layout, clone(layout)), true)
    const near = clone(layout); near.height += 0.9; assert.equal(api.layoutsEqual(layout, near), true)
    const narrow = api.createTextGrid(2, 5); narrow.columnWeights = [1, 4, 4, 4, 4]
    assert.throws(() => api.layoutTextGrid(narrow, 280, 0.5), /宽度不足/)
  })
  test(`${scope}: history is bounded to 40 and failed edits preserve the snapshot`, () => {
    const history = api.createGridHistory(filled())
    for (let i = 0; i < 45; i++) { const next = history.get(); next.cells[0].blocks[0].runs[0].text = String(i); history.apply(next) }
    assert.equal(history.size(), 40)
    const snapshot = history.get(); assert.throws(() => history.apply({}), /行数/); assert.deepEqual(history.get(), snapshot)
    history.clear(); assert.equal(history.size(), 0); assert.deepEqual(history.undo(), snapshot)
  })
  test(`${scope}: mixed runs retain spaces, theme AUTO follows effective cell background`, () => {
    const grid = filled(1, 1); grid.cellBackground = '#000000'
    grid.cells[0].blocks[0].runs = [{ ...api.createRun(), text: '10 ', fontSizeRpx: 64, fontWeight: 'BOLD' }, { ...api.createRun(), text: '年', fontSizeRpx: 24, color: '#212529' }]
    const runs = api.presentTextGrid(grid, api.layoutTextGrid(grid, 300, 0.5), 'light')[0].blocks[0].runs
    assert.match(runs[0].style, /font-size:64rpx;font-weight:700;color:#F2F3F3/); assert.equal(runs[0].text, '10 '); assert.match(runs[1].style, /font-size:24rpx;font-weight:400;color:#212529/)
  })
  test(`${scope}: split inherits vertical alignment for every replacement cell`, () => {
    const source = filled(); source.cells[0].verticalAlignment = 'BOTTOM'
    const merged = api.mergeCells(source, source.cells.map(cell => cell.cellKey))
    const result = api.splitCell(merged, merged.cells[0].cellKey)
    assert.equal(result.cells.length, 4); assert.ok(result.cells.every(cell => cell.verticalAlignment === 'BOTTOM'))
    assert.deepEqual(result.cells[0].blocks, merged.cells[0].blocks)
    assert.ok(result.cells.slice(1).every(cell => !api.hasText(cell)))
  })
  test(`${scope}: effective cell colors use the approved prototype threshold and border palette`, () => {
    const grid = filled(1, 1); grid.cellBorder = true; grid.cellBorderColor = 'AUTO'
    for (const [background, theme, fill, foreground, border] of [
      ['AUTO', 'light', '#F5F6F7', '#212529', '#D7DADD'], ['AUTO', 'dark', '#202123', '#F2F3F3', '#45464A'],
      ['#7F7F7F', 'light', '#7F7F7F', '#F2F3F3', '#45464A'], ['#808080', 'dark', '#808080', '#212529', '#D7DADD']
    ]) {
      grid.cellBackground = background
      const cell = api.presentTextGrid(grid, api.layoutTextGrid(grid, 375, 0.5), theme)[0]
      assert.ok(cell.style.includes(`background:${fill};`)); assert.ok(cell.style.includes(`solid ${border};`))
      assert.ok(cell.blocks[0].runs[0].style.includes(`color:${foreground};`))
    }
  })
  test(`${scope}: zero padding is valid and nonpositive text width is rejected`, () => {
    const grid = filled(1, 1); grid.cellPaddingRpx = 0
    assert.equal(api.validateTextGrid(grid), ''); assert.equal(api.layoutTextGrid(grid, 1, 1).cells[0].contentWidth, 1)
    grid.cellPaddingRpx = -1; assert.match(api.validateTextGrid(grid), /内边距/)
    grid.cellPaddingRpx = 24
    assert.throws(() => api.layoutTextGrid(grid, 48, 1), /宽度不足/)
    assert.throws(() => api.layoutTextGrid(grid, 47, 1), /宽度不足/)
    assert.equal(api.layoutTextGrid(grid, 49, 1).cells[0].contentWidth, 1)
  })
  test(`${scope}: blank cleanup and shrinking share the publishing whitespace rule`, () => {
    const grid = api.createTextGrid(1, 2); grid.cells[0].blocks[0].runs[0].text = '有效'
    grid.cells[1].blocks[0].runs[0].text = '\u00a0\ufeff'
    assert.equal(api.mergeCells(grid, grid.cells.map(cell => cell.cellKey)).cells[0].blocks.length, 1)
    assert.equal(api.resizeGrid(grid, 1, 1).cells.length, 1)
    grid.cells[1].blocks[0].runs[0].text = '\u200b'
    assert.throws(() => api.resizeGrid(grid, 1, 1), /清空/)
  })
}

test('stable cell, block and run keys reject null, undefined and numeric coercions', () => {
  for (const api of [personal, team]) for (const value of [undefined, null, 123]) {
    const cell = api.createTextGrid(); cell.cells[0].cellKey = value; assert.match(api.validateTextGrid(cell), /标识/)
    const block = api.createTextGrid(); block.cells[0].blocks[0].blockKey = value; assert.match(api.validateTextGrid(block), /样式/)
    const run = api.createTextGrid(); run.cells[0].blocks[0].runs[0].runKey = value; assert.match(api.validateTextGrid(run), /样式/)
  }
})

for (const [scope, api] of [['personal', personal], ['team', team]]) {
  test(`${scope}: new borders default to gray while omitted legacy fields preserve adaptive colors`, () => {
    const created = api.createTextGrid(1, 1)
    assert.equal(created.cellBorderWidthRpx, 1)
    assert.equal(created.cellBorderColor, '#D7DADD')
    assert.equal(api.resolveTextGridBorderColor(created, 'dark'), '#D7DADD')
    const legacy = clone(created)
    delete legacy.cellBorderWidthRpx; delete legacy.cellBorderColor
    const normalized = api.normalizeTextGrid(legacy)
    assert.equal(normalized.cellBorderWidthRpx, 1)
    assert.equal(normalized.cellBorderColor, 'AUTO')
    assert.equal(api.resolveTextGridBorderColor(normalized, 'light'), '#D7DADD')
    assert.equal(api.resolveTextGridBorderColor(normalized, 'dark'), '#45464A')
    assert.equal(Object.hasOwn(legacy, 'cellBorderColor'), false)
    normalized.cellBackground = '#FFFFFF'
    assert.equal(api.resolveTextGridBorderColor(normalized, 'dark'), '#D7DADD')
    normalized.cellBackground = '#000000'
    assert.equal(api.resolveTextGridBorderColor(normalized, 'light'), '#45464A')
  })
  test(`${scope}: border normalization retains explicit custom colors and rejects invalid values`, () => {
    const grid = api.createTextGrid(1, 1)
    grid.cellBorderWidthRpx = 12; grid.cellBorderColor = '#aBcDeF'
    const normalized = api.normalizeTextGrid(grid)
    assert.equal(api.validateTextGrid(normalized), '')
    assert.equal(normalized.cellBorderWidthRpx, 12)
    assert.equal(normalized.cellBorderColor, '#ABCDEF')
    for (const value of [0, 13, 1.5, '2', null, undefined]) {
      const invalid = api.normalizeTextGrid({ ...grid, cellBorderWidthRpx: value })
      assert.match(api.validateTextGrid(invalid), /边框宽度/)
    }
    for (const value of ['', '#123', 'red', 'auto', 123, null, undefined]) {
      const invalid = api.normalizeTextGrid({ ...grid, cellBorderColor: value })
      assert.match(api.validateTextGrid(invalid), /边框颜色/)
    }
  })
  test(`${scope}: enabled border width participates in text measurement and turning it off preserves settings`, () => {
    const grid = api.createTextGrid(1, 1)
    grid.cellPaddingRpx = 0; grid.rowMinHeightsRpx = [80]
    grid.cellBorder = true; grid.cellBorderWidthRpx = 12; grid.cellBorderColor = '#000000'
    const measured = { [grid.cells[0].cellKey]: 100 }
    const layout = api.layoutTextGrid(grid, 300, 0.5, measured)
    assert.equal(layout.cells[0].contentWidth, 288)
    assert.equal(layout.cells[0].height, 112)
    assert.match(api.presentTextGrid(grid, layout, 'dark')[0].style, /border:12rpx solid #000000;/)
    assert.throws(() => api.layoutTextGrid(grid, 12, 0.5), /宽度不足/)
    grid.cellBorder = false
    const disabled = api.layoutTextGrid(grid, 300, 0.5, measured)
    assert.equal(disabled.cells[0].contentWidth, 300)
    assert.equal(disabled.cells[0].height, 100)
    assert.match(api.presentTextGrid(grid, disabled, 'light')[0].style, /border:0rpx solid #000000;/)
    assert.equal(grid.cellBorderWidthRpx, 12); assert.equal(grid.cellBorderColor, '#000000')
    grid.cellBorder = true
    assert.equal(api.layoutTextGrid(grid, 300, 0.5, measured).cells[0].contentWidth, 288)
  })
}
