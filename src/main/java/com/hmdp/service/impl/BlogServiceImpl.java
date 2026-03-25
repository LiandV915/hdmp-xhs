package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BlogSaveDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;


import jakarta.annotation.Resource;

import java.time.LocalDateTime;
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

    @Resource
    CacheClient cacheClient;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private BlogMapper blogMapper;

    private static final DefaultRedisScript<Long> VIEW_SCRIPT;

    static {
        VIEW_SCRIPT= new DefaultRedisScript<>();
        VIEW_SCRIPT.setLocation(new ClassPathResource("blog_view.lua"));
        VIEW_SCRIPT.setResultType(Long.class);
    }
    /**
     浏览量更新：
     那就是相当于，对于没登录用户无法增加热度，只增加浏览量了呗。
     对于一登录用户会判断用户是否看过这个文章，如果看过的话就在增加浏览量的基础上增加热度。那
     么也就是，一篇文章的浏览量可以被无限制的刷，但是用户浏览这一操作只能给文章增加一个热度呗
     用户兴趣画像更新：
     每次用户浏览博客时，分析博客的标签，更新用户的兴趣画像（user:interest:{userId}）。
     我们使用 ZSet 来存储标签和它们的权重，权重值（score）可以根据实际的浏览次数或兴趣强度调整。
     同时将文章加入user：view：{userId}中，以避免重复推荐。
     * @param blogId
     * @return
     */
    @Override
    public Result getBlogById(Long blogId) {
        // 1️⃣ 先查缓存（旁路缓存 + 空对象防穿透）
        Blog blog = cacheClient.queryPassThrough(
                "blog:cache:",
                blogId,
                Blog.class,
                blogMapper::selectById,
                600L,
                TimeUnit.SECONDS
        );
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        UserDTO user = UserHolder.getUser();
        Long userId = user != null ? user.getId() : null;

        // ===============================
        // 2️⃣ PV统计 —— 所有人增加
        // ===============================
        Double pv = stringRedisTemplate.opsForZSet()
                .incrementScore("blog:view", blogId.toString(), 1);
        stringRedisTemplate.opsForSet().add("blog:dirty:view", blogId.toString());

        // 加入脏数据集合，用于定时任务同步
        if (pv != null) {
            blog.setViewCounts(pv.intValue());
        }

        // ===============================
        // 3️⃣ 登录用户：首次浏览才增加当日热度 + 更新兴趣画像
        // ===============================
        if (userId != null) {
            List<String> tags = new ArrayList<>();
            if (StrUtil.isNotBlank(blog.getTags())) {
                tags = Arrays.stream(blog.getTags().split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .toList();
            }
            // 当日热度桶 key，例如 blog:hot:20260325
            String todayHotKey = "blog:hot:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            List<String> keys = List.of(
                    "user:view:" + userId,          // KEYS[1]
                    todayHotKey,                   // KEYS[2]
                    "user:interest:" + userId      // KEYS[3]
            );

            List<String> args = new ArrayList<>();
            args.add(blogId.toString());            // ARGV[1] blogId
            args.add(String.valueOf(86400));        // ARGV[2] user:view 集合TTL，1天
            args.addAll(tags);                      // ARGV[3...] 标签列表

            stringRedisTemplate.execute(
                    VIEW_SCRIPT,
                    keys,
                    args.toArray(new String[0])
            );

            // ===============================
            // 4️⃣ 点赞状态
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
        // 1️⃣ 游客模式，直接推送热度最高的
        if (userId == null) {
            Set<String> topHot = stringRedisTemplate.opsForZSet()
                    .reverseRange("blog:hot", 0, size - 1);
            return listByIds(topHot);
        }
        Set<String> candidateBlogIds = new LinkedHashSet<>();//待推送blog
        // 2️⃣ 获取用户兴趣标签（优化：使用ZRANGE带分数）,取用户最感兴趣的前5标签
        Set<String> topTags = stringRedisTemplate.opsForZSet()
                .reverseRange("user:interest:" + userId, 0, 4);
        // 3️⃣ 标签召回（优化：限制每个标签召回数量，避免过多），每个标签召回三个
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
                operations.opsForSet().members("recommend:shown:" + userId);
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
        // 6️⃣ 如果推荐不足：冷启动处理（优化：先排除再补充）
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
        // 7️⃣ 按热度排序
        if (candidateBlogIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 批量获取热度分数
        Map<String, Double> hotScores = new HashMap<>();
        List<String> blogIdList = new ArrayList<>(candidateBlogIds);
        List<Object> scores = stringRedisTemplate.executePipelined(
                new RedisCallback<Object>() {
                    @Override
                    public Object doInRedis(RedisConnection connection) {
                        for (String blogId : blogIdList) {
                            connection.zSetCommands().zScore(
                                    "blog:hot".getBytes(),
                                    blogId.getBytes()
                            );
                        }
                        return null;
                    }
                }
        );
// 解析 pipeline 返回值
        for (int i = 0; i < blogIdList.size(); i++) {
            Object score = scores.get(i);
            if (score != null) {
                hotScores.put(blogIdList.get(i),
                        Double.valueOf(score.toString()));
            }
        }

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
     * 不建议每次访问直接落库，原因：
     * 访问频率高：每次浏览落库，数据库写压力大
     * 批量更新效率低：小量频繁写容易造成锁竞争
     * 缓存/排行榜系统依赖 Redis：热数据直接落库没有意义
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
        String userLikeKey = "user:like:" + userId; // 用户已点赞文章集合
        String blogLikedKey = "blog:liked";         // 博客点赞数统计
        String blogHotKey = "blog:hot:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE); // 当天热度桶

        Blog blog = getById(blogId);
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        // 判断是否已点赞
        boolean isLiked = Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(userLikeKey, blogId.toString())
        );
        if (!isLiked) {
            // -------------------------------
            // 点赞
            // -------------------------------
            stringRedisTemplate.opsForSet().add(userLikeKey, blogId.toString());
            stringRedisTemplate.opsForZSet().incrementScore(blogLikedKey, blogId.toString(), 1);
            stringRedisTemplate.opsForZSet().incrementScore(blogHotKey, blogId.toString(), 3); // 点赞热度 +3
            // 标签热度 & 用户兴趣
            if (blog.getTags() != null && !blog.getTags().isEmpty()) {
                for (String tag : blog.getTags().split(",")) {
                    tag = tag.trim();
                    if (!tag.isEmpty()) {
                        stringRedisTemplate.opsForZSet().incrementScore("user:interest:" + userId, tag, 3);
                    }
                }
            }
        } else {
            // -------------------------------
            // 取消点赞
            // -------------------------------
            stringRedisTemplate.opsForSet().remove(userLikeKey, blogId.toString());
            stringRedisTemplate.opsForZSet().incrementScore(blogLikedKey, blogId.toString(), -1);
            stringRedisTemplate.opsForZSet().incrementScore(blogHotKey, blogId.toString(), -3); // 当天热度 -3
            // 标签热度 & 用户兴趣
            if (blog.getTags() != null && !blog.getTags().isEmpty()) {
                for (String tag : blog.getTags().split(",")) {
                    tag = tag.trim();
                    if (!tag.isEmpty()) {
                        stringRedisTemplate.opsForZSet().incrementScore("user:interest:" + userId, tag, -3);
                    }
                }
            }
        }

        stringRedisTemplate.opsForSet().add("blog:dirty:liked", blogId.toString());
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

    /**
     * 保存blog同时构造向量文本存储到数据库
     * 并且把帖子的标签存入redis中。用于定时任务更新标签热度榜
     * @param blog
     */
    @Override
    @Transactional
    public void saveBlogWithVector(Blog blog) {
        // 1. 初始化字段
        blog.setLiked(0);
        blog.setComments(0);
        blog.setViewCounts(0);
        // 2. 保存到 MySQL
        this.save(blog);

        // 3. 构造向量文本
        String content = buildBlogVectorText(blog);

        // 4. 构造 Document 并写入向量库
        Document document = new Document(
                content,
                Map.of(
                        "blogId", blog.getId(),
                        "shopId", blog.getShopId(),
                        "userId", blog.getUserId(),
                        "tags", blog.getTags() == null ? "" : blog.getTags()
                )
        );
        vectorStore.add(List.of(document));
        // 5. 写入 Redis blog:tags
        if (blog.getTags() != null && !blog.getTags().isEmpty()) {
            String redisKey = "blog:tags:" + blog.getId();
            // tags 用逗号或 List 分割
            String[] tags = blog.getTags().split(",");
            stringRedisTemplate.opsForSet().add(redisKey, tags);
            // TTL 设置为永不过期（数据库是主存储）
            // 如果想设置TTL，可以用 Duration.ofDays(30) 之类
            // stringRedisTemplate.expire(redisKey, Duration.ofDays(30));
        }
    }

    /**
     * 博客 → 向量文本
     */
    private String buildBlogVectorText(Blog blog) {
        StringBuilder sb = new StringBuilder();
        sb.append("探店标题：").append(blog.getTitle()).append("。");
        sb.append("探店内容：").append(blog.getContent()).append("。");
        if (blog.getTags() != null) {
            sb.append("标签：").append(blog.getTags()).append("。");
        }
        return sb.toString();
    }

}
