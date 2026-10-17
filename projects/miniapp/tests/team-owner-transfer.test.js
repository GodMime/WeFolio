const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')
const teams = require('../utils/teams')

// 在独立页面环境中执行真实点击处理函数，记录确认框和提交动作。
function loadPage(userStatus) {
  const modals = []
  const actions = []
  let page
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/team-maintenance/team-maintenance.js'), 'utf8'), {
    require(name) { return name.endsWith('/teams') ? teams : {} },
    Page(value) { page = value },
    wx: { showModal(options) { modals.push(options); options.success({ confirm: true }) } }
  })
  page.data.detail = teams.normalizeTeamDetail({
    team: { canManageMembers: true },
    members: [{ memberId: 31, role: 'MANAGER', joinStatus: 'JOINED', userStatus }]
  })
  page.handleConfirmedMemberAction = (...args) => actions.push(args)
  return { page, modals, actions }
}

const EVENT = { currentTarget: { dataset: { memberId: 31 } } }

for (const status of ['DISABLED', 'MISSING', '', undefined]) {
  test(`unavailable account ${status} cannot receive ownership but can still be removed`, () => {
    const { page, modals, actions } = loadPage(status)
    page.handleTransferOwner(EVENT)
    assert.equal(modals.length, 0)
    assert.equal(actions.length, 0)
    assert.equal(page.data.detail.members[0].canReceiveOwnership, false)

    page.handleRemoveMember(EVENT)
    assert.equal(modals.length, 1)
    assert.deepEqual(actions, [['/api/mine/teams/remove-member', 31, '已移除']])
  })
}

test('active account keeps the ownership transfer confirmation and request', () => {
  const { page, modals, actions } = loadPage('ACTIVE')
  assert.equal(page.data.detail.members[0].canReceiveOwnership, true)
  page.handleTransferOwner(EVENT)
  assert.equal(modals.length, 1)
  assert.deepEqual(actions, [['/api/mine/teams/transfer-owner', 31, '已转让']])
})

test('only the transfer button is hidden for unavailable accounts', () => {
  const template = fs.readFileSync(path.join(__dirname, '../pages/team-maintenance/team-maintenance.wxml'), 'utf8')
  const transfer = template.match(/<button\b[^>]*bindtap="handleTransferOwner"[^>]*>/)[0]
  const remove = template.match(/<button\b[^>]*bindtap="handleRemoveMember"[^>]*>/)[0]
  assert.match(transfer, /wx:if="\{\{item.canReceiveOwnership\}\}"/)
  assert.doesNotMatch(remove, /wx:if/)
})
