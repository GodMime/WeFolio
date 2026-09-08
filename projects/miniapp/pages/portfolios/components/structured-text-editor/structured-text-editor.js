const { normalizeTextColor, isValidTextColor } = require('../../../../utils/portfolio-text-color')
const {
  createStructuredBlock, normalizeStructuredTextConfig, validateStructuredTextConfig,
  finalizeStructuredTextConfig, createBlockEditDraft, switchBlockEditType,
  normalizeSpacingInput, countStructuredText, MIN_FONT_SIZE, MAX_FONT_SIZE
} = require('../../utils/portfolio-text-sections')
const { PORTFOLIO_TEXT_FONT_OPTIONS } = require('../../../../utils/portfolio-text-typography')
const { getPortfolioFontCapability, isPortfolioFontAvailable } = require('../../../../utils/portfolio-font-loader')
const BLOCK_TYPES = [
  { value: 'EYEBROW', label: '眉题' }, { value: 'TITLE', label: '标题' },
  { value: 'PARAGRAPH', label: '正文' }, { value: 'LIST', label: '列表' },
  { value: 'HINT', label: '提示' }, { value: 'SPACER', label: '留白' }
]
const SPACING_FIELDS = ['marginTopRpx', 'marginBottomRpx', 'heightRpx']
const TRANSITION_MS = 200
const TAB_FADE_MS = TRANSITION_MS / 2
const FONT_SIZE_STEP = 2
const FONT_SIZE_ERROR = '字号须为 20–96 rpx 的整数'
const validFontSize = value => Number.isInteger(value) && value >= MIN_FONT_SIZE && value <= MAX_FONT_SIZE
const clone = value => JSON.parse(JSON.stringify(value))
const point = event => (event.changedTouches || event.touches || [])[0] || {}

Component({
  properties: {
    visible: { type: Boolean, value: false }, config: { type: Object, value: {} },
    isNew: { type: Boolean, value: false }, backgroundOptions: { type: Array, value: [] },
    backgroundLoading: { type: Boolean, value: false }, backgroundError: { type: String, value: '' },
    backgroundHasMore: { type: Boolean, value: false },
    backgroundSelection: { type: Object, value: {} }
  },
  data: {
    draft: { blocks: [] }, rows: [], blockDraft: null, blockIsNew: false, tab: 'content', error: '',
    revealedKey: '', draggingKey: '', dragTargetKey: '', dragStyle: '', count: 0, showTypes: false,
    types: BLOCK_TYPES, fonts: [],
    detailClosing: false, tabLeaving: false, tabSwitching: false,
    fontMin: MIN_FONT_SIZE, fontMax: MAX_FONT_SIZE, fontStep: FONT_SIZE_STEP,
    fontSizeError: '', fontDecreaseDisabled: false, fontIncreaseDisabled: false,
    weights: [{ value: 'NORMAL', label: '常规' }, { value: 'BOLD', label: '粗体' }],
    alignments: [{ value: 'LEFT', label: '左对齐' }, { value: 'CENTER', label: '居中' }, { value: 'RIGHT', label: '右对齐' }],
    spacingRows: [], listRows: []
  },
  observers: {
    backgroundSelection(selection) { this.applyBackgroundSelection(selection) },
    visible(visible) {
      if (visible) this.beginSession()
      else {
        this.clearTransitions()
        this.rowBounds = []; this.touchStart = null
        this.setData({ blockDraft: null, detailClosing: false, tabLeaving: false, tabSwitching: false, revealedKey: '', draggingKey: '' })
      }
    }
  },
  lifetimes: {
    detached() { this.clearTransitions() }
  },
  methods: {
    // 会话关闭或重开时清理回调，避免旧动画清空新会话的编辑副本。
    clearTransitions() {
      clearTimeout(this.detailCloseTimer)
      clearTimeout(this.tabTimer)
      clearTimeout(this.tabFinishTimer)
      this.detailCloseTimer = this.tabTimer = this.tabFinishTimer = null
    },
    noop() {},
    handleSheetTouchStart(event) { this.sheetTouchY = point(event).clientY },
    handleSheetTouchEnd(event) {
      if (point(event).clientY - this.sheetTouchY < 80) return
      if (event.currentTarget.dataset.layer === 'detail') this.handleCancelBlock()
      else this.handleCancel()
    },
    beginSession() {
      this.clearTransitions()
      this.setData({ detailClosing: false, tabLeaving: false, tabSwitching: false, fontSizeError: '' })
      const capability = getPortfolioFontCapability()
      this.setData({ draft: normalizeStructuredTextConfig(this.properties.config), blockDraft: null,
        tab: 'content', error: '', revealedKey: '', draggingKey: '', showTypes: false,
        fonts: PORTFOLIO_TEXT_FONT_OPTIONS.map(font => Object.assign({}, font, {
          available: isPortfolioFontAvailable(font.value, capability)
        })) })
      this.refreshRows()
    },
    refreshRows() {
      this.setData({ count: countStructuredText(this.data.draft.blocks), rows: this.data.draft.blocks.map(block => ({
        blockKey: block.blockKey, label: (BLOCK_TYPES.find(type => type.value === block.type) || {}).label,
        summary: block.type === 'LIST' ? (block.items || []).join(' / ') : block.type === 'SPACER' ? `${block.heightRpx} rpx 留白` : block.content,
        itemCount: block.type === 'LIST' ? (block.items || []).length : 0
      })) })
    },
    applyBackgroundSelection(selection = {}) {
      const draft = this.data.draft
      if (!draft.backgroundEnabled || String(draft.backgroundWorkId) !== String(selection.workId)) return
      if (selection.loading) {
        this.setData({ draft: Object.assign({}, draft, { backgroundLoading: true, backgroundLoadError: '' }) })
        return
      }
      if (!draft.backgroundLoading) return
      this.setData({ draft: Object.assign({}, draft, { backgroundWork: selection.work,
        backgroundInvalid: !selection.work, backgroundLoading: false, backgroundLoadError: selection.error || '' }) })
    },
    handleTab(event) {
      const tab = event.currentTarget.dataset.value
      if (this.data.blockDraft || this.data.tabSwitching || tab === this.data.tab || !['content', 'background'].includes(tab)) return
      this.setData({ tabLeaving: true, tabSwitching: true, revealedKey: '' })
      this.tabTimer = setTimeout(() => {
        this.setData({ tab, tabLeaving: false })
        this.tabFinishTimer = setTimeout(() => this.setData({ tabSwitching: false }), TAB_FADE_MS)
      }, TAB_FADE_MS)
    },
    handleAdd() {
      if (this.data.blockDraft || this.data.tabSwitching) return
      if (this.data.draft.blocks.length >= 20) { this.setData({ error: '最多添加20个区块' }); return }
      this.setData({ showTypes: !this.data.showTypes, revealedKey: '' })
    },
    handleAddType(event) {
      if (this.data.blockDraft || this.data.tabSwitching) return
      if (this.data.draft.blocks.length >= 20) return
      const block = createStructuredBlock(event.currentTarget.dataset.value, `block_${Date.now()}_${Math.random().toString(16).slice(2, 10)}`)
      this.listItemKeys = []
      this.setData({ blockDraft: createBlockEditDraft(block), blockIsNew: true, showTypes: false, error: '' })
      this.refreshDetail()
    },
    handleEditBlock(event) {
      if (this.data.blockDraft || this.data.tabSwitching) return
      if (this.data.draggingKey) return
      const key = event.currentTarget.dataset.key
      if (this.data.revealedKey) { this.setData({ revealedKey: '' }); return }
      const block = this.data.draft.blocks.find(item => item.blockKey === key)
      if (!block) return
      this.listItemKeys = []
      this.setData({ blockDraft: createBlockEditDraft(block), blockIsNew: false, error: '' })
      this.refreshDetail()
    },
    refreshDetail() {
      const block = this.data.blockDraft.block
      const validSize = validFontSize(block.fontSizeRpx)
      this.setData({
        fontSizeError: block.type !== 'SPACER' && !validSize ? FONT_SIZE_ERROR : '',
        fontDecreaseDisabled: !validSize || block.fontSizeRpx <= MIN_FONT_SIZE,
        fontIncreaseDisabled: !validSize || block.fontSizeRpx >= MAX_FONT_SIZE
      })
      this.setData({ spacingRows: SPACING_FIELDS.filter(field => field !== 'heightRpx' || block.type === 'SPACER').map(field => ({
          field, label: field === 'marginTopRpx' ? '上间距' : field === 'marginBottomRpx' ? '下间距' : '留白高度', value: block[field]
      })) })
      this.refreshListRows()
    },
    refreshListRows() {
      const block = this.data.blockDraft.block
      if (block.type !== 'LIST') { this.setData({ listRows: [] }); return }
      const keys = this.listItemKeys || []
      const listRows = (block.items || []).map((content, index) => {
        if (!keys[index]) {
          this.listItemSequence = (this.listItemSequence || 0) + 1
          keys[index] = `${block.blockKey}_item_${this.listItemSequence}`
        }
        return { key: keys[index], index, content }
      })
      this.listItemKeys = keys
      this.setData({ listRows })
    },
    handleBlockType(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const result = switchBlockEditType(this.data.blockDraft, event.currentTarget.dataset.value)
      this.setData({ blockDraft: result.draft, error: result.error })
      this.refreshDetail()
    },
    handleBlockInput(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const field = event.currentTarget.dataset.field
      if (field === 'fontSizeRpx') { this.handleFontSizeInput(event); return }
      const blockDraft = clone(this.data.blockDraft)
      blockDraft.block[field] = field === 'fontSizeRpx' ? Number(event.detail.value) : event.detail.value
      this.setData({ blockDraft, error: '' })
    },
    handleBlockOption(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const { field, value } = event.currentTarget.dataset
      if (field === 'fontFamily' && !isPortfolioFontAvailable(value, getPortfolioFontCapability())) return
      const blockDraft = clone(this.data.blockDraft)
      blockDraft.block[field] = value
      this.setData({ blockDraft, error: '' })
      this.refreshDetail()
    },
    handleColorChange(event) {
      if (!this.data.blockDraft || this.data.detailClosing || !isValidTextColor(event.detail.color)) return
      this.handleBlockOption({ currentTarget: { dataset: { field: 'color', value: normalizeTextColor(event.detail.color) } } })
    },
    handleFontSizeInput(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const raw = String(event.detail.value)
      const value = /^\d+$/.test(raw) ? Number(raw) : NaN
      const blockDraft = clone(this.data.blockDraft)
      // 非法输入原样留在编辑副本中，由字段提示和保存校验共同拦截。
      blockDraft.block.fontSizeRpx = validFontSize(value) ? value : raw
      this.setData({ blockDraft, error: '' })
      this.refreshDetail()
    },
    handleFontSizeStep(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const current = this.data.blockDraft.block.fontSizeRpx
      const delta = Number(event.currentTarget.dataset.delta)
      if (!validFontSize(current) || ![FONT_SIZE_STEP, -FONT_SIZE_STEP].includes(delta)) return
      const value = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, current + delta))
      this.handleFontSizeInput({ detail: { value: String(value) } })
    },
    handleListInput(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const blockDraft = clone(this.data.blockDraft)
      blockDraft.block.items[Number(event.currentTarget.dataset.index)] = event.detail.value
      this.setData({ blockDraft, error: '' })
      this.refreshListRows()
    },
    handleAddListItem() {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const blockDraft = clone(this.data.blockDraft)
      if (blockDraft.block.items.length >= 10) { this.setData({ error: '列表最多10条' }); return }
      blockDraft.block.items.push('')
      this.setData({ blockDraft, error: '' })
      this.refreshListRows()
    },
    handleDeleteListItem(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const blockDraft = clone(this.data.blockDraft)
      blockDraft.block.items.splice(Number(event.currentTarget.dataset.index), 1)
      this.listItemKeys.splice(Number(event.currentTarget.dataset.index), 1)
      this.setData({ blockDraft, error: '' })
      this.refreshListRows()
    },
    handleSpacingInput(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const field = event.currentTarget.dataset.field
      if (!SPACING_FIELDS.includes(field)) return
      const blockDraft = clone(this.data.blockDraft)
      blockDraft.block[field] = normalizeSpacingInput(event.detail.value)
      this.setData({ blockDraft })
      this.refreshDetail()
    },
    handleSpacingStep(event) {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const { field, delta } = event.currentTarget.dataset
      if (!SPACING_FIELDS.includes(field)) return
      this.handleSpacingInput({ currentTarget: { dataset: { field } },
        detail: { value: Number(this.data.blockDraft.block[field]) + Number(delta) } })
    },
    closeBlockDetail() {
      if (!this.data.blockDraft || this.data.detailClosing) return
      this.setData({ detailClosing: true, error: '' })
      this.detailCloseTimer = setTimeout(() => {
        this.setData({ blockDraft: null, detailClosing: false, fontSizeError: '' })
        this.detailCloseTimer = null
      }, TRANSITION_MS)
    },
    handleCancelBlock() { this.closeBlockDetail() },
    handleSaveBlock() {
      if (!this.data.blockDraft || this.data.detailClosing) return
      const draft = clone(this.data.draft)
      const block = clone(this.data.blockDraft.block)
      if (this.data.blockIsNew) draft.blocks.push(block)
      else draft.blocks = draft.blocks.map(item => item.blockKey === block.blockKey ? block : item)
      // 详情只验证内容，背景允许留在未选择的编辑态；留白可以先加入，再继续添加文字。
      const validation = Object.assign({}, draft, { backgroundEnabled: false })
      let error = validateStructuredTextConfig(validation)
      if (block.type === 'SPACER' && error === '请至少添加一个非空文字或列表区块') error = ''
      if (error) { this.setData({ error }); return }
      draft.blocks = finalizeStructuredTextConfig(validation).blocks
      this.setData({ draft, error: '' })
      this.closeBlockDetail()
      this.refreshRows()
    },
    handleDeleteBlock(event) {
      const draft = clone(this.data.draft)
      draft.blocks = draft.blocks.filter(block => block.blockKey !== event.currentTarget.dataset.key)
      this.setData({ draft, revealedKey: '' })
      this.refreshRows()
    },
    handleOutsideTap() { this.setData({ revealedKey: '' }) },
    handleRowTouchStart(event) { this.touchStart = Object.assign({ key: event.currentTarget.dataset.key }, point(event)) },
    handleRowTouchEnd(event) {
      if (!this.touchStart || this.data.draggingKey) return
      const end = point(event)
      const dx = end.clientX - this.touchStart.clientX
      const dy = end.clientY - this.touchStart.clientY
      if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.3) {
        this.setData({ revealedKey: dx < 0 ? this.touchStart.key : '' })
      }
      this.touchStart = null
    },
    handleDragStart(event) {
      const key = event.currentTarget.dataset.key
      this.dragStartY = point(event).clientY
      this.setData({ draggingKey: key, dragTargetKey: key, revealedKey: '' })
      this.createSelectorQuery().selectAll('.structured-row').boundingClientRect(rows => { this.rowBounds = rows || [] }).exec()
    },
    handleDragMove(event) {
      if (!this.data.draggingKey) return
      const y = point(event).clientY
      if (!Number.isFinite(y)) return
      const rows = this.rowBounds || []
      // 行间留白按相邻边界的中点归属，不能把内部缝隙当作拖出列表底部。
      let index = rows.findIndex((row, position) => {
        const next = rows[position + 1]
        return y <= (next ? (row.bottom + next.top) / 2 : row.bottom)
      })
      if (index < 0 && rows.length) index = rows.length - 1
      this.setData({ dragStyle: `transform:translateY(${y - this.dragStartY}px);`,
        dragTargetKey: index >= 0 ? this.data.rows[index].blockKey : this.data.dragTargetKey })
    },
    handleDragEnd() {
      this.reorderBlock(this.data.draggingKey, this.data.dragTargetKey)
      this.setData({ draggingKey: '', dragTargetKey: '', dragStyle: '' })
      this.touchStart = null
    },
    reorderBlock(key, targetKey) {
      const draft = clone(this.data.draft)
      const from = draft.blocks.findIndex(block => block.blockKey === key)
      const to = draft.blocks.findIndex(block => block.blockKey === targetKey)
      if (from < 0 || to < 0 || from === to) return
      draft.blocks.splice(to, 0, draft.blocks.splice(from, 1)[0])
      this.setData({ draft })
      this.refreshRows()
    },
    handleBackgroundChange(event) {
      this.setData({ draft: Object.assign({}, this.data.draft, event.detail) })
      this.triggerEvent('backgroundchange', event.detail)
    },
    handleBackgroundRequest(event) { this.triggerEvent('backgroundrequest', event.detail) },
    handleCancel() {
      this.clearTransitions()
      this.setData({ blockDraft: null, detailClosing: false, tabLeaving: false, tabSwitching: false })
      this.triggerEvent('cancel')
    },
    handleConfirm() {
      if (this.data.blockDraft || this.data.tabSwitching) return
      const error = this.data.draft.backgroundEnabled && this.data.draft.backgroundLoading
        ? '背景作品加载中，请稍候' : validateStructuredTextConfig(this.data.draft)
      if (error) { this.setData({ error }); return }
      this.triggerEvent('confirm', finalizeStructuredTextConfig(this.data.draft))
    }
  }
})
