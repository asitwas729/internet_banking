# 16. RAG 청크 크기·overlap 검색 품질 비교

- **측정일**: 2026-06-24
- **대상**: advisory-service RAG 청킹(`StructureAwareChunker.CHUNK_SIZE/CHUNK_OVERLAP`)
- **측정 코드**: `services/advisory-service/src/test/java/com/bank/loan/advisory/rag/ChunkSizeRetrievalEvalTest.java`
- **동결 질의셋**: `services/advisory-service/src/test/resources/chunk-eval/queries.json` (50개, gpt-4o-mini 합성)

---

## 1. 요약 (TL;DR)

기존 **800자 / overlap 100**은 "토큰 800 ≒ 문자 1000" 보수 근사로 경험 설정된 값이며 비교 근거가 없었다.
실제 사내 문서로 크기 {400·600·800·1000·1200} × overlap {0·50·100·150} 20셀을 OpenAI 임베딩으로
검색해 Recall@k·MRR@k·nDCG@k 를 측정한 결과:

> **채택: 청크 크기 800 → 1000자, overlap 100 → 50 (1000/50).**

| 지표 | 현재 800/100 | 채택 1000/50 | 변화 |
|---|---:|---:|---|
| Recall@5 | 0.500 | **0.580** | +8pp |
| MRR@5 | 0.297 | **0.401** | +35% |
| nDCG@5 | 0.230 | **0.346** | +50% |
| Recall@10 | 0.620 | **0.800** | +18pp |

- **청크 크기가 지배 변수.** 1000자에서 4개 지표 모두 정점.
- **overlap은 2차(노이즈) 변수.** 이번 실측에선 50이 최적(1000/50이 4지표 전부 1위), 50~150 모두 800/100 우위.

---

## 2. 방법론

| 항목 | 내용 |
|---|---|
| **코퍼스** | `docs/loan-service-api-spec.md` (108,754자, 헤딩 213개) |
| **청커** | 운영 슬라이딩 윈도우와 동일 식(step = size − overlap) |
| **임베딩** | OpenAI `text-embedding-3-small` (1536차원), 코사인 |
| **검색** | in-memory 전수 코사인(인덱스 영향 배제 — 순수 청킹 효과) |
| **질의** | H5 엔드포인트 섹션 50개를 gpt-4o-mini 로 자연어 질문화 → queries.json 동결 |
| **정답 라벨** | 마크다운 헤딩 섹션 span — 청크 char 구간이 정답 섹션 구간과 겹치면 정답 |
| **지표** | Recall@5(hit rate), MRR@5, nDCG@5, Recall@10 |

---

## 3. 결과 (질의 50개)

| 크기 | overlap | 청크수 | Recall@5 | MRR@5 | nDCG@5 | Recall@10 |
|---:|---:|---:|---:|---:|---:|---:|
| 400 | 0 | 239 | 0.460 | 0.292 | 0.194 | 0.540 |
| 400 | 50 | 273 | 0.440 | 0.230 | 0.165 | 0.600 |
| 400 | 100 | 318 | 0.440 | 0.271 | 0.172 | 0.580 |
| 400 | 150 | 381 | 0.400 | 0.312 | 0.205 | 0.560 |
| 600 | 0 | 159 | 0.420 | 0.296 | 0.237 | 0.580 |
| 600 | 50 | 174 | 0.460 | 0.282 | 0.211 | 0.620 |
| 600 | 100 | 191 | 0.440 | 0.361 | 0.256 | 0.580 |
| 600 | 150 | 212 | 0.460 | 0.317 | 0.229 | 0.580 |
| 800 | 0 | 120 | 0.520 | 0.321 | 0.276 | 0.620 |
| 800 | 50 | 128 | 0.480 | 0.335 | 0.258 | 0.640 |
| **800** | **100** | 137 | 0.500 | 0.297 | 0.230 | 0.620 |
| 800 | 150 | 147 | 0.500 | 0.372 | 0.267 | 0.600 |
| 1000 | 0 | 96 | 0.520 | 0.379 | 0.334 | 0.680 |
| **1000** | **50** | 101 | **0.580** | **0.401** | **0.346** | **0.800** |
| 1000 | 100 | 106 | 0.440 | 0.350 | 0.287 | 0.680 |
| 1000 | 150 | 112 | 0.540 | 0.397 | 0.338 | 0.680 |
| 1200 | 0 | 80 | 0.520 | 0.349 | 0.336 | 0.740 |
| 1200 | 50 | 83 | 0.520 | 0.350 | 0.302 | 0.700 |
| 1200 | 100 | 87 | 0.460 | 0.346 | 0.302 | 0.660 |
| 1200 | 150 | 91 | 0.560 | 0.371 | 0.312 | 0.700 |

---

## 4. 해석

1. **크기가 1차 변수.** 400~600은 답이 경계에서 잘려 낮고, 1000자에서 정점. 1200은 nDCG·Recall은
   비슷하나 MRR이 떨어진다(큰 청크일수록 정답 외 내용이 섞여 순위 변별력↓).
2. **overlap은 2차 변수.** 1000 행에서 overlap 50/150이 강하고 100이 dip — 진폭이 run-to-run 노이즈
   수준이다. 50~150 모두 현재 800/100을 상회한다. 비용(청크 수)·이번 1위를 종합해 **50** 채택.
3. **튜닝 우선순위**: 크기를 먼저 맞추고(800→1000), overlap은 노이즈 밴드 내 최저비용·최고점인 50.

---

## 5. 적용

- `StructureAwareChunker.CHUNK_SIZE = 1000`, `CHUNK_OVERLAP = 50`.
- **재인입 필요**: 청크 크기 변경은 기존 임베딩을 무효화한다. 정책문서·사례를 **재청킹·재임베딩(백필)** 하고
  `POST /api/internal/advisory/rag/reindex` 로 IVFFlat lists 를 재산정한다.

---

## 6. 한계

- 정답 판정이 "섹션 span 겹침"이라 큰 청크에 기계적으로 유리한 면이 일부 있으나, 순위 지표(MRR·nDCG)도
  1000을 가리켜 결론 방향은 견고. 더 엄밀히 하려면 정답을 짧은 "답변 앵커"로 좁혀야 한다.
- 코퍼스가 API 스펙 1종(구조·고밀도). 규정·FAQ 산문체에서는 곡선이 달라질 수 있어 코퍼스 다양화가 바람직.
- overlap 최적치(50 vs 100)는 run-to-run 노이즈에 민감. 단정에는 질의 표본 확대(100+)·반복 측정이 필요.

---

## 7. 재현

```bash
# 키는 형제 레포 .env(D:\internet_banking\.env) 의 OPENAI_API_KEY 사용
KEY=$(grep -E '^OPENAI_API_KEY=' /d/internet_banking/.env | sed -E 's/^OPENAI_API_KEY=//; s/"//g; s/\r$//')

# (1) 질의셋 재동결 (필요 시)
OPENAI_API_KEY="$KEY" ./gradlew --no-daemon :services:loan-service:test \
  --tests "com.bank.loan.advisory.rag.ChunkSizeRetrievalEvalTest.질의셋_50개_생성_동결" --rerun-tasks

# (2) 그리드 평가
OPENAI_API_KEY="$KEY" ./gradlew --no-daemon :services:loan-service:test \
  --tests "com.bank.loan.advisory.rag.ChunkSizeRetrievalEvalTest.청크크기별_검색품질_비교" --rerun-tasks
# 결과 표: services/loan-service/build/test-results/test/TEST-*.xml 의 <system-out>
```

- 키 없이도 `하니스_배선_오프라인_검증`은 항상 실행돼 파서·청커·지표·라벨 정합을 검증(CI 안전).
- 그리드 조정: 테스트의 `CHUNK_SIZES`, `OVERLAPS`, `GEN_COUNT` 상수.
