function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function resolveFrameData(data) {
  if (!data || typeof data.byteLength !== 'number') {
    return null
  }
  return data instanceof Uint8ClampedArray ? data : new Uint8ClampedArray(data)
}

function buildFrameFormatErrorMessage(option, actualBytes, expectedBytes) {
  if (typeof option === 'function') {
    return option(actualBytes, expectedBytes)
  }
  return option || '当前机型返回的帧格式暂不支持'
}

function setDataAsync(page, patch) {
  return new Promise((resolve) => {
    page.setData(patch, resolve)
  })
}

function getCanvasNode(page, canvasId, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.createSelectorQuery) {
    return Promise.reject(new Error('当前微信版本不支持 Canvas 2D'))
  }
  return new Promise((resolve, reject) => {
    const query = runtimeWx.createSelectorQuery()
    const scopedQuery = query.in ? query.in(page) : query
    scopedQuery
      .select(`#${canvasId}`)
      .fields({ node: true, size: true })
      .exec((results = []) => {
        const canvas = results[0] && results[0].node
        if (!canvas) {
          reject(new Error('未找到封面导出画布'))
          return
        }
        resolve(canvas)
      })
  })
}

function createImageData(canvas, context, width, height) {
  if (context && context.createImageData) {
    return context.createImageData(width, height)
  }
  if (canvas && canvas.createImageData) {
    return canvas.createImageData(width, height)
  }
  throw new Error('当前微信版本不支持 Canvas ImageData')
}

function canvasToTempFilePath(wxApi, canvas, frame, options = {}) {
  const runtimeWx = getRuntimeWx(wxApi)
  return new Promise((resolve, reject) => {
    runtimeWx.canvasToTempFilePath({
      canvas,
      x: 0,
      y: 0,
      width: frame.width,
      height: frame.height,
      destWidth: frame.width,
      destHeight: frame.height,
      fileType: options.fileType,
      quality: options.quality,
      success(response) {
        resolve(response.tempFilePath)
      },
      fail: reject
    })
  })
}

async function writeRgbaFrameToCanvas(options = {}) {
  const {
    page,
    wxApi,
    canvasId,
    frame = {},
    canvasWidthDataKey,
    canvasHeightDataKey,
    fileType,
    quality,
    frameFormatErrorMessage
  } = options
  const width = Number(frame.width || 0)
  const height = Number(frame.height || 0)
  const expectedBytes = width * height * 4
  const frameBytes = resolveFrameData(frame.data)
  if (!frameBytes || frameBytes.byteLength !== expectedBytes) {
    const actualBytes = frame.data && typeof frame.data.byteLength === 'number' ? frame.data.byteLength : 0
    throw new Error(buildFrameFormatErrorMessage(frameFormatErrorMessage, actualBytes, expectedBytes))
  }
  await setDataAsync(page, {
    [canvasWidthDataKey]: width,
    [canvasHeightDataKey]: height
  })
  const canvas = await getCanvasNode(page, canvasId, wxApi)
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  const imageData = createImageData(canvas, context, width, height)
  imageData.data.set(frameBytes)
  context.putImageData(imageData, 0, 0)
  return canvasToTempFilePath(wxApi, canvas, { width, height }, { fileType, quality })
}

module.exports = {
  writeRgbaFrameToCanvas
}
