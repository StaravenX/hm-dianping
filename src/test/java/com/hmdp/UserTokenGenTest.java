package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class UserTokenGenTest {

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void generateTokensForApifox() throws IOException {
        // 1. 从数据库中查出前 200 个用户
        List<User> userList = userService.query().last("LIMIT 200").list();

        if (userList.isEmpty()) {
            System.out.println("数据库里没有用户，请先导入用户数据！");
            return;
        }

        // 2. 将输出文件后缀改为 .csv
        FileWriter writer = new FileWriter("D:\\tokens.csv");

        // 3. 【核心改造 1】：写入严格符合 Apifox 规范的 CSV 表头
        writer.write("数据集名称,token\n");

        int index = 1; // 用于生成“数据_1”、“数据_2”这样的记录名称

        for (User user : userList) {
            // 4. 生成随机 Token
            String token = UUID.randomUUID().toString(true);
            String tokenKey = "login:token:" + token;

            // 5. 将 User 转为 UserDTO，脱敏并转换类型后存入 Redis
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, 60, TimeUnit.MINUTES);

            // 6. 【核心改造 2】：按照 Apifox 的标准格式写入数据行，使用英文逗号分隔
            writer.write("数据_" + index + "," + token + "\n");

            index++;
        }

        // 7. 关流并输出提示
        writer.close();
        System.out.println("成功生成 " + userList.size() + " 个 Token，已按标准 CSV 格式保存至 D:\\tokens.csv");
    }
}