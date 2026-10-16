package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.function.Supplier;
import static com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceProtocol.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无台账签名回执与保存行为验证，不连接真实数据库或 COS。 */
class PortfolioAiReferenceServiceTest {
    /** 仅用于测试签名，以便构造升级前不带大小字段的真实旧票据。 */
    private static final String TEST_SECRET="test-reference-secret-at-least-32-bytes", SIGN_ALGORITHM="HmacSHA256";
    /** 真实配置对象确保密钥刷新不会被 mock 掩盖。 */
    private PortfolioAiProperties properties;
    /** 外部依赖全部替换为内存桩。 */
    private CosService cos;
    private PortfolioAiReferenceStorage storage;
    private JdbcTemplate jdbc;
    private PortfolioAiReferenceService service;
    /** 测试身份、当前对象和内容版本。 */
    private String key;
    private long modified;

    /** 当前用户与 owner 一致，默认没有已保存引用。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        properties=new PortfolioAiProperties(); properties.setReferenceEnabled(true);properties.setReferenceTokenSecret(TEST_SECRET);
        cos=mock(CosService.class);storage=mock(PortfolioAiReferenceStorage.class);jdbc=mock(JdbcTemplate.class);
        UserEntityMapper users=mock(UserEntityMapper.class);UserEntity user=new UserEntity();user.setId(7L);user.setStatus(UserStatusDict.ACTIVE.getCode());user.setUniqueCode("WFTEST");when(users.selectById(7L)).thenReturn(user);
        PortfolioAiReferenceLock lock=mock(PortfolioAiReferenceLock.class);
        when(lock.execute(anyString(),anyLong(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(2)).get());
        when(jdbc.query(anyString(),any(RowMapper.class),eq("USER"),eq(7L))).thenReturn(List.of());
        service=new PortfolioAiReferenceService(properties,cos,storage,users,mock(PortfolioEntityMapper.class),mock(TeamPortfolioAccessService.class),jdbc,lock,mock(PortfolioAiReferenceUploadQuota.class));
        AuthContextHolder.set(new AuthContext(7L,"test"));modified=System.currentTimeMillis();
        when(cos.createPrivatePostUploadTicket(anyString(),anyString(),anyLong(),any(LocalDateTime.class),any(ZoneId.class)))
                .thenAnswer(call->{key=call.getArgument(0);return new CosService.PostUploadTicket("https://upload.test",key,call.getArgument(1),call.getArgument(2),LocalDateTime.now(),Map.of("key",key));});
        when(storage.head(anyString())).thenAnswer(call->new CosService.VersionedObjectHead("image/png",100,"etag-v1",modified));
    }
    /** 不将维护者上下文泄漏到其它测试。 */
    @AfterEach void clear() {AuthContextHolder.clear();}

    /** 完整签票、确认、保存后只存已签名的媒体信息，不保存任何凭证或 URL。 */
    @Test void confirmedReceiptCanBeSavedWithoutPersistingCredentials() {
        Map<String,Object> item=confirmed();ArrayNode input=input(item,"A1");
        String json=service.resolveForSave("USER",7,input,null,3);
        assertThat(json).contains(key,"\"nextReferenceNo\":3").doesNotContain(TOKEN,"https://");
        assertThat(service.maintenance(json,"USER",7)).containsKey("aiReferenceAttachments");
        Map<?,?> display=(Map<?,?>)((List<?>)service.maintenance(json,"USER",7).get("aiReferenceAttachments")).getFirst();
        assertThat(display.containsKey(KEY)).isFalse();assertThat(display.containsKey(CONTENT)).isFalse();
    }

    /** 篡改回执不能成为文件事实来源。 */
    @Test void tamperedReceiptIsRejected() {
        Map<String,Object> item=confirmed();ArrayNode input=input(item,"A1");
        ((ObjectNode)input.get(0)).put(TOKEN,"x"+item.get(TOKEN));
        assertThatThrownBy(()->service.resolveForSave("USER",7,input,null,2)).isInstanceOf(BusinessException.class);
    }

    /** 完整媒体声明仍保留两次 HEAD 身份复核，对象变化时不签回执。 */
    @Test void changedContentDuringConfirmationIsRejected() {
        Map<String,Object> upload=ticket();
        ObjectNode request=completionRequest(upload).set("mediaInfo",imageMediaInfo());
        when(storage.head(anyString())).thenReturn(new CosService.VersionedObjectHead("image/png",100,"first",modified),new CosService.VersionedObjectHead("image/png",100,"changed",modified+1));
        assertThatThrownBy(()->service.complete(request.toString()))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiReferenceMessage.UNAVAILABLE);
    }

    /** 前端识别值进入回执与持久字段，确认阶段只发 HEAD，不下载或转发原件。 */
    @Test void clientImageInfoIsSignedAndSavedWithoutDownloading() {
        Map<String,Object> upload=ticket();ObjectNode request=completionRequest(upload).set("mediaInfo",imageMediaInfo());
        clearInvocations(storage,cos);
        Map<String,Object> item=service.complete(request.toString());
        assertThat(item).containsEntry(MIME,"image/png").containsEntry(SIZE,100L).containsEntry(WIDTH,640).containsEntry(HEIGHT,320).containsEntry(DURATION,null);
        assertOnlyHeadDuringConfirmation();
        ObjectNode saved=stored(service.resolveForSave("USER",7,input(item,"A1"),null,2));
        assertThat(saved.path(ITEMS).get(0).path(WIDTH).asInt()).isEqualTo(640);
        assertThat(saved.path(ITEMS).get(0).path(HEIGHT).asInt()).isEqualTo(320);
        assertThat(saved.toString()).doesNotContain("codec","mediaInfo");
    }

    /** 视频采用客户端的实际时长和尺寸，编码只校验声明，不触发服务端解码。 */
    @Test void clientHevcInfoUsesExactValuesWithoutDownloading() {
        Map<String,Object> upload=videoTicket();
        when(storage.head(anyString())).thenAnswer(call->new CosService.VersionedObjectHead("video/mp4",100,"etag-v1",modified));
        ObjectNode metadata=NODES.objectNode().put(MIME,"video/mp4").put("codec","H.265").put(SIZE,100)
                .put(WIDTH,888).put(HEIGHT,1920).put(DURATION,41029);
        clearInvocations(storage,cos);
        Map<String,Object> item=service.complete(completionRequest(upload).set("mediaInfo",metadata).toString());
        assertThat(item).containsEntry(MEDIA,VIDEO).containsEntry(WIDTH,888).containsEntry(HEIGHT,1920).containsEntry(DURATION,41029L);
        assertOnlyHeadDuringConfirmation();
    }

    /** 缺失、空值、空对象或错误类型的媒体信息明确拒绝，不生成尺寸或时长占位。 */
    @Test void rejectsIncompleteMediaInfoForImagesAndVideosWithoutDownloading() {
        for(boolean video:new boolean[]{false,true}) {
            Map<String,Object> upload=video?videoTicket():ticket();String mime=video?"video/mp4":"image/png";
            when(storage.head(anyString())).thenAnswer(call->new CosService.VersionedObjectHead(mime,100,"etag-v1",modified));
            for(JsonNode value:new JsonNode[]{null,NODES.nullNode(),NODES.objectNode(),NODES.arrayNode(),NODES.textNode("")}) {
                ObjectNode request=completionRequest(upload);if(value!=null)request.set("mediaInfo",value);
                clearInvocations(storage,cos);
                assertThatThrownBy(()->service.complete(request.toString())).isInstanceOf(BusinessException.class)
                        .hasMessage(PortfolioAiReferenceMessage.INVALID);
                verify(storage).head(key);verifyNoMoreInteractions(storage);verifyNoInteractions(cos);
            }
        }
    }

    /** 旧票据没有声明大小时明确拒绝，即使签名和媒体声明均正确也不使用 HEAD 兜底。 */
    @Test void rejectsLegacyUploadTokenWithoutSize() throws Exception {
        Map<String,Object> upload=ticket();String token=legacyUploadToken(upload.get(UPLOAD_TOKEN).toString());
        ObjectNode request=NODES.objectNode().put(UPLOAD_TOKEN,token).set("mediaInfo",imageMediaInfo());
        clearInvocations(storage,cos);
        assertThatThrownBy(()->service.complete(request.toString())).isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioAiReferenceMessage.INVALID);
        verify(storage).head(key);verifyNoMoreInteractions(storage);verifyNoInteractions(cos);
    }

    /** 客户端媒体声明不能把另一个归属的票据变成当前用户的附件。 */
    @Test void anotherOwnerCannotConfirmWithOrWithoutMediaInfo() {
        for(boolean withMediaInfo:new boolean[]{false,true}) {
            AuthContextHolder.set(new AuthContext(7L,"test"));Map<String,Object> upload=ticket();
            ObjectNode request=completionRequest(upload);if(withMediaInfo)request.set("mediaInfo",imageMediaInfo());
            AuthContextHolder.set(new AuthContext(8L,"test"));clearInvocations(storage,cos);
            assertThatThrownBy(()->service.complete(request.toString())).isInstanceOf(BusinessException.class)
                    .hasMessage(PortfolioAiReferenceMessage.UNAVAILABLE);
            verifyNoInteractions(storage,cos);
        }
    }

    /** 大小同时绑定上传票据与当前对象，媒体声明不得替代 HEAD 身份检查。 */
    @Test void uploadedSizeMustMatchTicketAndClientDeclaration() {
        Map<String,Object> upload=ticket();ObjectNode request=completionRequest(upload).set("mediaInfo",imageMediaInfo().put(SIZE,101));
        assertThatThrownBy(()->service.complete(request.toString())).isInstanceOf(BusinessException.class);
        when(storage.head(anyString())).thenReturn(new CosService.VersionedObjectHead("image/png",101,"etag-v1",modified));
        assertThatThrownBy(()->service.complete(request.toString())).isInstanceOf(BusinessException.class);
    }

    /** 签名错误不会因为附带看似有效的前端媒体信息而被绕过。 */
    @Test void clientMediaInfoDoesNotBypassUploadTokenSignature() {
        Map<String,Object> upload=ticket();ObjectNode request=completionRequest(upload).set("mediaInfo",imageMediaInfo());
        request.put(UPLOAD_TOKEN,"x"+upload.get(UPLOAD_TOKEN));clearInvocations(storage,cos);
        assertThatThrownBy(()->service.complete(request.toString())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage,cos);
    }

    /** 清空保存保留已使用编号高水位，不能把旧 A1 重新分给新文件。 */
    @Test void clearedNumberCannotBeReused() {
        Map<String,Object> item=confirmed();String previous="{\"version\":1,\"nextReferenceNo\":3,\"items\":[]}";
        assertThatThrownBy(()->service.resolveForSave("USER",7,input(item,"A1"),previous,3)).isInstanceOf(BusinessException.class);
        assertThat(service.resolveForSave("USER",7,NODES.arrayNode(),previous,null)).contains("\"nextReferenceNo\":3");
    }

    /** 幂等比较不受 token 刷新或 token 过期影响，但说明变化改变业务投影。 */
    @Test void businessProjectionExcludesTransientTokens() {
        Map<String,Object> item=confirmed();ArrayNode first=input(item,"A1"),second=first.deepCopy();
        ((ObjectNode)second.get(0)).put(TOKEN,"expired-and-refreshed-token");
        assertThat(PortfolioAiReferenceService.normalizeBusinessProjection(first)).isEqualTo(PortfolioAiReferenceService.normalizeBusinessProjection(second));
        ((ObjectNode)second.get(0)).put(NOTE,"另一种风格");
        assertThat(PortfolioAiReferenceService.normalizeBusinessProjection(first)).isNotEqualTo(PortfolioAiReferenceService.normalizeBusinessProjection(second));
    }

    /** 即时清理不删除上传缓冲内原件，即使已从保存清单移除。 */
    @Test void cleanupKeepsUploadBuffer() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);
        service.cleanupRemoved("USER",7,json,null);
        verify(storage,never()).delete(anyString());
    }

    /** 即时删除失败只留下待扫描对象，不把已经成功的保存改成失败。 */
    @Test void cleanupFailureDoesNotEscape() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);modified-=WINDOW_MS+1;
        doThrow(new IllegalStateException("network")).when(storage).delete(anyString());
        assertThatCode(()->service.cleanupRemoved("USER",7,json,null)).doesNotThrowAnyException();
        verify(storage).delete(key);
    }

    /** 过期历史记录仍可显示，但不签发可重新绑定文件的回执。 */
    @Test void unavailableHistoricalAttachmentRemainsReadable() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);
        ObjectNode stored=PortfolioAiReferenceProtocol.stored(json);((ObjectNode)stored.path(ITEMS).get(0).path(CONTENT)).put(MODIFIED,System.currentTimeMillis()-WINDOW_MS-1);
        Map<String,Object> item=service.taskResponse(stored.toString(),"USER",7).getFirst();
        assertThat(item).containsEntry("available",false).doesNotContainKey(TOKEN);
    }

    /** COS 短暂失败必须让查询可重试，不能把成功附件降级为永久不可用。 */
    @Test void transientHeadFailureDoesNotInvalidateAttachment() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);
        when(storage.head(anyString())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->service.taskResponse(json,"USER",7))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiReferenceMessage.STORAGE);
    }

    /** 引用查询故障不能解释成没有保护引用。 */
    @Test @SuppressWarnings("unchecked") void failedReferenceQueryDoesNotExpireAttachment() {
        String json=oldSavedReference();
        when(jdbc.query(anyString(),any(RowMapper.class),eq("USER"),eq(7L))).thenThrow(new IllegalStateException("database"));
        assertThatThrownBy(()->service.taskResponse(json,"USER",7))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiReferenceMessage.STORAGE);
    }

    /** 无关坏行不能让有效作品集引用保护下的附件不可用。 */
    @Test @SuppressWarnings("unchecked") void unrelatedMalformedPortfolioDoesNotInvalidateHealthyReference() {
        String json=oldSavedReference();
        when(jdbc.query(anyString(),any(RowMapper.class),eq("USER"),eq(7L))).thenReturn(List.of(json,"{}"));
        assertThat(service.taskResponse(json,"USER",7).getFirst()).containsEntry("available",true).containsKey(TOKEN);
    }

    /** 无法确认保护完整性时返回重试错误，同时清理继续失败关闭。 */
    @Test @SuppressWarnings("unchecked") void incompleteReferencesCannotExpireOrDeleteAttachment() {
        String json=oldSavedReference();
        when(jdbc.query(anyString(),any(RowMapper.class),eq("USER"),eq(7L))).thenReturn(List.of("{}"));
        assertThatThrownBy(()->service.taskResponse(json,"USER",7))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiReferenceMessage.STORAGE);
        service.cleanupRemoved("USER",7,json,null);
        verify(storage,never()).delete(anyString());
        assertThatThrownBy(()->service.resolveForSave("USER",7,NODES.arrayNode(),json,2)).isInstanceOf(BusinessException.class);
    }

    /** 终态缺完成时间的坏任务不能污染其它已保存附件的展示。 */
    @Test @SuppressWarnings("unchecked") void incompleteTerminalTaskDoesNotInvalidateHealthyReference() throws Exception {
        String json=oldSavedReference();
        when(jdbc.query(anyString(),any(RowMapper.class),eq("USER"),eq(7L))).thenReturn(List.of(json));
        ResultSet row=mock(ResultSet.class);
        when(row.getString(2)).thenReturn(AiTaskStatusDict.SUCCEEDED.getCode());
        doAnswer(call->{call.<RowCallbackHandler>getArgument(1).processRow(row);return null;})
                .when(jdbc).query(anyString(),any(RowCallbackHandler.class),eq("USER"),eq(7L));
        assertThat(service.taskResponse(json,"USER",7).getFirst()).containsEntry("available",true).containsKey(TOKEN);
    }

    /** 同一对象存在冲突版本时不能把冲突解释为过期，也不能签出错误回执。 */
    @Test @SuppressWarnings("unchecked") void conflictingVersionsRemainRetryableAndCannotBeDeleted() {
        String json=oldSavedReference(); ObjectNode conflicting=stored(json);
        ((ObjectNode)conflicting.path(ITEMS).get(0).path(CONTENT)).put(ETAG,"different-version");
        when(jdbc.query(anyString(),any(RowMapper.class),eq("USER"),eq(7L))).thenReturn(List.of(json,conflicting.toString()));
        assertThatThrownBy(()->service.taskResponse(json,"USER",7))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiReferenceMessage.STORAGE);
        service.cleanupRemoved("USER",7,json,null);
        verify(storage,never()).delete(anyString());
    }

    /** 已确认的 404 仍应显示不可用，保持既有客户端契约。 */
    @Test void missingObjectRemainsUnavailable() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);
        when(storage.head(anyString())).thenReturn(null);
        assertThat(service.taskResponse(json,"USER",7).getFirst()).containsEntry("available",false).doesNotContainKey(TOKEN);
    }

    /** 构造超过上传缓冲但内容仍匹配的旧附件，用于验证数据库引用保护。 */
    private String oldSavedReference() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);
        modified-=WINDOW_MS+1000;
        ObjectNode value=stored(json); ((ObjectNode)value.path(ITEMS).get(0).path(CONTENT)).put(MODIFIED,modified);
        return value.toString();
    }

    /** 密钥未配置也能读取维护清单并通过空数组清空，不能卡死退出功能。 */
    @Test void disabledSigningStillAllowsDisplayAndClear() {
        String json=service.resolveForSave("USER",7,input(confirmed(),"A1"),null,2);
        properties.setReferenceEnabled(false);properties.setReferenceTokenSecret(null);
        assertThat(service.taskResponse(json,"USER",7).getFirst()).containsEntry("available",false).doesNotContainKey(TOKEN);
        assertThat(service.resolveForSave("USER",7,NODES.arrayNode(),json,2)).contains("\"items\":[]");
    }

    /** 票据创建辅助方法。 */
    private Map<String,Object> ticket() {
        return service.uploadTicket("{\"ownerType\":\"USER\",\"ownerId\":\"7\",\"originalFileName\":\"参考.png\",\"mimeType\":\"image/png\",\"sizeBytes\":100}");
    }
    /** 视频票据保留既有 MIME 和精确客户端大小。 */
    private Map<String,Object> videoTicket() {
        return service.uploadTicket("{\"ownerType\":\"USER\",\"ownerId\":\"7\",\"originalFileName\":\"参考.mp4\",\"mimeType\":\"video/mp4\",\"sizeBytes\":100}");
    }
    /** 新客户端图片媒体信息，不包含任何原件内容。 */
    private ObjectNode imageMediaInfo() {
        return NODES.objectNode().put(MIME,"image/png").put("codec","").put(SIZE,100).put(WIDTH,640).put(HEIGHT,320).putNull(DURATION);
    }
    /** 确认请求先带票据，测试用例再显式提供或故意省略必需的媒体信息。 */
    private ObjectNode completionRequest(Map<String,Object> upload) {
        return NODES.objectNode().put(UPLOAD_TOKEN,upload.get(UPLOAD_TOKEN).toString());
    }
    /** 远端交互白名单证明完成确认只读取两次对象元信息，没有读取文件内容或签发下载。 */
    private void assertOnlyHeadDuringConfirmation() {
        verify(storage,times(2)).head(key);verifyNoMoreInteractions(storage);verifyNoInteractions(cos);
    }
    /** 构造应被拒绝的旧协议票据，不依赖服务内部私有方法。 */
    private String legacyUploadToken(String current)throws Exception {
        ObjectNode payload=StrictAiJson.parseObject(new String(Base64.getUrlDecoder().decode(current.split("\\.")[0]),StandardCharsets.UTF_8),MAX_BYTES,8);
        payload.remove(SIZE);
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        Mac mac=Mac.getInstance(SIGN_ALGORITHM);mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8),SIGN_ALGORITHM));
        return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
    }
    /** 取得真实签名的确认响应。 */
    private Map<String,Object> confirmed() {Map<String,Object> ticket=ticket();return service.complete(completionRequest(ticket).set("mediaInfo",imageMediaInfo()).toString());}
    /** 只提交客户端允许控制的业务字段。 */
    private ArrayNode input(Map<String,Object> item,String number) {
        ArrayNode result=NODES.arrayNode();result.addObject().put(ID,item.get(ID).toString()).put(NUMBER,number).put(NOTE,"").put(TOKEN,item.get(TOKEN).toString());return result;
    }
}
