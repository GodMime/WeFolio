package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineScheduleResponse;
import com.jxc.wefolio.dto.ScheduleItemSaveRequest;
import com.jxc.wefolio.dto.ScheduleSlotDefinitionRequest;
import com.jxc.wefolio.dto.ScheduleSlotDefinitionStatusRequest;
import com.jxc.wefolio.service.MineScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的档期控制器 — 提供维护者个人档位定义和档期维护接口。
 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineScheduleController {

    /** 我的档期服务 */
    private final MineScheduleService mineScheduleService;

    /**
     * 获取档期聚合数据。
     *
     * @param month 月份，格式 yyyy-MM
     * @param date 选中日期，格式 yyyy-MM-dd
     * @return 档期聚合响应
     */
    @GetMapping("/api/mine/schedule")
    public Response<MineScheduleResponse> schedule(
            @RequestParam("month") String month,
            @RequestParam("date") String date
    ) {
        return Response.success(mineScheduleService.getScheduleOverview(month, date));
    }

    /**
     * 新增档位定义。
     *
     * @param request 档位定义请求
     * @return 新增后的档位定义
     */
    @PostMapping("/api/mine/schedule/slot-definitions")
    public Response<MineScheduleResponse.SlotDefinitionItem> createSlotDefinition(
            @RequestBody ScheduleSlotDefinitionRequest request
    ) {
        return Response.success(mineScheduleService.createSlotDefinition(request));
    }

    /**
     * 编辑档位定义。
     *
     * @param id 档位定义 ID
     * @param request 档位定义请求
     * @return 更新后的档位定义
     */
    @PostMapping("/api/mine/schedule/slot-definitions/save/{id}")
    public Response<MineScheduleResponse.SlotDefinitionItem> updateSlotDefinition(
            @PathVariable Long id,
            @RequestBody ScheduleSlotDefinitionRequest request
    ) {
        return Response.success(mineScheduleService.updateSlotDefinition(id, request));
    }

    /**
     * 启用或停用档位定义。
     *
     * @param id 档位定义 ID
     * @param request 状态请求
     * @return 更新后的档位定义
     */
    @PostMapping("/api/mine/schedule/slot-definitions/status/{id}")
    public Response<MineScheduleResponse.SlotDefinitionItem> updateSlotDefinitionStatus(
            @PathVariable Long id,
            @RequestBody ScheduleSlotDefinitionStatusRequest request
    ) {
        return Response.success(mineScheduleService.updateSlotDefinitionStatus(id, request));
    }

    /**
     * 删除停用档位定义。
     *
     * @param id 档位定义 ID
     * @return 空响应
     */
    @PostMapping("/api/mine/schedule/slot-definitions/delete/{id}")
    public Response<Void> deleteSlotDefinition(@PathVariable Long id) {
        mineScheduleService.deleteSlotDefinition(id);
        return Response.success();
    }

    /**
     * 保存档期记录。
     *
     * @param request 档期记录请求
     * @return 保存后的档期记录
     */
    @PostMapping("/api/mine/schedule/items/save")
    public Response<MineScheduleResponse.ScheduleItem> saveScheduleItem(
            @RequestBody ScheduleItemSaveRequest request
    ) {
        return Response.success(mineScheduleService.saveScheduleItem(request));
    }

    /**
     * 删除档期记录。
     *
     * @param id 档期 ID
     * @return 空响应
     */
    @PostMapping("/api/mine/schedule/items/delete/{id}")
    public Response<Void> deleteScheduleItem(@PathVariable Long id) {
        mineScheduleService.deleteScheduleItem(id);
        return Response.success();
    }
}
