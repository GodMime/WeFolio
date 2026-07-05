const assert = require('node:assert/strict')
const test = require('node:test')

const {
  PUBLISH_DISCLAIMER_CONTENT,
  PUBLISH_DISCLAIMER_TITLE,
  confirmPortfolioPublishDisclaimer
} = require('../utils/portfolio-publish-disclaimer')

test('portfolio publish disclaimer confirms only when maintainer agrees', async () => {
  let modalOptions = null
  const confirmed = await confirmPortfolioPublishDisclaimer({
    showModal(options) {
      modalOptions = options
      options.success({ confirm: true })
    }
  })

  assert.equal(confirmed, true)
  assert.equal(modalOptions.title, PUBLISH_DISCLAIMER_TITLE)
  assert.match(modalOptions.content, /肖像/)
  assert.match(modalOptions.content, /侵权/)
  assert.equal(modalOptions.content, PUBLISH_DISCLAIMER_CONTENT)
  assert.equal(modalOptions.confirmText, '确认发布')
  assert.equal(Array.from(modalOptions.confirmText).length <= 4, true)
  assert.equal(modalOptions.cancelText, '取消')
})

test('portfolio publish disclaimer cancels when maintainer declines or modal fails', async () => {
  const canceled = await confirmPortfolioPublishDisclaimer({
    showModal(options) {
      options.success({ confirm: false })
    }
  })
  const failed = await confirmPortfolioPublishDisclaimer({
    showModal(options) {
      options.fail(new Error('modal unavailable'))
    }
  })

  assert.equal(canceled, false)
  assert.equal(failed, false)
})
