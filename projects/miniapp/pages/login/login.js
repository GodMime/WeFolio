const { DEFAULT_BASE_URL } = require('../../utils/request')
const { setToken, wechatLogin } = require('../../utils/session')

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

function getProfileErrorText(avatarError, nicknameError) {
  if (avatarError && nicknameError) {
    return '请先授权微信头像和昵称'
  }
  if (avatarError) {
    return '请先授权微信头像'
  }
  if (nicknameError) {
    return '请先授权微信昵称'
  }
  return ''
}

function trimText(value) {
  return (value || '').trim()
}

function isRemoteUrl(url) {
  return /^https?:\/\//.test(url || '')
}

function uploadAvatar(filePath) {
  if (!filePath || isRemoteUrl(filePath)) {
    return Promise.resolve(filePath || '')
  }

  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${DEFAULT_BASE_URL}/api/auth/avatar`,
      filePath,
      name: 'file',
      success(response) {
        let body = {}
        try {
          body = JSON.parse(response.data || '{}')
        } catch (error) {
          reject(new Error('头像上传响应解析失败'))
          return
        }
        if (response.statusCode < 200 || response.statusCode >= 300 || body.success === false) {
          reject(new Error(body.message || '头像上传失败'))
          return
        }
        resolve(body.data && body.data.url ? body.data.url : '')
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '头像上传失败'))
      }
    })
  })
}

Page({
  data: {
    activeTab: 'register',
    loading: false,
    referralCode: '',
    nickname: '',
    avatarUrl: '',
    avatarError: false,
    nicknameError: false,
    profileErrorText: ''
  },

  handleTabTap(event) {
    const tab = event.currentTarget.dataset.tab
    if (!tab || tab === this.data.activeTab) {
      return
    }
    this.setData({
      activeTab: tab
    })
  },

  handleChooseAvatar(event) {
    const avatarUrl = event.detail && event.detail.avatarUrl ? event.detail.avatarUrl : ''
    if (!avatarUrl) {
      return
    }
    this.setProfileData({
      avatarUrl
    })
  },

  handleNicknameInput(event) {
    this.setProfileData({
      nickname: event.detail.value || ''
    })
  },

  handleReferralInput(event) {
    this.setData({
      referralCode: event.detail.value || ''
    })
  },

  setProfileData(fields) {
    const nextData = Object.assign({}, this.data, fields)
    const avatarError = this.data.avatarError && !nextData.avatarUrl
    const nicknameError = this.data.nicknameError && !trimText(nextData.nickname)
    this.setData(Object.assign({}, fields, {
      avatarError,
      nicknameError,
      profileErrorText: getProfileErrorText(avatarError, nicknameError)
    }))
  },

  validateProfile() {
    const avatarError = !this.data.avatarUrl
    const nicknameError = !trimText(this.data.nickname)
    const profileErrorText = getProfileErrorText(avatarError, nicknameError)
    this.setData({
      avatarError,
      nicknameError,
      profileErrorText
    })
    return {
      valid: !profileErrorText,
      message: profileErrorText
    }
  },

  handleIncompleteRegisterTap() {
    const result = this.validateProfile()
    wx.showToast({
      title: result.message || '请先授权头像和昵称',
      icon: 'none'
    })
  },

  handleRegisterPhone(event) {
    const profileValidation = this.validateProfile()
    if (!profileValidation.valid) {
      wx.showToast({
        title: profileValidation.message || '请先授权头像和昵称',
        icon: 'none'
      })
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
      phoneCode: detail.code,
      usePluginOpenpid: true
    })
  },

  handleWechatLogin() {
    this.authorizeByWechat({})
  },

  async authorizeByWechat(options = {}) {
    if (this.data.loading) {
      return
    }
    this.setData({
      loading: true
    })

    try {
      const code = await wxLogin()
      const pluginLoginCode = options.usePluginOpenpid ? await tryWxPluginLogin() : ''
      const avatarUrl = await uploadAvatar(this.data.avatarUrl)
      const response = await wechatLogin({
        code,
        phoneCode: options.phoneCode || '',
        pluginLoginCode,
        nickname: this.data.nickname.trim(),
        avatarUrl,
        referralCode: options.phoneCode ? this.data.referralCode.trim() : ''
      })
      setToken(response.token)
      wx.redirectTo({
        url: '/pages/index/index'
      })
    } catch (error) {
      this.setData({
        loading: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '登录失败',
        icon: 'none'
      })
    }
  }
})
