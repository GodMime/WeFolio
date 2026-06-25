const { request } = require('../../utils/request')
const { clearToken, handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { uploadAvatar } = require('../../utils/avatar')
const {
  DEFAULT_TAG_COLOR,
  TAG_COLOR_OPTIONS,
  buildProfileFieldCounters,
  buildProfilePayload,
  createProfileTag,
  normalizeProfile,
  validateProfileForm
} = require('../../utils/profile')

function emptyProfile() {
  return normalizeProfile({})
}

function emptyForm() {
  return {
    nickname: '',
    avatarUrl: '',
    profession: '',
    city: '',
    intro: '',
    tags: []
  }
}

function formFromProfile(profile) {
  return {
    nickname: profile.nickname,
    avatarUrl: profile.avatarUrl,
    profession: profile.profession,
    city: profile.city,
    intro: profile.intro,
    tags: profile.tags.slice()
  }
}

function trimText(value) {
  return (value || '').trim()
}

Page({
  data: {
    loading: true,
    saving: false,
    errorMessage: '',
    profile: emptyProfile(),
    form: emptyForm(),
    fieldCounters: buildProfileFieldCounters(emptyForm()),
    cancelling: false,
    tagDialogVisible: false,
    tagColorOptions: TAG_COLOR_OPTIONS,
    selectedTagColor: DEFAULT_TAG_COLOR,
    newTag: '',
    tagErrorText: ''
  },

  onLoad() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadProfile()
  },

  async loadProfile() {
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: '/api/mine/profile'
      })
      const profile = normalizeProfile(response)
      const form = formFromProfile(profile)
      this.setData({
        profile,
        form,
        fieldCounters: buildProfileFieldCounters(form),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '基础信息加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleRetry() {
    this.bootstrap()
  },

  handleInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    const value = event.detail.value || ''
    const form = Object.assign({}, this.data.form, {
      [field]: value
    })
    this.setData({
      [`form.${field}`]: value,
      fieldCounters: buildProfileFieldCounters(form)
    })
  },

  handleChooseAvatar(event) {
    const avatarUrl = event.detail && event.detail.avatarUrl ? event.detail.avatarUrl : ''
    if (!avatarUrl) {
      return
    }
    this.setData({
      'form.avatarUrl': avatarUrl
    })
  },

  handleOpenTagDialog() {
    if (this.data.form.tags.length >= 10) {
      wx.showToast({
        title: '标签最多保留 10 个',
        icon: 'none'
      })
      return
    }
    this.setData({
      tagDialogVisible: true,
      selectedTagColor: DEFAULT_TAG_COLOR,
      newTag: '',
      tagErrorText: ''
    })
  },

  handleCloseTagDialog() {
    this.setData({
      tagDialogVisible: false,
      selectedTagColor: DEFAULT_TAG_COLOR,
      newTag: '',
      tagErrorText: ''
    })
  },

  handleNewTagInput(event) {
    this.setData({
      newTag: event.detail.value || '',
      tagErrorText: ''
    })
  },

  handleSelectTagColor(event) {
    const color = event.currentTarget.dataset.color || DEFAULT_TAG_COLOR
    this.setData({
      selectedTagColor: color,
      tagErrorText: ''
    })
  },

  handleAddTag() {
    const value = trimText(this.data.newTag)
    const nextTags = this.data.form.tags.concat(createProfileTag(value, this.data.selectedTagColor))
    const validation = validateProfileForm(Object.assign({}, this.data.form, {
      tags: nextTags
    }))
    if (!validation.valid) {
      this.setData({
        tagErrorText: validation.message
      })
      return
    }

    this.setData({
      'form.tags': nextTags,
      'profile.tagCountText': `${nextTags.length} / 10`,
      tagDialogVisible: false,
      selectedTagColor: DEFAULT_TAG_COLOR,
      newTag: '',
      tagErrorText: ''
    })
  },

  handleRemoveTag(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isInteger(index) || index < 0) {
      return
    }
    const nextTags = this.data.form.tags.filter((_, currentIndex) => currentIndex !== index)
    this.setData({
      'form.tags': nextTags,
      'profile.tagCountText': `${nextTags.length} / 10`
    })
  },

  async handleSave() {
    if (this.data.saving) {
      return
    }

    const validation = validateProfileForm(this.data.form)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }

    this.setData({
      saving: true
    })

    try {
      const payload = buildProfilePayload(this.data.form)
      const avatarUrl = await uploadAvatar(payload.avatarUrl)
      const response = await request({
        url: '/api/mine/profile',
        method: 'PUT',
        data: Object.assign({}, payload, {
          avatarUrl
        })
      })
      const profile = normalizeProfile(response)
      const form = formFromProfile(profile)
      this.setData({
        profile,
        form,
        fieldCounters: buildProfileFieldCounters(form),
        saving: false
      })
      wx.showToast({
        title: '已保存',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '保存失败',
        icon: 'none'
      })
    }
  },

  handleLogout() {
    clearToken()
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleCancelAccount() {
    if (this.data.cancelling) {
      return
    }
    wx.showModal({
      title: '确认注销账号',
      content: '注销后账号将被停用，当前登录会失效。',
      confirmText: '确认注销',
      confirmColor: '#d83a3a',
      success: (result) => {
        if (result && result.confirm) {
          this.cancelAccount()
        }
      }
    })
  },

  async cancelAccount() {
    this.setData({
      cancelling: true
    })

    try {
      await request({
        url: '/api/auth/account/cancel',
        method: 'POST'
      })
      clearToken()
      wx.showToast({
        title: '账号已注销',
        icon: 'none'
      })
      wx.redirectTo({
        url: '/pages/login/login'
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        cancelling: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '注销失败',
        icon: 'none'
      })
    }
  }
})
