const assert = require('node:assert/strict')
const test = require('node:test')

const {
  fetchMaintainableTeams,
  fetchTeamPortfolioList,
  publishTeamPortfolio,
  resolveTeamCreateRoute
} = require('../pages/portfolios/utils/team-portfolio-list.js')

test('shared team portfolio list utility normalizes list and maintainable teams', async () => {
  const requests = []
  const request = async (options) => {
    requests.push(options)
    if (options.url.endsWith('/maintainable-teams')) {
      return [
        { teamId: 7, teamName: '甲', currentRole: 'OWNER' },
        { teamId: 8, teamName: '乙', currentRole: 'MEMBER' }
      ]
    }
    return [{ portfolioId: 9, teamId: 7, title: '团队作品集', canMaintain: true }]
  }

  assert.deepEqual(await fetchTeamPortfolioList(request), [{
    portfolioId: 9,
    shareCode: '',
    title: '团队作品集',
    coverUrl: '',
    teamId: 7,
    teamName: '',
    currentRole: '',
    canMaintain: true,
    canShare: false,
    publicationStatus: '',
    draftRevision: 0,
    publishedRevision: 0,
    updatedAt: ''
  }])
  assert.deepEqual(await fetchMaintainableTeams(request), [
    { teamId: 7, teamName: '甲', avatarUrl: '', currentRole: 'OWNER' }
  ])
  assert.deepEqual(resolveTeamCreateRoute([{ teamId: 7, currentRole: 'OWNER' }]), {
    action: 'CREATE',
    teamId: 7,
    url: ''
  })
  assert.deepEqual(requests.map((item) => item.url), [
    '/api/mine/team-portfolios',
    '/api/mine/team-portfolios/maintainable-teams'
  ])
})

test('shared team portfolio list utility publishes the selected draft revision', async () => {
  const requests = []
  const request = async (options) => {
    requests.push(options)
    return { publicationStatus: 'PUBLISHED', publishedRevision: 4 }
  }

  assert.deepEqual(await publishTeamPortfolio(request, 33, 4, 'team-publish-list-1'), {
    publicationStatus: 'PUBLISHED',
    publishedRevision: 4
  })
  assert.deepEqual(requests, [{
    url: '/api/mine/team-portfolios/33/publish',
    method: 'POST',
    data: {
      draftRevision: 4,
      idempotencyKey: 'team-publish-list-1'
    }
  }])
  await assert.rejects(
    () => publishTeamPortfolio(request, 33, 4, ''),
    /幂等键/
  )
})
