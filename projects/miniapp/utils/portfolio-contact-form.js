const {
  COMPONENT_TYPES,
  CONTACT_FORM_DISPLAY_MODES
} = require('./portfolios')

function createActiveContactFormComponent() {
  return {
    componentKey: '',
    componentType: COMPONENT_TYPES.CONTACT_FORM,
    contactForm: {
      title: '',
      description: '',
      displayMode: CONTACT_FORM_DISPLAY_MODES.MODAL_FORM,
      fields: []
    }
  }
}

function findContactFormComponent(portfolio = {}, componentKey = '') {
  const targetKey = String(componentKey || '')
  return (Array.isArray(portfolio.components) ? portfolio.components : [])
    .find((component) => {
      return component &&
        component.componentKey === targetKey &&
        component.componentType === COMPONENT_TYPES.CONTACT_FORM
    }) || null
}

module.exports = {
  createActiveContactFormComponent,
  findContactFormComponent
}
