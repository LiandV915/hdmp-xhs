package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation
.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> VIEW_SCRIPT;

    static {
        VIEW_SCRIPT= new DefaultRedisScript<>();
        VIEW_SCRIPT.setLocation(new ClassPathResource("blog_view.lua"));
        VIEW_SCRIPT.setResultType(Long.class);
    }
    /**
     浏览量更新：
     所有用户都会执行，增加 blog:view 的分数。
     热度更新：
     仅登录用户的 首次浏览 才会增加 blog:hot 和标签权重。
     用户兴趣画像更新：
     每次用户浏览博客时，分析博客的标签，更新用户的兴趣画像（user:interest:{userId}）。
     我们使用 ZSet 来存储标签和它们的权重，权重值（score）可以根据实际的浏览次数或兴趣强度调整。
     * @param blogId
     * @return
     */
    @Override
    public Result getBlogById(Long blogId) {
        Blog blog = getById(blogId);
        if (blog == null) return Result.fail("博客不存在");
        UserDTO user = UserHolder.getUser();
        Long userId = user != null ? user.getId() : null;
        // ===============================
        // 1️⃣ PV统计 —— 只走Redis
        // ===============================
        Double pv = stringRedisTemplate.opsForZSet()
                .incrementScore("blog:view", blogId.toString(), 1);
        if (pv != null) {
            blog.setViewCounts((int) pv.longValue());
        }
        // ===============================
        // 2️⃣ 登录用户：首次浏览统计
        // ===============================
        if (userId != null) {
            String viewKey = "user:view:" + userId;
            // 标签 Java 拆分
            List<String> tags = new ArrayList<>();
            if (StrUtil.isNotBlank(blog.getTags())) {
                tags = Arrays.stream(blog.getTags().split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .toList();
            }
            // KEYS
            List<String> keys = Arrays.asList(
                    viewKey,
                    "blog:hot",
                    "blog:tag:",
                    "user:interest:" + userId
            );
            // ARGV
            List<String> args = new ArrayList<>();
            args.add(blogId.toString());
            args.add(String.valueOf(86400)); // 1天TTL
            args.addAll(tags);
            // 执行Lua
            stringRedisTemplate.execute(
                    VIEW_SCRIPT,
                    keys,
                    args.toArray(new String[0])
            );

            // ===============================
            // 3️⃣ 点赞状态
            // ===============================
            Boolean liked = stringRedisTemplate.opsForSet()
                    .isMember("user:like:" + userId, blogId.toString());
            blog.setIsLike(Boolean.TRUE.equals(liked));
        } else {
            blog.setIsLike(false);
        }

        return Result.ok(blog);
    }


    /**
     *对于未登录的用户，推荐全局热门博客。
     * 对于登录用户，根据其兴趣标签推荐相关的博客，并去除已浏览和已推荐的博客，避免重复推荐。
     * 处理冷启动问题（新用户的推荐），如果推荐的博客不足，补充热门博客。
     * 排除用户自己发布的博客，避免推荐自己的内容。
     * 按照热度对推荐的博客进行排序，并更新已推荐集合。
     * @param userId
     * @param size
     * @return
     */
    @Override
    public List<Blog> recommendBlogs(Long userId, int size) {
        // 1️⃣ 游客模式
        if (userId == null) {
            Set<String> topHot = stringRedisTemplate.opsForZSet()
                    .reverseRange("blog:hot", 0, size - 1);
            return listByIds(topHot);
        }
        Set<String> candidateBlogIds = new LinkedHashSet<>();//待推送blog
        // 2️⃣ 获取用户兴趣标签（优化：使用ZRANGE带分数）
        Set<String> topTags = stringRedisTemplate.opsForZSet()
                .reverseRange("user:interest:" + userId, 0, 4);
        // 3️⃣ 标签召回（优化：限制每个标签召回数量，避免过多）
        int perTagLimit = Math.max(3, size / Math.max(topTags.size(), 1));
        if (topTags != null && !topTags.isEmpty()) {
            for (String tag : topTags) {
                Set<String> blogs = stringRedisTemplate.opsForZSet()
                        .reverseRange("blog:tag:" + tag, 0, perTagLimit - 1);
                if (blogs != null) candidateBlogIds.addAll(blogs);
            }
        }
// 4️⃣ 批量获取排除集合(用户看过的，之前推送过的，用户自己写的）
        Set<String> excludeIds = new HashSet<>();
        //pipeline 的核心价值就是：减少网络 RTT，避免大量来回的 Redis 请求。
        List<Object> results = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.opsForSet().members("user:view:" + userId);
                operations.opsForSet().members("user:recommend:shown:" + userId);
                operations.opsForSet().members("user:blog:" + userId);
                return null;
            }
        });
// 把 pipeline 返回的 3 个 Set<String> 合并到 excludeIds
        for (Object obj : results) {
            if (obj instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) obj;
                if (set != null) excludeIds.addAll(set);
            }
        }
        // 5️⃣ 排除处理
        candidateBlogIds.removeAll(excludeIds);
        // 6️⃣ 冷启动处理（优化：先排除再补充）
        if (candidateBlogIds.size() < size) {
            int needMore = size - candidateBlogIds.size();
            // 获取热门但未排除的博客
            Set<String> allHot = stringRedisTemplate.opsForZSet()
                    .reverseRange("blog:hot", 0, size * 2 - 1); // 多取一些备用
            if (allHot != null) {
                allHot.stream()
                        .filter(id -> !excludeIds.contains(id))//不在排除的集合中
                        .limit(needMore)//不超过数目
                        .forEach(candidateBlogIds::add);
            }
        }
        // 7️⃣ 按热度排序（优化：批量获取分数）
        if (candidateBlogIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 批量获取热度分数
        Map<String, Double> hotScores = new HashMap<>();
        stringRedisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                for (String blogId : candidateBlogIds) {
                    connection.zSetCommands().zScore(
                            "blog:hot".getBytes(),
                            blogId.getBytes()
                    );
                }
                return null;
            }
        });

        // 排序
        List<String> sortedBlogIds = candidateBlogIds.stream()
                .sorted((b1, b2) -> {
                    Double s1 = hotScores.get(b1);
                    Double s2 = hotScores.get(b2);
                    return Double.compare(
                            s2 != null ? s2 : 0,
                            s1 != null ? s1 : 0
                    );
                })
                .limit(size)
                .collect(Collectors.toList());
        // 8️⃣ 更新已推荐集合（优化：设置过期时间，控制集合大小）
        if (!sortedBlogIds.isEmpty()) {
            String shownKey = "user:recommend:shown:" + userId;
            stringRedisTemplate.opsForSet().add(shownKey,
                    sortedBlogIds.toArray(new String[0]));
            // 控制集合大小，避免无限增长
            Long shownSize = stringRedisTemplate.opsForSet().size(shownKey);
            if (shownSize != null && shownSize > 1000) {
                // 随机移除一些旧记录，或使用LRU策略
                stringRedisTemplate.opsForSet().pop(shownKey, shownSize - 800);
            }
            // 设置过期时间（例如7天）
            stringRedisTemplate.expire(shownKey, 7, TimeUnit.DAYS);
        }

        // 9️⃣ 查询数据库
        return listByIds(sortedBlogIds);
    }


    /**
     * 登录用户对某个博客的点赞进行修改（可增可减）
     * @param blogId
     * @return
     */
    @Override
    @Transactional
    public Result updateLikedCounts(Long blogId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String userLikeKey = "user:like:" + userId;
        String blogLikedKey = "blog:liked";
        String blogHotKey = "blog:hot";

        Blog blog = getById(blogId);
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        //判断这篇博客是否在用户点赞的集合里
        boolean isLiked = Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(userLikeKey, blogId.toString()));
        if (!isLiked) {
            // -------------------------------
            // 点赞操作
            // -------------------------------
            // 1️⃣ 数据库点赞数 +1
            update().setSql("liked = liked + 1").eq("id", blogId).update();
            // 2️⃣ Redis 用户点赞集合 + 全局点赞 ZSet + 热度 ZSet
            stringRedisTemplate.opsForSet().add(userLikeKey, blogId.toString());
            stringRedisTemplate.opsForZSet().incrementScore(blogLikedKey, blogId.toString(), 1);
            stringRedisTemplate.opsForZSet().incrementScore(blogHotKey, blogId.toString(), 3); // 点赞加权
            // 3️⃣ 标签热度 & 用户兴趣
            if (blog.getTags() != null && !blog.getTags().isEmpty()) {
                for (String tag : blog.getTags().split(",")) {
                    stringRedisTemplate.opsForZSet().incrementScore("blog:tag:" + tag, blogId.toString(), 3);
                    stringRedisTemplate.opsForZSet().incrementScore("user:interest:" + userId, tag, 3);
                }
            }
        } else {
            // -------------------------------
            // 取消点赞
            // -------------------------------
            update().setSql("liked = liked - 1").eq("id", blogId).update();
            stringRedisTemplate.opsForSet().remove(userLikeKey, blogId.toString());
            stringRedisTemplate.opsForZSet().incrementScore(blogLikedKey, blogId.toString(), -1);
            stringRedisTemplate.opsForZSet().incrementScore(blogHotKey, blogId.toString(), -3);
            // 标签热度 & 用户兴趣
            if (blog.getTags() != null && !blog.getTags().isEmpty()) {
                for (String tag : blog.getTags().split(",")) {
                    stringRedisTemplate.opsForZSet().incrementScore("blog:tag:" + tag, blogId.toString(), -3);
                    stringRedisTemplate.opsForZSet().incrementScore("user:interest:" + userId, tag, -3);
                }
            }
        }

        return Result.ok();
    }


    /**
     * 查找某个blog的点赞
     *
     * @param id
     * @return
     */
    @Override
    public Result getBlogLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range("blog:liked" + id, 0, -1);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2.解析除其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3.根据用户id查询用户  将user处理为userDTO对象    where id (5 , 1)   order by field(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);
    }
}
