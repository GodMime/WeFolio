const assert = require('node:assert/strict')
const test = require('node:test')

const {
  SCHEDULE_STATUS_OPTIONS,
  SLOT_COLOR_OPTIONS,
  buildScheduleFieldCounters,
  buildScheduleItemPayload,
  buildSlotDefinitionFieldCounters,
  buildSlotDefinitionPayload,
  normalizeScheduleOverview,
  validateScheduleItemForm,
  validateSlotDefinitionForm
} = require('../utils/schedule')

test('normalizes schedule overview for page rendering', () => {
  const result = normalizeScheduleOverview({
    slotDefinitions: [
      {
        id: 1,
        name: '迎亲档',
        startTime: '07:30',
        endTime: '09:30',
        color: '#d98200',
        sortOrder: 20,
        status: 'ACTIVE',
        statusText: '启用',
        enabled: true
      }
    ],
    month: {
      yearMonth: '2026-06',
      days: [
        {
          date: '2026-06-24',
          dayNumber: 24,
          currentMonth: true,
          selected: true,
          colors: ['#d98200'],
          count: 1
        }
      ]
    },
    selectedDate: {
      date: '2026-06-24',
      summaryText: '1 条档期，0 个可约',
      schedules: [
        {
          id: 9,
          scheduleDate: '2026-06-24',
          slotDefinitionId: 1,
          slotName: '迎亲档',
          startTime: '07:30',
          endTime: '09:30',
          color: '#d98200',
          status: 'TENTATIVE',
          statusText: '待定',
          statusTone: 'amber',
          contactName: '陈女士',
          contactPhone: '13800002026',
          note: '今晚回电'
        }
      ]
    }
  })

  assert.equal(result.slotDefinitions[0].timeRangeText, '07:30-09:30')
  assert.equal(result.slotDefinitions[0].statusClass, 'slot-status active')
  assert.equal(result.slotDefinitions[0].colorStyle, 'background: #d98200;')
  assert.equal(Object.hasOwn(result.slotDefinitions[0], 'sortOrder'), false)
  assert.equal(result.month.titleText, '2026年06月')
  assert.equal(result.month.days[0].markerColors[0].style, 'background: #d98200;')
  assert.equal(result.selectedDate.titleText, '06月24日')
  assert.equal(result.selectedDate.empty, false)
  assert.equal(result.selectedDate.schedules[0].statusClass, 'schedule-status amber')
  assert.equal(result.selectedDate.schedules[0].contactText, '陈女士 · 13800002026')
})

test('normalizes empty schedule overview with safe defaults', () => {
  const result = normalizeScheduleOverview({})

  assert.deepEqual(result.slotDefinitions, [])
  assert.equal(result.month.titleText, '本月')
  assert.deepEqual(result.month.days, [])
  assert.equal(result.selectedDate.summaryText, '暂无档期')
  assert.equal(result.selectedDate.empty, true)
})

test('builds and validates slot definition payloads', () => {
  assert.deepEqual(buildSlotDefinitionPayload({
    name: ' 迎亲档 ',
    startTime: '07:30',
    endTime: '09:30',
    color: '#d98200',
    sortOrder: '20'
  }), {
    name: '迎亲档',
    startTime: '07:30',
    endTime: '09:30',
    color: '#d98200',
    status: 'ACTIVE'
  })
  assert.deepEqual(buildSlotDefinitionFieldCounters({
    name: '中午档'
  }), {
    name: '3 / 30'
  })

  assert.deepEqual(validateSlotDefinitionForm({
    name: '迎亲档',
    startTime: '07:30',
    endTime: '09:30',
    color: '#d98200'
  }), { valid: true, message: '' })
  assert.equal(validateSlotDefinitionForm({}).message, '请输入档位名称')
  assert.equal(validateSlotDefinitionForm({
    name: '迎亲档',
    startTime: '09:30',
    endTime: '07:30',
    color: '#d98200'
  }).message, '开始时间必须早于结束时间')
})

test('builds and validates schedule item payloads', () => {
  assert.deepEqual(buildScheduleItemPayload({
    scheduleId: '9',
    scheduleDate: '2026-06-24',
    slotDefinitionId: '1',
    status: 'TENTATIVE',
    contactName: ' 陈女士 ',
    contactPhone: ' 13800002026 ',
    note: ' 今晚回电 '
  }), {
    scheduleId: 9,
    scheduleDate: '2026-06-24',
    slotDefinitionId: 1,
    status: 'TENTATIVE',
    contactName: '陈女士',
    contactPhone: '13800002026',
    note: '今晚回电'
  })

  assert.deepEqual(buildScheduleFieldCounters({
    contactName: '陈女士',
    contactPhone: '13800002026',
    note: '今晚回电'
  }), {
    contactName: '3 / 50',
    contactPhone: '11 / 50',
    note: '4 / 1000'
  })
  assert.deepEqual(validateScheduleItemForm({
    scheduleDate: '2026-06-24',
    slotDefinitionId: 1,
    status: 'TENTATIVE'
  }), { valid: true, message: '' })
  assert.equal(validateScheduleItemForm({ scheduleDate: '2026-06-24' }).message, '请选择档位定义')
})

test('exposes schedule form options', () => {
  assert.deepEqual(SCHEDULE_STATUS_OPTIONS.map((item) => item.value), ['AVAILABLE', 'TENTATIVE', 'BOOKED', 'REST'])
  assert.equal(SLOT_COLOR_OPTIONS.length, 8)
  assert.equal(SLOT_COLOR_OPTIONS[0].color, '#d98200')
  assert.equal(SLOT_COLOR_OPTIONS[0].name, '琥珀')
  assert.match(SLOT_COLOR_OPTIONS[0].swatchStyle, /#d98200/)
  assert.match(SLOT_COLOR_OPTIONS[0].choiceStyle, /border-color:/)
  assert.match(SLOT_COLOR_OPTIONS[0].choiceStyle, /background:/)
})
