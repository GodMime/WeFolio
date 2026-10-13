package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 分享图片快照覆盖实际使用、路径授权、幂等对象键和失败时拒绝候选。 */
@ExtendWith(MockitoExtension.class)
class PortfolioAiShareImageServiceTest {
    /** 用户固定 COS 目录读取器。 */
    @Mock private UserEntityMapper users;
    /** 只模拟同桶复制，不访问真实 COS。 */
    @Mock private CosService cos;
    /** 待测分享图片快照服务。 */
    private PortfolioAiShareImageService service;

    /** 公共域名与用户目录均使用无密钥测试值。 */
    @BeforeEach void prepare() {
        service = new PortfolioAiShareImageService(users, cos);
        UserEntity owner = new UserEntity(); owner.setId(101L); owner.setUniqueCode("WFOWNER");
        owner.setDeleted(0L); owner.setStatus(UserStatusDict.ACTIVE.getCode());
        lenient().when(users.selectById(101L)).thenReturn(owner);
        lenient().when(cos.publicUrl(anyString())).thenAnswer(call -> "https://cdn.test/" + call.getArgument(0));
    }

    /** 解析明确且无外部依赖的测试 JSON。 */
    private ObjectNode node(String json) { return StrictAiJson.parseObject(json, 262144, 20); }

    /** 两份来源图片与不依赖作品生命周期的本人头像。 */
    private ObjectNode request() {
        return node("""
                {"input":{"ownerType":"USER","ownerId":"101"},"facts":{"bindings":[
                 {"ref":"source_cover_1","kind":"IMAGE","sourceNo":1,"allowedSlots":["coverUrl","avatarUrl"],
                  "value":"https://cdn.test/WFOWNER/work/image/photo.jpg"},
                 {"ref":"source_cover_2","kind":"IMAGE","sourceNo":2,"allowedSlots":["coverUrl","avatarUrl"],
                  "value":"https://cdn.test/WFOWNER/work/video/film-thumb.jpg"},
                 {"ref":"profile_share_avatar","kind":"IMAGE","allowedSlots":["avatarUrl"],
                  "value":"https://cdn.test/WFOWNER/others/avatar.jpg"}]}}
                """);
    }

    /** 同一来源同时作为分享封面与头像，仅创建一份稳定图片。 */
    private ObjectNode selected() {
        return node("""
                {"page":{"settings":{"share":{"bindings":[
                 {"slot":"coverUrl","ref":"source_cover_1"},{"slot":"avatarUrl","ref":"source_cover_1"}]}}}}
                """);
    }

    /** 复制实际使用来源一次，不复制未用选图或本人资料头像，也不修改原任务快照。 */
    @Test void snapshotsOnlySelectedSourceReferencesWithoutMutatingFrozenRequest() {
        ObjectNode request = request(), before = request.deepCopy();
        ObjectNode output = selected(); output.putArray("notes").add("不要使用 source_cover_2");

        ObjectNode result = service.snapshot(801L, request, output);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(cos).copyObjectIfAbsent(eq("WFOWNER/work/image/photo.jpg"), target.capture());
        assertThat(target.getValue()).matches("WFOWNER/protfolio/[a-f0-9-]{36}\\.jpg");
        assertThat(result.path("facts").path("bindings").get(0).path("value").asText())
                .isEqualTo("https://cdn.test/" + target.getValue());
        assertThat(result.path("facts").path("bindings").get(1)).isEqualTo(before.path("facts").path("bindings").get(1));
        assertThat(result.path("facts").path("bindings").get(2)).isEqualTo(before.path("facts").path("bindings").get(2));
        assertThat(request).isEqualTo(before);
    }

    /** 同任务重复读取使用同一目标，不同任务保持独立目录内对象身份。 */
    @Test void repeatedQueryUsesDeterministicSnapshotTarget() {
        service.snapshot(801L, request(), selected());
        service.snapshot(801L, request(), selected());
        service.snapshot(802L, request(), selected());
        ArgumentCaptor<String> targets = ArgumentCaptor.forClass(String.class);
        verify(cos, times(3)).copyObjectIfAbsent(anyString(), targets.capture());
        assertThat(targets.getAllValues().get(0)).isEqualTo(targets.getAllValues().get(1));
        assertThat(targets.getAllValues().get(2)).isNotEqualTo(targets.getAllValues().get(0));
    }

    /** 用户资料头像没有素材号，采用它时不能复制作品或访问账户目录。 */
    @Test void profileAvatarAndUnusedSourceReferencesDoNotTriggerCopies() {
        ObjectNode request = request();
        ObjectNode output = node("{\"bindings\":[{\"slot\":\"avatarUrl\",\"ref\":\"profile_share_avatar\"}]}");

        assertThat(service.snapshot(801L, request, output)).isEqualTo(request);

        verifyNoInteractions(users, cos);
    }

    /** 团队沿用自有作品集图片上传约束，快照服务不引入成员图片复制行为。 */
    @Test void teamRequestDoesNotTriggerPersonalImageCopies() {
        ObjectNode request = request(); request.withObject("input").put("ownerType", "TEAM");

        assertThat(service.snapshot(801L, request, selected())).isEqualTo(request);

        verifyNoInteractions(users, cos);
    }

    /** 原始图片支持现有作品格式，动画或视频素材也仅使用冻结的静态图片地址。 */
    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "webp", "gif"})
    void preservesSupportedImageExtension(String extension) {
        ObjectNode request = request();
        ((ObjectNode) request.path("facts").path("bindings").get(0))
                .put("value", "https://cdn.test/WFOWNER/work/image/photo." + extension);

        service.snapshot(801L, request, selected());

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(cos).copyObjectIfAbsent(anyString(), target.capture());
        assertThat(target.getValue()).endsWith("." + extension);
    }

    /** 来源地址必须严格来自固定公开域名且锚定本人作品目录，不接受签名、穿越或播放文件。 */
    @ParameterizedTest
    @ValueSource(strings = {"https://foreign.test/WFOWNER/work/image/photo.jpg",
            "https://cdn.test.evil/WFOWNER/work/image/photo.jpg", "https://cdn.test/WFFOREIGN/work/image/photo.jpg",
            "https://cdn.test/WFOWNER/others/photo.jpg", "https://cdn.test/WFOWNER/work/image/../../others/photo.jpg",
            "https://cdn.test/WFOWNER/work/image/%2e%2e/photo.jpg", "https://cdn.test/WFOWNER/work/image/photo.mp4",
            "https://cdn.test/WFOWNER/work/image/photo.jpg?secret=signature", "https://user:secret@cdn.test/WFOWNER/work/image/photo.jpg",
            "https://cdn.test/WFOWNER/work/image/photo.jpg#fragment", "http://cdn.test/WFOWNER/work/image/photo.jpg"})
    void refusesForeignOrInvalidFrozenImageUrl(String url) {
        ObjectNode request = request();
        ((ObjectNode) request.path("facts").path("bindings").get(0)).put("value", url);

        assertThatThrownBy(() -> service.snapshot(801L, request, selected()))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.CANDIDATE_INVALID);

        verify(cos, never()).copyObjectIfAbsent(anyString(), anyString());
    }

    /** 复制失败时整体候选失败，不能返回仍依赖待删除作品文件的分享配置。 */
    @Test void copyFailureDoesNotReturnCandidateAndRetainsFrozenRequest() {
        ObjectNode request = request(), before = request.deepCopy();
        doThrow(new BusinessException("测试复制失败")).when(cos).copyObjectIfAbsent(anyString(), anyString());

        assertThatThrownBy(() -> service.snapshot(801L, request, selected())).isInstanceOf(BusinessException.class);
        assertThat(request).isEqualTo(before);
    }
}
