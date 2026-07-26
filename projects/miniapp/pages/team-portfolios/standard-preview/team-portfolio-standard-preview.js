const { request } = require('../../../utils/request.js')
const { handleTeamMaintainerAuthError, previewTeamPortfolio, showTeamPortfolioUnavailableToast } = require('../utils/team-portfolios.js')

const TYPE_BUCKETS = Object.freeze({ TEAM_PROFILE: 'teamProfile', CAROUSEL: 'carousel', SINGLE_WORK: 'singleWork', DIVIDER: 'divider', MEMBER_PORTFOLIO_GRID: 'grid', MEMBER_PORTFOLIO_LIST: 'list', TEXT_SECTION: 'text', SCHEDULE_QUERY: 'schedule', CONTACT_FORM: 'contact', QR_CONTACT: 'qr' })
const PERSONAL_PREVIEW_URL = '/pages' + '/portfolios/standard-preview/portfolio-standard-preview'
function buckets(items) { const value = { teamProfile: [], carousel: [], singleWork: [], divider: [], grid: [], list: [], text: [], schedule: [], contact: [], qr: [] }; (Array.isArray(items) ? items : []).forEach((item) => { const key = TYPE_BUCKETS[item.componentType]; if (key) value[key].push(item) }); return value }

Page({
  data: { portfolioId: 0, scope: 'draft', loading: false, errorMessage: '', render: {}, componentBuckets: buckets([]), empty: true, previewMode: true, activeSingleWorkVideoKey: '', scheduleResults: {}, contactForms: {}, contactModalVisible: {} },
  onLoad(options = {}) { const portfolioId = Number(options.portfolioId); const scope = options.scope === 'published' ? 'published' : 'draft'; if (!portfolioId) { this.setData({ errorMessage: '作品集参数无效' }); return }; this.setData({ portfolioId, scope }); this.bootstrap() },
  async bootstrap() { if (this.data.loading) return; this.setData({ loading: true, errorMessage: '' }); try { const detail = await previewTeamPortfolio(request, this.data.portfolioId, this.data.scope === 'published'); const render = detail.renderData || {}; const components = Array.isArray(render.components) ? render.components : []; this.setData({ render, componentBuckets: buckets(components), empty: components.length === 0 }) } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '预览加载失败，请重试' }) } finally { this.setData({ loading: false }) } },
  handleRetry() { this.bootstrap() },
  handleImagePreview(event) { const item = event.detail && event.detail.item; if (item && (item.mediaUrl || item.coverUrl)) wx.previewImage({ current: item.mediaUrl || item.coverUrl, urls: [item.mediaUrl || item.coverUrl] }) },
  handleSingleWorkPreview(event) { const work = event.detail && event.detail.work; if (work && work.mediaUrl) wx.previewImage({ current: work.mediaUrl, urls: [work.mediaUrl] }) },
  handleSingleWorkActivate(event) { const componentKey = event.detail && event.detail.componentKey; if (!componentKey) return; this.pauseSingleWorkVideos(componentKey); this.setData({ activeSingleWorkVideoKey: componentKey }) },
  handleSingleWorkVideoError() { this.stopSingleWorkVideos(); wx.showToast({ title: '视频播放失败，请重试', icon: 'none' }) },
  pauseSingleWorkVideos(exceptKey) { const children = this.selectAllComponents ? this.selectAllComponents('.team-single-work-instance') : []; (children || []).forEach((child) => { if (!exceptKey || child.properties.componentKey !== exceptKey) child.pauseVideo && child.pauseVideo() }) },
  stopSingleWorkVideos() { this.pauseSingleWorkVideos(''); this.setData({ activeSingleWorkVideoKey: '' }) },
  onHide() { this.stopSingleWorkVideos() },
  onUnload() { this.stopSingleWorkVideos() },
  handleQrPreview() {},
  handleMemberPortfolio(event) { const memberPortfolioId = Number(event.detail && event.detail.portfolioId); if (memberPortfolioId) wx.navigateTo({ url: `${PERSONAL_PREVIEW_URL}?portfolioId=${memberPortfolioId}&teamPortfolioId=${this.data.portfolioId}&teamScope=${this.data.scope}` }) },
  async handleScheduleQuery(event) { const detail = event.detail || {}; const child = this.selectComponent(`#schedule-${detail.componentKey}`); try { const result = await request({ url: `/api/mine/team-portfolios/${this.data.portfolioId}/schedule-query-preview?scope=${this.data.scope}`, method: 'POST', data: { componentKey: detail.componentKey, queriedDate: detail.queriedDate, idempotencyKey: detail.idempotencyKey } }); this.setData({ scheduleResults: Object.assign({}, this.data.scheduleResults, { [detail.componentKey]: result }) }); if (child && child.resolveQuery) child.resolveQuery({ detail: result }) } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) { if (child && child.rejectQuery) child.rejectQuery({ detail: { message: '当前团队作品集不可用' } }); return }; if (child && child.rejectQuery) child.rejectQuery({ detail: { message: '档期查询失败，请重试' } }); wx.showToast({ title: '档期查询失败，请重试', icon: 'none' }) } },
  handleContactInput(event) { const componentKey = event.currentTarget.dataset.key; this.setData({ contactForms: Object.assign({}, this.data.contactForms, { [componentKey]: event.detail.form }) }) },
  handleContactOpen(event) { const componentKey = event.currentTarget.dataset.key; this.setData({ contactModalVisible: Object.assign({}, this.data.contactModalVisible, { [componentKey]: true }) }) },
  handleContactClose(event) { const componentKey = event.currentTarget.dataset.key; this.setData({ contactModalVisible: Object.assign({}, this.data.contactModalVisible, { [componentKey]: false }) }) },
  handleContactSubmit() { wx.showToast({ title: '预览模式不提交', icon: 'none' }) }
})
