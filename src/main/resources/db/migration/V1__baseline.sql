-- V1__baseline.sql
-- 기존 테이블 DDL (baseline - 운영 DB에서는 실행되지 않음)

CREATE TABLE IF NOT EXISTS public_library (
    id BIGSERIAL PRIMARY KEY,
    lib_code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500),
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    region_name VARCHAR(100),
    homepage VARCHAR(500),
    phone VARCHAR(200),
    fax VARCHAR(200),
    operating_hours VARCHAR(1000),
    closed_days VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_public_library_lat_lon ON public_library(lat, lon);

CREATE TABLE IF NOT EXISTS elibrary (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    vendor_type VARCHAR(20) NOT NULL,
    base_url VARCHAR(500) NOT NULL UNIQUE,
    region VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    login_required BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS library_availability_snapshot (
    id BIGSERIAL PRIMARY KEY,
    isbn13 VARCHAR(13) NOT NULL,
    lib_code VARCHAR(20) NOT NULL,
    has_book BOOLEAN NOT NULL,
    loan_available BOOLEAN NOT NULL,
    source_status VARCHAR(20) NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_las_isbn13_lib_code UNIQUE (isbn13, lib_code)
);

CREATE INDEX IF NOT EXISTS idx_las_expires_at
    ON library_availability_snapshot(expires_at);
