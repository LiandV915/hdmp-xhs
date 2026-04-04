package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileVO {
    private Long id;
    private String nickName;
    private String icon;
    private String city;
    private String introduce;
    private Boolean gender;
    private LocalDate birthday;
    /** 博客数量 */
    private Long blogCount;
    /** 粉丝数量 */
    private Integer fans;
    /** 关注数量 */
    private Integer followee;
}
