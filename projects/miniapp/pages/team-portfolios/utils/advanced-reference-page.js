const { REFERENCE_API, referenceLimits, referenceUiState, normalizeReferenceItems, referenceNextNo, referenceBusiness,
  validateReferenceInput, referencePayload, createReferenceUploader, NOTE_MAX } = require('./advanced-reference-attachments')
const { handleMaintainerAuthRequired } = require('../../../utils/session.js')
const REFERENCE_SHEETS = Object.freeze({ CLOSED: '', PICKER: 'picker', EXAMPLE: 'example' })
const REFERENCE_PROMPT_EXAMPLE = '页面内容使用我选择的作品，整体温暖、留白充足，最后放档期查询。'
const REFERENCE_UNAVAILABLE_MESSAGE = '当前服务暂未开放参考附件，请稍后重试'

/** 示例仅使用当前可用的参考编号，不假定已选作品一定存在某个编号。 */
function referenceExampleText(items = []) {
  const item = items.find(value => value.status === 'READY' && value.available !== false)
  return REFERENCE_PROMPT_EXAMPLE + (item ? `参考${item.referenceNo}的配色与留白。` : '')
}

/** 参考附件只存在高级页和编辑器的内存维护清单，模型提交另行冻结。 */
function referencePageMethods(request) {
  return {
    referenceLocked() { return this.disposed || this.data.waiting || this.data.submitting || this.data.adopting || this.data.submissionUncertain },
    refreshReferenceUi() {
      this.setData({ referenceUi: referenceUiState(this.data.aiReferenceAttachments, this.data.capabilities, this.data.referenceReplacingKey),
        referenceExampleText: referenceExampleText(this.data.aiReferenceAttachments) })
    },
    setReferenceItems(items, patch = {}) {
      this.setData({ ...patch, aiReferenceAttachments: normalizeReferenceItems(items), referenceExampleText: referenceExampleText(items),
        referenceUi: referenceUiState(items, this.data.capabilities, this.data.referenceReplacingKey) })
    },
    async handleOpenReferencePicker() {
      if (this.referenceLocked() || this.referenceChoosing || this.data.referenceCapabilityChecking) return
      if (!referenceLimits(this.data.capabilities).enabled && !await this.refreshCapabilities()) return
      if (this.referenceLocked()) return
      this.setData({ referenceReplacingKey: '' })
      this.refreshReferenceUi()
      if (!referenceLimits(this.data.capabilities).enabled) { wx.showToast({ title: REFERENCE_UNAVAILABLE_MESSAGE, icon: 'none' }); return }
      if (!this.data.referenceUi.canAdd) { wx.showToast({ title: `最多添加${this.data.referenceUi.maxCount}个参考附件`, icon: 'none' }); return }
      this.setData({ referenceSheet: REFERENCE_SHEETS.PICKER })
    },
    handleCloseReferenceSheet() {
      this.setData({ referenceSheet: REFERENCE_SHEETS.CLOSED, referenceReplacingKey: '' })
      this.refreshReferenceUi()
    },
    async handleChooseReference(event) {
      if (this.referenceLocked() || this.referenceChoosing) return
      const mediaType = event.currentTarget.dataset.mediaType
      if (!['image', 'video'].includes(mediaType)) return
      const replacingKey = this.data.referenceReplacingKey
      this.handleCloseReferenceSheet()
      await this.handleAddReference({ mediaType, replacingKey })
    },
    handleOpenReferenceExample() {
      this.setData({ referenceSheet: REFERENCE_SHEETS.EXAMPLE, referenceReplacingKey: '' })
      this.refreshReferenceUi()
    },
    handleToggleReferenceNote(event) {
      const key = event.currentTarget.dataset.key
      if (!(this.data.aiReferenceAttachments || []).some(item => item.localKey === key)) return
      const open = this.data.referenceNoteOpen || {}
      this.setData({ referenceNoteOpen: { ...open, [key]: !open[key] } })
    },
    handleQuoteReference(event) {
      if (this.referenceLocked()) return
      const item = (this.data.aiReferenceAttachments || []).find(value => value.localKey === event.currentTarget.dataset.key)
      if (!item || item.status !== 'READY' || item.available === false) return
      const value = `${this.data.prompt}${this.data.prompt ? '\n' : ''}参考${item.referenceNo}的`
      if (Array.from(value).length > this.data.promptMaxLength) return
      this.handlePromptInput({ detail: { value } })
    },
    handleUseReferenceExample() {
      if (this.referenceLocked()) return
      const value = referenceExampleText(this.data.aiReferenceAttachments)
      this.handlePromptInput({ detail: { value } })
      this.handleCloseReferenceSheet()
    },
    handleReplaceReference(event) {
      if (this.referenceLocked() || this.referenceChoosing) return
      const item = (this.data.aiReferenceAttachments || []).find(value => value.localKey === event.currentTarget.dataset.key)
      if (!item || item.status !== 'FAILED' && item.available !== false || !referenceLimits(this.data.capabilities).enabled) return
      this.setData({ referenceReplacingKey: item.localKey, referenceSheet: REFERENCE_SHEETS.PICKER })
      this.refreshReferenceUi()
    },
    handleRemovePendingReferences() {
      if (this.referenceLocked()) return
      const items = this.data.aiReferenceAttachments || []
      const pending = items.filter(item => item.status !== 'READY' || item.available === false)
      if (!pending.length) return
      if (this.referenceUpload) pending.forEach(item => this.referenceUpload.cancel(item.localKey))
      this.referenceChanged(items.filter(item => item.status === 'READY' && item.available !== false))
    },
    referenceOwner() {
      return { ownerType: this.data.ownerType, ownerId: String(this.data.ownerId), ...(this.data.portfolioId ? { portfolioId: String(this.data.portfolioId) } : {}) }
    },
    referenceChanged(items, next = this.data.aiReferenceNextNo) {
      this.invalidateTaskQuery()
      this.pendingSubmission = null
      this.setReferenceItems(items, { aiReferenceNextNo: referenceNextNo(items, next),
        localEditRevision: (this.data.localEditRevision || 0) + 1, questions: [], clarificationAnswer: null,
        result: null, canAdopt: false, tokenUsageText: '', taskError: '' })
      this.emitContext()
    },
    referenceUploader() {
      if (!this.referenceUpload) this.referenceUpload = createReferenceUploader({ request, wxApi: wx, owner: this.referenceOwner(),
        limits: referenceLimits(this.data.capabilities), onError: error => { if (error.authRequired) handleMaintainerAuthRequired(error.message) },
        onChange: (key, patch) => {
          if (this.disposed) return
          const previous = this.data.aiReferenceAttachments || []
          const items = previous.map(item => item.localKey === key ? { ...item, ...patch, localKey: key, referenceNo: item.referenceNo, note: item.note } : item)
          if (!previous.some(item => item.localKey === key)) return
          // 新文件确认后身份进入业务基准；进度与回执刷新不制造无意义的修订。
          if (JSON.stringify(referenceBusiness(items)) !== JSON.stringify(referenceBusiness(previous))) this.referenceChanged(items)
          else { this.setReferenceItems(items); if (patch.status === 'READY' || patch.status === 'FAILED') this.emitContext() }
        } })
      return this.referenceUpload
    },
    async handleAddReference(options = {}) {
      if (this.referenceLocked() || this.referenceChoosing) return
      const limits = referenceLimits(this.data.capabilities)
      if (!limits.enabled) { wx.showToast({ title: '参考附件暂未开放', icon: 'none' }); return }
      const attachments = this.data.aiReferenceAttachments || []
      const replacing = options.replacingKey && attachments.find(item => item.localKey === options.replacingKey)
      if (options.replacingKey && !replacing) return
      const current = attachments.filter(item => !replacing || item.localKey !== replacing.localKey)
      const remaining = replacing ? Math.min(1, limits.maxCount - current.length) : limits.maxCount - current.length
      if (remaining <= 0) { wx.showToast({ title: `最多添加${limits.maxCount}个参考附件`, icon: 'none' }); return }
      const videoRemaining = limits.maxVideos - current.filter(item => item.mediaType === 'VIDEO').length
      if (options.mediaType === 'video' && videoRemaining <= 0) {
        wx.showToast({ title: `参考视频最多${limits.maxVideos}个`, icon: 'none' }); return
      }
      if (typeof wx.chooseMedia !== 'function') { wx.showToast({ title: '请更新微信后选择参考附件', icon: 'none' }); return }
      this.referenceChoosing = true
      try {
        const media = await new Promise((resolve, reject) => wx.chooseMedia({ count: options.mediaType === 'video' ? Math.min(remaining, videoRemaining) : remaining, sourceType: ['album'],
          mediaType: options.mediaType ? [options.mediaType] : limits.maxVideos === 0 || current.some(item => item.mediaType === 'VIDEO') ? ['image'] : ['image', 'video'], sizeType: ['original'], success: resolve, fail: reject }))
        if (this.referenceLocked()) return
        const files = media.tempFiles || []
        if (!files.length) return
        const now = this.data.aiReferenceAttachments || []
        const previous = replacing && now.find(item => item.localKey === replacing.localKey)
        if (replacing && !previous) return
        const existing = now.filter(item => !previous || item.localKey !== previous.localKey)
        if (previous && files.length !== 1 || existing.length + files.length > limits.maxCount || existing.filter(item => item.mediaType === 'VIDEO').length
          + files.filter(file => file.fileType === 'video').length > limits.maxVideos) throw new Error(`最多${limits.maxCount}个附件，其中视频最多${limits.maxVideos}个`)
        let next = referenceNextNo(now, this.data.aiReferenceNextNo)
        const additions = files.map(file => {
          const referenceNo = previous ? previous.referenceNo : `A${next++}`
          return { localKey: previous ? previous.localKey : `local-${referenceNo}`, referenceNo, attachmentId: '', note: previous ? previous.note : '', status: 'QUEUED',
            localPath: file.tempFilePath, thumbnailPath: file.thumbTempFilePath || (file.fileType === 'video' ? '' : file.tempFilePath),
            originalFileName: file.name || String(file.tempFilePath || '').split('/').pop(), mediaType: file.fileType === 'video' ? 'VIDEO' : 'IMAGE',
            sizeBytes: Number(file.size) || 0, durationMs: Math.ceil((Number(file.duration) || 0) * 1000) }
        })
        // 只有成功选定新文件才取消旧处理，取消相册不改变旧附件与编号高水位。
        if (previous && this.referenceUpload) this.referenceUpload.cancel(previous.localKey)
        this.referenceChanged(previous ? now.map(item => item.localKey === previous.localKey ? additions[0] : item) : now.concat(additions), next)
        additions.forEach((item, index) => this.referenceUploader().add(item.localKey, files[index]))
      } catch (error) {
        if (!this.disposed && !/cancel/i.test(error.errMsg || '')) wx.showToast({ title: error.message || '参考附件选择失败', icon: 'none' })
      } finally { this.referenceChoosing = false }
    },
    handleRemoveReference(event) {
      if (this.referenceLocked()) return
      const key = event.currentTarget.dataset.key
      if (this.referenceUpload) this.referenceUpload.cancel(key)
      this.referenceChanged((this.data.aiReferenceAttachments || []).filter(item => item.localKey !== key))
    },
    handleReferenceNote(event) {
      if (this.referenceLocked()) return
      const note = Array.from(String(event.detail.value || '')).slice(0, NOTE_MAX).join('')
      const key = event.currentTarget.dataset.key
      this.referenceChanged((this.data.aiReferenceAttachments || []).map(item => item.localKey === key ? { ...item, note } : item))
    },
    handleMoveReference(event) {
      if (this.referenceLocked()) return
      const items = (this.data.aiReferenceAttachments || []).slice()
      const index = items.findIndex(item => item.localKey === event.currentTarget.dataset.key)
      if (index <= 0) return
      ;[items[index - 1], items[index]] = [items[index], items[index - 1]]
      this.referenceChanged(items)
    },
    handleRetryReference(event) {
      if (this.referenceLocked()) return
      const key = event.currentTarget.dataset.key
      if (!this.referenceUpload || !this.referenceUpload.retry(key)) wx.showToast({ title: '本地文件已失效，请移除后重新选择', icon: 'none' })
    },
    async handlePreviewReference(event) {
      const item = (this.data.aiReferenceAttachments || []).find(value => value.localKey === event.currentTarget.dataset.key)
      if (!item || item.status !== 'READY' || this.referencePreviewing) return
      this.referencePreviewing = true
      try {
        const response = await request({ url: `${REFERENCE_API}/preview-ticket`, method: 'POST', data: {
          ownerType: this.data.ownerType, ownerId: String(this.data.ownerId), attachmentId: item.attachmentId, attachmentToken: item.attachmentToken } })
        if (this.disposed || !(this.data.aiReferenceAttachments || []).some(value => value.localKey === item.localKey)) return
        if (!response || typeof response.url !== 'string' || !response.url.startsWith('https://')) throw new Error('附件预览暂不可用')
        if (item.mediaType === 'VIDEO') {
          if (!wx.previewMedia) throw new Error('请更新微信后预览视频')
          wx.previewMedia({ sources: [{ url: response.url, type: 'video' }] })
        } else wx.previewImage({ current: response.url, urls: [response.url] })
      } catch (error) {
        if (error.authRequired) handleMaintainerAuthRequired(error.message)
        else if (!this.disposed) wx.showToast({ title: error.message || '附件预览失败', icon: 'none' })
      } finally { this.referencePreviewing = false }
    },
    stopReferenceProcessing() {
      if (this.referenceUpload) this.referenceUpload.dispose()
      const items = this.data.aiReferenceAttachments || []
      if (!items.some(item => !['READY', 'FAILED'].includes(item.status))) return
      this.setReferenceItems(items.map(item => ['READY', 'FAILED'].includes(item.status) ? item : {
        ...item, status: 'FAILED', errorMessage: '附件处理已中断，请移除后重新选择' }))
      this.emitContext()
    },
    referenceReadyError() {
      const unavailable = (this.data.aiReferenceAttachments || []).find(item => item.available === false)
      if (unavailable) return unavailable.errorMessage || '参考文件已不可用，请移除或重新选择'
      try { referencePayload(this.data.aiReferenceAttachments || []); return '' } catch (error) { return error.message }
    },
    referenceGenerationError() { return validateReferenceInput(this.data.aiReferenceAttachments || [], this.data.capabilities) },
    refreshReferenceTokens(task) {
      if (!Array.isArray(task.referenceAttachments)) return
      const refreshed = new Map(task.referenceAttachments.map(item => [item.attachmentId, item]))
      const items = (this.data.aiReferenceAttachments || []).map(item => {
        const value = refreshed.get(item.attachmentId)
        if (!value) return item
        const deadline = task.referenceSaveUntil || item.referenceSaveUntil || ''
        // 可用性查询不改变已完成上传的状态和上次回执；原候选仍可通过再次查询恢复。
        if (value.available === false) return { ...item, available: false, referenceSaveUntil: deadline,
          errorMessage: value.unavailableReason || '参考文件暂不可用，请重试查询' }
        return value.attachmentToken ? { ...item, available: true, status: 'READY', errorMessage: '',
          attachmentToken: value.attachmentToken, referenceSaveUntil: deadline, expiresAt: value.expiresAt || item.expiresAt } : item
      })
      this.setReferenceItems(items)
      this.emitContext()
    }
  }
}
module.exports = { referencePageMethods, referenceExampleText }
