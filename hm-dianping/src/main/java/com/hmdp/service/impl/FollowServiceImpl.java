package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    @Resource
    private IUserService userService;
    @Override
    public Result queryNotFollow(Long id) {
    Long userId=UserHolder.getUserId();
    Integer count=query().eq("user_id",userId).eq("follow_user_id",id).count();
        return Result.ok(count>0);
    }

    @Override
    public Result follow(Long id, Boolean isfollow) {
//传过来的id是被关注的人的id
        if (isfollow) {
            Follow follow = new Follow();
            follow.setUserId(UserHolder.getUserId());
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                String key="follows:"+UserHolder.getUserId();
                stringRedisTemplate.opsForSet().add(key,id.toString());
            }
        }
        else {
            Long userId = UserHolder.getUserId();
            boolean isRemove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if (isRemove) {
                String key="follows:"+UserHolder.getUserId();
                stringRedisTemplate.opsForSet().remove(key,id.toString());
            }
        }
        return Result.ok();
    }


    @Override
    public Result queryCommonFollow(Long id) {
        String FollowKey="follows:"+id;
        String UserKey="follows:"+UserHolder.getUserId().toString();
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(FollowKey, UserKey);
        if(intersect==null|| intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonUserListId = intersect.stream().map(Long::valueOf).toList();
        List<UserDTO> userDTOList = userService.listByIds(commonUserListId).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).toList();
        return Result.ok(userDTOList);
    }

}
