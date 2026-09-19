const ACTION_INTERNAL = 'INTERNAL_PORTFOLIO'
const ACTION_EXTERNAL = 'EXTERNAL_LINK'
const ICON_POSITIONS = ['OVERLAY', 'OVERLAY_BOTTOM_CENTER', 'OVERLAY_CENTER', 'BELOW']
const MOCK_TARGET_ID = 9002
const MOCK_TARGET_TITLE = '演示联系作品集'
const MOCK_PREVIEW_ROUTE = '/pages/mock/portfolio-standard-preview/portfolio-standard-preview'
const TARGET_UNAVAILABLE_MESSAGE = '演示作品集暂不可用'
const COPY_FAILED_MESSAGE = '复制失败，请重试'
const CONTACT_DURATION_MS = 2000
const HYPERLINK_DELAY_MS = 1700
const HYPERLINK_DURATION_MS = 2500
const COLOR_PATTERN = /^(AUTO|#[\da-f]{6})$/i
const CONTROL_PATTERN = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/
const CONTACT_NUMBERS = {
  contactBorderWidthRpx: { min: 1, max: 12, fallback: 1 },
  horizontalMarginRpx: { min: 0, max: 96, fallback: 0 },
  verticalMarginRpx: { min: 0, max: 96, fallback: 0 }
}
const MOCK_CONTACT_PROFILE = Object.freeze({ contactPhone: '示例电话', contactWechat: 'demo_wefolio' })

/** 保留原始文字供确认时校验，不通过截断或去除控制字符掩盖非法输入。 */
function normalizeMockContactInfoConfig(raw = {}) {
  const config = {
    contactPhone: typeof raw.contactPhone === 'string' ? raw.contactPhone : '',
    contactWechat: typeof raw.contactWechat === 'string' ? raw.contactWechat : '',
    contactBorder: raw.contactBorder === true,
    contactBorderColor: typeof raw.contactBorderColor === 'string' ? raw.contactBorderColor.toUpperCase() : 'AUTO'
  }
  Object.keys(CONTACT_NUMBERS).forEach(field => {
    config[field] = raw[field] === undefined ? CONTACT_NUMBERS[field].fallback : raw[field]
  })
  return config
}

function createMockContactInfoConfig() {
  return normalizeMockContactInfoConfig(MOCK_CONTACT_PROFILE)
}

function invalid(message) { return { valid: false, message } }

/** 联系资料只使用本地快照，边框关闭也校验并保留外观偏好。 */
function validateMockContactInfoConfig(raw = {}) {
  const config = normalizeMockContactInfoConfig(raw)
  if (CONTROL_PATTERN.test(config.contactPhone) || CONTROL_PATTERN.test(config.contactWechat)) return invalid('联系信息不能包含换行或控制字符')
  if (config.contactPhone.length > 32) return invalid('电话最多 32 字')
  if (config.contactWechat.length > 64) return invalid('微信最多 64 字')
  if (!config.contactPhone.trim() && !config.contactWechat.trim()) return invalid('请至少填写一项联系信息')
  if (!COLOR_PATTERN.test(config.contactBorderColor)) return invalid('边框颜色需为 AUTO 或六位 HEX 颜色')
  for (const field of Object.keys(CONTACT_NUMBERS)) {
    const bounds = CONTACT_NUMBERS[field]
    if (!Number.isInteger(config[field]) || config[field] < bounds.min || config[field] > bounds.max) return invalid('边框宽度或留白超出范围')
  }
  return { valid: true, message: '' }
}

/** 动作配置采用白名单，切换时不残留另一类目标。 */
function normalizeMockHyperlinkConfig(raw = {}) {
  const config = {
    workId: raw.workId === undefined ? 109 : raw.workId,
    actionType: raw.actionType === undefined ? ACTION_INTERNAL : raw.actionType,
    showClickIcon: raw.showClickIcon !== false,
    iconPosition: raw.iconPosition === undefined ? ICON_POSITIONS[0] : raw.iconPosition
  }
  if (config.actionType === ACTION_INTERNAL) config.targetPortfolioId = raw.targetPortfolioId === undefined ? MOCK_TARGET_ID : raw.targetPortfolioId
  if (config.actionType === ACTION_EXTERNAL) {
    config.externalContent = typeof raw.externalContent === 'string' ? raw.externalContent : ''
    config.promptText = typeof raw.promptText === 'string' ? raw.promptText : ''
  }
  return config
}

function createMockHyperlinkConfig() { return normalizeMockHyperlinkConfig() }

function validateMockHyperlinkConfig(raw = {}, works = []) {
  const config = normalizeMockHyperlinkConfig(raw)
  const work = works.find(item => Number(item.id || item.workId) === Number(config.workId))
  if (!work || !['IMAGE', 'ANIMATION'].includes(work.mediaType)) return invalid('请选择图片或动图作品')
  if (!ICON_POSITIONS.includes(config.iconPosition)) return invalid('请选择有效的图标位置')
  if (config.actionType === ACTION_INTERNAL) return resolveMockHyperlinkTarget(config.targetPortfolioId) ? { valid: true, message: '' } : invalid(TARGET_UNAVAILABLE_MESSAGE)
  if (config.actionType !== ACTION_EXTERNAL) return invalid('请选择超链接动作')
  if (!config.externalContent.trim() || config.externalContent.length > 2048) return invalid('外部内容需为 1–2048 字')
  if (!config.promptText.trim() || config.promptText.length > 30) return invalid('提示语需为 1–30 字')
  return { valid: true, message: '' }
}

/** 每次创建独立只读演示快照；此入口不读取、不写入编辑草稿。 */
function resolveMockHyperlinkTarget(targetId) {
  if (String(targetId) !== String(MOCK_TARGET_ID)) return null
  return {
    id: MOCK_TARGET_ID, title: MOCK_TARGET_TITLE, readOnly: true,
    config: {
      schemaVersion: 'standard-personal-v1', editorSchemaRevision: 3,
      share: { title: MOCK_TARGET_TITLE },
      style: { backgroundColor: '#FFFFFF', componentSpacingRpx: 32 },
      bottomNav: { enabled: false, items: [] },
      backgroundAudio: { enabled: false, workId: null, displayStyle: 'DISC' },
      components: [
        { componentKey: 'mock_target_text', componentType: 'TEXT_SECTION', name: '演示说明', enabled: true, sortOrder: 1000, config: { title: MOCK_TARGET_TITLE, content: '这是本地演示作品集。返回后可继续体验原作品集，以下联系信息仅作演示。', fontFamily: 'SYSTEM', fontSize: 28, alignment: 'LEFT' } },
        { componentKey: 'mock_target_contact', componentType: 'CONTACT_INFO', name: '演示联系信息', enabled: true, sortOrder: 2000, config: createMockContactInfoConfig() }
      ]
    }
  }
}

function getMockHyperlinkTargetUrl(targetId) {
  return resolveMockHyperlinkTarget(targetId) ? `${MOCK_PREVIEW_ROUTE}?demoTarget=${MOCK_TARGET_ID}` : ''
}

/** 仅在平台成功回调后展示结果；注入计时器可验证后台暂停及卸载行为。 */
function createMockCopyController(options = {}) {
  const wxApi = options.wxApi || {}
  const timers = options.timers || { setTimeout, clearTimeout }
  const now = options.now || Date.now
  const onContactState = options.onContactState || (() => {})
  const onPrompt = options.onPrompt || (() => {})
  const onError = options.onError || (() => {})
  let disposed = false
  let hidden = false
  let generation = 0
  let copiedField = ''
  let prompt = ''
  let job = null

  function clearJob() {
    if (job && job.id !== null) timers.clearTimeout(job.id)
    job = null
  }

  function arm() {
    if (!job || hidden || disposed) return
    job.started = now()
    job.id = timers.setTimeout(() => {
      const callback = job && job.callback
      job = null
      if (!disposed && !hidden && callback) callback()
    }, job.remaining)
  }

  function schedule(callback, delay) {
    clearJob()
    job = { callback, remaining: delay, started: now(), id: null }
    arm()
  }

  function resetFeedback() {
    clearJob()
    if (copiedField) { copiedField = ''; onContactState('') }
    if (prompt) { prompt = ''; onPrompt('') }
  }

  function copy(content, success) {
    if (disposed || hidden || !content) return
    resetFeedback()
    const current = ++generation
    const fail = () => { if (!disposed && current === generation && !hidden) onError(COPY_FAILED_MESSAGE) }
    try {
      wxApi.setClipboardData({ data: content, success: () => { if (!disposed && current === generation) success() }, fail })
    } catch (error) { fail() }
  }

  return {
    copyContact(field, value) {
      if (!['contactPhone', 'contactWechat'].includes(field) || typeof value !== 'string') return
      copy(value, () => {
        copiedField = field
        if (!hidden) onContactState(field)
        schedule(() => { copiedField = ''; onContactState('') }, CONTACT_DURATION_MS)
      })
    },
    copyHyperlink(config = {}) {
      if (config.actionType !== ACTION_EXTERNAL || typeof config.externalContent !== 'string') return
      copy(config.externalContent, () => schedule(() => {
        prompt = config.promptText || ''
        onPrompt(prompt)
        schedule(() => { prompt = ''; onPrompt('') }, HYPERLINK_DURATION_MS)
      }, HYPERLINK_DELAY_MS))
    },
    hide() {
      if (disposed || hidden) return
      hidden = true
      if (job && job.id !== null) {
        timers.clearTimeout(job.id)
        job.remaining = Math.max(0, job.remaining - (now() - job.started))
        job.id = null
      }
      if (copiedField) onContactState('')
      if (prompt) onPrompt('')
    },
    show() {
      if (disposed || !hidden) return
      hidden = false
      if (copiedField) onContactState(copiedField)
      if (prompt) onPrompt(prompt)
      arm()
    },
    destroy() {
      if (disposed) return
      disposed = true
      generation += 1
      resetFeedback()
    }
  }
}

module.exports = {
  MOCK_CONTACT_PROFILE, MOCK_TARGET_ID, MOCK_TARGET_TITLE, TARGET_UNAVAILABLE_MESSAGE,
  createMockContactInfoConfig, normalizeMockContactInfoConfig, validateMockContactInfoConfig,
  createMockHyperlinkConfig, normalizeMockHyperlinkConfig, validateMockHyperlinkConfig,
  resolveMockHyperlinkTarget, getMockHyperlinkTargetUrl, createMockCopyController
}
