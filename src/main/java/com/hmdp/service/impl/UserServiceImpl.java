package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合返回错误
            return Result.fail("手机号格式错误");
        }

        // 3.生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到session
        session.setAttribute("code", code);
        // 5.发送验证码
        log.info("验证码为：{}", code);
        // 6.返回ok

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.验证手机号
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合返回错误
            return Result.fail("手机号格式错误");
        }
        // 3. 验证验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if ((cacheCode == null || !cacheCode.toString().equals(code))) {
            // 4. 不一致就报错
            return Result.fail("验证码错误");
        }
        // 5. 一致就根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 6.判断用户是否存在
        if (user == null) {
            // 7.不存在就创建新用户 并保存
            user = createUserWithPhone(phone);
        }

        // 8.保存用户信息到session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();

    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
