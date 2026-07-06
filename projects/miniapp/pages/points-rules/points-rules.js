const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizePointOverview } = require('../../utils/points')

const POINT_OVERVIEW_URL = '/api/mine/points'
const LOGIN_PAGE_URL = '/pages/login/login'

function emptyPointData() {
  return normalizePointOverview({})
}

Page({
  data: {
    loading: true,
    errorMessage: '',
    pointData: emptyPointData()
  },

  onLoad() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadRules()
  },

  async loadRules() {
    this.setData({
      loading: true,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: POINT_OVERVIEW_URL
      })
      this.setData({
        pointData: normalizePointOverview(response),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '积分规则加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: LOGIN_PAGE_URL
    })
  },

  handleRetry() {
    this.bootstrap()
  }
})
