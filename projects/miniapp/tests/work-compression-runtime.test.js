const assert = require('node:assert/strict')
const test = require('node:test')

const {
  COMPRESSION_BUSY,
  COMPRESSION_CANCELLED,
  COMPRESSION_TIMEOUT,
  ENCODING_LEASE_MULTIPLIER,
  IMAGE_ATTEMPT_TIMEOUT_MS,
  IMAGE_TOTAL_TIMEOUT_MS,
  METADATA_TIMEOUT_MS,
  VIDEO_ATTEMPT_TIMEOUT_MS,
  VIDEO_TOTAL_TIMEOUT_MS,
  buildMediaErrorMessage,
  createCompressionSession,
  releasePreparedWorkFiles
} = require('../pages/works/utils/work-compression-runtime')

test('微信原生fail对象归一化后保留原因、错误码及失败API', async () => {
  const nativeError = Object.freeze({ errMsg: 'compressVideo:fail encoder not supported', errno: 1103003 })
  const session = createCompressionSession({ wxApi: { compressVideo({ fail }) { fail(nativeError) } } })
  await assert.rejects(session.call('compressVideo', {}, { timeoutMs: 1000 }), error => {
    assert.ok(error instanceof Error)
    assert.equal(error.message, nativeError.errMsg)
    assert.equal(error.errno, 1103003)
    assert.equal(error.errMsg, nativeError.errMsg)
    assert.equal(error.nativeMethod, 'compressVideo')
    assert.strictEqual(error.cause, nativeError)
    return true
  })
  session.dispose()
})

test('已映射的应用错误保留展示文案和原始原生诊断字段', async () => {
  const error = Object.assign(new Error('暂时无法选择音频，请联系管理员'), {
    errMsg: 'chooseMessageFile:fail api scope is not declared in the privacy agreement', errno: 112
  })
  const session = createCompressionSession({ wxApi: {} })
  await assert.rejects(session.call(({ fail }) => fail(error), {}, { timeoutMs: 1000 }), actual => {
    assert.equal(actual.message, '暂时无法选择音频，请联系管理员')
    assert.equal(actual.errMsg, error.errMsg)
    assert.equal(actual.errno, 112)
    return true
  })
  session.dispose()
})

test('媒体错误展示包含阶段和原生原因，兼容picker对象和错误码', () => {
  assert.equal(typeof buildMediaErrorMessage, 'function')
  for (const [error, expected] of [
    [{ errMsg: 'chooseMedia:fail permission denied', errno: 104 }, '选择作品失败：permission denied（错误码：104）'],
    [{ errMsg: 'getVideoInfo:fail decoder unsupported', errno: 1103001 }, '视频信息读取失败：decoder unsupported（错误码：1103001）'],
    [{ errMsg: 'compressVideo:fail encoder not supported', errno: 1103003 }, '视频压缩失败：encoder not supported（错误码：1103003）'],
    [{ errMsg: 'compressImage:fail system error', errCode: -1 }, '图片压缩失败：system error（错误码：-1）'],
    [new Error('视频作品不能超过 10 分钟'), '视频作品不能超过 10 分钟'],
    [undefined, '选择作品失败']
  ]) assert.equal(buildMediaErrorMessage(error), expected)
})

test('原生Error和同步异常保留内部错误code并能展示API阶段', async () => {
  const original = Object.assign(new Error('encoder stopped'), { code: 'ENCODER_ERROR' })
  const session = createCompressionSession({ wxApi: { compressVideo() { throw original } } })
  await assert.rejects(session.call('compressVideo', {}, { timeoutMs: 1000 }), error => {
    assert.equal(error.code, 'ENCODER_ERROR')
    assert.equal(error.message, 'encoder stopped')
    assert.equal(error.nativeMethod, 'compressVideo')
    assert.equal(buildMediaErrorMessage(error), '视频压缩失败：encoder stopped')
    return true
  })
  session.dispose()
})

test('错误展示不包含文件地址，仍保留解码失败原因', () => {
  assert.equal(typeof buildMediaErrorMessage, 'function')
  const message = buildMediaErrorMessage({
    errMsg: "getVideoInfo:fail decoder unsupported, src='wxfile://private clip.mp4', file /tmp/clip.mp4",
    errno: 1103001
  })
  assert.match(message, /视频信息读取失败.*decoder unsupported/)
  assert.match(message, /1103001/)
  assert.doesNotMatch(message, /wxfile|private clip|\/tmp\/clip/)
})

test('Android绝对路径不进入可展示错误及日志', () => {
  for (const filePath of ['/sdcard/DCIM/Camera/clip.mp4', '/mnt/sdcard/clip.mp4']) {
    const message = buildMediaErrorMessage({ errMsg: `getVideoInfo:fail decoder unsupported, src=${filePath}` })
    assert.match(message, /视频信息读取失败.*decoder unsupported/)
    assert.ok(!message.includes(filePath))
  }
})

function createWxApi(options = {}) {
  const callbacks = []
  const removed = []
  const wxApi = {
    compressImage(args) {
      callbacks.push(args)
    },
    compressVideo(args) {
      callbacks.push(args)
    },
    getFileSystemManager() {
      return {
        unlinkSync(path) {
          removed.push(path)
          if (options.unlinkErrorPaths && options.unlinkErrorPaths.has(path)) {
            throw new Error('unlink failed')
          }
        }
      }
    }
  }
  return { callbacks, removed, wxApi }
}

function enableClock(t) {
  t.mock.timers.enable({
    apis: ['Date', 'setTimeout'],
    now: 0
  })
}

test('导出压缩模块共享的错误码和时间预算', () => {
  assert.equal(COMPRESSION_CANCELLED, 'MEDIA_COMPRESSION_CANCELLED')
  assert.equal(COMPRESSION_TIMEOUT, 'MEDIA_COMPRESSION_TIMEOUT')
  assert.equal(COMPRESSION_BUSY, 'MEDIA_COMPRESSION_BUSY')
  assert.equal(METADATA_TIMEOUT_MS, 10 * 1000)
  assert.equal(IMAGE_ATTEMPT_TIMEOUT_MS, 30 * 1000)
  assert.equal(IMAGE_TOTAL_TIMEOUT_MS, 120 * 1000)
  assert.equal(VIDEO_ATTEMPT_TIMEOUT_MS, 10 * 60 * 1000)
  assert.equal(VIDEO_TOTAL_TIMEOUT_MS, 20 * 60 * 1000)
  assert.equal(ENCODING_LEASE_MULTIPLIER, 2)
})

test('取消后只清理本次编码的迟到文件，保留原片', async () => {
  let callbacks
  const removed = []
  const wxApi = {
    compressVideo(args) {
      callbacks = args
    },
    getFileSystemManager() {
      return {
        unlinkSync(path) {
          removed.push(path)
        }
      }
    }
  }
  const session = createCompressionSession({
    wxApi,
    protectedPaths: ['wxfile://source']
  })
  const promise = session.call('compressVideo', {
    src: 'wxfile://source'
  }, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true,
    createsFile: true
  })
  const rejection = assert.rejects(promise, {
    code: 'MEDIA_COMPRESSION_CANCELLED'
  })

  session.cancel()
  await rejection
  callbacks.success({ tempFilePath: 'wxfile://late' })
  session.dispose()

  assert.deepEqual(removed, ['wxfile://late'])
})

test('等待超时按当前预算结束，但编码租约按完整名义超时计算', async t => {
  enableClock(t)
  const originalWarn = console.warn
  console.warn = () => {}
  t.after(() => {
    console.warn = originalWarn
  })
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const pending = first.call('compressVideo', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  const timedOut = assert.rejects(pending, { code: COMPRESSION_TIMEOUT })

  t.mock.timers.tick(10)
  await timedOut
  t.mock.timers.setTime(100)
  first.cancel()

  const second = createCompressionSession({ wxApi })
  t.mock.timers.setTime(1999)
  await assert.rejects(
    () => second.call('compressVideo', {}, {
      timeoutMs: 10,
      nominalTimeoutMs: 1000,
      encoding: true
    }),
    { code: COMPRESSION_BUSY }
  )
  assert.equal(callbacks.length, 1)

  t.mock.timers.setTime(2000)
  const next = second.call('compressVideo', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  assert.equal(callbacks.length, 2)
  callbacks[1].fail(new Error('stop'))
  await assert.rejects(next, /stop/)
})

test('租约看门狗到截止时间恢复编码能力且不自动重启旧调用', async t => {
  enableClock(t)
  const originalWarn = console.warn
  const warnings = []
  console.warn = message => warnings.push(message)
  t.after(() => {
    console.warn = originalWarn
  })
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const pending = first.call('compressImage', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 50,
    encoding: true
  })
  const timedOut = assert.rejects(pending, { code: COMPRESSION_TIMEOUT })
  t.mock.timers.tick(10)
  await timedOut

  t.mock.timers.tick(89)
  const second = createCompressionSession({ wxApi })
  await assert.rejects(
    () => second.call('compressImage', {}, {
      timeoutMs: 10,
      nominalTimeoutMs: 50,
      encoding: true
    }),
    { code: COMPRESSION_BUSY }
  )
  t.mock.timers.tick(1)
  assert.equal(callbacks.length, 1)

  const next = second.call('compressImage', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 50,
    encoding: true
  })
  assert.equal(callbacks.length, 2)
  callbacks[1].fail(new Error('stop'))
  await assert.rejects(next, /stop/)
  assert.equal(warnings.length, 1)
})

test('争锁时回收后台暂停期间已过期的租约', async t => {
  enableClock(t)
  const originalWarn = console.warn
  console.warn = () => {}
  t.after(() => {
    console.warn = originalWarn
  })
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const pending = first.call('compressImage', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 100,
    encoding: true
  })
  const timedOut = assert.rejects(pending, { code: COMPRESSION_TIMEOUT })
  t.mock.timers.tick(10)
  await timedOut

  t.mock.timers.setTime(250)
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressImage', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 100,
    encoding: true
  })
  assert.equal(callbacks.length, 2)
  callbacks[1].fail(new Error('stop'))
  await assert.rejects(next, /stop/)
})

test('旧终态和旧看门狗不能释放新调用的锁或删除其文件', async t => {
  enableClock(t)
  const originalWarn = console.warn
  console.warn = () => {}
  t.after(() => {
    console.warn = originalWarn
  })
  const { callbacks, removed, wxApi } = createWxApi()
  const oldSession = createCompressionSession({
    wxApi,
    protectedPaths: ['wxfile://source']
  })
  const oldPromise = oldSession.call('compressImage', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 100,
    encoding: true,
    createsFile: true
  })
  const oldTimeout = assert.rejects(oldPromise, { code: COMPRESSION_TIMEOUT })
  t.mock.timers.tick(10)
  await oldTimeout
  t.mock.timers.setTime(250)

  const newSession = createCompressionSession({
    wxApi,
    protectedPaths: ['wxfile://source']
  })
  const newPromise = newSession.call('compressImage', {}, {
    timeoutMs: 100,
    nominalTimeoutMs: 100,
    encoding: true,
    createsFile: true
  })
  callbacks[0].success({ tempFilePath: 'wxfile://old-late' })
  callbacks[0].fail(new Error('duplicate'))
  callbacks[0].success({ tempFilePath: 'wxfile://source' })

  await assert.rejects(
    () => newSession.call('compressVideo', {}, {
      timeoutMs: 10,
      nominalTimeoutMs: 100,
      encoding: true
    }),
    { code: COMPRESSION_BUSY }
  )

  callbacks[1].success({ tempFilePath: 'wxfile://new' })
  await newPromise
  assert.deepEqual(newSession.transfer([
    'wxfile://source',
    'wxfile://missing',
    'wxfile://new'
  ]), ['wxfile://new'])
  t.mock.timers.tick(0)
  oldSession.dispose()
  newSession.dispose()

  assert.deepEqual(removed, ['wxfile://old-late'])
})

test('正常成功、失败和同步异常都会立即释放各自的编码锁', async () => {
  const { callbacks, wxApi } = createWxApi()
  const session = createCompressionSession({ wxApi })

  const success = session.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  callbacks[0].success({ ok: true })
  assert.deepEqual(await success, { ok: true })

  const failure = session.call('compressVideo', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  callbacks[1].fail(new Error('native fail'))
  await assert.rejects(failure, /native fail/)

  await assert.rejects(
    () => session.call(() => {
      throw new Error('sync fail')
    }, {}, {
      timeoutMs: 1000,
      nominalTimeoutMs: 1000,
      encoding: true
    }),
    /sync fail/
  )

  const after = session.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  callbacks[2].fail(new Error('stop'))
  await assert.rejects(after, /stop/)
})

test('两个会话共享编码锁，非编码调用不占用该锁', async () => {
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const second = createCompressionSession({ wxApi })
  const pending = first.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  await assert.rejects(
    () => second.call('compressVideo', {}, {
      timeoutMs: 1000,
      nominalTimeoutMs: 1000,
      encoding: true
    }),
    {
      code: COMPRESSION_BUSY,
      message: '上一次媒体处理尚未结束，请稍后重试'
    }
  )

  const metadata = second.call('compressVideo', {}, { timeoutMs: 1000 })
  assert.equal(callbacks.length, 2)
  callbacks[1].success({ width: 100 })
  assert.deepEqual(await metadata, { width: 100 })
  callbacks[0].fail(new Error('stop'))
  await assert.rejects(pending, /stop/)
})

test('生成文件必须有路径，返回受保护源路径时不取得其所有权', async () => {
  const { callbacks, removed, wxApi } = createWxApi()
  const session = createCompressionSession({
    wxApi,
    protectedPaths: ['wxfile://source']
  })
  const empty = session.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true,
    createsFile: true
  })
  callbacks[0].success({})
  await assert.rejects(empty, /未返回生成文件/)

  const source = session.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true,
    createsFile: true
  })
  callbacks[1].success({ tempFilePath: 'wxfile://source' })
  assert.deepEqual(await source, { tempFilePath: 'wxfile://source' })
  session.discard('wxfile://source')
  session.dispose()
  assert.deepEqual(removed, [])
})

test('transfer只转移会话自有文件，discard和dispose均幂等', async () => {
  const { callbacks, removed, wxApi } = createWxApi({
    unlinkErrorPaths: new Set(['wxfile://unlink-error'])
  })
  const session = createCompressionSession({ wxApi })
  for (const path of ['wxfile://keep', 'wxfile://discard', 'wxfile://unlink-error']) {
    const pending = session.call('compressImage', {}, {
      timeoutMs: 1000,
      nominalTimeoutMs: 1000,
      encoding: true,
      createsFile: true
    })
    callbacks[callbacks.length - 1].success({ tempFilePath: path })
    await pending
  }

  assert.deepEqual(session.transfer([
    'wxfile://missing',
    'wxfile://keep',
    'wxfile://keep'
  ]), ['wxfile://keep'])
  session.discard('wxfile://missing')
  session.discard('wxfile://discard')
  session.discard('wxfile://discard')
  session.dispose()
  session.dispose()

  assert.deepEqual(removed, ['wxfile://discard', 'wxfile://unlink-error'])
})

test('函数调用分支同样支持取消并清理迟到生成文件', async () => {
  let callbacks
  const { removed, wxApi } = createWxApi()
  const session = createCompressionSession({ wxApi })
  const pending = session.call(args => {
    callbacks = args
  }, { src: 'wxfile://source' }, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true,
    createsFile: true
  })
  const cancelled = assert.rejects(pending, { code: COMPRESSION_CANCELLED })
  session.cancel()
  await cancelled
  callbacks.success({ tempFilePath: 'wxfile://late-function' })

  assert.deepEqual(removed, ['wxfile://late-function'])
})

test('onNativeSettled只在真实终态通知一次，且收尾异常不替换结果', async t => {
  enableClock(t)
  const originalWarn = console.warn
  const warnings = []
  console.warn = message => warnings.push(message)
  t.after(() => {
    console.warn = originalWarn
  })
  const { callbacks, wxApi } = createWxApi()
  const session = createCompressionSession({ wxApi })
  let settledCount = 0
  const pending = session.call('compressImage', {}, {
    timeoutMs: 10,
    nominalTimeoutMs: 50,
    encoding: true,
    onNativeSettled() {
      settledCount++
      throw new Error('cleanup failed')
    }
  })
  const timedOut = assert.rejects(pending, { code: COMPRESSION_TIMEOUT })
  t.mock.timers.tick(10)
  await timedOut
  assert.equal(settledCount, 0)
  t.mock.timers.tick(90)
  assert.equal(settledCount, 0)

  callbacks[0].success({ ok: true })
  callbacks[0].success({ ok: false })
  callbacks[0].fail(new Error('duplicate'))
  assert.equal(settledCount, 1)
  assert.equal(warnings.length, 2)
})

test('取消后的真实失败仍通知收尾并释放锁，取消本身不伪造通知', async () => {
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  let settledCount = 0
  const pending = first.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true,
    onNativeSettled() {
      settledCount++
    }
  })
  const cancelled = assert.rejects(pending, { code: COMPRESSION_CANCELLED })
  first.cancel()
  await cancelled
  assert.equal(settledCount, 0)

  callbacks[0].fail(new Error('late fail'))
  assert.equal(settledCount, 1)
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressVideo', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  callbacks[1].fail(new Error('stop'))
  await assert.rejects(next, /stop/)
})

test('未启动的零预算和争锁失败不会通知原生终态', async () => {
  const { callbacks, wxApi } = createWxApi()
  const session = createCompressionSession({ wxApi })
  let settledCount = 0
  await assert.rejects(
    () => session.call('compressImage', {}, {
      timeoutMs: 0,
      nominalTimeoutMs: 1000,
      encoding: true,
      onNativeSettled() {
        settledCount++
      }
    }),
    { code: COMPRESSION_TIMEOUT }
  )
  assert.equal(callbacks.length, 0)

  const pending = session.call('compressImage', {}, {
    timeoutMs: 1000,
    nominalTimeoutMs: 1000,
    encoding: true
  })
  await assert.rejects(
    () => session.call('compressVideo', {}, {
      timeoutMs: 1000,
      nominalTimeoutMs: 1000,
      encoding: true,
      onNativeSettled() {
        settledCount++
      }
    }),
    { code: COMPRESSION_BUSY }
  )
  assert.equal(settledCount, 0)
  callbacks[0].fail(new Error('stop'))
  await assert.rejects(pending, /stop/)
})

test('releasePreparedWorkFiles按clientId幂等释放并继续处理清理异常', () => {
  const { removed, wxApi } = createWxApi({
    unlinkErrorPaths: new Set(['wxfile://bad'])
  })
  const ownedPathsByClientId = {
    a: ['wxfile://a', 'wxfile://bad'],
    b: ['wxfile://b'],
    c: ['wxfile://a']
  }

  releasePreparedWorkFiles({
    wxApi,
    ownedPathsByClientId,
    clientIds: ['a', 'a']
  })
  releasePreparedWorkFiles({
    wxApi,
    ownedPathsByClientId,
    clientIds: ['a']
  })
  assert.deepEqual(removed, ['wxfile://a', 'wxfile://bad'])
  assert.deepEqual(ownedPathsByClientId, {
    b: ['wxfile://b'],
    c: ['wxfile://a']
  })

  releasePreparedWorkFiles({ wxApi, ownedPathsByClientId })
  assert.deepEqual(removed, [
    'wxfile://a',
    'wxfile://bad',
    'wxfile://b',
    'wxfile://a'
  ])
  assert.deepEqual(ownedPathsByClientId, {})
})

test('编码可等待旧原生调用终态后继续，取消旧会话不提前并发编码', async () => {
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const old = first.call('compressImage', {}, { timeoutMs: 1000, nominalTimeoutMs: 1000, encoding: true })
  const cancelled = assert.rejects(old, { code: COMPRESSION_CANCELLED })
  first.cancel(); await cancelled
  const waiting = []
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressImage', {}, { timeoutMs: 1000, nominalTimeoutMs: 1000,
    encoding: true, waitForEncoding: true, encodingWaitTimeoutMs: 2000, onEncodingWait: value => waiting.push(value) })
  await new Promise(setImmediate)
  assert.equal(callbacks.length, 1)
  callbacks[0].fail(new Error('old stopped'))
  await new Promise(setImmediate)
  assert.equal(callbacks.length, 2)
  callbacks[1].success({ ok: true })
  assert.deepEqual(await next, { ok: true })
  assert.deepEqual(waiting, [true, false])
  second.dispose()
})

test('取消编码等待立即退出且不释放其他会话的原生锁', async () => {
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const old = first.call('compressImage', {}, { timeoutMs: 1000, nominalTimeoutMs: 1000, encoding: true })
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressImage', {}, { timeoutMs: 1000, nominalTimeoutMs: 1000,
    encoding: true, waitForEncoding: true, encodingWaitTimeoutMs: 2000 })
  const cancelled = assert.rejects(next, { code: COMPRESSION_CANCELLED })
  second.cancel(); await cancelled
  assert.equal(callbacks.length, 1)
  const third = createCompressionSession({ wxApi })
  await assert.rejects(third.call('compressImage', {}, { timeoutMs: 1000, nominalTimeoutMs: 1000, encoding: true }), { code: COMPRESSION_BUSY })
  callbacks[0].success({ ok: true }); await old
  assert.equal(callbacks.length, 1)
  first.dispose(); third.dispose()
})

test('租期到期唤醒等待者，等待时间计入编码总预算且旧终态不解新锁', async t => {
  enableClock(t)
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const old = first.call('compressImage', {}, { timeoutMs: 100, nominalTimeoutMs: 100, encoding: true })
  const oldCancelled = assert.rejects(old, { code: COMPRESSION_CANCELLED })
  first.cancel(); await oldCancelled
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressImage', {}, { timeoutMs: 100, nominalTimeoutMs: 100,
    encoding: true, waitForEncoding: true, encodingWaitTimeoutMs: 250 })
  const timedOut = assert.rejects(next, { code: COMPRESSION_TIMEOUT })
  t.mock.timers.tick(200)
  await new Promise(setImmediate)
  assert.equal(callbacks.length, 2)
  callbacks[0].fail(new Error('late old'))
  const third = createCompressionSession({ wxApi })
  await assert.rejects(third.call('compressImage', {}, { timeoutMs: 100, nominalTimeoutMs: 100, encoding: true }), { code: COMPRESSION_BUSY })
  t.mock.timers.tick(50)
  await timedOut
  callbacks[1].success({ ok: true })
  second.dispose(); third.dispose()
})

test('等待超时不启动原生编码，也不影响仍持锁调用', async t => {
  enableClock(t)
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const old = first.call('compressImage', {}, { timeoutMs: 1000, nominalTimeoutMs: 1000, encoding: true })
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressImage', {}, { timeoutMs: 100, nominalTimeoutMs: 100,
    encoding: true, waitForEncoding: true, encodingWaitTimeoutMs: 50 })
  const timedOut = assert.rejects(next, { code: COMPRESSION_TIMEOUT })
  t.mock.timers.tick(50)
  await timedOut
  assert.equal(callbacks.length, 1)
  callbacks[0].success({ ok: true }); await old
  second.dispose(); first.dispose()
})

test('多个等待会话被同时唤醒后仍只允许一个原生编码', async () => {
  const { callbacks, wxApi } = createWxApi()
  const sessions = [0, 1, 2].map(() => createCompressionSession({ wxApi }))
  const options = { encoding: true, timeoutMs: 1000, nominalTimeoutMs: 1000, waitForEncoding: true, encodingWaitTimeoutMs: 2000 }
  const pending = sessions.map(session => session.call('compressImage', {}, options))
  assert.equal(callbacks.length, 1)
  callbacks[0].success({ index: 0 }); await new Promise(setImmediate)
  assert.equal(callbacks.length, 2)
  callbacks[1].success({ index: 1 }); await new Promise(setImmediate)
  assert.equal(callbacks.length, 3)
  callbacks[2].success({ index: 2 })
  assert.deepEqual(await Promise.all(pending), [{ index: 0 }, { index: 1 }, { index: 2 }])
  sessions.forEach(session => session.dispose())
})

test('等待进度回调异常不能阻止旧调用结算或泄漏等待者', async () => {
  const { callbacks, wxApi } = createWxApi()
  const first = createCompressionSession({ wxApi })
  const old = first.call('compressImage', {}, { encoding: true, timeoutMs: 1000, nominalTimeoutMs: 1000 })
  const second = createCompressionSession({ wxApi })
  const next = second.call('compressImage', {}, { encoding: true, timeoutMs: 1000, nominalTimeoutMs: 1000,
    waitForEncoding: true, encodingWaitTimeoutMs: 2000, onEncodingWait() { throw new Error('display failed') } })
  next.catch(() => {})
  callbacks[0].success({ index: 0 })
  assert.deepEqual(await old, { index: 0 })
  await new Promise(setImmediate)
  assert.equal(callbacks.length, 2)
  callbacks[1].success({ index: 1 })
  assert.deepEqual(await next, { index: 1 })
  first.dispose(); second.dispose()
})
