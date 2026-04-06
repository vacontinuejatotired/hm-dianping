package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ITestService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/test")
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
    public Result testRestart(@PathVariable Long num,@PathVariable Long voucherId) {
        return testService.restart(num,voucherId);
    }
    @PostMapping("/generateToken/{num}")
    public Result testGenerateTestToken(@PathVariable Long num,@RequestParam String fileName) {
        return testService.generateTestToken(num,fileName);
    }
    @GetMapping("/checkSnowFlake/{num}")
    public Result testCheckSnowFlake(@PathVariable int num) {
        return testService.checkSnowFlake(num);
    }
    @GetMapping("/mq/order/save/{voucherId}/{orderNum}")
    public Result testMq(@PathVariable Long voucherId,@PathVariable Long orderNum) {
        return  testService.testSaveOrder(voucherId,orderNum);

    }
}
