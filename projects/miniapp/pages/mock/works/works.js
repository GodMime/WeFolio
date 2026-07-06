const {
  MOCK_WORK_LIBRARY,
  getMockTabs,
  showMockLoginRequiredToast
} = require('../../../utils/mock-experience')

Page({
  data: {
    tabs: getMockTabs('work'),
    list: MOCK_WORK_LIBRARY,
    selectedTagId: 0,
    visibleWorks: MOCK_WORK_LIBRARY.works,
    videoPreviewVisible: false,
    videoPreview: {
      title: '',
      src: '',
      poster: ''
    }
  },

  handleTagTap(event) {
    const tagId = Number(event.currentTarget.dataset.id || 0)
    this.setData({
      selectedTagId: tagId,
      visibleWorks: tagId
        ? MOCK_WORK_LIBRARY.works.filter((work) => work.tags.some((tag) => tag.id === tagId))
        : MOCK_WORK_LIBRARY.works
    })
  },

  handleWorkTap(event) {
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
    wx.previewImage({
      current: work.mediaUrl,
      urls: MOCK_WORK_LIBRARY.works
        .filter((item) => item.mediaType === 'IMAGE')
        .map((item) => item.mediaUrl)
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

  noop() {}
})
