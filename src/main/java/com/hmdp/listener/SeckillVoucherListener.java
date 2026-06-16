package com.hmdp.listener;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class SeckillVoucherListener {

    @Resource
    IVoucherOrderService iVoucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(value = "seckill.queue", durable = "true"),
        exchange = @Exchange(value = "seckill.exchange", type = "fanout")))
    public void handleSeckillVoucher(VoucherOrder voucherOrder) {
        log.info("收到消息：{}", voucherOrder);
        iVoucherOrderService.createVoucherOrder(voucherOrder);
    }

}