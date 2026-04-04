package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.CommentVO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Resource
    private IUserService userService;

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
        comment.setParentId(dto.getParentId() != null ? dto.getParentId() : 0L);
        comment.setAnswerId(dto.getAnswerId() != null ? dto.getAnswerId() : 0L);
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
     */
    public void updateUserInterest(Long userId, Long blogId){
        Blog blog = blogService.getById(blogId);
        if(blog == null || blog.getTags() == null){
            return;
        }
        String[] tags = blog.getTags().split(",");
        for(String tag : tags){
            stringRedisTemplate.opsForZSet()
                    .incrementScore("user:interest:" + userId, tag, 5);
        }
    }

    // ================================================================
    // 查询博客评论列表（支持根评论分页 + 子评论展开）
    // ================================================================
    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer current) {
        // 查询根评论（parentId = 0）
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("parent_id", 0)
                .eq("status", false)
                .orderByDesc("liked")
                .orderByAsc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        List<BlogComments> rootComments = page.getRecords();
        if (rootComments.isEmpty()) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        // 批量获取根评论 id
        List<Long> rootIds = rootComments.stream().map(BlogComments::getId).collect(Collectors.toList());

        // 查询子评论（parentId in rootIds）
        List<BlogComments> childComments = query()
                .in("parent_id", rootIds)
                .eq("status", false)
                .orderByAsc("create_time")
                .list();

        // 收集所有用户 id
        Set<Long> userIds = rootComments.stream().map(BlogComments::getUserId).collect(Collectors.toSet());
        childComments.forEach(c -> userIds.add(c.getUserId()));

        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Long loginUserId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;

        // 子评论按 parentId 分组
        Map<Long, List<CommentVO>> childMap = childComments.stream()
                .map(c -> toVO(c, userMap, loginUserId))
                .collect(Collectors.groupingBy(CommentVO::getParentId));

        // 组装根评论 VO
        List<CommentVO> voList = rootComments.stream().map(r -> {
            CommentVO vo = toVO(r, userMap, loginUserId);
            vo.setChildren(childMap.getOrDefault(r.getId(), Collections.emptyList()));
            return vo;
        }).collect(Collectors.toList());

        return Result.ok(voList, page.getTotal());
    }

    private CommentVO toVO(BlogComments c, Map<Long, User> userMap, Long loginUserId) {
        CommentVO vo = BeanUtil.copyProperties(c, CommentVO.class);
        User u = userMap.get(c.getUserId());
        if (u != null) {
            vo.setNickName(u.getNickName());
            vo.setIcon(u.getIcon());
        }
        // 是否已点赞
        if (loginUserId != null) {
            Boolean liked = stringRedisTemplate.opsForSet()
                    .isMember("comment:like:" + loginUserId, c.getId().toString());
            vo.setIsLike(Boolean.TRUE.equals(liked));
        } else {
            vo.setIsLike(false);
        }
        return vo;
    }

    // ================================================================
    // 删除评论（仅评论作者或博客作者可操作）
    // ================================================================
    @Override
    public Result deleteComment(Long id) {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;
        if (userId == null) {
            return Result.fail("请先登录");
        }
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        Blog blog = blogService.getById(comment.getBlogId());
        boolean isBlogAuthor = blog != null && blog.getUserId().equals(userId);
        if (!comment.getUserId().equals(userId) && !isBlogAuthor) {
            return Result.fail("无权删除该评论");
        }
        // 物理删除评论及其子评论
        removeById(id);
        remove(query().getWrapper().eq("parent_id", id));
        // 评论数 -1
        stringRedisTemplate.opsForZSet().incrementScore("blog:comment", comment.getBlogId().toString(), -1);
        stringRedisTemplate.opsForSet().add("blog:dirty:comment", comment.getBlogId().toString());
        return Result.ok();
    }

    // ================================================================
    // 评论点赞 / 取消点赞
    // key: comment:like:{userId}  value: Set<commentId>
    // ================================================================
    @Override
    public Result likeComment(Long id) {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;
        if (userId == null) {
            return Result.fail("请先登录");
        }
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        String likeKey = "comment:like:" + userId;
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(likeKey, id.toString());
        if (Boolean.TRUE.equals(isLiked)) {
            stringRedisTemplate.opsForSet().remove(likeKey, id.toString());
            update().setSql("liked = liked - 1").eq("id", id).update();
            return Result.ok(false);
        } else {
            stringRedisTemplate.opsForSet().add(likeKey, id.toString());
            update().setSql("liked = liked + 1").eq("id", id).update();
            return Result.ok(true);
        }
    }
}