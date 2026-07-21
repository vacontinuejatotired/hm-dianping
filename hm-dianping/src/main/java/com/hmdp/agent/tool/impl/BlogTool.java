package com.hmdp.tool.impl;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.annotation.TargetTool;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@TargetTool(active = true)
@Slf4j
public class BlogTool {

    @Resource
    private IBlogService blogService;

    //需要做安全校验，确保用户是当前登录用户
    @Tool(description = "查询本用户点赞10篇已发布博客")
    public List<Blog> queryPublishedBlogs(ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        log.info("queryPublishedBlogs userId: {}", userId);
        Page<Blog> page = blogService.query().eq("user_id", userId).orderByDesc("liked").page(new Page<>(1, 10));
        return page.getRecords();
    }

    @Tool(description = "为该用户发布一篇测试博客")
    public Blog publishTestBlog( ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        log.info("publishTestBlog userId: {}", userId);
        Blog blog = new Blog();
        blog.setUserId(userId);
        blog.setTitle("测试博客");
        blog.setContent("这是一篇测试博客");
        boolean save = blogService.save(blog);
        if (!save) {
            log.error("publishTestBlog failed, blog: {}", blog);
            return null;
        }
        return blog;
    }

    @Tool(description = "模糊查询博客标题")
    public List<Blog> queryBlogsByTitle(@ToolParam(description = "要查询的模糊博客标题") String title) {
        return blogService.query().like("title", title).list();
    }
}
