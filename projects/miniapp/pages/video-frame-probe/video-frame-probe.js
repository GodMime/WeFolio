const { writeRgbaFrameToCanvas } = require('../../utils/frame-canvas')

const CANVAS_ID = 'frameProbeCanvas'
const DEFAULT_VIDEO_DURATION_MS = 1000
const DEFAULT_CANVAS_SIZE = 1
const TARGET_FRAME_FILE_TYPE = 'jpg'
const TARGET_FRAME_QUALITY = 0.92

function formatTime(milliseconds) {
  const totalSeconds = Math.max(0, Math.round(Number(milliseconds || 0) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function normalizeDurationMs(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0
    ? Math.round(numberValue)
    : DEFAULT_VIDEO_DURATION_MS
}

function buildVideoMetaText(file = {}, durationMs = DEFAULT_VIDEO_DURATION_MS) {
  const width = Number(file.width || 0)
  const height = Number(file.height || 0)
  const size = Number(file.size || 0)
  const sizeText = size > 0 ? `${(size / 1024 / 1024).toFixed(1)}MB` : '大小未知'
  const dimensionText = width > 0 && height > 0 ? `${width} x ${height}` : '尺寸未知'
  return `${dimensionText} · ${formatTime(durationMs)} · ${sizeText}`
}

Page({
  data: {
    videoPath: '',
    coverPath: '',
    durationMs: DEFAULT_VIDEO_DURATION_MS,
    targetTimeMs: 0,
    targetTimeText: '00:00',
    videoMetaText: '未选择视频',
    canvasWidth: DEFAULT_CANVAS_SIZE,
    canvasHeight: DEFAULT_CANVAS_SIZE,
    working: false,
    logs: ['等待选择视频']
  },

  appendLog(message) {
    const time = new Date().toLocaleTimeString()
    const logs = (this.data.logs || []).concat(`${time} ${message}`)
    this.setData({
      logs: logs.slice(-20)
    })
  },

  handleChooseVideo() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['video'],
      sourceType: ['album'],
      success: (response) => {
        const file = response.tempFiles && response.tempFiles[0]
        if (!file || !file.tempFilePath) {
          this.appendLog('未拿到视频临时路径')
          return
        }
        const durationMs = normalizeDurationMs(Number(file.duration || 0) * 1000)
        this.setData({
          videoPath: file.tempFilePath,
          coverPath: '',
          durationMs,
          targetTimeMs: Math.min(1000, durationMs),
          targetTimeText: formatTime(Math.min(1000, durationMs)),
          videoMetaText: buildVideoMetaText(file, durationMs)
        })
        this.appendLog(`已选择视频: ${file.tempFilePath}`)
      },
      fail: (error) => {
        if (error && /cancel/.test(error.errMsg || '')) {
          return
        }
        this.appendLog(`选择失败: ${error && error.errMsg ? error.errMsg : '未知错误'}`)
      }
    })
  },

  handleVideoMetadata(event) {
    const detail = event.detail || {}
    if (!detail.duration) {
      return
    }
    const durationMs = normalizeDurationMs(detail.duration * 1000)
    this.setData({
      durationMs,
      targetTimeMs: Math.min(this.data.targetTimeMs, durationMs),
      targetTimeText: formatTime(Math.min(this.data.targetTimeMs, durationMs))
    })
    this.appendLog(`metadata: ${detail.width || 0}x${detail.height || 0}, ${formatTime(durationMs)}`)
  },

  handleTimeChanging(event) {
    this.updateTargetTime(event.detail.value)
  },

  handleTimeChange(event) {
    this.updateTargetTime(event.detail.value)
  },

  updateTargetTime(value) {
    const targetTimeMs = Math.max(0, Math.min(this.data.durationMs, Number(value) || 0))
    this.setData({
      targetTimeMs,
      targetTimeText: formatTime(targetTimeMs)
    })
  },

  async handleExtractFrame() {
    if (!this.data.videoPath) {
      this.appendLog('请先选择视频')
      return
    }
    if (!wx.createVideoDecoder) {
      this.appendLog('当前基础库不支持 wx.createVideoDecoder')
      return
    }
    this.setData({
      working: true,
      coverPath: ''
    })
    let decoder = null
    try {
      decoder = wx.createVideoDecoder()
      this.appendLog('创建 VideoDecoder 成功')
      await decoder.start({
        source: this.data.videoPath,
        abortAudio: true
      })
      this.appendLog(`开始解码，seek 到 ${this.data.targetTimeMs}ms`)
      await decoder.seek(this.data.targetTimeMs)
      const frame = await this.readFrame(decoder)
      const coverPath = await this.writeFrameToCanvas(frame)
      this.setData({ coverPath })
      this.appendLog(`导出成功: ${coverPath}`)
    } catch (error) {
      this.appendLog(`导出失败: ${error && error.message ? error.message : error}`)
      wx.showToast({
        title: '导出失败，看日志',
        icon: 'none'
      })
    } finally {
      if (decoder && decoder.stop) {
        decoder.stop()
      }
      if (decoder && decoder.remove) {
        decoder.remove()
      }
      this.setData({ working: false })
    }
  },

  async readFrame(decoder) {
    for (let index = 0; index < 12; index++) {
      const frame = decoder.getFrameData()
      if (frame && frame.data && frame.width && frame.height) {
        this.appendLog(`拿到帧: ${frame.width}x${frame.height}, bytes=${frame.data.byteLength}`)
        return frame
      }
      await new Promise((resolve) => setTimeout(resolve, 80))
    }
    throw new Error('多次读取后仍未拿到帧数据')
  },

  async writeFrameToCanvas(frame) {
    return writeRgbaFrameToCanvas({
      page: this,
      canvasId: CANVAS_ID,
      frame,
      canvasWidthDataKey: 'canvasWidth',
      canvasHeightDataKey: 'canvasHeight',
      fileType: TARGET_FRAME_FILE_TYPE,
      quality: TARGET_FRAME_QUALITY,
      frameFormatErrorMessage(actualBytes, expectedBytes) {
        return `帧数据不是 RGBA: ${actualBytes}/${expectedBytes}`
      }
    })
  }
})
