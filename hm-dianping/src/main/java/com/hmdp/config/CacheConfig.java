package com.hmdp.config;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.entity.UserinfoCache;
import com.hmdp.utils.BatchLoadCache;
import com.hmdp.utils.CaffeineConstants;
import lombok.NonNull;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;

import javax.annotation.Resource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Resource
    @Lazy
    private BatchLoadCache batchLoadCache;

    @Bean("userinfoCache")
    public LoadingCache<String, UserinfoCache> userinfoCache() {
        return Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(CaffeineConstants.USERINFO_CACHE_TTL_MINUTES, TimeUnit.MINUTES)
                .initialCapacity(1000).recordStats().refreshAfterWrite(10,TimeUnit.MINUTES).build(new CacheLoader<String, UserinfoCache>() {
                    @Override
                    public @Nullable UserinfoCache load(@NonNull String key) throws Exception {
                        // 缓存缺失时加载（第一次调用）
                        Long userId = Long.valueOf(key.substring(CaffeineConstants.USERINFO_CACHE_KEY.length()));
                        batchLoadCache.saveFuture(userId);
                        UserinfoCache userinfoCache = new UserinfoCache();
                        userinfoCache.setId(userId);
                        userinfoCache.setIcon("");
                        userinfoCache.setNickName("");
                        return userinfoCache;
                    }

                    @Override
                    public @NonNull CompletableFuture<UserinfoCache> asyncReload(
                            @NonNull String key,
                            @NonNull UserinfoCache oldValue,
                            @NonNull Executor executor) {

                        Long userId = Long.parseLong(key.replace(CaffeineConstants.USERINFO_CACHE_KEY, ""));
                        // 刷新时也只需要触发异步更新
                        return CompletableFuture.supplyAsync(() -> {
                            batchLoadCache.saveFuture(userId);  // 再次触发
                            return oldValue;  // 刷新期间返回旧值
                        }, executor);
                    }
                });
    }
}
