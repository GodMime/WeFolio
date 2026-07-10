const DEFAULT_DECODER_MODE = 0
const DEFAULT_DECODER_EVENT_TIMEOUT_MS = 1000
const DEFAULT_FRAME_READ_ATTEMPTS = 24
const DEFAULT_FRAME_READ_DELAY_MS = 100
const FRAME_END_GUARD_MS = 100
const FRAME_SEEK_FALLBACK_OFFSETS_MS = [0, -500, 500, -1000, 1000, -2000, 2000]
const VIDEO_FRAME_DEBUG_VERSION = 'VF-20260630-4'
const VIDEO_DECODER_UNSUPPORTED_MESSAGE = '当前基础库不支持视频帧导出'
const VIDEO_DECODER_SOURCE_MISSING_MESSAGE = '视频文件缺失，请刷新后重试'
const VIDEO_DECODER_START_FAILED_MESSAGE = withVideoFrameDebugVersion('视频解码启动失败，请换一个视频或保留默认封面')
const VIDEO_DECODER_SEEK_FAILED_MESSAGE = withVideoFrameDebugVersion('视频跳帧失败，请换一个时间点重试')
const VIDEO_DECODER_FRAME_FAILED_MESSAGE = withVideoFrameDebugVersion('未能读取当前帧，请换一个时间点重试')

function withVideoFrameDebugVersion(message) {
  if (!message || message.includes(VIDEO_FRAME_DEBUG_VERSION)) {
    return message
  }
  return `${message}（${VIDEO_FRAME_DEBUG_VERSION}）`
}

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function clampNumber(value, min, max) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return min
  }
  return Math.max(min, Math.min(max, numberValue))
}

function normalizeVideoFrameSeekTime(timeMs, durationMs) {
  const safeDurationMs = Math.max(0, Number(durationMs || 0))
  if (!safeDurationMs) {
    return Math.max(0, Number(timeMs || 0))
  }
  const maxSeekMs = safeDurationMs > FRAME_END_GUARD_MS ? safeDurationMs - FRAME_END_GUARD_MS : 0
  return Math.round(clampNumber(timeMs, 0, maxSeekMs))
}

function hasPromise(value) {
  return value && typeof value.then === 'function'
}

function resolveOptionNumber(options, field, defaultValue) {
  if (!Object.prototype.hasOwnProperty.call(options || {}, field)) {
    return defaultValue
  }
  const value = options[field]
  return value === undefined || value === null ? defaultValue : value
}

function isTransientFrameReadError(error) {
  const message = error && error.message ? error.message : String(error || '')
  return /playerStart|start|buffer|frame/i.test(message)
}

function describeError(error) {
  if (!error) {
    return 'unknown'
  }
  const message = error.rawMessage || error.errMsg || error.message || String(error)
  return String(message).replace(/\s+/g, ' ').slice(0, 120)
}

function rejectWithMessage(reject, error, fallbackMessage) {
  const rawMessage = describeError(error)
  if (rawMessage && rawMessage !== 'unknown' && !/^player/i.test(rawMessage)) {
    reject(error)
    return
  }
  const fallbackError = new Error(fallbackMessage)
  fallbackError.rawMessage = rawMessage
  reject(fallbackError)
}

function waitDecoderEvent(decoder, eventName, trigger, options = {}) {
  const timeoutMs = resolveOptionNumber(options, 'timeoutMs', DEFAULT_DECODER_EVENT_TIMEOUT_MS)
  const timeoutMessage = options.timeoutMessage || VIDEO_DECODER_START_FAILED_MESSAGE
  const actionErrorMessage = options.actionErrorMessage || timeoutMessage
  const softTimeout = options.softTimeout !== false
  return new Promise((resolve, reject) => {
    let settled = false
    let timer = null
    const eventSupported = decoder && typeof decoder.on === 'function'
    const cleanup = () => {
      if (timer) {
        clearTimeout(timer)
      }
      if (eventSupported && typeof decoder.off === 'function') {
        decoder.off(eventName, onEvent)
      }
    }
    const settleResolve = (value) => {
      if (settled) {
        return
      }
      settled = true
      cleanup()
      resolve(value)
    }
    const settleReject = (error, fallbackMessage) => {
      if (settled) {
        return
      }
      settled = true
      cleanup()
      rejectWithMessage(reject, error, fallbackMessage)
    }
    const onEvent = (detail) => {
      settleResolve(detail)
    }

    if (eventSupported) {
      decoder.on(eventName, onEvent)
    }
    timer = setTimeout(() => {
      if (softTimeout) {
        settleResolve()
        return
      }
      settleReject(new Error(timeoutMessage), timeoutMessage)
    }, timeoutMs)

    let actionResult
    try {
      actionResult = trigger()
    } catch (error) {
      settleReject(error, actionErrorMessage)
      return
    }
    if (hasPromise(actionResult)) {
      actionResult.then((value) => {
        settleResolve(value)
      }, (error) => {
        settleReject(error, actionErrorMessage)
      })
      return
    }
    if (!eventSupported) {
      settleResolve()
    }
  })
}

async function readDecodedFrame(decoder, options = {}) {
  const attempts = resolveOptionNumber(options, 'attempts', DEFAULT_FRAME_READ_ATTEMPTS)
  const delayMs = resolveOptionNumber(options, 'delayMs', DEFAULT_FRAME_READ_DELAY_MS)
  let lastError = null
  for (let index = 0; index < attempts; index++) {
    try {
      const frame = decoder.getFrameData()
      if (frame && frame.data && frame.width && frame.height) {
        return frame
      }
    } catch (error) {
      lastError = error
      if (!isTransientFrameReadError(error)) {
        throw error
      }
    }
    await delay(delayMs)
  }
  if (lastError && !isTransientFrameReadError(lastError)) {
    throw lastError
  }
  throw new Error(VIDEO_DECODER_FRAME_FAILED_MESSAGE)
}

function buildSeekCandidates(timeMs, durationMs) {
  const result = []
  const selectedTimeMs = normalizeVideoFrameSeekTime(timeMs, durationMs)
  FRAME_SEEK_FALLBACK_OFFSETS_MS.forEach((offset) => {
    const candidate = normalizeVideoFrameSeekTime(selectedTimeMs + offset, durationMs)
    if (!result.includes(candidate)) {
      result.push(candidate)
    }
  })
  if (!result.includes(0)) {
    result.push(0)
  }
  return result
}

function buildDecoderStartOptions(source) {
  return [
    {
      label: 'default',
      options: {
        source,
        abortAudio: true,
        mode: DEFAULT_DECODER_MODE
      }
    },
    {
      label: 'plain',
      options: {
        source
      }
    }
  ]
}

function disposeDecoder(decoder) {
  if (decoder && decoder.stop) {
    decoder.stop()
  }
  if (decoder && decoder.remove) {
    decoder.remove()
  }
}

async function createStartedDecoder(runtimeWx, options = {}) {
  const startOptions = buildDecoderStartOptions(options.source)
  let lastError = null
  const attemptMessages = []
  for (let index = 0; index < startOptions.length; index++) {
    const startOption = startOptions[index]
    const decoder = runtimeWx.createVideoDecoder()
    try {
      await waitDecoderEvent(decoder, 'start', () => decoder.start(startOption.options), {
        timeoutMs: options.eventTimeoutMs,
        timeoutMessage: VIDEO_DECODER_START_FAILED_MESSAGE,
        actionErrorMessage: VIDEO_DECODER_START_FAILED_MESSAGE
      })
      return decoder
    } catch (error) {
      lastError = error
      attemptMessages.push(`${startOption.label}=${describeError(error)}`)
      disposeDecoder(decoder)
    }
  }
  const detailText = attemptMessages.length ? `；start ${attemptMessages.join('；')}` : ''
  throw new Error(`${VIDEO_DECODER_START_FAILED_MESSAGE}${detailText}`)
}

async function decodeVideoFrameAtTime(options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  if (!runtimeWx.createVideoDecoder) {
    throw new Error(VIDEO_DECODER_UNSUPPORTED_MESSAGE)
  }
  if (!options.source) {
    throw new Error(VIDEO_DECODER_SOURCE_MISSING_MESSAGE)
  }
  let decoder = null
  try {
    decoder = await createStartedDecoder(runtimeWx, {
      source: options.source,
      eventTimeoutMs: options.eventTimeoutMs
    })
    const seekCandidates = buildSeekCandidates(options.timeMs, options.durationMs)
    let lastError = null
    for (let index = 0; index < seekCandidates.length; index++) {
      const seekTimeMs = seekCandidates[index]
      try {
        await waitDecoderEvent(decoder, 'seek', () => decoder.seek(seekTimeMs), {
          timeoutMs: options.eventTimeoutMs,
          timeoutMessage: VIDEO_DECODER_SEEK_FAILED_MESSAGE,
          actionErrorMessage: VIDEO_DECODER_SEEK_FAILED_MESSAGE
        })
        const frame = await readDecodedFrame(decoder, {
          attempts: options.readAttempts,
          delayMs: options.readDelayMs
        })
        return {
          frame,
          seekTimeMs
        }
      } catch (error) {
        lastError = error
      }
    }
    throw lastError || new Error(VIDEO_DECODER_FRAME_FAILED_MESSAGE)
  } finally {
    disposeDecoder(decoder)
  }
}

module.exports = {
  VIDEO_DECODER_FRAME_FAILED_MESSAGE,
  VIDEO_DECODER_UNSUPPORTED_MESSAGE,
  decodeVideoFrameAtTime,
  normalizeVideoFrameSeekTime,
  withVideoFrameDebugVersion
}
