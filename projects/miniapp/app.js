const { createUpdateController } = require('./utils/update-manager')

const updateController = createUpdateController()

App({
  onLaunch() {
    try {
      updateController.init()
    } catch (error) {
      console.warn('初始化小程序更新监听失败', error)
    }
  },
  onShow() {
    try {
      updateController.promptIfReady()
    } catch (error) {
      console.warn('检查小程序待更新状态失败', error)
    }
  },
  globalData: {
    apiBaseUrl: 'https://api.we-folio.dingchenyong.top'
  }
})
