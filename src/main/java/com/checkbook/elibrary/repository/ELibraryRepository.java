package com.checkbook.elibrary.repository;

import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ELibraryRepository extends JpaRepository<ELibrary, Long> {

    @Query("SELECT l FROM ELibrary l " +
            "WHERE l.status = :status AND l.loginRequired = false " +
            "AND (:region IS NULL OR l.region = :region) " +
            "AND (:vendorType IS NULL OR l.vendorType = :vendorType) " +
            "ORDER BY l.name ASC")
    List<ELibrary> findAllByFilterWithoutKeyword(
            @Param("status") ELibraryStatus status,
            @Param("region") String region,
            @Param("vendorType") VendorType vendorType
    );

    @Query("SELECT l FROM ELibrary l " +
            "WHERE l.status = :status AND l.loginRequired = false " +
            "AND (:region IS NULL OR l.region = :region) " +
            "AND (:vendorType IS NULL OR l.vendorType = :vendorType) " +
            "AND l.name LIKE CONCAT('%', :keyword, '%') " +
            "ORDER BY l.name ASC")
    List<ELibrary> findAllByFilterWithKeyword(
            @Param("status") ELibraryStatus status,
            @Param("region") String region,
            @Param("vendorType") VendorType vendorType,
            @Param("keyword") String keyword
    );
}
