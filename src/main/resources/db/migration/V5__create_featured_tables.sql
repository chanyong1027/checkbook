-- 메인 화면 추천 섹션(베스트셀러/신간/인기대출) 캐시 테이블

CREATE TABLE IF NOT EXISTS featured_section_snapshot (
    section_type     VARCHAR(20)  PRIMARY KEY,
    source           VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    last_fetched_at  TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    failure_reason   VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS featured_book (
    id            BIGSERIAL    PRIMARY KEY,
    section_type  VARCHAR(20)  NOT NULL,
    rank          INTEGER      NOT NULL,
    isbn13        VARCHAR(13)  NOT NULL,
    title         VARCHAR(500) NOT NULL,
    author        VARCHAR(500),
    publisher     VARCHAR(200),
    cover_url     VARCHAR(500),
    published_at  VARCHAR(20),
    CONSTRAINT uq_featured_book_section_rank UNIQUE (section_type, rank)
);

CREATE INDEX IF NOT EXISTS idx_featured_book_section
    ON featured_book(section_type);

-- 시드: 섹션 메타 3행을 NEVER_FETCHED 상태로 미리 삽입
INSERT INTO featured_section_snapshot (section_type, source, status)
VALUES
    ('BESTSELLER', 'ALADIN',   'NEVER_FETCHED'),
    ('NEW',        'ALADIN',   'NEVER_FETCHED'),
    ('LOAN',       'DATANARU', 'NEVER_FETCHED')
ON CONFLICT (section_type) DO NOTHING;
