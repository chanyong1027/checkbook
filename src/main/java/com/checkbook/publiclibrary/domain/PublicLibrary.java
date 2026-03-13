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

    @Column(length = 50)
    private String phone;

    @Column(length = 50)
    private String fax;

    @Column(name = "operating_hours", length = 1000)
    private String operatingHours;

    @Column(name = "closed_days", length = 1000)
    private String closedDays;

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
}
