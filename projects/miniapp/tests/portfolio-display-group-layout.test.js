const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildDisplayGroupWorkScrollHeight
} = require('../pages/portfolios/utils/display-group-layout')

test('builds content-sized display group work height with a scroll cap', () => {
  assert.equal(buildDisplayGroupWorkScrollHeight([]), 0)
  assert.equal(buildDisplayGroupWorkScrollHeight([{}]), 96)
  assert.equal(buildDisplayGroupWorkScrollHeight([{}, {}, {}]), 312)
  assert.equal(buildDisplayGroupWorkScrollHeight([{}, {}, {}, {}]), 360)
  assert.equal(buildDisplayGroupWorkScrollHeight(10), 360)
})
