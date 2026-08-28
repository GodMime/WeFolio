/**
 * 当前状态：业务入口不可达。
 * 原因：作品集未开放功能目前统一在列表页用 Toast 提示，不再跳转本功能预留页。
 * 保留说明：暂时保留页面注册和占位实现，待确认不再需要独立承接页后统一清理。
 */
Page({
  data: {
    type: ''
  },

  onLoad(options = {}) {
    this.setData({ type: options.type || '' })
  }
})
