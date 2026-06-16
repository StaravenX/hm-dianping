---@diagnostic disable: undefined-global
-- 1. 参数列表
-- 1.1. 优惠券id
local voucherId	= ARGV[1]
-- 1.2. 用户id
local userid = ARGV[2]

-- 2. 数据key
-- 2.1. 库存Key
local stockKey = "seckill:stock:" .. voucherId
-- 2.2. 订单Key
local orderKey = "seckill:order:" .. voucherId

-- 3. Lua脚本业务
-- 3.1. 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then return 1 end
-- 3.2. 判断用户是否已经下单
if (redis.call('sismember', orderKey, userid) == 1) then return 2 end
-- 3.3. 扣库存
redis.call('incrby', stockKey, -1)
-- 3.4. 下单（保存用户id到订单集合中）
redis.call('sadd', orderKey, userid)
return 0