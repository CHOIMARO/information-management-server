-- 더빙 속도(배율)를 추가하고, 동작 확인용 AI 목소리(DYLAN)를 정리한다.

-- 1. 읽기 속도. 1.0이 보통 속도다 (Typecast audio_tempo, 0.5~2.0). 기존 작업은 보통 속도로 만들어졌다.
alter table dubbing_jobs add column speed double precision not null default 1.0;

-- 2. DYLAN은 목록에서 지웠다. 남은 행이 있으면 엔티티의 enum으로 바꿀 수 없어 조회가 실패한다.
--    동작 확인용 개발 데이터라서 지운다. S3의 결과 파일은 SQL로 지울 수 없어 남는다.
delete from dubbing_jobs where preset_voice = 'DYLAN';
