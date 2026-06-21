package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 执行Lua脚本
        Long userId = UserHolder.getUser().getId();
        long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId, userId);
        if (result == 1) {
            return Result.fail("库存不足！");
        } else if (result == 2) {
            return Result.fail("请勿重复下单");
        }
        // 创建订单
        VoucherOrder order = new VoucherOrder();
        Long orderId = RedisIdWorker.nextId();
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setId(orderId);
        // 发送消息
        ProducerRecord<String, Object> record = new ProducerRecord<>("seckill.order.topic", order);
        record.headers().add("messageId", orderId.toString().getBytes());
        kafkaTemplate.send(record).whenComplete((result1, ex) -> {
            if(ex == null) {
                return;
            }
            log.error("订单发送失败！优惠券：" + voucherId);
        });
        return Result.ok();
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        save(voucherOrder);
        boolean res = iSeckillVoucherService.lambdaUpdate().gt(SeckillVoucher::getStock, 0).setSql("stock = stock - 1").eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId()).update();
        if (BooleanUtil.isFalse(res)) {
            log.info("库存不足");
            throw new RuntimeException();
        }
    }
}
