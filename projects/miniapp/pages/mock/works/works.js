const { createMockAudioController } = require('../utils/mock-portfolio-audio')
const { openMockVideoPlayer } = require('../utils/mock-video-player')
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
    audioPlaying: false,
    audioWorkId: 0,
    keyword: '',
    selectedTagId: 0,
    visibleWorks: MOCK_WORK_LIBRARY.works,
    imagePreviewVisible: false,
    imagePreview: null
  },

  onHide() {
    this.mockVideoHidden = true
    if (this.mockAudio) this.mockAudio.hide()
  },

  onShow() {
    this.mockVideoHidden = false
    this.mockVideoSession = null
    if (this.mockAudio) this.mockAudio.show()
  },

  onUnload() {
    this.mockVideoDisposed = true
    this.mockVideoSession = null
    if (this.mockAudio) this.mockAudio.destroy()
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
    if (this.mockVideoDisposed || this.mockVideoHidden || this.mockVideoSession) return false
    const workId = Number(event.currentTarget.dataset.id || 0)
    const work = MOCK_WORK_LIBRARY.works.find((item) => item.id === workId)
    if (!work) {
      return
    }
    if (work.mediaType === 'AUDIO') {
      if (!this.mockAudio) this.mockAudio = createMockAudioController({wxApi:wx,
        onPlaying:playing=>this.setData({audioPlaying:playing}),
        onError:()=>wx.showToast({title:'音频播放失败，请点击重试',icon:'none'})})
      if (this.data.audioWorkId !== work.id) {
        this.mockAudio.setResource({workId:work.id,mediaUrl:work.mediaUrl,enabled:true})
        this.setData({audioWorkId:work.id})
      }
      this.mockAudio.toggle()
      return
    }
    if (this.mockAudio) this.mockAudio.pause()
    if (work.mediaType === 'VIDEO') {
      return this.openVideoPreview(work)
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

  async openVideoPreview(work) {
    if (this.mockVideoDisposed || this.mockVideoHidden || this.mockVideoSession) return false
    // mock 视频也必须离开带底栏的 Skyline 页面，避免 iPhone 全屏被底栏遮挡。
    const session = {}
    this.mockVideoSession = session
    const opened = await openMockVideoPlayer({
      url: work.mediaUrl,
      poster: work.coverUrl,
      title: work.title
    }, wx)
    // 返回或重新打开后，旧失败回调不能解除新播放会话的保护。
    if (!opened && this.mockVideoSession === session) this.mockVideoSession = null
    return opened
  },

  handleLockedAction() {
    showMockLoginRequiredToast()
  },

  handleImagePreviewPanelTap() {}
})
