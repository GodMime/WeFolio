const { createTimelineNode, normalizeTimeline, validateTimeline, countTimelineText, timelineTheme, daysInMonth, formatTimelineDate, timelineDateFeedback, MAX_CUSTOM_LABEL_CODE_POINTS, ROLES, NODE_ROLES, VARIANT_LABELS, resolveTimelineStyle } = require('../../utils/portfolio-timeline')
const { PORTFOLIO_TEXT_SELECTION_OPTIONS, applyPortfolioFontSelection, getPortfolioFontCapability, isPortfolioFontAvailable } = require('../../utils/portfolio-component-platform')
const clone = value => JSON.parse(JSON.stringify(value))
const DRAG_MIN_DISTANCE = 4
const DRAG_TAP_SUPPRESS_MS = 300
const NODE_ROW_SELECTOR = '.timeline-node-row'
const dragPoint = (points, identifier) => (points || []).find(point => identifier === undefined || point.identifier === identifier)
const ROLE_LABELS = Object.freeze({ heading: '时间轴标题', date: '节点标记', title: '节点标题', description: '内容描述' })
Component({
  properties: { visible: { type: Boolean, value: false }, config: { type: Object, value: {} }, ownerType: { type: String, value: 'USER' }, themeMode: { type: String, value: '' }, backgroundColor: { type: String, value: '#FFFFFF' },
    fontContext: { type: Object, value: {} }, fontOptions: { type: Array, value: [] }, remoteFontAvailable: { type: Boolean, value: false }, moreFontsExpanded: { type: Boolean, value: false }, fontEditSessionId: { type: Number, value: 0 }, fontMessage: { type: String, value: '' } },
  data: { draft: {}, tab: 'content', nodeDetail: false, styleDetail: false, styleEntries: [], nodeStyleEntries: [], editorScrollTop: 0, draggingKey: '', dragTargetKey: '', dragStyle: '', nodeIndex: 0, node: {}, dates: {}, customDate: '', customDateCount: 0, maxCustomLabelLength: MAX_CUSTOM_LABEL_CODE_POINTS, datePreview: '', datePreviewValid: false, nodeRows: [], theme: 'light', count: 0, error: '', styleScope: 'component', styleRole: 'heading', stylePatch: {}, effectiveStyle: {}, fontValue: 'SYSTEM', fonts: [],
    variants: Object.keys(VARIANT_LABELS).map(value => ({ value, label: VARIANT_LABELS[value] })), directions: [{ value: 'HORIZONTAL', label: '横向' }, { value: 'VERTICAL', label: '纵向' }],
    precisions: [{ value: 'YEAR', label: '年份' }, { value: 'MONTH', label: '年月' }, { value: 'DAY', label: '年月日' }, { value: 'CUSTOM', label: '自定义' }], roles: [], inherited: true },
  observers: {
    visible(value) {
      if (!value) { this.closeSession(); return }
      this.clearDrag()
      this._suppressTapUntil = 0
      this._session = (this._session || 0) + 1
      this._dates = {}
      this._textStyleSnapshot = null
      this._nodeListScrollTop = 0
      this.setData({ tab: 'content', nodeDetail: false, styleDetail: false, nodeIndex: 0, styleScope: 'component', styleRole: 'heading', error: '' })
      this.refresh(normalizeTimeline(this.properties.config))
      this.setEditorScrollTop(0)
    },
    'themeMode, backgroundColor': function () { this.setData({ theme: timelineTheme(this.properties.themeMode, this.properties.backgroundColor) }) },
    'fontOptions, remoteFontAvailable': function () { this.refreshFonts() }
  },
  lifetimes: { detached() { this.closeSession() } },
  methods: {
    noop() {},
    closeSession() { this.clearDrag(); this._session = (this._session || 0) + 1; this._dates = null; this._textStyleSnapshot = null; this._scrollRequest = (this._scrollRequest || 0) + 1; this.setData({ styleDetail: false }) },
    active() { return this.properties.visible && this._dates !== null },
    handleEditorScroll(event) {
      const top = Number(event.detail.scrollTop)
      if (this.active() && Number.isFinite(top)) this._scrollTop = Math.max(0, top)
    },
    /** 先同步实际滚动值，再写目标值，避免相同 scroll-top 无法触发复位。 */
    setEditorScrollTop(top) {
      const request = this._scrollRequest = (this._scrollRequest || 0) + 1
      const session = this._session
      const next = Math.max(0, Number(top) || 0)
      this.setData({ editorScrollTop: this._scrollTop || 0 }, () => {
        if (!this.active() || this._session !== session || this._scrollRequest !== request) return
        this._scrollTop = next
        this.setData({ editorScrollTop: next })
      })
    },
    refreshFonts() {
      this.setData({ fonts: ((this.properties.fontOptions || []).length ? this.properties.fontOptions : PORTFOLIO_TEXT_SELECTION_OPTIONS).map(item => ({ ...item,
        available: item.remote ? item.available === true && this.properties.remoteFontAvailable : isPortfolioFontAvailable(item.value, getPortfolioFontCapability()) })) })
    },
    refresh(draft) {
      if (!this.active()) return
      const nodeIndex = Math.max(0, Math.min(this.data.nodeIndex, draft.nodes.length - 1)), node = draft.nodes[nodeIndex] || {}
      if (node.nodeKey && !this._dates[node.nodeKey]) {
        const custom = node.datePrecision === 'CUSTOM'
        const [year = '', month = '', day = ''] = (custom ? '' : node.date || '').split('-')
        this._dates[node.nodeKey] = { year, month, day, customDate: custom ? node.date : '' }
      }
      const dates = this._dates[node.nodeKey] || {}, feedback = timelineDateFeedback(node.datePrecision, node.date)
      const roles = this.data.styleScope === 'node' ? NODE_ROLES : ROLES
      const role = roles.includes(this.data.styleRole) ? this.data.styleRole : roles[0]
      const target = this.data.styleScope === 'node' ? node : draft, patch = (target.textStyles || {})[role] || {}
      const effective = resolveTimelineStyle(draft, role, this.data.styleScope === 'node' ? node : undefined)
      this.setData({ draft, nodeIndex, node, dates, customDate: dates.customDate || '', customDateCount: Array.from(dates.customDate || '').length, datePreview: feedback.text, datePreviewValid: feedback.valid,
        nodeRows: draft.nodes.map(item => ({ ...item, displayDate: formatTimelineDate(item.datePrecision, item.date) || '待填写标记' })), theme: timelineTheme(this.properties.themeMode, this.properties.backgroundColor), count: countTimelineText(draft),
        roles: roles.map(value => ({ value, label: ROLE_LABELS[value] })), styleRole: role, stylePatch: patch, inherited: !Object.keys(patch).length, effectiveStyle: effective,
        fontValue: effective.fontId || effective.fontFamily,
        styleEntries: this.textStyleEntries(draft, ROLES), nodeStyleEntries: this.textStyleEntries(draft, NODE_ROLES, node) })
      this.refreshFonts()
      this.triggerEvent('fontdraft', { componentType: 'TIMELINE', config: clone(draft), sessionId: this.properties.fontEditSessionId, operation: 'edit' })
    },
    edit(mutator) { if (!this.active()) return; const draft = clone(this.data.draft); mutator(draft); this.refresh(draft); this.setData({ error: validateTimeline(draft, { allowIncomplete: true }) }) },
    handleTab(event) { if (!this.active() || this.data.styleDetail) return; this.clearDrag(); this.setData({ tab: event.currentTarget.dataset.value }); this.setEditorScrollTop(0) },
    handleField(event) { const { field, value } = event.currentTarget.dataset; this.edit(draft => { draft[field] = value === undefined ? event.detail.value : value }) },
    handleGap(event) { const direction = event.currentTarget.dataset.direction; this.edit(draft => { draft.minGapRpx[direction] = Number(event.detail.value) }) },
    handleNode(event) {
      if (!this.active() || this._drag || Date.now() < (this._suppressTapUntil || 0)) return
      const { key, index } = event.currentTarget.dataset
      const nodeIndex = key ? this.data.draft.nodes.findIndex(node => node.nodeKey === key) : Number(index)
      if (!Number.isInteger(nodeIndex) || !this.data.draft.nodes[nodeIndex]) return
      this._nodeListScrollTop = this._scrollTop || 0
      this.setData({ nodeDetail: true, styleDetail: false, nodeIndex })
      this.refresh(this.data.draft)
      this.setEditorScrollTop(0)
    },
    handleBackNodes() { if (!this.active() || this.data.styleDetail) return; this.setData({ nodeDetail: false }); this.setEditorScrollTop(this._nodeListScrollTop) },
    handleNodeField(event) { const field = event.currentTarget.dataset.field; this.edit(draft => { const node = draft.nodes[this.data.nodeIndex]; if (node) node[field] = event.detail.value }) },
    writeDate(node, values) {
      if (node.datePrecision === 'CUSTOM') { node.date = values.customDate || ''; return }
      const year = String(values.year || '').padStart(4, '0'), month = String(values.month || '').padStart(2, '0'), day = String(values.day || '').padStart(2, '0')
      node.date = !values.year ? '' : node.datePrecision === 'YEAR' ? year : node.datePrecision === 'MONTH' ? `${year}-${values.month ? month : ''}` : `${year}-${values.month ? month : ''}-${values.day ? day : ''}`
    },
    handlePrecision(event) { this.edit(draft => { const node = draft.nodes[this.data.nodeIndex]; if (!node) return; node.datePrecision = event.currentTarget.dataset.value; this.writeDate(node, this._dates[node.nodeKey]) }) },
    handleCustomDate(event) {
      if (!this.active()) return
      const node = this.data.node
      if (event.currentTarget.dataset.nodeKey !== node.nodeKey || node.datePrecision !== 'CUSTOM') return
      const values = this._dates[node.nodeKey]
      if (!values) return
      values.customDate = event.detail.value
      this.edit(draft => this.writeDate(draft.nodes[this.data.nodeIndex], values))
    },
    /** 输入过程中仅同步预览，提交年份或月份后再判断月底与闰日。 */
    handleDate(event) {
      if (!this.active()) return
      const node = this.data.node
      if (event.currentTarget.dataset.nodeKey !== node.nodeKey || node.datePrecision === 'CUSTOM') return
      const values = this._dates[node.nodeKey]
      if (!values) return
      values[event.currentTarget.dataset.field] = event.detail.value
      this.edit(draft => this.writeDate(draft.nodes[this.data.nodeIndex], values))
    },
    handleDateBlur(event) {
      if (!this.active()) return
      const node = this.data.node
      if (event.currentTarget.dataset.nodeKey !== node.nodeKey || node.datePrecision === 'CUSTOM') return
      const values = this._dates[node.nodeKey]
      if (!values) return
      values[event.currentTarget.dataset.field] = event.detail.value
      const year = Number(values.year), month = Number(values.month)
      const invalidDay = values.day && Number.isInteger(year) && year >= 1 && year <= 9999 && Number.isInteger(month) && month >= 1 && month <= 12 && Number(values.day) > daysInMonth(year, month)
      if (invalidDay) values.day = ''
      this.edit(draft => this.writeDate(draft.nodes[this.data.nodeIndex], values))
      if (invalidDay) this.setData({ error: '当前日期不存在，请重新选择日' })
    },
    handleAddNode() { if (!this.active() || this._drag) return; if (this.data.draft.nodes.length >= 20) { this.setData({ error: '时间轴最多 20 个节点' }); return }; this._nodeListScrollTop = this._scrollTop || 0; this.setData({ nodeDetail: true, styleDetail: false, nodeIndex: this.data.draft.nodes.length }); this.edit(draft => draft.nodes.push(createTimelineNode())); this.setEditorScrollTop(0) },
    /** 列表删除按稳定身份定位，避免删掉当前编辑的另一个节点。 */
    handleRemoveNode(event) {
      if (!this.active() || this._drag) return
      const key = event && event.currentTarget && event.currentTarget.dataset.key || this.data.node.nodeKey
      const selectedKey = this.data.node.nodeKey
      const index = this.data.draft.nodes.findIndex(node => node.nodeKey === key)
      if (index < 0) return
      const returnToList = this.data.nodeDetail
      delete this._dates[key]
      this.edit(draft => {
        draft.nodes.splice(index, 1)
        const selectedIndex = draft.nodes.findIndex(node => node.nodeKey === selectedKey)
        this.setData({ nodeIndex: selectedIndex < 0 ? Math.min(index, draft.nodes.length - 1) : selectedIndex, nodeDetail: false, styleDetail: false })
      })
      if (returnToList) this.setEditorScrollTop(this._nodeListScrollTop)
    },
    clearDrag() {
      this._drag = null
      this.setData({ draggingKey: '', dragTargetKey: '', dragStyle: '' })
    },
    /** 手柄独占本次触摸，行坐标按开始时的节点身份保存。 */
    handleDragStart(event) {
      if (this._drag) { this.handleDragCancel(); return }
      if (!this.active() || this.data.nodeDetail || this.data.tab !== 'content' || this.data.draft.nodes.length < 2) return
      const touches = event.touches || []
      const touch = touches.length === 1 && touches[0]
      const key = event.currentTarget.dataset.key
      if (!touch || !Number.isFinite(touch.clientY) || !this.data.draft.nodes.some(node => node.nodeKey === key)) return
      const drag = this._drag = { key, identifier: touch.identifier, startY: touch.clientY, y: touch.clientY, moved: false,
        session: this._session, keys: this.data.draft.nodes.map(node => node.nodeKey), bounds: [] }
      this.setData({ draggingKey: key, dragTargetKey: key, dragStyle: '' })
      this.createSelectorQuery().selectAll(NODE_ROW_SELECTOR).boundingClientRect(rows => {
        if (this._drag !== drag || !this.active() || this._session !== drag.session) return
        drag.bounds = (rows || []).map((row, index) => ({ ...row, key: row.dataset && row.dataset.key || drag.keys[index] }))
          .filter(row => row.key && Number.isFinite(row.top) && Number.isFinite(row.bottom))
        this.updateDrag(drag.y)
      }).exec()
    },
    handleDragMove(event) {
      if (!this._drag) return
      if (!event.touches || event.touches.length !== 1) { this.handleDragCancel(); return }
      const touch = dragPoint(event.touches, this._drag.identifier)
      if (!touch || !Number.isFinite(touch.clientY)) { this.handleDragCancel(); return }
      this.updateDrag(touch.clientY)
    },
    updateDrag(y) {
      const drag = this._drag
      if (!drag || !this.active() || !Number.isFinite(y)) return
      drag.y = y
      if (!drag.moved && Math.abs(y - drag.startY) < DRAG_MIN_DISTANCE) return
      drag.moved = true
      // 行间空隙按相邻边界的中点归属；上下越界分别落到首尾节点。
      const bounds = drag.bounds
      let index = bounds.findIndex((row, position) => y <= (bounds[position + 1] ? (row.bottom + bounds[position + 1].top) / 2 : row.bottom))
      if (index < 0 && bounds.length) index = bounds.length - 1
      this.setData({ dragStyle: `transform:translateY(${y - drag.startY}px);`, dragTargetKey: index >= 0 ? bounds[index].key : drag.key })
    },
    handleDragEnd(event) {
      const drag = this._drag
      if (!drag) return
      const touch = dragPoint(event && event.changedTouches, drag.identifier)
      if (touch) this.updateDrag(touch.clientY)
      const targetKey = this.data.dragTargetKey
      this._suppressTapUntil = Date.now() + DRAG_TAP_SUPPRESS_MS
      this.clearDrag()
      if (!drag.moved || !this.active() || this._session !== drag.session) return
      const selectedKey = this.data.node.nodeKey
      const from = this.data.draft.nodes.findIndex(node => node.nodeKey === drag.key)
      const to = this.data.draft.nodes.findIndex(node => node.nodeKey === targetKey)
      if (from < 0 || to < 0 || from === to) return
      this.edit(draft => {
        draft.nodes.splice(to, 0, draft.nodes.splice(from, 1)[0])
        this.setData({ nodeIndex: draft.nodes.findIndex(node => node.nodeKey === selectedKey) })
      })
    },
    handleDragCancel() {
      if (this._drag) this._suppressTapUntil = Date.now() + DRAG_TAP_SUPPRESS_MS
      this.clearDrag()
    },
    textStyleEntries(draft, roles, node) {
      return roles.map(value => ({ value, label: ROLE_LABELS[value],
        summary: `${resolveTimelineStyle(draft, value, node).fontSizeRpx} rpx · ${Object.keys(((node || draft).textStyles || {})[value] || {}).length ? '单独设置' : node ? '继承统一样式' : '默认样式'}` }))
    },
    handleEditTextStyle(event) {
      if (!this.active() || this._drag || this.data.styleDetail) return
      const { scope, role } = event.currentTarget.dataset
      if (!['component', 'node'].includes(scope) || !(scope === 'node' ? NODE_ROLES : ROLES).includes(role) || scope === 'node' && !this.data.node.nodeKey) return
      const target = scope === 'node' ? this.data.node : this.data.draft
      this._textStyleSnapshot = { scope, nodeKey: target.nodeKey, scrollTop: this._scrollTop || 0,
        present: Object.prototype.hasOwnProperty.call(target, 'textStyles'), textStyles: clone(target.textStyles || {}) }
      this.setData({ styleDetail: true, styleScope: scope, styleRole: role, error: '' })
      this.refresh(this.data.draft)
      this.setEditorScrollTop(0)
    },
    handleBackTextStyle() { this.handleCancelTextStyle() },
    /** 仅撤回本次文字样式修改，正文与其它节点的草稿保持不变。 */
    handleCancelTextStyle() {
      const snapshot = this._textStyleSnapshot
      if (!this.active() || !this.data.styleDetail || !snapshot) return
      const draft = clone(this.data.draft)
      const target = snapshot.scope === 'node' ? draft.nodes.find(node => node.nodeKey === snapshot.nodeKey) : draft
      if (target) {
        if (snapshot.present) target.textStyles = clone(snapshot.textStyles)
        else delete target.textStyles
      }
      this.finishTextStyle(draft, snapshot.scrollTop)
    },
    handleSaveTextStyle() {
      const snapshot = this._textStyleSnapshot
      if (!this.active() || !this.data.styleDetail || !snapshot) return
      const draft = this.data.draft
      const target = snapshot.scope === 'node' ? draft.nodes.find(node => node.nodeKey === snapshot.nodeKey) : draft
      if (!target) return
      // 用最小有效内容复用样式校验，不要求用户先填完日期与正文。
      const validation = snapshot.scope === 'node'
        ? { nodes: [{ nodeKey: snapshot.nodeKey, datePrecision: 'YEAR', date: '', title: '', description: '', textStyles: target.textStyles || {} }] }
        : { textStyles: target.textStyles || {} }
      const error = validateTimeline(validation, { allowIncomplete: true })
      if (error) { this.setData({ error }); return }
      this.finishTextStyle(draft, snapshot.scrollTop)
    },
    finishTextStyle(draft, scrollTop) {
      this._textStyleSnapshot = null
      this.setData({ styleDetail: false, error: validateTimeline(draft, { allowIncomplete: true }) })
      this.refresh(draft)
      this.setEditorScrollTop(scrollTop)
    },
    handleStyleScope(event) { this.setData({ styleScope: event.currentTarget.dataset.value }); this.refresh(this.data.draft) },
    handleStyleRole(event) { this.setData({ styleRole: event.currentTarget.dataset.value }); this.refresh(this.data.draft) },
    styleEdit(mutator) { if (!this.data.styleDetail) return; this.edit(draft => { const target = this.data.styleScope === 'node' ? draft.nodes[this.data.nodeIndex] : draft; if (!target) return; target.textStyles = target.textStyles || {}; const patch = target.textStyles[this.data.styleRole] = { ...(target.textStyles[this.data.styleRole] || {}) }; mutator(patch); if (!Object.keys(patch).length) delete target.textStyles[this.data.styleRole]; if (!Object.keys(target.textStyles).length && this.data.styleScope === 'node') delete target.textStyles }) },
    handleFont(event) {
      if (!this.active() || !this.data.styleDetail) return
      const value = event.currentTarget.dataset.value, option = this.data.fonts.find(item => item.value === value)
      if (!option || !option.available) { this.setData({ error: this.properties.fontMessage || '当前字体暂不可用' }); return }
      const selection = applyPortfolioFontSelection(value)
      this.styleEdit(patch => { delete patch.fontId; delete patch.fontFamily; Object.assign(patch, selection); if (patch.fontId == null) delete patch.fontId })
      this.triggerEvent('fontselect', { fontId: selection.fontId, sessionId: this.properties.fontEditSessionId })
    },
    handleFontSize(event) { this.styleEdit(patch => { patch.fontSizeRpx = Number(event.detail.value) }) },
    handleFontSizeStep(event) { const next = this.data.effectiveStyle.fontSizeRpx + Number(event.currentTarget.dataset.delta); if (next >= 10 && next <= 96) this.styleEdit(patch => { patch.fontSizeRpx = next }) },
    handleColor(event) { if (this.active() && this.data.styleDetail) this.styleEdit(patch => { patch.color = event.detail.color }) },
    handleAutoColor() { this.styleEdit(patch => { patch.color = 'AUTO' }) },
    handleResetStyle(event) { const field = event.currentTarget.dataset.field; this.styleEdit(patch => { if (field === 'font') { delete patch.fontFamily; delete patch.fontId } else if (field) delete patch[field]; else Object.keys(patch).forEach(key => delete patch[key]) }) },
    handleExpandFonts() { if (this.active()) this.triggerEvent('fontexpand') },
    handleConfirm() { if (!this.active()) return; const error = validateTimeline(this.data.draft); if (error) { this.setData({ error }); return }; const draft = clone(this.data.draft); this.closeSession(); this.triggerEvent('confirm', draft) },
    handleCancel() { this.closeSession(); this.triggerEvent('cancel') }
  }
})
