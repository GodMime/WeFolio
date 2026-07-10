const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const MINIAPP_ROOT = path.join(__dirname, '..')
const NEW_VISITOR_PAGE = '/pages/portfolios/visitor-portfolio/visitor-portfolio'
const NEW_VISITOR_SCHEDULE_PAGE = '/pages/portfolios/visitor-schedule/visitor-schedule'

function read(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function loadCompatPage(relativePath) {
  const source = read(relativePath)
  let pageDefinition
  const redirects = []
  const toasts = []
  const dependencyStub = new Proxy({}, {
    get() {
      return () => ({})
    }
  })

  vm.runInNewContext(source, {
    Page(definition) {
      pageDefinition = definition
    },
    module: { exports: {} },
    exports: {},
    require() {
      return dependencyStub
    },
    wx: {
      redirectTo(options) {
        redirects.push(options.url)
      },
      showToast(options) {
        toasts.push(options)
      }
    }
  }, { filename: relativePath })

  return { pageDefinition, redirects, toasts }
}

function runOnLoad(relativePath, options) {
  const loaded = loadCompatPage(relativePath)
  const context = Object.assign({}, loaded.pageDefinition, {
    data: Object.assign({}, loaded.pageDefinition.data),
    bootstrap() {},
    setData(patch) {
      Object.assign(this.data, patch)
    }
  })

  loaded.pageDefinition.onLoad.call(context, options)
  return loaded.redirects
}

test('old visitor portfolio route forwards shareCode and scene to the new subpackage page', () => {
  assert.deepEqual(
    runOnLoad('pages/visitor-portfolio/visitor-portfolio.js', { shareCode: 'PF001' }),
    [`${NEW_VISITOR_PAGE}?shareCode=PF001`]
  )
  assert.deepEqual(
    runOnLoad('pages/visitor-portfolio/visitor-portfolio.js', { scene: 'PF001' }),
    [`${NEW_VISITOR_PAGE}?scene=PF001`]
  )
})

test('old visitor schedule route forwards all supported launch parameters', () => {
  assert.deepEqual(
    runOnLoad('pages/visitor-schedule/visitor-schedule.js', {
      shareCode: 'PF001',
      scene: 'SCENE001',
      visitorKey: 'VK001'
    }),
    [`${NEW_VISITOR_SCHEDULE_PAGE}?shareCode=PF001&scene=SCENE001&visitorKey=VK001`]
  )
})

test('new visitor schedule consumes scene forwarded by the old route', () => {
  const loaded = loadCompatPage('pages/portfolios/visitor-schedule/visitor-schedule.js')
  const context = {
    data: Object.assign({}, loaded.pageDefinition.data),
    setData(patch) {
      Object.assign(this.data, patch)
    }
  }

  loaded.pageDefinition.onLoad.call(context, { scene: 'PF001', visitorKey: 'VK001' })

  assert.equal(context.data.shareCode, 'PF001')
  assert.equal(context.data.visitorKey, 'VK001')
})

test('old compatibility pages stay lightweight and carry deletion guards', () => {
  const visitorCompatSource = read('pages/visitor-portfolio/visitor-portfolio.js')
  const scheduleCompatSource = read('pages/visitor-schedule/visitor-schedule.js')

  assert.doesNotMatch(visitorCompatSource, /\brequest\b|openVisitorSession|wx\.request/)
  assert.doesNotMatch(scheduleCompatSource, /\brequest\b|openVisitorSession|wx\.request/)
  assert.match(visitorCompatSource, /历史访客作品集分享兼容入口/)
  assert.match(visitorCompatSource, /禁止删除或改作业务页面/)
  assert.match(visitorCompatSource, /禁止请求接口或创建访客会话/)
  assert.match(scheduleCompatSource, /历史访客档期分享兼容入口/)
  assert.match(scheduleCompatSource, /禁止删除或改作业务页面/)
  assert.match(scheduleCompatSource, /禁止请求接口或查询档期/)
})
