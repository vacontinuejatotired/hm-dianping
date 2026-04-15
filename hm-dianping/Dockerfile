# 使用 JDK 17
FROM openjdk:17

# 设置工作目录（先设置目录）
WORKDIR /app

# 复制 JAR 文件 - 确保文件名和 target 目录下的一致
COPY target/hm-dianping-2.7.18.jar app.jar

# 创建配置目录（用于挂载外部配置文件）
RUN mkdir -p /app/config

# 暴露端口
EXPOSE 8082

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 启动命令 - 优先加载外部配置文件
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar", "--spring.config.location=/app/config/application.yml"]