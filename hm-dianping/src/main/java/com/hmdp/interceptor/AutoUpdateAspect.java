package com.hmdp.interceptor;


import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;

@Aspect
@Component
@Slf4j
public class AutoUpdateAspect {
    @Resource
    private IUserService userService;


    @Pointcut("@annotation(AutoUpdateTime)")
    public void pointcut() {}

    @Pointcut("@annotation(AutoUpdateTime)")
    public void test(){}

    @Before("test()")
    public void beforeTest(){
        log.info("test before");
    }

    @Around("test()")
    public void aroundTest(ProceedingJoinPoint joinPoint) throws Throwable {
        LocalDateTime now = LocalDateTime.now();
        log.info("{}:aroundTest before",now);
        joinPoint.proceed();
        LocalDateTime end = LocalDateTime.now();
        log.info("{}:aroundTest after",end);
        Long costTime = Duration.between(now, end).toMillis();
        log.info("耗时{}ms",costTime);
    }
    @Before("pointcut()")
    public void before(JoinPoint joinPoint) {
        log.info("{}自动插入开始",LocalDateTime.now());
    }

    @AfterReturning("pointcut()")
    public void autoUpdateTime(JoinPoint joinPoint) {
        if(UserHolder.getUserId() == null) {
            log.info("用户信息未传递");
            return;
        }
        Long userId  = UserHolder.getUserId();

        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
       AutoUpdateTime annotation  = methodSignature.getMethod().getAnnotation(AutoUpdateTime.class);
        boolean printLog = annotation.printLog();
        if(userId == null){
            if (printLog) {
                log.info("更新插入时间失败，userId为null");
            }
            return;
        }
        userService.update().eq("id", userId).set("update_time",LocalDateTime.now()).update();
        if (printLog) {
            log.info("userId:{}更新插入时间成功", userId);
        }
        log.info("{}自动插入结束",LocalDateTime.now());
    }

}
