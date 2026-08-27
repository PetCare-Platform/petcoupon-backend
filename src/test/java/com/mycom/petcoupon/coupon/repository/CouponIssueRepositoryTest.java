package com.mycom.petcoupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * status 필터(이슈 #106)를 real MySQL로 검증한다.
 * JPQL의 (:status IS NULL OR ci.status = :status) 분기는 목으로는 오타/문법 오류를 못 잡는다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponIssueRepositoryTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    private AppUser user;
    private CouponIssue issued;
    private CouponIssue used;
    private CouponIssue expired;

    @BeforeEach
    void setUp() {
        user = AppUser.builder()
                .name("테스트회원")
                .email("issue-repo-test@test.com")
                .phone("010-1234-5678")
                .role(UserRole.ROLE_MEMBER)
                .build();
        entityManager.persist(user);

        Event event = Event.builder()
                .createdBy(user)
                .name("리포지토리 테스트 이벤트")
                .description("repo test")
                .openAt(LocalDateTime.of(2026, 8, 20, 9, 0))
                .closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
                .build();
        entityManager.persist(event);

        Coupon coupon = Coupon.builder()
                .event(event)
                .name("리포지토리 테스트 쿠폰")
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(5_000)
                .minOrderAmount(10_000)
                .issueStartAt(LocalDateTime.of(2026, 8, 20, 9, 0))
                .issueEndAt(LocalDateTime.of(2026, 8, 31, 23, 59))
                .validDays(7)
                .build();
        entityManager.persist(coupon);

        CouponStock stock = CouponStock.builder()
                .coupon(coupon)
                .totalQuantity(100)
                .build();
        entityManager.persist(stock);

        // uk_issue_coupon_user(1인 1매) 때문에 발급 건마다 사용자가 달라야 한다.
        issued = persistIssue(coupon, "issue-repo-issued@test.com", 1, IssueStatus.ISSUED);
        used = persistIssue(coupon, "issue-repo-used@test.com", 2, IssueStatus.USED);
        expired = persistIssue(coupon, "issue-repo-expired@test.com", 3, IssueStatus.EXPIRED);

        entityManager.flush();
    }

    private CouponIssue persistIssue(Coupon coupon, String email, int sequenceNo, IssueStatus status) {
        AppUser issuedUser = AppUser.builder()
                .name("발급회원" + sequenceNo)
                .email(email)
                .phone("010-0000-" + String.format("%04d", sequenceNo))
                .role(UserRole.ROLE_MEMBER)
                .build();
        entityManager.persist(issuedUser);

        CouponIssue issue = CouponIssue.builder()
                .coupon(coupon)
                .user(issuedUser)
                .sequenceNo(sequenceNo)
                .couponCode("REPO-TEST-CODE-" + sequenceNo)
                .requestId("repo-test-request-" + sequenceNo)
                .status(status)
                .expiresAt(LocalDateTime.of(2026, 9, 30, 23, 59))
                .build();
        entityManager.persist(issue);
        return issue;
    }

    @Test
    @DisplayName("status를 지정하면 해당 상태의 발급 내역만 반환한다")
    void filtersByStatus_whenStatusGiven() {
        List<CouponIssue> usedResults = couponIssueRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(
                used.getUser().getUserId(), IssueStatus.USED);

        assertThat(usedResults)
                .extracting(CouponIssue::getCouponIssueId)
                .containsExactly(used.getCouponIssueId());
    }

    @Test
    @DisplayName("해당 상태에 일치하는 발급 내역이 없으면 빈 리스트를 반환한다")
    void returnsEmptyList_whenNoIssueMatchesStatus() {
        List<CouponIssue> results = couponIssueRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(
                issued.getUser().getUserId(), IssueStatus.USED);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("status 필터 없이 조회하면 같은 사용자의 발급 내역을 전부 반환한다")
    void returnsAllIssuesForSameUser_whenStatusIsNull() {
        AppUser sameUser = AppUser.builder()
                .name("동일회원")
                .email("issue-repo-multi@test.com")
                .phone("010-9999-9999")
                .role(UserRole.ROLE_MEMBER)
                .build();
        entityManager.persist(sameUser);

        Coupon anotherCoupon = issued.getCoupon();

        CouponIssue firstIssue = CouponIssue.builder()
                .coupon(anotherCoupon)
                .user(sameUser)
                .sequenceNo(10)
                .couponCode("REPO-TEST-CODE-10")
                .requestId("repo-test-request-10")
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.of(2026, 9, 30, 23, 59))
                .build();
        entityManager.persist(firstIssue);
        entityManager.flush();

        List<CouponIssue> results = couponIssueRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(
                sameUser.getUserId(), null);

        assertThat(results)
                .extracting(CouponIssue::getCouponIssueId)
                .containsExactly(firstIssue.getCouponIssueId());
    }
}
