const { createUpdateController } = require('./utils/update-manager')
const { createMaintainerWechatSessionController } = require('./utils/maintainer-wechat-session')
const { loadPortfolioFonts } = require('./utils/portfolio-font-loader')

const updateController = createUpdateController()
const maintainerWechatSessionController = createMaintainerWechatSessionController()

App({
  onLaunch() {
    try {
      updateController.init()
    } catch (error) {
      console.warn('初始化小程序更新监听失败', error)
    }
    try {
      loadPortfolioFonts().catch((error) => {
        console.warn('注册作品集内置字体失败', error)
      })
    } catch (error) {
      console.warn('启动作品集内置字体注册失败', error)
    }
  },
  onShow() {
    try {
      updateController.promptIfReady()
    } catch (error) {
      console.warn('检查小程序待更新状态失败', error)
    }
    try {
      maintainerWechatSessionController.onShow().catch((error) => {
        console.warn('维护者微信会话前台维护失败', error)
      })
    } catch (error) {
      console.warn('启动维护者微信会话前台维护失败', error)
    }
  },
  onHide() {
    try {
      maintainerWechatSessionController.onHide()
    } catch (error) {
      console.warn('停止维护者微信会话前台维护失败', error)
    }
  },
  globalData: {
    apiBaseUrl: 'https://api.we-folio.dingchenyong.top'
  }
})
