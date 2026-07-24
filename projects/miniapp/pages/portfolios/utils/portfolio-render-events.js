function readPortfolioRenderEventData(event = {}) {
  const dataset = event.currentTarget && event.currentTarget.dataset
  const detail = event.detail
  return Object.assign(
    {},
    dataset && typeof dataset === 'object' ? dataset : {},
    detail && typeof detail === 'object' ? detail : {}
  )
}

function normalizeWorkTapDataset(dataset = {}) {
  const previewUrl = dataset.mediaUrl || dataset.previewUrl || dataset.coverUrl || ''
  return {
    workId: Number(dataset.workId),
    mediaType: dataset.mediaType || '',
    previewUrl,
    coverUrl: dataset.coverUrl || '',
    title: dataset.title || ''
  }
}

function normalizeSingleWorkTapDataset(dataset = {}) {
  return Object.assign({}, normalizeWorkTapDataset(dataset), {
    previewUrl: dataset.mediaUrl || ''
  })
}

module.exports = {
  normalizeSingleWorkTapDataset,
  normalizeWorkTapDataset,
  readPortfolioRenderEventData
}
