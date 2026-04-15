package com.checkbook.aladinstore.domain;

import com.checkbook.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "aladin_store")
public class AladinStore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "off_code", nullable = false, unique = true, length = 20)
    private String offCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lon;

    @Builder
    public AladinStore(String offCode, String name, String address, Double lat, Double lon) {
        this.offCode = offCode;
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lon = lon;
    }
}
