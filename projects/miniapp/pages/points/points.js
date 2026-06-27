const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  appendPointTransactions,
  buildPointTransactionQuery,
  normalizePointOverview,
  normalizePointTransactions
} = require('../../utils/points')

const POINT_OVERVIEW_URL = '/api/mine/points'
const POINT_TRANSACTIONS_URL = '/api/mine/points/transactions'
const POINT_RULES_PAGE_URL = '/pages/points-rules/points-rules'
const LOGIN_PAGE_URL = '/pages/login/login'
const PAGE_SIZE = 20

function emptyPointData() {
  return normalizePointOverview({})
}

function emptyTransactionData() {
  return normalizePointTransactions({})
}

Page({
  data: {
    loading: true,
    loadingMore: false,
    errorMessage: '',
    pointData: emptyPointData(),
    transactionData: emptyTransactionData()
  },

  onLoad() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadPoints()
  },

  async loadPoints() {
    this.setData({
      loading: true,
      loadingMore: false,
      errorMessage: ''
    })

    try {
      const [overview, transactions] = await Promise.all([
        request({ url: POINT_OVERVIEW_URL }),
        request({
          url: POINT_TRANSACTIONS_URL,
          data: buildPointTransactionQuery({ page: 1, pageSize: PAGE_SIZE })
        })
      ])
      this.setData({
        pointData: normalizePointOverview(overview),
        transactionData: normalizePointTransactions(transactions),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '积分加载失败'
      })
    }
  },

  async handleLoadMore() {
    const transactionData = this.data.transactionData || {}
    if (this.data.loading || this.data.loadingMore || !transactionData.hasMore) {
      return
    }
    this.setData({
      loadingMore: true
    })
    try {
      const response = await request({
        url: POINT_TRANSACTIONS_URL,
        data: buildPointTransactionQuery({
          page: transactionData.nextPage,
          pageSize: PAGE_SIZE
        })
      })
      this.setData({
        transactionData: appendPointTransactions(this.data.transactionData, response),
        loadingMore: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loadingMore: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '更多流水加载失败',
        icon: 'none'
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
  },

  handleRechargeTap() {
    wx.showToast({
      title: '体验版暂时只支持后台加积分',
      icon: 'none'
    })
  },

  handleRuleTap() {
    wx.navigateTo({
      url: POINT_RULES_PAGE_URL
    })
  }
})
