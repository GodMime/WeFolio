const { request } = require('../../../utils/request.js')
const { uploadTeamPortfolioAsset } = require('../utils/team-portfolio-assets.js')
const { createStandardTeamPortfolio, fetchTeamPortfolioDetail, handleTeamMaintainerAuthError, normalizeTeamPortfolioConfig, publishTeamPortfolio, saveTeamPortfolioDraft, showTeamPortfolioUnavailableToast } = require('../utils/team-portfolios.js')

const TYPE_BUCKETS = Object.freeze({ TEAM_PROFILE: 'teamProfile', CAROUSEL: 'carousel', DIVIDER: 'divider', MEMBER_PORTFOLIO_GRID: 'grid', MEMBER_PORTFOLIO_LIST: 'list', TEXT_SECTION: 'text', SCHEDULE_QUERY: 'schedule', CONTACT_FORM: 'contact', QR_CONTACT: 'qr' })

function makeKey() { return `component-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` }
function makeIdempotencyKey(prefix) { return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}` }
function isUncertainFailure(error) { return !error || !Number(error.statusCode) || Number(error.statusCode) >= 500 }
function normalizeTeamSnapshot(teamId, source = {}) { const team = source && typeof source.team === 'object' ? source.team : source; return { teamId: Number(team.teamId) || Number(teamId) || 0, teamName: String(team.teamName || team.name || '').trim(), avatarUrl: String(team.avatarUrl || '').trim(), intro: String(team.intro || '').trim() } }
function hasTeamSnapshot(snapshot = {}) { return Number(snapshot.teamId) > 0 && Boolean(String(snapshot.teamName || '').trim()) }
function draftTeamSnapshot(config = {}) { const component = (Array.isArray(config.components) ? config.components : []).find((item) => item.componentType === 'TEAM_PROFILE'); return component ? normalizeTeamSnapshot(0, component.config && component.config.team) : {} }
function buckets(components) {
  const value = { teamProfile: [], carousel: [], divider: [], grid: [], list: [], text: [], schedule: [], contact: [], qr: [] }
  ;(Array.isArray(components) ? components : []).forEach((component) => { const key = TYPE_BUCKETS[component.componentType]; if (key) value[key].push(component) })
  return value
}

Page({
  data: { portfolioId: 0, teamId: 0, teamSnapshot: {}, loading: false, errorMessage: '', canMaintain: false, publicationStatus: 'DRAFT_ONLY', draftRevision: 0, publishedRevision: 0, config: { schemaVersion: 'standard-team-v1', share: {}, components: [] }, componentBuckets: buckets([]), componentValidation: {}, componentSources: {}, hasInvalidComponents: false, saving: false, publishing: false, openingLibrary: false, shareCoverUploading: false, pendingDraftKey: '', pendingPublishKey: '', pendingPublishRevision: 0 },
  onLoad(options = {}) {
    const portfolioId = Number(options.portfolioId) || 0
    const teamId = Number(options.teamId) || 0
    if (portfolioId) { this.setData({ portfolioId }); return this.bootstrap() }
    if (teamId) { this.setData({ teamId }); return this.bootstrapNew() }
    this.setData({ errorMessage: '作品集参数无效' })
  },
  async bootstrapNew() {
    if (this.data.loading || !this.data.teamId) return
    this.setData({ loading: true, errorMessage: '' })
    try {
      const teamDetail = await request({ url: `/api/mine/teams/${this.data.teamId}` })
      const teamSnapshot = normalizeTeamSnapshot(this.data.teamId, teamDetail && teamDetail.team)
      this.setData({ teamSnapshot, canMaintain: true })
    } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '团队资料加载失败，请重试', canMaintain: false }) } finally { this.setData({ loading: false }) }
  },
  async bootstrap() {
    if (this.data.loading || !this.data.portfolioId) return
    this.setData({ loading: true, errorMessage: '' })
    try {
      const detail = await fetchTeamPortfolioDetail(request, this.data.portfolioId)
      const config = normalizeTeamPortfolioConfig(detail.config)
      const componentValidation = Object.fromEntries(config.components.map((item) => [item.componentKey, true]))
      let teamSnapshot = draftTeamSnapshot(config)
      if (!hasTeamSnapshot(teamSnapshot)) {
        const teamDetail = await request({ url: `/api/mine/teams/${detail.ownerId}` })
        teamSnapshot = normalizeTeamSnapshot(detail.ownerId, teamDetail && teamDetail.team)
      }
      this.setData({ teamId: detail.ownerId, teamSnapshot, canMaintain: true, publicationStatus: detail.publicationStatus, draftRevision: detail.draftRevision, publishedRevision: detail.publishedRevision, config, componentBuckets: buckets(config.components), componentValidation, hasInvalidComponents: false })
    } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; this.setData({ errorMessage: '团队作品集加载失败，请重试', canMaintain: false }) } finally { this.setData({ loading: false }) }
  },
  onShow() { if (this.data.openingLibrary) this.setData({ openingLibrary: false }) },
  handleRetry() { this.bootstrap() },
  updateConfig(config) { this.setData({ config, componentBuckets: buckets(config.components) }) },
  clearPending() { this.setData({ pendingDraftKey: '', pendingPublishKey: '', pendingPublishRevision: 0 }) },
  handleShareInput(event) { const field = event.currentTarget.dataset.field; this.clearPending(); this.updateConfig(Object.assign({}, this.data.config, { share: Object.assign({}, this.data.config.share, { [field]: event.detail.value }) })) },
  handleComponentConfigChange(event) {
    const detail = event.detail || {}; const componentKey = detail.componentKey || (event.currentTarget && event.currentTarget.dataset.key); const components = this.data.config.components.map((item) => item.componentKey === componentKey ? Object.assign({}, item, { config: detail.config || Object.assign({}, item.config, detail.field ? { [detail.field]: detail.value } : {}) }) : item)
    this.clearPending()
    const changed = this.data.config.components.find((item) => item.componentKey === componentKey)
    if (changed && changed.componentType === 'TEXT_SECTION' && detail.field) { const componentValidation = Object.assign({}, this.data.componentValidation, { [componentKey]: false }); this.setData({ componentValidation, hasInvalidComponents: true }) }
    this.updateConfig(Object.assign({}, this.data.config, { components }))
  },
  handleComponentValidationChange(event) { const detail = event.detail || {}; const componentValidation = Object.assign({}, this.data.componentValidation, { [detail.componentKey]: detail.valid !== false }); this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((key) => componentValidation[key] === false) }) },
  handleComponentSave(event) { const key = event.currentTarget.dataset.key; this.handleComponentConfigChange(event); const componentValidation = Object.assign({}, this.data.componentValidation, { [key]: true }); this.setData({ componentValidation, hasInvalidComponents: Object.keys(componentValidation).some((name) => componentValidation[name] === false) }) },
  handleComponentDelete(event) { const key = event.currentTarget.dataset.key; this.clearPending(); const validation = Object.assign({}, this.data.componentValidation); delete validation[key]; this.setData({ componentValidation: validation, hasInvalidComponents: Object.keys(validation).some((name) => validation[name] === false) }); this.updateConfig(Object.assign({}, this.data.config, { components: this.data.config.components.filter((item) => item.componentKey !== key) })) },
  handleMove(event) { const key = event.currentTarget.dataset.key; const direction = Number(event.currentTarget.dataset.direction); const components = this.data.config.components.slice(); const index = components.findIndex((item) => item.componentKey === key); const target = index + direction; if (index < 0 || target < 0 || target >= components.length) return; this.clearPending(); [components[index], components[target]] = [components[target], components[index]]; this.updateConfig(Object.assign({}, this.data.config, { components: components.map((item, sortOrder) => Object.assign({}, item, { sortOrder })) })) },
  handleMaintainTeam(event) { const teamId = Number(event.currentTarget.dataset.teamId || this.data.teamId); if (teamId) wx.navigateTo({ url: `/pages/team-maintenance/team-maintenance?teamId=${teamId}` }) },
  handleOpenLibrary() {
    if (this.data.openingLibrary || !this.data.canMaintain) return
    this.setData({ openingLibrary: true })
    wx.navigateTo({ url: '/pages/team-portfolios/component-library/team-portfolio-component-library', success: (result) => {
      const channel = result && result.eventChannel
      if (!channel) return this.setData({ openingLibrary: false })
      if (typeof channel.emit === 'function') channel.emit('existingTypes', this.data.config.components.map((item) => item.componentType))
      if (typeof channel.on === 'function') channel.on('selectComponent', ({ componentType } = {}) => this.addComponent(componentType))
    }, fail: () => this.setData({ openingLibrary: false }) })
  },
  addComponent(componentType) { if (!componentType) return this.setData({ openingLibrary: false }); this.clearPending(); const componentKey = makeKey(); const componentConfig = componentType === 'TEAM_PROFILE' ? { team: Object.assign({}, this.data.teamSnapshot) } : {}; const components = this.data.config.components.concat({ componentKey, componentType, sortOrder: this.data.config.components.length, enabled: true, config: componentConfig }); this.updateConfig(Object.assign({}, this.data.config, { components })); this.setData({ componentValidation: Object.assign({}, this.data.componentValidation, { [componentKey]: false }), hasInvalidComponents: true, openingLibrary: false }) },
  updateSource(componentKey, patch) { this.setData({ componentSources: Object.assign({}, this.data.componentSources, { [componentKey]: Object.assign({}, this.data.componentSources[componentKey], patch) }) }) },
  async loadSource(componentKey, fingerprint, url, patch, failureState) { if (!this.data.portfolioId) return; const source = this.data.componentSources[componentKey] || {}; if (source.loadingFingerprint === fingerprint) return; this.updateSource(componentKey, { loadingFingerprint: fingerprint, errorMessage: '', sourceAvailable: true }); try { const value = await request({ url }); if ((this.data.componentSources[componentKey] || {}).loadingFingerprint !== fingerprint) return; this.updateSource(componentKey, Object.assign({}, patch(value), { loadingFingerprint: '', failedStage: '', memberUserId: 0 })) } catch (error) { if ((this.data.componentSources[componentKey] || {}).loadingFingerprint !== fingerprint) return; if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) { this.updateSource(componentKey, Object.assign({ loadingFingerprint: '', errorMessage: '', sourceAvailable: false }, failureState || {})); return }; this.updateSource(componentKey, Object.assign({ loadingFingerprint: '', errorMessage: '来源加载失败，请重试', sourceAvailable: false }, failureState || {})) } },
  async handleCarouselLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'carousel-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members`, (members) => ({ members, works: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleCarouselMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { works: [], memberUserId, failedStage: '' }); return this.loadSource(key, `carousel-works-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/carousel/members/${memberUserId}/works`, (works) => ({ works }), { failedStage: 'member-items', memberUserId }) },
  handleCarouselRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleCarouselMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleCarouselLoadMembers(event) },
  async handleGridLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'grid-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-grid/members`, (members) => ({ members, portfolios: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleGridMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { portfolios: [], memberUserId, failedStage: '' }); return this.loadSource(key, `grid-portfolios-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-grid/members/${memberUserId}/portfolios`, (portfolios) => ({ portfolios }), { failedStage: 'member-items', memberUserId }) },
  handleGridRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleGridMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleGridLoadMembers(event) },
  async handleListLoadMembers(event) { const key = event.currentTarget.dataset.key; return this.loadSource(key, 'list-members', `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-list/members`, (members) => ({ members, portfolios: [] }), { failedStage: 'members', memberUserId: 0 }) },
  async handleListMemberChange(event) { const key = event.currentTarget.dataset.key; const memberUserId = event.detail.memberUserId; this.updateSource(key, { portfolios: [], memberUserId, failedStage: '' }); return this.loadSource(key, `list-portfolios-${memberUserId}`, `/api/mine/team-portfolios/${this.data.portfolioId}/components/member-portfolio-list/members/${memberUserId}/portfolios`, (portfolios) => ({ portfolios }), { failedStage: 'member-items', memberUserId }) },
  handleListRetrySource(event) { const source = this.data.componentSources[event.currentTarget.dataset.key] || {}; return source.failedStage === 'member-items' && source.memberUserId ? this.handleListMemberChange({ currentTarget: event.currentTarget, detail: { memberUserId: source.memberUserId } }) : this.handleListLoadMembers(event) },
  async chooseAsset(assetType, onSuccess) { if (!this.data.portfolioId || !wx.chooseMedia) return; const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: resolve, fail: reject })); const file = media && Array.isArray(media.tempFiles) && media.tempFiles[0]; if (!file || !file.tempFilePath) throw new Error('未选择有效图片'); const url = await uploadTeamPortfolioAsset({ portfolioId: this.data.portfolioId, assetType, clientId: makeKey(), filePath: file.tempFilePath, requestFn: request }); onSuccess(url) },
  async handleCoverChoose() { if (this.data.shareCoverUploading) return; this.setData({ shareCoverUploading: true }); try { await this.chooseAsset('COVER', (coverUrl) => { this.clearPending(); this.updateConfig(Object.assign({}, this.data.config, { share: Object.assign({}, this.data.config.share, { coverUrl }) })) }) } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '封面上传失败，请重试', icon: 'none' }) } finally { this.setData({ shareCoverUploading: false }) } },
  async handleQrChoose(event) {
    if (this.data.shareCoverUploading || !this.data.canMaintain) return
    const componentKey = event.currentTarget.dataset.key
    if (!wx.chooseMedia) return
    this.setData({ shareCoverUploading: true })
    try {
      await this.chooseAsset('QR_CONTACT', (qrUrl) => { const current = this.data.config.components.find((item) => item.componentKey === componentKey); this.handleComponentConfigChange({ currentTarget: { dataset: { key: componentKey } }, detail: { config: Object.assign({}, current && current.config, { qrUrlSource: 'CUSTOM', qrUrl }) } }); const child = this.selectComponent(`#${componentKey}`); if (child && child.applyUploadedImage) child.applyUploadedImage({ detail: { qrUrl } }) })
    } catch (error) { if (handleTeamMaintainerAuthError(error)) return; if (showTeamPortfolioUnavailableToast(error)) return; if (!/cancel/i.test(String(error && error.errMsg || error && error.message || ''))) wx.showToast({ title: '上传失败，请重试', icon: 'none' }) } finally { this.setData({ shareCoverUploading: false }) }
  },
  hasInvalidComponents() { return Object.keys(this.data.componentValidation).some((key) => this.data.componentValidation[key] === false) },
  async saveDraft(forPublish = false) {
    if (this.data.saving || (this.data.publishing && !forPublish) || !this.data.canMaintain || this.hasInvalidComponents()) return null
    this.setData({ saving: true })
    try {
      const isNew = !this.data.portfolioId
      const clientRevision = this.data.draftRevision
      const idempotencyKey = isNew ? '' : (this.data.pendingDraftKey || makeIdempotencyKey('team-draft'))
      if (!isNew && !this.data.pendingDraftKey) this.setData({ pendingDraftKey: idempotencyKey })
      const result = isNew
        ? await createStandardTeamPortfolio(request, this.data.teamId, this.data.config)
        : await saveTeamPortfolioDraft(request, this.data.portfolioId, this.data.config, clientRevision, idempotencyKey)
      const portfolioId = Number(result && result.portfolioId) || this.data.portfolioId
      if (!portfolioId) throw new Error('团队作品集创建失败')
      const draftRevision = Number(result && result.draftRevision) || this.data.draftRevision
      const config = result && result.config ? normalizeTeamPortfolioConfig(result.config) : this.data.config
      this.setData({ portfolioId, draftRevision, publicationStatus: (result && result.publicationStatus) || this.data.publicationStatus, config, componentBuckets: buckets(config.components), pendingDraftKey: '' })
      return result
    } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return null
      if (showTeamPortfolioUnavailableToast(error)) {
        this.setData({ pendingDraftKey: '' })
        return null
      }
      if (!isUncertainFailure(error)) this.setData({ pendingDraftKey: '' })
      wx.showToast({ title: '保存失败，请检查组件配置', icon: 'none' })
      return null
    } finally { this.setData({ saving: false }) }
  },
  async handleSaveTap() { await this.saveDraft() },
  handlePreviewTap() { if (!this.data.portfolioId) return; wx.navigateTo({ url: `/pages/team-portfolios/standard-preview/team-portfolio-standard-preview?portfolioId=${this.data.portfolioId}&scope=draft` }) },
  async handlePublishTap() {
    if (this.data.publishing || !this.data.canMaintain) return
    this.setData({ publishing: true })
    try { if (this.data.pendingPublishKey) { const result = await publishTeamPortfolio(request, this.data.portfolioId, this.data.pendingPublishRevision, this.data.pendingPublishKey); this.setData({ publicationStatus: result.publicationStatus || 'PUBLISHED', publishedRevision: Number(result.publishedRevision) || this.data.publishedRevision, pendingPublishKey: '', pendingPublishRevision: 0 }); return wx.showToast({ title: '已发布', icon: 'success' }) }; const saved = await this.saveDraft(true); if (!saved) return; const revision = Number(saved.draftRevision) || this.data.draftRevision; const key = makeIdempotencyKey('team-publish'); this.setData({ pendingPublishKey: key, pendingPublishRevision: revision }); const result = await publishTeamPortfolio(request, this.data.portfolioId, revision, key); this.setData({ publicationStatus: result.publicationStatus || 'PUBLISHED', publishedRevision: Number(result.publishedRevision) || this.data.publishedRevision, pendingPublishKey: '', pendingPublishRevision: 0 }); wx.showToast({ title: '已发布', icon: 'success' }) } catch (error) {
      if (handleTeamMaintainerAuthError(error)) return
      if (showTeamPortfolioUnavailableToast(error)) {
        this.setData({ pendingPublishKey: '', pendingPublishRevision: 0 })
        return
      }
      if (!isUncertainFailure(error)) this.setData({ pendingPublishKey: '', pendingPublishRevision: 0 })
      wx.showToast({ title: '发布失败，请重试', icon: 'none' })
    } finally { this.setData({ publishing: false }) }
  }
})
