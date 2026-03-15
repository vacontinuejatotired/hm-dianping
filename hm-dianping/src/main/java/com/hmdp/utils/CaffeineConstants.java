package com.hmdp.utils;

public interface CaffeineConstants {
    static String USERINFO_CACHE_KEY = "cache:userinfo:";
    static Long USERINFO_CACHE_TTL_MINUTES = 20L;
    static Long USERINFO_ASYNC_REFRESH_THRESHOLD_MINUTES = 10L;
    static Long USERINFO_CACHE_TTL_SECONDS = (60*20L);
}
