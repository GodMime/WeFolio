const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeVisitRecords } = require('../../utils/visits')

Page({
  data: {
    loading: true,
    errorMessage: '',
    visitData: normalizeVisitRecords({})
  },

  onLoad() {
    this.bootstrap()
  },

  onReady() {
    this.drawTrendLineChart()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadVisits()
  },

  async loadVisits() {
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: '/api/mine/visits'
      })
      this.setData({
        visitData: normalizeVisitRecords(response),
        loading: false
      }, () => {
        this.drawTrendLineChart()
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '访问记录加载失败'
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

  drawTrendLineChart() {
    if (typeof wx === 'undefined' || !wx.createSelectorQuery) {
      return
    }

    const points = this.data.visitData && this.data.visitData.trend
      ? this.data.visitData.trend.points || []
      : []
    if (!points.length) {
      return
    }

    wx.createSelectorQuery().in(this)
      .select('#trendLineCanvas')
      .fields({ node: true, size: true })
      .exec((result) => {
        const canvasInfo = result && result[0]
        if (!canvasInfo || !canvasInfo.node || !canvasInfo.width || !canvasInfo.height) {
          return
        }

        const canvas = canvasInfo.node
        const ctx = canvas.getContext('2d')
        if (!ctx) {
          return
        }

        const dpr = wx.getSystemInfoSync ? wx.getSystemInfoSync().pixelRatio || 1 : 1
        const width = canvasInfo.width
        const height = canvasInfo.height
        canvas.width = width * dpr
        canvas.height = height * dpr
        ctx.scale(dpr, dpr)
        ctx.clearRect(0, 0, width, height)

        const values = points.map((item) => Number(item.value) || 0)
        const maxValue = Math.max(...values, 0)
        const paddingX = 18
        const paddingTop = 30
        const paddingBottom = 24
        const chartHeight = height - paddingTop - paddingBottom
        const step = points.length > 1 ? (width - paddingX * 2) / (points.length - 1) : 0
        const linePoints = values.map((value, index) => {
          const x = paddingX + step * index
          const y = maxValue > 0
            ? paddingTop + chartHeight - (value / maxValue) * chartHeight
            : paddingTop + chartHeight * 0.68
          return { x, y, value }
        })

        ctx.lineWidth = 1
        ctx.strokeStyle = '#edf2f6'
        for (let index = 0; index < 3; index += 1) {
          const y = paddingTop + (chartHeight / 2) * index
          ctx.beginPath()
          ctx.moveTo(paddingX, y)
          ctx.lineTo(width - paddingX, y)
          ctx.stroke()
        }

        const baselineY = height - paddingBottom
        ctx.beginPath()
        linePoints.forEach((point, index) => {
          if (index === 0) {
            ctx.moveTo(point.x, point.y)
            return
          }
          ctx.lineTo(point.x, point.y)
        })
        ctx.lineTo(linePoints[linePoints.length - 1].x, baselineY)
        ctx.lineTo(linePoints[0].x, baselineY)
        ctx.closePath()
        ctx.fillStyle = 'rgba(15, 118, 110, 0.12)'
        ctx.fill()

        ctx.beginPath()
        linePoints.forEach((point, index) => {
          if (index === 0) {
            ctx.moveTo(point.x, point.y)
            return
          }
          ctx.lineTo(point.x, point.y)
        })
        ctx.lineWidth = 3
        ctx.lineCap = 'round'
        ctx.lineJoin = 'round'
        ctx.strokeStyle = '#0f766e'
        ctx.stroke()

        linePoints.forEach((point) => {
          const isHot = maxValue > 0 && point.value === maxValue
          ctx.beginPath()
          ctx.arc(point.x, point.y, isHot ? 5 : 4, 0, Math.PI * 2)
          ctx.fillStyle = isHot ? '#c9963f' : '#ffffff'
          ctx.fill()
          ctx.lineWidth = 2
          ctx.strokeStyle = isHot ? '#c9963f' : '#0f766e'
          ctx.stroke()
        })

        ctx.font = '11px sans-serif'
        ctx.textAlign = 'center'
        ctx.textBaseline = 'bottom'
        ctx.fillStyle = '#536b82'
        linePoints.forEach((point) => {
          const labelY = Math.max(14, point.y - 9)
          ctx.fillText(String(point.value), point.x, labelY)
        })
      })
  }
})
