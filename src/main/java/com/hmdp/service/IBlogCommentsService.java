package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    Result addComment(CommentDTO dto);

    void updateUserInterest(Long userId, Long blogId);

    /** 查询博客的评论列表（根评论 + 子评论） */
    Result queryCommentsByBlogId(Long blogId, Integer current);

    /** 删除评论（仅评论作者或博客作者可操作） */
    Result deleteComment(Long id);

    /** 评论点赞 / 取消点赞 */
    Result likeComment(Long id);
}
