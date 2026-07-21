const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { createUpdateController } = require('../utils/update-manager')

const UPDATE_MANAGER_SOURCE = fs.readFileSync(
  path.join(__dirname, '../utils/update-manager.js'),
  'utf8'
)

function createWxHarness(options = {}) {
  const callbacks = {}
  const modals = []
  const registrations = { check: 0, ready: 0, failed: 0 }
  let applyCount = 0

  const updateManager = {
    onCheckForUpdate(callback) {
      registrations.check += 1
      callbacks.check = callback
    },
    onUpdateReady(callback) {
      registrations.ready += 1
      callbacks.ready = callback
    },
    onUpdateFailed(callback) {
      registrations.failed += 1
      callbacks.failed = callback
    },
    applyUpdate() {
      applyCount += 1
      if (options.applyError) throw options.applyError
    }
  }

  const wxApi = {
    getUpdateManager() {
      return updateManager
    },
    showModal(modalOptions) {
      modals.push(modalOptions)
    }
  }

  return {
    callbacks,
    modals,
    registrations,
    updateManager,
    wxApi,
    getApplyCount: () => applyCount
  }
}

function withoutConsoleWarnings(callback) {
  const originalWarn = console.warn
  console.warn = () => {}
  try {
    return callback()
  } finally {
    console.warn = originalWarn
  }
}

test('initializes update listeners once and keeps version checks silent', () => {
  const harness = createWxHarness()
  const controller = createUpdateController(harness.wxApi)

  assert.equal(controller.init(), true)
  assert.equal(controller.init(), true)
  assert.deepEqual(harness.registrations, { check: 1, ready: 1, failed: 1 })

  harness.callbacks.check({ hasUpdate: true })
  harness.callbacks.check({ hasUpdate: false })

  assert.equal(harness.modals.length, 0)
})

test('waits until the next prompt check before requiring a restart', () => {
  const harness = createWxHarness()
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  controller.promptIfReady()

  harness.callbacks.ready()

  assert.equal(harness.modals.length, 0)

  controller.promptIfReady()
  controller.promptIfReady()

  assert.equal(harness.modals.length, 1)
  assert.equal(harness.modals[0].title, '版本更新')
  assert.equal(harness.modals[0].content, '新版本已经准备好。点击后将立即重启，当前未保存的内容会丢失。')
  assert.equal(harness.modals[0].confirmText, '立即重启')
  assert.equal(harness.modals[0].showCancel, false)

  harness.modals[0].success({ confirm: true })
  harness.modals[0].success({ confirm: true })

  assert.equal(harness.getApplyCount(), 1)
})

test('duplicate successful modal callback clears the prompt state before returning', () => {
  assert.match(
    UPDATE_MANAGER_SOURCE,
    /if\s*\(applyRequested\)\s*\{\s*prompting\s*=\s*false\s*return\s*\}/
  )
})

test('does not prompt on the initial show when the update becomes ready during launch', () => {
  const harness = createWxHarness()
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  harness.callbacks.ready()

  controller.promptIfReady()

  assert.equal(harness.modals.length, 0)

  controller.promptIfReady()

  assert.equal(harness.modals.length, 1)
})

test('allows a later prompt after applyUpdate throws', () => {
  const harness = createWxHarness({ applyError: new Error('apply failed') })
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  controller.promptIfReady()
  harness.callbacks.ready()
  controller.promptIfReady()

  withoutConsoleWarnings(() => {
    harness.modals[0].success({ confirm: true })
  })
  controller.promptIfReady()

  assert.equal(harness.getApplyCount(), 1)
  assert.equal(harness.modals.length, 2)
})

test('reports update download failure and clears the pending restart', () => {
  const harness = createWxHarness()
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  controller.promptIfReady()
  harness.callbacks.ready()

  harness.callbacks.failed()

  assert.equal(harness.modals.length, 1)
  assert.equal(harness.modals[0].title, '更新失败')
  assert.equal(harness.modals[0].content, '新版本下载失败，请检查网络并稍后重新打开小程序。')
  assert.equal(harness.modals[0].confirmText, '知道了')
  assert.equal(harness.modals[0].showCancel, false)

  controller.promptIfReady()

  assert.equal(harness.modals.length, 1)
  assert.equal(harness.getApplyCount(), 0)
})

test('prompts once when the WeChat version does not support update manager', () => {
  const modals = []
  const controller = createUpdateController({
    showModal(options) {
      modals.push(options)
    }
  })

  assert.equal(controller.init(), false)
  assert.equal(controller.init(), false)
  assert.equal(modals.length, 1)
  assert.equal(modals[0].title, '微信版本过低')
  assert.equal(modals[0].content, '当前微信版本不支持自动更新，请升级微信后重新打开小程序。')
  assert.equal(modals[0].confirmText, '知道了')
  assert.equal(modals[0].showCancel, false)
})

test('degrades safely when update manager initialization throws', () => {
  const controller = createUpdateController({
    getUpdateManager() {
      throw new Error('manager failed')
    },
    showModal() {
      throw new Error('modal should not be called')
    }
  })

  withoutConsoleWarnings(() => {
    assert.equal(controller.init(), false)
    assert.doesNotThrow(() => controller.promptIfReady())
  })
})

test('clears the prompt guard when showModal throws', () => {
  const harness = createWxHarness()
  let modalCalls = 0
  harness.wxApi.showModal = () => {
    modalCalls += 1
    throw new Error('modal failed')
  }
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  controller.promptIfReady()
  harness.callbacks.ready()

  withoutConsoleWarnings(() => {
    controller.promptIfReady()
    controller.promptIfReady()
  })

  assert.equal(modalCalls, 2)
})

test('clears the prompt guard when showModal reports failure', () => {
  const harness = createWxHarness()
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  controller.promptIfReady()
  harness.callbacks.ready()
  controller.promptIfReady()

  withoutConsoleWarnings(() => {
    harness.modals[0].fail(new Error('modal failed'))
  })
  controller.promptIfReady()

  assert.equal(harness.modals.length, 2)
})

test('does not apply an update after a later download failure clears readiness', () => {
  const harness = createWxHarness()
  const controller = createUpdateController(harness.wxApi)
  controller.init()
  controller.promptIfReady()
  harness.callbacks.ready()
  controller.promptIfReady()
  const updateModal = harness.modals[0]

  harness.callbacks.failed()
  updateModal.success({ confirm: true })

  assert.equal(harness.modals.length, 2)
  assert.equal(harness.modals[1].title, '更新失败')
  assert.equal(harness.getApplyCount(), 0)
})

function loadAppWithUpdateController(updateController) {
  const appPath = require.resolve('../app')
  const updateManagerPath = require.resolve('../utils/update-manager')
  const previousApp = global.App
  const previousAppModule = require.cache[appPath]
  const previousUpdateManagerModule = require.cache[updateManagerPath]
  let appDefinition

  require.cache[updateManagerPath] = {
    id: updateManagerPath,
    filename: updateManagerPath,
    loaded: true,
    exports: {
      createUpdateController() {
        return updateController
      }
    }
  }
  global.App = (definition) => {
    appDefinition = definition
  }
  delete require.cache[appPath]

  try {
    require(appPath)
  } catch (error) {
    global.App = previousApp
    if (previousAppModule) {
      require.cache[appPath] = previousAppModule
    } else {
      delete require.cache[appPath]
    }
    if (previousUpdateManagerModule) {
      require.cache[updateManagerPath] = previousUpdateManagerModule
    } else {
      delete require.cache[updateManagerPath]
    }
    throw error
  }

  return {
    appDefinition,
    cleanup() {
      global.App = previousApp
      if (previousAppModule) {
        require.cache[appPath] = previousAppModule
      } else {
        delete require.cache[appPath]
      }
      if (previousUpdateManagerModule) {
        require.cache[updateManagerPath] = previousUpdateManagerModule
      } else {
        delete require.cache[updateManagerPath]
      }
    }
  }
}

test('app initializes updates on launch and prompts on show without changing api base url', () => {
  let initCalls = 0
  let promptCalls = 0
  const loaded = loadAppWithUpdateController({
    init() {
      initCalls += 1
    },
    promptIfReady() {
      promptCalls += 1
    }
  })

  try {
    loaded.appDefinition.onLaunch()
    loaded.appDefinition.onShow()

    assert.equal(initCalls, 1)
    assert.equal(promptCalls, 1)
    assert.equal(
      loaded.appDefinition.globalData.apiBaseUrl,
      'https://api.we-folio.dingchenyong.top'
    )
  } finally {
    loaded.cleanup()
  }
})

test('app lifecycle isolates update controller failures', () => {
  const loaded = loadAppWithUpdateController({
    init() {
      throw new Error('init failed')
    },
    promptIfReady() {
      throw new Error('prompt failed')
    }
  })

  try {
    withoutConsoleWarnings(() => {
      assert.doesNotThrow(() => loaded.appDefinition.onLaunch())
      assert.doesNotThrow(() => loaded.appDefinition.onShow())
    })
  } finally {
    loaded.cleanup()
  }
})
