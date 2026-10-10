package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.miniappcode.PortfolioMiniappCodeApplicationService;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 高级维护入口在普通 DTO 转换前的严格请求和维护数据隔离测试。 */
@ExtendWith(MockitoExtension.class)
class AdvancedPortfolioApplicationServiceTest {
    /** 当前登录者。 */
    private static final long USER_ID = 7L;
    /** 高级个人作品集。 */
    private static final long PORTFOLIO_ID = 11L;
    /** 固定高级模板。 */
    private static final String ADVANCED = PortfolioTemplateTypeDict.ADVANCED.getCode();
    /** 合法空配置，用来单独验证 HTTP 请求元数据类型。 */
    private static final String CONFIG = "{\"schemaVersion\":\"standard-personal-v1\",\"components\":[]}";
    /** 个人普通用例。 */
    @Mock private MinePortfolioService personal;
    /** 团队普通用例。 */
    @Mock private MineTeamPortfolioService team;
    /** 真实归属查询。 */
    @Mock private PortfolioEntityMapper portfolios;
    /** 团队权限。 */
    @Mock private TeamPortfolioAccessService access;
    /** 团队素材票据。 */
    @Mock private TeamPortfolioAssetService assets;
    /** 团队来源查询。 */
    @Mock private AdvancedPortfolioSourceService sources;
    /** 小程序码。 */
    @Mock private PortfolioMiniappCodeApplicationService miniappCodes;

    /** 安装当前维护者身份。 */
    @BeforeEach void login() { AuthContextHolder.set(new AuthContext(USER_ID, "test-maintainer")); }
    /** 清理线程身份。 */
    @AfterEach void cleanup() { AuthContextHolder.clear(); }

    /** 字符串 JSON 不能被宽松转换器当作 aiContext 对象。 */
    @Test void rejectsStringEncodedContextBeforeOrdinarySave() {
        arrangePersonal();
        assertThatThrownBy(() -> service().saveDraft(PORTFOLIO_ID,
                "{\"config\":" + CONFIG + ",\"aiContext\":\"{}\"}"))
                .isInstanceOf(BusinessException.class).hasMessage(AdvancedPortfolioMessage.INVALID_REQUEST);
        verify(personal, never()).saveDraft(any(), any(), any(), any());
    }

    /** 新发布请求不接受数字字符串或超出 Integer 的修订。 */
    @Test void rejectsCoercedPublishRevisionAndKey() {
        arrangePersonal();
        for (String raw : new String[]{"{\"draftRevision\":\"1\",\"idempotencyKey\":\"p\"}",
                "{\"draftRevision\":2147483648,\"idempotencyKey\":\"p\"}",
                "{\"draftRevision\":1,\"idempotencyKey\":100}"}) {
            assertThatThrownBy(() -> service().publish(PORTFOLIO_ID, raw)).isInstanceOf(BusinessException.class);
        }
        verify(personal, never()).publish(any(), any(), any());
    }

    /** 停用或不支持 schema 的个人高级数据不能由独立入口绕过门禁。 */
    @Test void rejectsInactiveAndUnsupportedSchemaBeforeDelegation() {
        PortfolioEntity portfolio = entity();
        when(portfolios.selectById(PORTFOLIO_ID)).thenReturn(portfolio);
        portfolio.setStatus(PortfolioStatusDict.DISABLED.getCode());
        assertThatThrownBy(() -> service().getDetail(PORTFOLIO_ID)).isInstanceOf(BusinessException.class);
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode()); portfolio.setSchemaVersion("future-personal-v2");
        assertThatThrownBy(() -> service().preview(PORTFOLIO_ID, false)).isInstanceOf(BusinessException.class);
        verify(personal, never()).requireOwnedPersonal(any(), any());
    }

    /** 最终手动保存使用普通 DTO，元数据和原始请求另交最终事务处理。 */
    @Test void forwardsContextToFinalSaveAndKeepsItOutOfOrdinaryConfig() {
        PortfolioEntity portfolio = arrangePersonal();
        MinePortfolioDetailResponse detail = new MinePortfolioDetailResponse(); detail.setPortfolioId(PORTFOLIO_ID);
        when(personal.saveDraft(eq(PORTFOLIO_ID), any(), eq(ADVANCED), any())).thenReturn(detail);
        String raw = "{\"config\":" + CONFIG + ",\"clientRevision\":0,\"idempotencyKey\":\"s\","
                + "\"aiContext\":{\"version\":1,\"nextSourceNo\":1,\"sources\":[]},\"aiPrompt\":\"新描述\"}";
        service().saveDraft(PORTFOLIO_ID, raw);
        ArgumentCaptor<MinePortfolioDraftSaveRequest> request = ArgumentCaptor.forClass(MinePortfolioDraftSaveRequest.class);
        ArgumentCaptor<AdvancedPortfolioSaveMetadata> metadata = ArgumentCaptor.forClass(AdvancedPortfolioSaveMetadata.class);
        verify(personal).saveDraft(eq(PORTFOLIO_ID), request.capture(), eq(ADVANCED), metadata.capture());
        assertThat(request.getValue().getClientRevision()).isZero();
        assertThat(metadata.getValue().contextPresent()).isTrue();
        assertThat(metadata.getValue().prompt()).isEqualTo("新描述");
        assertThat(JSONObject.parseObject(metadata.getValue().fingerprint()).getJSONObject("config")).doesNotContainKeys("aiContext", "aiPrompt");
        assertThat(portfolio.getAiPrompt()).isNull();
    }

    /** 维护详情追加 AI 信息，预览继续只返回原 DTO。 */
    @Test void maintenanceDetailContainsContextButPreviewDoesNot() {
        PortfolioEntity portfolio = arrangePersonal(); portfolio.setAiPrompt("仅维护者可读");
        portfolio.setAiContextJson("{\"version\":1,\"nextSourceNo\":1,\"sources\":[]}");
        MinePortfolioDetailResponse detail = new MinePortfolioDetailResponse(); detail.setPortfolioId(PORTFOLIO_ID);
        when(personal.getDetail(PORTFOLIO_ID, ADVANCED)).thenReturn(detail);
        when(personal.preview(PORTFOLIO_ID, ADVANCED)).thenReturn(detail);
        assertThat(service().getDetail(PORTFOLIO_ID).getString("aiPrompt")).isEqualTo("仅维护者可读");
        assertThat(service().preview(PORTFOLIO_ID, false)).isSameAs(detail);
        assertThat(JSONObject.parseObject(JSONObject.toJSONString(detail))).doesNotContainKeys("aiContext", "aiPrompt");
    }

    /** 归属 ID 超过 Long 上限时产生受控参数错误。 */
    @Test void rejectsOverflowOwnerId() {
        assertThatThrownBy(() -> service().create("{\"ownerType\":\"USER\",\"ownerId\":\"9999999999999999999\"}"))
                .isInstanceOf(BusinessException.class).hasMessage(AdvancedPortfolioMessage.INVALID_REQUEST);
    }

    /** 安排存在且属于当前人的高级实体。 */
    private PortfolioEntity arrangePersonal() {
        PortfolioEntity portfolio = entity(); when(portfolios.selectById(PORTFOLIO_ID)).thenReturn(portfolio);
        when(personal.requireOwnedPersonal(PORTFOLIO_ID, ADVANCED)).thenReturn(portfolio); return portfolio;
    }
    /** 构造独立高级用例服务。 */
    private AdvancedPortfolioApplicationService service() {
        return new AdvancedPortfolioApplicationService(personal, team, portfolios, access, assets, sources, miniappCodes);
    }
    /** 最小有效高级个人实体。 */
    private PortfolioEntity entity() {
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setId(PORTFOLIO_ID);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode()); portfolio.setOwnerId(USER_ID);
        portfolio.setTemplateType(ADVANCED); portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode()); portfolio.setDeleted(0L);
        portfolio.setSchemaVersion("standard-personal-v1"); return portfolio;
    }
}
