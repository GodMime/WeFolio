const {
  MOCK_WORK_LIBRARY,
  filterMockWorks,
  getMockTabs,
  showMockLoginRequiredToast
} = require('../utils/mock-experience')

Page({
  data: {
    tabs: getMockTabs('work'),
    list: MOCK_WORK_LIBRARY,
    keyword: '',
    selectedTagId: 0,
    visibleWorks: MOCK_WORK_LIBRARY.works,
    imagePreviewVisible: false,
    imagePreview: null,
    videoPreviewVisible: false,
    videoPreview: {
      title: '',
      src: '',
      poster: ''
    }
  },

  applyFilters(patch = {}) {
    const keyword = Object.prototype.hasOwnProperty.call(patch, 'keyword')
      ? patch.keyword
      : this.data.keyword
    const selectedTagId = Object.prototype.hasOwnProperty.call(patch, 'selectedTagId')
      ? patch.selectedTagId
      : this.data.selectedTagId
    this.setData(Object.assign({}, patch, {
      keyword,
      selectedTagId,
      visibleWorks: filterMockWorks(
        MOCK_WORK_LIBRARY.works,
        keyword,
        selectedTagId
      )
    }))
  },

  handleSearchInput(event) {
    const keyword = event.detail && event.detail.value
    this.applyFilters({ keyword: keyword || '' })
  },

  handleSearchConfirm() {
    this.applyFilters()
  },

  handleClearSearch() {
    this.applyFilters({ keyword: '' })
  },

  handleTagTap(event) {
    const tagId = Number(event.currentTarget.dataset.id || 0)
    this.applyFilters({ selectedTagId: tagId })
  },

  handleWorkTap() {
    this.handleLockedAction()
  },

  handleWorkPreviewTap(event) {
    const workId = Number(event.currentTarget.dataset.id || 0)
    const work = MOCK_WORK_LIBRARY.works.find((item) => item.id === workId)
    if (!work) {
      return
    }
    if (work.mediaType === 'VIDEO') {
      this.setData({
        videoPreviewVisible: true,
        videoPreview: {
          title: work.title,
          src: work.mediaUrl,
          poster: work.coverUrl
        }
      })
      return
    }
    this.setData({
      imagePreviewVisible: true,
      imagePreview: {
        title: work.title,
        src: work.mediaUrl
      }
    })
  },

  handleCloseImagePreview() {
    this.setData({
      imagePreviewVisible: false,
      imagePreview: null
    })
  },

  handleCloseVideoPreview() {
    this.setData({
      videoPreviewVisible: false,
      videoPreview: {
        title: '',
        src: '',
        poster: ''
      }
    })
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  },

  handleImagePreviewPanelTap() {},

  handleVideoPreviewPanelTap() {}
})
