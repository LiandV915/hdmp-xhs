package com.hmdp.utils;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.util.Set;

@Component
public class ExportTokenCSVRunner implements CommandLineRunner {

    private final StringRedisTemplate stringRedisTemplate;

    public ExportTokenCSVRunner(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        // Redis key 前缀
        String prefix = "login:token:";

        // 获取所有 token key
        Set<String> keys = stringRedisTemplate.keys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            System.out.println("Redis中没有登录token数据！");
            return;
        }

        // CSV 文件路径（JMeter可直接用）
        File file = new File("D:/test/token_user.csv");
        file.getParentFile().mkdirs(); // 自动创建目录
        FileWriter fw = new FileWriter(file);

        // 写入 CSV 表头（可选）
        fw.write("token,userId\n");

        for (String key : keys) {
            String token = key.replace(prefix, "");
            Object userId = stringRedisTemplate.opsForHash().get(key, "id");
            if (userId != null) {
                fw.write(token + "," + userId.toString() + "\n");
            }
        }

        fw.close();
        System.out.println("token + userId CSV 导出完成！");
    }
}