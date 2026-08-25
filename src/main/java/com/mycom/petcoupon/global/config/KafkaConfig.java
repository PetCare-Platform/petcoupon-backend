package com.mycom.petcoupon.global.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import lombok.RequiredArgsConstructor;

// KafkaProperties(spring.kafka.*)를 기반으로 빈을 구성 — 직접 Map을 새로 만들면
// bootstrap-servers/group-id 외의 설정(예: acks, max-poll-records 등)을 application.properties에
// 추가해도 반영되지 않아 오토컨피그를 우회하는 셈이 되므로, KafkaProperties가 만들어주는 값을 베이스로 쓰고
// 여기서 필요한 시리얼라이저/역직렬화 설정만 덧붙인다.
@Configuration
@EnableKafka
@RequiredArgsConstructor
public class KafkaConfig {

	private final KafkaProperties kafkaProperties;

	// Kafka send()의 whenComplete 콜백이 기본적으로 Kafka producer I/O 스레드에서 실행되는데,
	// 그 안에서 블로킹 DB 호출을 하면 I/O 스레드가 지연되어 발행 파이프라인 전체가 느려질 수 있음.
	// 별도 실행 스레드로 빼기 위한 executor (가상 스레드라 풀 크기 고민 없이 씀)
	@Bean
	public Executor kafkaCallbackExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean
	public KafkaAdmin kafkaAdmin() {
		return new KafkaAdmin(kafkaProperties.buildAdminProperties());
	}

	@Bean
	public ProducerFactory<String, Object> producerFactory() {
		Map<String, Object> configProps = kafkaProperties.buildProducerProperties();
		configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(configProps);
	}

	@Bean
	public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
		return new KafkaTemplate<>(producerFactory);
	}

	@Bean
	public ConsumerFactory<String, Object> consumerFactory() {
		Map<String, Object> configProps = kafkaProperties.buildConsumerProperties();
		configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		configProps.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
		configProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
		configProps.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.mycom.petcoupon.*");
		return new DefaultKafkaConsumerFactory<>(configProps);
	}
}
