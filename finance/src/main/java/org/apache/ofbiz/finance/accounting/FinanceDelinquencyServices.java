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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
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
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Finance STEP 6 services: delinquency detection / classification, late fee
 * (penalty interest) accrual, dunning notice recording and write-off / legal
 * actions.
 */
public final class FinanceDelinquencyServices {

    private static final String MODULE = FinanceDelinquencyServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private FinanceDelinquencyServices() {
    }

    /**
     * Daily delinquency batch. For every active agreement it detects past-due
     * installments, computes days past due, classifies the agreement against its
     * product's {@code FinancialProductDelinquency} configuration, records a
     * {@code LoanDelinquency} snapshot and triggers write-off / legal actions
     * when the classification flags them. Idempotent per processing day.
     */
    public static Map<String, Object> runLoanDelinquencyBatch(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp processingDate = (Timestamp) context.get("processingDate");
        String requestedAgreementId = (String) context.get("loanAgreementId");
        if (processingDate == null) {
            processingDate = UtilDateTime.nowTimestamp();
        }
        Timestamp asOfDate = endOfDay(processingDate);

        long processedAgreementCount = 0;
        long createdDelinquencyCount = 0;
        long createdActionCount = 0;
        try {
            List<GenericValue> agreements = new ArrayList<>();
            if (UtilValidate.isNotEmpty(requestedAgreementId)) {
                GenericValue agreement = EntityQuery.use(delegator).from("LoanAgreement")
                        .where("loanAgreementId", requestedAgreementId).queryOne();
                if (agreement == null) {
                    return recordNotFound("LoanAgreement", requestedAgreementId, locale);
                }
                agreements.add(agreement);
            } else {
                agreements = EntityQuery.use(delegator).from("LoanAgreement")
                        .where("statusId", "LOANAGR_ACTIVE").orderBy("loanAgreementId").queryList();
            }

            for (GenericValue agreement : agreements) {
                GenericValue locked = lockAgreement(delegator, agreement.getString("loanAgreementId"));
                if (locked == null) {
                    continue;
                }
                String loanAgreementId = locked.getString("loanAgreementId");
                DelinquencyComputation computation = computeDelinquency(delegator, locked, asOfDate);
                if (computation.daysPastDue <= 0) {
                    processedAgreementCount++;
                    continue;
                }

                GenericValue classification = classifyDelinquency(delegator, locked, computation.daysPastDue);
                String delinquencyLevelEnumId = classification == null
                        ? "DELINQ_D1" : classification.getString("delinquencyLevelEnumId");

                boolean createdSnapshot = createDelinquencySnapshot(delegator, locked,
                        asOfDate, computation, delinquencyLevelEnumId);
                if (createdSnapshot) {
                    createdDelinquencyCount++;
                }

                if (classification != null
                        && "Y".equals(classification.getString("writeoffFlag"))) {
                    if (performWriteOff(delegator, dispatcher, locked, computation,
                            asOfDate, userLogin)) {
                        createdActionCount++;
                    }
                }
                if (classification != null
                        && "Y".equals(classification.getString("legalActionFlag"))) {
                    if (recordAction(delegator, locked, "DELINQ_ACT_LEGAL", asOfDate,
                            null, null, null, "Legal action flag")) {
                        createdActionCount++;
                    }
                }
                processedAgreementCount++;
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("processedAgreementCount", processedAgreementCount);
            result.put("createdDelinquencyCount", createdDelinquencyCount);
            result.put("createdActionCount", createdActionCount);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Accrue late fee (penalty interest) on the overdue balance for one
     * agreement and post a balanced GL journal: debit interest receivable,
     * credit late fee income. Re-running the same period is idempotent.
     */
    public static Map<String, Object> runLateFeeAccrual(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String loanAgreementId = (String) context.get("loanAgreementId");
        Timestamp accrualDate = (Timestamp) context.get("accrualDate");
        if (accrualDate == null) {
            accrualDate = UtilDateTime.nowTimestamp();
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

            BigDecimal lateFeeRate = resolveLateFeeRate(delegator, agreement);
            if (lateFeeRate == null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("accrualCreated", Boolean.FALSE);
                return result;
            }

            DelinquencyComputation computation = computeDelinquency(delegator, agreement, endOfDay(accrualDate));
            BigDecimal overdueBalance = computation.overduePrincipal.add(computation.overdueInterest)
                    .setScale(SCALE, ROUNDING);
            if (overdueBalance.compareTo(BigDecimal.ZERO) <= 0) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("accrualCreated", Boolean.FALSE);
                result.put("overdueBalance", overdueBalance);
                return result;
            }

            Timestamp monthStart = UtilDateTime.getMonthStart(accrualDate, TimeZone.getDefault(), locale);
            Timestamp monthEnd = UtilDateTime.getMonthEnd(accrualDate, TimeZone.getDefault(), locale);
            Timestamp thruDate = endOfDay(accrualDate);
            if (thruDate.after(monthEnd)) {
                thruDate = monthEnd;
            }
            Timestamp fromDate = monthStart;
            GenericValue latestAccrual = null;
            for (GenericValue row : EntityQuery.use(delegator).from("LoanLateFeeAccrual")
                    .where("loanAgreementId", loanAgreementId).queryList()) {
                Timestamp rowFrom = row.getTimestamp("fromDate");
                Timestamp rowThru = row.getTimestamp("thruDate");
                if (rowFrom == null || rowThru == null || rowFrom.after(monthEnd)
                        || rowThru.before(monthStart)) {
                    continue;
                }
                if (!rowThru.before(thruDate)) {
                    return lateFeeResult(row, false);
                }
                if (latestAccrual == null || rowThru.after(latestAccrual.getTimestamp("thruDate"))) {
                    latestAccrual = row;
                }
            }
            if (latestAccrual != null) {
                fromDate = new Timestamp(latestAccrual.getTimestamp("thruDate").getTime() + 1L);
            }

            long days = daysInclusive(fromDate, thruDate);
            BigDecimal lateFee = overdueBalance.multiply(lateFeeRate)
                    .multiply(new BigDecimal(days))
                    .divide(DAYS_IN_YEAR, SCALE, ROUNDING);

            String organizationPartyId = getOrganizationPartyId(agreement);
            String acctgTransId = null;
            if (lateFee.compareTo(BigDecimal.ZERO) > 0) {
                String debitGlAccountId = findGlAccountId(delegator, organizationPartyId,
                        "INTRSTINC_RECEIVABLE");
                String creditGlAccountId = findGlAccountId(delegator, organizationPartyId,
                        "LATE_FEE_INCOME");
                if (debitGlAccountId == null) {
                    return glMappingMissing(organizationPartyId, "INTRSTINC_RECEIVABLE", locale);
                }
                if (creditGlAccountId == null) {
                    return glMappingMissing(organizationPartyId, "LATE_FEE_INCOME", locale);
                }
                acctgTransId = postGlTransaction(delegator, dispatcher, agreement,
                        "Loan late fee " + loanAgreementId + " " + fromDate, accrualDate,
                        lateFee, debitGlAccountId, creditGlAccountId, organizationPartyId, userLogin);
            }

            String loanLateFeeAccrualId = delegator.getNextSeqId("LoanLateFeeAccrual");
            GenericValue accrual = delegator.makeValue("LoanLateFeeAccrual");
            accrual.set("loanLateFeeAccrualId", loanLateFeeAccrualId);
            accrual.set("loanAgreementId", loanAgreementId);
            accrual.set("accrualDate", accrualDate);
            accrual.set("fromDate", fromDate);
            accrual.set("thruDate", thruDate);
            accrual.set("overduePrincipal", computation.overduePrincipal);
            accrual.set("overdueInterest", computation.overdueInterest);
            accrual.set("lateFeeAmount", lateFee);
            accrual.set("currencyUomId", agreement.getString("currencyUomId"));
            accrual.set("acctgTransId", acctgTransId);
            accrual.set("createdDate", UtilDateTime.nowTimestamp());
            accrual.create();
            return lateFeeResult(accrual, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Record a dunning notice for an agreement at the requested dunning level. */
    public static Map<String, Object> createLoanDunningNotice(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String loanAgreementId = (String) context.get("loanAgreementId");
        String dunningLevelEnumId = (String) context.get("dunningLevelEnumId");
        Timestamp noticeDate = (Timestamp) context.get("noticeDate");
        if (noticeDate == null) {
            noticeDate = UtilDateTime.nowTimestamp();
        }
        try {
            GenericValue agreement = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", loanAgreementId).queryOne();
            if (agreement == null) {
                return recordNotFound("LoanAgreement", loanAgreementId, locale);
            }
            GenericValue existing = EntityQuery.use(delegator).from("LoanDunningNotice")
                    .where("loanAgreementId", loanAgreementId,
                            "dunningLevelEnumId", dunningLevelEnumId,
                            "noticeDate", noticeDate).queryOne();
            if (existing != null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("loanDunningNoticeId", existing.getString("loanDunningNoticeId"));
                result.put("noticeCreated", Boolean.FALSE);
                return result;
            }

            String loanDunningNoticeId = delegator.getNextSeqId("LoanDunningNotice");
            GenericValue notice = delegator.makeValue("LoanDunningNotice");
            notice.set("loanDunningNoticeId", loanDunningNoticeId);
            notice.set("loanAgreementId", loanAgreementId);
            notice.set("dunningLevelEnumId", dunningLevelEnumId);
            notice.set("noticeDate", noticeDate);
            notice.set("comments", context.get("comments"));
            notice.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("loanDunningNoticeId", loanDunningNoticeId);
            result.put("noticeCreated", Boolean.TRUE);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static DelinquencyComputation computeDelinquency(Delegator delegator, GenericValue agreement,
            Timestamp asOfDate) throws GenericEntityException {
        BigDecimal overduePrincipal = BigDecimal.ZERO;
        BigDecimal overdueInterest = BigDecimal.ZERO;
        Timestamp oldestDueDate = null;

        List<GenericValue> schedule = EntityQuery.use(delegator)
                .from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", agreement.getString("loanAgreementId"))
                .orderBy("dueDate", "installmentNumber").queryList();
        for (GenericValue installment : schedule) {
            Timestamp dueDate = installment.getTimestamp("dueDate");
            if (dueDate == null || dueDate.after(asOfDate)) {
                continue;
            }
            BigDecimal scheduledPrincipal = defaultZero(installment.getBigDecimal("principalAmount"));
            BigDecimal scheduledInterest = defaultZero(installment.getBigDecimal("interestAmount"));
            BigDecimal overdueForInstallment = scheduledPrincipal.add(scheduledInterest);

            GenericValue link = EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                    .where("loanAgreementId", agreement.getString("loanAgreementId"),
                            "installmentNumber", installment.getLong("installmentNumber")).queryOne();
            if (link != null && link.getString("invoiceId") != null) {
                GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                        .where("invoiceId", link.getString("invoiceId")).queryOne();
                if (invoice != null && ("INVOICE_PAID".equals(invoice.getString("statusId"))
                        || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))
                        || "INVOICE_CANCELLED".equals(invoice.getString("statusId")))) {
                    continue;
                }
                BigDecimal notApplied = defaultZero(InvoiceWorker.getInvoiceNotApplied(invoice));
                overdueForInstallment = notApplied;
            }
            BigDecimal unappliedInterest = overdueForInstallment.min(scheduledInterest);
            BigDecimal unappliedPrincipal = overdueForInstallment.subtract(unappliedInterest)
                    .min(scheduledPrincipal).max(BigDecimal.ZERO);
            overdueInterest = overdueInterest.add(unappliedInterest);
            overduePrincipal = overduePrincipal.add(unappliedPrincipal);
            if (oldestDueDate == null) {
                oldestDueDate = dueDate;
            }
        }

        long daysPastDue = 0;
        if (oldestDueDate != null) {
            LocalDate dueDay = toLocalDate(oldestDueDate);
            LocalDate asOfDay = toLocalDate(asOfDate);
            daysPastDue = ChronoUnit.DAYS.between(dueDay, asOfDay);
            if (daysPastDue < 0) {
                daysPastDue = 0;
            }
        }
        return new DelinquencyComputation(daysPastDue,
                overduePrincipal.setScale(SCALE, ROUNDING),
                overdueInterest.setScale(SCALE, ROUNDING));
    }

    private static GenericValue classifyDelinquency(Delegator delegator, GenericValue agreement,
            long daysPastDue) throws GenericEntityException {
        String productId = resolveProductId(delegator, agreement);
        if (productId == null) {
            return null;
        }
        List<GenericValue> rows = EntityQuery.use(delegator).from("FinancialProductDelinquency")
                .where("productId", productId).orderBy("minDaysPastDue").queryList();
        for (GenericValue row : rows) {
            Long min = row.getLong("minDaysPastDue");
            Long max = row.getLong("maxDaysPastDue");
            if (min != null && daysPastDue >= min && (max == null || daysPastDue <= max)) {
                return row;
            }
        }
        return null;
    }

    private static boolean createDelinquencySnapshot(Delegator delegator, GenericValue agreement,
            Timestamp asOfDate, DelinquencyComputation computation, String levelEnumId)
            throws GenericEntityException {
        GenericValue existing = EntityQuery.use(delegator).from("LoanDelinquency")
                .where("loanAgreementId", agreement.getString("loanAgreementId"),
                        "asOfDate", asOfDate).queryOne();
        if (existing != null) {
            return false;
        }
        String loanDelinquencyId = delegator.getNextSeqId("LoanDelinquency");
        GenericValue snapshot = delegator.makeValue("LoanDelinquency");
        snapshot.set("loanDelinquencyId", loanDelinquencyId);
        snapshot.set("loanAgreementId", agreement.getString("loanAgreementId"));
        snapshot.set("asOfDate", asOfDate);
        snapshot.set("daysPastDue", computation.daysPastDue);
        snapshot.set("delinquencyLevelEnumId", levelEnumId);
        snapshot.set("overduePrincipalAmount", computation.overduePrincipal);
        snapshot.set("overdueInterestAmount", computation.overdueInterest);
        snapshot.set("lateFeeAccruedAmount", BigDecimal.ZERO);
        snapshot.set("createdDate", UtilDateTime.nowTimestamp());
        snapshot.create();
        return true;
    }

    private static boolean performWriteOff(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue agreement, DelinquencyComputation computation, Timestamp asOfDate,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        if (!recordAction(delegator, agreement, "DELINQ_ACT_WRITEOFF", asOfDate,
                computation.overduePrincipal.add(computation.overdueInterest), null, null,
                "Write-off delinquent balance")) {
            return false;
        }
        // Mark overdue invoices as written off.
        List<GenericValue> links = EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                .where("loanAgreementId", agreement.getString("loanAgreementId")).queryList();
        for (GenericValue link : links) {
            GenericValue invoice = link.getString("invoiceId") == null ? null
                    : EntityQuery.use(delegator).from("Invoice")
                            .where("invoiceId", link.getString("invoiceId")).queryOne();
            if (invoice == null || "INVOICE_PAID".equals(invoice.getString("statusId"))
                    || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))) {
                continue;
            }
            Map<String, Object> statusResult = dispatcher.runSync("setInvoiceStatus",
                    UtilMisc.toMap("invoiceId", invoice.getString("invoiceId"),
                            "statusId", "INVOICE_WRITEOFF", "userLogin", userLogin));
            if (ServiceUtil.isError(statusResult)) {
                throw new GenericServiceException(ServiceUtil.getErrorMessage(statusResult));
            }
        }
        // Post write-off GL: expense against principal and interest receivables.
        String organizationPartyId = getOrganizationPartyId(agreement);
        String writeOffGlAccountId = findGlAccountId(delegator, organizationPartyId, "WRITEOFF");
        String principalReceivable = findGlAccountId(delegator, organizationPartyId, "ACCOUNTS_RECEIVABLE");
        String interestReceivable = findGlAccountId(delegator, organizationPartyId, "INTRSTINC_RECEIVABLE");
        if (writeOffGlAccountId != null && principalReceivable != null
                && computation.overduePrincipal.compareTo(BigDecimal.ZERO) > 0) {
            postGlTransaction(delegator, dispatcher, agreement,
                    "Loan write-off principal " + agreement.getString("loanAgreementId"),
                    asOfDate, computation.overduePrincipal, writeOffGlAccountId,
                    principalReceivable, organizationPartyId, userLogin);
        }
        if (writeOffGlAccountId != null && interestReceivable != null
                && computation.overdueInterest.compareTo(BigDecimal.ZERO) > 0) {
            postGlTransaction(delegator, dispatcher, agreement,
                    "Loan write-off interest " + agreement.getString("loanAgreementId"),
                    asOfDate, computation.overdueInterest, writeOffGlAccountId,
                    interestReceivable, organizationPartyId, userLogin);
        }
        return true;
    }

    private static boolean recordAction(Delegator delegator, GenericValue agreement,
            String actionTypeEnumId, Timestamp actionDate, BigDecimal amount, String invoiceId,
            String acctgTransId, String comments) throws GenericEntityException {
        GenericValue existing = EntityQuery.use(delegator).from("LoanDelinquencyAction")
                .where("loanAgreementId", agreement.getString("loanAgreementId"),
                        "actionTypeEnumId", actionTypeEnumId,
                        "actionDate", actionDate).queryOne();
        if (existing != null) {
            return false;
        }
        String loanDelinquencyActionId = delegator.getNextSeqId("LoanDelinquencyAction");
        GenericValue action = delegator.makeValue("LoanDelinquencyAction");
        action.set("loanDelinquencyActionId", loanDelinquencyActionId);
        action.set("loanAgreementId", agreement.getString("loanAgreementId"));
        action.set("actionTypeEnumId", actionTypeEnumId);
        action.set("actionDate", actionDate);
        action.set("amount", amount);
        action.set("invoiceId", invoiceId);
        action.set("acctgTransId", acctgTransId);
        action.set("comments", comments);
        action.create();
        return true;
    }

    private static BigDecimal resolveLateFeeRate(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        String productId = resolveProductId(delegator, agreement);
        if (productId == null) {
            return null;
        }
        GenericValue product = EntityQuery.use(delegator).from("FinancialProduct")
                .where("productId", productId).queryOne();
        return product == null ? null : product.getBigDecimal("lateFeeAnnualRate");
    }

    private static String resolveProductId(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        GenericValue application = EntityQuery.use(delegator).from("LoanApplication")
                .where("loanApplicationId", agreement.getString("loanApplicationId")).queryOne();
        return application == null ? null : application.getString("productId");
    }

    private static String postGlTransaction(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue agreement, String description, Timestamp transactionDate, BigDecimal amount,
            String debitGlAccountId, String creditGlAccountId, String organizationPartyId,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        Map<String, Object> glInput = new HashMap<>();
        glInput.put("acctgTransTypeId", "OTHER_INTERNAL");
        glInput.put("description", description);
        glInput.put("transactionDate", transactionDate);
        glInput.put("isPosted", "N");
        glInput.put("glFiscalTypeId", "ACTUAL");
        glInput.put("partyId", agreement.getString("borrowerPartyId"));
        glInput.put("organizationPartyId", organizationPartyId);
        glInput.put("amount", amount);
        glInput.put("origAmount", amount);
        glInput.put("currencyUomId", agreement.getString("currencyUomId"));
        glInput.put("origCurrencyUomId", agreement.getString("currencyUomId"));
        glInput.put("debitGlAccountId", debitGlAccountId);
        glInput.put("creditGlAccountId", creditGlAccountId);
        glInput.put("userLogin", userLogin);
        Map<String, Object> glResult = dispatcher.runSync("quickCreateAcctgTransAndEntries", glInput);
        if (ServiceUtil.isError(glResult)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(glResult));
        }
        String acctgTransId = (String) glResult.get("acctgTransId");
        Map<String, Object> postResult = dispatcher.runSync("postAcctgTrans",
                UtilMisc.toMap("acctgTransId", acctgTransId, "userLogin", userLogin));
        if (ServiceUtil.isError(postResult)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(postResult));
        }
        return acctgTransId;
    }

    private static GenericValue lockAgreement(Delegator delegator, String loanAgreementId)
            throws GenericEntityException {
        for (int attempt = 0; attempt < 5; attempt++) {
            GenericValue agreement = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", loanAgreementId).cache(false).queryOne();
            if (agreement == null) {
                return null;
            }
            Long currentVersion = agreement.getLong("processingLockVersion");
            long nextVersion = currentVersion == null ? 1L : currentVersion + 1L;
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("loanAgreementId", EntityOperator.EQUALS, loanAgreementId),
                    EntityCondition.makeCondition("processingLockVersion",
                            EntityOperator.EQUALS, currentVersion)), EntityOperator.AND);
            int updated = delegator.storeByCondition("LoanAgreement",
                    UtilMisc.toMap("processingLockVersion", nextVersion), condition);
            if (updated == 1) {
                agreement.set("processingLockVersion", nextVersion);
                return agreement;
            }
        }
        throw new GenericEntityException("Could not acquire processing lock for loan agreement "
                + loanAgreementId);
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
        List<GenericValue> accounts = EntityQuery.use(delegator).from("GlAccount")
                .where("glAccountTypeId", glAccountTypeId).orderBy("glAccountId").queryList();
        for (GenericValue account : accounts) {
            GenericValue organizationAccount = EntityQuery.use(delegator).from("GlAccountOrganization")
                    .where("organizationPartyId", organizationPartyId,
                            "glAccountId", account.getString("glAccountId")).queryFirst();
            if (organizationAccount != null) {
                return account.getString("glAccountId");
            }
        }
        return null;
    }

    private static Timestamp endOfDay(Timestamp value) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate date = value.toInstant().atZone(zone).toLocalDate();
        return Timestamp.from(date.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1));
    }

    private static LocalDate toLocalDate(Timestamp value) {
        return value.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static long daysInclusive(Timestamp fromDate, Timestamp thruDate) {
        LocalDate from = toLocalDate(fromDate);
        LocalDate thru = toLocalDate(thruDate);
        long days = ChronoUnit.DAYS.between(from, thru) + 1L;
        return Math.max(days, 0L);
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<String, Object> lateFeeResult(GenericValue accrual, boolean created) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("loanLateFeeAccrualId", accrual.getString("loanLateFeeAccrualId"));
        result.put("acctgTransId", accrual.getString("acctgTransId"));
        result.put("lateFeeAmount", accrual.getBigDecimal("lateFeeAmount"));
        result.put("overduePrincipal", accrual.getBigDecimal("overduePrincipal"));
        result.put("overdueInterest", accrual.getBigDecimal("overdueInterest"));
        result.put("accrualCreated", created);
        return result;
    }

    private static Map<String, Object> recordNotFound(String entityName, String id, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceRecordNotFound",
                UtilMisc.toMap("entityName", entityName, "id", id), locale));
    }

    private static Map<String, Object> glMappingMissing(String organizationPartyId,
            String glAccountTypeId, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceGlAccountMappingMissing",
                UtilMisc.toMap("organizationPartyId", organizationPartyId,
                        "glAccountTypeId", glAccountTypeId), locale));
    }

    private static final class DelinquencyComputation {
        private final long daysPastDue;
        private final BigDecimal overduePrincipal;
        private final BigDecimal overdueInterest;

        private DelinquencyComputation(long daysPastDue, BigDecimal overduePrincipal,
                BigDecimal overdueInterest) {
            this.daysPastDue = daysPastDue;
            this.overduePrincipal = overduePrincipal;
            this.overdueInterest = overdueInterest;
        }
    }
}
