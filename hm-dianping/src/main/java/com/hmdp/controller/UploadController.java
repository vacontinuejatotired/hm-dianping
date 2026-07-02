package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.service.FileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * 文件上传控制器 — 博客图片上传/删除，委托 FileService 处理
 * dev → LocalFileServiceImpl, prod → OssFileServiceImpl
 */
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private FileService fileService;

    private static final Set<String> ALLOWED_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            String originalFilename = image.getOriginalFilename();
            // 文件类型校验
            String ext = StrUtil.subAfter(originalFilename, ".", true).toLowerCase();
            if (!ALLOWED_TYPES.contains(ext)) {
                return Result.fail("不支持的文件类型，仅允许: " + ALLOWED_TYPES);
            }
            // 文件大小校验
            if (image.getSize() > MAX_FILE_SIZE) {
                return Result.fail("文件过大，最大允许 5MB");
            }
            // 委托 FileService 上传，返回完整 URL
            String url = fileService.upload(image.getInputStream(), originalFilename, "blogs");
            log.debug("文件上传成功，{}", url);
            return Result.ok(url);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("url") String fileUrl) {
        fileService.delete(fileUrl);
        return Result.ok();
    }
}
