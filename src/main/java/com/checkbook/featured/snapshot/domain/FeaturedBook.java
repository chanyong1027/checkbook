package com.checkbook.featured.snapshot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "featured_book",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_featured_book_section_rank",
                columnNames = {"section_type", "rank"}
        ),
        indexes = @Index(name = "idx_featured_book_section", columnList = "section_type")
)
public class FeaturedBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "section_type", nullable = false, length = 20)
    private FeaturedSectionType sectionType;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "isbn13", nullable = false, length = 13)
    private String isbn13;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "author", length = 500)
    private String author;

    @Column(name = "publisher", length = 200)
    private String publisher;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column(name = "published_at", length = 20)
    private String publishedAt;

    @Builder
    public FeaturedBook(
            FeaturedSectionType sectionType,
            int rank,
            String isbn13,
            String title,
            String author,
            String publisher,
            String coverUrl,
            String publishedAt
    ) {
        this.sectionType = sectionType;
        this.rank = rank;
        this.isbn13 = isbn13;
        this.title = title;
        this.author = author;
        this.publisher = publisher;
        this.coverUrl = coverUrl;
        this.publishedAt = publishedAt;
    }
}
