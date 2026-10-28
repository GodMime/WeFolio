const { referenceBasis, hasReferenceState, referencePayload } = require('./advanced-reference-attachments')
const ADVANCED_API_PREFIX = '/api/mine/advanced-portfolios'
const AI_API_PREFIX = '/api/mine/portfolio-ai'
const ADVANCED_TEMPLATE = 'ADVANCED'
const CLIENT_ADVANCED_VERSION = 2
const AI_CONTRACT_VERSION = 'ai-portfolio-v2'
const SOURCE_MODES = Object.freeze({ SELECTED: 'SELECTED_SOURCES', TEXT_ONLY: 'TEXT_ONLY' })
const CLIENT_UPGRADE_MESSAGE = '当前客户端不支持此候选，请升级小程序'
function effectiveSourceMode(current = {}) { return current.sourceMode === SOURCE_MODES.TEXT_ONLY && current.mode === 'CREATE' ? SOURCE_MODES.TEXT_ONLY : SOURCE_MODES.SELECTED }
function supportedProtocol(capabilities = {}) { return (capabilities.supportedProtocols || []).find(item => item.aiContractVersion === AI_CONTRACT_VERSION) || null }
function candidateVersionError(response = {}) {
  const version = response.taskAdvancedVersion === undefined ? 1 : response.taskAdvancedVersion
  return !Number.isInteger(version) || version < 1 || version > CLIENT_ADVANCED_VERSION ? CLIENT_UPGRADE_MESSAGE : ''
}
// 与普通个人、团队编辑器版本同步；分包保持本地声明，由跨编辑器契约测试核对。
const PERSONAL_EDITOR_SCHEMA_REVISION = 15
const TEAM_EDITOR_SCHEMA_REVISION = 11
// 与 Runtime/Job 的 PortfolioAiTaskMessage.TASK_FAILED_MESSAGE 同源，由跨端测试锁定文案。
const TASK_FAILED_MESSAGE = '未能生成，现有内容已保留'
const PROMPT_MAX_LENGTH = 2000
const ROOT_MENU_KEY = '__root__'
const ACTIVE_STATUSES = ['PENDING', 'RUNNING']
const AI_TASK_MODES = ['CREATE', 'MODIFY_PAGE']
const clone = value => JSON.parse(JSON.stringify(value))
const text = value => String(value || '').trim()

/** 新来源编号只前进，恢复同一来源的旧号不会把描述指向其他内容。 */
function normalizeContext(raw = {}) {
  const sources = Array.isArray(raw && raw.sources) ? raw.sources.map(source => ({
    sourceNo: Number(source.sourceNo), kind: text(source.kind), sourceId: text(source.sourceId)
  })).filter(source => Number.isInteger(source.sourceNo) && source.sourceNo > 0 && ['WORK', 'MEMBER_PORTFOLIO'].includes(source.kind) && source.sourceId) : []
  const nextSourceNo = Number(raw && raw.nextSourceNo) || 1
  return { version: 1, nextSourceNo: Math.max(nextSourceNo, 1 + Math.max(0, ...sources.map(source => source.sourceNo))), sources }
}

/** 会话记录的旧号仅在未被占用且小于下一新号时恢复。 */
function addSource(raw, source, previousSourceNo = 0) {
  const context = normalizeContext(raw)
  if (context.sources.some(item => item.kind === source.kind && item.sourceId === text(source.sourceId))) return context
  const previousNo = Number(previousSourceNo)
  const canRestore = Number.isInteger(previousNo) && previousNo > 0 && previousNo < context.nextSourceNo
    && !context.sources.some(item => item.sourceNo === previousNo)
  context.sources.push({ sourceNo: canRestore ? previousNo : context.nextSourceNo, kind: source.kind, sourceId: text(source.sourceId) })
  if (!canRestore) context.nextSourceNo += 1
  return context
}

function removeSource(raw, sourceNo) {
  const context = normalizeContext(raw)
  context.sources = context.sources.filter(source => source.sourceNo !== Number(sourceNo))
  return context
}

function validatePrompt(prompt, raw, unavailable = []) {
  const value = text(prompt)
  if (!value) return '请描述希望调整的内容'
  if (Array.from(value).length > PROMPT_MAX_LENGTH) return '描述不能超过 2,000 个字符'
  // 日期中的“20号”不是素材引用；日期事实先排除，A 编号仍由附件校验处理。
  const numberedText = value.replace(/A\d+\s*号?/g, '').replace(/\d{1,4}年(?:\d{1,2}月)?(?:\d{1,2}[日号])?|\d{1,2}月\d{1,2}[日号]/g, '')
  const numbers = Array.from(numberedText.matchAll(/(\d+)\s*号/g), match => Number(match[1]))
  const context = normalizeContext(raw)
  const invalid = numbers.find(number => !context.sources.some(source => source.sourceNo === number) || unavailable.includes(number))
  return invalid === undefined ? '' : `${invalid} 号已移除或不可用，请调整描述`
}

function captureBasis(current = {}) {
  return JSON.stringify([current.ownerType, text(current.ownerId), text(current.portfolioId), Number(current.draftRevision) || 0,
    Number(current.localEditRevision) || 0, CLIENT_ADVANCED_VERSION, AI_CONTRACT_VERSION, effectiveSourceMode(current), current.config || null, normalizeContext(current.aiContext), ...(hasReferenceState(current) ? [referenceBasis(current)] : [])])
}

/** 优先使用服务端具体原因，同时兼容旧任务的顶层错误字段与候选提示。 */
function taskErrorMessage(task = {}) {
  if (task.status === 'FAILED') return text(task.error && task.error.message) || text(task.errorMessage) || TASK_FAILED_MESSAGE
  const result = task.result || {}
  return result.available === false ? text(result.error && result.error.message) || text(result.unavailableReason) || '候选不可用，请重新生成' : ''
}

/** 采用前重新查询，同步校验当前内存，禁止首次保存后复用无 ID 候选。 */
function candidateError(response = {}, basis, current) {
  const versionError = candidateVersionError(response)
  if (versionError) return versionError
  if (captureBasis(current) !== basis) return '当前编辑基础已变化，请基于最新内容重新生成'
  if (Number(response.baseDraftRevision) !== (Number(current.draftRevision) || 0) || Number(response.localEditRevision) !== (Number(current.localEditRevision) || 0)) return '当前编辑基础已变化，请重新生成'
  const result = response.result || {}
  if (response.status !== 'SUCCEEDED' || result.kind !== 'CANDIDATE' || result.available !== true || !result.candidate) return taskErrorMessage(response) || '候选已不可用，请重新生成'
  if (result.hasChanges !== true) return '当前内容已满足要求'
  return ''
}

function buildTaskPayload(current, options, idempotencyKey) {
  const mode = options.mode || (current.config ? 'MODIFY_PAGE' : 'CREATE')
  if (!AI_TASK_MODES.includes(mode)) throw new Error('AI 操作参数无效')
  return {
    ownerType: current.ownerType, ownerId: text(current.ownerId), portfolioId: current.portfolioId || null, mode,
    clientAdvancedVersion: CLIENT_ADVANCED_VERSION, sourceMode: effectiveSourceMode({ ...current, ...options, mode }),
    targetMenuKey: mode === 'CREATE' ? null : (options.targetMenuKey || ROOT_MENU_KEY),
    baseDraftRevision: Number(current.draftRevision) || 0, baseConfig: mode === 'CREATE' ? null : clone(current.config),
    localEditRevision: Number(current.localEditRevision) || 0,
    editorSchemaRevision: current.ownerType === 'TEAM' ? TEAM_EDITOR_SCHEMA_REVISION : PERSONAL_EDITOR_SCHEMA_REVISION, aiContractVersion: AI_CONTRACT_VERSION,
    clientCapabilities: { portfolioRemoteFont: 1 }, aiContext: normalizeContext(current.aiContext), prompt: text(options.prompt),
    ...((current.aiReferenceAttachments || []).length ? { referenceAttachments: referencePayload(current.aiReferenceAttachments) } : {}),
    ...(options.clarificationAnswer ? { clarificationAnswer: clone(options.clarificationAnswer) } : {}), idempotencyKey
  }
}

function makeKey(prefix = 'ai') { return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}` }
function isAdvanced(item) { return !!item && item.templateType === ADVANCED_TEMPLATE }
function advancedQuery(item) { return isAdvanced(item) ? '&templateType=ADVANCED' : '' }
function advancedEndpoint(id, suffix = '') { return `${ADVANCED_API_PREFIX}/${encodeURIComponent(text(id))}${suffix}` }
function statusMessage(task = {}) {
  if (task.attemptCount > 1 && ACTIVE_STATUSES.includes(task.status)) return `正在重试（第${task.attemptCount}/3次）`
  return ({ PENDING: '等待生成，可取消', RUNNING: '正在生成，可取消', CANCELLED: '已取消，现有内容已保留', FAILED: TASK_FAILED_MESSAGE })[task.status] || ''
}

module.exports = { CLIENT_ADVANCED_VERSION, SOURCE_MODES, CLIENT_UPGRADE_MESSAGE, effectiveSourceMode, supportedProtocol, candidateVersionError, ADVANCED_API_PREFIX, ADVANCED_TEMPLATE, AI_API_PREFIX, AI_CONTRACT_VERSION, TASK_FAILED_MESSAGE, PROMPT_MAX_LENGTH, ROOT_MENU_KEY,
  ACTIVE_STATUSES, AI_TASK_MODES, clone, text, normalizeContext, addSource, removeSource, validatePrompt, captureBasis, candidateError, taskErrorMessage,
  buildTaskPayload, makeKey, isAdvanced, advancedQuery, advancedEndpoint, statusMessage }
