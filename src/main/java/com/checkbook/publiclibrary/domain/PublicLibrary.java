package com.checkbook.publiclibrary.domain;

import com.checkbook.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "public_library",
        indexes = @Index(name = "idx_public_library_lat_lon", columnList = "lat, lon")
)
public class PublicLibrary extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lib_code", nullable = false, unique = true, length = 20)
    private String libCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lon;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(length = 500)
    private String homepage;

    @Column(length = 200)
    private String phone;

    @Column(length = 200)
    private String fax;

    @Column(name = "operating_hours", length = 1000)
    private String operatingHours;

    @Column(name = "closed_days", length = 1000)
    private String closedDays;

    /**
     * 정보나루 참여 목록에 있는지. 빠지면 지우지 않고 false로 둔다 — 다시 참여하면 되살리고,
     * 그동안은 주변·이름 검색과 내 도서관에서 뺀다(bookExist가 빈 응답을 줘 늘 '확인 못 함'이 되므로).
     */
    @Column(nullable = false)
    private boolean active = true;

    @Builder
    public PublicLibrary(
            String libCode,
            String name,
            String address,
            Double lat,
            Double lon,
            String regionName,
            String homepage,
            String phone,
            String fax,
            String operatingHours,
            String closedDays
    ) {
        this.libCode = libCode;
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lon = lon;
        this.regionName = regionName;
        this.homepage = homepage;
        this.phone = phone;
        this.fax = fax;
        this.operatingHours = operatingHours;
        this.closedDays = closedDays;
    }

    public void deactivate() {
        this.active = false;
    }

    /**
     * 정보나루 목록의 최신 값으로 고치고 되살린다. 이름·좌표가 비어 오면 그 칸만 기존 값을 지킨다
     * (좌표 없는 도서관도 목록에는 있으니 빠진 것으로 치지 않되, 지도·거리 계산은 옛 좌표로 계속한다).
     */
    public void refresh(String name, String address, Double lat, Double lon, String regionName, String homepage,
                        String phone, String fax, String operatingHours, String closedDays) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (lat != null && lon != null) {
            this.lat = lat;
            this.lon = lon;
        }
        this.address = address;
        this.regionName = regionName;
        this.homepage = homepage;
        this.phone = phone;
        this.fax = fax;
        this.operatingHours = operatingHours;
        this.closedDays = closedDays;
        this.active = true;
    }
}
