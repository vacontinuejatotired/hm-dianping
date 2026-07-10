package com.hmdp.controller;

import com.hmdp.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 聊天控制器 — 预留，聊天功能开发中（后续对接AI或人工客服）
 * </p>
 */
@RestController
@RequestMapping("/chat")
@Tag(name = "聊天模块", description = "聊天功能接口")
public class ChatController {

    @PostMapping
    @Operation(summary = "发送聊天消息", description = "聊天功能接口，待开发")
    public Result chat(
            @Parameter(description = "聊天内容") @RequestParam String content) {

        return Result.ok("聊天功能正在开发中...");
    }
}