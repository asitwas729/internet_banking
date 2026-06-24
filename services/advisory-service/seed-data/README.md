# Advisory RAG 시드 데이터

Advisory 정책문서 RAG를 위한 시드 문서 디렉토리.

## 폴더 ↔ doc_category_cd 매핑

| 폴더 | doc_category_cd | 설명 |
|------|----------------|------|
| `law/` | `CREDIT_REGULATION` | 법규·규정 (금융소비자보호법, 여신전문금융업법 등) |
| `supervision/` | `SUPERVISION` | 감독 지침·통계 기준 (금융감독원 가이드라인, HMDA 방법론 등) |
| `internal/` | `INTERNAL_POLICY` | 내부 기준·절차 (심사 SOP, 편향 탐지 내규 등) |
| `product/` | `PRODUCT_POLICY` | 상품 정책 (신용대출 한도 기준, 금리 정책 등) |
| `fair-lending/` | `FAIR_LENDING` | 공정대출·편향 관련 (공정대출법, 편향 패턴 참조 데이터 등) |

> 파일의 YAML 프런트매터에 `doc_category_cd` 를 명시하면 폴더 기본값을 덮어씁니다.

## 파일 형식

각 `.md` 파일의 첫 블록에 YAML 프런트매터 포함:

```markdown
---
doc_cd: HMDA_STAT_001
doc_title: 대출심사 편향 탐지 통계 기준서
doc_version: "1.0"
effective_start_date: "20250101"
effective_end_date: "20991231"
source_uri: ""
doc_desc: "HMDA 방법론 기반 편향 탐지 기준값 정의서"
---

문서 본문...
```

파일명 규칙: `{doc_cd}.md`

## 적재 방법

```bash
# dev 서버 (기본 host: http://localhost:8080)
python data-tools/scripts/seed_advisory_rag.py

# 대상 host 지정
python data-tools/scripts/seed_advisory_rag.py --host http://localhost:8085

# dry-run (실제 적재 없이 대상 파일만 출력)
python data-tools/scripts/seed_advisory_rag.py --dry-run

# 특정 디렉토리만 적재
python data-tools/scripts/seed_advisory_rag.py --seed-dir services/advisory-service/seed-data/fair-lending
```

멱등 보장: 동일 `doc_cd` + `doc_version` 조합은 재실행 시 건너뜀(409 응답 = NO-OP).

## 재인입 (청크 설정 변경 후)

청크 크기·overlap 등 청킹 설정을 바꾸면(예: 800/100 → 1000/50, `docs/plan/16_chunk_size_eval.md`)
기존 임베딩이 무효화되므로 **재청킹·재임베딩**이 필요하다. 멱등 스킵을 우회하는 `--force` 로 교체 재인입한다.

```bash
# 1) 교체 재인입: 동일 doc_cd/version 의 기존 청크 삭제 + 문서 소프트삭제 후 새 설정으로 재청킹
python data-tools/scripts/seed_advisory_rag.py --force

# (서버측: POST /api/internal/advisory/documents?replace=true 로 전송)

# 2) IVFFlat lists 재산정 — 청크 수가 바뀌었으므로 실측 rows 기준 재인덱싱
curl -X POST http://localhost:8080/api/internal/advisory/rag/reindex
```

- `--force` 없이 재실행하면 기존과 동일하게 409 SKIP.
- 사례 인덱스(`advisory_case_index`)는 청크가 아니라 심사 요약 1건 임베딩이라 청크 설정 변경의 영향을 받지
  않는다(재인입 불필요). 모델 교체 시에만 `POST /rag/case-index/backfill` 로 재적재.

## dev용 seed_hmda_rag.py 와의 관계

`data-tools/scripts/seed_hmda_rag.py` 는 이 디렉토리의 문서 5건을 인라인 데이터로 포함하는 구버전 스크립트.
운영 시드는 본 디렉토리 파일 기반 `seed_advisory_rag.py` 를 사용. 두 스크립트 모두 멱등.
