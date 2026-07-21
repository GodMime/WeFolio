const TEAM_COMPONENTS = Object.freeze([
  Object.freeze({ type: 'TEAM_PROFILE', name: '团队资料' }),
  Object.freeze({ type: 'CAROUSEL', name: '轮播图' }),
  Object.freeze({ type: 'DIVIDER', name: '分割线' }),
  Object.freeze({ type: 'MEMBER_PORTFOLIO_GRID', name: '双列作品集' }),
  Object.freeze({ type: 'MEMBER_PORTFOLIO_LIST', name: '单列作品集' }),
  Object.freeze({ type: 'TEXT_SECTION', name: '文字说明' }),
  Object.freeze({ type: 'SCHEDULE_QUERY', name: '档期查询' }),
  Object.freeze({ type: 'CONTACT_FORM', name: '预留联系信息' }),
  Object.freeze({ type: 'QR_CONTACT', name: '二维码联系' })
])

const TEAM_COMPONENT_REGISTRATION = Object.freeze({
  TEAM_PROFILE: '../components/team-profile/team-profile',
  CAROUSEL: '../components/carousel/carousel',
  DIVIDER: '../components/divider/divider',
  MEMBER_PORTFOLIO_GRID: '../components/member-portfolio-grid/member-portfolio-grid',
  MEMBER_PORTFOLIO_LIST: '../components/member-portfolio-list/member-portfolio-list',
  TEXT_SECTION: '../components/text-section/text-section',
  SCHEDULE_QUERY: '../components/schedule-query/schedule-query',
  CONTACT_FORM: '../components/contact-form/contact-form',
  QR_CONTACT: '../components/qr-contact/qr-contact'
})

module.exports = {
  TEAM_COMPONENTS,
  TEAM_COMPONENT_REGISTRATION
}
