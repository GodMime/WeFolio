const { loadPortfolioFonts } = require('./utils/portfolio-font-loader')
const { visitActivityLifecycle } = require('./utils/visit-activity-lifecycle')
const { apiBaseUrl } = require('./utils/android-config')

App({
  onLaunch() {
    loadPortfolioFonts().catch(() => {})
  },
  onShow() {
    visitActivityLifecycle.onShow()
  },
  onHide() {
    visitActivityLifecycle.onHide()
  },
  globalData: {
    visitDeviceCollectionEnabled: false,
    apiBaseUrl
  }
})
