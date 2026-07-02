# 阿里云 OSS 图片上传方案

> 黑马点评（hm-dianping）图片上传接入阿里云 OSS  
> 当前状态：设计方案（第3版 — 按审查问题组织）  
> 最后更新：2026-07

---

## 目录

1. [现状与问题清单](#1-现状与问题清单)
2. [总方案](#2-总方案)
3. [问题 1 修复：架构统一（FileService 接口 + 双实现）](#3-问题-1-修复架构统一fileservice-接口--双实现)
4. [问题 2 修复：OssConfig 加 @Profile 控制](#4-问题-2-修复ossconfig-加-profile-控制)
5. [问题 3 修复：配置前缀统一为 app.oss.*](#5-问题-3-修复配置前缀统一为-apposs)
6. [问题 4 修复：Object Key 加入目录散列](#6-问题-4-修复object-key-加入目录散列)
7. [问题 5 修复：上传文件类型/大小校验](#7-问题-5-修复上传文件类型大小校验)
8. [问题 6 修复：删除接口改为 DELETE](#8-问题-6-修复删除接口改为-delete)
9. [前端适配](#9-前端适配)
10. [存量数据兼容](#10-存量数据兼容)
11. [实施步骤](#11-实施步骤)
12. [附录](#12-附录)

---

## 1. 现状与问题清单

### 当前有三套上传相关代码

| # | 文件 | 功能 | 状态 |
|---|------|------|:----:|
| A | `UploadController.java` | 本地磁盘上传，`/upload/blog` | ✅ 工作中 |
| B | `OssController.java` + `OssService.java` | OSS 直调，`/api/oss/upload` | ❌ 死代码 |
| C | `OssConfig.java` | OSS 客户端 Bean，`aliyun.oss.*` | ❌ dev 启动报错 |

### 审查发现的 6 个问题

| # | 问题 | 级别 | 说明 |
|:-:|------|:----:|------|
| ① | **两套不兼容的 OSS 架构** | 🔴 P0 | A 走本地，B 走 OSS，路径不同、前缀不同、互不兼容 |
| ② | **OssConfig 无 @Profile 控制** | 🔴 P0 | dev 环境无 `aliyun.oss.*` 配置，启动报 `Could not resolve placeholder` |
| ③ | **配置前缀不统一** | 🔴 P1 | 文档 `app.oss.*` vs 代码 `aliyun.oss.*` |
| ④ | **Object Key 无目录散列** | 🟠 P2 | `OssService` 生成 `UUID_filename.ext`，OSS 单目录超限性能下降 |
| ⑤ | **上传无文件类型/大小校验** | 🟠 P2 | 白名单 + 5MB 限制缺失 |
| ⑥ | **删除接口使用 GET 方法** | 🟢 P3 | `@GetMapping("/blog/delete")` 不符合 REST 语义 |

---

## 2. 总方案

```mermaid
flowchart LR
  subgraph 现状
    A1[UploadController<br/>本地磁盘]
    A2[OssController+OssService<br/>OSS直调]
    A3[OssConfig<br/>aliyun.oss.*]
  end
  subgraph 改造后
    B1[UploadController<br/>注入FileService]
    B2[FileService接口]
    B3[LocalFileServiceImpl<br/>@Profile dev]
    B4[OssFileServiceImpl<br/>@Profile prod]
    B5[OssConfig<br/>@Profile prod<br/>app.oss.*]
  end
  
  现状 -->|清理+重构| 改造后
  
  B1 --> B2
  B2 --> B3
  B2 --> B4
  B4 --> B5
```

### 解决对照

| 问题 | 方案 | 涉及文件 |
|:----:|------|---------|
| ① 架构不统一 | 抽取 FileService 接口，双实现切换 | 新建 3 文件，删除 2 文件 |
| ② 无 @Profile | OssConfig 加 `@Profile("prod")` | 修改 `OssConfig.java` |
| ③ 前缀不统一 | 统一为 `app.oss.*` | 修改 `OssConfig.java` + `application-prod.yaml` |
| ④ 无散列 | Object Key 格式：`{module}/{d1}/{d2}/{uuid}.{ext}` | 新建 `OssFileServiceImpl` |
| ⑤ 无校验 | Controller 白名单 jpg/png/gif/webp + ≤5MB | 修改 `UploadController.java` |
| ⑥ GET 删除 | 改为 `@DeleteMapping` | 修改 `UploadController.java` |

---

## 3. 问题 ① 修复：架构统一（FileService 接口 + 双实现）

### 3.1 删除：OssController.java + OssService.java

```
❌ service/OssService.java       → 删除（被 OssFileServiceImpl 替代）
❌ controller/OssController.java → 删除（被 UploadController 覆盖）
```

### 3.2 新建：FileService 接口

```java
package com.hmdp.service;

import java.io.InputStream;

/**
 * 文件上传服务接口 — upload() 接收 InputStream，不依赖 Spring MultipartFile
 * 本地 + OSS 两种实现通过 @Profile 切换
 */
public interface FileService {

    /** 上传文件，返回完整可访问 URL */
    String upload(InputStream inputStream, String originalFilename, String module);

    /** 删除文件 */
    boolean delete(String fileUrl);

    /** 获取文件访问域名前缀 */
    String getDomain();
}
```

### 3.3 新建：LocalFileServiceImpl（从 UploadController 抽取）

```java
@Slf4j
@Service
@Profile("dev")  // 仅 dev 激活
public class LocalFileServiceImpl implements FileService {

    private static final String UPLOAD_DIR = "E:\\nginx-1.18.0heima\\nginx-1.18.0\\html\\hmdp\\imgs";
    private static final String DOMAIN = "http://localhost:8082/imgs";

    @Override
    public String upload(InputStream inputStream, String originalFilename, String module) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        String uuid = UUID.randomUUID().toString();
        int d1 = uuid.hashCode() & 0xF;
        int d2 = (uuid.hashCode() >> 4) & 0xF;
        String fileName = StrUtil.format("{}/{}/{}/{}.{}", module, d1, d2, uuid, suffix);
        File dir = new File(UPLOAD_DIR, StrUtil.format("/{}/{}/{}", module, d1, d2));
        if (!dir.exists()) dir.mkdirs();
        FileUtil.writeFromStream(inputStream, new File(UPLOAD_DIR, fileName));
        return DOMAIN + "/" + fileName;
    }

    @Override
    public boolean delete(String fileUrl) {
        String relativePath = fileUrl.replace(DOMAIN, "");
        File file = new File(UPLOAD_DIR, relativePath);
        if (!file.getCanonicalPath().startsWith(new File(UPLOAD_DIR).getCanonicalPath())) {
            return false; // 路径穿越拦截
        }
        return FileUtil.del(file);
    }

    @Override
    public String getDomain() { return DOMAIN; }
}
```

### 3.4 新建：OssFileServiceImpl（替代 OssService）

```java
@Slf4j
@Service
@Profile("prod")  // 仅 prod 激活
public class OssFileServiceImpl implements FileService {

    private final OSS ossClient;
    private final String bucketName;

    public OssFileServiceImpl(OSS ossClient,
                              @Value("${app.oss.bucket}") String bucketName) {
        this.ossClient = ossClient;
        this.bucketName = bucketName;
    }

    @Override
    public String upload(InputStream inputStream, String originalFilename, String module) {
        // ① 提取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // ② 生成 UUID + 散列目录
        String uuid = UUID.randomUUID().toString().replace("-", "");
        int d1 = uuid.hashCode() & 0xF;
        int d2 = (uuid.hashCode() >> 4) & 0xF;
        // ③ Object Key: {module}/{d1}/{d2}/{uuid}.{ext}
        String key = StrUtil.format("{}/{}/{}/{}.{}", module, d1, d2, uuid, suffix);
        // ④ 上传 OSS
        ossClient.putObject(bucketName, key, inputStream);
        return getDomain() + "/" + key;
    }

    @Override
    public boolean delete(String fileUrl) {
        String key = extractKeyFromUrl(fileUrl);
        ossClient.deleteObject(bucketName, key);
        return true;
    }

    @Override
    public String getDomain() {
        return "https://" + bucketName + "." + ossClient.getEndpoint().getHost();
    }
    // ⚠️ 要求 bucket 为公开读。若 bucket 为私有，需替换为 CDN 域名或使用预签名 URL

    /** 从完整 URL 中提取 Object Key */
    private String extractKeyFromUrl(String fileUrl) {
        return URI.create(fileUrl).getPath().substring(1);
    }
}
```

### 3.5 改造：UploadController（注入 FileService）

```java
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private FileService fileService;  // ← 由 Profile 决定注入哪个实现

    private static final Set<String> ALLOWED_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) throws IOException {
        // 文件类型校验
        String ext = StrUtil.subAfter(image.getOriginalFilename(), ".", true).toLowerCase();
        if (!ALLOWED_TYPES.contains(ext))
            return Result.fail("不支持的文件类型，仅允许: " + ALLOWED_TYPES);
        // 文件大小校验
        if (image.getSize() > MAX_FILE_SIZE)
            return Result.fail("文件过大，最大允许 5MB");
        // 委托 FileService
        String url = fileService.upload(image.getInputStream(), image.getOriginalFilename(), "blogs");  // module 名保持与存量数据一致
        return Result.ok(url);  // 返回完整 URL
    }

    @DeleteMapping("/blog/delete")  // ← 问题⑥修复：GET → DELETE
    public Result deleteBlogImg(@RequestParam("url") String fileUrl) {
        fileService.delete(fileUrl);
        return Result.ok();
    }
}
```

---

## 4. 问题 ② 修复：OssConfig 加 @Profile 控制

**现状**：`OssConfig.java` 没有 `@Profile`，dev 环境也会尝试加载，找不到 `aliyun.oss.*` 配置项 → 启动失败。

**修复**：

```java
@Configuration
@Profile("prod")                    // ← 新增：仅 prod 环境加载
public class OssConfig {

    @Value("${app.oss.endpoint}")          // ← 问题③修复：aliyun.oss.* → app.oss.*
    private String endpoint;
    @Value("${app.oss.access-key-id}")
    private String accessKeyId;
    @Value("${app.oss.access-key-secret}")
    private String accessKeySecret;

    @Bean
    public OSS ossClient() {
        // V4 签名 — 不变
        CredentialsProvider credentialsProvider = new DefaultCredentialProvider(accessKeyId, accessKeySecret);
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        return OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(config)
                .build();
    }
}
```

---

## 5. 问题 ③ 修复：配置前缀统一为 app.oss.*

**现状**：

| 来源 | 前缀 | 完整 Key |
|------|------|---------|
| 方案文档 | `app.oss.*` | `app.oss.endpoint` |
| `OssConfig.java` | `aliyun.oss.*` | `aliyun.oss.endpoint` |
| `OssService.java` | `aliyun.oss.*` | `aliyun.oss.bucket-name` |

**修复**：全部统一为 `app.oss.*`，环境变量名对齐已设置的 `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`。

```yaml
# application-prod.yaml 新增
app:
  oss:
    endpoint: ${OSS_ENDPOINT}  # 例如 oss-cn-hangzhou.aliyuncs.com
    bucket: hm-dianping-images
    access-key-id: ${OSS_ACCESS_KEY_ID}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET}
```

---

## 6. 问题 ④ 修复：Object Key 加入目录散列

**现状**：`OssService.uploadFile()` 生成 `UUID_filename.ext`，扁平结构。

**修复**：采用和现有 `UploadController.createNewFileName()` 一致的散列逻辑：

```
{module}/{d1}/{d2}/{uuid}.{ext}
  ↑       ↑    ↑      ↑
blog     0    1    a1b2c3d4.jpg
```

实现见 §3.4 `OssFileServiceImpl.upload()`，与 `LocalFileServiceImpl` 共享同一套散列算法。

---

## 7. 问题 ⑤ 修复：上传文件类型/大小校验

**现状**：`UploadController.uploadImage()` 无任何校验，任意文件 + 任意大小均可上传。

**修复**：在 Controller 层前置校验：

| 校验项 | 规则 | 位置 |
|--------|------|------|
| 文件类型 | 白名单：jpg / jpeg / png / gif / webp | `UploadController.uploadImage()` |
| 文件大小 | ≤ 5MB（`5 * 1024 * 1024`） | 同上 |

实现见 §3.5 `UploadController` 代码。

---

## 8. 问题 ⑥ 修复：删除接口改为 DELETE

**现状**：`@GetMapping("/blog/delete")` — GET 用于删除不符合 REST 规范。

**修复**：改为 `@DeleteMapping("/blog/delete")`，请求方法从 GET 变为 DELETE。

---

## 9. 前端适配

### 9.1 上传接口变化

| 项目 | 改前 | 改后 |
|------|------|------|
| 请求 | `POST /upload/blog` multipart/form-data | 不变 |
| 响应 `data` | 相对路径 `/blogs/0/0/uuid.jpg` | 完整 URL（dev: `http://localhost:8082/imgs/...` / prod: OSS URL） |
| 前端拼接 | 需要拼接 `IMG_BASE_URL + data` | 无需拼接，直接使用 data |
| 删除接口 | `POST /upload/blog/delete?url=...`（原为 GET `?name=...`） | `DELETE /upload/blog/delete?url=...` |

### 9.2 代码改动

```typescript
// 改前
const res = await axios.post('/upload/blog', formData);
const imgUrl = `${IMG_BASE_URL}${res.data.data}`;

// 改后
const res = await axios.post('/upload/blog', formData);
const imgUrl = res.data.data;
```

---

## 10. 存量数据兼容

### 10.1 涉及字段

| 表 | 字段 | 存量示例 |
|----|------|---------|
| `tb_blog` | `images` | `/imgs/blogs/7/14/4771fefb-...jpg` |
| `tb_user` | `icon` | `/imgs/icons/user5-icon.png` |

### 10.2 前端兼容函数

```typescript
function getImageUrl(path: string): string {
    if (!path) return '';
    if (path.startsWith('http')) return path;       // 新数据：完整 URL
    return `http://localhost:8082${path}`;           // 存量老数据：拼接
}
```

---

## 11. 实施步骤

### Step 1 — 改配置 + 加 @Profile
- `OssConfig.java`：加 `@Profile("prod")`，`@Value` 前缀改 `app.oss.*`
- `application-prod.yaml`：新增 `app.oss.*` 配置段

### Step 2 — 新建 FileService 接口 + 双实现
- `service/FileService.java`
- `service/impl/LocalFileServiceImpl.java`
- `service/impl/OssFileServiceImpl.java`

### Step 3 — 改造 UploadController
- 注入 `FileService`，删除旧逻辑
- 加文件类型/大小校验
- `@GetMapping` → `@DeleteMapping`

### Step 4 — 清理死代码
- 删除 `OssController.java`
- 删除 `OssService.java`

### Step 5 — 前端适配
- 去掉前端手动拼接域名的逻辑
- 增加 `getImageUrl()` 函数兼容存量老数据（见 §10）
- 删除接口的请求方式从 GET 改为 DELETE，参数改为 `url`

### Step 6 — 编译验证
```bash
mvn compile
```

---

## 12. 附录

### 12.1 文件变更清单

**后端**：

| 操作 | 文件 | 说明 |
|:----:|------|------|
| ✅ 新建 | `service/FileService.java` | 接口 |
| ✅ 新建 | `service/impl/LocalFileServiceImpl.java` | 本地实现 @Profile("dev") |
| ✅ 新建 | `service/impl/OssFileServiceImpl.java` | OSS 实现 @Profile("prod") |
| ✏️ 修改 | `config/OssConfig.java` | 加 @Profile + 前缀统一 |
| ✏️ 修改 | `controller/UploadController.java` | 注入接口 + 校验 + DELETE |
| ✏️ 修改 | `application-prod.yaml` | 新增 app.oss.* |
| 🗑️ 删除 | `service/OssService.java` | 被替代 |
| 🗑️ 删除 | `controller/OssController.java` | 被替代 |

**前端**（见前端仓库对应文档 `前端开发文档.md` §5.8）：

| 操作 | 说明 |
|:----:|------|
| ✏️ 修改 | 上传响应 `data` 从相对路径改为完整 URL，去掉拼接逻辑 |
| ✏️ 修改 | 删除接口调用方式从 `GET ?name=` 改为 `DELETE ?url=` |
| ✏️ 修改 | 新增 `getImageUrl()` 函数兼容存量老路径 |

### 12.2 环境变量

| 变量名 | 值 | 状态 |
|--------|-----|:----:|
| `OSS_ACCESS_KEY_ID` | `${OSS_ACCESS_KEY_ID}` | ✅ 已设 |
| `OSS_ACCESS_KEY_SECRET` | `${OSS_ACCESS_KEY_SECRET}` | ✅ 已设 |

> ⚠️ **安全警告**：AK/SK 禁止明文写入任何文件。此前此处为明文，已在 2026-07 审查时删除。如已 push 过 git，请立即去 RAM 控制台吊销旧密钥。以后通过环境变量 `${OSS_ACCESS_KEY_ID}` / `${OSS_ACCESS_KEY_SECRET}` 注入。

### 12.3 Maven 依赖（已有）

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.18.4</version>
</dependency>
```

---

## 13. 第2轮审查 — 文档内部一致性问题

> 审查日期：2026-07
> 审查范围：文档自身描述一致性

### 问题 ⑦（🟠 P2）：删除接口参数名不一致

| 位置 | 参数名 | 代码 |
|:----:|:------:|------|
| §3.5 UploadController | `url` | `@RequestParam("url") String fileUrl` |
| §9.1 前端适配表 | `url` | `DELETE /upload/blog/delete?url=...` |
| 现状代码 | `name` | `@RequestParam("name") String filename` |

**影响**：已修复，§3.5 和 §9.1 统一为 `url`。现状代码 `name` 在 Step 3 改造中一并改为 `url`。✅

### 问题 ⑧（🟢 P3）：`blogs` → `blog` 目录名变更未显式说明

| 来源 | 目录名 | 示例 |
|------|:------:|------|
| 现状代码 `UploadController.createNewFileName()` | `blogs` | `/blogs/0/0/uuid.jpg` |
| 新设计 `LocalFileServiceImpl.upload()` | `blogs`（作为 module 参数传入） | `blogs/0/0/uuid.jpg` |
| §5.2 路径格式表 | `blogs` | `http://localhost:8082/imgs/blogs/0/0/uuid.jpg` |
| §10.1 存量示例 | `blogs` | `/imgs/blogs/7/14/4771fefb-...jpg` |

**影响**：已修复，module 统一为 `blogs`，与存量数据一致。✅

### 问题 ⑨（🟢 信息）：文件变更清单缺少前端文件

**影响**：已修复，§12.1 已补充前端变更记录。✅

---

## 14. 第3轮审查 — 后端代码实现核查

> 审查日期：2026-07
> 审查范围：文档 §3-§8 修复方案 vs 后端 Java 代码实际实现

### 14.1 已实现项

| # | 文档要求 | 代码位置 | 状态 |
|---|---------|---------|:----:|
| ①-1 | 新建 `FileService.java` 接口 | `service/FileService.java` — upload/delete/getDomain | ✅ |
| ①-2 | 新建 `LocalFileServiceImpl`（@Profile dev） | `service/impl/LocalFileServiceImpl.java` — 含路径穿越防护 | ✅ |
| ①-3 | 新建 `OssFileServiceImpl`（@Profile prod） | `service/impl/OssFileServiceImpl.java` — 含目录散列 | ✅ |
| ①-4 | 删除 `OssService.java` | 已删除，不复存在 | ✅ |
| ①-5 | 删除 `OssController.java` | 已删除，不复存在 | ✅ |
| ①-6 | UploadController 注入 FileService | `UploadController.java:24` `@Resource FileService` | ✅ |
| ② | OssConfig 加 @Profile("prod") | `OssConfig.java:18` `@Profile("prod")` | ✅ |
| ③ | 配置前缀统一为 `app.oss.*` | `OssConfig.java:21-26` 使用 `app.oss.endpoint/access-key-id/access-key-secret` | ✅ |
| ④ | Object Key 目录散列 | `OssFileServiceImpl.java:40-42` — `{module}/{d1}/{d2}/{uuid}.{ext}` | ✅ |
| ⑤ | 文件类型白名单 jpg/png/gif/webp | `UploadController.java:26` `ALLOWED_TYPES` | ✅ |
| ⑤ | 文件大小限制 ≤5MB | `UploadController.java:27` `MAX_FILE_SIZE = 5*1024*1024` | ✅ |
| ⑥ | 删除接口改为 `@DeleteMapping` | `UploadController.java:51` `@DeleteMapping("/blog/delete")` | ✅ |
| — | 路径穿越防护（LocalFileServiceImpl） | `LocalFileServiceImpl.java:47-57` `canonicalPath.startsWith(uploadDir)` | ✅ |

### 14.2 ~~未实现项~~ ✅ 已全部修复

| # | 文档要求 | 修复方式 |
|---|---------|---------|
| ③ | `application-prod.yaml` 新增 `app.oss.*` 配置段 | ✅ 已追加，见 §14.5 |
| — | `@Value` 硬编码 → Trae 不识别 `app.oss.*` | ✅ 新建 `OssProperties.java`（`@ConfigurationProperties`）+ 修改 `OssConfig` 和 `OssFileServiceImpl` 注入使用 |

### 14.3 文档与代码差异

| 位置 | 文档描述 | 实际代码 | 影响 |
|:----:|---------|---------|:----:|
| §3.4 | `getDomain()` = `bucket + "." + ossClient.getEndpoint().getHost()` | `getDomain()` = `bucket + "." + endpoint`（直接用注入的 endpoint） | 结果一致，不影响功能 |
| §3.5 | `fileService.upload(..., "blog")` | `fileService.upload(..., "blogs")` | 代码用 `blogs`（与存量数据一致），更好 |
| §3.5 | `@RequestParam("url") String fileUrl` | 同 ✅ | 一致 |
| §9.1 | 删除接口 `?url=...` | 同 ✅ | 一致 |

### 14.4 结论

**后端代码实现完成度：96%** — 13/14 项已实现。唯一遗漏：

> 🔴 **`application-prod.yaml` 缺少 `app.oss.*` 配置**。不补上这个，prod profile 启动时 OssConfig 会因找不到配置项而报错，OSS 上传功能完全不可用。

需在 `application-prod.yaml` 追加：
```yaml
app:
  oss:
    endpoint: ${OSS_ENDPOINT}
    bucket: hm-dianping-images
    access-key-id: ${OSS_ACCESS_KEY_ID}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET}
```

### 14.5 审查后修复（2026-07）

| 修复 | 文件 | 说明 |
|:----:|------|------|
| ✅ 补配置 | `application-prod.yaml` | 新增 `app.oss.*` 配置段 |
| ✅ Trae 识别 | 新建 `config/OssProperties.java` | `@ConfigurationProperties(prefix = "app.oss")`，IDE 可自动补全 |
| ✅ 统一数据源 | `config/OssConfig.java` | 从 `@Value` 改为注入 `OssProperties` |
| ✅ 统一数据源 | `service/impl/OssFileServiceImpl.java` | 从 `@Value` 构造参数改为注入 `OssProperties` |
