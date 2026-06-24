package com.bank.loan.advisory.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크 크기·overlap 검색 품질 평가 하니스 (plan §15 후속 — 경험값 800/100 근거 측정).
 *
 * <p>실제 사내 문서(docs/loan-service-api-spec.md)를 크기×overlap 그리드로 청킹·임베딩해
 * Recall@k·MRR@k·nDCG@k 를 비교한다. 정답 라벨은 마크다운 헤딩 섹션 span 겹침.
 *
 * <ul>
 *   <li>{@code 하니스_배선_오프라인_검증} — 키 없이 항상 실행(CI 안전). 파서·청커·지표·라벨 정합 검증.</li>
 *   <li>{@code 질의셋_50개_생성_동결} — OPENAI_API_KEY 필요. LLM 으로 NL 질의 합성 → queries.json 동결.</li>
 *   <li>{@code 청크크기별_검색품질_비교} — OPENAI_API_KEY 필요. 동결 질의셋으로 그리드 측정·표 출력.</li>
 * </ul>
 *
 * 청킹은 운영 {@link com.bank.loan.advisory.rag.chunk.StructureAwareChunker} 의 슬라이딩
 * 윈도우 로직과 동일한 식(step = size - overlap)을 사용한다.
 */
class ChunkSizeRetrievalEvalTest {

    static final int[] CHUNK_SIZES = {400, 600, 800, 1000, 1200};
    static final int[] OVERLAPS    = {0, 50, 100, 150};
    static final int   K           = 5;
    static final int   GEN_COUNT   = 50;

    static final String CORPUS_REL  = "docs/loan-service-api-spec.md";
    static final String QUERIES_RES = "/chunk-eval/queries.json";
    static final String EMBED_MODEL = "text-embedding-3-small";
    static final String GEN_MODEL   = "gpt-4o-mini";

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} +(.*)$");
    private static final ObjectMapper OM = new ObjectMapper();

    record Section(String title, int start, int end) {}
    record Query(String query, String section) {}
    record ChunkSpan(String text, int start, int end) {}

    // ──────────────────────────────────────────────────────────────────────
    // 오프라인 배선 검증 (키 불필요)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void 하니스_배선_오프라인_검증() throws Exception {
        String corpus = loadCorpus();
        assertThat(corpus.length()).isGreaterThan(50_000);

        List<Section> sections = parseSections(corpus);
        assertThat(sections.size()).isGreaterThan(100);
        // span 단조·커버 검증
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            assertThat(s.end()).isGreaterThan(s.start());
            if (i + 1 < sections.size()) {
                assertThat(s.end()).isEqualTo(sections.get(i + 1).start());
            }
        }

        // 청크 스팬: 첫 시작 0, 각 길이 ≤ size, 마지막 end == corpus 길이
        List<ChunkSpan> chunks = chunk(corpus, 800, 100);
        assertThat(chunks.get(0).start()).isZero();
        assertThat(chunks).allMatch(c -> c.text().length() <= 800);
        assertThat(chunks.get(chunks.size() - 1).end()).isEqualTo(corpus.length());

        // 라벨 겹침 함수
        assertThat(overlaps(new ChunkSpan("", 10, 20), new Section("s", 15, 30))).isTrue();
        assertThat(overlaps(new ChunkSpan("", 0, 10), new Section("s", 10, 20))).isFalse();

        // 지표 정합 (toy): 정답이 1위면 mrr=1, recall@5=1; nDCG@5=1
        boolean[] top = {true, false, false, false, false};
        assertThat(mrrAtK(top, 5)).isEqualTo(1.0);
        assertThat(recallHitAtK(top, 5)).isEqualTo(1.0);
        assertThat(ndcgAtK(top, 1, 5)).isEqualTo(1.0);
        // 정답이 2위면 mrr=0.5, nDCG=1/log2(3)
        boolean[] second = {false, true, false, false, false};
        assertThat(mrrAtK(second, 5)).isEqualTo(0.5);
        assertThat(ndcgAtK(second, 1, 5)).isCloseTo(1.0 / (Math.log(3) / Math.log(2)), org.assertj.core.data.Offset.offset(1e-9));

        // 동결 질의셋이 모두 실제 섹션으로 해석되는지
        List<Query> queries = loadQueries();
        assertThat(queries).isNotEmpty();
        for (Query q : queries) {
            assertThat(findSection(sections, q.section()))
                    .as("질의 섹션 미해석: %s", q.section())
                    .isNotNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 질의셋 생성 (LLM — 키 필요). queries.json 을 새로 동결한다.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void 질의셋_50개_생성_동결() throws Exception {
        String corpus = loadCorpus();
        List<Section> sections = parseSections(corpus);

        // 엔드포인트(H5 backtick 메서드) 섹션만 대상
        List<Section> endpoints = sections.stream()
                .filter(s -> s.title().matches(".*`(GET|POST|PUT|PATCH|DELETE)`.*"))
                .limit(GEN_COUNT)
                .toList();

        List<Map<String, String>> out = new ArrayList<>();
        for (Section s : endpoints) {
            String body = corpus.substring(s.start(), Math.min(s.end(), s.start() + 1500));
            String q = synthesizeQuery(body);
            if (q != null && !q.isBlank()) {
                out.add(Map.of("query", q.strip(), "section", s.title()));
            }
        }
        Path file = repoPath("services/advisory-service/src/test/resources/chunk-eval/queries.json");
        Files.writeString(file, OM.writerWithDefaultPrettyPrinter().writeValueAsString(out), StandardCharsets.UTF_8);
        System.out.printf("질의셋 %d개 동결 → %s%n", out.size(), file);
        assertThat(out).isNotEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 그리드 평가 (임베딩 — 키 필요)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void 청크크기별_검색품질_비교() throws Exception {
        String corpus = loadCorpus();
        List<Section> sections = parseSections(corpus);
        List<Query> queries = loadQueries();

        // 질의 임베딩은 셀과 무관 — 1회 계산 후 재사용
        List<String> qTexts = queries.stream().map(Query::query).toList();
        float[][] qVecs = embed(qTexts);
        List<Section> qSections = queries.stream().map(q -> findSection(sections, q.section())).toList();

        System.out.println("\n| 크기 | overlap | 청크수 | Recall@5 | MRR@5 | nDCG@5 | Recall@10 |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---:|");

        for (int size : CHUNK_SIZES) {
            for (int overlap : OVERLAPS) {
                List<ChunkSpan> chunks = chunk(corpus, size, overlap);
                float[][] cVecs = embed(chunks.stream().map(ChunkSpan::text).toList());

                double sumR5 = 0, sumMrr = 0, sumNdcg = 0, sumR10 = 0;
                for (int qi = 0; qi < queries.size(); qi++) {
                    boolean[] rel = rankRelevance(qVecs[qi], cVecs, chunks, qSections.get(qi));
                    int totalRel = countTrue(rel);
                    sumR5   += recallHitAtK(rel, 5);
                    sumMrr  += mrrAtK(rel, 5);
                    sumNdcg += ndcgAtK(rel, totalRel, 5);
                    sumR10  += recallHitAtK(rel, 10);
                }
                int n = queries.size();
                System.out.printf("| %d | %d | %d | %.3f | %.3f | %.3f | %.3f |%n",
                        size, overlap, chunks.size(),
                        sumR5 / n, sumMrr / n, sumNdcg / n, sumR10 / n);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 코퍼스·섹션·청킹
    // ──────────────────────────────────────────────────────────────────────

    static String loadCorpus() throws Exception {
        return Files.readString(repoPath(CORPUS_REL), StandardCharsets.UTF_8);
    }

    static List<Section> parseSections(String corpus) {
        List<int[]> heads = new ArrayList<>();   // [lineStart]
        List<String> titles = new ArrayList<>();
        Matcher m = HEADING.matcher(corpus);
        while (m.find()) {
            heads.add(new int[]{m.start()});
            titles.add(m.group(1).strip());
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            int start = heads.get(i)[0];
            int end = (i + 1 < heads.size()) ? heads.get(i + 1)[0] : corpus.length();
            sections.add(new Section(titles.get(i), start, end));
        }
        return sections;
    }

    /** 운영 StructureAwareChunker.slidingWindow 와 동일한 step = size - overlap 윈도우. */
    static List<ChunkSpan> chunk(String content, int size, int overlap) {
        List<ChunkSpan> result = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            int end = Math.min(pos + size, content.length());
            result.add(new ChunkSpan(content.substring(pos, end), pos, end));
            if (end >= content.length()) break;
            pos += size - overlap;
        }
        return result;
    }

    static boolean overlaps(ChunkSpan c, Section s) {
        return c.start() < s.end() && s.start() < c.end();
    }

    static Section findSection(List<Section> sections, String title) {
        return sections.stream().filter(s -> s.title().equals(title)).findFirst().orElse(null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 지표
    // ──────────────────────────────────────────────────────────────────────

    static boolean[] rankRelevance(float[] qVec, float[][] cVecs, List<ChunkSpan> chunks, Section target) {
        int n = chunks.size();
        Integer[] idx = new Integer[n];
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            score[i] = cosine(qVec, cVecs[i]);
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));
        boolean[] rel = new boolean[n];
        for (int r = 0; r < n; r++) {
            rel[r] = target != null && overlaps(chunks.get(idx[r]), target);
        }
        return rel;
    }

    /** top-k 안에 정답이 1개라도 있으면 1.0 (hit rate). */
    static double recallHitAtK(boolean[] rankedRel, int k) {
        for (int i = 0; i < Math.min(k, rankedRel.length); i++) if (rankedRel[i]) return 1.0;
        return 0.0;
    }

    static double mrrAtK(boolean[] rankedRel, int k) {
        for (int i = 0; i < Math.min(k, rankedRel.length); i++) if (rankedRel[i]) return 1.0 / (i + 1);
        return 0.0;
    }

    static double ndcgAtK(boolean[] rankedRel, int totalRelevant, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, rankedRel.length); i++) {
            if (rankedRel[i]) dcg += 1.0 / log2(i + 2);
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(k, totalRelevant); i++) idcg += 1.0 / log2(i + 2);
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    static double log2(double x) { return Math.log(x) / Math.log(2); }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ──────────────────────────────────────────────────────────────────────
    // OpenAI 호출
    // ──────────────────────────────────────────────────────────────────────

    /** 일시적 5xx·연결 오류는 백오프 재시도(OpenAI upstream 종료 대비). */
    static JsonNode callJson(RestClient client, String uri, Object body) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return client.post().uri(uri).body(body).retrieve().body(JsonNode.class);
            } catch (org.springframework.web.client.HttpServerErrorException
                     | org.springframework.web.client.ResourceAccessException e) {
                last = e;
                try { Thread.sleep(1500L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw last;
    }

    static RestClient openai() {
        return RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + System.getenv("OPENAI_API_KEY"))
                .build();
    }

    static float[][] embed(List<String> texts) {
        RestClient client = openai();
        float[][] out = new float[texts.size()][];
        final int batch = 96;
        for (int from = 0; from < texts.size(); from += batch) {
            List<String> slice = texts.subList(from, Math.min(from + batch, texts.size()));
            JsonNode resp = callJson(client, "/v1/embeddings",
                    Map.of("model", EMBED_MODEL, "input", slice));
            JsonNode data = resp.get("data");
            for (JsonNode item : data) {
                int index = item.get("index").asInt();
                JsonNode emb = item.get("embedding");
                float[] v = new float[emb.size()];
                for (int j = 0; j < emb.size(); j++) v[j] = (float) emb.get(j).asDouble();
                out[from + index] = v;
            }
        }
        return out;
    }

    static String synthesizeQuery(String sectionBody) {
        RestClient client = openai();
        String sys = "다음 API 명세 섹션 내용을 보고, 그 섹션이 답이 되는 한국어 자연어 질문 한 개만 만들어라. "
                + "헤딩이나 경로를 그대로 베끼지 말고 사용자가 실제로 물을 법한 문장으로. 질문만 출력.";
        JsonNode resp = callJson(client, "/v1/chat/completions", Map.of(
                "model", GEN_MODEL,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", sys),
                        Map.of("role", "user", "content", sectionBody))));
        return resp.get("choices").get(0).get("message").get("content").asText();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 리소스 로딩
    // ──────────────────────────────────────────────────────────────────────

    List<Query> loadQueries() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(QUERIES_RES)) {
            assertThat(in).as("queries.json 리소스 누락").isNotNull();
            JsonNode arr = OM.readTree(in);
            List<Query> queries = new ArrayList<>();
            for (JsonNode q : arr) {
                queries.add(new Query(q.get("query").asText(), q.get("section").asText()));
            }
            return queries;
        }
    }

    /** 작업 디렉터리에서 위로 올라가며 레포 루트 기준 상대경로를 해석. */
    static Path repoPath(String rel) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(rel);
            if (Files.exists(candidate)) return candidate;
        }
        // 못 찾으면 user.dir 기준 경로 반환(쓰기 케이스)
        return Path.of(System.getProperty("user.dir")).resolve(rel);
    }
}
