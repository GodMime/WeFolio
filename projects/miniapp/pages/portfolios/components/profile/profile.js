const { normalizeProfileBorderConfig, PROFILE_BORDER_AUTO_COLOR } = require('../../utils/portfolios')

Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
    profile: {
      type: Object,
      value: {}
    }
  },

  data: {
    profileBorderVisible: false,
    profileBorderStyle: '',
    profileSpacingStyle: ''
  },

  observers: {
    profile(profile) {
      this.updateBorderStyle(profile)
    }
  },

  lifetimes: {
    attached() {
      this.updateBorderStyle(this.data.profile)
    }
  },

  methods: {
    /** 规范化后的颜色和线宽才能进入样式，默认色随作品集主题切换。 */
    updateBorderStyle(profile = {}) {
      const border = normalizeProfileBorderConfig(profile || {})
      const color = border.profileBorderColor === PROFILE_BORDER_AUTO_COLOR
        ? 'var(--portfolio-border)' : border.profileBorderColor
      this.setData({
        profileBorderVisible: border.profileBorder,
        // 外层用内边距承载卡片外侧留白，避免上下外边距折叠，保留完整组件占位。
        profileSpacingStyle: border.profileBorder
          ? `padding: ${border.profileVerticalMarginRpx}rpx ${border.profileHorizontalMarginRpx}rpx;` : '',
        profileBorderStyle: border.profileBorder
          ? `border-width: ${border.profileBorderWidthRpx}rpx; border-color: ${color};` : ''
      })
    },

    handlePreviewQr() {
      const profile = this.data.profile || {}
      this.triggerEvent('previewqr', { qrUrl: profile.wechatQrUrl || '' })
    }
  }
})
