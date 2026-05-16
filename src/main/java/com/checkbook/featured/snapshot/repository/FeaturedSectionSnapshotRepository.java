package com.checkbook.featured.snapshot.repository;

import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeaturedSectionSnapshotRepository
        extends JpaRepository<FeaturedSectionSnapshot, FeaturedSectionType> {
}
