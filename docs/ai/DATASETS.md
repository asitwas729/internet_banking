# AI 학습/평가용 데이터셋 목록

본 문서는 ai-service의 ML/LLM 파이프라인에 사용되는 **모든 외부 데이터셋의 출처, 라이선스, 버전, 가공 내역**을 기록한다. 신규 데이터셋 추가 시 반드시 본 문서에 항목 추가.

---

## 1. nvidia/Nemotron-Personas-Korea

### 출처
- HuggingFace: <https://huggingface.co/datasets/nvidia/Nemotron-Personas-Korea>
- 제공자: NVIDIA
- 발행일: 2026-04-20 (last modified 2026-04-23)

### 버전 (재현성 pin)
- Revision (commit SHA): `d0a9272116a2ebf139b964ca72b8b8f604616689`
- Branch: `main`
- 다운로드 일자: 2026-05-19

### 라이선스: CC BY 4.0
- 상업/비상업 사용 자유
- **의무**: 저작자 표시(attribution), 변경 사항 표시
- 본 프로젝트는 교육/포트폴리오 목적. CC BY 4.0 준수 위해:
  - 본 문서에 출처 명시 (현재 항목)
  - 원본 README.md를 `data/synthetic/personas/README.md`로 보관
  - 가공본(slim) 사용 시 "Modified from NVIDIA Nemotron-Personas-Korea (CC BY 4.0)" 표기

### 원본 사양
- 총 1,000,000 records × 26 columns
- Parquet 9 샤드, 원본 1.9 GB
- 데이터 소스: KOSIS, 대법원, NHIS, KREI, NAVER Cloud
- 인구학적 분포: 한국 실통계 기반 (19세 이상 성인만)

### 본 프로젝트 가공본 (slim)
- 위치: `data/synthetic/personas/slim/` (`.gitignore` 처리됨, 레포 미포함)
- 형식: Parquet, zstd level 9 압축
- 크기: **65.7 MB** (원본 대비 96.5% 감소)
- 레코드: 1,000,000 그대로
- **사용 14 컬럼**:
  | 컬럼 | 타입 | 용도 |
  |------|------|------|
  | `uuid` | string | 페르소나 식별자 (PK) |
  | `sex` | string | 성별 (남자/여자) — 공정성 평가 |
  | `age` | int8 | 19~99, 다운캐스트 |
  | `marital_status` | string | 기혼/미혼/이혼/사별 |
  | `military_status` | string | 군필/미필/면제/해당없음 |
  | `family_type` | string | 39 카테고리 (배우자+자녀 등) |
  | `housing_type` | string | 아파트/단독주택 등 6종 |
  | `education_level` | string | 7단계 (초등~대학원) |
  | `bachelors_field` | string | 학사 전공 |
  | `occupation` | string | 직업 (KSCO 기반) |
  | `district` | string | 시군구 (252+ 종) |
  | `province` | string | 17개 광역시도 |
  | `country` | string | "대한민국" |
  | `persona` | string | 1~2문장 요약 (LLM narrative 입력용) |

- **삭제 컬럼 (12)**: `professional_persona`, `sports_persona`, `arts_persona`, `travel_persona`, `culinary_persona`, `family_persona`, `cultural_background`, `skills_and_expertise`, `skills_and_expertise_list`, `hobbies_and_interests`, `hobbies_and_interests_list`, `career_goals_and_ambitions`
- 삭제 사유: 자동심사 Phase 1.1 Layer 1(Persona Base)에는 인구학 + 요약만 필요. narrative는 Phase 1.1 Layer 3에서 LLM이 새로 생성. 추후 narrative 필요 시 원본 재다운로드(소요 ~15분).

### 한계 / 사용 주의
- **19세 이상 성인만** → 청소년 대출은 어차피 대상 외라 무관
- **기업 페르소나 없음** → 본 자동심사 범위(개인대출)에 무관
- **일반 인구 분포 vs 대출 신청자 분포 차이**:
  - 페르소나는 KOSIS 일반 인구 분포 기반
  - 실제 대출 신청자는 셀프 셀렉션(청년/자영업자 over-represented)
  - **대응**: 합성 파이프라인에 명시적 reweighting 함수 분리 — 실데이터 도입 시 이 단계만 교체
- **demographic 변수 독립성 가정** 일부 적용 → 페르소나끼리 직업↔거주지 등 상관관계는 보존되나, 변수 일부는 한계 분포만 매칭
- **합성 데이터**: 실존 인물과의 유사성은 우연. PII 아님

### 재다운로드 절차
```bash
# pip install -U huggingface_hub
hf download nvidia/Nemotron-Personas-Korea \
  --repo-type dataset \
  --revision d0a9272116a2ebf139b964ca72b8b8f604616689 \
  --local-dir data/synthetic/personas
```

### slim 재생성 절차
원본 다운로드 후 `synthetic-data-generator` Gradle subproject의 `SlimifyPersonas` 태스크 (또는 동등 Python 스크립트)로 14개 컬럼 추출 + zstd-9 압축.

---

## 추가 데이터셋 후보 (미도입)

자동심사 합성 파이프라인 Layer 2(Financial Profile) / Layer 3(Narrative) 구축 시 검토:

| 후보 | 용도 | 상태 |
|------|------|------|
| KOSIS Open API (가구소득 분위, 산업별 임금) | Financial Profile 분포 시드 | 미도입, API 키 발급 필요 |
| 한국은행 ECOS API (가계대출 통계) | DSR/LTV 분포 검증 | 미도입 |
| 금감원 공시 (대출 연체율 분기) | Oracle 레이블 분포 보정 | 미도입 |

도입 시 본 문서에 동일 형식으로 항목 추가.
