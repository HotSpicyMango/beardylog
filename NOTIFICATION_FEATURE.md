# 알림 기능 (2026-09-04)

## 추가된 것
- **설정 탭 "알림" 섹션**: 오늘의 일정 / 오늘의 식단 / 부화 임박 알림 토글
- **Google Drive 백업 카드**: 백업 경과 알림 토글
- **오늘의 일정 알림**: 달력 탭에서 미리 체크해둔 항목(6개 체크리스트 + 병원 기록)이 그 날짜가 되면 알림. 약 기록·메모는 제외
- **오늘의 식단 알림**: 개체별 당일 식단(충식/채식/사료/금식)을 한 알림에 몰아서 표시
- **부화 임박 알림**: 클러치 산란일 + 평균 인큐베이션(60일) 추정으로 부화 3일 전~당일 알림 (예상 부화일 필드가 없어 추정치)
- **백업 경과 알림**: 마지막 백업 후 14일 경과(또는 백업 이력 없음) 시 알림
- **D-Day 계산 변경**: 브리딩 짝짓기일/산란일 D-Day를 0일 시작 → 1일 시작으로 수정 (`BreedingSection.formatDDay`)
- 알림 탭 시 관련 탭으로 이동 (일정→달력, 식단→홈, 부화→브리딩, 백업→설정)

## 구조
`app/src/main/java/com/hsm/beardylog/notification/` 패키지
- `NotificationSettings` — 알림별 on/off 저장
- `AppNotificationChannel` — 알림 채널(낮은 중요도, 배터리 최적화)
- `NotificationScheduler` + `DailyNotificationWorker` — 매일 오전 9시 실행, 실행할 때마다 다음 9시로 스스로 재예약(드리프트 방지)
- `TodayScheduleNotifier` / `DailyDietNotifier` / `HatchDueSoonNotifier` / `DriveBackupOverdueNotifier` — 알림 종류별 로직
- `DayCursor` — 하루 놓쳐도 최근 며칠 치를 따라잡는 공용 커서
- `NotifiedClutchStore` — 부화 임박 알림 중복 방지용 기록
- `NotificationSupport` — 권한 체크 + 알림 게시 + 딥링크 PendingIntent 공용 함수

## 알려진 제한
- 부화 추정일은 참고용(±실제 인큐베이션 기간과 다를 수 있음)
- 네트워크 문제로 실기기 빌드 검증은 못 함 — Android Studio에서 한 번 빌드 확인 필요
- MIUI 등 배터리 최적화가 공격적인 기기에서는 알림이 안 올 수 있음(수동 배터리 최적화 예외 필요)
