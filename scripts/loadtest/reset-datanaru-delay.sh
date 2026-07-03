#!/usr/bin/env bash
curl -s -X DELETE http://localhost:8089/__admin/mappings/fa010e00-0000-4000-8000-000000000001
echo "지연 주입 해제됨 (기본 스텁으로 복귀)"
