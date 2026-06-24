package com.bank.ai.review.client;

import com.bank.ai.review.dto.AutoReviewRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AutoReviewRequest → inference-server snake_case feature map 변환.
 *
 * <p>규칙:
 * <ul>
 *   <li>camelCase → snake_case (Python 측 컬럼명과 일치)</li>
 *   <li>null 필드도 key 를 포함하되 value=null (서버 측 NaN imputation)</li>
 *   <li>boolean → Integer(0/1) — ONNX 입력 타입 요구</li>
 *   <li>Layer 4 PD 전용 필드는 {@link #toPdFeatures} 에서만 포함</li>
 * </ul>
 */
@Component
public class FeatureMapper {

    /** Decision 모델 입력 생성 (Layer 1~3). */
    public Map<String, Object> toDecisionFeatures(AutoReviewRequest req) {
        var m = new LinkedHashMap<String, Object>();
        // Layer 1
        m.put("sex", req.sex());
        m.put("age", req.age());
        m.put("marital_status", req.maritalStatus());
        m.put("military_status", req.militaryStatus());
        m.put("family_type", req.familyType());
        m.put("housing_type", req.housingType());
        m.put("education_level", req.educationLevel());
        m.put("bachelors_field", req.bachelorsField());
        m.put("occupation", req.occupation());
        m.put("district", req.district());
        m.put("province", req.province());
        m.put("applicant_segment", req.applicantSegment());
        // Layer 2
        m.put("income_quintile", req.incomeQuintile());
        m.put("annual_income_kw", req.annualIncomeKw());
        m.put("total_asset_kw", req.totalAssetKw());
        m.put("total_debt_kw", req.totalDebtKw());
        m.put("collateral_debt_kw", req.collateralDebtKw());
        m.put("credit_debt_kw", req.creditDebtKw());
        m.put("dsr", req.dsr());
        m.put("ltv", req.ltv());
        m.put("monthly_cashflow_mean_kw", req.monthlyCashflowMeanKw());
        m.put("monthly_cashflow_std_kw", req.monthlyCashflowStdKw());
        m.put("delinquency_history_24m", req.delinquencyHistory24m());
        m.put("credit_score_proxy", req.creditScoreProxy());
        // Layer 3
        m.put("product_code", req.productCode());
        m.put("requested_amount_kw", req.requestedAmountKw());
        m.put("requested_period_mo", req.requestedPeriodMo());
        m.put("purpose_cd", req.purposeCd());
        m.put("purpose_red_flag", toBit(req.purposeRedFlag()));
        return m;
    }

    /** PD 모델 입력 생성 (Layer 1~4 전체). */
    public Map<String, Object> toPdFeatures(AutoReviewRequest req) {
        var m = toDecisionFeatures(req);
        // Layer 4 PD 전용
        m.put("industry_cd", req.industryCd());
        m.put("region_risk_band", req.regionRiskBand());
        m.put("n_children", req.nChildren());
        m.put("employment_years", req.employmentYears());
        m.put("bureau_has_record", toBit(req.bureauHasRecord()));
        m.put("bureau_n_active", req.bureauNActive());
        m.put("bureau_max_status_24m", req.bureauMaxStatus24m());
        // Layer 4 확장 — homecredit_kr_v1 PD 피처
        m.put("ext_credit_score_2", req.extCreditScore2());
        m.put("ext_credit_score_3", req.extCreditScore3());
        m.put("bureau_overdue_cnt", req.bureauOverdueCnt());
        m.put("bureau_active_ratio", req.bureauActiveRatio());
        m.put("past_loan_dpd_mean", req.pastLoanDpdMean());
        m.put("past_loan_dpd_max", req.pastLoanDpdMax());
        m.put("past_loan_pay_ratio", req.pastLoanPayRatio());
        m.put("prev_app_refused_ratio", req.prevAppRefusedRatio());
        return m;
    }

    private static Integer toBit(Boolean v) {
        if (v == null) return null;
        return v ? 1 : 0;
    }
}
