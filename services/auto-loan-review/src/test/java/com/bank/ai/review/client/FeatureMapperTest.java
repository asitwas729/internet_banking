package com.bank.ai.review.client;

import com.bank.ai.review.dto.AutoReviewRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FeatureMapper — AutoReviewRequest → snake_case feature map 변환 검증.
 */
class FeatureMapperTest {

    private final FeatureMapper mapper = new FeatureMapper();

    private AutoReviewRequest fullRequest() {
        return new AutoReviewRequest(
                1L,
                "남자", 35, "MARRIED", "DONE", "NUCLEAR", "OWN", "BACHELOR", "ENG", "사무직", "강남구", "서울", "regular",
                5, 9000L, 100000L, 5000L, 3000L, 2000L, 0.28, 0.30, 700L, 50L, 0, 720,
                "MORT_001", 30000L, 36, "PURCHASE", false,
                "IND_01", 2, 1, 8, true, 3, 1,
                0.55, 0.60, 1, 0.5, 2.0, 10.0, 0.95, 0.1
        );
    }

    @Test
    void shouldMapAllLayer1To3Fields() {
        Map<String, Object> m = mapper.toDecisionFeatures(fullRequest());
        assertThat(m).hasSize(29);
        assertThat(m).containsKeys(
                "sex", "age", "marital_status", "military_status", "family_type",
                "housing_type", "education_level", "bachelors_field", "occupation", "district",
                "province", "applicant_segment",
                "income_quintile", "annual_income_kw", "total_asset_kw", "total_debt_kw",
                "collateral_debt_kw", "credit_debt_kw", "dsr", "ltv",
                "monthly_cashflow_mean_kw", "monthly_cashflow_std_kw", "delinquency_history_24m", "credit_score_proxy",
                "product_code", "requested_amount_kw", "requested_period_mo", "purpose_cd", "purpose_red_flag");
    }

    @Test
    void shouldMapLayer4InPdFeaturesOnly() {
        Map<String, Object> dec = mapper.toDecisionFeatures(fullRequest());
        Map<String, Object> pd = mapper.toPdFeatures(fullRequest());

        assertThat(dec).doesNotContainKey("industry_cd");
        assertThat(pd).hasSize(44);
        assertThat(pd).containsKeys(
                "industry_cd", "region_risk_band", "n_children", "employment_years",
                "bureau_has_record", "bureau_n_active", "bureau_max_status_24m");
        assertThat(pd).containsKeys(
                "ext_credit_score_2", "ext_credit_score_3", "bureau_overdue_cnt", "bureau_active_ratio",
                "past_loan_dpd_mean", "past_loan_dpd_max", "past_loan_pay_ratio", "prev_app_refused_ratio");
    }

    @Test
    void shouldConvertBooleanToInt() {
        Map<String, Object> pd = mapper.toPdFeatures(fullRequest());
        assertThat(pd.get("purpose_red_flag")).isEqualTo(0);   // false → 0
        assertThat(pd.get("bureau_has_record")).isEqualTo(1);  // true → 1
    }

    @Test
    void shouldIncludeNullValueForMissingFields() {
        AutoReviewRequest sparse = new AutoReviewRequest(
                1L,
                null, 35, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );
        Map<String, Object> pd = mapper.toPdFeatures(sparse);

        assertThat(pd).containsKey("sex");
        assertThat(pd.get("sex")).isNull();
        assertThat(pd.get("dsr")).isNull();
        assertThat(pd.get("purpose_red_flag")).isNull();   // toBit(null) → null
        assertThat(pd.get("bureau_has_record")).isNull();
    }

    @Test
    void shouldNotMutateOriginalRequest() {
        AutoReviewRequest req = fullRequest();
        Map<String, Object> m = mapper.toDecisionFeatures(req);
        m.put("sex", "CHANGED");
        m.remove("age");

        assertThat(req.sex()).isEqualTo("남자");
        assertThat(req.age()).isEqualTo(35);
        Map<String, Object> again = mapper.toDecisionFeatures(req);
        assertThat(again.get("sex")).isEqualTo("남자");
        assertThat(again).containsKey("age");
    }
}
