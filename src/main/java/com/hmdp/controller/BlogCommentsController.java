package com.hmdp.controller;


import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogCommentsService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/blog/comment")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /** 发表评论（parentId=0 为根评论，否则为回复） */
    @PostMapping
    public Result addComment(@RequestBody CommentDTO dto) {
        return blogCommentsService.addComment(dto);
    }

    /** 查询博客评论列表 */
    @GetMapping("/{blogId}")
    public Result queryComments(
            @PathVariable("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryCommentsByBlogId(blogId, current);
    }

    /** 删除评论 */
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }

    /** 评论点赞 / 取消点赞 */
    @PutMapping("/like/{id}")
    public Result likeComment(@PathVariable("id") Long id) {
        return blogCommentsService.likeComment(id);
    }
}
