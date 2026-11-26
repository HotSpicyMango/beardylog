#!/bin/sh
# 에뮬레이터의 디버그 앱에 화면 확인용 더미 개체·무게·식단 데이터를 넣는다.
# 기존 개체·무게·식단 기록은 모두 지운다. 실기기 데이터를 지우지 않도록 에뮬레이터(-e)에만 보낸다.
# 사용: 앱을 한 번 실행해 DB가 만들어진 뒤 ./scripts/seed-emulator.sh
set -e
PKG=com.hsm.beardylog
ADB="adb -e"

$ADB shell am force-stop $PKG
$ADB shell run-as $PKG sqlite3 databases/beardylog.db <<'SQL'
BEGIN;
DELETE FROM care_logs;
DELETE FROM care_schedules;
DELETE FROM weight_records;
DELETE FROM reptiles;
DELETE FROM sqlite_sequence WHERE name IN ('reptiles','weight_records','care_schedules','care_logs');

-- 오늘 날짜의 epochDay(앱이 날짜를 저장하는 단위)
CREATE TEMP TABLE t AS SELECT CAST(julianday(date('now','localtime')) - 2440587.5 AS INTEGER) AS d;

INSERT INTO reptiles (id,name,species,morph,gender,referenceDate,referenceDateType,photoUri,createdAt,hatchingDate,adoptionDate,deathDate,memorialNote)
SELECT 1,'망고','크레스티드 게코','릴리화이트','수컷',d-400,'해칭일',NULL,0,d-400,d-300,NULL,NULL FROM t UNION ALL
SELECT 2,'보리','레오파드 게코','하이옐로우','암컷',d-250,'입양일',NULL,0,NULL,d-250,NULL,NULL FROM t UNION ALL
SELECT 3,'Pixel 9','비어디드래곤','하이포 트랜스','미구분',d-60,'해칭일',NULL,0,d-60,NULL,NULL,NULL FROM t UNION ALL
SELECT 4,'콩이','크레스티드 게코','','암컷',d-1500,'해칭일',NULL,0,d-1500,NULL,d-30,'고마웠어' FROM t;

-- 망고: 6개월간 매주 기록(점점 증가), 보리: 격주 기록, Pixel 9: 기록 없음
WITH RECURSIVE w(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM w WHERE i < 25)
INSERT INTO weight_records (reptileId,recordedAt,grams)
SELECT 1, d - 7*i, round(42.0 - i*0.8 + (i % 3)*0.4, 1) FROM w, t;
WITH RECURSIVE w(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM w WHERE i < 11)
INSERT INTO weight_records (reptileId,recordedAt,grams)
SELECT 2, d - 14*i - 3, round(68.5 - i*1.2, 1) FROM w, t;

-- 반복 식단(요일 1=월 ~ 7=일)과 단일 일정
INSERT INTO care_schedules (reptileId,scheduledDate,careType,memo,repeatDayOfWeek)
SELECT 1,d,'충식','귀뚜라미 5마리',1 FROM t UNION ALL
SELECT 1,d,'사료','',4 FROM t UNION ALL
SELECT 2,d,'충식','밀웜',3 FROM t UNION ALL
SELECT 2,d+2,'금식','탈피 중',NULL FROM t;
COMMIT;
SELECT 'reptiles', COUNT(*) FROM reptiles UNION ALL
SELECT 'weight_records', COUNT(*) FROM weight_records UNION ALL
SELECT 'care_schedules', COUNT(*) FROM care_schedules;
SQL
