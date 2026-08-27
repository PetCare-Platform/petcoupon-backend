package com.mycom.petcoupon.reconciliation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

// PR #155 리뷰 반영 — 이전엔 @Component만 있어서 어떤 환경에서 앱을 띄우든 무조건 등록되고
// 끌 방법이 없었다. coupon.reconciliation.scheduler.enabled=false로 비활성화할 수 있는지
// 확인한다. 다른 테스트와 프로퍼티 조합이 겹치지 않게 별도 컨텍스트로 뜨므로, 확인 후
// 컨텍스트를 정리한다(다른 테스트에 영향 없게).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "coupon.reconciliation.scheduler.enabled=false",
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false",
        "coupon.issue.stream.key=coupon:issue:stream:reconciliation-scheduler-conditional-test",
        "coupon.issue.stream.group=reconciliation-scheduler-conditional-test-group"
})
class ReconciliationSchedulerConditionalTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void enabled가_false면_ReconciliationScheduler_빈이_등록되지_않는다() {
        assertThat(applicationContext.getBeansOfType(ReconciliationScheduler.class)).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(
                NoSuchBeanDefinitionException.class,
                () -> applicationContext.getBean(ReconciliationScheduler.class)
        );
    }
}
