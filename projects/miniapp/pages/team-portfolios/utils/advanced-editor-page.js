const { referenceBasis, normalizeReferenceItems, referenceNextNo, referenceSaveFields, referenceSaveDeferred, hasReferenceState } = require('./advanced-reference-attachments')
const { request } = require('../../../utils/request.js')
const { handleMaintainerAuthRequired } = require('../../../utils/session.js')
const { PUBLISH_DISCLAIMER_CONTENT } = require('./portfolio-publish-disclaimer')
const { CLIENT_ADVANCED_VERSION, AI_CONTRACT_VERSION, SOURCE_MODES, candidateVersionError, ADVANCED_API_PREFIX, advancedEndpoint, advancedQuery, captureBasis, clone, normalizeContext, makeKey, text } = require('./advanced-portfolios')
const SAVE_INCOMPLETE_MESSAGE = '草稿已创建，当前内容尚未完整保存，请重试'
const ADVANCED_PUBLISH_SCENE = 'MAINTAIN_ADVANCED_PORTFOLIO'
const SOURCE_PAGE_SIZE = 100
const PERSONAL_WORK_DETAIL_PREFIX = '/api/mine/works'

/** 来源摘要只用于界面展示，不写入稳定编号上下文或候选生成依据。 */
function advancedSourceFacts(page) {
  const owner = JSON.stringify([page.data.advancedOwnerType, text(page.data.advancedOwnerId || page.data.teamId)])
  if (page.advancedSourceFactsOwner !== owner) {
    page.advancedSourceFactsOwner = owner
    page.advancedSourceFacts = {}
  }
  return page.advancedSourceFacts
}

/** 同一来源的编号来自 aiContext；缩略图与标题来自当前会话或有权限的详情接口。 */
function refreshAdvancedSourceItems(page, sourceItems = []) {
  const facts = advancedSourceFacts(page)
  if (Array.isArray(sourceItems)) sourceItems.forEach(item => {
    const sourceId = text(item.sourceId || item.id || item.workId || item.portfolioId)
    const kind = item.kind || 'WORK'
    if (!sourceId) return
    facts[`${kind}:${sourceId}`] = {
      title: text(item.title), coverUrl: item.thumbnailUrl || item.coverUrl || '', available: item.available !== false,
      unavailableReason: item.unavailableReason || ''
    }
  })
  const items = normalizeContext(page.data.aiContext).sources.map(source => {
    const fact = facts[`${source.kind}:${source.sourceId}`] || {}
    return { ...source, title: fact.title || `${source.sourceNo}号${source.kind === 'MEMBER_PORTFOLIO' ? '成员作品集' : '作品'}`,
      coverUrl: fact.coverUrl || '', available: fact.available !== false, unavailableReason: fact.unavailableReason || '' }
  })
  page.setData({ advancedSourceItems: items })
}

/** 保存重开后恢复已选缩略图；失效项保留原编号，异步摘要不触发编辑修订。 */
async function hydrateAdvancedSourceItems(page) {
  const sequence = page.advancedSourceHydrationSequence = (page.advancedSourceHydrationSequence || 0) + 1
  const sources = normalizeContext(page.data.aiContext).sources
  const facts = advancedSourceFacts(page)
  const owner = page.advancedSourceFactsOwner
  const missing = sources.filter(source => !facts[`${source.kind}:${source.sourceId}`])
  const isCurrent = () => !page.advancedSourceDisposed && sequence === page.advancedSourceHydrationSequence
    && owner === page.advancedSourceFactsOwner
  const requestFn = options => page.advancedRequest ? page.advancedRequest(options) : request(options)
  if (!missing.length) return
  if (page.data.advancedOwnerType === 'TEAM') {
    try {
      let currentPage = 1
      let more = true
      while (more && missing.some(source => !facts[`${source.kind}:${source.sourceId}`])) {
        const response = await requestFn({ url: `${ADVANCED_API_PREFIX}/sources`, data: {
          ownerType: 'TEAM', ownerId: page.data.advancedOwnerId || page.data.teamId, page: currentPage, pageSize: SOURCE_PAGE_SIZE
        } })
        if (!isCurrent()) return
        const items = response.records || response.items || []
        refreshAdvancedSourceItems(page, items)
        more = items.length > 0 && (response.hasMore === true || currentPage * SOURCE_PAGE_SIZE < Number(response.total))
        currentPage += 1
      }
      if (isCurrent()) refreshAdvancedSourceItems(page, missing.filter(source => !facts[`${source.kind}:${source.sourceId}`]).map(source => ({
        ...source, available: false, unavailableReason: '来源已移除、下线或授权失效'
      })))
    } catch (error) {
      if (isCurrent()) refreshAdvancedSourceItems(page, missing.filter(source => !facts[`${source.kind}:${source.sourceId}`]).map(source => ({
        ...source, unavailableReason: '来源摘要暂未加载，请重新管理内容核对'
      })))
    }
  } else {
    await Promise.all(missing.map(async source => {
      try {
        const response = await requestFn({ url: `${PERSONAL_WORK_DETAIL_PREFIX}/${encodeURIComponent(source.sourceId)}` })
        if (!isCurrent()) return
        const work = response.work || response
        refreshAdvancedSourceItems(page, [{ ...work, ...source,
          available: work.auditStatus === 'PASSED' && work.status !== 'DISABLED', unavailableReason: '作品已不可用于新绑定' }])
      } catch (error) {
        if (isCurrent()) refreshAdvancedSourceItems(page, [{ ...source, available: false, unavailableReason: '作品已移除或不可用' }])
      }
    }))
  }
}

/** 发布确认展示服务端生效规则，不把方案中的积分数字写死在界面。 */
async function confirmAdvancedPortfolioPublish(requestFn = request, wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  try {
    const result = await requestFn({ url: '/api/mine/points/calculate', method: 'POST', data: { sceneCode: ADVANCED_PUBLISH_SCENE, actionCount: 1 } })
    const pointsChange = Number(result.pointsChange)
    if (!Number.isFinite(pointsChange)) throw new Error('发布积分规则暂不可用')
    return new Promise(resolve => runtimeWx.showModal({ title: '发布高级作品集',
      content: `本次发布预计消耗 ${Math.abs(pointsChange)} 积分，发布成功后按生效规则扣除。${PUBLISH_DISCLAIMER_CONTENT}`,
      confirmText: '确认发布', cancelText: '取消', success: result => resolve(result.confirm === true), fail: () => resolve(false) }))
  } catch (error) {
    if (error.authRequired) { handleMaintainerAuthRequired(error.message); return false }
    if (runtimeWx && runtimeWx.showToast) runtimeWx.showToast({ title: error.message || '积分规则加载失败，请重试', icon: 'none' })
    return false
  }
}

/** 仅分派高级公开契约，普通选择组件接口继续走原权限与媒体查询。 */
function editorRequest(page, options, requestFn = request) {
  if (!page.data.advancedEditing) return requestFn(options)
  let url = options.url
  const sourceRoute = url.match(/^\/api\/mine\/team-portfolios\/\d+\/components\/(carousel|video-carousel|member-portfolio-grid|member-portfolio-list)\/members(?:\/(\d+)\/(works|portfolios))?$/)
  if (sourceRoute && page.data.advancedOwnerType === 'TEAM') {
    return advancedTeamComponentSources(page, sourceRoute, options, requestFn)
  }
  const memberPreviewRoute = url.match(/^\/api\/mine\/team-portfolios\/(\d+)(\/member-portfolios\/\d+\/(?:published-preview|schedule-options|schedule-query-preview))$/)
  if (memberPreviewRoute) url = advancedEndpoint(memberPreviewRoute[1], memberPreviewRoute[2])
  const match = url.match(/^\/api\/mine\/(?:portfolios|team-portfolios)\/([^/]+)(\/.*)?$/)
  if (match && /^\d+$/.test(match[1]) && /^(?:|\/draft|\/publish|\/delete|\/preview|\/published-preview|\/asset\/upload-ticket|\/fonts\/prepare|\/share-records|\/miniapp-code|\/schedule-options|\/schedule-query-preview)$/.test(match[2] || '')) url = advancedEndpoint(match[1], match[2] || '')
  if (url === '/api/mine/portfolios/hyperlink-targets') url = `${ADVANCED_API_PREFIX}/hyperlink-targets`
  let data = options.data
  if (/\/component-library$/.test(url)) {
    data = { ...(data || {}), templateType: 'ADVANCED', clientAdvancedVersion: CLIENT_ADVANCED_VERSION }
    delete data.portfolioTimeline
  } else if (/\/fonts\/prepare$/.test(url)) {
    const capabilities = { ...((data || {}).clientCapabilities || {}) }; delete capabilities.portfolioTimeline
    data = { ...(data || {}), clientAdvancedVersion: CLIENT_ADVANCED_VERSION, clientCapabilities: capabilities }
  }
  return requestFn({ ...options, url, ...(data === undefined ? {} : { data }) })
}

/** 高级成员作品集选择使用独立授权来源；普通组件控件仍消费原来的字段结构。 */
async function advancedTeamComponentSources(page, route, options, requestFn) {
  const component = route[1]
  const memberUserId = route[2]
  const sourceKind = component.startsWith('member-portfolio-') ? 'MEMBER_PORTFOLIO' : 'WORK'
  const common = { ownerType: 'TEAM', ownerId: page.data.teamId || page.data.advancedOwnerId, sourceKind,
    memberUserId: memberUserId || undefined, keyword: options.data && options.data.keyword || '', pageSize: 100 }
  let response = await requestFn({ url: `${ADVANCED_API_PREFIX}/sources`, data: { ...common, page: 1 } })
  if (!memberUserId) return (response.members || []).map(member => ({ ...member, displayName: member.displayName || member.memberName }))
  let records = response.records || response.items || []
  let currentPage = 1
  while (response.hasMore === true || currentPage * 100 < Number(response.total)) {
    currentPage += 1
    response = await requestFn({ url: `${ADVANCED_API_PREFIX}/sources`, data: { ...common, page: currentPage } })
    const next = response.records || response.items || []
    if (!next.length) break
    records = records.concat(next)
  }
  const mapped = records.filter(item => item.available !== false).map(item => ({ ...item,
    workId: sourceKind === 'WORK' ? item.sourceId : undefined, portfolioId: sourceKind === 'MEMBER_PORTFOLIO' ? item.sourceId : undefined,
    memberDisplayName: item.memberName || item.memberDisplayName || '', coverUrl: item.coverUrl || item.thumbnailUrl || '' }))
  if (sourceKind === 'MEMBER_PORTFOLIO') return mapped
  const filtered = mapped.filter(item => component === 'video-carousel' ? item.mediaType === 'VIDEO' : item.mediaType === 'IMAGE')
  if (component !== 'video-carousel') return filtered
  const requestedPage = Number(options.data && options.data.page) || 1
  const pageSize = Number(options.data && options.data.pageSize) || 20
  return { works: filtered.slice((requestedPage - 1) * pageSize, requestedPage * pageSize), page: requestedPage, pageSize,
    total: filtered.length, hasMore: requestedPage * pageSize < filtered.length }
}

function editorState(page) {
  return { clientAdvancedVersion: CLIENT_ADVANCED_VERSION, aiContractVersion: AI_CONTRACT_VERSION, sourceMode: SOURCE_MODES.SELECTED, ownerType: page.data.advancedOwnerType, ownerId: page.data.advancedOwnerId || page.data.teamId,
    portfolioId: page.data.portfolioId || null, draftRevision: page.data.draftRevision, config: page.data.config,
    aiContext: page.data.aiContext, ...(hasReferenceState(page.data) ? { aiReferenceAttachments: page.data.aiReferenceAttachments, aiReferenceNextNo: page.data.aiReferenceNextNo } : {}), localEditRevision: page.data.localEditRevision }
}

/** setData 统一跟踪完整配置，覆盖普通编辑器全部手动参数入口。 */
function installAdvancedEditor(page, options = {}, team = false) {
  if (options.templateType !== 'ADVANCED') return
  page.setData({ advancedEditing: true, templateType: 'ADVANCED', advancedOwnerType: team ? 'TEAM' : 'USER',
    advancedOwnerId: options.teamId || options.ownerId || '', aiContext: normalizeContext(), aiPrompt: '', localEditRevision: 0,
    savedAdvancedVersion: 1, advancedDirty: false, advancedCanUndo: false, advancedSourceItems: [], aiReferenceAttachments: [], aiReferenceNextNo: 1 })
  const onUnload = page.onUnload
  page.onUnload = function (...args) {
    this.advancedSourceDisposed = true
    this.advancedSourceHydrationSequence = (this.advancedSourceHydrationSequence || 0) + 1
    if (onUnload) return onUnload.apply(this, args)
  }
  const setData = page.setData.bind(page)
  let signature = captureBasis(editorState(page))
  const contentSignature = () => JSON.stringify([page.data.config, normalizeContext(page.data.aiContext), page.data.aiPrompt || '', referenceBasis(page.data)])
  let savedContent = contentSignature()
  page.setData = function (patch, callback) {
    setData(patch, callback)
    const next = captureBasis(editorState(page))
    if (next !== signature) {
      if (!Object.prototype.hasOwnProperty.call(patch, 'localEditRevision')) setData({ localEditRevision: (page.data.localEditRevision || 0) + 1 })
      signature = captureBasis(editorState(page))
    }
    const nextContent = contentSignature()
    if (nextContent !== savedContent) {
      savedContent = nextContent
      if (!page.advancedApplying) { page.advancedUndo = null; setData({ advancedCanUndo: false }) }
      if (!page.data.advancedDirty && typeof wx !== 'undefined' && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '当前编辑尚未保存，离开后将丢失' })
      setData({ advancedDirty: true })
    }
    if (Object.prototype.hasOwnProperty.call(patch, 'aiContext') || Object.prototype.hasOwnProperty.call(patch, 'advancedOwnerId')) refreshAdvancedSourceItems(page)
  }
  if (typeof wx !== 'undefined' && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '当前编辑尚未保存，离开后将丢失' })
  const channel = page.getOpenerEventChannel && page.getOpenerEventChannel()
  if (channel && channel.on) channel.on('advancedInitial', initial => {
    const versionError = candidateVersionError(initial)
    if (versionError) { wx.showToast({ title: versionError, icon: 'none' }); return }
    page.advancedApplying = true
    page.setData({ advancedOwnerId: initial.ownerId, aiContext: normalizeContext(initial.aiContext), aiPrompt: initial.prompt || '', aiReferenceAttachments: normalizeReferenceItems(initial.aiReferenceAttachments), aiReferenceNextNo: referenceNextNo(initial.aiReferenceAttachments, initial.aiReferenceNextNo), localEditRevision: initial.localEditRevision || 1 })
    refreshAdvancedSourceItems(page, initial.sourceItems)
    if (team) page.updateConfig(initial.config)
    else page.applyEditorConfig(initial.config)
    page.applyAdvancedRenderMetadata(initial.renderData)
    if (team && page.loadSingleWorkRowSummaries) page.loadSingleWorkRowSummaries(initial.config)
    if (!team && page.loadSingleWorkSummaries) page.loadSingleWorkSummaries(initial.config)
    page.advancedApplying = false
  })
}

function loadAdvancedMetadata(page, detail) {
  if (!page.data.advancedEditing) return
  page.setData({ savedAdvancedVersion: (detail.config || {}).advancedPortfolioVersion || 1, advancedOwnerId: detail.ownerId || page.data.advancedOwnerId,
    aiContext: normalizeContext(detail.aiContext), aiPrompt: detail.aiPrompt || '', aiReferenceAttachments: normalizeReferenceItems((detail.aiReferenceAttachments || []).map(item => ({ ...item, saved: true }))), aiReferenceNextNo: referenceNextNo(detail.aiReferenceAttachments, detail.aiReferenceNextNo) })
  markAdvancedSaved(page)
  return page.advancedSourceHydration = hydrateAdvancedSourceItems(page)
}

function markAdvancedSaved(page) {
  if (!page.data.advancedEditing) return
  page.advancedUndo = null
  page.advancedDraftCreatedLocally = false
  page.setData({ advancedDirty: false, advancedCanUndo: false,
    aiReferenceAttachments: normalizeReferenceItems((page.data.aiReferenceAttachments || []).map(item => ({ ...item, saved: true }))) })
  if (typeof wx !== 'undefined' && wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
}

const advancedEditorPageMethods = {
  advancedRequest(options) { return editorRequest(this, options) },
  applyAdvancedRenderMetadata(renderData) {
    const backgroundAudio = renderData && renderData.backgroundAudio || null
    if (this.syncBackgroundAudio) this.syncBackgroundAudio(backgroundAudio ? { ...backgroundAudio, enabled: false } : null)
    this.setData({ backgroundAudioSelected: backgroundAudio })
  },
  advancedDraftPayload(payload) {
    if (!this.data.advancedEditing) return payload
    const clientCapabilities = { ...(payload.clientCapabilities || { portfolioRemoteFont: 1 }) }; delete clientCapabilities.portfolioTimeline
    return this.data.advancedEditing ? { ...payload, clientAdvancedVersion: CLIENT_ADVANCED_VERSION, clientCapabilities,
      ...referenceSaveFields(Object.prototype.hasOwnProperty.call(payload, 'aiReferenceAttachments') ? payload : this.data),
      aiContext: normalizeContext(Object.prototype.hasOwnProperty.call(payload, 'aiContext') ? payload.aiContext : this.data.aiContext),
      aiPrompt: Object.prototype.hasOwnProperty.call(payload, 'aiPrompt') ? payload.aiPrompt : this.data.aiPrompt || '' } : payload
  },
  /** 服务端只回填保存元数据，保存期间的新正文保持原样。 */
  applyAdvancedSavedMetadata(response = {}) {
    if (!this.data.advancedEditing) return
    const version = response.config && response.config.advancedPortfolioVersion
    if (!Number.isInteger(version) || version < 1) return
    this.setData({ config: { ...this.data.config, advancedPortfolioVersion: version }, savedAdvancedVersion: version })
  },
  /** 只标记本次成功请求包含的附件，期间新编辑仍由脏状态保留。 */
  advancedReferencesSaved(payload) {
    if (!this.data.advancedEditing || !Array.isArray(payload.aiReferenceAttachments)) return
    const savedIds = new Set(payload.aiReferenceAttachments.map(item => item.attachmentId))
    this.setData({ aiReferenceAttachments: normalizeReferenceItems((this.data.aiReferenceAttachments || [])
      .map(item => savedIds.has(item.attachmentId) ? { ...item, saved: true } : item)) })
  },
  advancedSaveMessage(fallback) {
    return this.data.advancedEditing && referenceSaveDeferred(this.data)
      ? '正文草稿已保存，附件待就绪后再次保存' : fallback
  },
  advancedDraftChanged(payload) {
    return this.data.advancedEditing && (JSON.stringify(normalizeContext(this.data.aiContext)) !== JSON.stringify(normalizeContext(payload.aiContext))
      || (this.data.aiPrompt || '') !== (payload.aiPrompt || '')
      || JSON.stringify(referenceBasis(this.data)) !== JSON.stringify(referenceBasis(payload)))
  },
  createAdvancedDraft() {
    if (this.advancedCreationResultUnknown) return Promise.reject(new Error('创建结果暂无法确认，请先核对列表中的草稿'))
    return this.advancedRequest({ url: ADVANCED_API_PREFIX, method: 'POST', data: { ownerType: this.data.advancedOwnerType,
      ownerId: this.data.advancedOwnerId || this.data.teamId || null, clientAdvancedVersion: CLIENT_ADVANCED_VERSION } })
      .then(result => {
        if (!result || !result.portfolioId) throw new Error('创建响应缺少作品集 ID')
        this.advancedDraftCreatedLocally = true
        return result
      }).catch(error => {
        const statusCode = Number(error.statusCode)
        if (this.data.advancedOwnerType === 'TEAM' && !(statusCode >= 400 && statusCode < 500 && statusCode !== 408)) {
          this.advancedCreationResultUnknown = true
          this.setData({ advancedCreationUncertain: true })
        }
        throw error
      })
  },
  /** 创建没有幂等契约；只有用户核对后选定已有草稿或明确未创建才能继续。 */
  async handleAdvancedVerifyCreation() {
    if (!this.advancedCreationResultUnknown) return
    const response = await this.advancedRequest({ url: '/api/mine/team-portfolios' }).catch(error => {
      wx.showToast({ title: error.message || '列表核对失败，请重试', icon: 'none' }); return null
    })
    if (!response) return
    const items = Array.isArray(response) ? response : response.items || response.portfolios || []
    this.setData({ advancedRecoveryDrafts: items.filter(item => item.templateType === 'ADVANCED' && String(item.teamId) === String(this.data.teamId)) })
    wx.showModal({ title: '核对创建结果', content: '请检查下方该团队高级草稿。如果已有本次草稿，选择继续该草稿；确认列表没有本次草稿后，才可允许重新创建。', showCancel: false })
  },
  handleAdvancedSelectCreated(event) {
    const portfolioId = event.currentTarget.dataset.id
    const draft = (this.data.advancedRecoveryDrafts || []).find(item => String(item.portfolioId) === String(portfolioId))
    if (!draft) return
    this.advancedCreationResultUnknown = false
    this.setData({ portfolioId: draft.portfolioId, draftRevision: draft.draftRevision, advancedCreationUncertain: false, advancedRecoveryDrafts: [] })
  },
  handleAdvancedConfirmNotCreated() {
    if (!Array.isArray(this.data.advancedRecoveryDrafts)) return
    wx.showModal({ title: '确认尚未创建', content: '已核对刷新后的列表，确认没有本次创建的草稿？确认后下次保存才会重新创建。',
      success: result => { if (result.confirm) { this.advancedCreationResultUnknown = false; this.setData({ advancedCreationUncertain: false, advancedRecoveryDrafts: [] }) } } })
  },
  handleAdvancedAi(event = {}) {
    if (!this.data.advancedEditing || this.data.saving || this.data.publishing || this.portfolioSubmissionPromise) return
    const dataset = event.currentTarget && event.currentTarget.dataset || {}
    const mode = dataset.mode || 'MODIFY_PAGE'
    if (!['MODIFY_PAGE', 'MANAGE_SOURCES'].includes(mode)) return
    const menuKey = this.data.activeMenuKey || '__root__'
    if (mode !== 'MANAGE_SOURCES') {
      this.advancedUndo = null
      this.setData({ localEditRevision: (this.data.localEditRevision || 0) + 1, advancedCanUndo: false })
    }
    const state = editorState(this)
    const team = state.ownerType === 'TEAM'
    wx.navigateTo({ url: `/pages/${team ? 'team-portfolios' : 'portfolios'}/advanced-ai/advanced-ai?mode=${mode}`,
      events: {
        contextChange: detail => {
          this.setData({ aiContext: normalizeContext(detail.aiContext), aiPrompt: detail.prompt, localEditRevision: detail.localEditRevision, aiReferenceAttachments: normalizeReferenceItems(detail.aiReferenceAttachments), aiReferenceNextNo: referenceNextNo(detail.aiReferenceAttachments, detail.aiReferenceNextNo) })
          refreshAdvancedSourceItems(this, detail.sourceItems)
        },
        adopt: detail => {
          const versionError = candidateVersionError(detail)
          if (versionError) { wx.showToast({ title: versionError, icon: 'none' }); return }
          if (captureBasis(editorState(this)) !== detail.basis) { wx.showToast({ title: '当前编辑已变化，请重新生成', icon: 'none' }); return }
          this.advancedUndo = { config: clone(this.data.config), aiContext: clone(this.data.aiContext), prompt: this.data.aiPrompt, aiReferenceAttachments: clone(this.data.aiReferenceAttachments || []), aiReferenceNextNo: this.data.aiReferenceNextNo,
            renderData: { backgroundAudio: this.data.backgroundAudioSelected || null } }
          this.advancedApplying = true
          if (team) this.updateConfig(detail.config)
          else this.applyEditorConfig(detail.config)
          this.applyAdvancedRenderMetadata(detail.renderData)
          refreshAdvancedSourceItems(this, detail.sourceItems)
          if (team && this.loadSingleWorkRowSummaries) this.loadSingleWorkRowSummaries(detail.config)
          if (!team && this.loadSingleWorkSummaries) this.loadSingleWorkSummaries(detail.config)
          this.setData({ advancedDirty: true, advancedCanUndo: true, aiReferenceAttachments: normalizeReferenceItems(detail.aiReferenceAttachments || this.data.aiReferenceAttachments), aiReferenceNextNo: referenceNextNo(detail.aiReferenceAttachments || this.data.aiReferenceAttachments, detail.aiReferenceNextNo || this.data.aiReferenceNextNo) })
          this.advancedApplying = false
        }
      }, success: result => result.eventChannel.emit('initialize', { ...state, mode, targetMenuKey: menuKey,
        prompt: this.data.aiPrompt || '', sourceItems: this.data.advancedSourceItems || [] }) })
  },
  handleAdvancedUndo() {
    if (!this.data.advancedCanUndo || !this.advancedUndo) return
    const undo = this.advancedUndo
    if (this.data.savedAdvancedVersion > 1) undo.config.advancedPortfolioVersion = this.data.savedAdvancedVersion
    this.advancedApplying = true
    if (this.data.advancedOwnerType === 'TEAM') this.updateConfig(undo.config)
    else this.applyEditorConfig(undo.config)
    this.applyAdvancedRenderMetadata(undo.renderData)
    this.setData({ aiContext: undo.aiContext, aiPrompt: undo.prompt, aiReferenceAttachments: normalizeReferenceItems(undo.aiReferenceAttachments), aiReferenceNextNo: Math.max(this.data.aiReferenceNextNo || 1, undo.aiReferenceNextNo || 1), advancedCanUndo: false, advancedDirty: true })
    this.advancedUndo = null
    this.advancedApplying = false
  },
  advancedAssetOptions(portfolioId) {
    return this.data.advancedEditing ? { ticketUrl: advancedEndpoint(portfolioId, '/asset/upload-ticket') } : {}
  }
}

module.exports = { advancedEditorPageMethods, installAdvancedEditor, loadAdvancedMetadata, markAdvancedSaved, editorRequest, editorState,
  SAVE_INCOMPLETE_MESSAGE, advancedQuery, advancedEndpoint, makeKey, confirmAdvancedPortfolioPublish }
