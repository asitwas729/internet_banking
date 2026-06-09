-- V14: 데모 로그인 계정용 입출금 계좌·잔액 시드 (이체 테스트용)
-- 고객(user01~10)·직원·관리자 로그인 계정마다 활성 DEPOSIT 계좌와 잔액을 채워
-- 이체 기능을 바로 테스트할 수 있게 한다.
--
-- ⚠️ 운영 안전장치: 이 시드는 placeholder ${seedDemoData} 가 'true' 일 때만 실행된다.
--   - application.yml(기본/운영): seedDemoData=false  → 아래 INSERT/UPDATE 모두 0건
--   - application-local.yml(로컬): seedDemoData=true   → 시드 적용
-- 모든 구문은 ON CONFLICT DO NOTHING / GREATEST 로 멱등하게 작성되어
-- 이미 시드된 DB에서 재실행해도 안전하다.
--
-- customer_id 매핑(customer-service credential 기준):
--   9001 employee01  9002 employee02  9003 audit01  9004 review01  9005 risk01
--   9006 mkt01       9007 owner01     9008 staff01  9009 other01   9010 deputy01
--   9011 ops01       9101~9105 admin01~05         9111~9120 user01~10

-- ──────────────────────────────────────────────────────────────────────────
-- 1) 계약(deposit_contracts) 시드
--    contract_id 5001~5026: 계정별 기본 계좌 / 5201~5203: user01~03 자가이체용 보조 계좌
-- ──────────────────────────────────────────────────────────────────────────
INSERT INTO deposit_contracts (
    contract_id, contract_number, customer_id, banking_product_id,
    is_monthly_payment, join_amount, contract_interest_rate, total_preferential_rate,
    final_interest_rate, tax_benefit_type, applied_tax_rate, contract_period_month,
    started_at, maturity_at, is_auto_renewal, auto_transfer_enabled, contract_status,
    join_channel, is_proxy_joined, is_power_of_attorney_verified,
    created_at, updated_at, consecutive_miss_count)
SELECT v.contract_id, v.contract_number, v.customer_id, 1,
       false, 50000000, 0.10, 0.00, 0.10, 'GENERAL', 15.40, 12,
       current_date, current_date + interval '12 months', false, false, 'ACTIVE',
       'WEB', false, false, now(), now(), 0
FROM (VALUES
    (5001, 'SEED-9001-DEP',  '9001'), (5002, 'SEED-9002-DEP',  '9002'),
    (5003, 'SEED-9003-DEP',  '9003'), (5004, 'SEED-9004-DEP',  '9004'),
    (5005, 'SEED-9005-DEP',  '9005'), (5006, 'SEED-9006-DEP',  '9006'),
    (5007, 'SEED-9007-DEP',  '9007'), (5008, 'SEED-9008-DEP',  '9008'),
    (5009, 'SEED-9009-DEP',  '9009'), (5010, 'SEED-9010-DEP',  '9010'),
    (5011, 'SEED-9011-DEP',  '9011'), (5012, 'SEED-9101-DEP',  '9101'),
    (5013, 'SEED-9102-DEP',  '9102'), (5014, 'SEED-9103-DEP',  '9103'),
    (5015, 'SEED-9104-DEP',  '9104'), (5016, 'SEED-9105-DEP',  '9105'),
    (5017, 'SEED-9111-DEP',  '9111'), (5018, 'SEED-9112-DEP',  '9112'),
    (5019, 'SEED-9113-DEP',  '9113'), (5020, 'SEED-9114-DEP',  '9114'),
    (5021, 'SEED-9115-DEP',  '9115'), (5022, 'SEED-9116-DEP',  '9116'),
    (5023, 'SEED-9117-DEP',  '9117'), (5024, 'SEED-9118-DEP',  '9118'),
    (5025, 'SEED-9119-DEP',  '9119'), (5026, 'SEED-9120-DEP',  '9120'),
    (5201, 'SEED-9111-DEP2', '9111'), (5202, 'SEED-9112-DEP2', '9112'),
    (5203, 'SEED-9113-DEP2', '9113')
) AS v(contract_id, contract_number, customer_id)
WHERE '${seedDemoData}' = 'true'
ON CONFLICT (contract_id) DO NOTHING;

-- ──────────────────────────────────────────────────────────────────────────
-- 2) 계좌(deposit_accounts) 시드
--    잔액: 기본 계좌 5천만원 / 보조 계좌 3천만원
--    daily_withdraw_limit 은 NULL(무제한)로 두어 테스트 금액 제한 없음
--    account_password 는 유효한 bcrypt 해시(데모 공용)
-- ──────────────────────────────────────────────────────────────────────────
INSERT INTO deposit_accounts (
    account_number, customer_id, contract_id, account_type, bank_code,
    balance, currency, account_password,
    is_withdrawable, is_online_banking_enabled, is_mobile_banking_enabled, is_phone_banking_enabled,
    account_status, opened_at, created_at, created_by)
SELECT v.account_number, v.customer_id, v.contract_id, 'DEPOSIT', '001',
       v.balance, 'KRW', '$2a$10$hTp1Rac9BsYw/6ZzFl..IeTX048JgErR1o.F7GzTM5PuSPLgNTYjG',
       true, true, true, true,
       'ACTIVE', current_date, now(), 'seed-v14'
FROM (VALUES
    ('001-2000-0000001', '9001', 5001, 50000000), ('001-2000-0000002', '9002', 5002, 50000000),
    ('001-2000-0000003', '9003', 5003, 50000000), ('001-2000-0000004', '9004', 5004, 50000000),
    ('001-2000-0000005', '9005', 5005, 50000000), ('001-2000-0000006', '9006', 5006, 50000000),
    ('001-2000-0000007', '9007', 5007, 50000000), ('001-2000-0000008', '9008', 5008, 50000000),
    ('001-2000-0000009', '9009', 5009, 50000000), ('001-2000-0000010', '9010', 5010, 50000000),
    ('001-2000-0000011', '9011', 5011, 50000000), ('001-2000-0000012', '9101', 5012, 50000000),
    ('001-2000-0000013', '9102', 5013, 50000000), ('001-2000-0000014', '9103', 5014, 50000000),
    ('001-2000-0000015', '9104', 5015, 50000000), ('001-2000-0000016', '9105', 5016, 50000000),
    ('001-2000-0000017', '9111', 5017, 50000000), ('001-2000-0000018', '9112', 5018, 50000000),
    ('001-2000-0000019', '9113', 5019, 50000000), ('001-2000-0000020', '9114', 5020, 50000000),
    ('001-2000-0000021', '9115', 5021, 50000000), ('001-2000-0000022', '9116', 5022, 50000000),
    ('001-2000-0000023', '9117', 5023, 50000000), ('001-2000-0000024', '9118', 5024, 50000000),
    ('001-2000-0000025', '9119', 5025, 50000000), ('001-2000-0000026', '9120', 5026, 50000000),
    ('001-2002-0000001', '9111', 5201, 30000000), ('001-2002-0000002', '9112', 5202, 30000000),
    ('001-2002-0000003', '9113', 5203, 30000000)
) AS v(account_number, customer_id, contract_id, balance)
WHERE '${seedDemoData}' = 'true'
ON CONFLICT (account_number) DO NOTHING;

-- ──────────────────────────────────────────────────────────────────────────
-- 3) 기존 활성 계좌 잔액 보충
--    이전에 0원으로 개설된 계좌(예: 런타임 생성 user01 계좌)나 기존 시드 계좌가
--    비어 있어도 이체 테스트가 가능하도록 최소 5천만원을 보장한다.
-- ──────────────────────────────────────────────────────────────────────────
UPDATE deposit_accounts
   SET balance = GREATEST(balance, 50000000),
       updated_at = now(),
       updated_by = 'seed-v14'
 WHERE '${seedDemoData}' = 'true'
   AND account_status = 'ACTIVE'
   AND balance < 50000000
   AND created_by IS DISTINCT FROM 'seed-v14';  -- 본 시드가 만든 계좌(보조 30M 등)는 건드리지 않음
