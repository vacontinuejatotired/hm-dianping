package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isfollow}")
    public Result followUser(@PathVariable Long id, @PathVariable Boolean isfollow) {
        return followService.follow(id, isfollow);
    }

    @GetMapping("/or/not/{id}")
    public Result queryNotFollow(@PathVariable("id") Long id) {
        return followService.queryNotFollow(id);
    }
    @GetMapping("/common/{id}")
    public Result queryCommonFollow(@PathVariable("id") Long id) {
        return followService.queryCommonFollow(id);
    }
}
