const { request } = require('../../utils/request')
const { setToken, ensureSession, hasLocalToken } = require('../../utils/session')
const config = require('../../utils/android-config')

const MOBILE_LOGIN_URL = '/api/auth/mobile/wechat-login'
const LOGIN_CONFIG_URL = '/api/auth/mobile/config'
const HOME_PAGE = '/pages/index/index'

Page({
  data: { loading: false, agreed: false, message: '', loginReady: false },

  async onLoad() {
    if (hasLocalToken()) {
      try {
        const session = await ensureSession()
        if (session && session.authenticated) { wx.redirectTo({ url: HOME_PAGE }); return }
      } catch (error) { /* 失效会话允许用户重新授权。 */ }
    }
    if (!config.mobileAppId) {
      this.setData({ message: '微信登录尚未开通，可先体验功能。' })
      return
    }
    try {
      const capabilities = await request({ url: LOGIN_CONFIG_URL, authMode: 'none' })
      this.setData({ loginReady: capabilities.enabled === true,
        message: capabilities.enabled ? '' : '微信登录尚未开通，可先体验功能。' })
    } catch (error) {
      this.setData({ message: '暂时无法连接登录服务，请稍后重试。' })
    }
  },

  handleAgreement() { this.setData({ agreed: !this.data.agreed }) },

  handleExperienceTap() { wx.navigateTo({ url: '/pages/mock/index/index' }) },

  async handleLogin() {
    if (this.data.loading || !this.data.loginReady) return
    if (!this.data.agreed) { wx.showToast({ title: '请先确认微信授权说明', icon: 'none' }); return }
    if (!wx.miniapp || typeof wx.miniapp.login !== 'function') {
      this.setData({ message: '请在安装后的安卓 App 中使用微信登录。' })
      return
    }
    this.setData({ loading: true, message: '' })
    try {
      const authorization = await new Promise((resolve, reject) => wx.miniapp.login({ success: resolve, fail: reject }))
      if (!authorization || !authorization.code) throw new Error('微信未返回有效授权凭证，请重试')
      const result = await request({ url: MOBILE_LOGIN_URL, method: 'POST', authMode: 'none', data: { code: authorization.code } })
      if (!result || !result.token) throw new Error('登录服务未返回有效会话，请重试')
      setToken(result.token)
      wx.reLaunch({ url: HOME_PAGE })
    } catch (error) {
      this.setData({ message: /cancel/i.test(error.errMsg || '') ? '已取消微信授权' : error.message || '微信授权失败，请重试' })
    } finally { this.setData({ loading: false }) }
  }
})
