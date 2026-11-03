-- refresh 토큰 교체(rotation)와 해시 저장.
-- 재발급할 때마다 새 refresh 토큰을 주고, 이전 토큰은 "교체됨"으로 표시한다.
-- 같은 로그인에서 이어진 토큰은 family_id로 묶는다. 교체된 토큰이 유예 시간 뒤에 다시 오면 묶음 전체를 지운다 (재사용 감지).

alter table refresh_tokens add column token_hash varchar(64);
alter table refresh_tokens add column family_id varchar(36);
alter table refresh_tokens add column rotated_at timestamp(6);

-- 기존 토큰: 원문을 SHA-256 해시(16진수 소문자)로 바꾼다. 서버 코드와 같은 계산이다.
-- 앱이 가진 토큰은 그대로 쓸 수 있으므로 다시 로그인할 필요가 없다. 기존 토큰은 하나씩 자기 묶음이 된다.
update refresh_tokens
set token_hash = encode(sha256(convert_to(token, 'UTF8')), 'hex'),
    family_id  = gen_random_uuid()::text;

alter table refresh_tokens alter column token_hash set not null;
alter table refresh_tokens alter column family_id set not null;

-- 원문 컬럼을 지운다. DB가 새어도 토큰 원문은 남지 않는다.
alter table refresh_tokens drop constraint uk_refresh_tokens_token;
alter table refresh_tokens drop column token;

alter table refresh_tokens add constraint uk_refresh_tokens_token_hash unique (token_hash);
-- 로그아웃·재사용 감지 때 묶음 전체를 지우는 쿼리용
create index idx_refresh_tokens_family_id on refresh_tokens (family_id);
