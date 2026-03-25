local viewKey = KEYS[1]
local hotKey = KEYS[2]
local interestKey = KEYS[3]

local blogId = ARGV[1]
local expireTime = tonumber(ARGV[2])

-- 去重：是否第一次浏览
local result = redis.call('SADD', viewKey, blogId)

if result == 1 then
    -- 设置过期
    redis.call('EXPIRE', viewKey, expireTime)

    -- 1️⃣ 增加当日热度
    redis.call('ZINCRBY', hotKey, 1, blogId)

    -- 2️⃣ 更新用户兴趣（标签维度）
    for i = 3, #ARGV do
        local tag = ARGV[i]
        redis.call('ZINCRBY', interestKey, 1, tag)
    end
end

return result