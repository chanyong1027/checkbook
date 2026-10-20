package com.checkbook.elibrary.domain;

import com.checkbook.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "elibrary")
public class ELibrary extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "vendor_type", nullable = false, length = 20)
    private VendorType vendorType;

    @Column(name = "base_url", nullable = false, unique = true, length = 500)
    private String baseUrl;

    @Column(length = 20)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ELibraryStatus status;

    @Column(name = "login_required", nullable = false)
    private boolean loginRequired;

    /** robots.txt가 앱이 부르는 경로를 막는 사이트. 사용자 1회 조회는 허용하되 하루 상한을 낮춘다(결정 2026-10-20) */
    @Column(name = "robots_disallowed", nullable = false)
    private boolean robotsDisallowed;

    @Builder
    public ELibrary(
            String name,
            VendorType vendorType,
            String baseUrl,
            String region,
            ELibraryStatus status,
            boolean loginRequired,
            boolean robotsDisallowed
    ) {
        this.name = name;
        this.vendorType = vendorType;
        this.baseUrl = baseUrl;
        this.region = region;
        this.status = status;
        this.loginRequired = loginRequired;
        this.robotsDisallowed = robotsDisallowed;
    }
}
