package com.checkbook.aladinstore.repository;

import com.checkbook.aladinstore.domain.AladinStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AladinStoreRepository extends JpaRepository<AladinStore, Long> {

    List<AladinStore> findByOffCodeIn(List<String> offCodes);
}
