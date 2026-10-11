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

package org.apache.ofbiz.finance.common;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.accounting.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Common delinquency engine (STEP 6, generalized in STEP L0): delinquency
 * detection / classification, late fee (penalty interest) accrual, dunning
 * notice recording and write-off / legal actions.
 *
 * <p>The engine works on {@code FinanceContract}. Everything that depends on the
 * contract type (agreement, confirmed schedule, invoices) is delegated to the
 * {@link FinanceContractAdapter} registered for the contract type. The existing
 * service interfaces accept {@code loanAgreementId} (resolved through the loan
 * adapter) or {@code financeContractId}.
 */
public final class FinanceDelinquencyServices {

    private static final String MODULE = FinanceDelinquencyServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    private FinanceDelinquencyServices() {
    }

    /**
     * Daily delinquency batch. For every ACTIVE contract (or the requested one) it
     * computes days past due, classifies the contract against its product's
     * {@code FinancialProductDelinquency} configuration, records a delinquency
     * snapshot and triggers write-off / legal actions when the classification
     * flags them. Idempotent per processing day.
     */
    public static Map<String, Object> runLoanDelinquencyBatch(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp processingDate = (Timestamp) context.get("processingDate");
        if (processingDate == null) {
            processingDate = UtilDateTime.nowTimestamp();
        }
        Timestamp asOfDate = endOfDay(processingDate);

        long processedAgreementCount = 0;
        long createdDelinquencyCount = 0;
        long createdActionCount = 0;
        try {
            List<GenericValue> contracts = new ArrayList<>();
            String requestedId = FinanceContractAdapters.requestedAgreementId(context);
            if (UtilValidate.isNotEmpty(requestedId)) {
                GenericValue contract = findRequestedContract(delegator, context);
                if (contract == null) {
                    return recordNotFound(requestedEntityName(context), requestedId, locale);
                }
                contracts.add(contract);
            } else {
                contracts = EntityQuery.use(delegator).from("FinanceContract")
                        .where("statusId", FinanceContractHelper.STATUS_ACTIVE)
                        .orderBy("financeContractId").queryList();
            }

            for (GenericValue contract : contracts) {
                FinanceContractAdapter adapter = FinanceContractAdapters.forType(contract.getString("contractTypeId"));
                if (adapter == null) {
                    continue;
                }
                String financeContractId = contract.getString("financeContractId");
                FinanceContractHelper.lock(delegator, financeContractId);
                GenericValue agreement = adapter.findAgreement(delegator, financeContractId);
                if (agreement == null) {
                    continue;
                }
                DelinquencyComputation computation = adapter.computeDelinquency(delegator, agreement, asOfDate);
                if (computation.getDaysPastDue() <= 0) {
                    processedAgreementCount++;
                    continue;
                }

                GenericValue classification = classifyDelinquency(delegator,
                        contract.getString("productId"), computation.getDaysPastDue());
                String delinquencyLevelEnumId = classification == null
                        ? "DELINQ_D1" : classification.getString("delinquencyLevelEnumId");

                if (createDelinquencySnapshot(delegator, contract, adapter, agreement,
                        asOfDate, computation, delinquencyLevelEnumId)) {
                    createdDelinquencyCount++;
                }
                if (classification != null && "Y".equals(classification.getString("writeoffFlag"))) {
                    if (performWriteOff(delegator, dispatcher, contract, adapter, agreement,
                            computation, asOfDate, userLogin)) {
                        createdActionCount++;
                    }
                }
                if (classification != null && "Y".equals(classification.getString("legalActionFlag"))) {
                    if (recordAction(delegator, contract, adapter, agreement, "DELINQ_ACT_LEGAL",
                            asOfDate, null, null, null, "Legal action flag")) {
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
     * Accrue late fee (penalty interest) on the overdue balance of one contract
     * and post a balanced GL journal: debit interest receivable, credit late fee
     * income. Re-running the same period is idempotent.
     */
    public static Map<String, Object> runLateFeeAccrual(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp accrualDate = (Timestamp) context.get("accrualDate");
        if (accrualDate == null) {
            accrualDate = UtilDateTime.nowTimestamp();
        }

        try {
            GenericValue contract = findRequestedContract(delegator, context);
            FinanceContractAdapter adapter = contract == null ? null
                    : FinanceContractAdapters.forType(contract.getString("contractTypeId"));
            if (contract == null || adapter == null) {
                return recordNotFound(requestedEntityName(context),
                        FinanceContractAdapters.requestedAgreementId(context), locale);
            }
            String financeContractId = contract.getString("financeContractId");
            FinanceContractHelper.lock(delegator, financeContractId);
            GenericValue agreement = adapter.findAgreement(delegator, financeContractId);
            if (agreement == null) {
                return recordNotFound(adapter.getAgreementEntityName(),
                        FinanceContractAdapters.requestedAgreementId(context), locale);
            }
            String agreementId = agreement.getString(adapter.getAgreementIdField());
            if (!adapter.isActive(agreement)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinanceAgreementNotActive", UtilMisc.toMap("loanAgreementId", agreementId), locale));
            }

            String productId = contract.getString("productId");
            BigDecimal lateFeeRate = resolveLateFeeRate(delegator, productId);
            if (lateFeeRate == null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("accrualCreated", Boolean.FALSE);
                return result;
            }

            String currencyUomId = contract.getString("currencyUomId");
            DelinquencyComputation computation = adapter.computeDelinquency(delegator, agreement,
                    endOfDay(accrualDate));
            BigDecimal overdueBalance = computation.getOverdueTotal()
                    .setScale(FinanceInterestCalculator.resolveScale(currencyUomId, delegator),
                            FinanceInterestCalculator.resolveRoundingMode(currencyUomId, delegator));
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
                    .where("financeContractId", financeContractId).queryList()) {
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

            String dayCountConvId = FinanceInterestCalculator.resolveDayCountConvention(delegator, productId);
            BigDecimal lateFee;
            if (FinanceInterestCalculator.isPeriodBased(dayCountConvId)) {
                // 30/360: compute as a single period (overdueBalance is constant).
                lateFee = FinanceInterestCalculator.computePeriodInterest30_360(
                        overdueBalance, lateFeeRate, toLocalDate(fromDate), toLocalDate(thruDate));
            } else {
                // Actual-based: daily loop (overdueBalance is constant across the period).
                BigDecimal accumulated = BigDecimal.ZERO;
                LocalDate loopEnd = toLocalDate(thruDate);
                for (LocalDate d = toLocalDate(fromDate); !d.isAfter(loopEnd); d = d.plusDays(1)) {
                    accumulated = accumulated.add(FinanceInterestCalculator.computeDailyInterest(
                            overdueBalance, lateFeeRate, d, dayCountConvId));
                }
                lateFee = accumulated;
            }
            lateFee = FinanceInterestCalculator.roundInterest(lateFee, currencyUomId, delegator);

            String organizationPartyId = adapter.getOrganizationPartyId(delegator, agreement);
            String acctgTransId = null;
            if (lateFee.compareTo(BigDecimal.ZERO) > 0) {
                String debitGlAccountId = findGlAccountId(delegator, organizationPartyId, "INTRSTINC_RECEIVABLE");
                String creditGlAccountId = findGlAccountId(delegator, organizationPartyId, "LATE_FEE_INCOME");
                if (debitGlAccountId == null) {
                    return glMappingMissing(organizationPartyId, "INTRSTINC_RECEIVABLE", locale);
                }
                if (creditGlAccountId == null) {
                    return glMappingMissing(organizationPartyId, "LATE_FEE_INCOME", locale);
                }
                acctgTransId = postGlTransaction(dispatcher, contract,
                        adapter.getDescriptionPrefix() + " late fee " + agreementId + " " + fromDate, accrualDate,
                        lateFee, debitGlAccountId, creditGlAccountId, organizationPartyId, userLogin);
            }

            GenericValue accrual = delegator.makeValue("LoanLateFeeAccrual");
            accrual.set("loanLateFeeAccrualId", delegator.getNextSeqId("LoanLateFeeAccrual"));
            accrual.set("financeContractId", financeContractId);
            accrual.setNonPKFields(adapter.getAgreementKeyFields(agreement));
            accrual.set("accrualDate", accrualDate);
            accrual.set("fromDate", fromDate);
            accrual.set("thruDate", thruDate);
            accrual.set("overduePrincipal", computation.getOverduePrincipal());
            accrual.set("overdueInterest", computation.getOverdueInterest());
            accrual.set("lateFeeAmount", lateFee);
            accrual.set("currencyUomId", currencyUomId);
            accrual.set("acctgTransId", acctgTransId);
            accrual.set("createdDate", UtilDateTime.nowTimestamp());
            accrual.create();
            return lateFeeResult(accrual, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Record a dunning notice for a contract at the requested dunning level. */
    public static Map<String, Object> createLoanDunningNotice(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String dunningLevelEnumId = (String) context.get("dunningLevelEnumId");
        Timestamp noticeDate = (Timestamp) context.get("noticeDate");
        if (noticeDate == null) {
            noticeDate = UtilDateTime.nowTimestamp();
        }
        try {
            GenericValue contract = findRequestedContract(delegator, context);
            FinanceContractAdapter adapter = contract == null ? null
                    : FinanceContractAdapters.forType(contract.getString("contractTypeId"));
            GenericValue agreement = adapter == null ? null
                    : adapter.findAgreement(delegator, contract.getString("financeContractId"));
            if (agreement == null) {
                return recordNotFound(requestedEntityName(context),
                        FinanceContractAdapters.requestedAgreementId(context), locale);
            }
            String financeContractId = contract.getString("financeContractId");
            GenericValue existing = EntityQuery.use(delegator).from("LoanDunningNotice")
                    .where("financeContractId", financeContractId,
                            "dunningLevelEnumId", dunningLevelEnumId,
                            "noticeDate", noticeDate).queryFirst();
            if (existing != null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("loanDunningNoticeId", existing.getString("loanDunningNoticeId"));
                result.put("noticeCreated", Boolean.FALSE);
                return result;
            }

            String loanDunningNoticeId = delegator.getNextSeqId("LoanDunningNotice");
            GenericValue notice = delegator.makeValue("LoanDunningNotice");
            notice.set("loanDunningNoticeId", loanDunningNoticeId);
            notice.set("financeContractId", financeContractId);
            notice.setNonPKFields(adapter.getAgreementKeyFields(agreement));
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

    private static GenericValue findRequestedContract(Delegator delegator, Map<String, Object> context)
            throws GenericEntityException {
        String financeContractId = FinanceContractAdapters.resolveContractId(delegator, context);
        if (UtilValidate.isEmpty(financeContractId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("FinanceContract")
                .where("financeContractId", financeContractId).queryOne();
    }

    private static String requestedEntityName(Map<String, Object> context) {
        if (UtilValidate.isNotEmpty((String) context.get("financeContractId"))) {
            return "FinanceContract";
        }
        for (FinanceContractAdapter adapter : FinanceContractAdapters.all()) {
            if (UtilValidate.isNotEmpty((String) context.get(adapter.getAgreementIdField()))) {
                return adapter.getAgreementEntityName();
            }
        }
        return "FinanceContract";
    }

    private static GenericValue classifyDelinquency(Delegator delegator, String productId,
            long daysPastDue) throws GenericEntityException {
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

    private static boolean createDelinquencySnapshot(Delegator delegator, GenericValue contract,
            FinanceContractAdapter adapter, GenericValue agreement, Timestamp asOfDate,
            DelinquencyComputation computation, String levelEnumId) throws GenericEntityException {
        String financeContractId = contract.getString("financeContractId");
        GenericValue existing = EntityQuery.use(delegator).from("LoanDelinquency")
                .where("financeContractId", financeContractId, "asOfDate", asOfDate).queryFirst();
        if (existing != null) {
            return false;
        }
        GenericValue snapshot = delegator.makeValue("LoanDelinquency");
        snapshot.set("loanDelinquencyId", delegator.getNextSeqId("LoanDelinquency"));
        snapshot.set("financeContractId", financeContractId);
        snapshot.setNonPKFields(adapter.getAgreementKeyFields(agreement));
        snapshot.set("asOfDate", asOfDate);
        snapshot.set("daysPastDue", computation.getDaysPastDue());
        snapshot.set("delinquencyLevelEnumId", levelEnumId);
        snapshot.set("overduePrincipalAmount", computation.getOverduePrincipal());
        snapshot.set("overdueInterestAmount", computation.getOverdueInterest());
        snapshot.set("lateFeeAccruedAmount", BigDecimal.ZERO);
        snapshot.set("createdDate", UtilDateTime.nowTimestamp());
        snapshot.create();
        return true;
    }

    private static boolean performWriteOff(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue contract, FinanceContractAdapter adapter, GenericValue agreement,
            DelinquencyComputation computation, Timestamp asOfDate, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        if (!recordAction(delegator, contract, adapter, agreement, "DELINQ_ACT_WRITEOFF", asOfDate,
                computation.getOverdueTotal(), null, null, "Write-off delinquent balance")) {
            return false;
        }
        // Mark the open invoices of the contract as written off.
        for (String invoiceId : adapter.findOpenInvoiceIds(delegator, agreement)) {
            Map<String, Object> statusResult = dispatcher.runSync("setInvoiceStatus",
                    UtilMisc.toMap("invoiceId", invoiceId, "statusId", "INVOICE_WRITEOFF",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(statusResult)) {
                throw new GenericServiceException(ServiceUtil.getErrorMessage(statusResult));
            }
        }
        // Post write-off GL: expense against principal and interest receivables.
        String agreementId = agreement.getString(adapter.getAgreementIdField());
        String organizationPartyId = adapter.getOrganizationPartyId(delegator, agreement);
        String writeOffGlAccountId = findGlAccountId(delegator, organizationPartyId, "WRITEOFF");
        String principalReceivable = findGlAccountId(delegator, organizationPartyId, "ACCOUNTS_RECEIVABLE");
        String interestReceivable = findGlAccountId(delegator, organizationPartyId, "INTRSTINC_RECEIVABLE");
        if (writeOffGlAccountId != null && principalReceivable != null
                && computation.getOverduePrincipal().compareTo(BigDecimal.ZERO) > 0) {
            postGlTransaction(dispatcher, contract,
                    adapter.getDescriptionPrefix() + " write-off principal " + agreementId,
                    asOfDate, computation.getOverduePrincipal(), writeOffGlAccountId,
                    principalReceivable, organizationPartyId, userLogin);
        }
        if (writeOffGlAccountId != null && interestReceivable != null
                && computation.getOverdueInterest().compareTo(BigDecimal.ZERO) > 0) {
            postGlTransaction(dispatcher, contract,
                    adapter.getDescriptionPrefix() + " write-off interest " + agreementId,
                    asOfDate, computation.getOverdueInterest(), writeOffGlAccountId,
                    interestReceivable, organizationPartyId, userLogin);
        }
        return true;
    }

    private static boolean recordAction(Delegator delegator, GenericValue contract,
            FinanceContractAdapter adapter, GenericValue agreement, String actionTypeEnumId,
            Timestamp actionDate, BigDecimal amount, String invoiceId, String acctgTransId,
            String comments) throws GenericEntityException {
        String financeContractId = contract.getString("financeContractId");
        GenericValue existing = EntityQuery.use(delegator).from("LoanDelinquencyAction")
                .where("financeContractId", financeContractId,
                        "actionTypeEnumId", actionTypeEnumId,
                        "actionDate", actionDate).queryFirst();
        if (existing != null) {
            return false;
        }
        GenericValue action = delegator.makeValue("LoanDelinquencyAction");
        action.set("loanDelinquencyActionId", delegator.getNextSeqId("LoanDelinquencyAction"));
        action.set("financeContractId", financeContractId);
        action.setNonPKFields(adapter.getAgreementKeyFields(agreement));
        action.set("actionTypeEnumId", actionTypeEnumId);
        action.set("actionDate", actionDate);
        action.set("amount", amount);
        action.set("invoiceId", invoiceId);
        action.set("acctgTransId", acctgTransId);
        action.set("comments", comments);
        action.create();
        return true;
    }

    private static BigDecimal resolveLateFeeRate(Delegator delegator, String productId)
            throws GenericEntityException {
        if (productId == null) {
            return null;
        }
        GenericValue product = EntityQuery.use(delegator).from("FinancialProduct")
                .where("productId", productId).queryOne();
        return product == null ? null : product.getBigDecimal("lateFeeAnnualRate");
    }

    private static String postGlTransaction(LocalDispatcher dispatcher, GenericValue contract,
            String description, Timestamp transactionDate, BigDecimal amount,
            String debitGlAccountId, String creditGlAccountId, String organizationPartyId,
            GenericValue userLogin) throws GenericServiceException {
        Map<String, Object> glInput = new HashMap<>();
        glInput.put("acctgTransTypeId", "OTHER_INTERNAL");
        glInput.put("description", description);
        glInput.put("transactionDate", transactionDate);
        glInput.put("isPosted", "N");
        glInput.put("glFiscalTypeId", "ACTUAL");
        glInput.put("partyId", contract.getString("customerPartyId"));
        glInput.put("organizationPartyId", organizationPartyId);
        glInput.put("amount", amount);
        glInput.put("origAmount", amount);
        glInput.put("currencyUomId", contract.getString("currencyUomId"));
        glInput.put("origCurrencyUomId", contract.getString("currencyUomId"));
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
}
