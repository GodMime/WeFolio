const { request } = require('../../utils/request')
const {
  handleMaintainerAuthRequired,
  hasLocalToken,
  refreshMaintainerWechatSession
} = require('../../utils/session')
const {
  applyRechargeBalance,
  buildRequestVirtualPaymentOptions,
  isPaymentCancelled,
  normalizeRechargePage,
  selectRechargePackage
} = require('../../utils/recharge')

const RECHARGE_PAGE_URL = '/api/mine/recharges'
const CREATE_ORDER_URL = '/api/mine/recharges/orders'
const RECHARGE_RECORDS_PAGE_URL = '/pages/recharge-records/recharge-records'
const POINT_RULES_PAGE_URL = '/pages/points-rules/points-rules'
const LOGIN_PAGE_URL = '/pages/login/login'

function emptyRechargeData() {
  return normalizeRechargePage({})
}

function requestWechatVirtualPayment(paymentOptions) {
  return new Promise((resolve, reject) => {
    wx.requestVirtualPayment(Object.assign({}, paymentOptions, {
      success: resolve,
      fail: reject
    }))
  })
}

function refreshWechatSessionNow() {
  return new Promise((resolve, reject) => {
    wx.login({
      success(result) {
        if (!result || !result.code) {
          reject(new Error('微信登录未返回有效 code'))
          return
        }
        Promise.resolve(refreshMaintainerWechatSession(result.code)).then(resolve, reject)
      },
      fail: reject
    })
  })
}

function isVirtualPaymentSessionInvalid(error) {
  return Number(error && (error.errCode || error.err_code)) === -15007
}

Page({
  data: {
    loading: true,
    paying: false,
    syncing: false,
    errorMessage: '',
    packageSkeletons: [1, 2, 3, 4],
    rechargeData: emptyRechargeData()
  },

  onLoad() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      wx.redirectTo({ url: LOGIN_PAGE_URL })
      return
    }
    this.loadRechargePage()
  },

  async loadRechargePage(options = {}) {
    const silent = Boolean(options.silent)
    if (!silent) {
      this.setData({ loading: true, errorMessage: '' })
    }
    try {
      const response = await request({ url: RECHARGE_PAGE_URL })
      this.setData({
        rechargeData: normalizeRechargePage(response),
        loading: false,
        errorMessage: ''
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ loading: false })
        handleMaintainerAuthRequired(error.message)
        return
      }
      if (silent) {
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '充值套餐加载失败'
      })
    }
  },

  handleRetry() {
    this.bootstrap()
  },

  handlePackageTap(event) {
    if (this.data.paying || this.data.syncing) {
      return
    }
    const packageId = event.currentTarget.dataset.packageId
    this.setData({
      rechargeData: selectRechargePackage(this.data.rechargeData, packageId)
    })
  },

  async handlePay(options = {}) {
    if (this.data.paying || this.data.syncing) {
      return
    }
    const packageId = this.data.rechargeData.selectedPackageId
    if (!packageId) {
      wx.showToast({ title: '请选择充值套餐', icon: 'none' })
      return
    }

    this.setData({ paying: true })
    let paymentCompleted = false
    try {
      const order = await request({
        url: CREATE_ORDER_URL,
        method: 'POST',
        data: { packageId }
      })
      await requestWechatVirtualPayment(buildRequestVirtualPaymentOptions(order))
      paymentCompleted = true
      this.setData({ paying: false, syncing: true })

      let syncResult
      try {
        syncResult = await request({
          url: `${CREATE_ORDER_URL}/${encodeURIComponent(order.merchantOrderNo)}/sync`,
          method: 'POST'
        })
      } catch (syncError) {
        if (syncError && syncError.authRequired) {
          handleMaintainerAuthRequired(syncError.message)
          return
        }
        wx.showToast({
          title: '支付结果暂未确认，请稍后在充值记录中查看',
          icon: 'none'
        })
        return
      }

      if (syncResult.status === 'PAID') {
        this.setData({
          rechargeData: applyRechargeBalance(this.data.rechargeData, syncResult.balance)
        })
        await this.loadRechargePage({ silent: true })
        wx.showToast({ title: '充值成功，积分已到账', icon: 'success' })
      } else {
        wx.showToast({ title: '支付结果确认中，请稍后在充值记录中查看', icon: 'none' })
      }
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      if (!paymentCompleted && isPaymentCancelled(error)) {
        wx.showToast({ title: '已取消支付', icon: 'none' })
        return
      }
      if (!paymentCompleted && !options.sessionRetried && isVirtualPaymentSessionInvalid(error)) {
        await refreshWechatSessionNow()
        this.setData({ paying: false, syncing: false })
        return this.handlePay({ sessionRetried: true })
      }
      wx.showToast({
        title: error && error.message ? error.message : '支付调起失败，请稍后重试',
        icon: 'none'
      })
    } finally {
      this.setData({ paying: false, syncing: false })
    }
  },

  handleRecordsTap() {
    wx.navigateTo({ url: RECHARGE_RECORDS_PAGE_URL })
  },

  handleRuleTap() {
    wx.navigateTo({ url: POINT_RULES_PAGE_URL })
  }
})
