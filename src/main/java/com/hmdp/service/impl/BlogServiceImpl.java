package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private final IUserService iUserService;
    private final StringRedisTemplate stringRedisTemplate;
    private final IFollowService iFollowService;

    public BlogServiceImpl(IUserService iUserService, BlogMapper blogMapper, StringRedisTemplate stringRedisTemplate, IFollowService iFollowService) {
        this.iUserService = iUserService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.iFollowService = iFollowService;
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = iUserService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        // 查询所有记录
        Set<ZSetOperations.TypedTuple<String>> result = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                key, 0, max, offset, 2);
        if (result == null) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>();
        long minTime = 0L;
        int os = 1;
        // 计算最小时间戳和偏移量
        for (ZSetOperations.TypedTuple<String> stringTypedTuple : result) {
            ids.add(Long.parseLong(Objects.requireNonNull(stringTypedTuple.getValue())));
            long time = Objects.requireNonNull(stringTypedTuple.getScore()).longValue();
            if(time == minTime) ++os;
            else {
                minTime = time;
                os = 1;
            }
        }
        List<Blog> list = lambdaQuery().in(Blog::getId, ids).last("order by field(" + StrUtil.join(",", ids) + ")").list();
        list.forEach(a -> {
            queryBlogUser(a);
            isBlogLiked(a);
        });
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(list);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 查询粉丝
        List<Follow> list = iFollowService.lambdaQuery().eq(Follow::getUserId, user.getId()).list();
        for (Follow follow : list) {
            Long userId = follow.getId();
            // 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (range != null && !range.isEmpty()) {
            List<Long> top5 = range.stream().map(Long::parseLong).collect(Collectors.toList());
            String join = StrUtil.join(",", top5);
            List<User> users = iUserService.lambdaQuery().in(User::getId, top5).last("order by field(id, " + join + ")").list();
            return Result.ok(users);
        } else return Result.ok();
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 判断是否已经点赞
        if(score == null) {
            boolean update = lambdaUpdate().setSql("liked = liked + 1").eq(Blog::getId, id).update();
            if (update) {
                // 根据时间排序
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 已点赞，取消点赞
            lambdaUpdate().setSql("liked = liked - 1").eq(Blog::getId, id).update();
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(BeanUtil.isEmpty(blog)){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        // 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        Long id = blog.getId();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        String key = "blog:liked:" + id;
        blog.setIsLike(stringRedisTemplate.opsForZSet().score(key, userId.toString()) != null);
    }
}