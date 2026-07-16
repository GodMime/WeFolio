function positiveId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

function normalizeShowMemberName(value) {
  return value !== false
}

function createDefaultGridConfig() {
  return { items: [], showMemberName: true }
}

function normalizeGridItem(item = {}) {
  return {
    memberUserId: positiveId(item.memberUserId),
    portfolioId: positiveId(item.portfolioId),
    title: String(item.title || '').trim(),
    coverUrl: String(item.coverUrl || '').trim(),
    shareCode: String(item.shareCode || '').trim(),
    memberDisplayName: String(item.memberDisplayName || '').trim()
  }
}

function validateGridConfig(config = {}) {
  const items = Array.isArray(config.items) ? config.items.map(normalizeGridItem) : []
  if (items.length === 0) return { valid: false, message: '请至少选择一个成员作品集' }
  if (items.some((item) => !item.memberUserId || !item.portfolioId)) return { valid: false, message: '成员作品集引用无效' }
  return { valid: true, message: '' }
}

function fetchGridMembers(requestFn, portfolioId) {
  return requestFn({ url: `/api/mine/team-portfolios/${positiveId(portfolioId)}/components/member-portfolio-grid/members` })
}

function fetchGridPortfolios(requestFn, portfolioId, memberUserId) {
  const memberId = positiveId(memberUserId)
  if (!memberId) return Promise.reject(new Error('请先选择团队成员'))
  return requestFn({ url: `/api/mine/team-portfolios/${positiveId(portfolioId)}/components/member-portfolio-grid/members/${memberId}/portfolios` })
}

function changeGridMember(items) {
  return Array.isArray(items) ? items.slice() : []
}

function pruneGridItemsByMember(items, memberUserId) {
  const invalidMemberId = positiveId(memberUserId)
  return (Array.isArray(items) ? items : []).filter((item) => positiveId(item.memberUserId) !== invalidMemberId)
}

function toggleGridPortfolio(items, selected, memberUserId) {
  const source = Array.isArray(items) ? items.map(normalizeGridItem) : []
  const item = normalizeGridItem(Object.assign({}, selected, { memberUserId }))
  if (!item.memberUserId || !item.portfolioId) return source
  return source.some((value) => value.portfolioId === item.portfolioId)
    ? source.filter((value) => value.portfolioId !== item.portfolioId)
    : source.concat(item)
}

function buildGridConfig(items, showMemberName = true) {
  return {
    items: (Array.isArray(items) ? items : []).map((item) => ({
      memberUserId: positiveId(item.memberUserId),
      portfolioId: positiveId(item.portfolioId)
    })).filter((item) => item.memberUserId && item.portfolioId),
    showMemberName: normalizeShowMemberName(showMemberName)
  }
}

function syncGridDraft(component, requestSources) {
  component.setData({
    selectedMemberId: null,
    draftItems: (component.properties.items || []).map(normalizeGridItem),
    draftShowMemberName: normalizeShowMemberName(component.properties.showMemberName),
    errorMessage: ''
  })
  if (requestSources) component.triggerEvent('loadmembers', { portfolioId: component.properties.portfolioId })
}

Component({
  properties: {
    portfolioId: { type: Number, value: 0 },
    items: { type: Array, value: [], observer() { if (!this.properties.editMode) syncGridDraft(this, false) } },
    members: { type: Array, value: [] },
    portfolios: { type: Array, value: [] },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncGridDraft(this, value === true) } },
    showMemberName: { type: Boolean, value: true },
    sourceAvailable: { type: Boolean, value: true }
  },
  data: { selectedMemberId: null, draftItems: [], draftShowMemberName: true, errorMessage: '' },
  methods: {
    beginEdit() { syncGridDraft(this, true) },
    selectMember(event) { const memberUserId = positiveId(event.currentTarget.dataset.id); this.setData({ selectedMemberId: memberUserId, draftItems: changeGridMember(this.data.draftItems, memberUserId) }); this.triggerEvent('memberchange', { portfolioId: this.properties.portfolioId, memberUserId }) },
    pruneInvalidMember(event) { const memberUserId = positiveId(event.detail && event.detail.memberUserId); const draftItems = pruneGridItemsByMember(this.data.draftItems, memberUserId); this.setData({ draftItems }); this.triggerEvent('sourceinvalid', { memberUserId, items: draftItems }) },
    togglePortfolio(event) { const draftItems = toggleGridPortfolio(this.data.draftItems, event.currentTarget.dataset.item, this.data.selectedMemberId); this.setData({ draftItems }); this.triggerEvent('change', { items: draftItems }) },
    handleShowMemberNameChange(event) { this.setData({ draftShowMemberName: event.detail.value === true }) },
    cancelEdit() { syncGridDraft(this, false); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateGridConfig({ items: this.data.draftItems }); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: buildGridConfig(this.data.draftItems, this.data.draftShowMemberName) }) },
    previewPortfolio(event) { this.triggerEvent('preview', { portfolioId: event.currentTarget.dataset.id, shareCode: event.currentTarget.dataset.shareCode }) }
  },
  lifetimes: {
    attached() { syncGridDraft(this, this.properties.editMode === true) }
  }
})

module.exports = { buildGridConfig, changeGridMember, createDefaultGridConfig, fetchGridMembers, fetchGridPortfolios, normalizeGridItem, normalizeShowMemberName, pruneGridItemsByMember, toggleGridPortfolio, validateGridConfig }
