const { request } = require('../../utils/request')
const { normalizePortfolioConfig } = require('../../utils/portfolios')

const PORTFOLIO_API_PREFIX = '/api/mine/portfolios'

Page({
  data: {
    portfolioId: null,
    config: normalizePortfolioConfig({})
  },

  onLoad(options = {}) {
    this.setData({ portfolioId: options.portfolioId || null })
    if (options.portfolioId) {
      request({ url: `${PORTFOLIO_API_PREFIX}/${options.portfolioId}/preview` })
        .then((response) => {
          this.setData({ config: normalizePortfolioConfig(response.config || {}) })
        })
        .catch((error) => {
          wx.showToast({ title: error.message || '预览加载失败', icon: 'none' })
        })
    }
  }
})
