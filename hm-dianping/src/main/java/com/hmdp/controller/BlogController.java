package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 探店笔记控制器 — 笔记CRUD、点赞、点赞列表、关注者Feed流
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 更新博客图片列表 — 上传完成后调用
     * 接收 JSON 数组而非逗号分隔字符串，避免 URL 含逗号时切分错误
     */
    @PutMapping("/{id}/images")
    public Result updateBlogImages(
            @PathVariable("id") Long id,
            @RequestBody List<String> images) {
        return blogService.updateBlogImages(id, images);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
//        blogService.update()
//                .setSql("liked = liked + 1").eq("id", id).update();
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        return blogService.queryByUserId(id, current);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryMyBlog(current);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotById(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryLikeBlog(@PathVariable("id") Long id) {
        return blogService.queryUserList(id);
    }
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam("lastId") Long max
            ,@RequestParam(defaultValue  ="0",value ="offset")Integer offset) {
    return blogService.queryBlogOfFollow(max,offset);
    }
}

