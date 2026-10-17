const { PORTFOLIO_TEXT_SELECTION_OPTIONS, applyPortfolioFontSelection } = require('../../utils/portfolio-component-platform')
const { MAX_TEXT, MIN_FONT_SIZE, MAX_FONT_SIZE, DEFAULT_LINE_HEIGHT, normalizeTextGrid, validateTextGrid, countGridText, mergeCells, splitCell, resizeGrid, createGridHistory, createBlock, createRun, layoutTextGrid, resolveTextGridBorderColor, gridContentWidth } = require('../../utils/portfolio-text-grid')
const { getPortfolioFontCapability, isPortfolioFontAvailable } = require('../../utils/portfolio-component-platform')
const { PORTFOLIO_TEXT_LINE_HEIGHT_STEP, PORTFOLIO_TEXT_LINE_HEIGHT_ERROR, isValidPortfolioTextLineHeight, parsePortfolioTextLineHeightInput, stepPortfolioTextLineHeight, buildPortfolioTextLineHeightEditor } = require('../../utils/portfolio-component-platform')
const clone = value => JSON.parse(JSON.stringify(value))
// 校验使用访客视口扣除外侧留白的宽度；窄编辑面板内预览不决定配置可用性。
const DISPLAY_WIDTH_RPX = 750
const RPX_STEP = 1
// 所有尺寸按钮共用步长和范围，段落、片段及行高仍分别修改当前编辑对象。
const RPX_FIELDS = Object.freeze({
  fontSizeRpx: { min: MIN_FONT_SIZE, max: MAX_FONT_SIZE, tab: 'content', scope: 'run' },
  marginTopRpx: { min: 0, max: 128, tab: 'content', scope: 'block' },
  marginBottomRpx: { min: 0, max: 128, tab: 'content', scope: 'block' },
  horizontalMarginRpx: { min: 0, max: 96, tab: 'appearance' },
  verticalMarginRpx: { min: 0, max: 96, tab: 'appearance' },
  gapRpx: { min: 0, max: 48, tab: 'appearance' },
  cellPaddingRpx: { min: 0, max: 48, tab: 'appearance' },
  cellRadiusRpx: { min: 0, max: 48, tab: 'appearance' },
  cellBorderWidthRpx: { min: 1, max: 12, tab: 'appearance' },
  rowMinHeightsRpx: { min: 80, max: 600, tab: 'appearance', scope: 'row' }
})
// 拆分只作用于唯一选中的合并格，不能沿用取消选中前的内容编辑焦点。
function findSplitCell(draft, selectedKeys) {
  if (selectedKeys.length !== 1) return null
  const cell = draft.cells.find(item => item.cellKey === selectedKeys[0])
  return cell && (cell.rowSpan > 1 || cell.columnSpan > 1) ? cell : null
}
Component({
  properties: {
    fontContext: { type: Object, value: {} },
    moreFontsExpanded: { type: Boolean, value: false },
    fontEditSessionId: { type: Number, value: 0 },
    fontMessage: { type: String, value: '' },
    fontOptions: { type: Array, value: [] },
    remoteFontAvailable: { type: Boolean, value: false }, visible: { type: Boolean, value: false }, config: { type: Object, value: {} }, themeMode: { type: String, value: 'light' }, backgroundColor: { type: String, value: '#FFFFFF' } },
  data: { draft: {}, tab: 'layout', scrollTop: 0, previewThemeMode: 'light', selectedKeys: [], cell: {}, block: {}, run: {}, cellIndex: 0, blockIndex: 0, runIndex: 0,
    cells: [], count: 0, undoCount: 0, hasInvalidDraft: false, lineHeightEditor: buildPortfolioTextLineHeightEditor(undefined, DEFAULT_LINE_HEIGHT), canSplitCell: false, borderColor: '#D7DADD', inputRenderKeys: [0], rpxStep: RPX_STEP, rpxFields: RPX_FIELDS, error: '', tabs: [{ value: 'layout', label: '布局' }, { value: 'content', label: '内容' }, { value: 'appearance', label: '外观' }],
    fonts: PORTFOLIO_TEXT_SELECTION_OPTIONS.map(item => ({ ...item, available: item.value === 'SYSTEM' })),
    weights: [{ value: 'NORMAL', label: '常规 400' }, { value: 'BOLD', label: '粗体 700' }],
    alignments: [{ value: 'LEFT', label: '左' }, { value: 'CENTER', label: '中' }, { value: 'RIGHT', label: '右' }],
    verticals: [{ value: 'TOP', label: '顶' }, { value: 'CENTER', label: '中' }, { value: 'BOTTOM', label: '底' }],
    appearance: [{ field: 'gapRpx', label: '格子间距' }, { field: 'cellPaddingRpx', label: '格子内边距' }, { field: 'cellRadiusRpx', label: '圆角' }],
    outerSpacing: [{ field: 'horizontalMarginRpx', label: '左右留白' }, { field: 'verticalMarginRpx', label: '上下留白' }]
  },
  observers: {
    draft(value) { if (this.properties.visible) this.triggerEvent('fontdraft', { componentType: 'TEXT_GRID', config: value, sessionId: this.properties.fontEditSessionId, historyKey: this._history && this._history.key(), operation: this.fontUndo ? 'undo' : 'edit' }) },
    fontOptions(options) { if (options && options.length) this.setData({ fonts: clone(options) }) },
    remoteFontAvailable(value) {
      this.setData({ fonts: ((this.properties.fontOptions || []).length ? this.properties.fontOptions : PORTFOLIO_TEXT_SELECTION_OPTIONS).map(item => ({ ...item,
        available: item.remote ? item.available === true && value : isPortfolioFontAvailable(item.value, getPortfolioFontCapability()) })) })
    }, visible(value) { if (value) { this._history = createGridHistory(normalizeTextGrid(this.properties.config)); this.setData({ tab: 'layout', selectedKeys: [], cellIndex: 0, blockIndex: 0, runIndex: 0, error: '', previewThemeMode: this.previewThemeMode() }); this.refresh(this._history.get()); this.resetScroll() } else this.clearHistory() } },
  lifetimes: { detached() { this.clearHistory() } },
  methods: {
    handleExpandFonts() { this.triggerEvent('fontexpand') },
    noop() {},
    previewThemeMode() { const color = this.properties.backgroundColor || '#FFFFFF'; const channels = [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16)); return channels[0] * 0.299 + channels[1] * 0.587 + channels[2] * 0.114 < 128 ? 'dark' : 'light' },
    refresh(draft) {
      if (!this._history) return
      const cellIndex = Math.min(this.data.cellIndex, draft.cells.length - 1), cell = draft.cells[cellIndex]
      const blockIndex = Math.min(this.data.blockIndex, cell.blocks.length - 1), block = cell.blocks[blockIndex]
      const runIndex = Math.min(this.data.runIndex, block.runs.length - 1), run = block.runs[runIndex]
      const selectedKeys = this.data.selectedKeys.filter(key => draft.cells.some(cell => cell.cellKey === key))
      const weights = draft.columnWeights.reduce((a, b) => a + b, 0)
      const left = col => draft.columnWeights.slice(0, col).reduce((a, b) => a + b, 0) / weights * 100
      this.setData({ draft, cell, block, run, cellIndex, blockIndex, runIndex, selectedKeys, hasInvalidDraft: Boolean(validateTextGrid(draft)), lineHeightEditor: buildPortfolioTextLineHeightEditor(block.lineHeight, DEFAULT_LINE_HEIGHT), canSplitCell: Boolean(findSplitCell(draft, selectedKeys)), borderColor: resolveTextGridBorderColor(draft, this.data.previewThemeMode), count: countGridText(draft), undoCount: this._history.size(),
        cells: draft.cells.map((item, index) => ({ ...item, index, selected: selectedKeys.includes(item.cellKey),
          label: `${item.row + 1}行 ${item.column + 1}列`, style: `left:${left(item.column)}%;top:${item.row * 90}rpx;width:${left(item.column + item.columnSpan) - left(item.column)}%;height:${item.rowSpan * 90}rpx;` })) })
    },
    rejectInput(error) {
      // 数值输入框有原生临时值；拒绝后重建输入节点，明确回显仍被保存的合法值。
      if (this._history) this.refresh(this.data.draft)
      this._inputRenderRevision = (this._inputRenderRevision || 0) + 1
      this.setData({ error: `${error.message}，此次调整未应用`, inputRenderKeys: [this._inputRenderRevision] })
      return false
    },
    apply(next) {
      if (!this._history) return false
      try { layoutTextGrid(next, gridContentWidth(next, DISPLAY_WIDTH_RPX, 1), 1); this.refresh(this._history.apply(next)); this.setData({ error: '' }); return true }
      catch (error) { return this.rejectInput(error) }
    },
    handleScroll(event) { this._scrollTop = event.detail.scrollTop },
    resetScroll() {
      // 常驻弹层会保留原生滚动位置；先同步实际位置，再发出回顶更新。
      this.setData({ scrollTop: this._scrollTop || 0 }, () => {
        this._scrollTop = 0
        this.setData({ scrollTop: 0 })
      })
    },
    handleTab(event) {
      const tab = event.currentTarget.dataset.value
      if (tab === this.data.tab) return
      if (tab === 'content' && this._history) {
        // 内容仅编辑当前焦点格，进入时同步收起布局多选和预览高亮。
        this.setData({ selectedKeys: [this.data.cell.cellKey] })
        this.refresh(this.data.draft)
      }
      this.setData({ tab }, () => this.resetScroll())
    },
    handleCell(event) {
      const index = Number(event.currentTarget.dataset.index), cell = this.data.draft.cells[index]
      if (!cell) return
      const selected = this.data.selectedKeys
      this.setData({ cellIndex: index, blockIndex: 0, runIndex: 0, selectedKeys: this.data.tab === 'layout' ? (selected.includes(cell.cellKey) ? selected.filter(key => key !== cell.cellKey) : [...selected, cell.cellKey]) : [cell.cellKey] })
      this.refresh(this.data.draft)
    },
    handleResize(event) {
      const field = event.currentTarget.dataset.field, value = Number(event.detail.value)
      try { this.apply(resizeGrid(this.data.draft, field === 'rows' ? value : this.data.draft.rows, field === 'columns' ? value : this.data.draft.columns)) }
      catch (error) { this.rejectInput(error) }
    },
    handleMerge() { try { this.apply(mergeCells(this.data.draft, this.data.selectedKeys)) } catch (error) { this.setData({ error: error.message }) } },
    handleSplit() {
      if (!this.properties.visible || !this._history) return
      const cell = findSplitCell(this.data.draft, this.data.selectedKeys)
      if (!cell) return
      try { this.apply(splitCell(this.data.draft, cell.cellKey)) } catch (error) { this.setData({ error: error.message }) }
    },
    handleUndo() {
      if (!this._history) return
      // 非法输入尚未进入合法历史，第一次撤销先恢复最后一次合法内容。
      this.fontUndo = true
      this.refresh(validateTextGrid(this.data.draft) ? this._history.get() : this._history.undo())
      this.fontUndo = false
      this.setData({ error: '' })
    },
    handleArray(event) { const draft = clone(this.data.draft); draft[event.currentTarget.dataset.field][Number(event.currentTarget.dataset.index)] = Number(event.detail.value); this.apply(draft) },
    handleAppearance(event) { const draft = clone(this.data.draft); draft[event.currentTarget.dataset.field] = typeof event.detail.value === 'boolean' ? event.detail.value : Number(event.detail.value); this.apply(draft) },
    handleRpxStep(event) {
      if (!this.properties.visible || !this._history) return
      const { field, index, delta } = event.currentTarget.dataset, settings = RPX_FIELDS[field]
      if (!settings || this.data.tab !== settings.tab || Math.abs(Number(delta)) !== RPX_STEP) return
      if (field === 'cellBorderWidthRpx' && !this.data.draft.cellBorder) return
      const draft = clone(this.data.draft)
      let target = draft, key = field
      if (settings.scope === 'row') {
        target = draft.rowMinHeightsRpx; key = Number(index)
        if (!Number.isInteger(key) || key < 0 || key >= target.length) return
      } else if (settings.scope === 'block' || settings.scope === 'run') {
        const block = draft.cells[this.data.cellIndex].blocks[this.data.blockIndex]
        target = settings.scope === 'block' ? block : block.runs[this.data.runIndex]
      }
      const current = target[key]
      if (!Number.isInteger(current)) return
      const next = Math.max(settings.min, Math.min(settings.max, current + Number(delta)))
      if (next === current) return
      target[key] = next
      this.apply(draft)
    },
    handleBackground(event) { this.apply({ ...this.data.draft, cellBackground: event.detail.color }) },
    handleBorderWidth(event) {
      if (!this.properties.visible || this.data.tab !== 'appearance' || !this.data.draft.cellBorder) return
      this.apply({ ...this.data.draft, cellBorderWidthRpx: Number(event.detail.value) })
    },
    handleBorderColor(event) {
      // 开关关闭或切走页签后，晚到的取色事件不能改写已隐藏的配置。
      if (!this.properties.visible || this.data.tab !== 'appearance' || !this.data.draft.cellBorder) return
      this.apply({ ...this.data.draft, cellBorderColor: event.detail.color })
    },
    handleSelectBlock(event) { this.setData({ blockIndex: Number(event.currentTarget.dataset.index), runIndex: 0 }); this.refresh(this.data.draft) },
    handleSelectRun(event) { this.setData({ runIndex: Number(event.currentTarget.dataset.index) }); this.refresh(this.data.draft) },
    handleLineHeightInput(event) {
      if (!this.properties.visible || !this._history || this.data.tab !== 'content') return
      const draft = clone(this.data.draft), block = draft.cells[this.data.cellIndex].blocks[this.data.blockIndex]
      block.lineHeight = parsePortfolioTextLineHeightInput(event.detail.value)
      if (!isValidPortfolioTextLineHeight(block.lineHeight)) {
        // 留下未完成的小数输入并阻止保存，避免用户看到非法值却提交了上次的合法配置。
        this.refresh(draft); this.setData({ error: PORTFOLIO_TEXT_LINE_HEIGHT_ERROR }); return
      }
      this.apply(draft)
    },
    handleLineHeightStep(event) {
      if (!this.properties.visible || !this._history || this.data.tab !== 'content') return
      const delta = Number(event.currentTarget.dataset.delta)
      if (Math.abs(delta) !== PORTFOLIO_TEXT_LINE_HEIGHT_STEP) return
      const draft = clone(this.data.draft), block = draft.cells[this.data.cellIndex].blocks[this.data.blockIndex]
      const next = stepPortfolioTextLineHeight(block.lineHeight, delta, DEFAULT_LINE_HEIGHT)
      if (next === block.lineHeight) return
      block.lineHeight = next
      this.apply(draft)
    },
    handleAddBlock() { const draft = clone(this.data.draft); const cell = draft.cells[this.data.cellIndex]; if (cell.blocks.length >= 8) return; cell.blocks.push(createBlock()); this.setData({ blockIndex: cell.blocks.length - 1, runIndex: 0 }); this.apply(draft) },
    handleAddRun() { const draft = clone(this.data.draft); const runs = draft.cells[this.data.cellIndex].blocks[this.data.blockIndex].runs; if (runs.length >= 8) return; runs.push(createRun()); this.setData({ runIndex: runs.length - 1 }); this.apply(draft) },
    handleRemoveBlock() { const draft = clone(this.data.draft); const cell = draft.cells[this.data.cellIndex]; cell.blocks.splice(this.data.blockIndex, 1); if (!cell.blocks.length) cell.blocks.push(createBlock()); this.apply(draft) },
    handleRemoveRun() { const draft = clone(this.data.draft); const runs = draft.cells[this.data.cellIndex].blocks[this.data.blockIndex].runs; runs.splice(this.data.runIndex, 1); if (!runs.length) runs.push(createRun()); this.apply(draft) },
    handleField(event) {
      if (!this._history) return
      const dataset = event.currentTarget.dataset, draft = clone(this.data.draft), cell = draft.cells[this.data.cellIndex]
      const target = dataset.scope === 'cell' ? cell : dataset.scope === 'block' ? cell.blocks[this.data.blockIndex] : cell.blocks[this.data.blockIndex].runs[this.data.runIndex]
      let value = dataset.value === undefined ? event.detail.value : dataset.value
      if (['fontSizeRpx', 'marginTopRpx', 'marginBottomRpx'].includes(dataset.field)) value = Number(value)
      if (dataset.field === 'fontFamily' && ((PORTFOLIO_TEXT_SELECTION_OPTIONS.find(item => item.value === value) || {}).remote ? !(this.data.fonts.find(item => item.value === value) || {}).available : !isPortfolioFontAvailable(value, getPortfolioFontCapability()))) { this.setData({ error: (PORTFOLIO_TEXT_SELECTION_OPTIONS.find(item => item.value === value) || {}).remote ? (this.properties.fontMessage || '字体暂不可用，当前使用系统字体') : '当前设备暂不支持此字体' }); return }
      if (dataset.field === 'fontFamily') {
        Object.assign(target, applyPortfolioFontSelection(value))
        this.triggerEvent('fontselect', { fontId: target.fontId, sessionId: this.properties.fontEditSessionId })
      } else target[dataset.field] = value
      if (dataset.field === 'text' && countGridText(draft) > MAX_TEXT) {
        // 超长输入保留在当前面板中供用户删改，既不截断，也不保存成旧文字。
        this.refresh(draft); this.setData({ error: '文字网格最多 2000 字，请删减后完成' }); return
      }
      this.apply(draft)
    },
    handleRunColor(event) { const draft = clone(this.data.draft); draft.cells[this.data.cellIndex].blocks[this.data.blockIndex].runs[this.data.runIndex].color = event.detail.color; this.apply(draft) },
    clearHistory() { if (this._history) this._history.clear(); this._history = null; this.setData({ undoCount: 0, canSplitCell: false }) },
    handleConfirm() {
      if (!this.properties.visible || !this._history) return
      const error = validateTextGrid(this.data.draft, { requireText: true })
      if (error) { this.setData({ error }); return }
      try { layoutTextGrid(this.data.draft, gridContentWidth(this.data.draft, DISPLAY_WIDTH_RPX, 1), 1) } catch (error) { this.rejectInput(error); return }
      const draft = clone(this.data.draft); this.clearHistory(); this.triggerEvent('confirm', draft)
    },
    handleCancel() { this.clearHistory(); this.triggerEvent('cancel') }
  }
})
