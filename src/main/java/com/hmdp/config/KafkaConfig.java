package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

@Configuration
public class KafkaConfig {

    // 配置主题
    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name("seckill.order.topic").partitions(3).replicas(2).build();
    }

    // 配置序列化器
    @Bean
    public StringJacksonJsonMessageConverter converter() {
        return new StringJacksonJsonMessageConverter();
    }

    // 容器工厂
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> factory(ConsumerFactory<String, Object> consumerFactory, RecordMessageConverter jsonMessageConverter) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setRecordMessageConverter(jsonMessageConverter);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // 重试策略
    @Bean
    public RetryTopicConfiguration retryTopicConfiguration(KafkaTemplate<?, ?> kafkaTemplate) {
        return RetryTopicConfigurationBuilder.newInstance()
                .includeTopic("seckill.order.topic")
                .maxAttempts(3)
                .dltHandlerMethod("seckillVoucherKafkaListener", "dltHandler")
                .create(kafkaTemplate);
    }

}
