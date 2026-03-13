package com.checkbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EBookSearchResult {

    private String title;
    private String author;
    private String publisher;
    private String publishDate;
    private String vendor;       // 공급사: 교보문고, 북큐브 등
    private String coverUrl;
    private String description;
    private boolean available;   // 대출 가능 여부
    private String libraryName;  // 소장 도서관명
    private String source;       // 검색한 도서관 URL
}
