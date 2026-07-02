package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aliyun.oss.OSS;
import com.hmdp.config.OssProperties;
import com.hmdp.service.FileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * 阿里云 OSS 文件上传实现 — prod 环境
 * Object Key 格式：{module}/{d1}/{d2}/{uuid}.{ext}
 */
@Slf4j
@Service
@Profile("prod")
public class OssFileServiceImpl implements FileService {

    private final OSS ossClient;
    private final OssProperties ossProperties;

    public OssFileServiceImpl(OSS ossClient, OssProperties ossProperties) {
        this.ossClient = ossClient;
        this.ossProperties = ossProperties;
    }

    @Override
    public String upload(InputStream inputStream, String originalFilename, String module) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        int d1 = uuid.hashCode() & 0xF;
        int d2 = (uuid.hashCode() >> 4) & 0xF;
        String key = StrUtil.format("{}/{}/{}/{}.{}", module, d1, d2, uuid, suffix);

        ossClient.putObject(ossProperties.getBucket(), key, inputStream);
        log.info("OSS 上传成功 key={}, bucket={}", key, ossProperties.getBucket());
        return getDomain() + "/" + key;
    }

    @Override
    public boolean delete(String fileUrl) {
        String key = extractKeyFromUrl(fileUrl);
        ossClient.deleteObject(ossProperties.getBucket(), key);
        log.info("OSS 删除成功 key={}, bucket={}", key, ossProperties.getBucket());
        return true;
    }

    @Override
    public String getDomain() {
        return "https://" + ossProperties.getBucket() + "." + ossProperties.getEndpoint();
    }

    private String extractKeyFromUrl(String fileUrl) {
        return URI.create(fileUrl).getPath().substring(1);
    }
}
