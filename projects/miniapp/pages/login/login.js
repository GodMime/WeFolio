const {
  setToken,
  precheckMaintainerWechatLogin,
  maintainerWechatLogin
} = require('../../utils/session')
const { request } = require('../../utils/request')

const REFERRAL_CODE_MAX_LENGTH = 16
const LOGIN_TAB_EXPERIENCE = 'experience'
const LOGIN_TAB_MAINTAINER = 'maintainer'
const LOGIN_PAGE_CONFIG_URL = '/api/auth/login-page-config'
const AUTH_MODE_NONE = 'none'

function normalizeReferralCode(value) {
  if (typeof value !== 'string') {
    return ''
  }
  try {
    return decodeURIComponent(value).trim().slice(0, REFERRAL_CODE_MAX_LENGTH)
  } catch (error) {
    return ''
  }
}

function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success(response) {
        if (response.code) {
          resolve(response.code)
          return
        }
        reject(new Error('微信登录凭证为空'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '微信登录失败'))
      }
    })
  })
}

function tryWxPluginLogin() {
  return new Promise((resolve) => {
    if (!wx.pluginLogin) {
      resolve('')
      return
    }
    wx.pluginLogin({
      success(response) {
        resolve(response && response.code ? response.code : '')
      },
      fail() {
        resolve('')
      }
    })
  })
}

Page({
  data: {
    activeTab: LOGIN_TAB_EXPERIENCE,
    prechecking: false,
    precheckReady: false,
    phoneAuthorizationRequired: false,
    precheckErrorMessage: '',
    maintainerButtonText: '重新识别',
    loading: false,
    referralCode: ''
  },

  onLoad(options = {}) {
    const referralCode = normalizeReferralCode(options.referralCode)
    this._defaultTabLocked = Boolean(referralCode)
    if (referralCode) {
      this.setData({
        activeTab: LOGIN_TAB_MAINTAINER,
        referralCode
      })
    }
    this.loadLoginPageConfig()
    this.runWechatLoginPrecheck()
  },

  onHide() {
    this._defaultTabLocked = true
  },

  onUnload() {
    this._defaultTabLocked = true
  },

  async loadLoginPageConfig() {
    try {
      const config = await request({
        url: LOGIN_PAGE_CONFIG_URL,
        method: 'GET',
        authMode: AUTH_MODE_NONE
      })
      // 默认配置只影响首次展示，不能覆盖推荐入口、用户操作或离页后的状态。
      if (this._defaultTabLocked || this.data.loading) {
        return
      }
      this.setData({
        activeTab: config && config.defaultTab === LOGIN_TAB_MAINTAINER
          ? LOGIN_TAB_MAINTAINER
          : LOGIN_TAB_EXPERIENCE
      })
    } catch (error) {
      // 兼容尚未部署此接口的后端和网络失败，保留本地体验默认值。
    }
  },

  handleTabTap(event) {
    const tab = event.currentTarget.dataset.tab
    if (tab !== LOGIN_TAB_EXPERIENCE && tab !== LOGIN_TAB_MAINTAINER) {
      return
    }
    this._defaultTabLocked = true
    if (tab === this.data.activeTab) {
      return
    }
    this.setData({
      activeTab: tab
    })
  },

  handleExperienceTap() {
    this._defaultTabLocked = true
    wx.redirectTo({
      url: '/pages/mock/index/index'
    })
  },

  handleReferralInput(event) {
    this._defaultTabLocked = true
    this.setData({
      referralCode: event.detail.value || ''
    })
  },

  async runWechatLoginPrecheck() {
    if (this.data.prechecking || this.data.loading) {
      return
    }
    this.setData({
      prechecking: true,
      precheckReady: false,
      phoneAuthorizationRequired: false,
      precheckErrorMessage: '',
      maintainerButtonText: '识别账号中'
    })

    try {
      const code = await wxLogin()
      const response = await precheckMaintainerWechatLogin(code)
      const phoneAuthorizationRequired = Boolean(
        response && response.phoneAuthorizationRequired
      )
      this.setData({
        prechecking: false,
        precheckReady: true,
        phoneAuthorizationRequired,
        precheckErrorMessage: '',
        maintainerButtonText: phoneAuthorizationRequired ? '手机号快捷注册' : '登录'
      })
    } catch (error) {
      this.setData({
        prechecking: false,
        precheckReady: false,
        phoneAuthorizationRequired: false,
        precheckErrorMessage: '账号识别失败，请重试',
        maintainerButtonText: '重新识别'
      })
      wx.showToast({
        title: '账号识别失败，请重试',
        icon: 'none'
      })
    }
  },

  handleMaintainerAuthTap() {
    this._defaultTabLocked = true
    if (this.data.prechecking || this.data.loading) {
      return
    }
    if (!this.data.precheckReady) {
      this.runWechatLoginPrecheck()
      return
    }
    if (this.data.phoneAuthorizationRequired) {
      return
    }
    this.authorizeByWechat()
  },

  handleRegisterPhone(event) {
    this._defaultTabLocked = true
    if (
      this.data.prechecking
      || this.data.loading
      || !this.data.precheckReady
      || !this.data.phoneAuthorizationRequired
    ) {
      return
    }
    const detail = event.detail || {}
    if (detail.errMsg && !/getPhoneNumber:ok/.test(detail.errMsg)) {
      wx.showToast({
        title: '请先授权手机号',
        icon: 'none'
      })
      return
    }
    if (!detail.code) {
      wx.showToast({
        title: '手机号授权凭证为空',
        icon: 'none'
      })
      return
    }
    this.authorizeByWechat({
      phoneCode: detail.code
    })
  },

  async authorizeByWechat(options = {}) {
    this._defaultTabLocked = true
    if (this.data.loading) {
      return
    }
    this.setData({
      loading: true,
      maintainerButtonText: options.phoneCode ? '注册中' : '登录中'
    })

    try {
      const code = await wxLogin()
      const payload = { code }
      if (options.phoneCode) {
        const pluginLoginCode = await tryWxPluginLogin()
        Object.assign(payload, {
          phoneCode: options.phoneCode,
          pluginLoginCode,
          nickname: '',
          avatarUrl: '',
          referralCode: this.data.referralCode.trim()
        })
      }
      const response = await maintainerWechatLogin(payload)
      setToken(response.token)
      wx.redirectTo({
        url: '/pages/index/index'
      })
    } catch (error) {
      this.setData({
        loading: false,
        maintainerButtonText: options.phoneCode ? '手机号快捷注册' : '登录'
      })
      wx.showToast({
        title: error && error.message ? error.message : '登录失败',
        icon: 'none'
      })
      if (!options.phoneCode) {
        this.runWechatLoginPrecheck()
      }
    }
  }
})
