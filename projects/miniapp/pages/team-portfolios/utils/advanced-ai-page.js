const { referencePageMethods, referenceExampleText } = require('./advanced-reference-page')
const { openVideoPlayer } = require('./portfolio-video-player')
const { normalizeReferenceItems, referenceNextNo, hasReferenceState, referenceUiState } = require('./advanced-reference-attachments')
const { request: defaultRequest } = require('../../../utils/request.js')
const { hasLocalToken, handleMaintainerAuthRequired } = require('../../../utils/session.js')
const { CLIENT_ADVANCED_VERSION, AI_CONTRACT_VERSION, SOURCE_MODES, supportedProtocol, candidateVersionError, AI_API_PREFIX, ADVANCED_API_PREFIX, ACTIVE_STATUSES, AI_TASK_MODES, PROMPT_MAX_LENGTH, normalizeContext, addSource, removeSource,
  captureBasis, candidateError, taskErrorMessage, buildTaskPayload, statusMessage, makeKey, clone, text, validatePrompt } = require('./advanced-portfolios')
const PAGE_SIZE = 20
const CAPABILITY_CHECK_FAILED_MESSAGE = '暂时无法检查附件服务，请重试'
const SOURCE_REQUIRED_MESSAGE = '请先选择至少一项可用内容'
/** 教程固定使用公开竖屏视频，独立播放时不改动当前新建会话。 */
const TUTORIAL_VIDEO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/wefolio-ai-portfolio-v11.mp4'
const TUTORIAL_VIDEO_TITLE = '高级作品集使用教程'
/** 创建示例统一用于页面展示与一键填入。 */
/** 媒体枚举只用于显示，提交仍保留原始类型。 */
const MEDIA_TYPE_LABELS = Object.freeze({ IMAGE: '图片', VIDEO: '视频', ANIMATION: '动图', AUDIO: '音频' })

/** 仅展示接口明确返回的累计用量，缺失值与不安全整数不得伪装为零。 */
function normalizeTokenUsage(usage) {
  if (!usage || typeof usage !== 'object' || Array.isArray(usage) || typeof usage.complete !== 'boolean') return ''
  if (usage.complete === false && usage.totalTokens === null) return 'Token 用量未返回'
  if (!Number.isSafeInteger(usage.totalTokens) || usage.totalTokens < 0) return ''
  const amount = String(usage.totalTokens).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return usage.complete ? `本次消耗 ${amount} tokens` : `已记录 ${amount} tokens · 部分用量未返回`
}

function normalizeSource(source = {}, team = false) {
  return { sourceId: text(source.sourceId || source.id || source.workId || source.portfolioId),
    key: `${source.kind || 'WORK'}:${text(source.sourceId || source.id || source.workId || source.portfolioId)}`,
    kind: source.kind || 'WORK', title: source.title || '未命名内容', thumbnailUrl: source.thumbnailUrl || source.coverUrl || source.url || '',
    memberUserId: source.memberUserId || '', memberName: source.memberName || '', mediaType: source.mediaType || '',
    mediaTypeLabel: source.kind === 'MEMBER_PORTFOLIO' ? '作品集' : MEDIA_TYPE_LABELS[source.mediaType] || '作品',
    available: source.available !== false, unavailableReason: source.unavailableReason || '', team }
}

/** 两个分包保留本地实现，任务与候选仅存在当前内存会话。 */
function createAdvancedAiPage(team = false, { requestFn = defaultRequest } = {}) {
  const request = requestFn
  const packageRoot = team ? 'team-portfolios' : 'portfolios'
  const tutorialVideoRoute = `/pages/${packageRoot}/video-player/video-player`
  return {
    ...referencePageMethods(request),
    data: { team, ownerType: team ? 'TEAM' : 'USER', ownerId: '', portfolioId: null, draftRevision: 0, localEditRevision: 0,
      config: null, aiContext: normalizeContext(), aiReferenceAttachments: [], aiReferenceNextNo: 1, prompt: '', promptCount: 0, promptMaxLength: PROMPT_MAX_LENGTH,
      referenceSheet: '', referenceReplacingKey: '', referenceNoteOpen: {}, referenceUi: referenceUiState(), referenceExampleText: referenceExampleText(),
      referenceCapabilityChecking: false, capabilityError: '',
      mode: 'CREATE', sourceMode: SOURCE_MODES.SELECTED, v2Available: false, targetMenuKey: null, managingSources: false, step: 'sources', loading: true,
      errorMessage: '', sourceLoading: false, sourceError: '', sourceOptions: [], selectedSources: [], keyword: '', tagId: '', tags: [],
      sourceKind: team ? 'WORK' : '', members: [], memberIndex: 0, page: 1, total: 0, hasMore: false, capabilities: null,
      submitting: false, submissionUncertain: false, waiting: false, adopting: false, task: null, statusText: '', result: null, summaryText: '', candidateTitle: '',
      canAdopt: false, questions: [], clarificationAnswer: null, taskError: '', tokenUsageText: '', cancelFailed: false, activeTaskId: '' },

    onLoad(options = {}) {
      this.sourceNumbers = {}
      this.channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
      if (options.mode) {
        if (options.mode !== 'MANAGE_SOURCES' && !AI_TASK_MODES.includes(options.mode)) {
          this.setData({ loading: false, errorMessage: 'AI 操作参数无效' }); return
        }
        this.setData({ mode: options.mode === 'MANAGE_SOURCES' ? 'MODIFY_PAGE' : options.mode, managingSources: options.mode === 'MANAGE_SOURCES',
          step: options.mode === 'MANAGE_SOURCES' || options.mode === 'CREATE' ? 'sources' : 'prompt' })
        if (this.channel && this.channel.on) this.channel.on('initialize', state => {
          this.invalidateTaskQuery()
          this.sourceNumbers = {}
          this.setData({ ...state, aiContext: normalizeContext(state.aiContext), aiReferenceAttachments: normalizeReferenceItems(state.aiReferenceAttachments), aiReferenceNextNo: referenceNextNo(state.aiReferenceAttachments, state.aiReferenceNextNo), promptCount: Array.from(state.prompt || '').length,
            step: options.mode === 'MANAGE_SOURCES' || options.mode === 'CREATE' ? 'sources' : 'prompt',
            mode: options.mode === 'MANAGE_SOURCES' ? 'MODIFY_PAGE' : options.mode, sourceMode: SOURCE_MODES.SELECTED, managingSources: options.mode === 'MANAGE_SOURCES', tokenUsageText: '' })
          this.refreshReferenceUi()
          this.bootstrap()
        })
      } else {
        this.setData({ ownerId: options.teamId || options.ownerId || '' })
        this.bootstrap()
      }
    },
    onShow() { if (this.data.waiting && !this.pollTimer) this.schedulePoll() },
    onHide() { clearTimeout(this.pollTimer); this.pollTimer = null },
    /** 导航到本分包播放器，返回后继续使用原页面内存中的内容与描述。 */
    handleOpenTutorial() {
      if (this.data.mode !== 'CREATE' || this.data.managingSources) return Promise.resolve(false)
      return openVideoPlayer({ url: TUTORIAL_VIDEO_URL, title: TUTORIAL_VIDEO_TITLE,
        route: tutorialVideoRoute, portrait: true }, wx)
    },
    onUnload() {
      this.stopReferenceProcessing()
      this.disposed = true
      clearTimeout(this.pollTimer)
      this.pollSequence = (this.pollSequence || 0) + 1
      if (this.data.task && ACTIVE_STATUSES.includes(this.data.task.status)) request({ url: `${AI_API_PREFIX}/tasks/${this.data.task.taskId}/cancel`, method: 'POST' }).catch(() => {})
    },
    async bootstrap() {
      if (!hasLocalToken()) { handleMaintainerAuthRequired(); return }
      this.setData({ loading: true, errorMessage: '' })
      let capabilityError = ''
      try {
        const results = await Promise.all([request({ url: `${AI_API_PREFIX}/capabilities` }).catch(error => {
          if (error.authRequired) throw error
          capabilityError = CAPABILITY_CHECK_FAILED_MESSAGE
          return { enabled: false, unavailableReason: 'AI 服务暂不可用，可继续管理内容与手动编辑' }
        }),
          !team && !this.data.ownerId ? request({ url: '/api/auth/session' }) : Promise.resolve(null),
          !team ? request({ url: '/api/mine/works/tags' }) : Promise.resolve([])])
        const capabilities = results[0] || {}
        this.setData({ capabilities, capabilityError, v2Available: !!supportedProtocol(capabilities), ownerId: team ? this.data.ownerId : text(results[1] && results[1].userId || this.data.ownerId),
          tags: Array.isArray(results[2]) ? results[2] : results[2] && results[2].tags || [],
          activeTaskId: capabilities.activeTask && capabilities.activeTask.taskId || '' })
        this.refreshReferenceUi()
        await this.loadSources(false)
        await this.hydrateSelectedSources()
        this.refreshSelection()
      } catch (error) {
        if (error.authRequired) handleMaintainerAuthRequired(error.message)
        else this.setData({ errorMessage: error.message || '加载失败，请重试' })
      } finally { if (!this.disposed) this.setData({ loading: false }) }
    },
    /** 仅刷新服务能力，不重新加载用户已选内容或覆盖当前编辑与候选。 */
    async refreshCapabilities() {
      if (this.disposed || this.data.referenceCapabilityChecking) return false
      this.setData({ referenceCapabilityChecking: true })
      try {
        const capabilities = await request({ url: `${AI_API_PREFIX}/capabilities` }) || {}
        if (this.disposed) return false
        this.setData({ capabilities, capabilityError: '', v2Available: !!supportedProtocol(capabilities), activeTaskId: capabilities.activeTask && capabilities.activeTask.taskId || '' })
        this.refreshReferenceUi()
        return true
      } catch (error) {
        if (error.authRequired) handleMaintainerAuthRequired(error.message)
        else if (!this.disposed) {
          this.setData({ capabilityError: CAPABILITY_CHECK_FAILED_MESSAGE })
          wx.showToast({ title: CAPABILITY_CHECK_FAILED_MESSAGE, icon: 'none' })
        }
        return false
      } finally { if (!this.disposed) this.setData({ referenceCapabilityChecking: false }) }
    },
    currentState() {
      return { clientAdvancedVersion: CLIENT_ADVANCED_VERSION, aiContractVersion: AI_CONTRACT_VERSION, mode: this.data.mode, sourceMode: SOURCE_MODES.SELECTED, ownerType: this.data.ownerType, ownerId: this.data.ownerId, portfolioId: this.data.portfolioId,
        draftRevision: this.data.draftRevision, localEditRevision: this.data.localEditRevision, config: this.data.config, aiContext: this.data.aiContext, ...(hasReferenceState(this.data) ? { aiReferenceAttachments: this.data.aiReferenceAttachments, aiReferenceNextNo: this.data.aiReferenceNextNo } : {}) }
    },
    emitContext() {
      if (this.channel && this.channel.emit) this.channel.emit('contextChange', { aiContext: this.data.aiContext, prompt: this.data.prompt, localEditRevision: this.data.localEditRevision, sourceItems: this.data.selectedSources, aiReferenceAttachments: this.data.aiReferenceAttachments, aiReferenceNextNo: this.data.aiReferenceNextNo })
    },
    /** 仅在当前选择会话记住来源编号，搜索与取消不会让同一内容重新领号。 */
    rememberSourceNumbers() {
      this.sourceNumbers = this.sourceNumbers || {}
      this.data.aiContext.sources.forEach(source => { this.sourceNumbers[`${source.kind}:${source.sourceId}`] = source.sourceNo })
    },
    refreshSelection() {
      const facts = this.sourceFacts || {}
      const selectedSources = this.data.aiContext.sources.map(source => ({ ...source,
        ...(facts[`${source.kind}:${source.sourceId}`] || { title: `${source.kind === 'WORK' ? '作品' : '成员作品集'} ${source.sourceId}` }) }))
      this.setData({ selectedSources, sourceOptions: this.data.sourceOptions.map(item => ({ ...item,
        selected: this.data.aiContext.sources.some(source => source.kind === item.kind && source.sourceId === item.sourceId),
        sourceNo: (this.data.aiContext.sources.find(source => source.kind === item.kind && source.sourceId === item.sourceId) || {}).sourceNo || 0 })) })
    },
    /** 重开恢复编号后重新读取有权访问的摘要；失效项保留编号但不再用于新增绑定。 */
    async hydrateSelectedSources() {
      const missing = this.data.aiContext.sources.filter(source => !(this.sourceFacts || {})[`${source.kind}:${source.sourceId}`])
      if (!missing.length) return
      this.sourceFacts = { ...(this.sourceFacts || {}) }
      if (!team) {
        await Promise.all(missing.map(async source => {
          try {
            const response = await request({ url: `/api/mine/works/${encodeURIComponent(source.sourceId)}` })
            const work = response.work || response
            this.sourceFacts[`${source.kind}:${source.sourceId}`] = { ...normalizeSource({ ...work, sourceId: source.sourceId }),
              available: work.auditStatus === 'PASSED' && work.status !== 'DISABLED', unavailableReason: '作品已不可用于新绑定' }
          } catch (error) {
            this.sourceFacts[`${source.kind}:${source.sourceId}`] = { available: false, title: `${source.sourceNo}号作品`, unavailableReason: '作品已移除或不可用' }
          }
        }))
      } else {
        try {
          let page = 1
          let more = true
          while (more && missing.some(source => !this.sourceFacts[`${source.kind}:${source.sourceId}`])) {
            const response = await request({ url: `${ADVANCED_API_PREFIX}/sources`, data: { ownerType: 'TEAM', ownerId: this.data.ownerId, page, pageSize: 100 } })
            const items = response.records || response.items || []
            items.forEach(source => { this.sourceFacts[`${source.kind}:${source.sourceId}`] = normalizeSource(source, true) })
            more = items.length > 0 && (response.hasMore === true || page * 100 < Number(response.total))
            page += 1
            if (this.disposed) return
          }
          missing.forEach(source => {
            if (!this.sourceFacts[`${source.kind}:${source.sourceId}`]) this.sourceFacts[`${source.kind}:${source.sourceId}`] = {
              available: false, title: `${source.sourceNo}号内容`, unavailableReason: '来源已移除、下线或授权失效' }
          })
        } catch (error) {
          this.setData({ sourceError: '部分已选来源未能核对，生成与采用时仍须重新校验权限' })
        }
      }
    },
    async loadSources(more = false) {
      const sequence = this.sourceSequence = (this.sourceSequence || 0) + 1
      const page = more ? this.data.page + 1 : 1
      this.setData({ sourceLoading: true, sourceError: '' })
      try {
        const member = this.data.members[this.data.memberIndex - 1]
        const response = await request({ url: team ? `${ADVANCED_API_PREFIX}/sources` : '/api/mine/works',
          data: team ? { ownerType: 'TEAM', ownerId: this.data.ownerId, sourceKind: this.data.sourceKind || undefined,
            memberUserId: member && member.memberUserId || undefined, keyword: this.data.keyword, page, pageSize: PAGE_SIZE }
            : { auditStatus: 'PASSED', keyword: this.data.keyword, tagId: this.data.tagId || undefined, page, pageSize: PAGE_SIZE } })
        if (this.disposed || sequence !== this.sourceSequence) return
        const records = Array.isArray(response) ? response : response.records || response.items || response.works || []
        const items = records.map(item => normalizeSource(item, team)).filter(item => item.sourceId)
        this.sourceFacts = { ...(this.sourceFacts || {}) }
        items.forEach(item => { this.sourceFacts[`${item.kind}:${item.sourceId}`] = item })
        this.setData({ sourceOptions: more ? this.data.sourceOptions.concat(items) : items, page, total: Number(response.total) || items.length,
          hasMore: response.hasMore === true || page * PAGE_SIZE < Number(response.total), members: (response.members || this.data.members).map(member => ({ ...member, memberName: member.memberName || member.displayName })), memberNames: ['全部成员'].concat((response.members || this.data.members).map(member => member.memberName || member.displayName)) })
        this.refreshSelection()
      } catch (error) {
        if (error.authRequired) handleMaintainerAuthRequired(error.message)
        else if (sequence === this.sourceSequence) this.setData({ sourceError: error.message || '内容加载失败，请重试' })
      } finally { if (!this.disposed && sequence === this.sourceSequence) this.setData({ sourceLoading: false }) }
    },
    handleKeywordInput(event) { this.setData({ keyword: event.detail.value }) },
    handleSearch() { return this.loadSources(false) },
    handleTagTap(event) { this.setData({ tagId: event.currentTarget.dataset.id || '' }); return this.loadSources(false) },
    handleKindTap(event) { this.setData({ sourceKind: event.currentTarget.dataset.kind || '' }); return this.loadSources(false) },
    handleMemberChange(event) { this.setData({ memberIndex: Number(event.detail.value) || 0 }); return this.loadSources(false) },
    handleMemberTap(event) { return this.handleMemberChange({ detail: { value: event.currentTarget.dataset.index } }) },
    handleLoadMore() { if (this.data.step === 'sources' && !this.data.sourceLoading && this.data.hasMore) return this.loadSources(true) },
    handleSourceTap(event) {
      if (this.data.waiting || this.data.adopting || this.data.submissionUncertain) return
      const source = this.data.sourceOptions[Number(event.currentTarget.dataset.index)]
      if (!source || source.available === false) return
      const selected = this.data.aiContext.sources.find(item => item.kind === source.kind && item.sourceId === source.sourceId)
      const limits = (supportedProtocol(this.data.capabilities || {}) || {}).limits || (this.data.capabilities || {}).limits || {}
      const max = limits.maxSourceCount
      if (!selected && max && this.data.aiContext.sources.length >= max) { wx.showToast({ title: `最多选择 ${max} 个来源`, icon: 'none' }); return }
      this.invalidateTaskQuery()
      this.rememberSourceNumbers()
      this.setData({ aiContext: selected ? removeSource(this.data.aiContext, selected.sourceNo)
        : addSource(this.data.aiContext, source, this.sourceNumbers[`${source.kind}:${source.sourceId}`]),
        localEditRevision: this.data.localEditRevision + 1, result: null, canAdopt: false, tokenUsageText: '' })
      this.refreshSelection()
      this.emitContext()
    },
    handleRemoveSelected(event) {
      if (this.data.waiting || this.data.adopting || this.data.submissionUncertain) return
      this.invalidateTaskQuery()
      this.rememberSourceNumbers()
      this.setData({ aiContext: removeSource(this.data.aiContext, event.currentTarget.dataset.no), localEditRevision: this.data.localEditRevision + 1, result: null, canAdopt: false, tokenUsageText: '' })
      this.refreshSelection()
      this.emitContext()
      wx.showToast({ title: '移除后仍保留页面已有展示', icon: 'none' })
    },
    handleNext() {
      if (this.data.mode === 'CREATE' && !this.data.selectedSources.some(source => source.available !== false)) {
        wx.showToast({ title: SOURCE_REQUIRED_MESSAGE, icon: 'none' }); return
      }
      if (this.data.managingSources) { this.emitContext(); wx.navigateBack(); return }; this.setData({ step: 'prompt' }) },
    handleBackToSources() { this.setData({ step: 'sources' }) },
    handlePromptInput(event) {
      if (this.data.waiting || this.data.submitting || this.data.adopting || this.data.submissionUncertain) return
      this.invalidateTaskQuery()
      this.setData({ prompt: event.detail.value, promptCount: Array.from(event.detail.value).length, tokenUsageText: '', result: null, canAdopt: false, taskError: '' })
      this.emitContext()
    },
    handleInsertNumber(event) {
      if (this.data.waiting || this.data.adopting || this.data.submissionUncertain) return
      const value = `${this.data.prompt}${event.currentTarget.dataset.no}号`
      if (Array.from(value).length > PROMPT_MAX_LENGTH) return
      this.invalidateTaskQuery()
      this.setData({ prompt: value, promptCount: Array.from(value).length, tokenUsageText: '' })
      this.emitContext()
    },
    handleSelectedSourceTap(event) { if (this.data.step === 'prompt') this.handleInsertNumber(event) },
    handleQuestionInput(event) { this.setData({ clarificationAnswer: { questionId: event.currentTarget.dataset.id, answerText: event.detail.value } }) },
    handleQuestionOption(event) { this.setData({ clarificationAnswer: { questionId: event.currentTarget.dataset.id,
      optionId: event.currentTarget.dataset.option, answerText: event.currentTarget.dataset.value } }) },
    async handleGenerate() {
      if (this.data.submitting || this.data.waiting || this.data.adopting) return
      if (!this.pendingSubmission && (!this.data.capabilities || !this.data.capabilities.enabled || !supportedProtocol(this.data.capabilities))) { wx.showToast({ title: '当前服务暂不支持此版本生成，草稿已保留', icon: 'none' }); return }
      if (!this.pendingSubmission && this.data.mode === 'CREATE' && !this.data.selectedSources.some(source => source.available !== false)) { wx.showToast({ title: SOURCE_REQUIRED_MESSAGE, icon: 'none' }); return }
      if (this.data.activeTaskId && !this.data.task) { wx.showToast({ title: '已有生成任务，请先取消或等待结束', icon: 'none' }); return }
      const referenceError = this.pendingSubmission ? '' : this.referenceGenerationError()
      if (referenceError) { wx.showToast({ title: referenceError, icon: 'none' }); return }
      const message = validatePrompt(this.data.prompt, this.data.aiContext, this.data.selectedSources.filter(source => source.available === false).map(source => source.sourceNo))
      if (message) { wx.showToast({ title: message, icon: 'none' }); return }
      if (this.data.questions.length && (!this.data.clarificationAnswer || !text(this.data.clarificationAnswer.answerText))) {
        wx.showToast({ title: '请回答澄清问题后继续', icon: 'none' }); return
      }
      // 网络提交不确定时复用原请求与幂等键，不能把新描述配进旧键。
      if (!this.pendingSubmission) {
        const current = this.currentState()
        this.taskBasis = captureBasis(current)
        this.pendingSubmission = buildTaskPayload(current, { ...this.data,
          clarificationAnswer: this.data.clarificationAnswer || null }, makeKey())
      }
      // 新提交使旧任务的在途查询失效，避免延迟结果与用量覆盖新的生成会话。
      this.invalidateTaskQuery()
      this.setData({ submitting: true, taskError: '', result: null, canAdopt: false, tokenUsageText: '', cancelFailed: false })
      try {
        const task = await request({ url: `${AI_API_PREFIX}/tasks`, method: 'POST', data: clone(this.pendingSubmission) })
        if (this.disposed) { request({ url: `${AI_API_PREFIX}/tasks/${task.taskId}/cancel`, method: 'POST' }).catch(() => {}); return }
        this.pendingSubmission = null
        this.setData({ task, submissionUncertain: false, waiting: ACTIVE_STATUSES.includes(task.status) || task.status === 'SUCCEEDED', activeTaskId: '', questions: [], statusText: statusMessage(task) })
        if (ACTIVE_STATUSES.includes(task.status)) this.schedulePoll(task.pollAfterMs)
        // 提交幂等重放只返回安全状态；已成功时仍须查询当前可用候选。
        else if (task.status === 'SUCCEEDED') await this.queryTask()
        else this.applyTask(task)
      } catch (error) {
        if (error.authRequired) handleMaintainerAuthRequired(error.message)
        const code = Number(error.statusCode)
        const uncertain = !(code >= 400 && code < 500 && code !== 408)
        if (!uncertain) this.pendingSubmission = null
        this.setData({ submissionUncertain: uncertain, taskError: uncertain ? '提交结果未知，重试提交将找回同一个任务' : error.message || '提交失败，请调整后重试' })
      } finally { if (!this.disposed) this.setData({ submitting: false }) }
    },
    /** 清除计划轮询并使在途查询失效，手动重试与编辑不能恢复旧任务结果。 */
    invalidateTaskQuery() {
      clearTimeout(this.pollTimer); this.pollTimer = null
      this.pollSequence = (this.pollSequence || 0) + 1
    },
    schedulePoll(delay = 2000) {
      clearTimeout(this.pollTimer)
      this.pollTimer = setTimeout(() => { this.pollTimer = null; this.queryTask() }, Math.max(2000, Math.min(5000, Number(delay) || 2000)))
    },
    async queryTask() {
      if (!this.data.task || this.disposed) return
      this.invalidateTaskQuery()
      const sequence = this.pollSequence
      try {
        const task = await request({ url: `${AI_API_PREFIX}/tasks/${this.data.task.taskId}`, data: { clientAdvancedVersion: CLIENT_ADVANCED_VERSION } })
        if (this.disposed || sequence !== this.pollSequence) return
        this.applyTask(task)
        if (this.data.waiting) this.schedulePoll(task.pollAfterMs || Math.min(5000, 2000 + (task.attemptCount || 0) * 1000))
      } catch (error) {
        if (error.authRequired) handleMaintainerAuthRequired(error.message)
        else if (!this.disposed && sequence === this.pollSequence) this.setData({ taskError: error.message || '查询失败，请重试查询' })
      }
    },
    applyTask(task) {
      this.refreshReferenceTokens(task)
      const referenceError = this.referenceReadyError()
      const versionError = candidateVersionError(task)
      const result = versionError ? { kind: 'CANDIDATE', available: false, unavailableReason: versionError } : task.result || null
      const summary = result && result.summary
      const summaryText = typeof summary === 'string' ? summary : Array.isArray(summary) ? summary.map(item => typeof item === 'string' ? item : item.description || item.message || '').join('\n') : summary && (summary.description || summary.message || JSON.stringify(summary)) || ''
      this.setData({ task, waiting: ACTIVE_STATUSES.includes(task.status), statusText: statusMessage(task), result,
        candidateTitle: result && result.available === false ? '生成失败' : result && result.hasChanges === false ? '当前内容已满足要求' : this.data.mode === 'CREATE' ? '作品集已生成' : '修改已生成',
        summaryText: result && result.available === false ? '' : result && result.hasChanges === false ? '当前内容已满足要求' : summaryText,
        questions: result && result.questions || [], canAdopt: task.status === 'SUCCEEDED' && !referenceError && !!result && result.kind === 'CANDIDATE' && result.available === true && result.hasChanges === true,
        taskError: versionError || taskErrorMessage(task) || (result && result.kind === 'CANDIDATE' ? referenceError : ''), tokenUsageText: task.status === 'SUCCEEDED' || task.status === 'FAILED' ? normalizeTokenUsage(task.usage) : '' })
    },
    async handleCancel() {
      const id = this.data.task && this.data.task.taskId || this.data.activeTaskId
      if (!id) return
      this.invalidateTaskQuery()
      this.setData({ waiting: false, canAdopt: false, result: null, tokenUsageText: '', cancelFailed: false, statusText: '已取消，现有内容已保留' })
      try {
        await request({ url: `${AI_API_PREFIX}/tasks/${id}/cancel`, method: 'POST' })
        this.setData({ task: { taskId: id, status: 'CANCELLED' }, activeTaskId: '' })
      } catch (error) { this.setData({ cancelFailed: true, activeTaskId: id, taskError: '取消未确认，请重试取消；当前内容已保留' }) }
    },
    handleDiscard() {
      this.invalidateTaskQuery()
      this.setData({ result: null, canAdopt: false, questions: [], taskError: '', summaryText: '', tokenUsageText: '' })
    },
    handleCandidatePreview() {
      const versionError = candidateVersionError(this.data.task || {})
      if (versionError) { wx.showToast({ title: versionError, icon: 'none' }); return }
      const renderData = this.data.result && this.data.result.renderData
      if (!renderData) { wx.showToast({ title: '候选完整效果暂不可用，请重新查询', icon: 'none' }); return }
      const preview = team ? 'team-portfolio-standard-preview' : 'portfolio-standard-preview'
      wx.navigateTo({ url: `/pages/${packageRoot}/standard-preview/${preview}?candidate=1&templateType=ADVANCED`,
        success: response => response.eventChannel.emit('candidatePreview', { renderData: { ...renderData, preview: true }, title: '候选效果 · 尚未采用' }) })
    },
    async handleAdopt() {
      if (!this.data.canAdopt || this.data.adopting) return
      this.setData({ adopting: true })
      try {
        const response = await request({ url: `${AI_API_PREFIX}/tasks/${this.data.task.taskId}`, data: { clientAdvancedVersion: CLIENT_ADVANCED_VERSION } })
        if (this.disposed) return
        this.refreshReferenceTokens(response)
        const referenceError = this.referenceReadyError()
        if (referenceError) { this.setData({ taskError: referenceError, canAdopt: false }); return }
        const message = candidateError(response, this.taskBasis, this.currentState())
        if (message) { this.applyTask(response); this.setData({ taskError: message, canAdopt: false }); return }
        const state = this.currentState()
        if (this.data.mode === 'CREATE') {
          const editor = team ? 'team-portfolio-standard-edit' : 'portfolio-standard-edit'
          wx.navigateTo({ url: `/pages/${packageRoot}/standard-edit/${editor}?templateType=ADVANCED&${team ? 'teamId' : 'ownerId'}=${state.ownerId}`,
            success: result => {
              result.eventChannel.emit('advancedInitial', { ...state, sourceMode: SOURCE_MODES.SELECTED, taskAdvancedVersion: response.taskAdvancedVersion, config: response.result.candidate,
                renderData: response.result.renderData, prompt: this.data.prompt, sourceItems: this.data.selectedSources, localEditRevision: state.localEditRevision + 1 })
              this.setData({ result: null, canAdopt: false, tokenUsageText: '' })
            } })
        } else {
          this.emitContext()
          if (this.channel && this.channel.emit) this.channel.emit('adopt', { taskAdvancedVersion: response.taskAdvancedVersion, config: response.result.candidate, renderData: response.result.renderData, basis: this.taskBasis, aiReferenceAttachments: this.data.aiReferenceAttachments, aiReferenceNextNo: this.data.aiReferenceNextNo })
          wx.navigateBack()
        }
      } catch (error) { this.setData({ taskError: error.message || '采用前校验失败，当前内容已保留' }) }
      finally { if (!this.disposed) this.setData({ adopting: false }) }
    }
  }
}

module.exports = { createAdvancedAiPage, normalizeSource }
