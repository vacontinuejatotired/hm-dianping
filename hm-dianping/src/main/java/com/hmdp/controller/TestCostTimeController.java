package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ITestService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 测试/工具控制器 — 压测接口（空接口耗时、Token生成、秒杀重置、MQ批量下单等）
 */
@RestController
@RequestMapping("/test")
@Tag(name = "测试工具模块", description = "压测、性能测试、Token批量生成等工具接口")
public class TestCostTimeController {

    @Resource
    private ITestService testService;

    @GetMapping("/costTime")
    public String testCostTime() {

        long startTime = System.currentTimeMillis();
        // 模拟耗时操作
        try {
            Thread.sleep(500); // 休眠500毫秒
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long endTime = System.currentTimeMillis();
        long costTime = endTime - startTime;
        return "操作耗时: " + costTime + " ms";
    }
    @GetMapping("/void")
    public String testVoid() {
        return "void方法测试成功";
    }

    @PostMapping("/restart/{num}/{voucherId}")
    public Result testRestart(@Parameter(description = "库存数量") @PathVariable Long num,@Parameter(description = "秒杀券ID") @PathVariable Long voucherId) {
        return testService.restart(num,voucherId);
    }
    @PostMapping("/generateToken/{num}")
    public Result testGenerateTestToken(@Parameter(description = "生成数量") @PathVariable Long num,@Parameter(description = "输出文件名") @RequestParam String fileName) {
        return testService.generateTestToken(num,fileName);
    }
    @GetMapping("/checkSnowFlake/{num}")
    public Result testCheckSnowFlake(@Parameter(description = "生成数量") @PathVariable int num) {
        return testService.checkSnowFlake(num);
    }
    @GetMapping("/mq/order/save/{voucherId}/{orderNum}")
    public Result testMq(@Parameter(description = "秒杀券ID") @PathVariable Long voucherId,@Parameter(description = "订单数量") @PathVariable Long orderNum) {
        return  testService.testSaveOrder(voucherId,orderNum);

    }
}
