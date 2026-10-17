package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.service.SystemStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 系统接口控制器，仅将系统状态映射为原 HTTP 响应。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class VersionController {
    /** 系统探活与版本应用入口。 */
    private final SystemStatusService statusService;

    /** 健康检查保持原整站状态，字体能力通过增量字段表达。 */
    @GetMapping("/api/health")
    public Response<Map<String, Object>> health() { return Response.success(statusService.health()); }

    /** 构建版本接口保持原字段及缺省值。 */
    @GetMapping("/api/version")
    public Response<Map<String, String>> version() { return Response.success(statusService.version()); }
}
