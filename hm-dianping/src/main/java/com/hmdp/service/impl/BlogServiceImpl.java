package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        setUserToBlog(blog);
        isLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotById(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.setUserToBlog(blog);
            this.isLiked(blog);
        });
        return Result.ok(records);
    }

    private void setUserToBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isLiked(Blog blog) {
        Long user = UserHolder.getUserId();
        if (user == null) {
            return;
        }
        Long userId = UserHolder.getUserId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    //TODO 点赞有bug  ，一人一赞没实现，还有取消点赞再点还是取消
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUserId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            //更新数据库
            boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
            if (update) {
                //写入缓存
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean update = update().setSql("liked = liked - 1").eq("id", id).update();
            if (update) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryUserList(Long id) {
        Set<String> userDTOList = new HashSet<>();
        Blog blog = getById(id);
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        userDTOList = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (userDTOList == null || userDTOList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = new ArrayList<>();
        userIds = userDTOList.stream().map(Long::valueOf).toList();
        String idStr = StringUtil.join(userIds, ",");

        List<UserDTO> userDTOS = userService.query()
                .in("id", userDTOList)
                .last("order by field (id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        Long user = UserHolder.getUserId();
        blog.setUserId(user);
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        //拿到所有的粉丝账号id
        List<Follow> followsUserId = followService.query().eq("follow_user_id", user).list();
        for (Follow follow : followsUserId) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
//        pushBloToFansBatch(followsUserId, blog.getId());
        return Result.ok();
    }
    //抽取方法，实现批量插入，防止N次连接
    private void  pushBloToFansBatch(List<Follow> follows,Long blogId) {
        if (follows == null || follows.isEmpty()) {
            log.info("Empty follows");
            return;
        }
        log.info("开始批量插入");
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connect ->{
            Long followUserId ;
            double now = System.currentTimeMillis();
            String key;
            byte []keyByte;
            byte []valueByte= blogId.toString().getBytes();
        for (Follow follow : follows) {
            followUserId = follow.getUserId();
            key = "feed:"+followUserId;
            keyByte = key.getBytes(StandardCharsets.UTF_8);
            connect.zAdd(keyByte,now,valueByte);
        }
      return null;
        } );
        return ;
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //拿到最小时间戳
        int pageSize = SystemConstants.MAX_PAGE_SIZE;
        String key = "feed:"+UserHolder.getUserId();
        ScrollResult result = new ScrollResult();
        Set<ZSetOperations.TypedTuple<String>> scores = stringRedisTemplate.opsForZSet().rangeByScoreWithScores(key,0, max, offset,2);
        if(scores == null || scores.isEmpty()) {
            return Result.ok();
        }
        long min=0;
        List<Long>ids=new ArrayList<>(scores.size());
        int os=1;
        for (ZSetOperations.TypedTuple<String> score : scores) {
            Double scoreScore = score.getScore();
            ids.add(Long.valueOf(Objects.requireNonNull(score.getValue())));
            long time= Objects.requireNonNull(score.getScore()).longValue();
            //统计最小时间时的相同个数
            //注意已经提前降序排序
            if (time ==min){
            os++;
            }
            else if(time<min){
                min=time;
                os=1;
            }
        }
        String idStr=StringUtil.join(ids, ",");
        List<Blog> blogList = query().in("id", ids).
                last("order by field(id," + idStr + ")")
                .list();
        log.info("已查询到博客");
        for (Blog blog : blogList) {
        setUserToBlog(blog);
        isLiked(blog);
        }
        result.setOffset(os);
        result.setMinTime(min);
        result.setList(blogList);
        log.info("即将返回页面对象{}",result);

        return Result.ok(result);
    }
}
