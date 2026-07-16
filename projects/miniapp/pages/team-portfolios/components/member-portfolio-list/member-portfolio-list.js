function positiveId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

function normalizeShowMemberName(value) {
  return value !== false
}

function createDefaultListConfig() {
  return { items: [], showMemberName: true }
}

function normalizeListItem(item = {}) {
  return {
    memberUserId: positiveId(item.memberUserId),
    portfolioId: positiveId(item.portfolioId),
    title: String(item.title || '').trim(),
    description: String(item.description || '').trim(),
    coverUrl: String(item.coverUrl || '').trim(),
    shareCode: String(item.shareCode || '').trim(),
    memberDisplayName: String(item.memberDisplayName || '').trim(),
    publishedRevision: Number(item.publishedRevision) || 0
  }
}

function validateListConfig(config = {}) {
  const items = Array.isArray(config.items) ? config.items.map(normalizeListItem) : []
  if (items.length === 0) return { valid: false, message: '请至少选择一个成员作品集' }
  if (items.some((item) => !item.memberUserId || !item.portfolioId)) return { valid: false, message: '成员作品集引用无效' }
  return { valid: true, message: '' }
}

function fetchListMembers(requestFn, portfolioId) {
  return requestFn({ url: `/api/mine/team-portfolios/${positiveId(portfolioId)}/components/member-portfolio-list/members` })
}

function fetchListPortfolios(requestFn, portfolioId, memberUserId) {
  const memberId = positiveId(memberUserId)
  if (!memberId) return Promise.reject(new Error('请先选择团队成员'))
  return requestFn({ url: `/api/mine/team-portfolios/${positiveId(portfolioId)}/components/member-portfolio-list/members/${memberId}/portfolios` })
}

function changeListMember(items) {
  return Array.isArray(items) ? items.slice() : []
}

function pruneListItemsByMember(items, memberUserId) {
  const invalidMemberId = positiveId(memberUserId)
  return (Array.isArray(items) ? items : []).filter((item) => positiveId(item.memberUserId) !== invalidMemberId)
}

function toggleListPortfolio(items, selected, memberUserId) {
  const source = Array.isArray(items) ? items.map(normalizeListItem) : []
  const item = normalizeListItem(Object.assign({}, selected, { memberUserId }))
  if (!item.memberUserId || !item.portfolioId) return source
  return source.some((value) => value.portfolioId === item.portfolioId)
    ? source.filter((value) => value.portfolioId !== item.portfolioId)
    : source.concat(item)
}

function buildListConfig(items, showMemberName = true) {
  return {
    items: (Array.isArray(items) ? items : []).map((item) => ({
      memberUserId: positiveId(item.memberUserId),
      portfolioId: positiveId(item.portfolioId)
    })).filter((item) => item.memberUserId && item.portfolioId),
    showMemberName: normalizeShowMemberName(showMemberName)
  }
}

function syncListDraft(component, requestSources) {
  component.setData({
    selectedMemberId: null,
    draftItems: (component.properties.items || []).map(normalizeListItem),
    draftShowMemberName: normalizeShowMemberName(component.properties.showMemberName),
    errorMessage: ''
  })
  if (requestSources) component.triggerEvent('loadmembers', { portfolioId: component.properties.portfolioId })
}

Component({
  properties: {
    portfolioId: { type: Number, value: 0 },
    items: { type: Array, value: [], observer() { if (!this.properties.editMode) syncListDraft(this, false) } },
    members: { type: Array, value: [] },
    portfolios: { type: Array, value: [] },
    editMode: { type: Boolean, value: false, observer(value, oldValue) { if (value !== oldValue) syncListDraft(this, value === true) } },
    showMemberName: { type: Boolean, value: true },
    sourceAvailable: { type: Boolean, value: true }
  },
  data: { selectedMemberId: null, draftItems: [], draftShowMemberName: true, errorMessage: '' },
  methods: {
    beginEdit() { syncListDraft(this, true) },
    selectMember(event) { const memberUserId = positiveId(event.currentTarget.dataset.id); this.setData({ selectedMemberId: memberUserId, draftItems: changeListMember(this.data.draftItems, memberUserId) }); this.triggerEvent('memberchange', { portfolioId: this.properties.portfolioId, memberUserId }) },
    pruneInvalidMember(event) { const memberUserId = positiveId(event.detail && event.detail.memberUserId); const draftItems = pruneListItemsByMember(this.data.draftItems, memberUserId); this.setData({ draftItems }); this.triggerEvent('sourceinvalid', { memberUserId, items: draftItems }) },
    togglePortfolio(event) { const draftItems = toggleListPortfolio(this.data.draftItems, event.currentTarget.dataset.item, this.data.selectedMemberId); this.setData({ draftItems }); this.triggerEvent('change', { items: draftItems }) },
    handleShowMemberNameChange(event) { this.setData({ draftShowMemberName: event.detail.value === true }) },
    cancelEdit() { syncListDraft(this, false); this.triggerEvent('cancel') },
    saveEdit() { const validation = validateListConfig({ items: this.data.draftItems }); if (!validation.valid) return this.setData({ errorMessage: validation.message }); this.triggerEvent('save', { config: buildListConfig(this.data.draftItems, this.data.draftShowMemberName) }) },
    previewPortfolio(event) { this.triggerEvent('preview', { portfolioId: event.currentTarget.dataset.id, shareCode: event.currentTarget.dataset.shareCode }) }
  },
  lifetimes: {
    attached() { syncListDraft(this, this.properties.editMode === true) }
  }
})

module.exports = { buildListConfig, changeListMember, createDefaultListConfig, fetchListMembers, fetchListPortfolios, normalizeListItem, normalizeShowMemberName, pruneListItemsByMember, toggleListPortfolio, validateListConfig }
