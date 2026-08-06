Component({
  properties: {
    currentSelection: { type: Object, value: null },
    keyword: { type: String, value: '' },
    filterTags: { type: Array, value: [] },
    selectedTagId: { type: null, value: null },
    options: { type: Array, value: [] },
    loading: { type: Boolean, value: false },
    loadingMore: { type: Boolean, value: false },
    errorText: { type: String, value: '' },
    emptyText: { type: String, value: '暂无作品' },
    hasMore: { type: Boolean, value: false },
    showTitle: { type: Boolean, value: true },
    showDescription: { type: Boolean, value: false }
  },
  methods: {
    handleKeywordInput(event) {
      this.triggerEvent('keywordchange', {
        value: event.detail && event.detail.value ? event.detail.value : ''
      })
    },
    handleSearchConfirm() {
      this.triggerEvent('search')
    },
    handleClearSearch() {
      this.triggerEvent('clearsearch')
    },
    handleTagTap(event) {
      this.triggerEvent('tagchange', {
        tagId: event.currentTarget.dataset.tagId || null
      })
    },
    handleWorkTap(event) {
      this.triggerEvent('workselect', {
        work: event.currentTarget.dataset.item || null
      })
    },
    handleScrollToLower() {
      if (this.properties.loading || this.properties.loadingMore || !this.properties.hasMore) {
        return
      }
      this.triggerEvent('loadmore')
    },
    handleRetryLoadMore() {
      this.triggerEvent('retryloadmore')
    },
    handleShowTitleChange(event) {
      this.triggerEvent('showtitlechange', {
        value: Boolean(event.detail && event.detail.value)
      })
    },
    handleShowDescriptionChange(event) {
      this.triggerEvent('showdescriptionchange', {
        value: Boolean(event.detail && event.detail.value)
      })
    }
  }
})
