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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@Slf4j
@Primary
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ISeckillVoucherService iSeckillVoucherService;

    public VoucherOrderServiceImpl(StringRedisTemplate stringRedisTemplate, RabbitTemplate rabbitTemplate, ISeckillVoucherService iSeckillVoucherService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.iSeckillVoucherService = iSeckillVoucherService;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        if(result == 1) {
            return Result.fail("库存不足！");
        } else if(result == 2) {
            return Result.fail("同一用户不允许重复下单！");
        }
        VoucherOrder build = VoucherOrder.builder().userId(userId).voucherId(voucherId).id(RedisIdWorker.nextId()).build();
        rabbitTemplate.convertAndSend("seckill.exchange", "", build);
        return Result.ok();
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        try {
            save(voucherOrder);
        } catch (Exception e) {
            log.info("同一用户不允许重复下单");
        }
        boolean b = iSeckillVoucherService.lambdaUpdate().setSql("stock = stock - 1").eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId()).gt(SeckillVoucher::getStock, 0).update();
        if(BooleanUtil.isFalse(b)) {
            log.info("库存不足");
        }
    }
}
