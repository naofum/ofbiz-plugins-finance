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
import java.util.ArrayList;
import java.util.Comparator;
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
 * Finance STEP 3 services: monthly interest accrual, installment billing and
 * payment receipt / settlement. Core accounting services own Invoice, Payment,
 * PaymentApplication and GL transaction persistence.
 */
public final class FinanceAccountingServices {

    private static final String MODULE = FinanceAccountingServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private FinanceAccountingServices() {
    }

    /**
     * Compute one monthly interest accrual for an active agreement and post the
     * balanced GL journal: debit interest receivable, credit interest income.
     * Re-running the same agreement/month returns the original accrual.
     */
    public static Map<String, Object> runMonthlyInterestAccrual(DispatchContext dctx,
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
                return agreementNotActive(loanAgreementId, locale);
            }

            Timestamp monthStart = UtilDateTime.getMonthStart(accrualDate, TimeZone.getDefault(), locale);
            Timestamp monthEnd = UtilDateTime.getMonthEnd(accrualDate, TimeZone.getDefault(), locale);
            Timestamp thruDate = endOfDay(accrualDate);
            if (thruDate.after(monthEnd)) {
                thruDate = monthEnd;
            }
            Timestamp agreementDate = agreement.getTimestamp("agreementDate");
            if (agreementDate != null && agreementDate.after(thruDate)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinanceAccrualBeforeAgreement", UtilMisc.toMap("loanAgreementId", loanAgreementId), locale));
            }
            Timestamp fromDate = agreementDate != null && agreementDate.after(monthStart)
                    ? agreementDate : monthStart;
            GenericValue latestAccrual = null;
            for (GenericValue row : EntityQuery.use(delegator).from("LoanAccrual")
                    .where("loanAgreementId", loanAgreementId).queryList()) {
                Timestamp rowFrom = row.getTimestamp("fromDate");
                Timestamp rowThru = row.getTimestamp("thruDate");
                if (rowFrom == null || rowThru == null || rowFrom.after(monthEnd)
                        || rowThru.before(monthStart)) {
                    continue;
                }
                if (!rowThru.before(thruDate)) {
                    return accrualResult(row, false);
                }
                if (latestAccrual == null
                        || rowThru.after(latestAccrual.getTimestamp("thruDate"))) {
                    latestAccrual = row;
                }
            }
            if (latestAccrual != null) {
                fromDate = new Timestamp(latestAccrual.getTimestamp("thruDate").getTime() + 1L);
            }

            BigDecimal annualRate = agreement.getBigDecimal("annualInterestRate");
            if (annualRate == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinanceQuoteMissingInputs", locale));
            }
            AccrualComputation computation = computeAccrual(delegator, agreement,
                    fromDate, thruDate, annualRate);
            BigDecimal outstanding = computation.outstandingPrincipal;
            BigDecimal interest = computation.interestAmount;
            String organizationPartyId = getOrganizationPartyId(agreement);
            String acctgTransId = null;

            if (interest.compareTo(BigDecimal.ZERO) > 0) {
                String debitGlAccountId = findGlAccountId(delegator, organizationPartyId,
                        "INTRSTINC_RECEIVABLE");
                if (debitGlAccountId == null) {
                    return glMappingMissing(organizationPartyId, "INTRSTINC_RECEIVABLE", locale);
                }
                String creditGlAccountId = findGlAccountId(delegator, organizationPartyId,
                        "INTEREST_INCOME");
                if (creditGlAccountId == null) {
                    return glMappingMissing(organizationPartyId, "INTEREST_INCOME", locale);
                }

                Map<String, Object> glInput = new HashMap<>();
                glInput.put("acctgTransTypeId", "OTHER_INTERNAL");
                glInput.put("description", "Loan interest accrual " + loanAgreementId + " " + fromDate);
                glInput.put("transactionDate", accrualDate);
                glInput.put("isPosted", "N");
                glInput.put("glFiscalTypeId", "ACTUAL");
                glInput.put("partyId", agreement.getString("borrowerPartyId"));
                glInput.put("organizationPartyId", organizationPartyId);
                glInput.put("amount", interest);
                glInput.put("origAmount", interest);
                glInput.put("currencyUomId", agreement.getString("currencyUomId"));
                glInput.put("origCurrencyUomId", agreement.getString("currencyUomId"));
                glInput.put("debitGlAccountId", debitGlAccountId);
                glInput.put("creditGlAccountId", creditGlAccountId);
                glInput.put("userLogin", userLogin);
                Map<String, Object> glResult = dispatcher.runSync("quickCreateAcctgTransAndEntries", glInput);
                if (ServiceUtil.isError(glResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(glResult));
                }
                acctgTransId = (String) glResult.get("acctgTransId");

                Map<String, Object> postResult = dispatcher.runSync("postAcctgTrans",
                        UtilMisc.toMap("acctgTransId", acctgTransId, "userLogin", userLogin));
                if (ServiceUtil.isError(postResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(postResult));
                }
            }

            String loanAccrualId = delegator.getNextSeqId("LoanAccrual");
            GenericValue accrual = delegator.makeValue("LoanAccrual");
            accrual.set("loanAccrualId", loanAccrualId);
            accrual.set("loanAgreementId", loanAgreementId);
            accrual.set("accrualDate", accrualDate);
            accrual.set("fromDate", fromDate);
            accrual.set("thruDate", thruDate);
            accrual.set("outstandingPrincipal", outstanding);
            accrual.set("interestAmount", interest);
            accrual.set("currencyUomId", agreement.getString("currencyUomId"));
            accrual.set("acctgTransId", acctgTransId);
            accrual.set("createdDate", UtilDateTime.nowTimestamp());
            accrual.create();
            return accrualResult(accrual, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Schedulable month-end process. It accrues every selected active agreement
     * and creates invoices for every confirmed installment due by processingDate.
     */
    public static Map<String, Object> runLoanMonthlyAccountingBatch(DispatchContext dctx,
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
        processingDate = endOfDay(processingDate);

        long processedAgreementCount = 0;
        long createdAccrualCount = 0;
        long createdInvoiceCount = 0;
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
                String loanAgreementId = agreement.getString("loanAgreementId");
                int accrualCountBefore = EntityQuery.use(delegator).from("LoanAccrual")
                        .where("loanAgreementId", loanAgreementId).queryList().size();

                // Process due installments in date/sequence order. Each invoice first
                // accrues only through its due date, so an accrual spanning a later
                // processing date can never hide the receivable available at billing.
                List<GenericValue> schedule = EntityQuery.use(delegator)
                        .from("LoanAgreementRepaymentSchedule")
                        .where("loanAgreementId", loanAgreementId)
                        .orderBy("dueDate", "installmentNumber").queryList();
                for (GenericValue installment : schedule) {
                    Timestamp dueDate = installment.getTimestamp("dueDate");
                    if (dueDate == null || dueDate.after(processingDate)) {
                        continue;
                    }
                    Map<String, Object> invoiceResult = dispatcher.runSync("createLoanInstallmentInvoiceInternal",
                            UtilMisc.toMap("loanAgreementId", loanAgreementId,
                                    "installmentNumber", installment.getLong("installmentNumber"),
                                    "userLogin", userLogin));
                    if (ServiceUtil.isError(invoiceResult)) {
                        return batchError(loanAgreementId, ServiceUtil.getErrorMessage(invoiceResult), locale);
                    }
                    if (Boolean.TRUE.equals(invoiceResult.get("invoiceCreated"))) {
                        createdInvoiceCount++;
                    }
                }

                // After all due-date transfers, accrue any remaining days through
                // the batch processing date as a non-overlapping incremental period.
                Map<String, Object> accrualResult = dispatcher.runSync("runMonthlyInterestAccrualInternal",
                        UtilMisc.toMap("loanAgreementId", loanAgreementId,
                                "accrualDate", processingDate, "userLogin", userLogin));
                if (ServiceUtil.isError(accrualResult)) {
                    return batchError(loanAgreementId, ServiceUtil.getErrorMessage(accrualResult), locale);
                }
                int accrualCountAfter = EntityQuery.use(delegator).from("LoanAccrual")
                        .where("loanAgreementId", loanAgreementId).queryList().size();
                createdAccrualCount += accrualCountAfter - accrualCountBefore;
                processedAgreementCount++;
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("processedAgreementCount", processedAgreementCount);
            result.put("createdAccrualCount", createdAccrualCount);
            result.put("createdInvoiceCount", createdInvoiceCount);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Create one sales invoice from the immutable agreement schedule. */
    public static Map<String, Object> createLoanInstallmentInvoice(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String loanAgreementId = (String) context.get("loanAgreementId");
        Long installmentNumber = (Long) context.get("installmentNumber");

        try {
            GenericValue agreement = lockAgreement(delegator, loanAgreementId);
            if (agreement == null) {
                return recordNotFound("LoanAgreement", loanAgreementId, locale);
            }
            if (!"LOANAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return agreementNotActive(loanAgreementId, locale);
            }

            GenericValue existing = EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                    .where("loanAgreementId", loanAgreementId,
                            "installmentNumber", installmentNumber).queryOne();
            if (existing != null && existing.getString("invoiceId") != null) {
                return invoiceResult(existing.getString("invoiceId"),
                        existing.getBigDecimal("billedAmount"), false);
            }

            GenericValue scheduleRow = EntityQuery.use(delegator)
                    .from("LoanAgreementRepaymentSchedule")
                    .where("loanAgreementId", loanAgreementId,
                            "installmentNumber", installmentNumber).queryOne();
            if (scheduleRow == null) {
                return recordNotFound("LoanAgreementRepaymentSchedule",
                        loanAgreementId + "/" + installmentNumber, locale);
            }

            BigDecimal principal = defaultZero(scheduleRow.getBigDecimal("principalAmount"));
            BigDecimal interest = defaultZero(scheduleRow.getBigDecimal("interestAmount"));
            Timestamp dueDate = scheduleRow.getTimestamp("dueDate");
            if (dueDate == null || dueDate.after(endOfDay(UtilDateTime.nowTimestamp()))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceInstallmentNotDue",
                        UtilMisc.toMap("loanAgreementId", loanAgreementId,
                                "installmentNumber", installmentNumber), locale));
            }
            String organizationPartyId = getOrganizationPartyId(agreement);
            GenericValue finAccount = agreement.getRelatedOne("FinAccount", false);
            String principalReceivableGlAccountId = finAccount == null
                    ? null : finAccount.getString("postToGlAccountId");
            if (principalReceivableGlAccountId == null) {
                principalReceivableGlAccountId = findGlAccountId(delegator, organizationPartyId,
                        "ACCOUNTS_RECEIVABLE");
            }
            if (principalReceivableGlAccountId == null) {
                return glMappingMissing(organizationPartyId, "ACCOUNTS_RECEIVABLE", locale);
            }
            String accruedInterestGlAccountId = findGlAccountId(delegator, organizationPartyId,
                    "INTRSTINC_RECEIVABLE");
            if (accruedInterestGlAccountId == null) {
                return glMappingMissing(organizationPartyId, "INTRSTINC_RECEIVABLE", locale);
            }
            String interestIncomeGlAccountId = findGlAccountId(delegator, organizationPartyId,
                    "INTEREST_INCOME");
            if (interestIncomeGlAccountId == null) {
                return glMappingMissing(organizationPartyId, "INTEREST_INCOME", locale);
            }

            Map<String, Object> accrualResult = dispatcher.runSync("runMonthlyInterestAccrualInternal",
                    UtilMisc.toMap("loanAgreementId", loanAgreementId,
                            "accrualDate", dueDate, "userLogin", userLogin));
            if (ServiceUtil.isError(accrualResult)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(accrualResult));
            }
            BigDecimal accruedInterestAvailable = getAccruedInterestAvailable(delegator,
                    loanAgreementId, dueDate);
            BigDecimal interestFromAccrual = interest.min(accruedInterestAvailable);
            BigDecimal interestRecognizedAtBilling = interest.subtract(interestFromAccrual);

            Map<String, Object> invInput = new HashMap<>();
            invInput.put("invoiceTypeId", "SALES_INVOICE");
            invInput.put("partyIdFrom", organizationPartyId);
            invInput.put("partyId", agreement.getString("borrowerPartyId"));
            invInput.put("statusId", "INVOICE_IN_PROCESS");
            invInput.put("currencyUomId", agreement.getString("currencyUomId"));
            invInput.put("invoiceDate", UtilDateTime.nowTimestamp());
            invInput.put("dueDate", dueDate);
            invInput.put("userLogin", userLogin);
            Map<String, Object> invResult = dispatcher.runSync("createInvoice", invInput);
            if (ServiceUtil.isError(invResult)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(invResult));
            }
            String invoiceId = (String) invResult.get("invoiceId");

            if (principal.compareTo(BigDecimal.ZERO) > 0) {
                createInvoiceItem(dispatcher, invoiceId, "LOAN_PRINCIPAL", principal,
                        principalReceivableGlAccountId, userLogin);
            }
            if (interestFromAccrual.compareTo(BigDecimal.ZERO) > 0) {
                createInvoiceItem(dispatcher, invoiceId, "INV_INTRST_CHRG", interestFromAccrual,
                        accruedInterestGlAccountId, userLogin);
            }
            if (interestRecognizedAtBilling.compareTo(BigDecimal.ZERO) > 0) {
                createInvoiceItem(dispatcher, invoiceId, "INV_INTRST_CHRG", interestRecognizedAtBilling,
                        interestIncomeGlAccountId, userLogin);
            }
            BigDecimal billed = principal.add(interest).setScale(SCALE, ROUNDING);

            GenericValue link = delegator.makeValue("LoanInstallmentInvoice");
            link.set("loanAgreementId", loanAgreementId);
            link.set("installmentNumber", installmentNumber);
            link.set("invoiceId", invoiceId);
            link.set("loanQuoteId", agreement.getString("loanQuoteId"));
            link.set("dueDate", dueDate);
            link.set("principalAmount", principal);
            link.set("interestAmount", interest);
            link.set("accruedInterestAppliedAmount", interestFromAccrual);
            link.set("billedAmount", billed);
            link.set("createdDate", UtilDateTime.nowTimestamp());
            link.create();

            Map<String, Object> readyResult = dispatcher.runSync("setInvoiceStatus",
                    UtilMisc.toMap("invoiceId", invoiceId, "statusId", "INVOICE_READY",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(readyResult)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(readyResult));
            }
            postUnpostedTransactions(delegator, dispatcher, "invoiceId", invoiceId, userLogin);
            return invoiceResult(invoiceId, billed, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Receive a positive payment and optionally apply it to one loan invoice. */
    public static Map<String, Object> receiveLoanPayment(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String loanAgreementId = (String) context.get("loanAgreementId");
        String invoiceId = (String) context.get("invoiceId");
        String paymentMethodTypeId = (String) context.get("paymentMethodTypeId");
        if (UtilValidate.isEmpty(paymentMethodTypeId)) {
            paymentMethodTypeId = "EFT_ACCOUNT";
        }
        BigDecimal amount = (BigDecimal) context.get("amount");
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
                return agreementNotActive(loanAgreementId, locale);
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE,
                        "FinancePaymentAmountInvalid", locale));
            }

            String currencyUomId = agreement.getString("currencyUomId");
            String borrowerPartyId = agreement.getString("borrowerPartyId");
            if (UtilValidate.isNotEmpty(invoiceId)) {
                GenericValue installmentLink = EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                        .where("loanAgreementId", loanAgreementId, "invoiceId", invoiceId).queryFirst();
                GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                        .where("invoiceId", invoiceId).queryOne();
                boolean invalidStatus = invoice != null
                        && ("INVOICE_CANCELLED".equals(invoice.getString("statusId"))
                                || "INVOICE_WRITEOFF".equals(invoice.getString("statusId")));
                boolean mismatched = installmentLink == null || invoice == null || invalidStatus
                        || !borrowerPartyId.equals(invoice.getString("partyId"))
                        || !currencyUomId.equals(invoice.getString("currencyUomId"));
                BigDecimal remaining = invoice == null
                        ? BigDecimal.ZERO : InvoiceWorker.getInvoiceNotApplied(invoice);
                if (mismatched || amount.compareTo(remaining) > 0) {
                    return paymentInvoiceMismatch(invoiceId, loanAgreementId, locale);
                }
            }
            String organizationPartyId = getOrganizationPartyId(agreement);

            Map<String, Object> payInput = new HashMap<>();
            payInput.put("paymentTypeId", "CUSTOMER_PAYMENT");
            payInput.put("partyIdFrom", borrowerPartyId);
            payInput.put("partyIdTo", organizationPartyId);
            payInput.put("statusId", "PMNT_RECEIVED");
            payInput.put("paymentMethodTypeId", paymentMethodTypeId);
            payInput.put("amount", amount);
            payInput.put("currencyUomId", currencyUomId);
            payInput.put("effectiveDate", effectiveDate);
            payInput.put("userLogin", userLogin);
            Map<String, Object> payResult = dispatcher.runSync("createPayment", payInput);
            if (ServiceUtil.isError(payResult)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(payResult));
            }
            String paymentId = (String) payResult.get("paymentId");

            if (UtilValidate.isNotEmpty(invoiceId)) {
                Map<String, Object> appInput = new HashMap<>();
                appInput.put("paymentId", paymentId);
                appInput.put("invoiceId", invoiceId);
                appInput.put("amountApplied", amount);
                appInput.put("userLogin", userLogin);
                Map<String, Object> appResult = dispatcher.runSync("createPaymentApplication", appInput);
                if (ServiceUtil.isError(appResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(appResult));
                }
            }
            postUnpostedTransactions(delegator, dispatcher, "paymentId", paymentId, userLogin);

            String loanPaymentReceiptId = delegator.getNextSeqId("LoanPaymentReceipt");
            GenericValue receipt = delegator.makeValue("LoanPaymentReceipt");
            receipt.set("loanPaymentReceiptId", loanPaymentReceiptId);
            receipt.set("loanAgreementId", loanAgreementId);
            receipt.set("paymentId", paymentId);
            receipt.set("invoiceId", invoiceId);
            receipt.set("amount", amount);
            receipt.set("currencyUomId", currencyUomId);
            receipt.set("receiptDate", effectiveDate);
            receipt.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentId", paymentId);
            result.put("loanPaymentReceiptId", loanPaymentReceiptId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static AccrualComputation computeAccrual(Delegator delegator, GenericValue agreement,
            Timestamp fromDate, Timestamp thruDate, BigDecimal annualRate) throws GenericEntityException {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate firstDay = fromDate.toInstant().atZone(zone).toLocalDate();
        LocalDate lastDay = thruDate.toInstant().atZone(zone).toLocalDate();
        List<PrincipalPaymentEvent> events = getPrincipalPaymentEvents(delegator, agreement);
        BigDecimal outstanding = defaultZero(agreement.getBigDecimal("principalAmount"));
        BigDecimal accrued = BigDecimal.ZERO;
        int eventIndex = 0;

        while (eventIndex < events.size() && events.get(eventIndex).date.isBefore(firstDay)) {
            outstanding = outstanding.subtract(events.get(eventIndex).principalAmount).max(BigDecimal.ZERO);
            eventIndex++;
        }
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            while (eventIndex < events.size() && !events.get(eventIndex).date.isAfter(day)) {
                outstanding = outstanding.subtract(events.get(eventIndex).principalAmount).max(BigDecimal.ZERO);
                eventIndex++;
            }
            BigDecimal dailyInterest = outstanding.multiply(annualRate)
                    .divide(new BigDecimal("365"), 12, ROUNDING);
            accrued = accrued.add(dailyInterest);
        }
        return new AccrualComputation(outstanding.setScale(SCALE, ROUNDING),
                accrued.setScale(SCALE, ROUNDING));
    }

    private static List<PrincipalPaymentEvent> getPrincipalPaymentEvents(Delegator delegator,
            GenericValue agreement) throws GenericEntityException {
        List<PrincipalPaymentEvent> events = new ArrayList<>();
        List<GenericValue> installments = EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                .where("loanAgreementId", agreement.getString("loanAgreementId")).queryList();
        for (GenericValue installment : installments) {
            List<PaymentApplicationValue> applications = new ArrayList<>();
            for (GenericValue application : EntityQuery.use(delegator).from("PaymentApplication")
                    .where("invoiceId", installment.getString("invoiceId")).queryList()) {
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
            BigDecimal cumulativePaid = BigDecimal.ZERO;
            BigDecimal previousPrincipalPaid = BigDecimal.ZERO;
            BigDecimal principalAmount = defaultZero(installment.getBigDecimal("principalAmount"));
            BigDecimal interestAmount = defaultZero(installment.getBigDecimal("interestAmount"));
            for (PaymentApplicationValue application : applications) {
                cumulativePaid = cumulativePaid.add(application.amount);
                BigDecimal principalPaid = cumulativePaid.subtract(interestAmount).max(BigDecimal.ZERO)
                        .min(principalAmount);
                BigDecimal principalDelta = principalPaid.subtract(previousPrincipalPaid);
                if (principalDelta.compareTo(BigDecimal.ZERO) > 0) {
                    LocalDate effectiveDay = application.effectiveDate.toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDate();
                    events.add(new PrincipalPaymentEvent(effectiveDay, principalDelta));
                }
                previousPrincipalPaid = principalPaid;
            }
        }
        events.sort(Comparator.comparing(value -> value.date));
        return events;
    }

    private static BigDecimal getAccruedInterestAvailable(Delegator delegator,
            String loanAgreementId, Timestamp dueDate) throws GenericEntityException {
        Timestamp dueEnd = endOfDay(dueDate);
        BigDecimal accrued = BigDecimal.ZERO;
        for (GenericValue row : EntityQuery.use(delegator).from("LoanAccrual")
                .where("loanAgreementId", loanAgreementId).queryList()) {
            Timestamp accrualThru = row.getTimestamp("thruDate");
            if (accrualThru != null && !accrualThru.after(dueEnd)) {
                accrued = accrued.add(defaultZero(row.getBigDecimal("interestAmount")));
            }
        }
        BigDecimal alreadyApplied = BigDecimal.ZERO;
        for (GenericValue row : EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                .where("loanAgreementId", loanAgreementId).queryList()) {
            alreadyApplied = alreadyApplied.add(
                    defaultZero(row.getBigDecimal("accruedInterestAppliedAmount")));
        }
        return accrued.subtract(alreadyApplied).max(BigDecimal.ZERO).setScale(SCALE, ROUNDING);
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
            EntityCondition versionCondition = EntityCondition.makeCondition("processingLockVersion",
                    EntityOperator.EQUALS, currentVersion);
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("loanAgreementId", EntityOperator.EQUALS, loanAgreementId),
                    versionCondition), EntityOperator.AND);
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

    private static void postUnpostedTransactions(Delegator delegator, LocalDispatcher dispatcher,
            String fieldName, String fieldValue, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        for (GenericValue transaction : EntityQuery.use(delegator).from("AcctgTrans")
                .where(fieldName, fieldValue).cache(false).queryList()) {
            if (!"Y".equals(transaction.getString("isPosted"))) {
                Map<String, Object> result = dispatcher.runSync("postAcctgTrans",
                        UtilMisc.toMap("acctgTransId", transaction.getString("acctgTransId"),
                                "userLogin", userLogin));
                if (ServiceUtil.isError(result)) {
                    throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
                }
            }
        }
    }

    private static Timestamp endOfDay(Timestamp value) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate date = value.toInstant().atZone(zone).toLocalDate();
        return Timestamp.from(date.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1));
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

    private static void createInvoiceItem(LocalDispatcher dispatcher, String invoiceId,
            String invoiceItemTypeId, BigDecimal amount, String overrideGlAccountId,
            GenericValue userLogin) throws GenericServiceException {
        Map<String, Object> itemInput = new HashMap<>();
        itemInput.put("invoiceId", invoiceId);
        itemInput.put("invoiceItemTypeId", invoiceItemTypeId);
        itemInput.put("quantity", BigDecimal.ONE);
        itemInput.put("amount", amount);
        itemInput.put("overrideGlAccountId", overrideGlAccountId);
        itemInput.put("userLogin", userLogin);
        Map<String, Object> result = dispatcher.runSync("createInvoiceItem", itemInput);
        if (ServiceUtil.isError(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    private static Map<String, Object> accrualResult(GenericValue accrual, boolean created) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("loanAccrualId", accrual.getString("loanAccrualId"));
        result.put("acctgTransId", accrual.getString("acctgTransId"));
        result.put("interestAmount", accrual.getBigDecimal("interestAmount"));
        result.put("outstandingPrincipal", accrual.getBigDecimal("outstandingPrincipal"));
        result.put("accrualCreated", created);
        return result;
    }

    private static Map<String, Object> invoiceResult(String invoiceId,
            BigDecimal billedAmount, boolean created) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("invoiceId", invoiceId);
        result.put("billedAmount", billedAmount);
        result.put("invoiceCreated", created);
        return result;
    }

    private static Map<String, Object> recordNotFound(String entityName, String id, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceRecordNotFound",
                UtilMisc.toMap("entityName", entityName, "id", id), locale));
    }

    private static Map<String, Object> agreementNotActive(String loanAgreementId, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceAgreementNotActive",
                UtilMisc.toMap("loanAgreementId", loanAgreementId), locale));
    }

    private static Map<String, Object> glMappingMissing(String organizationPartyId,
            String glAccountTypeId, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceGlAccountMappingMissing",
                UtilMisc.toMap("organizationPartyId", organizationPartyId,
                        "glAccountTypeId", glAccountTypeId), locale));
    }

    private static Map<String, Object> paymentInvoiceMismatch(String invoiceId,
            String loanAgreementId, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinancePaymentInvoiceMismatch",
                UtilMisc.toMap("invoiceId", invoiceId, "loanAgreementId", loanAgreementId), locale));
    }

    private static Map<String, Object> batchError(String loanAgreementId, String cause, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceMonthlyBatchFailed",
                UtilMisc.toMap("loanAgreementId", loanAgreementId, "cause", cause), locale));
    }

    private static final class AccrualComputation {
        private final BigDecimal outstandingPrincipal;
        private final BigDecimal interestAmount;

        private AccrualComputation(BigDecimal outstandingPrincipal, BigDecimal interestAmount) {
            this.outstandingPrincipal = outstandingPrincipal;
            this.interestAmount = interestAmount;
        }
    }

    private static final class PrincipalPaymentEvent {
        private final LocalDate date;
        private final BigDecimal principalAmount;

        private PrincipalPaymentEvent(LocalDate date, BigDecimal principalAmount) {
            this.date = date;
            this.principalAmount = principalAmount;
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

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
