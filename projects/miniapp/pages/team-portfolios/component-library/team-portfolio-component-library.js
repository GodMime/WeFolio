const { request } = require('../../../utils/request.js')
const { fetchTeamComponentLibrary, handleTeamMaintainerAuthError, showTeamPortfolioUnavailableToast } = require('../utils/team-portfolios.js')

Page({
  data: { components: [], existingTypes: [], loading: false, errorMessage: '', empty: false, selectingType: '' },
  onLoad() {
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
    if (channel && channel.on) channel.on('existingTypes', (existingTypes) => this.applyExistingTypes(existingTypes))
    this.bootstrap()
  },
  async bootstrap() {
    if (this.data.loading) return
    this.setData({ loading: true, errorMessage: '' })
    try { this.applyComponents(await fetchTeamComponentLibrary(request)) } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '组件库加载失败，请重试' }) } finally { this.setData({ loading: false }) }
  },
  applyExistingTypes(existingTypes) { this.setData({ existingTypes: Array.isArray(existingTypes) ? existingTypes : [], components: this.decorateComponents(this.data.components) }) },
  decorateComponents(components) { return (Array.isArray(components) ? components : []).map((item) => Object.assign({}, item, { disabled: item.componentType === 'TEAM_PROFILE' && this.data.existingTypes.includes(item.componentType) })) },
  applyComponents(components) { const normalized = this.decorateComponents(components); this.setData({ components: normalized, empty: !normalized.length }) },
  handleRetry() { this.bootstrap() },
  handleSelect(event) {
    const componentType = event.currentTarget.dataset.type
    if (!componentType || this.data.selectingType || event.currentTarget.dataset.disabled) return
    this.setData({ selectingType: componentType })
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
    if (channel && channel.emit) channel.emit('selectComponent', { componentType })
    wx.navigateBack()
  }
})
