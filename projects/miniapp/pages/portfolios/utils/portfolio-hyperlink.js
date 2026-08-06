const DEFAULT_REVEAL_DELAY_MS = 1700
const DEFAULT_VISIBLE_DURATION_MS = 2500

/**
 * 创建外部内容复制提示控制器。小程序进入后台时可暂停计时，回到前台后继续。
 *
 * @param {object} options 计时器和状态回调
 * @returns {object} 提示控制器
 */
function createClipboardPromptController(options = {}) {
  const now = typeof options.now === 'function' ? options.now : Date.now
  const scheduleTimeout = typeof options.setTimeout === 'function' ? options.setTimeout : setTimeout
  const cancelTimeout = typeof options.clearTimeout === 'function' ? options.clearTimeout : clearTimeout
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {}
  const revealDelayMs = Math.max(0, Number(options.revealDelayMs) || DEFAULT_REVEAL_DELAY_MS)
  const visibleDurationMs = Math.max(0, Number(options.visibleDurationMs) || DEFAULT_VISIBLE_DURATION_MS)

  let phase = 'idle'
  let text = ''
  let timerId = null
  let deadline = 0
  let remainingMs = 0
  let paused = false

  function clearTimer() {
    if (timerId !== null) {
      cancelTimeout(timerId)
      timerId = null
    }
  }

  function emit(visible, promptText) {
    onChange({ visible, text: promptText })
  }

  function schedulePhase() {
    if (paused || phase === 'idle') {
      return
    }
    clearTimer()
    deadline = now() + remainingMs
    timerId = scheduleTimeout(() => {
      timerId = null
      if (phase === 'waiting') {
        phase = 'visible'
        remainingMs = visibleDurationMs
        emit(true, text)
        schedulePhase()
        return
      }
      phase = 'idle'
      text = ''
      remainingMs = 0
      emit(false, '')
    }, remainingMs)
  }

  return {
    copied(promptText) {
      clearTimer()
      phase = 'waiting'
      text = String(promptText || '')
      remainingMs = revealDelayMs
      emit(false, '')
      schedulePhase()
    },

    pause() {
      if (paused) {
        return
      }
      paused = true
      if (phase !== 'idle' && timerId !== null) {
        remainingMs = Math.max(0, deadline - now())
        clearTimer()
      }
    },

    resume() {
      if (!paused) {
        return
      }
      paused = false
      schedulePhase()
    },

    dispose() {
      clearTimer()
      phase = 'idle'
      text = ''
      remainingMs = 0
      paused = false
      emit(false, '')
    }
  }
}

module.exports = {
  createClipboardPromptController
}
