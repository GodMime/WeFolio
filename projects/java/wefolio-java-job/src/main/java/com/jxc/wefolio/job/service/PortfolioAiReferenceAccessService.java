package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.PortfolioAiReferenceRepository;
import com.qcloud.cos.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.jxc.wefolio.job.service.PortfolioAiReferenceContract.*;

/** 参考输入只信任冻结事实与当前维护权，不能使用作品 allow_works 代替团队附件授权。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiReferenceAccessService {
    /** 复用用户和团队当前 OWNER/MANAGER 资格检查。 */
    private final PortfolioAiMediaAccessService access;
    /** 作品集及归属的当前主库状态。 */
    private final PortfolioAiReferenceRepository repository;
    /** 私有原件的 HEAD 与签名读取。 */
    private final PortfolioAiReferenceStorage storage;
    /** 签名期限覆盖本次模型调用及抓取余量。 */
    private final PortfolioAiProperties properties;
    /** 调用前及结果返回后核对所有原件，权限或内容变化立即停止。 */
    public void verify(AiGenerationTaskEntity task, List<JsonNode> items) {
        access.requireOwner(task);
        PortfolioAiReferenceRepository.Owner owner;
        try { owner = repository.currentOwner(task); }
        catch (RuntimeException exception) { throw unavailable(); }
        for (JsonNode item : items) {
            if (!item.path(KEY).asText().startsWith(owner.code() + PortfolioAiReferenceContract.DIRECTORY)) { throw unavailable(); }
        }
        try { storage.requireVersioningOff(); }
        catch (RuntimeException exception) { throw unavailable(); }
        for (JsonNode item : items) {
            String key = item.path(KEY).asText();
            ObjectMetadata head;
            try { head = storage.head(key); }
            catch (RuntimeException exception) { throw new PortfolioAiCallException("MEDIA_ACCESS_RETRY", "参考文件暂时不可读取，请稍后重试", true); }
            JsonNode version = item.path(CONTENT);
            if (head == null || head.getLastModified() == null || head.getContentLength() != item.path(SIZE).asLong()
                    || !item.path(MIME).asText().equalsIgnoreCase(head.getContentType())
                    || !version.path(ETAG).asText().equals(head.getETag())
                    || version.path(MODIFIED).asLong() != head.getLastModified().getTime()) { throw unavailable(); }
        }
    }
    /** 先验证当前权限和原件，再仅在内存创建短期官方源站地址。 */
    public List<PortfolioAiModelClient.MediaInput> signed(AiGenerationTaskEntity task, List<JsonNode> items) {
        verify(task, items);
        return signedAfterVerification(items);
    }
    /** 仅供执行器紧接全量 verify 使用，签名本身不重复调用 COS。 */
    List<PortfolioAiModelClient.MediaInput> signedAfterVerification(List<JsonNode> items) {
        List<PortfolioAiModelClient.MediaInput> media = new ArrayList<>();
        for (JsonNode item : items) {
            String type = VIDEO.equals(item.path(TYPE).asText()) ? VIDEO_URL : IMAGE_URL;
            media.add(new PortfolioAiModelClient.MediaInput(type, storage.sign(item.path(KEY).asText(),
                    Duration.ofSeconds(Math.max(180, properties.getCallTimeoutSeconds() + 60))), null));
        }
        return media;
    }
    /** 明确区分文件失效与分析失败，绝不回退成无附件生成。 */
    private static PortfolioAiCallException unavailable() { return new PortfolioAiCallException(ERROR_UNAVAILABLE, "参考文件或维护权限已失效，请重新选择", false); }
}
