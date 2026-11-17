package io.contextads;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.Acknowledgment;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "ads.events.enabled", havingValue = "true", matchIfMissing = true)
class KafkaEvents {
    static final String TOPIC = "ads.events.v1";

    @Bean
    NewTopic adsEventsTopic() {
        return new NewTopic(TOPIC, 3, (short) 1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(ConsumerFactory<String, String> consumerFactory, EventProcessor processor) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Durable SQL DLQ. If DB quarantine fails, recovery throws and the Kafka offset is not committed.
        var errors = new DefaultErrorHandler((record, error) -> processor.quarantine(record.topic(), record.partition(), record.offset(), (String) record.value()), new FixedBackOff(200, 2));
        errors.setCommitRecovered(true);
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    @Bean
    EventListener listener(EventProcessor processor) {
        return new EventListener(processor);
    }

    static class EventListener {
        private final EventProcessor processor;

        EventListener(EventProcessor processor) {
            this.processor = processor;
        }

        @KafkaListener(topics = TOPIC, groupId = "fraud-review-v1", id = "fraud-review")
        public void receive(ConsumerRecord<String, String> record, Acknowledgment ack) {
            processor.consume(record.value());
            ack.acknowledge();
        }
    }
}
