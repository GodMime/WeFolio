Component({
  data: {
    navigationBackStyle: '',
    menuArrowStyle: ''
  },

  properties: {
    visible: {
      type: Boolean,
      value: false
    },
    back: {
      type: Boolean,
      value: false
    }
  },

  lifetimes: {
    attached() {
      const systemInfo = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {}
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : systemInfo
      const menuButtonRect = wx.getMenuButtonBoundingClientRect()
      const windowWidth = Math.max(0, Number(windowInfo.windowWidth) || Number(systemInfo.windowWidth) || 0)
      const menuTop = Math.max(0, Number(menuButtonRect.top) || 0)
      const menuHeight = Math.max(32, Number(menuButtonRect.height) || 0)
      const menuBottom = Math.max(menuTop + menuHeight, Number(menuButtonRect.bottom) || 0)
      const menuRight = Number(menuButtonRect.right) || windowWidth
      const arrowRight = Math.max(16, windowWidth - menuRight + 8)

      this.setData({
        navigationBackStyle: `top: ${menuTop}px; width: ${menuHeight}px; height: ${menuHeight}px;`,
        menuArrowStyle: `top: ${menuBottom + 6}px; right: ${arrowRight}px;`
      })
    }
  },

  methods: {
    noop() {},

    handleBack() {
      this.triggerEvent('back', {})
    },

    handleClose() {
      this.triggerEvent('close', {})
    }
  }
})
