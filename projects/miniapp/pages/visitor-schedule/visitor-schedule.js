const { request } = require('../../utils/request')
const { normalizeVisitorSchedule } = require('../../utils/visitor-portfolio')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'

function todayText() {
  return new Date().toISOString().slice(0, 10)
}

Page({
  data: {
    shareCode: '',
    visitorKey: '',
    startDate: todayText(),
    endDate: todayText(),
    schedules: []
  },

  onLoad(options = {}) {
    this.setData({
      shareCode: options.shareCode || '',
      visitorKey: options.visitorKey || ''
    })
  },

  handleInput(event) {
    const field = event.currentTarget.dataset.field
    this.setData({ [field]: event.detail.value })
  },

  handleQuery() {
    request({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/schedule`,
      data: {
        startDate: this.data.startDate,
        endDate: this.data.endDate,
        scope: 'ALL',
        visitorKey: this.data.visitorKey,
        idempotencyKey: `schedule-${Date.now()}`
      }
    }).then((response) => {
      this.setData({ schedules: normalizeVisitorSchedule(response).schedules })
    }).catch((error) => {
      wx.showToast({ title: error.message || '查询失败', icon: 'none' })
    })
  }
})
