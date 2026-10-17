package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 为候选实际使用的作品分享图片生成稳定快照，避免作品删除破坏分享信息。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiShareImageService {
    /** 固定命名空间确保同任务同句柄同来源生成相同目标对象键。 */
    private static final String SNAPSHOT_UUID_NAMESPACE = "wefolio-ai-share-image-v1";
    /** 快照命名种子的无歧义分隔符。 */
    private static final String SEED_SEPARATOR = "|";
    /** 个人来源必须位于本人作品目录。 */
    private static final String WORK_FOLDER = "/work/";
    /** 固定保留历史拼写的作品集素材目录。 */
    private static final String PORTFOLIO_FOLDER = "/protfolio/";
    /** 现有作品上传允许的图片格式，视频和动图只能提供静态封面。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    /** 目录唯一码只能使用现有生成器允许的英文字母和数字。 */
    private static final String UNIQUE_CODE_PATTERN = "[A-Za-z0-9]+";
    /** 作品对象键不接受编码、空白、查询、凭据或替代路径分隔符。 */
    private static final String SOURCE_KEY_PATTERN = "[A-Za-z0-9_./-]+";
    /** 图片公开地址与当前 COS 公共前缀均必须使用 HTTPS。 */
    private static final String PUBLIC_SCHEME = "https";
    /** 对象路径分隔符。 */
    private static final String PATH_SEPARATOR = "/";
    /** 对象路径中不允许的当前目录段。 */
    private static final String CURRENT_DIRECTORY = ".";
    /** 对象路径中不允许的上级目录段。 */
    private static final String PARENT_DIRECTORY = "..";
    /** 图片扩展名分隔符。 */
    private static final String EXTENSION_SEPARATOR = ".";
    /** 当前个人归属与固定 COS 目录读取器。 */
    private final UserEntityMapper users;
    /** 同桶图片复制与公开地址生成器。 */
    private final CosService cos;

    /** 返回仅修改所用分享图片原值的请求副本，不修改任务保存的冻结请求。 */
    public ObjectNode snapshot(Long taskId, ObjectNode request, ObjectNode envelope) {
        ObjectNode result = request.deepCopy();
        if (!PortfolioOwnerTypeDict.USER.getCode().equals(request.path(KEY_INPUT).path(KEY_OWNER_TYPE).asText())) { return result; }
        Set<String> usedRefs = new LinkedHashSet<>(); collectRefs(envelope, usedRefs);
        List<ObjectNode> selected = new ArrayList<>();
        for (JsonNode binding : result.path(KEY_FACTS).path(KEY_BINDINGS)) {
            if (binding.isObject() && RESOURCE_KIND_IMAGE.equals(binding.path(KEY_KIND).asText())
                    && binding.path(KEY_SOURCE_NO).isIntegralNumber() && usedRefs.contains(binding.path(KEY_REF).asText())) {
                selected.add((ObjectNode) binding);
            }
        }
        if (selected.isEmpty()) { return result; }
        if (taskId == null || taskId <= 0L) { throw invalid(); }
        UserEntity owner = users.selectById(PortfolioAiInput.id(request.path(KEY_INPUT), KEY_OWNER_ID, false));
        if (owner == null || !UserStatusDict.ACTIVE.getCode().equals(owner.getStatus())
                || owner.getDeleted() != null && owner.getDeleted() != 0L
                || owner.getUniqueCode() == null || !owner.getUniqueCode().matches(UNIQUE_CODE_PATTERN)) { throw invalid(); }
        String publicPrefix = cos.publicUrl("");
        if (!isPublicUrl(publicPrefix) || !publicPrefix.endsWith(PATH_SEPARATOR)) { throw invalid(); }
        List<ImageCopy> copies = new ArrayList<>();
        for (ObjectNode binding : selected) {
            String sourceKey = sourceKey(binding.path(KEY_VALUE), publicPrefix, owner.getUniqueCode());
            String extension = sourceKey.substring(sourceKey.lastIndexOf(EXTENSION_SEPARATOR) + 1).toLowerCase(Locale.ROOT);
            String seed = String.join(SEED_SEPARATOR, SNAPSHOT_UUID_NAMESPACE, taskId.toString(), binding.path(KEY_REF).asText(), sourceKey);
            String targetKey = owner.getUniqueCode() + PORTFOLIO_FOLDER
                    + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)) + EXTENSION_SEPARATOR + extension;
            String targetUrl = cos.publicUrl(targetKey);
            if (!(publicPrefix + targetKey).equals(targetUrl)) { throw invalid(); }
            copies.add(new ImageCopy(binding, sourceKey, targetKey, targetUrl));
        }
        // 全部来源路径先验证，再执行复制，避免非法候选产生部分素材副作用。
        for (ImageCopy copy : copies) {
            cos.copyObjectIfAbsent(copy.sourceKey(), copy.targetKey());
            copy.binding().put(KEY_VALUE, copy.targetUrl());
        }
        return result;
    }

    /** 只读取结构化 ref 字段，说明文字中提及素材号或句柄不会触发复制。 */
    private static void collectRefs(JsonNode node, Set<String> refs) {
        if (node.isObject() && node.path(KEY_REF).isTextual()) { refs.add(node.path(KEY_REF).textValue()); }
        for (JsonNode child : node) { collectRefs(child, refs); }
    }

    /** 可信冻结地址也须锚定当前公共域名及本人作品目录，不接受编码与目录穿越。 */
    private static String sourceKey(JsonNode value, String publicPrefix, String uniqueCode) {
        if (!value.isTextual() || !isPublicUrl(value.textValue()) || !value.textValue().startsWith(publicPrefix)) { throw invalid(); }
        String key = value.textValue().substring(publicPrefix.length());
        if (!key.startsWith(uniqueCode + WORK_FOLDER) || !key.matches(SOURCE_KEY_PATTERN)) { throw invalid(); }
        for (String segment : key.split(PATH_SEPARATOR, -1)) {
            if (segment.isEmpty() || CURRENT_DIRECTORY.equals(segment) || PARENT_DIRECTORY.equals(segment)) { throw invalid(); }
        }
        int extensionIndex = key.lastIndexOf(EXTENSION_SEPARATOR);
        if (extensionIndex <= key.lastIndexOf(PATH_SEPARATOR)
                || !IMAGE_EXTENSIONS.contains(key.substring(extensionIndex + 1).toLowerCase(Locale.ROOT))) { throw invalid(); }
        return key;
    }

    /** 地址不能携带凭据、查询签名或片段，避免误用模型地址或带权限的临时链接。 */
    private static boolean isPublicUrl(String url) {
        if (url == null || url.isBlank()) { return false; }
        try {
            URI uri = new URI(url);
            return PUBLIC_SCHEME.equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (URISyntaxException ignored) { return false; }
    }

    /**
     * 已通过路径校验的单次图片复制计划。
     * @param binding 待替换原值的请求副本资源句柄
     * @param sourceKey 已授权本人作品图片对象键
     * @param targetKey 幂等生成的作品集快照对象键
     * @param targetUrl 快照对象对应的公开图片地址
     */
    private record ImageCopy(ObjectNode binding, String sourceKey, String targetKey, String targetUrl) { }

    /** 对外使用固定候选错误，不回显源地址或账户目录。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID); }
}
