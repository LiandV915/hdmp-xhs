package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl
        extends ServiceImpl<BlogCommentsMapper, BlogComments>
        implements IBlogCommentsService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IBlogService blogService;

    /**
     * 新增评论
     * 1 保存评论到 MySQL
     * 2 判断用户是否第一次评论该 blog
     * 3 如果是第一次 → comment数 +1 + 热度+5
     * 4 如果不是 → 我们允许用户多次评论以保持真实评论量，但为了防止刷热度
     * 用 Redis Set 记录每个用户评论过的 blogId，第一次评论增加热度，其后评论只增加评论数，不影响热度。
     * @param dto
     * @return
     */
    @Override
    public Result addComment(CommentDTO dto) {

        Long userId = UserHolder.getUser().getId();
        Long blogId = dto.getBlogId();

        // 1 创建评论对象
        BlogComments comment = new BlogComments();
        comment.setUserId(userId);
        comment.setBlogId(blogId);
        comment.setParentId(dto.getParentId());
        comment.setAnswerId(dto.getAnswerId());
        comment.setContent(dto.getContent());
        comment.setLiked(0);
        comment.setStatus(false);

        // 2 保存评论
        save(comment);

        // 3 判断是否第一次评论
        String key = "user:comment:" + userId;
        Boolean isFirst = stringRedisTemplate.opsForSet()
                .add(key, blogId.toString()) == 1;
        if (Boolean.TRUE.equals(isFirst)) {
            // 热度 +5
            stringRedisTemplate.opsForZSet()
                    .incrementScore("blog:hot"+ LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                            blogId.toString(),
                            5);
        }
        // 评论数 +1
        stringRedisTemplate.opsForZSet()
                .incrementScore("blog:comment",
                        blogId.toString(),
                        1);

        // 4 更新用户兴趣画像
        updateUserInterest(userId, blogId);
        stringRedisTemplate.opsForSet().add("blog:dirty:comment", blogId.toString());
        return Result.ok();
    }

    /**
     * 更新用户兴趣画像，user：interest
     * @param userId
     * @param blogId
     */
    public void updateUserInterest(Long userId, Long blogId){
        Blog blog = blogService.getById(blogId);
        if(blog == null || blog.getTags() == null){
            return;
        }
        String[] tags = blog.getTags().split(",");

        for(String tag : tags){
            stringRedisTemplate.opsForZSet()
                    .incrementScore(
                            "user:interest:" + userId,
                            tag,
                            5
                    );

        }
    }

}