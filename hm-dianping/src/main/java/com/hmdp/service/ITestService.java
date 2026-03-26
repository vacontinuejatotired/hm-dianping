package com.hmdp.service;

import com.hmdp.dto.Result;

public interface ITestService {

    Result restart(Long num,Long voucherId);

    Result generateTestToken(Long num,String fileName);

    Result checkSnowFlake(int num);
}
