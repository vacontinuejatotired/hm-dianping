package com.hmdp.config;

import com.hmdp.utils.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(DefaultResourceLoader defaultResourceLoader) {
        JwtUtil jwtUtil = new JwtUtil(defaultResourceLoader);
        return jwtUtil;
    }
}
