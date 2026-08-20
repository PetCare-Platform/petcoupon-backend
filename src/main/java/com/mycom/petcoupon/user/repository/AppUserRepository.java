package com.mycom.petcoupon.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.entity.enums.UserStatus;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
	// 관리자 인증 API 구현 전까지 이벤트 생성에 사용할 활성 관리자 한 명을 임시로 조회한다.
	// TODO: 인증 API 구현 후 인증된 관리자 ID로 조회하도록 변경한다.
	Optional<AppUser> findFirstByRoleAndStatusOrderByUserIdAsc(UserRole role, UserStatus status);
}
