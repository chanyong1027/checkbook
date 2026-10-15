package com.checkbook.aladinstore.repository;

import com.checkbook.aladinstore.domain.AladinStore;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AladinStoreRepository extends JpaRepository<AladinStore, Long> {
}
