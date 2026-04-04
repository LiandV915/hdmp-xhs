package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.UserProfileVO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;

import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Lazy
    private IUserInfoService userInfoService;

    @Autowired
    @Lazy
    private IBlogService blogService;

    @Autowired
    @Lazy
    private IFollowService followService;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、判断手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        // 2、手机号合法，生成验证码，并保存到redis中,有效期30min
        String code = "123456";
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES);
        // 3、发送验证码
        log.info("验证码:{}", code);
        return Result.ok();
    }


    /**
     * 验证码登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        User user = new User();
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        String code = loginForm.getCode();
        //从redis中比对验证码
        String cachecode=stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(code==null||!code.equals(cachecode)){
            return Result.fail("验证码错误");
        }
        user=query().eq("phone",phone).one();
        if(user==null){//查不到，则还没有这个用户
            user=createUserWithPhone(phone);
            initNewUserRedis(user.getId());//注册用户的时候初始化一系列redis表
        }
        UserDTO userDTO= BeanUtil.copyProperties(user,UserDTO.class);
        //将userDTO转为map类型，从而封装入redis
        Map<String,Object> map=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String token= UUID.randomUUID().toString();//生成随机令牌作为key
        String tokenname=RedisConstants.LOGIN_USER_KEY + token;
        //将用户的基本信息存入redis
        stringRedisTemplate.opsForHash().putAll(tokenname, map);

        UserHolder.saveUser(userDTO);

        stringRedisTemplate.expire(tokenname,30, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        this.save(user);
        return user;
    }
    /**
     * 新用户注册后的推荐系统初始化
     */
    public void initNewUserRedis(Long userId) {
        // 采用 Redis Key 懒加载策略，首次行为自动创建
    }

    // ================================================================
    // 登出：删除 Redis Token
    // ================================================================
    @Override
    public Result logout(String token) {
        if (StrUtil.isBlank(token)) {
            return Result.fail("token 不能为空");
        }
        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok();
    }

    // ================================================================
    // 更新用户基本信息 + UserInfo 扩展信息
    // ================================================================
    @Override
    public Result updateUserInfo(UpdateUserDTO dto) {
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null) {
            return Result.fail("请先登录");
        }
        Long userId = loginUser.getId();

        // 更新 tb_user（昵称、头像）
        User user = new User();
        user.setId(userId);
        if (StrUtil.isNotBlank(dto.getNickName())) {
            user.setNickName(dto.getNickName());
        }
        if (StrUtil.isNotBlank(dto.getIcon())) {
            user.setIcon(dto.getIcon());
        }
        updateById(user);

        // 更新 tb_user_info
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
        }
        if (StrUtil.isNotBlank(dto.getCity()))      info.setCity(dto.getCity());
        if (StrUtil.isNotBlank(dto.getIntroduce())) info.setIntroduce(dto.getIntroduce());
        if (dto.getGender() != null)                info.setGender(dto.getGender());
        if (dto.getBirthday() != null)              info.setBirthday(dto.getBirthday());
        userInfoService.saveOrUpdate(info);

        return Result.ok();
    }

    // ================================================================
    // 查看用户主页（含博客数、粉丝数、关注数）
    // ================================================================
    @Override
    public Result getUserProfile(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserInfo info = userInfoService.getById(userId);

        UserProfileVO vo = new UserProfileVO();
        vo.setId(userId);
        vo.setNickName(user.getNickName());
        vo.setIcon(user.getIcon());
        if (info != null) {
            vo.setCity(info.getCity());
            vo.setIntroduce(info.getIntroduce());
            vo.setGender(info.getGender());
            vo.setBirthday(info.getBirthday());
            vo.setFans(info.getFans());
            vo.setFollowee(info.getFollowee());
        }
        // 博客数量
        long blogCount = blogService.query().eq("user_id", userId).count();
        vo.setBlogCount(blogCount);

        return Result.ok(vo);
    }

    // ================================================================
    // 按昵称关键词搜索用户（分页）
    // ================================================================
    @Override
    public Result searchUsers(String keyword, Integer current) {
        if (StrUtil.isBlank(keyword)) {
            return Result.fail("请输入搜索关键词");
        }
        Page<User> page = query()
                .like("nick_name", keyword)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
                List<UserDTO> list = page.getRecords().stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }
}


