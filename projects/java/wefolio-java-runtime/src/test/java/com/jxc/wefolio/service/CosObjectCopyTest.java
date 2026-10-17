package com.jxc.wefolio.service;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.CosMessage;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.transfer.TransferManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 同桶稳定快照复制只使用模拟 COS，覆盖保留元数据、公有读与目标幂等性。 */
@ExtendWith(MockitoExtension.class)
class CosObjectCopyTest {
    /** COS 客户端容器。 */
    @Mock private TransferManager manager;
    /** 仅模拟目标探测与同桶复制。 */
    @Mock private COSClient client;
    /** 本地模拟存储配置。 */
    private CosService service;

    /** 创建不带真实凭据或外网依赖的存储服务。 */
    @BeforeEach void prepare() {
        CosProperties properties = new CosProperties(); properties.setBucketName("test-bucket");
        service = new CosService(manager, properties);
        lenient().when(manager.getCOSClient()).thenReturn(client);
    }

    /** 目标不存在时在相同桶复制，保留原始 ContentType 等元数据并设置公有读。 */
    @Test void absentTargetCopiesWithinBucketWithOriginalMetadataAndPublicRead() {
        service.copyObjectIfAbsent("WFOWNER/work/image/photo.jpg", "WFOWNER/protfolio/snapshot.jpg");

        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).doesObjectExist("test-bucket", "WFOWNER/protfolio/snapshot.jpg");
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().getSourceBucketName()).isEqualTo("test-bucket");
        assertThat(request.getValue().getDestinationBucketName()).isEqualTo("test-bucket");
        assertThat(request.getValue().getSourceKey()).isEqualTo("WFOWNER/work/image/photo.jpg");
        assertThat(request.getValue().getDestinationKey()).isEqualTo("WFOWNER/protfolio/snapshot.jpg");
        assertThat(request.getValue().getMetadataDirective()).isEqualTo("COPY");
        assertThat(request.getValue().getNewObjectMetadata()).isNull();
        assertThat(request.getValue().getCannedAccessControlList()).isEqualTo(CannedAccessControlList.PublicRead);
    }

    /** 快照已存在后无需访问原作品，原作品已删除也不会破坏重复查询。 */
    @Test void existingTargetDoesNotReadOrCopySource() {
        when(client.doesObjectExist("test-bucket", "WFOWNER/protfolio/snapshot.jpg")).thenReturn(true);

        service.copyObjectIfAbsent("WFOWNER/work/image/deleted.jpg", "WFOWNER/protfolio/snapshot.jpg");

        verify(client, never()).copyObject(any(CopyObjectRequest.class));
        verify(client, never()).getObjectMetadata("test-bucket", "WFOWNER/work/image/deleted.jpg");
    }

    /** 存储异常使用固定业务提示，不能把内部 COS 详情拼入客户端文案。 */
    @Test void copyFailureUsesFixedBusinessMessage() {
        when(client.copyObject(any(CopyObjectRequest.class))).thenThrow(new IllegalStateException("private-storage-details"));

        assertThatThrownBy(() -> service.copyObjectIfAbsent("WFOWNER/work/image/photo.jpg", "WFOWNER/protfolio/snapshot.jpg"))
                .isInstanceOf(BusinessException.class).hasMessage(CosMessage.OBJECT_COPY_FAILED_MESSAGE);
    }
}
