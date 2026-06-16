package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate, RedisTemplate<Object, Object> redisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 发送验证码
     * @param phone
     * @param session
     */
    @Override
    public void sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)) {
            throw new RuntimeException("手机号有误！");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("发送验证码成功, {}", code);
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 当月的第几天
        int dayOfMonth = now.getDayOfMonth();
        List<Long> list = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (list == null || list.isEmpty()) {
            return Result.ok(0);
        }
        Long result = list.get(0);
        int cnt = 0;
        if(result == null || result == 0) return Result.ok(0);
        while (true) {
            if((result & 1) == 0) break;
            else ++cnt;
            result >>>= 1;
        }
        return Result.ok(cnt);
    }

    // 用户签到
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 当月的第几天
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 用户登录
     *
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public String login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        if(code == null  || !code.equals(stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone))) {
            throw new RuntimeException("手机号或验证码错误！");
        }
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if(user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = new HashMap<>();
        CopyOptions copyOptions = new CopyOptions();
        copyOptions.setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()).setIgnoreNullValue(true);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, map, copyOptions);
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        return token;
    }
}
