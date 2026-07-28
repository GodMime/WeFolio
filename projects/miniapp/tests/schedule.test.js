const assert = require('node:assert/strict')
const test = require('node:test')

const {
  SCHEDULE_STATUS_OPTIONS,
  SLOT_COLOR_OPTIONS,
  buildDefaultSlotEndTime,
  buildScheduleFieldCounters,
  buildScheduleItemPayload,
  buildSlotDefinitionFieldCounters,
  buildSlotDefinitionPayload,
  markMonthSelectedDate,
  normalizeMonthOverview,
  normalizeScheduleOverview,
  normalizeSelectedDateOverview,
  normalizeSlotDefinitions,
  validateScheduleItemPayload,
  validateScheduleItemForm,
  validateSlotDefinitionPayload,
  validateSlotDefinitionForm
} = require('../pages/schedule/utils/schedule')

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
          lunarText: '初十',
          colors: ['#d98200'],
          count: 1
        }
      ]
    },
    selectedDate: {
      date: '2026-06-24',
      summaryText: '1 条档期',
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
  assert.equal(result.month.titleText, '2026 年 6 月')
  assert.equal(result.month.days[0].metaText, '初十')
  assert.equal(result.month.days[0].markerColors[0].style, 'background: #d98200;')
  assert.equal(result.selectedDate.titleText, '06月24日')
  assert.equal(result.selectedDate.lunarTitleText, '农历五月初十')
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

test('normalizes split schedule responses and selected date locally', () => {
  const slotDefinitions = normalizeSlotDefinitions([
    {
      id: 1,
      name: '迎亲档',
      startTime: '07:30',
      endTime: '09:30',
      color: '#d98200',
      status: 'ACTIVE',
      statusText: '启用',
      enabled: true
    }
  ])
  const month = normalizeMonthOverview({
    yearMonth: '2026-06',
    days: [
      {
        date: '2026-06-24',
        dayNumber: 24,
        currentMonth: true,
        colors: ['#d98200'],
        count: 1
      }
    ]
  }, '2026-06-28')
  const selectedMonth = markMonthSelectedDate(month, '2026-06-24')
  const selectedDate = normalizeSelectedDateOverview({
    date: '2026-06-24',
    summaryText: '1 条档期',
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
        statusTone: 'amber'
      }
    ]
  })

  assert.equal(slotDefinitions[0].timeRangeText, '07:30-09:30')
  assert.equal(month.titleText, '2026 年 6 月')
  assert.equal(month.days[0].selected, false)
  assert.equal(selectedMonth.days[0].selected, true)
  assert.match(selectedMonth.days[0].dayClass, /selected/)
  assert.equal(selectedDate.titleText, '06月24日')
  assert.equal(selectedDate.summaryText, '1 条档期')
  assert.equal(selectedDate.schedules[0].statusClass, 'schedule-status amber')
})

test('prefers holiday text for calendar day meta', () => {
  const result = normalizeScheduleOverview({
    month: {
      yearMonth: '2026-06',
      days: [
        {
          date: '2026-06-19',
          dayNumber: 19,
          currentMonth: true,
          selected: false,
          lunarText: '初五',
          holidayText: '端午节'
        }
      ]
    }
  })

  assert.equal(result.month.days[0].metaText, '端午节')
})

test('calculates lunar day meta when backend omits it', () => {
  const result = normalizeScheduleOverview({
    month: {
      yearMonth: '2026-06',
      days: [
        { date: '2026-06-01', dayNumber: 1, currentMonth: true },
        { date: '2026-06-15', dayNumber: 15, currentMonth: true },
        { date: '2026-06-24', dayNumber: 24, currentMonth: true }
      ]
    }
  })

  assert.deepEqual(result.month.days.map((item) => item.metaText), ['十六', '五月', '初十'])
})

test('calculates lunar festival meta when backend omits holiday text', () => {
  const result = normalizeScheduleOverview({
    month: {
      yearMonth: '2026-06',
      days: [
        { date: '2026-06-19', dayNumber: 19, currentMonth: true }
      ]
    }
  })

  assert.equal(result.month.days[0].metaText, '端午节')
})

test('calculates full lunar text for selected date detail', () => {
  const result = normalizeScheduleOverview({
    selectedDate: {
      date: '2026-06-28',
      schedules: []
    }
  })

  assert.equal(result.selectedDate.lunarTitleText, '农历五月十四')
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
  assert.deepEqual(validateSlotDefinitionPayload({
    name: '迎亲档',
    startTime: '07:30',
    endTime: '09:30',
    color: '#d98200',
    status: 'ACTIVE'
  }), { valid: true, message: '' })
  assert.equal(validateSlotDefinitionForm({}).message, '请输入档位名称')
  assert.equal(validateSlotDefinitionForm({
    name: '迎亲档',
    startTime: '09:30',
    endTime: '07:30',
    color: '#d98200'
  }).message, '开始时间必须早于结束时间')
})

test('builds slot end time two hours after start and caps it at the end of day', () => {
  assert.equal(buildDefaultSlotEndTime('10:15'), '12:15')
  assert.equal(buildDefaultSlotEndTime('22:30'), '23:59')
})

test('builds and validates schedule item payloads', () => {
  assert.deepEqual(buildScheduleItemPayload({
    scheduleDate: '2026-06-24',
    slotDefinitionId: '1'
  }), {
    scheduleDate: '2026-06-24',
    slotDefinitionId: 1,
    status: 'TENTATIVE',
    contactName: '',
    contactPhone: '',
    note: ''
  })

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
  assert.deepEqual(validateScheduleItemPayload({
    scheduleDate: '2026-06-24',
    slotDefinitionId: 1,
    status: 'TENTATIVE',
    contactName: '',
    contactPhone: '',
    note: ''
  }), { valid: true, message: '' })
  assert.equal(validateScheduleItemForm({ scheduleDate: '2026-06-24' }).message, '请选择档位定义')
  assert.equal(validateScheduleItemForm({
    scheduleDate: '2026-06-24',
    slotDefinitionId: 1,
    status: 'AVAILABLE'
  }).message, '请选择有效的档期状态')
})

test('exposes schedule form options', () => {
  assert.deepEqual(SCHEDULE_STATUS_OPTIONS.map((item) => item.value), ['TENTATIVE', 'BOOKED', 'REST'])
  assert.deepEqual(SCHEDULE_STATUS_OPTIONS.map((item) => item.text), ['待定', '已约', '休息'])
  assert.equal(SLOT_COLOR_OPTIONS.length, 8)
  assert.deepEqual(SLOT_COLOR_OPTIONS.map(({ name, color }) => ({ name, color })), [
    { name: '琥珀', color: '#c28f4b' },
    { name: '湖蓝', color: '#6f8cb5' },
    { name: '青绿', color: '#5f999b' },
    { name: '玫红', color: '#af7482' },
    { name: '森绿', color: '#738e71' },
    { name: '紫藤', color: '#897dbc' },
    { name: '珊瑚', color: '#c87a65' },
    { name: '墨蓝', color: '#667b96' }
  ])
  assert.equal(SLOT_COLOR_OPTIONS[0].swatchStyle, 'background: #c28f4b;')
  assert.equal('choiceStyle' in SLOT_COLOR_OPTIONS[0], false)
})
