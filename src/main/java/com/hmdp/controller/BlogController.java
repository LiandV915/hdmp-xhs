package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        blogService.saveBlogWithVector(blog);
        return Result.ok(blog.getId());
    }

    /** 修改博客 */
    @PutMapping("/{id}")
    public Result updateBlog(@PathVariable("id") Long id, @RequestBody Blog blog) {
        return blogService.updateBlog(id, blog);
    }

    /** 删除博客 */
    @DeleteMapping("/{id}")
    public Result deleteBlog(@PathVariable("id") Long id) {
        return blogService.deleteBlog(id);
    }

    /** 点赞或取消点赞 */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.updateLikedCounts(id);
    }

    /** 查看我的博客 */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /** 查看某用户的博客列表 */
    @GetMapping("/of/user")
    public Result queryBlogOfUser(
            @RequestParam("id") Long userId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryBlogOfUser(userId, current);
    }

    /** 关注的人的 Feed 流（滚动分页） */
    @GetMapping("/of/follow")
    public Result queryFollowBlog(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryFollowBlog(lastId, offset);
    }

    /** 关键词搜索博客 */
    @GetMapping("/search")
    public Result searchBlog(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.searchBlog(keyword, current);
    }

    /** 收藏 / 取消收藏 */
    @PostMapping("/collect/{id}")
    public Result collectBlog(@PathVariable("id") Long id) {
        return blogService.collectBlog(id);
    }

    /** 查看我的收藏列表 */
    @GetMapping("/collect/me")
    public Result queryMyCollect(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryMyCollect(current);
    }

    /** 查看博客详情 */
    @GetMapping("{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.getBlogById(id);
    }

    /** 查看博客点赞列表 */
    @GetMapping("likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.getBlogLikes(id);
    }

    /** 推荐博客 */
    @GetMapping("/recommend")
    public Result recommendBlogs(@RequestParam(defaultValue = "2") int size) {
        UserDTO user = UserHolder.getUser();
        List<Blog> blogs = blogService.recommendBlogs(user != null ? user.getId() : null, size);
        return Result.ok(blogs);
    }

    /** 热门博客排行（基于 Redis ZSet 热度分，降序） */
    @GetMapping("/hot")
    public Result hotBlogs(@RequestParam(value = "size", defaultValue = "10") Integer size) {
        return blogService.queryHotBlogs(size);
    }

    /** 我的浏览历史（需登录，按浏览时间倒序分页） */
    @GetMapping("/history")
    public Result queryBlogHistory(
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryBlogHistory(current);
    }

    /** 清空我的浏览历史 */
    @DeleteMapping("/history")
    public Result clearBlogHistory() {
        return blogService.clearBlogHistory();
    }
}
