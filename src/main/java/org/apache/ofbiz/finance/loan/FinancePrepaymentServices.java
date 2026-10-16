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

package org.apache.ofbiz.finance.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.BusinessDayCalculator;
import org.apache.ofbiz.finance.common.FinanceBusinessCalendar;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Finance STEP 7 services: loan prepayment (full or partial) and loan
 * modification (interest rate / term). Both re-amortize the remaining schedule
 * as a new revision, leaving the original schedule immutable.
 */
public final class FinancePrepaymentServices {

    private static final String MODULE = FinancePrepaymentServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private FinancePrepaymentServices() {
    }

    /** Full or partial prepayment with a settlement Payment and fee. */
    public static Map<String, Object> createLoanPrepayment(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String loanAgreementId = (String) context.get("loanAgreementId");
        String prepaymentTypeEnumId = (String) context.get("prepaymentTypeEnumId");
        BigDecimal principalAmount = (BigDecimal) context.get("principalAmount");
        Timestamp prepaymentDate = (Timestamp) context.get("prepaymentDate");
        if (prepaymentDate == null) {
            prepaymentDate = UtilDateTime.nowTimestamp();
        }

        try {
            GenericValue agreement = lockAgreement(delegator, loanAgreementId);
            if (agreement == null) {
                return recordNotFound("LoanAgreement", loanAgreementId, locale);
            }
            if (!"LOANAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinanceAgreementNotActive",
                        UtilMisc.toMap("loanAgreementId", loanAgreementId), locale));
            }

            BigDecimal outstanding = currentOutstandingPrincipal(delegator, agreement);
            BigDecimal prepaidPrincipal;
            if ("PREPAY_FULL".equals(prepaymentTypeEnumId)) {
                prepaidPrincipal = outstanding;
            } else {
                prepaidPrincipal = principalAmount;
            }
            if (prepaidPrincipal == null || prepaidPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinancePaymentAmountInvalid", locale));
            }
            if (prepaidPrincipal.compareTo(outstanding) > 0) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinancePrepaymentExceedsBalance",
                        UtilMisc.toMap("loanAgreementId", loanAgreementId), locale));
            }

            GenericValue product = resolveProduct(delegator, agreement);
            BigDecimal fee = computeFee(product, prepaidPrincipal);
            BigDecimal totalPayment = prepaidPrincipal.add(fee);

            String organizationPartyId = getOrganizationPartyId(agreement);
            String currencyUomId = agreement.getString("currencyUomId");
            String borrowerPartyId = agreement.getString("borrowerPartyId");
            String paymentMethodTypeId = (String) context.get("paymentMethodTypeId");
            if (UtilValidate.isEmpty(paymentMethodTypeId)) {
                paymentMethodTypeId = "EFT_ACCOUNT";
            }

            Map<String, Object> payInput = new HashMap<>();
            payInput.put("paymentTypeId", "CUSTOMER_PAYMENT");
            payInput.put("partyIdFrom", borrowerPartyId);
            payInput.put("partyIdTo", organizationPartyId);
            payInput.put("statusId", "PMNT_RECEIVED");
            payInput.put("paymentMethodTypeId", paymentMethodTypeId);
            payInput.put("amount", totalPayment);
            payInput.put("currencyUomId", currencyUomId);
            payInput.put("effectiveDate", prepaymentDate);
            payInput.put("userLogin", userLogin);
            Map<String, Object> payResult = dispatcher.runSync("createPayment", payInput);
            if (ServiceUtil.isError(payResult)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(payResult));
            }
            String paymentId = (String) payResult.get("paymentId");

            String loanPrepaymentId = delegator.getNextSeqId("LoanPrepayment");
            GenericValue prepayment = delegator.makeValue("LoanPrepayment");
            prepayment.set("loanPrepaymentId", loanPrepaymentId);
            prepayment.set("loanAgreementId", loanAgreementId);
            prepayment.set("prepaymentTypeEnumId", prepaymentTypeEnumId);
            prepayment.set("prepaymentDate", prepaymentDate);
            prepayment.set("principalAmount", prepaidPrincipal);
            prepayment.set("penaltyAmount", fee);
            prepayment.set("paymentId", paymentId);
            prepayment.set("comments", context.get("comments"));
            prepayment.create();

            long fromRevision = FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId);
            prepayment.set("fromRevisionNumber", fromRevision);
            if (fee.compareTo(BigDecimal.ZERO) > 0) {
                // STEP L15a: keep the fee transaction for a reversal.
                prepayment.set("feeAcctgTransId", postFeeIncome(delegator, dispatcher, agreement, fee, prepaymentDate,
                        organizationPartyId, userLogin));
            }

            if ("PREPAY_FULL".equals(prepaymentTypeEnumId)) {
                Map<String, Object> closeResult = dispatcher.runSync("setLoanAgreementStatus",
                        UtilMisc.toMap("loanAgreementId", loanAgreementId,
                                "statusId", "LOANAGR_CLOSED", "userLogin", userLogin));
                if (ServiceUtil.isError(closeResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(closeResult));
                }
            } else {
                regenerateAfterPrepayment(delegator, agreement, outstanding.subtract(prepaidPrincipal),
                        prepaymentTypeEnumId, prepaymentDate);
                prepayment.set("toRevisionNumber", FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId));
            }
            prepayment.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("loanPrepaymentId", loanPrepaymentId);
            result.put("paymentId", paymentId);
            result.put("penaltyAmount", fee);
            result.put("remainingPrincipal", outstanding.subtract(prepaidPrincipal));
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Change the interest rate and/or term, re-amortizing the remaining schedule. */
    public static Map<String, Object> createLoanModification(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String loanAgreementId = (String) context.get("loanAgreementId");
        BigDecimal newAnnualInterestRate = (BigDecimal) context.get("newAnnualInterestRate");
        Long newTermMonths = (Long) context.get("newTermMonths");
        Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
        if (effectiveDate == null) {
            effectiveDate = UtilDateTime.nowTimestamp();
        }

        try {
            GenericValue agreement = lockAgreement(delegator, loanAgreementId);
            if (agreement == null) {
                return recordNotFound("LoanAgreement", loanAgreementId, locale);
            }
            if (!"LOANAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinanceAgreementNotActive",
                        UtilMisc.toMap("loanAgreementId", loanAgreementId), locale));
            }

            BigDecimal oldRate = agreement.getBigDecimal("annualInterestRate");
            Long oldTerm = agreement.getLong("termMonths");
            BigDecimal rate = newAnnualInterestRate != null ? newAnnualInterestRate : oldRate;
            Long term = newTermMonths != null ? newTermMonths : oldTerm;

            boolean rateChanged = newAnnualInterestRate != null
                    && newAnnualInterestRate.compareTo(oldRate) != 0;
            boolean termChanged = newTermMonths != null && !newTermMonths.equals(oldTerm);
            if (!rateChanged && !termChanged) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinanceModificationNoChange", locale));
            }

            String modificationTypeEnumId = rateChanged ? "MOD_RATE" : "MOD_TERM";

            GenericValue modification = delegator.makeValue("LoanModification");
            modification.set("loanModificationId", delegator.getNextSeqId("LoanModification"));
            modification.set("loanAgreementId", loanAgreementId);
            modification.set("modificationDate", UtilDateTime.nowTimestamp());
            modification.set("effectiveDate", effectiveDate);
            modification.set("modificationTypeEnumId", modificationTypeEnumId);
            modification.set("oldAnnualInterestRate", oldRate);
            modification.set("newAnnualInterestRate", rate);
            modification.set("oldTermMonths", oldTerm);
            modification.set("newTermMonths", term);
            modification.set("reason", context.get("reason"));
            modification.set("comments", context.get("comments"));
            modification.set("fromRevisionNumber", FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId));
            modification.create();

            agreement.set("annualInterestRate", rate);
            agreement.set("termMonths", term);
            agreement.store();

            regenerateAfterModification(delegator, agreement, rate, term, effectiveDate);
            // STEP L15a: the revision created (for a reversal).
            modification.set("toRevisionNumber", FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId));
            modification.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("loanModificationId", modification.getString("loanModificationId"));
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =====================================================================
    // Re-amortization
    // =====================================================================

    private static void regenerateAfterPrepayment(Delegator delegator, GenericValue agreement,
            BigDecimal remainingPrincipal, String prepaymentTypeEnumId, Timestamp prepaymentDate)
            throws GenericEntityException {
        String methodEnumId = resolveRepaymentMethod(delegator, agreement);
        BigDecimal annualRate = agreement.getBigDecimal("annualInterestRate");
        long revision = FinanceAccountingServices.currentScheduleRevision(delegator,
                agreement.getString("loanAgreementId")) + 1L;

        List<GenericValue> future = futureInstallments(delegator, agreement, prepaymentDate);
        Timestamp firstDueDate = future.isEmpty() ? UtilDateTime.adjustTimestamp(prepaymentDate,
                Calendar.MONTH, 1) : future.get(0).getTimestamp("dueDate");

        int termMonths;
        if ("PREPAY_PART_TERM".equals(prepaymentTypeEnumId)) {
            termMonths = computeTermReductionMonths(delegator, agreement, remainingPrincipal,
                    annualRate, methodEnumId);
        } else {
            termMonths = Math.max(future.size(), 1);
        }
        persistSchedule(delegator, agreement, remainingPrincipal, annualRate, termMonths,
                firstDueDate, methodEnumId, revision, prepaymentDate);
    }

    private static void regenerateAfterModification(Delegator delegator, GenericValue agreement,
            BigDecimal newRate, Long newTermMonths, Timestamp effectiveDate) throws GenericEntityException {
        String methodEnumId = resolveRepaymentMethod(delegator, agreement);
        BigDecimal remainingPrincipal = currentOutstandingPrincipal(delegator, agreement);
        long revision = FinanceAccountingServices.currentScheduleRevision(delegator,
                agreement.getString("loanAgreementId")) + 1L;

        List<GenericValue> future = futureInstallments(delegator, agreement, effectiveDate);
        Timestamp firstDueDate = future.isEmpty() ? UtilDateTime.adjustTimestamp(effectiveDate,
                Calendar.MONTH, 1) : future.get(0).getTimestamp("dueDate");

        long elapsed = elapsedInstallments(delegator, agreement, effectiveDate);
        int termMonths = (int) Math.max(newTermMonths - elapsed, 1L);
        persistSchedule(delegator, agreement, remainingPrincipal, newRate, termMonths,
                firstDueDate, methodEnumId, revision, effectiveDate);
    }

    private static int computeTermReductionMonths(Delegator delegator, GenericValue agreement,
            BigDecimal remainingPrincipal, BigDecimal annualRate, String methodEnumId)
            throws GenericEntityException {
        long revision = FinanceAccountingServices.currentScheduleRevision(delegator,
                agreement.getString("loanAgreementId"));
        GenericValue first = EntityQuery.use(delegator).from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", agreement.getString("loanAgreementId"),
                        "scheduleRevisionNumber", revision)
                .orderBy("installmentNumber").queryFirst();
        if (first == null) {
            return 1;
        }
        if ("LOAN_RPM_EQUAL_PRIN".equals(methodEnumId)) {
            BigDecimal principalPortion = defaultZero(first.getBigDecimal("principalAmount"));
            if (principalPortion.compareTo(BigDecimal.ZERO) <= 0) {
                return 1;
            }
            return remainingPrincipal.divide(principalPortion, 0, RoundingMode.UP).max(BigDecimal.ONE).intValue();
        }
        BigDecimal payment = defaultZero(first.getBigDecimal("paymentAmount"));
        return solveAnnuityTerm(remainingPrincipal, annualRate, payment);
    }

    private static int solveAnnuityTerm(BigDecimal principal, BigDecimal annualRate, BigDecimal payment) {
        BigDecimal monthlyRate = annualRate.divide(TWELVE, 10, ROUNDING);
        if (payment == null || payment.compareTo(BigDecimal.ZERO) <= 0) {
            return 1;
        }
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(payment, 0, RoundingMode.UP).max(BigDecimal.ONE).intValue();
        }
        BigDecimal ratio = principal.multiply(monthlyRate).divide(payment, 10, ROUNDING);
        BigDecimal oneMinus = BigDecimal.ONE.subtract(ratio);
        if (oneMinus.compareTo(BigDecimal.ZERO) <= 0) {
            return 1;
        }
        double n = -Math.log(oneMinus.doubleValue())
                / Math.log(BigDecimal.ONE.add(monthlyRate).doubleValue());
        return Math.max((int) Math.ceil(n), 1);
    }

    private static void persistSchedule(Delegator delegator, GenericValue agreement,
            BigDecimal principal, BigDecimal annualRate, int termMonths, Timestamp firstDueDate,
            String methodEnumId, long revision, Timestamp eventDate) throws GenericEntityException {
        List<ScheduleRow> rows = amortize(principal, annualRate, termMonths, methodEnumId);
        String currencyUomId = agreement.getString("currencyUomId");
        String dueDateConvId = FinanceInterestCalculator.resolveDueDateConvention(delegator, agreement);
        // STEP L15b: holidays of the product's business-day calendars.
        FinanceBusinessCalendar businessCalendar = FinanceInterestCalculator.resolveBusinessCalendar(delegator, agreement);
        // interestFromDate[1] = eventDate + 1 day (the day after the prepayment / modification date)
        // interestFromDate[n] = interestThruDate[n-1] + 1 day
        Timestamp prevThru = null;
        for (ScheduleRow row : rows) {
            Timestamp calendarDue = UtilDateTime.adjustTimestamp(firstDueDate, Calendar.MONTH,
                    row.installmentNumber - 1);
            // interestThruDate = calendar due date (before business-day adjustment)
            Timestamp interestThru = calendarDue;
            // Apply business-day adjustment to dueDate.
            Timestamp dueDate = BusinessDayCalculator.adjust(calendarDue, dueDateConvId, businessCalendar);
            Timestamp interestFrom;
            if (prevThru == null) {
                // First installment: day after the event date
                interestFrom = new Timestamp(eventDate.getTime() + 86_400_000L);
            } else {
                interestFrom = new Timestamp(prevThru.getTime() + 86_400_000L);
            }
            prevThru = interestThru;
            // Apply currency-appropriate rounding to interest and derived amounts.
            BigDecimal interest = FinanceInterestCalculator.roundInterest(
                    row.interestAmount, currencyUomId, delegator);
            BigDecimal payment = row.principalAmount.add(interest)
                    .setScale(FinanceInterestCalculator.resolveScale(currencyUomId, delegator),
                              FinanceInterestCalculator.resolveRoundingMode(currencyUomId, delegator));
            GenericValue value = delegator.makeValue("LoanAgreementRepaymentSchedule");
            value.set("loanAgreementId", agreement.getString("loanAgreementId"));
            value.set("scheduleRevisionNumber", revision);
            value.set("installmentNumber", (long) row.installmentNumber);
            value.set("loanQuoteId", agreement.getString("loanQuoteId"));
            value.set("dueDate", dueDate);
            value.set("interestFromDate", interestFrom);
            value.set("interestThruDate", interestThru);
            value.set("paymentAmount", payment);
            value.set("principalAmount", row.principalAmount);
            value.set("interestAmount", interest);
            value.set("remainingBalance", row.remainingBalance);
            value.create();
        }
    }

    private static List<ScheduleRow> amortize(BigDecimal principal, BigDecimal annualRate,
            int termMonths, String methodEnumId) {
        BigDecimal monthlyRate = annualRate.divide(TWELVE, 10, ROUNDING);
        List<ScheduleRow> rows = new ArrayList<>();
        BigDecimal balance = principal;
        // Use high intermediate precision; final rounding is applied by persistSchedule.
        if ("LOAN_RPM_EQUAL_PRIN".equals(methodEnumId)) {
            BigDecimal principalPortion = principal.divide(new BigDecimal(termMonths), SCALE, ROUNDING);
            for (int i = 1; i <= termMonths; i++) {
                BigDecimal interest = balance.multiply(monthlyRate).setScale(10, ROUNDING);
                BigDecimal thisPrincipal = (i == termMonths) ? balance : principalPortion;
                BigDecimal payment = thisPrincipal.add(interest);
                balance = balance.subtract(thisPrincipal).setScale(SCALE, ROUNDING);
                rows.add(new ScheduleRow(i, payment, thisPrincipal, interest, balance));
            }
        } else {
            BigDecimal monthlyPayment;
            if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
                monthlyPayment = principal.divide(new BigDecimal(termMonths), SCALE, ROUNDING);
            } else {
                BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
                BigDecimal pow = onePlusR.pow(termMonths);
                monthlyPayment = principal.multiply(monthlyRate).multiply(pow)
                        .divide(pow.subtract(BigDecimal.ONE), SCALE, ROUNDING);
            }
            for (int i = 1; i <= termMonths; i++) {
                BigDecimal interest = balance.multiply(monthlyRate).setScale(10, ROUNDING);
                BigDecimal thisPrincipal;
                BigDecimal payment;
                if (i == termMonths) {
                    thisPrincipal = balance;
                    payment = balance.add(interest);
                } else {
                    thisPrincipal = monthlyPayment.subtract(interest).setScale(SCALE, ROUNDING);
                    payment = monthlyPayment;
                }
                balance = balance.subtract(thisPrincipal).setScale(SCALE, ROUNDING);
                rows.add(new ScheduleRow(i, payment, thisPrincipal, interest, balance));
            }
        }
        return rows;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static List<GenericValue> futureInstallments(Delegator delegator, GenericValue agreement,
            Timestamp afterDate) throws GenericEntityException {
        long revision = FinanceAccountingServices.currentScheduleRevision(delegator,
                agreement.getString("loanAgreementId"));
        List<GenericValue> future = new ArrayList<>();
        for (GenericValue row : EntityQuery.use(delegator).from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", agreement.getString("loanAgreementId"),
                        "scheduleRevisionNumber", revision)
                .orderBy("installmentNumber").queryList()) {
            Timestamp dueDate = row.getTimestamp("dueDate");
            if (dueDate != null && dueDate.after(afterDate)) {
                future.add(row);
            }
        }
        return future;
    }

    private static long elapsedInstallments(Delegator delegator, GenericValue agreement,
            Timestamp effectiveDate) throws GenericEntityException {
        long revision = FinanceAccountingServices.currentScheduleRevision(delegator,
                agreement.getString("loanAgreementId"));
        long elapsed = 0;
        for (GenericValue row : EntityQuery.use(delegator).from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", agreement.getString("loanAgreementId"),
                        "scheduleRevisionNumber", revision).queryList()) {
            Timestamp dueDate = row.getTimestamp("dueDate");
            if (dueDate != null && !dueDate.after(effectiveDate)) {
                elapsed++;
            }
        }
        return elapsed;
    }

    private static BigDecimal currentOutstandingPrincipal(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        BigDecimal outstanding = defaultZero(agreement.getBigDecimal("principalAmount"));
        for (GenericValue link : EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                .where("loanAgreementId", agreement.getString("loanAgreementId")).queryList()) {
            List<PaymentApplicationValue> applications = new ArrayList<>();
            for (GenericValue application : EntityQuery.use(delegator).from("PaymentApplication")
                    .where("invoiceId", link.getString("invoiceId")).queryList()) {
                GenericValue payment = EntityQuery.use(delegator).from("Payment")
                        .where("paymentId", application.getString("paymentId")).queryOne();
                if (payment == null || !("PMNT_RECEIVED".equals(payment.getString("statusId"))
                        || "PMNT_CONFIRMED".equals(payment.getString("statusId")))) {
                    continue;
                }
                applications.add(new PaymentApplicationValue(payment.getTimestamp("effectiveDate"),
                        defaultZero(application.getBigDecimal("amountApplied"))));
            }
            applications.sort(Comparator.comparing(value -> value.effectiveDate));
            BigDecimal principalAmount = defaultZero(link.getBigDecimal("principalAmount"));
            BigDecimal interestAmount = defaultZero(link.getBigDecimal("interestAmount"));
            BigDecimal cumulative = BigDecimal.ZERO;
            BigDecimal previousPrincipal = BigDecimal.ZERO;
            for (PaymentApplicationValue application : applications) {
                cumulative = cumulative.add(application.amount);
                BigDecimal principalPaid = cumulative.subtract(interestAmount).max(BigDecimal.ZERO)
                        .min(principalAmount);
                outstanding = outstanding.subtract(principalPaid.subtract(previousPrincipal));
                previousPrincipal = principalPaid;
            }
        }
        return outstanding.max(BigDecimal.ZERO).setScale(SCALE, ROUNDING);
    }

    private static BigDecimal computeFee(GenericValue product, BigDecimal prepaidPrincipal) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal feeRate = product.getBigDecimal("prepaymentFeeRate");
        if (feeRate != null && feeRate.compareTo(BigDecimal.ZERO) != 0) {
            return prepaidPrincipal.multiply(feeRate).setScale(SCALE, ROUNDING);
        }
        BigDecimal feeAmount = product.getBigDecimal("prepaymentFeeAmount");
        return feeAmount == null ? BigDecimal.ZERO : feeAmount.setScale(SCALE, ROUNDING);
    }

    private static GenericValue resolveProduct(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        GenericValue application = EntityQuery.use(delegator).from("LoanApplication")
                .where("loanApplicationId", agreement.getString("loanApplicationId")).queryOne();
        if (application == null) {
            return null;
        }
        return EntityQuery.use(delegator).from("FinancialProduct")
                .where("productId", application.getString("productId")).queryOne();
    }

    private static String resolveRepaymentMethod(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        GenericValue quote = EntityQuery.use(delegator).from("LoanQuote")
                .where("loanQuoteId", agreement.getString("loanQuoteId")).queryOne();
        String method = quote == null ? null : quote.getString("repaymentMethodEnumId");
        return UtilValidate.isEmpty(method) ? "LOAN_RPM_EQUAL_PAY" : method;
    }

    private static String postFeeIncome(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue agreement, BigDecimal fee, Timestamp transactionDate, String organizationPartyId,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        String debitGlAccountId = findGlAccountId(delegator, organizationPartyId, "ACCOUNTS_RECEIVABLE");
        String creditGlAccountId = findGlAccountId(delegator, organizationPartyId, "INTEREST_INCOME");
        if (debitGlAccountId == null || creditGlAccountId == null) {
            return null;
        }
        Map<String, Object> glInput = new HashMap<>();
        glInput.put("acctgTransTypeId", "OTHER_INTERNAL");
        glInput.put("description", "Loan prepayment fee " + agreement.getString("loanAgreementId"));
        glInput.put("transactionDate", transactionDate);
        glInput.put("isPosted", "N");
        glInput.put("glFiscalTypeId", "ACTUAL");
        glInput.put("partyId", agreement.getString("borrowerPartyId"));
        glInput.put("organizationPartyId", organizationPartyId);
        glInput.put("amount", fee);
        glInput.put("origAmount", fee);
        glInput.put("currencyUomId", agreement.getString("currencyUomId"));
        glInput.put("origCurrencyUomId", agreement.getString("currencyUomId"));
        glInput.put("debitGlAccountId", debitGlAccountId);
        glInput.put("creditGlAccountId", creditGlAccountId);
        glInput.put("userLogin", userLogin);
        Map<String, Object> glResult = dispatcher.runSync("quickCreateAcctgTransAndEntries", glInput);
        if (ServiceUtil.isError(glResult)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(glResult));
        }
        Map<String, Object> postResult = dispatcher.runSync("postAcctgTrans",
                UtilMisc.toMap("acctgTransId", glResult.get("acctgTransId"), "userLogin", userLogin));
        if (ServiceUtil.isError(postResult)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(postResult));
        }
        return (String) glResult.get("acctgTransId");
    }

    private static GenericValue lockAgreement(Delegator delegator, String loanAgreementId)
            throws GenericEntityException {
        return FinanceAccountingServices.lockLoanAgreement(delegator, loanAgreementId);
    }

    private static String getOrganizationPartyId(GenericValue agreement) throws GenericEntityException {
        GenericValue finAccount = agreement.getRelatedOne("FinAccount", false);
        return finAccount == null ? null : finAccount.getString("organizationPartyId");
    }

    private static String findGlAccountId(Delegator delegator, String organizationPartyId,
            String glAccountTypeId) throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            return null;
        }
        GenericValue accountDefault = EntityQuery.use(delegator).from("GlAccountTypeDefault")
                .where("organizationPartyId", organizationPartyId,
                        "glAccountTypeId", glAccountTypeId).queryFirst();
        if (accountDefault != null) {
            return accountDefault.getString("glAccountId");
        }
        for (GenericValue account : EntityQuery.use(delegator).from("GlAccount")
                .where("glAccountTypeId", glAccountTypeId).orderBy("glAccountId").queryList()) {
            GenericValue organizationAccount = EntityQuery.use(delegator).from("GlAccountOrganization")
                    .where("organizationPartyId", organizationPartyId,
                            "glAccountId", account.getString("glAccountId")).queryFirst();
            if (organizationAccount != null) {
                return account.getString("glAccountId");
            }
        }
        return null;
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<String, Object> recordNotFound(String entityName, String id, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceRecordNotFound",
                UtilMisc.toMap("entityName", entityName, "id", id), locale));
    }

    private static final class ScheduleRow {
        private final int installmentNumber;
        private final BigDecimal paymentAmount;
        private final BigDecimal principalAmount;
        private final BigDecimal interestAmount;
        private final BigDecimal remainingBalance;

        private ScheduleRow(int installmentNumber, BigDecimal paymentAmount, BigDecimal principalAmount,
                BigDecimal interestAmount, BigDecimal remainingBalance) {
            this.installmentNumber = installmentNumber;
            this.paymentAmount = paymentAmount;
            this.principalAmount = principalAmount;
            this.interestAmount = interestAmount;
            this.remainingBalance = remainingBalance;
        }
    }

    private static final class PaymentApplicationValue {
        private final Timestamp effectiveDate;
        private final BigDecimal amount;

        private PaymentApplicationValue(Timestamp effectiveDate, BigDecimal amount) {
            this.effectiveDate = effectiveDate;
            this.amount = amount;
        }
    }
}
