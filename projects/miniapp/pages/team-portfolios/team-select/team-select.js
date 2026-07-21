const { request } = require('../../../utils/request.js')
const { fetchMaintainableTeams, handleTeamMaintainerAuthError, showTeamPortfolioUnavailableToast } = require('../utils/team-portfolios.js')

Page({
  data: { teams: [], selectedTeamId: null, loading: false, errorMessage: '', creating: false, empty: false },
  onLoad() {
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
    let received = false
    if (channel && channel.on) channel.on('maintainableTeams', (teams) => { received = true; this.applyTeams(teams) })
    setTimeout(() => { if (!received) this.bootstrap() }, 0)
  },
  applyTeams(teams) {
    const selectedTeamId = teams.length === 1 ? teams[0].teamId : this.data.selectedTeamId
    this.setData({ teams, selectedTeamId, empty: teams.length === 0, loading: false, errorMessage: '' })
  },
  async bootstrap() {
    if (this.data.loading) return
    this.setData({ loading: true, errorMessage: '' })
    try { this.applyTeams(await fetchMaintainableTeams(request)) } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '可维护团队加载失败，请重试' }) } finally { this.setData({ loading: false }) }
  },
  handleRetry() { this.bootstrap() },
  handleTeamTap(event) { this.setData({ selectedTeamId: Number(event.currentTarget.dataset.id) || null }) },
  handleConfirm() {
    const teamId = this.data.selectedTeamId
    if (!teamId || this.data.creating) return
    wx.navigateTo({ url: `/pages/team-portfolios/standard-edit/team-portfolio-standard-edit?teamId=${teamId}` })
  }
})
