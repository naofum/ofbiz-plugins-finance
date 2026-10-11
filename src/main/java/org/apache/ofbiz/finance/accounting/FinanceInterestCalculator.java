/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/

package org.apache.ofbiz.finance.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;

import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * Shared helper for configurable interest calculation.
 *
 * <p>Supports the following day-count conventions (enumId from LOAN_DAY_COUNT_CONV):
 * <ul>
 *   <li>{@code DAY_COUNT_ACT_365} – Actual/365 Fixed: daily rate = annualRate / 365</li>
 *   <li>{@code DAY_COUNT_ACT_360} – Actual/360: daily rate = annualRate / 360</li>
 *   <li>{@code DAY_COUNT_ACT_ACT} – Actual/Actual (ISDA): daily rate = annualRate / daysInYear(day)</li>
 *   <li>{@code DAY_COUNT_30_360} – 30/360 Bond Basis: period rate = annualRate * days30_360 / 360</li>
 * </ul>
 *
 * <p>Supports the following period endpoint conventions (enumId from LOAN_PERIOD_ENDPT):
 * <ul>
 *   <li>{@code PERIOD_BOTH}       – Both ends inclusive [from, to]:  days = to - from + 1</li>
 *   <li>{@code PERIOD_BACK_EXCL}  – Back-end exclusive  [from, to):  days = to - from (後落とし)</li>
 *   <li>{@code PERIOD_FRONT_EXCL} – Front-end exclusive (from, to]:  days = to - from (前落とし)</li>
 * </ul>
 *
 * <p>Convention resolution order (highest priority first):
 * <ol>
 *   <li>FinancialProduct field override (product-level)</li>
 *   <li>SystemProperty / finance.properties key</li>
 *   <li>Hard-coded fallback</li>
 * </ol>
 *
 * <p>Currency rounding is resolved from finance.properties / SystemProperty:
 * <ul>
 *   <li>Key {@code currency.scale.<currencyUomId>} → decimal scale (integer)</li>
 *   <li>Key {@code currency.rounding.<currencyUomId>} → DOWN, HALF_UP, or UP</li>
 *   <li>Fallback keys: {@code currency.scale.default} / {@code currency.rounding.default}</li>
 * </ul>
 */
public final class FinanceInterestCalculator {

    /** Finance properties resource name (maps to config/finance.properties). */
    private static final String FINANCE_RESOURCE = "finance";

    /** Intermediate computation precision to minimise rounding drift. */
    private static final int INTERMEDIATE_SCALE = 12;
    private static final RoundingMode INTERMEDIATE_ROUNDING = RoundingMode.HALF_UP;

    /** Hard-coded fallback convention used when nothing is configured. */
    private static final String DEFAULT_DAY_COUNT = "DAY_COUNT_ACT_365";
    private static final String DEFAULT_PERIOD_ENDPOINT = "PERIOD_BOTH";
    private static final String DEFAULT_DUEDATE_CONVENTION = "DUEDATE_NONE";

    /** Denominators for fixed conventions. */
    private static final BigDecimal D_365 = new BigDecimal("365");
    private static final BigDecimal D_360 = new BigDecimal("360");

    private FinanceInterestCalculator() {
    }

    // -----------------------------------------------------------------------
    // Convention resolution
    // -----------------------------------------------------------------------

    /**
     * Resolve the day-count convention enumId for the given agreement.
     * Reads the associated FinancialProduct first; falls back to the
     * system-wide setting in finance.properties / SystemProperty entity.
     *
     * @param delegator  OFBiz delegator
     * @param agreement  LoanAgreement GenericValue (must have productId via LoanApplication)
     * @return enumId string such as {@code "DAY_COUNT_ACT_365"}
     */
    public static String resolveDayCountConvention(Delegator delegator, GenericValue agreement) {
        // 1. Try FinancialProduct.dayCountConventionEnumId
        try {
            String productId = resolveProductId(delegator, agreement);
            if (UtilValidate.isNotEmpty(productId)) {
                GenericValue product = EntityQuery.use(delegator)
                        .from("FinancialProduct")
                        .where("productId", productId)
                        .cache()
                        .queryOne();
                if (product != null) {
                    String fromProduct = product.getString("dayCountConventionEnumId");
                    if (UtilValidate.isNotEmpty(fromProduct)) {
                        return fromProduct;
                    }
                }
            }
        } catch (GenericEntityException e) {
            // fall through to system property
        }

        // 2. SystemProperty / finance.properties
        String fromSystem = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "interest.daycount.convention", null, delegator);
        if (UtilValidate.isNotEmpty(fromSystem)) {
            return fromSystem;
        }

        // 3. Hard-coded fallback
        return DEFAULT_DAY_COUNT;
    }

    /** Resolve convention for cases where no agreement context exists (e.g. late-fee). */
    public static String resolveDayCountConvention(Delegator delegator, String productId) {
        // 1. Try FinancialProduct.dayCountConventionEnumId
        if (UtilValidate.isNotEmpty(productId)) {
            try {
                GenericValue product = EntityQuery.use(delegator)
                        .from("FinancialProduct")
                        .where("productId", productId)
                        .cache()
                        .queryOne();
                if (product != null) {
                    String fromProduct = product.getString("dayCountConventionEnumId");
                    if (UtilValidate.isNotEmpty(fromProduct)) {
                        return fromProduct;
                    }
                }
            } catch (GenericEntityException e) {
                // fall through
            }
        }

        // 2. SystemProperty / finance.properties
        String fromSystem = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "interest.daycount.convention", null, delegator);
        if (UtilValidate.isNotEmpty(fromSystem)) {
            return fromSystem;
        }

        return DEFAULT_DAY_COUNT;
    }

    // -----------------------------------------------------------------------
    // Period endpoint convention resolution
    // -----------------------------------------------------------------------

    /**
     * Resolve the period endpoint convention enumId.
     * Resolution: FinancialProduct.periodEndpointEnumId → SystemProperty/finance.properties
     * key {@code interest.period.endpoint} → {@code PERIOD_BOTH}.
     */
    public static String resolvePeriodEndpoint(Delegator delegator, GenericValue agreement) {
        try {
            String productId = resolveProductId(delegator, agreement);
            if (UtilValidate.isNotEmpty(productId)) {
                GenericValue product = EntityQuery.use(delegator)
                        .from("FinancialProduct").where("productId", productId).cache().queryOne();
                if (product != null) {
                    String v = product.getString("periodEndpointEnumId");
                    if (UtilValidate.isNotEmpty(v)) return v;
                }
            }
        } catch (GenericEntityException e) { /* fall through */ }
        String sys = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "interest.period.endpoint", null, delegator);
        return UtilValidate.isNotEmpty(sys) ? sys : DEFAULT_PERIOD_ENDPOINT;
    }

    /** Resolve period endpoint when only productId is available. */
    public static String resolvePeriodEndpoint(Delegator delegator, String productId) {
        if (UtilValidate.isNotEmpty(productId)) {
            try {
                GenericValue product = EntityQuery.use(delegator)
                        .from("FinancialProduct").where("productId", productId).cache().queryOne();
                if (product != null) {
                    String v = product.getString("periodEndpointEnumId");
                    if (UtilValidate.isNotEmpty(v)) return v;
                }
            } catch (GenericEntityException e) { /* fall through */ }
        }
        String sys = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "interest.period.endpoint", null, delegator);
        return UtilValidate.isNotEmpty(sys) ? sys : DEFAULT_PERIOD_ENDPOINT;
    }

    /**
     * Adjust the loop boundaries (firstDay / lastDay) according to the endpoint convention.
     *
     * <ul>
     *   <li>PERIOD_BOTH:       [from, to]  → firstDay=from, lastDay=to   (no change)</li>
     *   <li>PERIOD_BACK_EXCL:  [from, to)  → firstDay=from, lastDay=to-1 (後落とし)</li>
     *   <li>PERIOD_FRONT_EXCL: (from, to]  → firstDay=from+1, lastDay=to (前落とし)</li>
     * </ul>
     *
     * @return {@code LocalDate[]{adjustedFirstDay, adjustedLastDay}}
     */
    public static LocalDate[] adjustLoopBoundaries(LocalDate firstDay, LocalDate lastDay,
            String periodEndpointId) {
        if (periodEndpointId == null) periodEndpointId = DEFAULT_PERIOD_ENDPOINT;
        switch (periodEndpointId) {
            case "PERIOD_BACK_EXCL":
                return new LocalDate[]{firstDay, lastDay.minusDays(1)};
            case "PERIOD_FRONT_EXCL":
                return new LocalDate[]{firstDay.plusDays(1), lastDay};
            default: // PERIOD_BOTH
                return new LocalDate[]{firstDay, lastDay};
        }
    }

    // -----------------------------------------------------------------------
    // Due-date convention resolution
    // -----------------------------------------------------------------------

    /**
     * Resolve the due-date business-day adjustment convention enumId.
     * Resolution: FinancialProduct.dueDateConventionEnumId → SystemProperty/finance.properties
     * key {@code duedate.convention} → {@code DUEDATE_NONE}.
     */
    public static String resolveDueDateConvention(Delegator delegator, GenericValue agreement) {
        try {
            String productId = resolveProductId(delegator, agreement);
            if (UtilValidate.isNotEmpty(productId)) {
                GenericValue product = EntityQuery.use(delegator)
                        .from("FinancialProduct").where("productId", productId).cache().queryOne();
                if (product != null) {
                    String v = product.getString("dueDateConventionEnumId");
                    if (UtilValidate.isNotEmpty(v)) return v;
                }
            }
        } catch (GenericEntityException e) { /* fall through */ }
        String sys = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "duedate.convention", null, delegator);
        return UtilValidate.isNotEmpty(sys) ? sys : DEFAULT_DUEDATE_CONVENTION;
    }

    /** Resolve due-date convention when only productId is available. */
    public static String resolveDueDateConvention(Delegator delegator, String productId) {
        if (UtilValidate.isNotEmpty(productId)) {
            try {
                GenericValue product = EntityQuery.use(delegator)
                        .from("FinancialProduct").where("productId", productId).cache().queryOne();
                if (product != null) {
                    String v = product.getString("dueDateConventionEnumId");
                    if (UtilValidate.isNotEmpty(v)) return v;
                }
            } catch (GenericEntityException e) { /* fall through */ }
        }
        String sys = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "duedate.convention", null, delegator);
        return UtilValidate.isNotEmpty(sys) ? sys : DEFAULT_DUEDATE_CONVENTION;
    }

    // -----------------------------------------------------------------------
    // Currency rounding
    // -----------------------------------------------------------------------

    /**
     * Apply currency-appropriate rounding to an interest amount.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>finance.properties / SystemProperty key {@code currency.scale.<currencyUomId>}
     *       and {@code currency.rounding.<currencyUomId>}</li>
     *   <li>Fallback keys {@code currency.scale.default} / {@code currency.rounding.default}</li>
     *   <li>Hard-coded fallback: scale=2, HALF_UP</li>
     * </ol>
     *
     * @param value          the interest amount to round
     * @param currencyUomId  ISO currency code, e.g. "JPY" or "USD"
     * @param delegator      OFBiz delegator
     * @return rounded BigDecimal
     */
    public static BigDecimal roundInterest(BigDecimal value, String currencyUomId,
            Delegator delegator) {
        int scale = resolveScale(currencyUomId, delegator);
        RoundingMode mode = resolveRoundingMode(currencyUomId, delegator);
        return value.setScale(scale, mode);
    }

    /** Resolve decimal scale for the given currency. */
    public static int resolveScale(String currencyUomId, Delegator delegator) {
        String key = "currency.scale." + (UtilValidate.isNotEmpty(currencyUomId) ? currencyUomId : "");
        String val = EntityUtilProperties.getPropertyValue(FINANCE_RESOURCE, key, null, delegator);
        if (UtilValidate.isNotEmpty(val)) {
            try { return Integer.parseInt(val.trim()); } catch (NumberFormatException ignored) { }
        }
        String def = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "currency.scale.default", "2", delegator);
        try { return Integer.parseInt(def.trim()); } catch (NumberFormatException ignored) { }
        return 2;
    }

    /** Resolve RoundingMode for the given currency. */
    public static RoundingMode resolveRoundingMode(String currencyUomId, Delegator delegator) {
        String key = "currency.rounding." + (UtilValidate.isNotEmpty(currencyUomId) ? currencyUomId : "");
        String val = EntityUtilProperties.getPropertyValue(FINANCE_RESOURCE, key, null, delegator);
        RoundingMode mode = parseRoundingMode(val);
        if (mode != null) return mode;
        String def = EntityUtilProperties.getPropertyValue(
                FINANCE_RESOURCE, "currency.rounding.default", "HALF_UP", delegator);
        mode = parseRoundingMode(def);
        return mode != null ? mode : RoundingMode.HALF_UP;
    }

    // -----------------------------------------------------------------------
    // Daily interest computation (for Actual-based conventions)
    // -----------------------------------------------------------------------

    /**
     * Compute the daily interest for one day using the specified day-count convention.
     *
     * <p>For Actual/365 and Actual/360 conventions the denominator is fixed.
     * For Actual/Actual the denominator is the number of days in the calendar year
     * that contains {@code day}.
     * For 30/360 this method returns zero (use
     * {@link #computePeriodInterest30_360} instead).
     *
     * @param outstanding      outstanding principal on this day
     * @param annualRate       annual nominal interest rate (e.g. 0.12 for 12%)
     * @param day              the calendar date for which interest is calculated
     * @param dayCountConvId   convention enumId (e.g. "DAY_COUNT_ACT_365")
     * @return daily interest amount at intermediate precision (scale=12)
     */
    public static BigDecimal computeDailyInterest(BigDecimal outstanding, BigDecimal annualRate,
            LocalDate day, String dayCountConvId) {
        BigDecimal denominator = getDenominator(day, dayCountConvId);
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            // 30/360 or unknown: caller must use computePeriodInterest30_360
            return BigDecimal.ZERO;
        }
        return outstanding.multiply(annualRate)
                .divide(denominator, INTERMEDIATE_SCALE, INTERMEDIATE_ROUNDING);
    }

    /**
     * Returns true when the convention requires whole-period computation
     * (i.e. 30/360) rather than a daily loop.
     */
    public static boolean isPeriodBased(String dayCountConvId) {
        return "DAY_COUNT_30_360".equals(dayCountConvId);
    }

    // -----------------------------------------------------------------------
    // Period-based interest (30/360)
    // -----------------------------------------------------------------------

    /**
     * Compute interest for a period using the 30/360 Bond Basis convention.
     *
     * <p>Day-count formula (US 30/360):
     * <pre>
     *   D1 = min(d1, 30)
     *   D2 = if (D1 == 30 &amp;&amp; d2 == 31) then 30 else d2
     *   days30_360 = (y2-y1)*360 + (m2-m1)*30 + (D2-D1)
     *   interest   = principal * annualRate * days30_360 / 360
     * </pre>
     *
     * @param principal    outstanding principal for this sub-period
     * @param annualRate   annual nominal rate
     * @param from         period start date (inclusive)
     * @param to           period end date (inclusive, matching current both-ends convention)
     * @return interest amount at intermediate precision (scale=12)
     */
    public static BigDecimal computePeriodInterest30_360(BigDecimal principal,
            BigDecimal annualRate, LocalDate from, LocalDate to) {
        long days = days30_360(from, to);
        if (days <= 0) return BigDecimal.ZERO;
        return principal.multiply(annualRate)
                .multiply(new BigDecimal(days))
                .divide(D_360, INTERMEDIATE_SCALE, INTERMEDIATE_ROUNDING);
    }

    /**
     * 30/360 Bond Basis day count between two dates (US method).
     * Both {@code from} and {@code to} are inclusive (matching the current
     * both-ends convention used throughout the system).
     */
    public static long days30_360(LocalDate from, LocalDate to) {
        int y1 = from.getYear(), m1 = from.getMonthValue(), d1 = from.getDayOfMonth();
        int y2 = to.getYear(),   m2 = to.getMonthValue(),   d2 = to.getDayOfMonth();
        int D1 = Math.min(d1, 30);
        int D2 = (D1 == 30 && d2 == 31) ? 30 : d2;
        long result = (long)(y2 - y1) * 360 + (long)(m2 - m1) * 30 + (D2 - D1);
        // +1 for both-ends inclusive to stay consistent with the ACT loops
        return result + 1;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Return the annual denominator for the given day and convention.
     * Returns null for 30/360 (period-based – use computePeriodInterest30_360).
     */
    private static BigDecimal getDenominator(LocalDate day, String dayCountConvId) {
        if (dayCountConvId == null) dayCountConvId = DEFAULT_DAY_COUNT;
        switch (dayCountConvId) {
            case "DAY_COUNT_ACT_365": return D_365;
            case "DAY_COUNT_ACT_360": return D_360;
            case "DAY_COUNT_ACT_ACT": return new BigDecimal(Year.of(day.getYear()).length());
            case "DAY_COUNT_30_360":  return null; // period-based
            default:                 return D_365; // unknown → fallback ACT/365
        }
    }

    /**
     * Resolve the productId from a LoanAgreement GenericValue.
     * The agreement itself does not carry productId directly; it is reached
     * via the associated LoanApplication.
     */
    private static String resolveProductId(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        if (agreement == null) return null;
        String loanApplicationId = agreement.getString("loanApplicationId");
        if (UtilValidate.isEmpty(loanApplicationId)) return null;
        GenericValue app = EntityQuery.use(delegator)
                .from("LoanApplication")
                .where("loanApplicationId", loanApplicationId)
                .cache()
                .queryOne();
        return app != null ? app.getString("productId") : null;
    }

    private static RoundingMode parseRoundingMode(String val) {
        if (UtilValidate.isEmpty(val)) return null;
        switch (val.trim().toUpperCase()) {
            case "DOWN":    return RoundingMode.DOWN;
            case "HALF_UP": return RoundingMode.HALF_UP;
            case "UP":      return RoundingMode.UP;
            default:        return null;
        }
    }
}
