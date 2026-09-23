const { installRemoteFontPage } = require('../utils/portfolio-remote-font-page')
const { portfolioAudioPageMethods } = require('../utils/portfolio-audio-player')
const { openVideoPlayer } = require('../utils/portfolio-video-player')
const VIDEO_PLAYER_ROUTE = '/pages/team-portfolios/video-player/video-player'
const { createPortfolioVisitActivity } = require('../utils/visit-activity-page')
const { openWorkDetail } = require('../utils/portfolio-work-detail')
const { request } = require('../../../utils/request.js')
const { createTeamContactLeadForm, submitTeamContactLead } = require('../utils/team-contact-leads.js')
const { buildTeamSingleWorkViewEvent, normalizeTeamVisitorPortfolio, queryTeamVisitorSchedule, submitTeamVisitorEvent, switchTeamPortfolioMenu } = require('../utils/team-visitor-portfolio.js')
const { captureTeamPortfolioMenuInteraction, clearTeamPortfolioMenuComponentState, clearTeamPortfolioMenuTransitionTimers, isTeamPortfolioMenuInteractionCurrent, startTeamPortfolioMenuTransition } = require('../utils/team-portfolio-menu-transition.js')
const { createIdempotencyKey, openTeamVisitorSession, requestWithTeamVisitorSessionRefresh, resolveTeamShareCode } = require('../utils/team-visitor-session.js')
const { uploadTeamVisitorProfile } = require('../utils/team-visitor-profile.js')
const { showTeamPortfolioUnavailableToast } = require('../utils/team-portfolios.js')
const { createAnonymousSessionId, isWechatTimelineSinglePage } = require('../utils/single-page-mode.js')

const TYPE_BUCKETS = Object.freeze({ TEAM_PROFILE: 'teamProfile', CAROUSEL: 'carousel', VIDEO_CAROUSEL: 'videoCarousel', SINGLE_WORK: 'singleWork', DIVIDER: 'divider', MEMBER_PORTFOLIO_GRID: 'grid', MEMBER_PORTFOLIO_LIST: 'list', TEXT_SECTION: 'text', STRUCTURED_TEXT_SECTION: 'structuredText', TEXT_GRID: 'textGrid', CONTACT_INFO: 'contactInfo', SCHEDULE_QUERY: 'schedule', CONTACT_FORM: 'contact', QR_CONTACT: 'qr' })
const VIDEO_PLAYED_EVENT_TYPE = 'VIDEO_PLAYED'
const WORK_VIEWED_EVENT_TYPE = 'WORK_VIEWED'
const WORK_DETAIL_ROUTE = '/pages/team-portfolios/work-detail/team-portfolio-work-detail'
const MEDIA_TYPE_IMAGE = 'IMAGE'
const MEDIA_TYPE_VIDEO = 'VIDEO'
const PERSONAL_VISITOR_URL = '/pages' + '/portfolios/visitor-portfolio/visitor-portfolio'
const PERSONAL_VISITOR_TEAM_SOURCE_QUERY = 'fromTeamPortfolio=1'
const TIMELINE_SHARE_GUIDE_VALUE = 'timeline'
const SHARE_MENU_ITEMS = Object.freeze(['shareAppMessage', 'shareTimeline'])
const SHARE_CHANNEL_WECHAT_TIMELINE = 'WECHAT_TIMELINE'
const SHARE_SCENE_TEAM_PORTFOLIO_LIST = 'TEAM_PORTFOLIO_LIST'
const LIGHT_LOGO_URL = '/assets/system/folio-logo-stack-bold-small-50kb.png'
const DARK_LOGO_URL = '/assets/system/folio-logo-stack-bold-dark-50kb.png'
function buckets(items) { const value = { teamProfile: [], carousel: [], videoCarousel: [], singleWork: [], divider: [], grid: [], list: [], text: [], structuredText: [], textGrid: [], contactInfo: [], schedule: [], contact: [], qr: [] }; (Array.isArray(items) ? items : []).forEach((item) => { const key = TYPE_BUCKETS[item.componentType]; if (key) value[key].push(item) }); return value }
function isUncertainFailure(error) { return !error || !Number(error.statusCode) || Number(error.statusCode) >= 500 }
function positiveId(value) { const id = Number(value); return Number.isInteger(id) && id > 0 ? id : 0 }
function shareTitle(render = {}) { return render.share && render.share.title || render.title || '团队作品集' }
function hasPreviousPage() { const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []; return Array.isArray(pages) && pages.length > 1 }

Page({
  ...portfolioAudioPageMethods,
  data: {
    backgroundAudioPlaying: false, backgroundAudioResource: {}, backgroundAudioTop: 76, shareCode: '', sourceType: 'WECHAT_SHARE_CARD', loading: false, errorMessage: '', render: {}, portfolio: normalizeTeamVisitorPortfolio(), componentBuckets: buckets([]), activeSingleWorkVideoKey: '', videoPreviewVisible: false, visitorKey: '', visitRecordId: 0, visitorProfileToken: '', visitorProfileAuthVisible: false, visitorProfileForm: { nickname: '', avatarPath: '' }, visitorProfileSaving: false, empty: true, underMaintenance: false, showNavigationBack: false, timelineGuideRequested: false, timelineGuideVisible: false, timelineSharePortfolioId: 0, timelineShareRecordEnabled: false, scheduleResults: {}, contactForms: {}, contactSubmitting: {}, contactModalVisible: {}, pendingOpenKey: '', pendingContactKeys: {}, themeMode: 'light', backgroundColor: '#FFFFFF', navigationColor: '#212529', brandLogoUrl: LIGHT_LOGO_URL, portfolioScrollTop: 0, teamPortfolioMenuSwitching: false, teamPortfolioMenuTransitionClass: '' },
  onLoad(options = {}) {
    this.remoteFontPage = installRemoteFontPage(this, { editor: false, team: true })
    this.visitPageUnloaded = false
    this.positionBackgroundAudio()
    this.visitPageVisible = true
    const shareCode = resolveTeamShareCode(options)
    if (!shareCode) {
      wx.showToast({ title: '暂无权限访问该团队作品集', icon: 'none' })
      return
    }
    const timelineGuideRequested = options.shareGuide === TIMELINE_SHARE_GUIDE_VALUE
    this.anonymousSessionId = isWechatTimelineSinglePage()
      ? createAnonymousSessionId()
      : ''
    this.setData({
      shareCode,
      sourceType: options.scene && !options.shareCode ? 'QR_CODE' : 'WECHAT_SHARE_CARD',
      showNavigationBack: hasPreviousPage(),
      timelineGuideRequested,
      timelineGuideVisible: false,
      timelineSharePortfolioId: timelineGuideRequested ? positiveId(options.sharePortfolioId) : 0,
      timelineShareRecordEnabled: false
    })
    if (wx.showShareMenu) {
      wx.showShareMenu({ menus: SHARE_MENU_ITEMS })
    }
    return this.open()
  },
  async open() {
    if (this.visitPageUnloaded || this.data.loading || !this.data.shareCode) return
    this.setData({ loading: true, errorMessage: '' })
    if (!this.browserContext || this.browserContext.isInvalid()) this.browserContext = createPortfolioVisitActivity(this)
    const idempotencyKey = this.browserContext.idempotencyKey; if (!this.data.pendingOpenKey) this.setData({ pendingOpenKey: idempotencyKey })
    try { const session = await openTeamVisitorSession({ shareCode: this.data.shareCode, sourceType: this.data.sourceType, idempotencyKey, browserContext: this.browserContext, anonymousSessionId: this.anonymousSessionId, requestFn: request, wxApi: wx }); this.applySession(session); this.setData({ pendingOpenKey: '' }) } catch (error) { if (this.visitPageUnloaded || error && error.contextDisposed) return; if (this.browserContext) this.browserContext.setDisplayable(false); this.setData({ timelineGuideVisible: false, timelineShareRecordEnabled: false }); if (this.handleUnavailableError(error)) return; this.setData({ errorMessage: '团队作品集暂不可访问' }) } finally { if (!this.visitPageUnloaded) this.setData({ loading: false }) }
  },
  applySession(session, preferredMenuKey = '') {
    const normalizedRender = normalizeTeamVisitorPortfolio(session)
    const render = preferredMenuKey
      ? switchTeamPortfolioMenu(normalizedRender, preferredMenuKey)
      : normalizedRender
    this.syncBackgroundAudio(render.backgroundAudio, true)
    const displayable = !render.underMaintenance && render.components.length > 0
    const activeComponents = Array.isArray(render.activeComponents) ? render.activeComponents : []
    const themeMode = render.themeMode === 'dark' ? 'dark' : 'light'
    this.setData({
      render,
      portfolio: render,
      componentBuckets: buckets(activeComponents),
      visitorKey: render.visitorKey,
      visitRecordId: render.visitRecordId,
      visitorProfileToken: render.visitorProfileToken,
      visitorProfileAuthVisible: render.needVisitorProfile,
      empty: activeComponents.length === 0,
      underMaintenance: render.underMaintenance,
      themeMode,
      backgroundColor: render.style.backgroundColor,
      navigationColor: themeMode === 'dark' ? '#F8F9FA' : '#212529',
      brandLogoUrl: themeMode === 'dark' ? DARK_LOGO_URL : LIGHT_LOGO_URL,
      timelineGuideVisible: Boolean(this.data.timelineGuideRequested && displayable),
      timelineShareRecordEnabled: Boolean(this.data.timelineSharePortfolioId && displayable)
    }, () => {
      if (this.browserContext) {
        this.browserContext.setDisplayable(!render.underMaintenance)
        if (this.visitPageVisible) this.browserContext.show(this)
      }
    })
  },
  handleRetry() { this.open() },
  handleMenuTap(event) {
    const menuKey = (event.detail && event.detail.menuKey) ||
      (event.currentTarget && event.currentTarget.dataset.key)
    const leavingComponents = this.data.portfolio.activeComponents
    startTeamPortfolioMenuTransition(this, menuKey, {
      onBeforeExit: () => { this.stopSingleWorkVideos(); this.clearVideoPreview() },
      switchPortfolio: switchTeamPortfolioMenu,
      buildSwitchPatch: (portfolio) => ({
        render: portfolio,
        componentBuckets: buckets(portfolio.activeComponents),
        empty: portfolio.activeComponents.length === 0,
        scheduleResults: clearTeamPortfolioMenuComponentState(this.data.scheduleResults, leavingComponents),
        contactForms: clearTeamPortfolioMenuComponentState(this.data.contactForms, leavingComponents),
        contactSubmitting: clearTeamPortfolioMenuComponentState(this.data.contactSubmitting, leavingComponents),
        contactModalVisible: clearTeamPortfolioMenuComponentState(this.data.contactModalVisible, leavingComponents),
        pendingContactKeys: clearTeamPortfolioMenuComponentState(this.data.pendingContactKeys, leavingComponents)
      })
    })
  },
  visitorRequest(options) { return requestWithTeamVisitorSessionRefresh({ browserContext: this.browserContext, shareCode: this.data.shareCode, sourceType: this.data.sourceType, anonymousSessionId: this.anonymousSessionId, requestFn: request, wxApi: wx, requestOptions: options, onRefresh: this.browserContext ? undefined : async (session) => this.applySession(session, this.data.portfolio.activeMenuKey), refreshRequestOptions: (session, original) => Object.assign({}, original, { data: Object.assign({}, original.data, original.data && original.data.visitorProfileToken ? { visitorProfileToken: session.visitorProfileToken } : {}) }) }) },
  profileRequest(options) { const data = Object.assign({}, options.data, { visitorProfileToken: this.data.visitorProfileToken }); return this.visitorRequest(Object.assign({}, options, { data })) },
  handleUnavailableError(error) {
    const unavailable = showTeamPortfolioUnavailableToast(error)
    if (unavailable && this.browserContext) this.browserContext.invalidate()
    return unavailable
  },
  sendEvent(payload) { return submitTeamVisitorEvent((options) => this.visitorRequest(options), this.data.shareCode, Object.assign({}, payload, { idempotencyKey: createIdempotencyKey() })).catch((error) => { this.handleUnavailableError(error); return null }) },
  handleWorkDetail(event) {
    const componentKey = event.detail && event.detail.componentKey
    const opened = openWorkDetail(this, {
      componentKey,
      components: this.data.portfolio.activeComponents || this.data.portfolio.components,
      theme: this.data.portfolio.style,
      browserContext: this.browserContext,
      onWorkEvent: (work) => this.sendEvent({
        eventType: work.mediaType === MEDIA_TYPE_VIDEO ? VIDEO_PLAYED_EVENT_TYPE : WORK_VIEWED_EVENT_TYPE,
        componentKey, workId: work.workId, memberUserId: work.memberUserId, mediaType: work.mediaType,
        durationSeconds: 0
      }),
      route: WORK_DETAIL_ROUTE
    })
    if (opened) { this.pauseBackgroundAudio(); this.stopSingleWorkVideos() }
    return opened
  },
  handleImagePreview(event) { const item = event.detail && event.detail.item; const componentKey = event.currentTarget.dataset.key; if (!item) return; this.sendEvent({ eventType: WORK_VIEWED_EVENT_TYPE, componentKey, workId: item.workId, mediaType: MEDIA_TYPE_IMAGE }); const url = item.mediaUrl || item.coverUrl; if (url) wx.previewImage({ current: url, urls: [url] }) },
  handleSingleWorkPreview(event) { const detail = event.detail || {}; const work = detail.work; const visitorEvent = buildTeamSingleWorkViewEvent(detail); if (!visitorEvent || !work || !work.mediaUrl) return; this.sendEvent(visitorEvent); wx.previewImage({ current: work.mediaUrl, urls: [work.mediaUrl] }) },
  handleSingleWorkActivate(event) {
    this.pauseBackgroundAudio(); const detail = event.detail || {}; const work = detail.work; if (!detail.componentKey || !work) return; this.pauseSingleWorkVideos(detail.componentKey); this.setData({ activeSingleWorkVideoKey: detail.componentKey }); this.sendEvent({ eventType: VIDEO_PLAYED_EVENT_TYPE, componentKey: detail.componentKey, workId: work.workId, mediaType: MEDIA_TYPE_VIDEO, durationSeconds: 0 }) },
  handleVideoCarouselPlay(event) { const detail = event && event.detail || {}; const work = detail.work || {}; if (!work.mediaUrl) { wx.showToast({ title: '视频地址缺失', icon: 'none' }); return false }; this.sendEvent({ eventType: VIDEO_PLAYED_EVENT_TYPE, componentKey: detail.componentKey || '', workId: work.workId, mediaType: MEDIA_TYPE_VIDEO, durationSeconds: 0 }); return this.openVideoPreview(work) },
  handleSingleWorkVideoError() { this.stopSingleWorkVideos(); wx.showToast({ title: '视频播放失败，请重试', icon: 'none' }) },
  pauseSingleWorkVideos(exceptKey) { const children = this.selectAllComponents ? this.selectAllComponents('.team-single-work-instance') : []; (children || []).forEach((child) => { if (!exceptKey || child.properties.componentKey !== exceptKey) child.pauseVideo && child.pauseVideo() }) },
  stopSingleWorkVideos() { this.pauseSingleWorkVideos(''); this.setData({ activeSingleWorkVideoKey: '' }) },
  clearVideoPreview() {
    // 返回作品集或打开失败时，解除文字组件的背景视频暂停。
    if (this.data.videoPreviewVisible) this.setData({ videoPreviewVisible: false })
  },

  openVideoPreview(work = {}) {
    if (this.videoPlayerDisposed || this.data.videoPreviewVisible) return false
    this.pauseBackgroundAudio()
    const mediaUrl = String(work.mediaUrl || '')
    if (!mediaUrl) {
      wx.showToast({ title: '视频地址缺失', icon: 'none' })
      return false
    }
    this.stopSingleWorkVideos()
    this.setData({ videoPreviewVisible: true })
    return openVideoPlayer({ url: mediaUrl, poster: work.coverUrl || '', title: work.title || '',
      route: VIDEO_PLAYER_ROUTE, browserContext: this.browserContext || null }, wx)
      .then((opened) => {
        // 导航成功后来源页保持暂停，失败或返回作品集时才恢复背景视频。
        if (!opened && !this.videoPlayerDisposed) this.clearVideoPreview()
        return opened
      })
  },

  onShow() { this.visitPageVisible = true; if (this.browserContext) this.browserContext.show(this); this.clearVideoPreview(); this.showBackgroundAudio() },
  onHide() {
    this.visitPageVisible = false; if (this.browserContext) this.browserContext.hide(this)
    this.hideBackgroundAudio(); this.stopSingleWorkVideos() },
  onUnload() {
    if (this.remoteFontPage) this.remoteFontPage.dispose()
    this.videoPlayerDisposed = true
    this.visitPageUnloaded = true
    this.visitPageVisible = false; if (this.browserContext) this.browserContext.dispose()
    this.destroyBackgroundAudio(); this.stopSingleWorkVideos(); clearTeamPortfolioMenuTransitionTimers(this) },
  handleMemberPortfolio(event) { const detail = event.detail || {}; const componentKey = event.currentTarget.dataset.key; this.sendEvent({ eventType: 'MEMBER_PORTFOLIO_OPENED', componentKey, memberPortfolioId: detail.portfolioId }); if (detail.shareCode) wx.navigateTo({ url: `${PERSONAL_VISITOR_URL}?shareCode=${encodeURIComponent(detail.shareCode)}&${PERSONAL_VISITOR_TEAM_SOURCE_QUERY}` }) },
  async handleScheduleQuery(event) {
    const detail = event.detail || {}
    const child = this.selectComponent(`#schedule-${detail.componentKey}`)
    const menuInteraction = captureTeamPortfolioMenuInteraction(this)
    try {
      const result = await queryTeamVisitorSchedule(
        (options) => this.visitorRequest(options),
        this.data.shareCode,
        {
          componentKey: detail.componentKey,
          queriedDate: detail.queriedDate,
          idempotencyKey: detail.idempotencyKey
        }
      )
      if (!isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) return
      this.setData({
        scheduleResults: Object.assign({}, this.data.scheduleResults, {
          [detail.componentKey]: result
        })
      })
      if (child && child.resolveQuery) child.resolveQuery({ detail: result })
    } catch (error) {
      if (this.handleUnavailableError(error)) {
        if (isTeamPortfolioMenuInteractionCurrent(this, menuInteraction) &&
            child && child.rejectQuery) {
          child.rejectQuery({
            detail: {
              message: '当前团队作品集不可用',
              clearPendingIdempotencyKey: true
            }
          })
        }
        return
      }
      if (!isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) return
      if (child && child.rejectQuery) {
        child.rejectQuery({
          detail: {
            message: '档期查询失败，请重试',
            clearPendingIdempotencyKey: !isUncertainFailure(error)
          }
        })
      }
      wx.showToast({ title: '档期查询失败，请重试', icon: 'none' })
    }
  },
  handleContactInput(event) { const detail = event.detail || {}; const componentKey = event.currentTarget.dataset.key; const pendingContactKeys = Object.assign({}, this.data.pendingContactKeys); delete pendingContactKeys[componentKey]; this.setData({ contactForms: Object.assign({}, this.data.contactForms, { [componentKey]: detail.form || {} }), pendingContactKeys }) },
  handleContactOpen(event) { const componentKey = event.currentTarget.dataset.key; this.setData({ contactModalVisible: Object.assign({}, this.data.contactModalVisible, { [componentKey]: true }) }); this.sendEvent({ eventType: 'CONTACT_FORM_EXPOSED', componentKey }) },
  handleContactClose(event) { const componentKey = event.currentTarget.dataset.key; this.setData({ contactModalVisible: Object.assign({}, this.data.contactModalVisible, { [componentKey]: false }) }) },
  async handleContactSubmit(event) {
    const componentKey = event.currentTarget.dataset.key
    const form = event.detail && event.detail.form
    if (this.data.contactSubmitting[componentKey]) return
    const menuInteraction = captureTeamPortfolioMenuInteraction(this)
    const idempotencyKey =
      this.data.pendingContactKeys[componentKey] || createIdempotencyKey()
    this.setData({
      contactSubmitting: Object.assign({}, this.data.contactSubmitting, {
        [componentKey]: true
      }),
      pendingContactKeys: Object.assign({}, this.data.pendingContactKeys, {
        [componentKey]: idempotencyKey
      })
    })
    const child = this.selectComponent(`#contact-form-${componentKey}`)
    try {
      await submitTeamContactLead(
        (options) => this.visitorRequest(options),
        this.data.shareCode,
        form,
        {
          visitRecordId: this.data.visitRecordId,
          sourceType: this.data.sourceType,
          idempotencyKey
        }
      )
      if (!isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) return
      if (child && child.completeSubmit) {
        child.completeSubmit({ detail: { success: true } })
      }
      const pendingContactKeys = Object.assign({}, this.data.pendingContactKeys)
      delete pendingContactKeys[componentKey]
      this.setData({
        contactForms: Object.assign({}, this.data.contactForms, {
          [componentKey]: createTeamContactLeadForm()
        }),
        contactModalVisible: Object.assign(
          {},
          this.data.contactModalVisible,
          { [componentKey]: false }
        ),
        pendingContactKeys
      })
      wx.showToast({ title: '已提交给团队', icon: 'success' })
    } catch (error) {
      if (this.handleUnavailableError(error)) return
      if (!isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) return
      if (!isUncertainFailure(error)) {
        const pendingContactKeys = Object.assign({}, this.data.pendingContactKeys)
        delete pendingContactKeys[componentKey]
        this.setData({ pendingContactKeys })
      }
      if (child && child.completeSubmit) {
        child.completeSubmit({
          detail: { success: false, message: '提交失败，请检查信息' }
        })
      }
      wx.showToast({ title: '提交失败，请检查信息', icon: 'none' })
    } finally {
      if (isTeamPortfolioMenuInteractionCurrent(this, menuInteraction)) {
        this.setData({
          contactSubmitting: Object.assign({}, this.data.contactSubmitting, {
            [componentKey]: false
          })
        })
      }
    }
  },
  handleQrInteract(event) { const detail = event.detail || {}; this.sendEvent({ eventType: 'QR_CODE_INTERACTED', componentKey: detail.componentKey, action: detail.action }) },
  handleProfileInput(event) { const field = event.currentTarget.dataset.field; this.setData({ visitorProfileForm: Object.assign({}, this.data.visitorProfileForm, { [field]: event.detail.value }) }) },
  async handleChooseAvatar() { if (!wx.chooseMedia) return; try { const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: resolve, fail: reject })); this.setData({ visitorProfileForm: Object.assign({}, this.data.visitorProfileForm, { avatarPath: media.tempFiles[0].tempFilePath }) }) } catch (error) {} },
  handleProfileSkip() { this.setData({ visitorProfileAuthVisible: false }) },
  async handleProfileSave() { if (this.data.visitorProfileSaving) return; const form = this.data.visitorProfileForm; if (!form.nickname || !form.avatarPath) return wx.showToast({ title: '请完善头像和昵称', icon: 'none' }); this.setData({ visitorProfileSaving: true }); try { await uploadTeamVisitorProfile({ shareCode: this.data.shareCode, avatarPath: form.avatarPath, nickname: form.nickname, visitorProfileToken: this.data.visitorProfileToken, requestFn: (options) => this.profileRequest(options) }); this.setData({ visitorProfileAuthVisible: false }) } catch (error) { if (this.handleUnavailableError(error)) return; wx.showToast({ title: '资料保存失败，请重试', icon: 'none' }) } finally { this.setData({ visitorProfileSaving: false }) } },
  handleCloseTimelineGuide() { this.setData({ timelineGuideRequested: false, timelineGuideVisible: false }) },
  handleTimelineGuideBack() { if (hasPreviousPage()) wx.navigateBack({ delta: 1 }) },
  onShareAppMessage() { return { title: shareTitle(this.data.render), path: `/pages/team-portfolios/visitor-portfolio/team-visitor-portfolio?shareCode=${encodeURIComponent(this.data.shareCode)}`, imageUrl: this.data.render.share && this.data.render.share.coverUrl } },
  recordTimelineShare() { const portfolioId = this.data.timelineShareRecordEnabled ? positiveId(this.data.timelineSharePortfolioId) : 0; if (!portfolioId) return; this.setData({ timelineSharePortfolioId: 0, timelineShareRecordEnabled: false }); request({ url: `/api/mine/team-portfolios/${portfolioId}/share-records`, method: 'POST', data: { shareChannel: SHARE_CHANNEL_WECHAT_TIMELINE, shareScene: SHARE_SCENE_TEAM_PORTFOLIO_LIST } }).catch(() => {}) },
  onShareTimeline() { this.recordTimelineShare(); return { title: shareTitle(this.data.render), query: `shareCode=${encodeURIComponent(this.data.shareCode)}`, imageUrl: this.data.render.share && this.data.render.share.coverUrl } }
})
