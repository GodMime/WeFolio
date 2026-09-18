const { hasLocalToken, handleMaintainerAuthRequired } = require('../../../utils/session')
const { createPortfolioMiniappCodeController } = require('../utils/portfolio-miniapp-code')
const { createPortfolioMiniappCodeRenderer } = require('../utils/portfolio-miniapp-code-renderer')

Page({
  data: {
    status: 'idle',
    saving: false,
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
      onChange: state => this.setData(state),
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
