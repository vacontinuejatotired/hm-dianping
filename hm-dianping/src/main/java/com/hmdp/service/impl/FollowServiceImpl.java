package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryNotFollow(Long id) {
    Long userId=UserHolder.getUser().getId();
    Integer count=query().eq("user_id",userId).eq("follow_user_id",id).count();
        return Result.ok(count>0);
    }

    @Override
    public Result follow(Long id, Boolean isfollow) {

        if (isfollow) {
            Follow follow = new Follow();
            follow.setUserId(UserHolder.getUser().getId());
            follow.setFollowUserId(id);
            save(follow);
        }
        else {
            Long userId = UserHolder.getUser().getId();
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",id));
        }
        return Result.ok();
    }
}
