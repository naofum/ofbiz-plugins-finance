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

package org.apache.ofbiz.finance.lease;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.BusinessDayCalculator;
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Change of terms of a lease (条件変更, STEP L15): the lease term, the monthly
 * lease amount and / or the calculation interest rate, from the first unbilled
 * installment k. Like loan STEP 7, the confirmed lease payment schedule is rebuilt
 * as a new revision; installments 1..k-1 are copied unchanged.
 *
 * <p>The remaining schedule recovers the same principal: the principal outstanding
 * after installment k-1 down to the (unchanged) residual value. With payments in
 * advance the interest of installment k was earned in period k-1 at the old rate,
 * so it is kept and the new terms apply from period k:
 * <ul>
 * <li>arrears: base = remaining principal after k-1; LeaseCalculator in arrears;</li>
 * <li>advance: base = remaining principal after k-1 + old interest of k;
 *     LeaseCalculator in advance; installment k keeps its old interest.</li>
 * </ul>
 * A given monthly amount solves the calculation interest rate (rate factor over the
 * base); otherwise the payment is the annuity at the given (or current) rate.
 *
 * <p>Accounting: the principal does not change, so the change of the lease payments
 * equals the change of the interest equivalent. Finance lease: Dr lease investment /
 * Cr deferred lease profit (reversed for a decrease), no profit or loss; the release
 * follows the new schedule (the incremental recognition catches up). Operating
 * lease: no entry; the remaining depreciable amount is spread over the new remaining
 * months ({@link LeaseDepreciationServices#cumulativeFromBase}). Leases taxed in full
 * at the start (LEASE_TAX_AT_START) cannot change the total lease payments.
 */
public final class LeaseModificationServices {

    private static final String MODULE = LeaseModificationServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    private LeaseModificationServices() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    private static void check(Map<String, Object> result) throws GenericServiceException {
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Timestamp addMonths(Timestamp ts, int months) {
        return UtilDateTime.adjustTimestamp(ts, Calendar.MONTH, months);
    }

    private static Timestamp minusOneDay(Timestamp ts) {
        return UtilDateTime.adjustTimestamp(ts, Calendar.DAY_OF_MONTH, -1);
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    /** The planned change (computed, not stored). */
    static final class Plan {
        private String errorKey;
        private Map<String, Object> errorArgs = new HashMap<>();
        private int k;
        private int oldTerm;
        private int newTerm;
        private long fromRevision;
        private BigDecimal principalBase;
        private BigDecimal newPayment;
        private BigDecimal newRate;
        private BigDecimal oldRemaining = BigDecimal.ZERO;
        private BigDecimal newRemaining = BigDecimal.ZERO;
        private BigDecimal interestAdjustment = BigDecimal.ZERO;
        private BigDecimal newTotalTax = BigDecimal.ZERO;
        private Timestamp effectiveDate;
        private Timestamp newLeaseEnd;
        private final List<GenericValue> oldRows = new ArrayList<>();
        private final List<Map<String, Object>> newRows = new ArrayList<>();

        boolean failed() {
            return errorKey != null;
        }

        Plan fail(String key, Object... args) {
            this.errorKey = key;
            for (int i = 0; i + 1 < args.length; i += 2) {
                errorArgs.put((String) args[i], args[i + 1]);
            }
            return this;
        }
    }

    /** Compute the change of an ACTIVE agreement (no side effects). */
    static Plan plan(Delegator delegator, GenericValue agreement, Long newTermIn, BigDecimal newAmountIn, BigDecimal newRateIn)
            throws GenericEntityException {
        Plan p = new Plan();
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
            return p.fail("FinanceLeaseAccountingRequiresActive", "leaseAgreementId", leaseAgreementId);
        }
        if (!LeaseReLeaseServices.reLeases(delegator, leaseAgreementId).isEmpty()) {
            return p.fail("FinanceLeaseModificationReLease", "leaseAgreementId", leaseAgreementId);
        }
        if (newTermIn == null && newAmountIn == null && newRateIn == null) {
            return p.fail("FinanceLeaseModificationNoChange", "leaseAgreementId", leaseAgreementId);
        }
        p.fromRevision = LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId);
        p.oldRows.addAll(EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", p.fromRevision)
                .orderBy("installmentNumber").queryList());
        p.oldTerm = agreement.getLong("termMonths").intValue();
        long billedMax = 0;
        for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            billedMax = Math.max(billedMax, link.getLong("installmentNumber"));
        }
        p.k = (int) billedMax + 1;
        if (p.k > p.oldTerm) {
            return p.fail("FinanceLeaseModificationNothingUnbilled", "leaseAgreementId", leaseAgreementId);
        }
        p.newTerm = newTermIn != null ? newTermIn.intValue() : p.oldTerm;
        if (p.newTerm < p.k) {
            return p.fail("FinanceLeaseModificationTermInvalid", "newTermMonths", p.newTerm, "minTermMonths", p.k);
        }
        // The new term stays within the product's term range (as for a quote).
        GenericValue product = EntityQuery.use(delegator).from("FinancialProduct")
                .where("productId", agreement.getString("productId")).cache().queryOne();
        Long min = product == null ? null : product.getLong("minTermMonths");
        Long max = product == null ? null : product.getLong("maxTermMonths");
        if (p.newTerm != p.oldTerm && ((min != null && p.newTerm < min) || (max != null && p.newTerm > max))) {
            return p.fail("FinanceLeaseTermOutOfRange", "termMonths", p.newTerm, "min", min == null ? "" : min,
                    "max", max == null ? "" : max);
        }
        String currency = agreement.getString("currencyUomId");
        int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
        RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
        boolean advance = LeaseProductHelper.TIMING_ADVANCE.equals(agreement.getString("paymentTimingEnumId"));
        BigDecimal residual = nz(agreement.getBigDecimal("residualValue"));
        GenericValue rowK = p.oldRows.get(p.k - 1);
        BigDecimal remainingBefore = p.k == 1 ? nz(agreement.getBigDecimal("acquisitionCost"))
                : nz(p.oldRows.get(p.k - 2).getBigDecimal("remainingPrincipal"));
        BigDecimal keptInterest = advance && p.k > 1 ? nz(rowK.getBigDecimal("interestPortion")) : BigDecimal.ZERO;
        BigDecimal base = remainingBefore.add(keptInterest);
        p.principalBase = remainingBefore.subtract(residual);
        int m = p.newTerm - p.k + 1;

        LeaseCalculator.Input input = new LeaseCalculator.Input().acquisitionCost(base).residualValue(residual).termMonths(m)
                .advance(advance).currencyRounding(scale, mode);
        if (newAmountIn != null) {
            if (newAmountIn.signum() <= 0) {
                return p.fail("FinanceLeaseModificationAmountInvalid", "amount", newAmountIn);
            }
            input.monthlyRateFactor(newAmountIn.divide(base, 16, RoundingMode.HALF_UP));
        } else {
            BigDecimal rate = newRateIn != null ? newRateIn : nz(agreement.getBigDecimal("calcInterestRate"));
            if (rate.signum() < 0) {
                return p.fail("FinanceLeaseModificationAmountInvalid", "amount", rate);
            }
            input.annualCalcRate(rate);
        }
        LeaseCalculator.Result calc;
        try {
            calc = LeaseCalculator.calculate(input);
        } catch (IllegalArgumentException e) {
            return p.fail("FinanceLeaseCalcInvalid", "reason", e.getMessage());
        }
        p.newPayment = calc.getMonthlyLeaseAmount();
        p.newRate = calc.getCalcInterestRate();

        // Dates as at the acceptance (STEP L3).
        Timestamp start = agreement.getTimestamp("leaseStartDate");
        Timestamp firstPayment = advance ? start : addMonths(start, 1);
        String convention = FinanceInterestCalculator.resolveDueDateConvention(delegator, agreement.getString("productId"));
        p.effectiveDate = addMonths(start, p.k - 1);
        p.newLeaseEnd = minusOneDay(addMonths(start, p.newTerm));

        // Tax of the new installments at the rate fixed at the start.
        boolean lumpSum = LeaseTaxHelper.TIMING_AT_START.equals(agreement.getString("taxTimingEnumId"));
        BigDecimal taxRate = nz(agreement.getBigDecimal("taxRate"));
        List<BigDecimal> amounts = new ArrayList<>();
        for (LeaseCalculator.Row r : calc.getRows()) {
            amounts.add(r.getLeaseAmount());
            p.newRemaining = p.newRemaining.add(r.getLeaseAmount());
        }
        BigDecimal oldRemainingTax = BigDecimal.ZERO;
        BigDecimal oldRemainingInterest = BigDecimal.ZERO;
        for (int i = p.k - 1; i < p.oldRows.size(); i++) {
            p.oldRemaining = p.oldRemaining.add(nz(p.oldRows.get(i).getBigDecimal("leaseAmount")));
            oldRemainingTax = oldRemainingTax.add(nz(p.oldRows.get(i).getBigDecimal("taxAmount")));
            oldRemainingInterest = oldRemainingInterest.add(nz(p.oldRows.get(i).getBigDecimal("interestPortion")));
        }
        if (lumpSum && p.newRemaining.compareTo(p.oldRemaining) != 0) {
            return p.fail("FinanceLeaseModificationLumpSumTax", "leaseAgreementId", leaseAgreementId);
        }
        List<BigDecimal> taxes = LeaseTaxHelper.allocate(amounts, taxRate, lumpSum, scale, mode);
        if (lumpSum) {
            // The tax was booked in full at the start: keep the remaining total exactly.
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal t : taxes) {
                sum = sum.add(t);
            }
            int last = taxes.size() - 1;
            taxes.set(last, taxes.get(last).add(oldRemainingTax.subtract(sum)));
        }
        BigDecimal newInterest = BigDecimal.ZERO;
        for (int i = 0; i < calc.getRows().size(); i++) {
            LeaseCalculator.Row r = calc.getRows().get(i);
            int number = p.k + i;
            BigDecimal interest = r.getInterestPortion();
            BigDecimal principal = r.getPrincipalPortion();
            if (i == 0 && keptInterest.signum() != 0) {
                interest = keptInterest;
                principal = r.getLeaseAmount().subtract(keptInterest);
            }
            newInterest = newInterest.add(interest);
            Map<String, Object> row = new HashMap<>();
            row.put("installmentNumber", (long) number);
            row.put("dueDate", BusinessDayCalculator.adjust(addMonths(firstPayment, number - 1), convention));
            row.put("periodFromDate", addMonths(start, number - 1));
            row.put("periodThruDate", minusOneDay(addMonths(start, number)));
            row.put("leaseAmount", r.getLeaseAmount());
            row.put("principalPortion", principal);
            row.put("interestPortion", interest);
            row.put("taxAmount", taxes.get(i));
            row.put("remainingPrincipal", r.getRemainingPrincipal());
            p.newRows.add(row);
        }
        p.interestAdjustment = newInterest.subtract(oldRemainingInterest);
        for (int i = 0; i < p.k - 1; i++) {
            p.newTotalTax = p.newTotalTax.add(nz(p.oldRows.get(i).getBigDecimal("taxAmount")));
        }
        for (BigDecimal t : taxes) {
            p.newTotalTax = p.newTotalTax.add(t);
        }
        if (p.newTerm == p.oldTerm && p.newPayment.compareTo(nz(rowK.getBigDecimal("leaseAmount"))) == 0
                && p.interestAdjustment.signum() == 0) {
            return p.fail("FinanceLeaseModificationNoChange", "leaseAgreementId", leaseAgreementId);
        }
        return p;
    }

    private static Map<String, Object> planResult(Plan p) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("effectiveInstallmentNumber", (long) p.k);
        result.put("effectiveDate", p.effectiveDate);
        result.put("newTermMonths", (long) p.newTerm);
        result.put("newMonthlyLeaseAmount", p.newPayment);
        result.put("newCalcInterestRate", p.newRate);
        result.put("newLeaseEndDate", p.newLeaseEnd);
        result.put("oldRemainingLeaseAmount", p.oldRemaining);
        result.put("newRemainingLeaseAmount", p.newRemaining);
        result.put("interestAdjustment", p.interestAdjustment);
        return result;
    }

    /** Service: the change of terms if applied now (no change). */
    public static Map<String, Object> calculateLeaseModification(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            Plan p = plan(delegator, agreement, (Long) context.get("newTermMonths"),
                    (BigDecimal) context.get("newMonthlyLeaseAmount"), (BigDecimal) context.get("newCalcInterestRate"));
            return p.failed() ? error(p.errorKey, p.errorArgs, locale) : planResult(p);
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Service: apply the change of terms (new schedule revision, agreement, GL, history). */
    public static Map<String, Object> createLeaseModification(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            agreement = findAgreement(delegator, leaseAgreementId);
            Plan p = plan(delegator, agreement, (Long) context.get("newTermMonths"),
                    (BigDecimal) context.get("newMonthlyLeaseAmount"), (BigDecimal) context.get("newCalcInterestRate"));
            if (p.failed()) {
                return error(p.errorKey, p.errorArgs, locale);
            }
            String currency = agreement.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
            boolean operating = LeaseProductHelper.isOperating(agreement);

            // Operating lease: depreciation through the day before the effective period (the base of the new spread).
            Long depreciationBase = null;
            if (operating) {
                Timestamp today = UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp());
                Timestamp until = minusOneDay(p.effectiveDate);
                check(LeaseDepreciationServices.depreciateLeaseAssets(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "depreciationDate", until.before(today) ? until : today, "userLogin", userLogin, "locale", locale)));
                GenericValue last = EntityQuery.use(delegator).from("LeaseAssetDepreciation")
                        .where("leaseAgreementId", leaseAgreementId).orderBy("-periodNumber").cache(false).queryFirst();
                depreciationBase = last == null ? 0L : last.getLong("periodNumber");
            }

            // New schedule revision: 1..k-1 copied, k..n' recalculated.
            long toRevision = p.fromRevision + 1;
            for (int i = 0; i < p.k - 1; i++) {
                GenericValue line = delegator.makeValue("LeaseAgreementPaymentSchedule", p.oldRows.get(i).getAllFields());
                line.set("scheduleRevisionNumber", toRevision);
                line.create();
            }
            String quoteId = p.oldRows.get(0).getString("leaseQuoteId");
            for (Map<String, Object> row : p.newRows) {
                GenericValue line = delegator.makeValue("LeaseAgreementPaymentSchedule", row);
                line.set("leaseAgreementId", leaseAgreementId);
                line.set("scheduleRevisionNumber", toRevision);
                line.set("leaseQuoteId", quoteId);
                line.create();
            }

            // Components: unchanged installments copied; new installments at the definition's monthly amount
            // (the old amount where the installment existed), tax at the rate fixed at the start.
            BigDecimal taxRate = nz(agreement.getBigDecimal("taxRate"));
            for (GenericValue def : EntityQuery.use(delegator).from("LeaseAgreementComponent")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                String seq = def.getString("componentSeqId");
                Map<Long, GenericValue> old = new LinkedHashMap<>();
                for (GenericValue c : EntityQuery.use(delegator).from("LeaseAgrPaySchedComp").where("leaseAgreementId",
                        leaseAgreementId, "scheduleRevisionNumber", p.fromRevision, "componentSeqId", seq).queryList()) {
                    old.put(c.getLong("installmentNumber"), c);
                }
                BigDecimal total = BigDecimal.ZERO;
                BigDecimal totalTax = BigDecimal.ZERO;
                for (long n = 1; n <= p.newTerm; n++) {
                    GenericValue src = old.get(n);
                    BigDecimal amount = src != null ? nz(src.getBigDecimal("amount")) : nz(def.getBigDecimal("monthlyAmount"));
                    BigDecimal tax = src != null && n < p.k ? nz(src.getBigDecimal("taxAmount"))
                            : LeaseComponentCalculator.isTaxable(def.getString("taxClassEnumId"))
                                    ? amount.multiply(taxRate).setScale(scale, mode) : BigDecimal.ZERO;
                    GenericValue c = delegator.makeValue("LeaseAgrPaySchedComp");
                    c.set("leaseAgreementId", leaseAgreementId);
                    c.set("scheduleRevisionNumber", toRevision);
                    c.set("installmentNumber", n);
                    c.set("componentSeqId", seq);
                    c.set("componentTypeEnumId", def.get("componentTypeEnumId"));
                    c.set("amount", amount);
                    c.set("taxAmount", tax);
                    c.set("taxClassEnumId", def.get("taxClassEnumId"));
                    c.set("revenueTreatmentEnumId", def.get("revenueTreatmentEnumId"));
                    c.create();
                    total = total.add(amount);
                    totalTax = totalTax.add(tax);
                }
                def.set("totalAmount", total);
                def.set("totalTaxAmount", totalTax);
                def.store();
            }

            // Finance lease: Dr lease investment / Cr deferred lease profit (reversed for a decrease).
            String acctgTransId = null;
            LeaseRevenueStrategy strategy = LeaseRevenueStrategy.REGISTRY.get(agreement.getString("revenueMethodEnumId"));
            if (strategy != null && strategy.defersProfit() && p.interestAdjustment.signum() != 0) {
                boolean increase = p.interestAdjustment.signum() > 0;
                BigDecimal abs = p.interestAdjustment.abs();
                List<FinanceGlHelper.Line> lines = new ArrayList<>();
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.assetAccountType(agreement), increase, abs));
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_DEFERRED_PROFIT, !increase, abs));
                acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                        "Lease change of terms " + leaseAgreementId, UtilDateTime.nowTimestamp(),
                        agreement.getString("organizationPartyId"), agreement.getString("lesseePartyId"), currency, lines, userLogin);
            }

            // History, then the agreement.
            String modificationId = delegator.getNextSeqId("LeaseModification");
            GenericValue mod = delegator.makeValue("LeaseModification");
            mod.set("leaseModificationId", modificationId);
            mod.set("leaseAgreementId", leaseAgreementId);
            mod.set("modificationDate", UtilDateTime.nowTimestamp());
            mod.set("effectiveInstallmentNumber", (long) p.k);
            mod.set("effectiveDate", p.effectiveDate);
            mod.set("fromRevisionNumber", p.fromRevision);
            mod.set("toRevisionNumber", toRevision);
            mod.set("oldTermMonths", (long) p.oldTerm);
            mod.set("newTermMonths", (long) p.newTerm);
            mod.set("oldMonthlyLeaseAmount", p.oldRows.get(p.k - 1).getBigDecimal("leaseAmount"));
            mod.set("newMonthlyLeaseAmount", p.newPayment);
            mod.set("oldCalcInterestRate", agreement.getBigDecimal("calcInterestRate"));
            mod.set("newCalcInterestRate", p.newRate);
            mod.set("oldLeaseEndDate", agreement.getTimestamp("leaseEndDate"));
            mod.set("newLeaseEndDate", p.newLeaseEnd);
            mod.set("principalBase", p.principalBase);
            mod.set("oldRemainingLeaseAmount", p.oldRemaining);
            mod.set("newRemainingLeaseAmount", p.newRemaining);
            mod.set("interestAdjustment", p.interestAdjustment);
            mod.set("acctgTransId", acctgTransId);
            mod.set("depreciationBaseMonth", depreciationBase);
            mod.set("reason", context.get("reason"));
            mod.set("currencyUomId", currency);
            mod.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
            mod.set("createdDate", UtilDateTime.nowTimestamp());
            mod.create();

            Timestamp oldEnd = agreement.getTimestamp("leaseEndDate");
            BigDecimal delta = p.newRemaining.subtract(p.oldRemaining);
            agreement.set("termMonths", (long) p.newTerm);
            agreement.set("leaseEndDate", p.newLeaseEnd);
            agreement.set("monthlyLeaseAmount", p.newPayment);
            agreement.set("monthlyRateFactor", p.newPayment.divide(nz(agreement.getBigDecimal("acquisitionCost")), 6,
                    RoundingMode.HALF_UP));
            agreement.set("calcInterestRate", p.newRate);
            agreement.set("totalLeaseAmount", nz(agreement.getBigDecimal("totalLeaseAmount")).add(delta));
            agreement.set("totalInterestAmount", nz(agreement.getBigDecimal("totalInterestAmount")).add(p.interestAdjustment));
            agreement.set("totalTaxAmount", p.newTotalTax);
            agreement.store();

            // The expiry notice task of the old end date no longer applies.
            if (!oldEnd.equals(p.newLeaseEnd)) {
                FinanceWorkEffortHelper.cancelAndUnlinkTask(delegator, dispatcher, agreement.getString("financeContractId"),
                        FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE, oldEnd.toLocalDateTime().toLocalDate().toString(), userLogin);
            }
            Map<String, Object> result = planResult(p);
            result.put("leaseModificationId", modificationId);
            result.put("scheduleRevisionNumber", toRevision);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
