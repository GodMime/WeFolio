const { buildRemoteFontStyle } = require('../../../utils/portfolio-text-typography.js')
// 两分包保留本地实现；一致性测试约束结构、编辑操作和 Skyline 布局结果。
const { PORTFOLIO_TEXT_LINE_HEIGHT_ERROR, isValidPortfolioTextLineHeight, buildPortfolioTextLineHeightStyle } = require('../../../utils/portfolio-text-typography.js')
const MAX_ROWS = 8
const MAX_COLUMNS = 5
const DIMENSIONS_ERROR = `行数须为 1–${MAX_ROWS}，列数须为 1–${MAX_COLUMNS}`
const MAX_BLOCKS = 8
const MAX_RUNS = 8
const MAX_TEXT = 2000
const MIN_FONT_SIZE = 10
const MAX_FONT_SIZE = 96
const DEFAULT_LINE_HEIGHT = 1.5
const UNDO_LIMIT = 40
const MIN_ROW_HEIGHT = 180
const DEFAULT_BORDER_WIDTH_RPX = 1
const MAX_BORDER_WIDTH_RPX = 12
const MAX_OUTER_MARGIN_RPX = 96
const OUTER_MARGIN_FIELDS = ['horizontalMarginRpx', 'verticalMarginRpx']
const CELL_BACKGROUND_LIGHT = '#F5F6F7'
const CELL_BACKGROUND_DARK = '#202123'
const CELL_FOREGROUND_LIGHT = '#212529'
const CELL_FOREGROUND_DARK = '#F2F3F3'
const CELL_BORDER_LIGHT = '#D7DADD'
const CELL_BORDER_DARK = '#45464A'
const COLOR_PATTERN = /^(AUTO|#[0-9A-Fa-f]{6})$/
const KEY_PATTERN = /^[A-Za-z0-9_-]{1,64}$/
const copy = value => JSON.parse(JSON.stringify(value))
const owns = (value, name) => Object.prototype.hasOwnProperty.call(value, name)
const borderWidth = grid => owns(grid, 'cellBorderWidthRpx') ? grid.cellBorderWidthRpx : DEFAULT_BORDER_WIDTH_RPX
const borderColor = grid => owns(grid, 'cellBorderColor') ? grid.cellBorderColor : 'AUTO'
const outerMargin = (grid, field) => owns(grid, field) ? grid[field] : 0
const integer = (value, min, max) => Number.isInteger(value) && value >= min && value <= max
const key = prefix => `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`
const order = (a, b) => a.row - b.row || a.column - b.column
const hasText = cell => (cell.blocks || []).some(block => (block.runs || []).some(run => String(run.text || '').trim()))

function createRun() {
  return { runKey: key('run'), text: '', fontFamily: 'SYSTEM', fontSizeRpx: 28, fontWeight: 'NORMAL', color: 'AUTO' }
}
function createBlock() {
  return { blockKey: key('block'), alignment: 'CENTER', marginTopRpx: 0, marginBottomRpx: 12, runs: [createRun()] }
}
function createCell(row, column) {
  return { cellKey: key('cell'), row, column, rowSpan: 1, columnSpan: 1, verticalAlignment: 'CENTER', blocks: [createBlock()] }
}
function createTextGrid(rows = 2, columns = 2) {
  return { rows, columns, columnWeights: Array(columns).fill(1), rowMinHeightsRpx: Array(rows).fill(MIN_ROW_HEIGHT),
    gapRpx: 16, cellPaddingRpx: 24, cellRadiusRpx: 24, cellBorder: false, cellBackground: 'AUTO',
    cellBorderWidthRpx: DEFAULT_BORDER_WIDTH_RPX, cellBorderColor: CELL_BORDER_LIGHT,
    horizontalMarginRpx: 0, verticalMarginRpx: 0,
    cells: Array.from({ length: rows * columns }, (_, i) => createCell(Math.floor(i / columns), i % columns)) }
}
function normalizeTextGrid(raw = {}) {
  if (!raw || !Object.keys(raw).length) return createTextGrid()
  // 非法输入交给校验，不截断用户文字，也不以默认布局覆盖错误结构。
  const source = copy(raw)
  const result = {}
  ;['rows', 'columns', 'columnWeights', 'rowMinHeightsRpx', 'gapRpx', 'cellPaddingRpx', 'cellRadiusRpx', 'cellBorder', 'cellBackground', 'cells']
    .forEach(name => { if (Object.prototype.hasOwnProperty.call(source, name)) result[name] = source[name] })
  // 显式 undefined 也属于非法配置，不能在 JSON 克隆后误当作历史缺省值。
  if (Array.isArray(raw.cells)) raw.cells.forEach((cell, cellIndex) => {
    if (cell && Array.isArray(cell.blocks)) cell.blocks.forEach((block, blockIndex) => {
      if (block && owns(block, 'lineHeight') && block.lineHeight === undefined) result.cells[cellIndex].blocks[blockIndex].lineHeight = undefined
    })
  })
  // 历史配置缺省保留自适应线色；显式非法值继续交给校验，不能被默认值掩盖。
  result.cellBorderWidthRpx = borderWidth(raw)
  const color = borderColor(raw)
  result.cellBorderColor = typeof color === 'string' && COLOR_PATTERN.test(color) ? color.toUpperCase() : color
  OUTER_MARGIN_FIELDS.forEach(field => { result[field] = outerMargin(raw, field) })
  return result
}
function countGridText(grid) {
  return (grid.cells || []).reduce((total, cell) => total + (cell.blocks || []).reduce((count, block) =>
    count + (block.runs || []).reduce((n, run) => n + Array.from(String(run.text || '')).length, 0), 0), 0)
}
function validateTextGrid(grid, { requireText = false } = {}) {
  if (!grid || !integer(grid.rows, 1, MAX_ROWS) || !integer(grid.columns, 1, MAX_COLUMNS)) return DIMENSIONS_ERROR
  if (!Array.isArray(grid.columnWeights) || grid.columnWeights.length !== grid.columns || grid.columnWeights.some(n => !integer(n, 1, 4))) return '每列宽度比例须为 1–4'
  if (!Array.isArray(grid.rowMinHeightsRpx) || grid.rowMinHeightsRpx.length !== grid.rows || grid.rowMinHeightsRpx.some(n => !integer(n, 80, 600))) return '最小行高须为 80–600 rpx'
  if (['gapRpx', 'cellPaddingRpx', 'cellRadiusRpx'].some(name => !integer(grid[name], 0, 48))) return '间距、内边距和圆角须为 0–48 rpx'
  if (typeof grid.cellBorder !== 'boolean' || !COLOR_PATTERN.test(grid.cellBackground)) return '请设置有效的网格外观'
  if (!integer(borderWidth(grid), DEFAULT_BORDER_WIDTH_RPX, MAX_BORDER_WIDTH_RPX)) return '边框宽度须为 1–12 rpx'
  if (typeof borderColor(grid) !== 'string' || !COLOR_PATTERN.test(borderColor(grid))) return '请选择有效的边框颜色'
  if (OUTER_MARGIN_FIELDS.some(field => !integer(outerMargin(grid, field), 0, MAX_OUTER_MARGIN_RPX))) return '外侧留白须为 0–96 rpx'
  if (!Array.isArray(grid.cells) || !grid.cells.length || grid.cells.length > MAX_ROWS * MAX_COLUMNS) return '网格单元格无效'
  const coverage = Array(grid.rows * grid.columns).fill(0)
  const keys = new Set()
  function validKey(value) { if (typeof value !== 'string' || !KEY_PATTERN.test(value) || keys.has(value)) return false; keys.add(value); return true }
  for (const cell of grid.cells) {
    if (!cell || !validKey(cell.cellKey) || !integer(cell.row, 0, grid.rows - 1) || !integer(cell.column, 0, grid.columns - 1)
      || !integer(cell.rowSpan, 1, grid.rows - cell.row) || !integer(cell.columnSpan, 1, grid.columns - cell.column)) return '单元格位置或标识无效'
    if (!['TOP', 'CENTER', 'BOTTOM'].includes(cell.verticalAlignment)) return '请选择垂直对齐方式'
    for (let r = cell.row; r < cell.row + cell.rowSpan; r++) for (let c = cell.column; c < cell.column + cell.columnSpan; c++) coverage[r * grid.columns + c]++
    if (!Array.isArray(cell.blocks) || !cell.blocks.length || cell.blocks.length > MAX_BLOCKS) return '每格最多 8 段文字'
    for (const block of cell.blocks) {
      if (!block || !validKey(block.blockKey) || !['LEFT', 'CENTER', 'RIGHT'].includes(block.alignment)
        || !integer(block.marginTopRpx, 0, 128) || !integer(block.marginBottomRpx, 0, 128)) return '段落样式无效'
      if (owns(block, 'lineHeight') && !isValidPortfolioTextLineHeight(block.lineHeight)) return PORTFOLIO_TEXT_LINE_HEIGHT_ERROR
      if (!Array.isArray(block.runs) || !block.runs.length || block.runs.length > MAX_RUNS) return '每段最多 8 个文字片段'
      for (const run of block.runs) {
        if (!run || !validKey(run.runKey) || typeof run.text !== 'string' || !['SYSTEM', 'WECHAT_SANS_SS'].includes(run.fontFamily)
          || !integer(run.fontSizeRpx, MIN_FONT_SIZE, MAX_FONT_SIZE) || !['NORMAL', 'BOLD'].includes(run.fontWeight) || !COLOR_PATTERN.test(run.color)) return '文字片段样式无效'
      }
    }
  }
  if (coverage.some(n => n !== 1)) return '单元格必须完整覆盖网格且不能重叠'
  if (countGridText(grid) > MAX_TEXT) return '文字网格最多 2000 字'
  return requireText && !grid.cells.some(hasText) ? '请至少添加一处文字' : ''
}
function requireValid(grid) { const error = validateTextGrid(grid); if (error) throw new Error(error); return grid }
function mergeCells(grid, selectedKeys) {
  requireValid(grid)
  const selected = grid.cells.filter(cell => selectedKeys.includes(cell.cellKey)).sort(order)
  if (selected.length < 2) throw new Error('请选择至少两个完整单元格')
  const top = Math.min(...selected.map(cell => cell.row)), left = Math.min(...selected.map(cell => cell.column))
  const bottom = Math.max(...selected.map(cell => cell.row + cell.rowSpan)), right = Math.max(...selected.map(cell => cell.column + cell.columnSpan))
  if (selected.reduce((n, cell) => n + cell.rowSpan * cell.columnSpan, 0) !== (bottom - top) * (right - left)) throw new Error('请选择连续完整的矩形区域')
  const blocks = selected.flatMap(cell => cell.blocks.filter(block => block.runs.some(run => run.text.trim())))
  if (blocks.length > MAX_BLOCKS || blocks.some(block => block.runs.length > MAX_RUNS)) throw new Error('合并后超过 8 段或每段 8 片段，请先整理文字')
  const merged = { ...copy(selected[0]), row: top, column: left, rowSpan: bottom - top, columnSpan: right - left, blocks: blocks.length ? copy(blocks) : [createBlock()] }
  return requireValid({ ...copy(grid), cells: [...grid.cells.filter(cell => !selectedKeys.includes(cell.cellKey)).map(copy), merged].sort(order) })
}
function splitCell(grid, cellKey) {
  requireValid(grid)
  const cell = grid.cells.find(item => item.cellKey === cellKey)
  if (!cell) throw new Error('请选择单元格')
  const cells = grid.cells.filter(item => item.cellKey !== cellKey).map(copy)
  for (let r = cell.row; r < cell.row + cell.rowSpan; r++) for (let c = cell.column; c < cell.column + cell.columnSpan; c++) {
    cells.push(r === cell.row && c === cell.column ? { ...copy(cell), rowSpan: 1, columnSpan: 1 }
      : { ...createCell(r, c), verticalAlignment: cell.verticalAlignment })
  }
  return requireValid({ ...copy(grid), cells: cells.sort(order) })
}
function resizeGrid(grid, rows, columns) {
  requireValid(grid)
  if (!integer(rows, 1, MAX_ROWS) || !integer(columns, 1, MAX_COLUMNS)) throw new Error(DIMENSIONS_ERROR)
  if (grid.cells.some(cell => (cell.row + cell.rowSpan > rows || cell.column + cell.columnSpan > columns) && hasText(cell))) throw new Error('请先移走或清空范围外的文字')
  const cells = grid.cells.filter(cell => cell.row < rows && cell.column < columns).map(cell => ({ ...copy(cell),
    rowSpan: Math.min(cell.rowSpan, rows - cell.row), columnSpan: Math.min(cell.columnSpan, columns - cell.column) }))
  for (let r = 0; r < rows; r++) for (let c = 0; c < columns; c++) {
    if (!cells.some(cell => r >= cell.row && r < cell.row + cell.rowSpan && c >= cell.column && c < cell.column + cell.columnSpan)) cells.push(createCell(r, c))
  }
  return requireValid({ ...copy(grid), rows, columns, columnWeights: Array.from({ length: columns }, (_, i) => grid.columnWeights[i] || 1),
    rowMinHeightsRpx: Array.from({ length: rows }, (_, i) => grid.rowMinHeightsRpx[i] || MIN_ROW_HEIGHT), cells: cells.sort(order) })
}
function createGridHistory(grid) {
  let current = copy(grid), history = [], sequence = 0, currentKey = 0
  // 历史身份不写入配置；页面用它协调相同文字状态下不同的根版本快照。
  return { get: () => copy(current), key: () => currentKey, size: () => history.length,
    apply(next) { requireValid(next); history.push({ config: current, key: currentKey }); history = history.slice(-UNDO_LIMIT); current = copy(next); currentKey = ++sequence; return copy(current) },
    undo() { if (history.length) { const previous = history.pop(); current = previous.config; currentKey = previous.key } return copy(current) }, clear() { history = [] } }
}
/** 留白由外层占位，内部测量节点始终保持真实内容宽度。 */
function gridSpacingStyle(grid) {
  return `padding:${outerMargin(grid, 'verticalMarginRpx')}rpx ${outerMargin(grid, 'horizontalMarginRpx')}rpx;`
}
function gridContentWidth(grid, outerWidthPx, rpxScale) {
  return outerWidthPx - 2 * outerMargin(grid, 'horizontalMarginRpx') * rpxScale
}
/** 输入为扣除外侧留白后的内容宽度，输出使用布局像素；先满足单行再补足跨行格。 */
function layoutTextGrid(grid, widthPx, rpxScale, measuredHeights = {}) {
  requireValid(grid)
  if (!(widthPx > 0) || !(rpxScale > 0)) throw new Error('网格宽度无效')
  const gap = grid.gapRpx * rpxScale, padding = grid.cellPaddingRpx * rpxScale, border = grid.cellBorder ? borderWidth(grid) * rpxScale : 0
  const available = widthPx - gap * (grid.columns - 1), weightSum = grid.columnWeights.reduce((a, b) => a + b, 0)
  const widths = grid.columnWeights.map(weight => available * weight / weightSum)
  const heights = grid.rowMinHeightsRpx.map(height => height * rpxScale)
  const sum = (list, start, span) => list.slice(start, start + span).reduce((a, b) => a + b, 0) + gap * (span - 1)
  const cells = grid.cells.slice().sort(order).map(cell => {
    const width = sum(widths, cell.column, cell.columnSpan)
    if (width - 2 * (padding + border) <= 0) throw new Error('文字可用宽度不足，请减小内边距或调整列宽')
    return { ...copy(cell), width, contentWidth: width - 2 * (padding + border), left: widths.slice(0, cell.column).reduce((a, b) => a + b, 0) + gap * cell.column }
  })
  cells.slice().sort((a, b) => a.rowSpan - b.rowSpan || order(a, b)).forEach(cell => {
    const measured = measuredHeights[cell.cellKey]
    if (!(measured >= 0)) return
    const deficit = measured + 2 * (padding + border) - sum(heights, cell.row, cell.rowSpan)
    if (deficit > 0) for (let r = cell.row; r < cell.row + cell.rowSpan; r++) heights[r] += deficit / cell.rowSpan
  })
  return { width: widthPx, height: sum(heights, 0, grid.rows), rowHeights: heights,
    cells: cells.map(cell => ({ ...cell, top: heights.slice(0, cell.row).reduce((a, b) => a + b, 0) + gap * cell.row, height: sum(heights, cell.row, cell.rowSpan) })) }
}
function layoutsEqual(a, b) {
  return !!a && !!b && a.cells.length === b.cells.length && Math.abs(a.height - b.height) <= 1 && a.cells.every((cell, i) =>
    cell.cellKey === b.cells[i].cellKey && ['left', 'top', 'width', 'height'].every(name => Math.abs(cell[name] - b.cells[i][name]) <= 1))
}
function isDarkBackground(background) {
  const values = background.slice(1).match(/../g).map(value => parseInt(value, 16))
  return (values[0] * 299 + values[1] * 587 + values[2] * 114) / 1000 < 128
}
function resolveTextGridBackground(grid, themeMode) {
  return grid.cellBackground === 'AUTO' ? (themeMode === 'dark' ? CELL_BACKGROUND_DARK : CELL_BACKGROUND_LIGHT) : grid.cellBackground
}
/** 旧 AUTO 边框沿用有效底色的深浅规则，编辑器展示实际线色但不改写原配置。 */
function resolveTextGridBorderColor(grid, themeMode = 'light') {
  const color = borderColor(grid)
  return color === 'AUTO' ? (isDarkBackground(resolveTextGridBackground(grid, themeMode)) ? CELL_BORDER_DARK : CELL_BORDER_LIGHT) : color
}
function presentTextGrid(grid, layout, themeMode = 'light', fontContext = {}) {
  const background = resolveTextGridBackground(grid, themeMode)
  const dark = isDarkBackground(background)
  const color = dark ? CELL_FOREGROUND_DARK : CELL_FOREGROUND_LIGHT
  const lineColor = resolveTextGridBorderColor(grid, themeMode)
  return layout.cells.map(cell => ({ ...cell,
    style: `left:${cell.left}px;top:${cell.top}px;width:${cell.width}px;height:${cell.height}px;padding:${grid.cellPaddingRpx}rpx;border-radius:${grid.cellRadiusRpx}rpx;background:${background};border:${grid.cellBorder ? borderWidth(grid) : 0}rpx solid ${lineColor};justify-content:${{ TOP: 'flex-start', CENTER: 'center', BOTTOM: 'flex-end' }[cell.verticalAlignment]};`,
    blocks: cell.blocks.map(block => ({ ...block,
      style: `text-align:${block.alignment.toLowerCase()};padding-top:${block.marginTopRpx}rpx;padding-bottom:${block.marginBottomRpx}rpx;`,
      // 显式行高同时作用于行容器和各字号片段，避免继承后的固定像素值压住混合字号。
      lineStyle: isValidPortfolioTextLineHeight(block.lineHeight) ? `font-size:${Math.max(...block.runs.map(run => run.fontSizeRpx))}rpx;${buildPortfolioTextLineHeightStyle(block.lineHeight)}` : '',
      runs: block.runs.map(run => ({ ...run, fontClass: !run.fontId && run.fontFamily === 'WECHAT_SANS_SS' ? 'font-wechat-sans-ss' : 'font-system',
        style: buildRemoteFontStyle(run, fontContext) + `font-size:${run.fontSizeRpx}rpx;font-weight:${run.fontWeight === 'BOLD' ? 700 : 400};color:${run.color === 'AUTO' ? color : run.color};${buildPortfolioTextLineHeightStyle(block.lineHeight)}` })) })) }))
}
module.exports = { MAX_TEXT, MIN_FONT_SIZE, MAX_FONT_SIZE, DEFAULT_LINE_HEIGHT, createTextGrid, createCell, createBlock, createRun, normalizeTextGrid, validateTextGrid, countGridText,
  mergeCells, splitCell, resizeGrid, createGridHistory, layoutTextGrid, layoutsEqual, presentTextGrid, resolveTextGridBorderColor, hasText,
  gridSpacingStyle, gridContentWidth }
