package com.hmdp.utils;

import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class UserLoginBatchTest {

    @Resource
    private MockMvc mockMvc;

    // 模拟1000用户
    private static final int USER_COUNT = 1000;

    @Test
    public void testLoginWith1000Users() throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(200);

        CountDownLatch latch = new CountDownLatch(USER_COUNT);

        long start = System.currentTimeMillis();

        for (int i = 0; i < USER_COUNT; i++) {
            int index = i;

            pool.submit(() -> {
                try {
                    // 1. 构造手机号（避免重复）
                    String phone = "1380000" + String.format("%04d", index);

                    // 2. 请求 /user/code 获取验证码
                    mockMvc.perform(MockMvcRequestBuilders.post("/user/code")
                                    .param("phone", phone))
                            .andExpect(status().isOk());

                    // 3. 请求 /user/login 登录
                    String json = "{\n" +
                            "  \"phone\": \"" + phone + "\",\n" +
                            "  \"code\": \"123456\"\n" +
                            "}";

                    MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/user/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json))
                            .andReturn();

                    String response = result.getResponse().getContentAsString();

                    System.out.println("用户 " + phone + " 登录结果：" + response);

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有线程执行完
        latch.await();

        long end = System.currentTimeMillis();

        System.out.println("总耗时：" + (end - start) + " ms");

        pool.shutdown();
    }
}