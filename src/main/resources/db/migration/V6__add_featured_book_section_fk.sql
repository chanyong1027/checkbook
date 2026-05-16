-- featured_book.section_type → featured_section_snapshot.section_type FK 추가
-- 스냅샷 시드는 V5에서 INSERT되고 삭제될 일이 없으므로 ON DELETE RESTRICT.
ALTER TABLE featured_book
    ADD CONSTRAINT fk_featured_book_section
        FOREIGN KEY (section_type)
        REFERENCES featured_section_snapshot(section_type)
        ON DELETE RESTRICT;
