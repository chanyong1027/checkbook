package com.checkbook.featured.snapshot.repository;

import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeaturedBookRepository extends JpaRepository<FeaturedBook, Long> {

    List<FeaturedBook> findBySectionTypeOrderByRankAsc(FeaturedSectionType sectionType);

    void deleteBySectionType(FeaturedSectionType sectionType);
}
