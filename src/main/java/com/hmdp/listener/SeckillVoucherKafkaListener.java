package com.hmdp.listener;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class SeckillVoucherKafkaListener {

    private final IVoucherOrderService iVoucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    public SeckillVoucherKafkaListener(IVoucherOrderService iVoucherOrderService, StringRedisTemplate stringRedisTemplate) {
        this.iVoucherOrderService = iVoucherOrderService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @KafkaListener(topics = "seckill.order.topic", concurrency = "3", groupId = "seckill.order.group")
    public void createSeckillOrder(VoucherOrder voucherOrder, Acknowledgment acknowledgment, @Header("messageId") String messageId) {
        String key = "seckill:" + messageId;
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 1000, TimeUnit.SECONDS);
        if(BooleanUtil.isFalse(b)) {
            log.info("不允许重复下单");
            acknowledgment.acknowledge();
            return;
        }
        try {
            iVoucherOrderService.createVoucherOrder(voucherOrder);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            if(e instanceof DuplicateKeyException) {
                log.info("不允许重复下单");
                acknowledgment.acknowledge();
            }
            else {
                // 非重复异常，删除幂等key
                stringRedisTemplate.delete(key);
                throw e;
            }
        }
    }

    public void dltHandler(VoucherOrder order, ListenerExecutionFailedException e) {
        Throwable cause = e.getCause();
        log.error("死信队列出现故障，原因：{}", String.valueOf(cause));
        // 添加失败list
        stringRedisTemplate.opsForList().rightPush("seckill:fail", order.getId().toString());
    }
}
