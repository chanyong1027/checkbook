-- Fix missing addresses introduced in V3 seed data

UPDATE aladin_store
SET address = '서울 서초구 방배동 2989 지하1층',
    updated_at = CURRENT_TIMESTAMP
WHERE off_code = 'isu';

UPDATE aladin_store
SET address = '서울 송파구 신천동 29 지하1층',
    updated_at = CURRENT_TIMESTAMP
WHERE off_code = 'Jamsil';
