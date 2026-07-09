const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  appendVisitDetailPage,
  appendVisitEventTimeline,
  markContactLeadFollowed,
  markVisitRecordFollowed,
  normalizeVisitDetailPage,
  normalizeVisitEventTimeline,
  normalizeVisitRecords
} = require('../../utils/visits')

const MINE_VISITS_URL = '/api/mine/visits'
const FIRST_VISIT_EVENT_PAGE = 1
const VISIT_EVENT_PAGE_SIZE = 20
const FIRST_VISIT_DETAIL_PAGE = 1
const VISIT_DETAIL_PAGE_SIZE = 20
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const DETAIL_TYPE_SCHEDULE_QUERIES = 'scheduleQueries'
const DETAIL_TYPE_CONTACT_LEADS = 'contactLeads'
const DETAIL_ENDPOINTS = {
  [DETAIL_TYPE_SCHEDULE_QUERIES]: `${MINE_VISITS_URL}/schedule-queries`,
  [DETAIL_TYPE_CONTACT_LEADS]: `${MINE_VISITS_URL}/contact-leads`
}
const DETAIL_TITLES = {
  [DETAIL_TYPE_SCHEDULE_QUERIES]: '查询档期',
  [DETAIL_TYPE_CONTACT_LEADS]: '预留信息'
}

Page({
  data: {
    loading: true,
    errorMessage: '',
    visitData: normalizeVisitRecords({}),
    eventSheetVisible: false,
    eventSheetLoading: false,
    eventSheetLoadingMore: false,
    eventSheetErrorMessage: '',
    eventSheetRecordId: null,
    eventSheetPageNo: FIRST_VISIT_EVENT_PAGE,
    eventSheetPageSize: VISIT_EVENT_PAGE_SIZE,
    eventSheetHasMore: false,
    selectedVisitRecord: normalizeVisitEventTimeline({ events: [] }),
    detailSheetVisible: false,
    detailSheetLoading: false,
    detailSheetLoadingMore: false,
    detailSheetErrorMessage: '',
    detailSheetType: '',
    detailSheetPageNo: FIRST_VISIT_DETAIL_PAGE,
    detailSheetPageSize: VISIT_DETAIL_PAGE_SIZE,
    detailSheetHasMore: false,
    detailSheet: normalizeVisitDetailPage(DETAIL_TYPE_SCHEDULE_QUERIES, { items: [] }),
    revealedVisitRecordId: null,
    followingVisitRecordId: null,
    followingContactLeadId: null,
    visitTouchStart: null
  },

  onLoad() {
    this.bootstrap()
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
        url: MINE_VISITS_URL
      })
      this.setData({
        visitData: normalizeVisitRecords(response),
        loading: false,
        revealedVisitRecordId: null,
        followingVisitRecordId: null,
        followingContactLeadId: null,
        visitTouchStart: null
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
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

  noop() {
    // 用于 catchtouchmove 阻止弹层触摸事件继续穿透到页面。
  },

  handleMetricTap(event) {
    const action = event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.action
      : ''
    if (!action || !DETAIL_ENDPOINTS[action]) {
      return
    }
    this.openVisitDetailSheet(action)
  },

  openVisitDetailSheet(type) {
    this.setData({
      detailSheetVisible: true,
      detailSheetLoading: true,
      detailSheetLoadingMore: false,
      detailSheetErrorMessage: '',
      detailSheetType: type,
      detailSheetPageNo: FIRST_VISIT_DETAIL_PAGE,
      detailSheetPageSize: VISIT_DETAIL_PAGE_SIZE,
      detailSheetHasMore: false,
      followingContactLeadId: null,
      detailSheet: normalizeVisitDetailPage(type, {
        pageNo: FIRST_VISIT_DETAIL_PAGE,
        pageSize: VISIT_DETAIL_PAGE_SIZE,
        hasMore: false,
        items: []
      })
    })
    return this.loadVisitDetailPage(type, {
      pageNo: FIRST_VISIT_DETAIL_PAGE
    })
  },

  handleVisitTouchStart(event) {
    if (this.data.followingVisitRecordId) {
      this.setData({ visitTouchStart: null })
      return
    }
    const recordId = event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.recordId
      : ''
    const record = this.findVisitRecord(recordId)
    if (!record || !record.canMarkFollowed) {
      this.setData({ visitTouchStart: null })
      return
    }
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      visitTouchStart: {
        recordId: record.id,
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handleVisitTouchMove() {
  },

  handleVisitTouchEnd(event) {
    const start = this.data.visitTouchStart
    if (!start || !start.recordId) {
      return
    }
    const touch = (event.changedTouches && event.changedTouches[0]) || {}
    const deltaX = (touch.clientX || start.x) - start.x
    const deltaY = Math.abs((touch.clientY || start.y) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedVisitRecordId: start.recordId,
        visitTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedVisitRecordId: null,
        visitTouchStart: null
      })
      return
    }
    this.setData({ visitTouchStart: null })
  },

  handleVisitTouchCancel() {
    this.setData({ visitTouchStart: null })
  },

  handleVisitRowTap(event) {
    const recordId = event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.recordId
      : ''
    if (this.data.revealedVisitRecordId && String(this.data.revealedVisitRecordId) === String(recordId)) {
      this.setData({ revealedVisitRecordId: null })
      return
    }
    const record = this.findVisitRecord(recordId)
    if (!record || !record.id) {
      return
    }
    this.setData({
      eventSheetVisible: true,
      eventSheetLoading: true,
      eventSheetLoadingMore: false,
      eventSheetErrorMessage: '',
      eventSheetRecordId: record.id,
      eventSheetPageNo: FIRST_VISIT_EVENT_PAGE,
      eventSheetPageSize: VISIT_EVENT_PAGE_SIZE,
      eventSheetHasMore: false,
      selectedVisitRecord: record,
      revealedVisitRecordId: null
    })
    return this.loadVisitEvents(record, {
      pageNo: FIRST_VISIT_EVENT_PAGE
    })
  },

  findVisitRecord(recordId) {
    const records = this.data.visitData && Array.isArray(this.data.visitData.records)
      ? this.data.visitData.records
      : []
    return records.find((record) => String(record.id) === String(recordId))
  },

  async handleMarkVisitFollowedTap(event) {
    const recordId = event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.recordId
      : ''
    const record = this.findVisitRecord(recordId)
    if (!record || !record.id || !record.canMarkFollowed || this.data.followingVisitRecordId) {
      return
    }
    this.setData({ followingVisitRecordId: record.id })
    try {
      const response = await request({
        url: `${MINE_VISITS_URL}/${record.id}/followed`,
        method: 'PUT'
      })
      const visitData = markVisitRecordFollowed(this.data.visitData, record.id, response)
      const shouldUpdateSelected = this.data.selectedVisitRecord
        && String(this.data.selectedVisitRecord.id || this.data.selectedVisitRecord.recordId || '') === String(record.id)
      const selectedVisitRecord = shouldUpdateSelected
        ? markVisitRecordFollowed({ records: [this.data.selectedVisitRecord] }, record.id, response).records[0]
        : this.data.selectedVisitRecord
      this.setData({
        visitData,
        selectedVisitRecord,
        followingVisitRecordId: null,
        revealedVisitRecordId: null,
        visitTouchStart: null
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ followingVisitRecordId: null })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ followingVisitRecordId: null })
      if (typeof wx !== 'undefined' && wx.showToast) {
        wx.showToast({
          title: error && error.message ? error.message : '标记跟进失败',
          icon: 'none'
        })
      }
    }
  },

  async loadVisitEvents(record, options = {}) {
    const recordId = record.recordId || record.id
    const append = Boolean(options.append)
    const pageNo = options.pageNo || (append ? (this.data.eventSheetPageNo || FIRST_VISIT_EVENT_PAGE) + 1 : FIRST_VISIT_EVENT_PAGE)
    const pageSize = this.data.eventSheetPageSize || VISIT_EVENT_PAGE_SIZE
    const requestContext = this.createVisitEventRequestContext(recordId, pageNo)
    this.setData(append ? {
      eventSheetLoadingMore: true
    } : {
      eventSheetLoading: true,
      eventSheetLoadingMore: false,
      eventSheetErrorMessage: ''
    })
    try {
      const response = await request({
        url: `${MINE_VISITS_URL}/${recordId}/events`,
        data: {
          pageNo,
          pageSize
        }
      })
      if (!this.isCurrentVisitEventRequest(requestContext)) {
        return
      }
      const selectedVisitRecord = normalizeVisitEventTimeline(response, record)
      const mergedVisitRecord = append
        ? appendVisitEventTimeline(this.data.selectedVisitRecord, selectedVisitRecord)
        : selectedVisitRecord
      this.setData({
        eventSheetLoading: false,
        eventSheetLoadingMore: false,
        eventSheetErrorMessage: '',
        eventSheetPageNo: mergedVisitRecord.pageNo,
        eventSheetPageSize: mergedVisitRecord.pageSize,
        eventSheetHasMore: mergedVisitRecord.hasMore,
        selectedVisitRecord: mergedVisitRecord
      })
    } catch (error) {
      if (!this.isCurrentVisitEventRequest(requestContext)) {
        return
      }
      if (error && error.authRequired) {
        this.setData({
          eventSheetLoading: false,
          eventSheetLoadingMore: false
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      if (append) {
        this.setData({
          eventSheetLoadingMore: false
        })
        wx.showToast({
          title: error && error.message ? error.message : '更多事件加载失败',
          icon: 'none'
        })
        return
      }
      this.setData({
        eventSheetLoading: false,
        eventSheetLoadingMore: false,
        eventSheetErrorMessage: error && error.message ? error.message : '事件明细加载失败'
      })
    }
  },

  createVisitEventRequestContext(recordId, pageNo) {
    const requestId = (this.visitEventRequestId || 0) + 1
    this.visitEventRequestId = requestId
    return {
      requestId,
      recordId,
      pageNo
    }
  },

  isCurrentVisitEventRequest(requestContext) {
    return Boolean(requestContext)
      && this.visitEventRequestId === requestContext.requestId
      && String(this.data.eventSheetRecordId || '') === String(requestContext.recordId || '')
  },

  handleVisitEventScrollToLower() {
    if (
      this.data.eventSheetLoading ||
      this.data.eventSheetLoadingMore ||
      !this.data.eventSheetHasMore ||
      !this.data.eventSheetRecordId
    ) {
      return
    }
    return this.loadVisitEvents(this.data.selectedVisitRecord, {
      append: true,
      pageNo: (this.data.eventSheetPageNo || FIRST_VISIT_EVENT_PAGE) + 1
    })
  },

  handleCloseEventSheet() {
    this.visitEventRequestId = (this.visitEventRequestId || 0) + 1
    this.setData({
      eventSheetVisible: false,
      eventSheetLoading: false,
      eventSheetLoadingMore: false,
      eventSheetErrorMessage: '',
      eventSheetRecordId: null,
      eventSheetHasMore: false
    })
  },

  async loadVisitDetailPage(type, options = {}) {
    const append = Boolean(options.append)
    const pageNo = options.pageNo || (append ? (this.data.detailSheetPageNo || FIRST_VISIT_DETAIL_PAGE) + 1 : FIRST_VISIT_DETAIL_PAGE)
    const pageSize = this.data.detailSheetPageSize || VISIT_DETAIL_PAGE_SIZE
    const requestContext = this.createVisitDetailRequestContext(type, pageNo)
    this.setData(append ? {
      detailSheetLoadingMore: true
    } : {
      detailSheetLoading: true,
      detailSheetLoadingMore: false,
      detailSheetErrorMessage: ''
    })
    try {
      const response = await request({
        url: DETAIL_ENDPOINTS[type],
        data: {
          pageNo,
          pageSize
        }
      })
      if (!this.isCurrentVisitDetailRequest(requestContext)) {
        return
      }
      const normalizedPage = normalizeVisitDetailPage(type, response, this.data.detailSheet)
      const detailSheet = append
        ? appendVisitDetailPage(this.data.detailSheet, normalizedPage)
        : Object.assign({}, normalizedPage, { title: DETAIL_TITLES[type] })
      this.setData({
        detailSheetLoading: false,
        detailSheetLoadingMore: false,
        detailSheetErrorMessage: '',
        detailSheetPageNo: detailSheet.pageNo,
        detailSheetPageSize: detailSheet.pageSize,
        detailSheetHasMore: detailSheet.hasMore,
        detailSheet
      })
    } catch (error) {
      if (!this.isCurrentVisitDetailRequest(requestContext)) {
        return
      }
      if (error && error.authRequired) {
        this.setData({
          detailSheetLoading: false,
          detailSheetLoadingMore: false
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      if (append) {
        this.setData({
          detailSheetLoadingMore: false
        })
        wx.showToast({
          title: error && error.message ? error.message : '更多明细加载失败',
          icon: 'none'
        })
        return
      }
      this.setData({
        detailSheetLoading: false,
        detailSheetLoadingMore: false,
        detailSheetErrorMessage: error && error.message ? error.message : '明细加载失败'
      })
    }
  },

  createVisitDetailRequestContext(type, pageNo) {
    const requestId = (this.visitDetailRequestId || 0) + 1
    this.visitDetailRequestId = requestId
    return {
      requestId,
      type,
      pageNo
    }
  },

  isCurrentVisitDetailRequest(requestContext) {
    return Boolean(requestContext)
      && this.visitDetailRequestId === requestContext.requestId
      && this.data.detailSheetVisible
      && this.data.detailSheetType === requestContext.type
  },

  handleVisitDetailScrollToLower() {
    if (
      this.data.detailSheetLoading ||
      this.data.detailSheetLoadingMore ||
      !this.data.detailSheetHasMore ||
      !this.data.detailSheetType
    ) {
      return
    }
    return this.loadVisitDetailPage(this.data.detailSheetType, {
      append: true,
      pageNo: (this.data.detailSheetPageNo || FIRST_VISIT_DETAIL_PAGE) + 1
    })
  },

  findContactLead(leadId) {
    const items = this.data.detailSheet && Array.isArray(this.data.detailSheet.items)
      ? this.data.detailSheet.items
      : []
    return items.find((item) => String(item.id) === String(leadId))
  },

  async handleMarkContactLeadFollowedTap(event) {
    const leadId = event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.leadId
      : ''
    const lead = this.findContactLead(leadId)
    if (!lead || !lead.id || !lead.canMarkFollowed || this.data.followingContactLeadId) {
      return
    }
    this.setData({ followingContactLeadId: lead.id })
    try {
      const response = await request({
        url: `${MINE_VISITS_URL}/contact-leads/${lead.id}/followed`,
        method: 'PUT'
      })
      this.setData({
        detailSheet: markContactLeadFollowed(this.data.detailSheet, lead.id, response),
        followingContactLeadId: null
      })
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({ followingContactLeadId: null })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ followingContactLeadId: null })
      if (typeof wx !== 'undefined' && wx.showToast) {
        wx.showToast({
          title: error && error.message ? error.message : '标记跟进失败',
          icon: 'none'
        })
      }
    }
  },

  handleCopyContactValueTap(event) {
    const copyText = event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.copyText
      : ''
    if (!copyText || typeof wx === 'undefined' || !wx.setClipboardData) {
      return
    }
    wx.setClipboardData({
      data: copyText,
      success() {
        if (wx.showToast) {
          wx.showToast({
            title: '已复制',
            icon: 'none'
          })
        }
      }
    })
  },

  handleCloseDetailSheet() {
    this.visitDetailRequestId = (this.visitDetailRequestId || 0) + 1
    this.setData({
      detailSheetVisible: false,
      detailSheetLoading: false,
      detailSheetLoadingMore: false,
      detailSheetErrorMessage: '',
      detailSheetType: '',
      detailSheetHasMore: false,
      followingContactLeadId: null
    })
  }
})
