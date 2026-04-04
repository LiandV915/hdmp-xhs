package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateUserDTO {
    /** 昵称 */
    private String nickName;
    /** 头像 URL */
    private String icon;
    /** 城市 */
    private String city;
    /** 个人介绍 */
    private String introduce;
    /** 性别：0-男 1-女 */
    private Boolean gender;
    /** 生日 */
    private LocalDate birthday;
}
