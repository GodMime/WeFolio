const assert = require('node:assert/strict')
const test = require('node:test')

const { normalizeId } = require('../utils/id')

test('normalizes positive ids and rejects invalid ids', () => {
  assert.equal(normalizeId('7'), 7)
  assert.equal(normalizeId(8), 8)
  assert.equal(normalizeId('0'), null)
  assert.equal(normalizeId('-1'), null)
  assert.equal(normalizeId('abc'), null)
})
