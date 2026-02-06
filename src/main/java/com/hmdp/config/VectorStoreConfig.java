package com.hmdp.config;

import lombok.AllArgsConstructor;
import org.springframework.ai.autoconfigure.vectorstore.redis.RedisVectorStoreAutoConfiguration;
import org.springframework.ai.autoconfigure.vectorstore.redis.RedisVectorStoreProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import redis.clients.jedis.JedisPooled;

@Configuration
@EnableAutoConfiguration(exclude = RedisVectorStoreAutoConfiguration.class)//禁用了官方自动配置
@EnableConfigurationProperties(RedisVectorStoreProperties.class)
public class VectorStoreConfig {

    /**
     * 向量 Redis（redis-stack 6380）专用 Jedis
     * 定义了一个“向量专用 Redis 连接”
     */
    @Bean
    public JedisPooled vectorJedisPooled() {
        return new JedisPooled("127.0.0.1", 6380);
    }

    /**
     * Redis VectorStore（Jedis 版）
     * 你显式构建了 RedisVectorStore
     * Bean 名称：redisVectorStore
     * Bean 类型：VectorStore
     */
    @Bean
    public VectorStore redisVectorStore(
            JedisPooled vectorJedisPooled,
            EmbeddingModel embeddingModel,
            RedisVectorStoreProperties properties
    ) {
        return RedisVectorStore.builder(vectorJedisPooled, embeddingModel)
                .indexName(properties.getIndex())
                .prefix(properties.getPrefix())
                .initializeSchema(properties.isInitializeSchema())
                .batchingStrategy(new TokenCountBatchingStrategy())
                .build();
    }
}


