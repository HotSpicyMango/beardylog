#!/bin/sh
# 에뮬레이터의 디버그 앱에 '더미' 개체 하나와 6개월치 무게·식단 기록을 추가한다.
# 기존 데이터는 지우지 않는다. 실기기에 가지 않도록 에뮬레이터(-e)에만 보낸다.
# 사용: 앱을 한 번 실행해 DB가 만들어진 뒤 ./scripts/seed-dummy-profile.sh
set -e
PKG=com.hsm.beardylog
ADB="adb -e"

$ADB shell am force-stop $PKG
$ADB shell run-as $PKG sqlite3 databases/beardylog.db <<'SQL'
BEGIN;
-- 오늘 날짜의 epochDay(앱이 날짜를 저장하는 단위)
CREATE TEMP TABLE t AS SELECT CAST(julianday(date('now','localtime')) - 2440587.5 AS INTEGER) AS d;
INSERT INTO reptiles (name,species,morph,gender,referenceDate,referenceDateType,photoUri,createdAt,hatchingDate,adoptionDate,deathDate,memorialNote)
SELECT '더미','비어디드래곤','하이포 레더백','수컷',d-240,'해칭일',NULL,CAST(strftime('%s','now') AS INTEGER)*1000,d-240,d-185,NULL,NULL FROM t;
CREATE TEMP TABLE r AS SELECT last_insert_rowid() AS id;

-- 6개월(26주) 주 1회 무게, 점점 증가
WITH RECURSIVE w(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM w WHERE i < 25)
INSERT INTO weight_records (reptileId,recordedAt,grams)
SELECT r.id, t.d - 7*i, round(310.0 - i*6.5 + (i % 3)*2.1, 1) FROM w, t, r;

-- 반복 식단: 월 충식, 목 채식 (6개월 전 시작)
INSERT INTO care_schedules (reptileId,scheduledDate,careType,memo,repeatDayOfWeek)
SELECT r.id,t.d-182,'충식','귀뚜라미 10마리',1 FROM t,r UNION ALL
SELECT r.id,t.d-182,'채식','청경채',4 FROM t,r;

-- 지난 6개월 수행 기록(가끔 미실시). epochDay 0 = 목요일 → 요일(1=월)=(d+3)%7+1
WITH RECURSIVE days(x) AS (SELECT (SELECT d-182 FROM t) UNION ALL SELECT x+1 FROM days WHERE x < (SELECT d-1 FROM t))
INSERT INTO care_logs (scheduleId,completedDate,status)
SELECT s.id, x, CASE WHEN x % 11 = 0 THEN '미실시' ELSE '완료' END
FROM days JOIN care_schedules s ON s.reptileId=(SELECT id FROM r) AND s.repeatDayOfWeek = (x+3)%7+1;
COMMIT;
PRAGMA wal_checkpoint(TRUNCATE);
SELECT 'reptiles', COUNT(*) FROM reptiles UNION ALL
SELECT 'weight_records', COUNT(*) FROM weight_records UNION ALL
SELECT 'care_logs', COUNT(*) FROM care_logs;
SQL
