const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  appendRechargeOrders,
  applyRechargeOrderSyncs,
  buildRechargeOrderQuery,
  normalizeRechargeOrders
} = require('../../utils/recharge')

const RECHARGE_ORDERS_URL = '/api/mine/recharges/orders'
const LOGIN_PAGE_URL = '/pages/login/login'
const FIRST_PAGE = 1
const PAGE_SIZE = 20

function emptyOrderData() {
  return normalizeRechargeOrders({})
}

Page({
  data: {
    loading: true,
    loadingMore: false,
    errorMessage: '',
    recordSkeletons: [1, 2, 3],
    orderData: emptyOrderData()
  },

  onLoad() {
    this.syncedOrderNos = {}
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      wx.redirectTo({ url: LOGIN_PAGE_URL })
      return
    }
    this.loadOrders()
  },

  async loadOrders(options = {}) {
    const append = Boolean(options.append)
    const page = append ? this.data.orderData.nextPage : FIRST_PAGE
    this.setData(append ? { loadingMore: true } : {
      loading: true,
      loadingMore: false,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: RECHARGE_ORDERS_URL,
        data: buildRechargeOrderQuery({ page, pageSize: PAGE_SIZE })
      })
      const nextData = normalizeRechargeOrders(response)
      this.setData({
        orderData: append ? appendRechargeOrders(this.data.orderData, nextData) : nextData,
        loading: false,
        loadingMore: false
      })
      await this.syncPendingOrders(nextData.records)
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ loading: false, loadingMore: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      if (append) {
        this.setData({ loadingMore: false })
        wx.showToast({
          title: error && error.message ? error.message : '更多充值记录加载失败',
          icon: 'none'
        })
        return
      }
      this.setData({
        loading: false,
        loadingMore: false,
        errorMessage: error && error.message ? error.message : '充值记录加载失败'
      })
    }
  },

  async syncPendingOrders(records) {
    const pendingRecords = records.filter((item) => (
      item.status === 'PENDING_PAYMENT' && !this.syncedOrderNos[item.merchantOrderNo]
    ))
    pendingRecords.forEach((item) => {
      this.syncedOrderNos[item.merchantOrderNo] = true
    })
    if (!pendingRecords.length) {
      return
    }
    const results = await Promise.all(pendingRecords.map((item) => request({
      url: `${RECHARGE_ORDERS_URL}/${encodeURIComponent(item.merchantOrderNo)}/sync`,
      method: 'POST'
    }).catch((error) => {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
      }
      return null
    })))
    this.setData({
      orderData: applyRechargeOrderSyncs(this.data.orderData, results.filter(Boolean))
    })
  },

  handleRetry() {
    this.bootstrap()
  },

  handleLoadMore() {
    if (this.data.loading || this.data.loadingMore || !this.data.orderData.hasMore) {
      return
    }
    this.loadOrders({ append: true })
  }
})
