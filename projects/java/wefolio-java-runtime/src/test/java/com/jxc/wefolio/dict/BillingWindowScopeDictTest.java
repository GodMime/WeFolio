package com.jxc.wefolio.dict;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访客扣费窗口作用域字典测试。
 */
class BillingWindowScopeDictTest {

    /** 编码查询必须只接受作品集和作品作用域。 */
    @Test
    void fromCodeReturnsKnownScopesAndRejectsUnknownValues() {
        assertThat(BillingWindowScopeDict.fromCode("PORTFOLIO"))
                .contains(BillingWindowScopeDict.PORTFOLIO);
        assertThat(BillingWindowScopeDict.fromCode("WORK"))
                .contains(BillingWindowScopeDict.WORK);
        assertThat(BillingWindowScopeDict.fromCode(null)).isEmpty();
        assertThat(BillingWindowScopeDict.fromCode("VISITOR_WORK")).isEmpty();
    }

    /** 所有访客积分场景必须映射到各自唯一作用域。 */
    @Test
    void fromSceneCodeMapsEveryBillableVisitorScene() {
        assertThat(BillingWindowScopeDict.fromSceneCode(
                PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode()))
                .contains(BillingWindowScopeDict.PORTFOLIO);
        assertThat(BillingWindowScopeDict.fromSceneCode(
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode()))
                .contains(BillingWindowScopeDict.WORK);
        assertThat(BillingWindowScopeDict.fromSceneCode(
                PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode()))
                .contains(BillingWindowScopeDict.WORK);
        assertThat(BillingWindowScopeDict.fromSceneCode(
                PointSceneCodeDict.QUERY_PORTFOLIO_SCHEDULE.getCode()))
                .contains(BillingWindowScopeDict.PORTFOLIO);
        assertThat(BillingWindowScopeDict.fromSceneCode(
                PointSceneCodeDict.SUBMIT_CONTACT_LEAD.getCode()))
                .contains(BillingWindowScopeDict.PORTFOLIO);
        assertThat(BillingWindowScopeDict.fromSceneCode(null)).isEmpty();
        assertThat(BillingWindowScopeDict.fromSceneCode(
                PointSceneCodeDict.UPLOAD_IMAGE.getCode())).isEmpty();
    }
}
