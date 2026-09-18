const { hasLocalToken, handleMaintainerAuthRequired } = require('../../../utils/session')
const { createPortfolioMiniappCodeController } = require('../utils/portfolio-miniapp-code')
const { createPortfolioMiniappCodeRenderer } = require('../utils/portfolio-miniapp-code-renderer')

Page({
  data: {
    status: 'idle',
    saving: false,
    actionsDisabled: true,
    useAvatar: false,
    canToggleAvatar: false,
    codeUrl: '',
    tempFilePath: '',
    displayFilePath: '',
    errorMessage: ''
  },

  onLoad(options = {}) {
    if (this.codeController) this.codeController.dispose()
    if (this.codeRenderer) this.codeRenderer.dispose()
    if (!hasLocalToken()) {
      handleMaintainerAuthRequired('请先登录')
      return
    }
    this.codeRenderer = createPortfolioMiniappCodeRenderer({ page: this, wxApi: wx })
    this.codeController = createPortfolioMiniappCodeController({
      renderFn: input => this.codeRenderer.render(input),
      wxApi: wx,
      onChange: state => {
        // 图片替换时保持原生按钮状态不变，能否保存或预览由控制器检查当前成图。
        const imageFailed = ['error', 'download-error', 'render-error'].includes(state.status)
        const next = Object.assign({}, state, { actionsDisabled: !state.displayFilePath || state.saving || imageFailed })
        const patch = {}
        for (const key of Object.keys(next)) {
          if (next[key] !== this.data[key]) patch[key] = next[key]
        }
        // 只发送变化字段，避免每个绘制阶段重复更新按钮的 loading、禁用态和文案。
        if (Object.keys(patch).length) this.setData(patch)
      },
      onAuthRequired: handleMaintainerAuthRequired
    })
    return this.codeController.start(options)
  },

  onReady() {
    if (this.codeRenderer) this.codeRenderer.ready()
  },

  handleRetry() {
    return this.codeController && this.codeController.retry()
  },

  handlePreview() {
    return this.codeController && this.codeController.preview()
  },

  handleUseAvatarChange(event) {
    return this.codeController && this.codeController.setUseAvatar(event.detail.value === true)
  },

  handleSave() {
    return this.codeController && this.codeController.save()
  },

  onUnload() {
    if (this.codeController) this.codeController.dispose()
    if (this.codeRenderer) this.codeRenderer.dispose()
    this.codeController = null
    this.codeRenderer = null
  }
})
