const assert = require('node:assert/strict')
const test = require('node:test')

const feedback = require('../utils/feedback')

function exportedFunction(name) {
  assert.equal(typeof feedback[name], 'function', `${name} should be exported`)
  return feedback[name]
}

function imageFile(overrides = {}) {
  return Object.assign({
    clientId: 'feedback-file-image-1',
    tempFilePath: '/tmp/photo.jpg',
    fileName: 'photo.jpg',
    mediaType: 'IMAGE',
    mimeType: 'image/jpeg',
    fileSize: 1024,
    durationMs: 0,
    status: 'READY'
  }, overrides)
}

function videoFile(overrides = {}) {
  return Object.assign({
    clientId: 'feedback-file-video-1',
    tempFilePath: '/tmp/demo.mov',
    fileName: 'demo.mov',
    mediaType: 'VIDEO',
    mimeType: 'video/quicktime',
    fileSize: 2048,
    durationMs: 120000,
    status: 'READY'
  }, overrides)
}

function uploadTicket(file, taskId, expiresAt = '2026-08-25T12:15:00') {
  return {
    taskId,
    clientId: file.clientId,
    mediaType: file.mediaType,
    objectKey: `server-only/${taskId}`,
    uploadUrl: `https://cos.example.com/upload/${taskId}`,
    formData: {
      key: `server-only/${taskId}`,
      policy: `policy-${taskId}`
    },
    expiresAt,
    maxBytes: file.mediaType === 'VIDEO' ? 100 * 1024 * 1024 : 10 * 1024 * 1024
  }
}

function successfulWxUpload(calls = []) {
  return {
    uploadFile(options) {
      calls.push(options)
      queueMicrotask(() => options.success({ statusCode: 204 }))
      return { onProgressUpdate() {} }
    }
  }
}

test('strips feedback description and counts Unicode code points up to 200', () => {
  const validateFeedbackDescription = exportedFunction('validateFeedbackDescription')

  assert.deepEqual(validateFeedbackDescription('  网络异常  '), {
    valid: true,
    message: '',
    description: '网络异常',
    length: 4
  })
  assert.equal(validateFeedbackDescription('   ').valid, false)
  assert.equal(validateFeedbackDescription('😀'.repeat(200)).valid, true)
  assert.equal(validateFeedbackDescription('😀'.repeat(201)).valid, false)
})

test('validates three attachments and exact image/video limits and formats', () => {
  const validateFeedbackDraft = exportedFunction('validateFeedbackDraft')
  const imageMax = 10 * 1024 * 1024
  const videoMax = 100 * 1024 * 1024

  assert.equal(validateFeedbackDraft('问题', [
    imageFile({ fileName: 'a.jpeg', mimeType: 'image/jpeg', fileSize: imageMax }),
    imageFile({ clientId: 'image-png', fileName: 'b.png', mimeType: 'image/png' }),
    videoFile({ clientId: 'video-mp4', fileName: 'c.mp4', mimeType: 'video/mp4', fileSize: videoMax, durationMs: 600000 })
  ]).valid, true)

  assert.match(validateFeedbackDraft('问题', [imageFile(), imageFile(), imageFile(), imageFile()]).message, /3/)
  assert.match(validateFeedbackDraft('问题', [imageFile({ fileSize: imageMax + 1 })]).message, /10MB/)
  assert.match(validateFeedbackDraft('问题', [videoFile({ fileSize: videoMax + 1 })]).message, /100MB/)
  assert.match(validateFeedbackDraft('问题', [videoFile({ durationMs: 600001 })]).message, /10 分钟/)
  assert.equal(validateFeedbackDraft('问题', [imageFile({ fileName: 'a.gif', mimeType: 'image/gif' })]).valid, false)
  assert.equal(validateFeedbackDraft('问题', [videoFile({ fileName: 'a.mov', mimeType: 'video/mp4' })]).valid, false)
  assert.equal(validateFeedbackDraft('问题', [imageFile({ fileName: 'a.webp', mimeType: 'image/webp' })]).valid, true)
  assert.equal(validateFeedbackDraft('问题', [imageFile({ clientId: '' })]).valid, false)
  assert.equal(validateFeedbackDraft('问题', [imageFile({ fileName: '../a.jpg' })]).valid, false)
  assert.equal(validateFeedbackDraft('问题', [imageFile({ mediaType: 'AUDIO' })]).valid, false)
})

test('choosing media only creates local draft state and fresh ASCII identifiers', () => {
  const createFeedbackDraft = exportedFunction('createFeedbackDraft')
  const addChosenFeedbackMedia = exportedFunction('addChosenFeedbackMedia')
  let requestCount = 0
  let uploadCount = 0
  let sequence = 0
  const idFactory = (prefix) => `${prefix}-${++sequence}`
  const firstDraft = createFeedbackDraft({ idFactory })
  const draft = addChosenFeedbackMedia(firstDraft, [
    {
      tempFilePath: '/tmp/现场 图.png',
      name: '现场图.png',
      fileType: 'image',
      mimeType: 'image/png',
      size: 1024
    },
    {
      tempFilePath: '/tmp/demo.mp4',
      name: 'demo.mp4',
      fileType: 'video',
      mimeType: 'video/mp4',
      size: 2048,
      duration: 9.5
    }
  ], {
    idFactory,
    requestFn() { requestCount += 1 },
    wxApi: { uploadFile() { uploadCount += 1 } }
  })

  assert.equal(requestCount, 0)
  assert.equal(uploadCount, 0)
  assert.match(draft.idempotencyKey, /^[\x21-\x7e]{1,64}$/)
  assert.equal(draft.attachments.length, 2)
  assert.match(draft.attachments[0].clientId, /^[\x21-\x7e]{1,64}$/)
  assert.notEqual(draft.attachments[0].clientId, draft.attachments[1].clientId)
  assert.equal(draft.attachments[1].durationMs, 9500)

  const nextDraft = createFeedbackDraft({ idFactory })
  assert.notEqual(nextDraft.idempotencyKey, draft.idempotencyKey)
})

test('choosing media defers file validation until submit without making network calls', () => {
  const createFeedbackDraft = exportedFunction('createFeedbackDraft')
  const addChosenFeedbackMedia = exportedFunction('addChosenFeedbackMedia')
  const validateFeedbackDraft = exportedFunction('validateFeedbackDraft')
  let networkCalls = 0
  const draft = addChosenFeedbackMedia(createFeedbackDraft(), [{
    tempFilePath: '/tmp/oversized.gif',
    name: 'oversized.gif',
    fileType: 'image',
    mimeType: 'image/gif',
    size: 11 * 1024 * 1024
  }], {
    requestFn() { networkCalls += 1 },
    wxApi: { uploadFile() { networkCalls += 1 } }
  })

  assert.equal(networkCalls, 0)
  assert.equal(draft.attachments.length, 1)
  assert.equal(validateFeedbackDraft('问题', draft.attachments).valid, false)
})

test('builds upload ticket and create or append payloads matching backend DTOs', () => {
  const buildFeedbackUploadTicketPayload = exportedFunction('buildFeedbackUploadTicketPayload')
  const buildFeedbackSubmitPayload = exportedFunction('buildFeedbackSubmitPayload')
  const files = [
    imageFile({ taskId: 101 }),
    videoFile({ taskId: 102 })
  ]

  assert.deepEqual(buildFeedbackUploadTicketPayload(files), {
    files: [
      {
        clientId: 'feedback-file-image-1',
        fileName: 'photo.jpg',
        mediaType: 'IMAGE',
        mimeType: 'image/jpeg',
        fileSize: 1024,
        durationMs: 0
      },
      {
        clientId: 'feedback-file-video-1',
        fileName: 'demo.mov',
        mediaType: 'VIDEO',
        mimeType: 'video/quicktime',
        fileSize: 2048,
        durationMs: 120000
      }
    ]
  })
  assert.deepEqual(buildFeedbackSubmitPayload({
    idempotencyKey: 'feedback-submit-1',
    description: '  页面卡住  ',
    attachments: files
  }), {
    idempotencyKey: 'feedback-submit-1',
    description: '页面卡住',
    uploadTaskIds: [101, 102]
  })
})

test('feedback API helpers use the six maintainer endpoint contracts', async () => {
  const fetchFeedbackCreationState = exportedFunction('fetchFeedbackCreationState')
  const createFeedbackUploadTickets = exportedFunction('createFeedbackUploadTickets')
  const createFeedback = exportedFunction('createFeedback')
  const fetchFeedbackList = exportedFunction('fetchFeedbackList')
  const fetchFeedbackDetail = exportedFunction('fetchFeedbackDetail')
  const appendFeedbackRound = exportedFunction('appendFeedbackRound')
  const calls = []
  const requestFn = async (options) => {
    calls.push(options)
    return options
  }
  const ticketPayload = { files: [{ clientId: 'file-1' }] }
  const submitPayload = { idempotencyKey: 'submit-1', description: '问题', uploadTaskIds: [] }

  await fetchFeedbackCreationState(requestFn)
  await createFeedbackUploadTickets(requestFn, ticketPayload)
  await createFeedback(requestFn, submitPayload)
  await fetchFeedbackList(requestFn, { pageNo: 2, pageSize: 20 })
  await fetchFeedbackDetail(requestFn, 88)
  await appendFeedbackRound(requestFn, 88, submitPayload)

  assert.deepEqual(calls, [
    { url: '/api/mine/feedbacks/creation-state' },
    { url: '/api/mine/feedbacks/upload-tickets', method: 'POST', data: ticketPayload },
    { url: '/api/mine/feedbacks', method: 'POST', data: submitPayload },
    { url: '/api/mine/feedbacks', data: { pageNo: 2, pageSize: 20 } },
    { url: '/api/mine/feedbacks/88' },
    { url: '/api/mine/feedbacks/88/rounds', method: 'POST', data: submitPayload }
  ])
})

test('feedback list helper clamps page number and page size to backend limits', async () => {
  const fetchFeedbackList = exportedFunction('fetchFeedbackList')
  const calls = []
  const requestFn = async (options) => calls.push(options)

  await fetchFeedbackList(requestFn, { pageNo: -3, pageSize: 100 })
  await fetchFeedbackList(requestFn, {})

  assert.deepEqual(calls, [
    { url: '/api/mine/feedbacks', data: { pageNo: 1, pageSize: 50 } },
    { url: '/api/mine/feedbacks', data: { pageNo: 1, pageSize: 20 } }
  ])
})

test('normalizes feedback status text and tone with backend text preferred', () => {
  const feedbackStatusMeta = exportedFunction('feedbackStatusMeta')

  assert.deepEqual(feedbackStatusMeta('PROCESSING'), { text: '处理中', tone: 'blue' })
  assert.deepEqual(feedbackStatusMeta('WAITING_FOLLOW_UP'), { text: '待再次反馈', tone: 'amber' })
  assert.deepEqual(feedbackStatusMeta('RESOLVED'), { text: '已处理', tone: 'teal' })
  assert.deepEqual(feedbackStatusMeta('UNKNOWN', '服务端文案'), { text: '服务端文案', tone: 'muted' })
})

test('normalizes up to three detail rounds into an ordered user and team timeline', () => {
  const normalizeFeedbackDetail = exportedFunction('normalizeFeedbackDetail')
  const detail = normalizeFeedbackDetail({
    id: 88,
    feedbackNo: 'FB123',
    status: 'WAITING_FOLLOW_UP',
    statusText: '待再次反馈',
    feedbackResult: '请补充最新录屏',
    feedbackResultAt: '2026-08-25T11:00:00',
    roundCount: 2,
    attachmentCount: 2,
    createdAt: '2026-08-25T09:00:00',
    updatedAt: '2026-08-25T11:00:00',
    canAppendRound: true,
    rounds: [
      {
        roundNo: 2,
        description: '第二轮',
        submittedAt: '2026-08-25T10:30:00',
        attachments: [{ mediaType: 'VIDEO', mimeType: 'video/mp4', size: 20, durationMs: 9000, url: 'https://cdn.example.com/b.mp4' }]
      },
      {
        roundNo: 1,
        description: '第一轮',
        submittedAt: '2026-08-25T09:00:00',
        teamResult: '请补充',
        teamResultAt: '2026-08-25T10:00:00',
        attachments: [{ mediaType: 'IMAGE', mimeType: 'image/jpeg', size: 10, durationMs: 0, url: 'https://cdn.example.com/a.jpg' }]
      }
    ]
  })

  assert.equal(detail.id, 88)
  assert.equal(detail.statusText, '待再次反馈')
  assert.equal(detail.statusTone, 'amber')
  assert.equal(detail.canAppendRound, true)
  assert.deepEqual(detail.rounds.map((round) => round.roundNo), [1, 2])
  assert.deepEqual(detail.timeline.map((item) => item.type), ['USER', 'TEAM', 'USER', 'TEAM_CURRENT'])
  assert.equal(detail.timeline[0].attachments[0].isImage, true)
  assert.equal(detail.timeline[2].attachments[0].isVideo, true)
  assert.equal(detail.timeline[3].description, '请补充最新录屏')
})

test('replaces the first feedback page and merges later pages without duplicate IDs', () => {
  const mergeFeedbackPage = exportedFunction('mergeFeedbackPage')
  const first = mergeFeedbackPage({ items: [{ id: 9 }] }, {
    pageNo: 1,
    pageSize: 20,
    total: 2,
    hasMore: true,
    items: [{ id: 3, status: 'PROCESSING' }, { id: 2, status: 'RESOLVED' }]
  })
  const next = mergeFeedbackPage(first, {
    pageNo: 2,
    pageSize: 20,
    total: 3,
    hasMore: false,
    items: [{ id: 2, status: 'RESOLVED' }, { id: 1, status: 'WAITING_FOLLOW_UP' }]
  })

  assert.deepEqual(first.items.map((item) => item.id), [3, 2])
  assert.deepEqual(next.items.map((item) => item.id), [3, 2, 1])
  assert.equal(next.pageNo, 2)
  assert.equal(next.hasMore, false)
  assert.equal(next.items[2].statusText, '待再次反馈')
})

test('uploads images with concurrency two before any video starts', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const files = [
    imageFile({ clientId: 'image-1', tempFilePath: '/tmp/1.jpg' }),
    imageFile({ clientId: 'image-2', tempFilePath: '/tmp/2.jpg' }),
    videoFile({ clientId: 'video-1', tempFilePath: '/tmp/3.mov' })
  ]
  const events = []
  let activeImages = 0
  let maxActiveImages = 0
  let activeTotal = 0
  const requestFn = async (options) => {
    events.push(`request:${options.url}`)
    if (options.url.endsWith('/upload-tickets')) {
      return { items: files.map((file, index) => uploadTicket(file, index + 1)) }
    }
    return { id: 88, status: 'PROCESSING', rounds: [] }
  }
  const wxApi = {
    uploadFile(options) {
      const isVideo = options.filePath.endsWith('.mov')
      assert.equal(isVideo && activeTotal > 0, false, 'video cannot overlap another upload')
      activeTotal += 1
      if (!isVideo) {
        activeImages += 1
        maxActiveImages = Math.max(maxActiveImages, activeImages)
      }
      events.push(`upload:${options.filePath}`)
      setImmediate(() => {
        activeTotal -= 1
        if (!isVideo) activeImages -= 1
        options.success({ statusCode: 204 })
      })
      return { onProgressUpdate() {} }
    }
  }

  await submitFeedbackDraft({
    draft: { idempotencyKey: 'submit-1', description: '问题', attachments: files },
    requestFn,
    wxApi,
    nowMs: Date.parse('2026-08-25T04:00:00Z')
  })

  assert.equal(maxActiveImages, 2)
  assert.ok(events.indexOf('upload:/tmp/3.mov') > events.indexOf('upload:/tmp/2.jpg'))
  assert.equal(events.at(-1), 'request:/api/mine/feedbacks')
})

test('accepts backend space-separated LocalDateTime in feedback upload tickets', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const file = imageFile({ clientId: 'feedback-file-production-response' })
  const uploadCalls = []
  const requestFn = async (options) => {
    if (options.url.endsWith('/upload-tickets')) {
      return { items: [uploadTicket(file, 2, '2026-08-26 16:29:10')] }
    }
    return { id: 88, status: 'PROCESSING', rounds: [] }
  }

  const result = await submitFeedbackDraft({
    draft: { idempotencyKey: 'submit-space-expiry', description: '问题', attachments: [file] },
    requestFn,
    wxApi: successfulWxUpload(uploadCalls),
    nowMs: Date.parse('2026-08-26T08:14:11Z')
  })

  assert.equal(uploadCalls.length, 1)
  assert.equal(result.draft.attachments[0].taskId, 2)
})

test('uploads videos strictly one at a time and uses only ticket uploadUrl and formData', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const files = [
    imageFile({ clientId: 'image-1', tempFilePath: '/tmp/1.jpg' }),
    videoFile({ clientId: 'video-1', tempFilePath: '/tmp/1.mov' }),
    videoFile({ clientId: 'video-2', tempFilePath: '/tmp/2.mov' })
  ]
  let active = 0
  let maxVideoActive = 0
  const uploadCalls = []
  const requestFn = async (options) => {
    if (options.url.endsWith('/upload-tickets')) {
      return { items: files.map((file, index) => uploadTicket(file, 10 + index)) }
    }
    return { id: 88, status: 'PROCESSING', rounds: [] }
  }
  const wxApi = {
    uploadFile(options) {
      active += 1
      if (options.filePath.endsWith('.mov')) maxVideoActive = Math.max(maxVideoActive, active)
      uploadCalls.push(options)
      setImmediate(() => {
        active -= 1
        options.success({ statusCode: 204 })
      })
      return { onProgressUpdate() {} }
    }
  }

  await submitFeedbackDraft({
    draft: { idempotencyKey: 'submit-2', description: '问题', attachments: files },
    requestFn,
    wxApi,
    nowMs: Date.parse('2026-08-25T04:00:00Z')
  })

  assert.equal(maxVideoActive, 1)
  assert.deepEqual(uploadCalls.map((call) => ({
    url: call.url,
    filePath: call.filePath,
    name: call.name,
    formData: call.formData
  })), [
    { url: 'https://cos.example.com/upload/10', filePath: '/tmp/1.jpg', name: 'file', formData: { key: 'server-only/10', policy: 'policy-10' } },
    { url: 'https://cos.example.com/upload/11', filePath: '/tmp/1.mov', name: 'file', formData: { key: 'server-only/11', policy: 'policy-11' } },
    { url: 'https://cos.example.com/upload/12', filePath: '/tmp/2.mov', name: 'file', formData: { key: 'server-only/12', policy: 'policy-12' } }
  ])
})

test('partial upload failure never creates feedback and retry reuses a valid failed client task', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const firstFiles = [
    imageFile({ clientId: 'image-ok', tempFilePath: '/tmp/ok.jpg' }),
    imageFile({ clientId: 'image-fail', tempFilePath: '/tmp/fail.jpg' })
  ]
  const requestCalls = []
  const uploadCalls = []
  let ticketRound = 0
  const requestFn = async (options) => {
    requestCalls.push(options)
    if (options.url.endsWith('/upload-tickets')) {
      ticketRound += 1
      return {
        items: options.data.files.map((metadata, index) => uploadTicket(
          Object.assign({}, firstFiles[index], metadata),
          ticketRound === 1 ? 100 + index : 101,
          '2026-08-25T12:15:00'
        ))
      }
    }
    return { id: 88, status: 'PROCESSING', rounds: [] }
  }
  let shouldFail = true
  const wxApi = {
    uploadFile(options) {
      uploadCalls.push(options.filePath)
      if (shouldFail && options.filePath === '/tmp/fail.jpg') {
        queueMicrotask(() => options.fail({ errMsg: 'upload failed' }))
      } else {
        queueMicrotask(() => options.success({ statusCode: 204 }))
      }
      return { onProgressUpdate() {} }
    }
  }

  let failedDraft
  await assert.rejects(async () => {
    try {
      await submitFeedbackDraft({
        draft: { idempotencyKey: 'submit-retry', description: '问题', attachments: firstFiles },
        requestFn,
        wxApi,
        nowMs: Date.parse('2026-08-25T04:00:00Z'),
        idFactory: (prefix) => `${prefix}-renewed`
      })
    } catch (error) {
      failedDraft = error.draft
      throw error
    }
  }, /附件上传失败/)

  assert.equal(requestCalls.some((call) => call.url === '/api/mine/feedbacks'), false)
  assert.equal(failedDraft.attachments[0].status, 'UPLOADED')
  assert.equal(failedDraft.attachments[1].status, 'FAILED')
  shouldFail = false
  const oldFailedClientId = failedDraft.attachments[1].clientId
  const result = await submitFeedbackDraft({
    draft: failedDraft,
    requestFn,
    wxApi,
    nowMs: Date.parse('2026-08-25T04:01:00Z'),
    idFactory: (prefix) => `${prefix}-retry`
  })

  assert.deepEqual(uploadCalls, ['/tmp/ok.jpg', '/tmp/fail.jpg', '/tmp/fail.jpg'])
  assert.equal(requestCalls.filter((call) => call.url.endsWith('/upload-tickets')).length, 2)
  const retryTicketPayload = requestCalls.filter((call) => call.url.endsWith('/upload-tickets'))[1].data.files
  assert.equal(retryTicketPayload.length, 1)
  assert.equal(retryTicketPayload[0].clientId, oldFailedClientId)
  assert.match(retryTicketPayload[0].clientId, /^[\x21-\x7e]{1,64}$/)
  assert.equal(result.draft.idempotencyKey, 'submit-retry')
  assert.deepEqual(requestCalls.at(-1).data.uploadTaskIds, [100, 101])
})

test('expired uploaded task gets a new clientId and ticket before append confirmation', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const previous = imageFile({
    clientId: 'expired-image',
    status: 'UPLOADED',
    taskId: 7,
    expiresAt: '2026-08-25T11:59:59'
  })
  const calls = []
  const uploads = []
  const requestFn = async (options) => {
    calls.push(options)
    if (options.url.endsWith('/upload-tickets')) {
      const nextFile = options.data.files[0]
      return { items: [uploadTicket(nextFile, 8)] }
    }
    return { id: 99, status: 'PROCESSING', rounds: [] }
  }

  const result = await submitFeedbackDraft({
    feedbackId: 99,
    draft: { idempotencyKey: 'append-2', description: '补充', attachments: [previous] },
    requestFn,
    wxApi: successfulWxUpload(uploads),
    nowMs: Date.parse('2026-08-25T04:00:00Z'),
    idFactory: (prefix) => `${prefix}-fresh`
  })

  assert.notEqual(calls[0].data.files[0].clientId, 'expired-image')
  assert.equal(uploads.length, 1)
  assert.equal(calls.at(-1).url, '/api/mine/feedbacks/99/rounds')
  assert.deepEqual(calls.at(-1).data, {
    idempotencyKey: 'append-2',
    description: '补充',
    uploadTaskIds: [8]
  })
  assert.equal(result.detail.id, 99)
})

test('valid uploaded tasks skip ticket and upload while create performs server confirmation', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const calls = []
  const uploads = []
  const requestFn = async (options) => {
    calls.push(options)
    return { id: 88, status: 'PROCESSING', rounds: [] }
  }
  const uploaded = imageFile({
    status: 'UPLOADED',
    taskId: 77,
    expiresAt: '2026-08-25T12:15:00',
    uploadTicket: {
      uploadUrl: 'https://old-ticket.example.com',
      formData: { policy: 'must-not-survive' }
    }
  })

  const result = await submitFeedbackDraft({
    draft: { idempotencyKey: 'create-confirm', description: '问题', attachments: [uploaded] },
    requestFn,
    wxApi: successfulWxUpload(uploads),
    nowMs: Date.parse('2026-08-25T04:00:00Z')
  })

  assert.equal(uploads.length, 0)
  assert.equal(result.draft.attachments[0].uploadTicket, undefined)
  assert.deepEqual(calls, [{
    url: '/api/mine/feedbacks',
    method: 'POST',
    data: {
      idempotencyKey: 'create-confirm',
      description: '问题',
      uploadTaskIds: [77]
    }
  }])
})

test('forbid-overwrite conflict only counts as uploaded for COS FileAlreadyExists', async () => {
  const submitFeedbackDraft = exportedFunction('submitFeedbackDraft')
  const responses = [
    { statusCode: 409, data: '<Error><Code>FileAlreadyExists</Code></Error>' },
    { statusCode: 409, data: '<Error><Code>UploadConflict</Code></Error>' }
  ]
  const requestFn = async (options) => {
    if (options.url.endsWith('/upload-tickets')) {
      return { items: [uploadTicket(imageFile(), 91)] }
    }
    return { id: 88, status: 'PROCESSING', rounds: [] }
  }
  const wxApi = {
    uploadFile(options) {
      queueMicrotask(() => options.success(responses.shift()))
      return { onProgressUpdate() {} }
    }
  }

  await submitFeedbackDraft({
    draft: { idempotencyKey: 'already-uploaded', description: '问题', attachments: [imageFile()] },
    requestFn,
    wxApi,
    nowMs: Date.parse('2026-08-25T04:00:00Z')
  })
  await assert.rejects(() => submitFeedbackDraft({
    draft: { idempotencyKey: 'other-conflict', description: '问题', attachments: [imageFile()] },
    requestFn,
    wxApi,
    nowMs: Date.parse('2026-08-25T04:00:00Z')
  }), /附件上传失败.*409/)
})
