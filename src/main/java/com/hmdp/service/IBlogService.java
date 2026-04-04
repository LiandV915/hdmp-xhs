package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result getBlogById(Long id);

    List<Blog> recommendBlogs(Long userId, int size);

    Result updateLikedCounts(Long id);

    Result getBlogLikes(Long id);

    void saveBlogWithVector(Blog blog);

    /** 修改博客（仅作者可操作） */
    Result updateBlog(Long id, Blog blog);

    /** 删除博客（仅作者可操作） */
    Result deleteBlog(Long id);

    /** 查看某用户的博客列表 */
    Result queryBlogOfUser(Long userId, Integer current);

    /** 关注的人的 Feed 流（滚动分页） */
    Result queryFollowBlog(Long lastId, Integer offset);

    /** 关键词搜索博客 */
    Result searchBlog(String keyword, Integer current);

    /** 收藏 / 取消收藏博客 */
    Result collectBlog(Long id);

    /** 查看我的收藏列表 */
    Result queryMyCollect(Integer current);

    /**
     * 热门博客排行
     * 基于 Redis ZSet blog:hot，按热度分降序返回
     * @param size 返回数量（默认10）
     */
    Result queryHotBlogs(Integer size);

    /** 我的浏览历史（按浏览时间倒序，分页） */
    Result queryBlogHistory(Integer current);

    /** 清空我的浏览历史 */
    Result clearBlogHistory();
}
