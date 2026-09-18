package com.jxc.wefolio.service;

import com.jxc.wefolio.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.transfer.TransferManager;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** COS 原码缓存探测仅接受明确 404，网络故障不能触发生成风暴。 */
class CosMiniappCodeHeadTest {
    /** 404 与临时服务故障具有不同应用语义。 */
    @Test void distinguishesMissingFromServiceFailure() {
        var manager = mock(TransferManager.class); var client = mock(COSClient.class);
        var config = new CosProperties(); config.setBucketName("bucket");
        when(manager.getCOSClient()).thenReturn(client);
        var missing = new CosServiceException("不存在"); missing.setStatusCode(404);
        var unavailable = new CosServiceException("暂时不可用"); unavailable.setStatusCode(503);
        when(client.getObjectMetadata("bucket", "key")).thenThrow(missing).thenThrow(unavailable);
        var cos = new CosService(manager, config);
        assertThat(cos.headObjectVersion("key")).isNull();
        assertThatThrownBy(() -> cos.headObjectVersion("key")).isSameAs(unavailable);
        verify(client, times(2)).getObjectMetadata("bucket", "key");
        verifyNoMoreInteractions(client);
    }
    /** 版本、类型和长度来自 HEAD 响应，完全不获取正文。 */
    @Test void preservesHeadVersionMetadata() {
        var manager = mock(TransferManager.class); var client = mock(COSClient.class);
        var config = new CosProperties(); config.setBucketName("bucket"); when(manager.getCOSClient()).thenReturn(client);
        var metadata = new ObjectMetadata(); metadata.setContentLength(100); metadata.setContentType("image/png"); metadata.setHeader("ETag", "etag");
        when(client.getObjectMetadata("bucket", "key")).thenReturn(metadata);
        var head = new CosService(manager, config).headObjectVersion("key");
        assertThat(head.eTag()).isEqualTo("etag"); assertThat(head.contentLength()).isEqualTo(100); assertThat(head.contentType()).isEqualTo("image/png");
        verify(client).getObjectMetadata("bucket", "key"); verifyNoMoreInteractions(client);
    }
}
