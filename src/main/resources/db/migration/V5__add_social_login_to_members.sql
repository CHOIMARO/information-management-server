-- SNS 로그인(카카오/구글) 지원:
-- 1) SNS 계정은 비밀번호가 없다 → password의 NOT NULL 해제
-- 2) SNS가 이메일을 안 줄 수 있다 → email의 NOT NULL 해제
-- 3) SNS 계정의 신원은 (provider, provider_id) 조합
-- 기존 행들은 default 'LOCAL'로 채워지므로 데이터 이전 UPDATE가 따로 필요 없다.
alter table members alter column email set null;
alter table members alter column password set null;
alter table members add column provider varchar(20) default 'LOCAL' not null;
alter table members add column provider_id varchar(100);
alter table members add constraint uk_members_provider unique (provider, provider_id);
