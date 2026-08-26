package com.mycom.petcoupon.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka Producer 신뢰성 설정(#112) 검증.
 * acks/enable.idempotence/retries는 명시적으로 설정하지 않았지만, Kafka 3.0+부터
 * enable.idempotence 기본값이 true라 클라이언트가 자동으로 안전값을 강제한다 —
 * 실제로 그렇게 적용되는지, 그리고 명시적으로 낮춘 delivery.timeout.ms/request.timeout.ms가
 * 의도대로 반영됐는지 확인한다.
 */
@SpringBootTest
class KafkaConfigTest {

	@Autowired
	private ProducerFactory<String, Object> producerFactory;

	@Test
	void idempotence_기반_안전값이_자동으로_적용된다() {
		ProducerConfig producerConfig = new ProducerConfig(producerFactory.getConfigurationProperties());

		assertThat(producerConfig.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
		assertThat(producerConfig.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
		assertThat(producerConfig.getInt(ProducerConfig.RETRIES_CONFIG)).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void Outbox_재시도_정책에_맞게_delivery_timeout이_단축돼있다() {
		ProducerConfig producerConfig = new ProducerConfig(producerFactory.getConfigurationProperties());

		assertThat(producerConfig.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(5000);
		assertThat(producerConfig.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(10000);
	}
}
