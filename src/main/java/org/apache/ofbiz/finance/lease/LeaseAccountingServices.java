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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Lease monthly accounting (STEP L4): installment billing from the confirmed
 * lease payment schedule, release of the deferred lease profit (interest method),
 * the monthly batch and the receipt / application of customer payments.
 *
 * <ul>
 * <li>Billing: one core SALES_INVOICE per installment (READY). The lease payment item
 *     credits the account given by the revenue strategy (method 1: the lease
 *     investment / receivable booked at the start), the tax item credits the
 *     consumption tax received account; the core debits accounts receivable.
 *     No sales are booked at billing.</li>
 * <li>Recognition: the cumulative interest earned through a day
 *     ({@link LeaseProfitAllocator}) minus what is already recognized, one record and
 *     one transaction per calendar month (Dr deferred lease profit / Cr release).</li>
 * <li>Payments: core CUSTOMER_PAYMENT (PMNT_RECEIVED) applied to the lease invoices;
 *     the collection of an invoice is allocated tax first, then interest, then principal.</li>
 * </ul>
 * Every operation of one agreement is serialized with {@link FinanceContractHelper#lock}.
 */
public final class LeaseAccountingServices {

    private static final String MODULE = LeaseAccountingServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String ITEM_LEASE_PAYMENT = "LEASE_PAYMENT";
    public static final String ITEM_CONS_TAX = "LEASE_CONS_TAX";
    public static final String ITEM_CORE_SALES_TAX = "ITM_SALES_TAX";
    public static final String ITEM_COMPONENT = "LEASE_COMPONENT";
    public static final String ITEM_COMPONENT_TAX = "LEASE_COMP_TAX";

    private LeaseAccountingServices() {
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    /** Calendar day of a timestamp (system time zone). */
    public static LocalDate toDate(Timestamp ts) {
        return ts.toLocalDateTime().toLocalDate();
    }

    /** Start of a calendar day (system time zone). */
    public static Timestamp toTimestamp(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    /** Lock the contract of the agreement and re-read it; null when it does not exist. */
    private static GenericValue lockAgreement(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        GenericValue agreement = findAgreement(delegator, leaseAgreementId);
        if (agreement == null) {
            return null;
        }
        FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
        return findAgreement(delegator, leaseAgreementId);
    }

    private static boolean isActive(GenericValue agreement) {
        return "LEASEAGR_ACTIVE".equals(agreement.getString("statusId"));
    }

    /** Highest revision of the confirmed schedule (the one dated from the lease start). */
    public static long currentScheduleRevision(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        GenericValue latest = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId).orderBy("-scheduleRevisionNumber").queryFirst();
        return latest == null ? 1L : latest.getLong("scheduleRevisionNumber");
    }

    private static List<GenericValue> currentSchedule(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId,
                        "scheduleRevisionNumber", currentScheduleRevision(delegator, leaseAgreementId))
                .orderBy("installmentNumber").queryList();
    }

    /** Allocator of the interest equivalent over the lease periods of the current schedule. */
    public static LeaseProfitAllocator allocator(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        List<LeaseProfitAllocator.Installment> rows = new ArrayList<>();
        for (GenericValue row : currentSchedule(delegator, agreement.getString("leaseAgreementId"))) {
            rows.add(new LeaseProfitAllocator.Installment(row.getLong("installmentNumber").intValue(),
                    toDate(row.getTimestamp("periodFromDate")), toDate(row.getTimestamp("periodThruDate")),
                    row.getBigDecimal("interestPortion")));
        }
        boolean advance = LeaseProductHelper.TIMING_ADVANCE.equals(agreement.getString("paymentTimingEnumId"));
        String currency = agreement.getString("currencyUomId");
        int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
        RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
        return new LeaseProfitAllocator(LeaseProfitAllocator.segments(rows, advance), scale, mode);
    }

    private static String glAccount(Delegator delegator, String organizationPartyId, String glAccountTypeId)
            throws GenericEntityException, GenericServiceException {
        String glAccountId = FinanceGlHelper.findGlAccountId(delegator, organizationPartyId, glAccountTypeId);
        if (glAccountId == null) {
            throw new GenericServiceException("No GL account of type " + glAccountTypeId
                    + " is configured for organization " + organizationPartyId);
        }
        return glAccountId;
    }

    private static boolean isValidPayment(GenericValue payment) {
        return payment != null && ("PMNT_RECEIVED".equals(payment.getString("statusId"))
                || "PMNT_CONFIRMED".equals(payment.getString("statusId")));
    }

    // ------------------------------------------------------------------
    // Billing
    // ------------------------------------------------------------------

    /** Bill one installment of the confirmed schedule (idempotent per installment). */
    public static Map<String, Object> createLeaseInstallmentInvoice(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Long installmentNumber = (Long) context.get("installmentNumber");
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!isActive(agreement)) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            GenericValue existing = EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                    .where("leaseAgreementId", leaseAgreementId, "installmentNumber", installmentNumber).queryOne();
            if (existing != null) {
                return invoiceResult(existing.getString("invoiceId"), existing.getBigDecimal("billedAmount"), false);
            }
            long revision = currentScheduleRevision(delegator, leaseAgreementId);
            GenericValue row = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                    .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", revision,
                            "installmentNumber", installmentNumber).queryOne();
            if (row == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreementPaymentSchedule",
                        "id", leaseAgreementId + "/" + installmentNumber), locale);
            }
            Timestamp dueDate = row.getTimestamp("dueDate");
            if (dueDate == null || dueDate.after(UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp()))) {
                return error("FinanceLeaseInstallmentNotDue", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "installmentNumber", installmentNumber), locale);
            }
            LeaseRevenueStrategy strategy = LeaseRevenueStrategy.REGISTRY.get(agreement.getString("revenueMethodEnumId"));
            if (strategy == null) {
                return error("FinanceLeaseRevenueMethodUnsupported",
                        UtilMisc.toMap("revenueMethodEnumId", agreement.getString("revenueMethodEnumId")), locale);
            }
            String organizationPartyId = agreement.getString("organizationPartyId");
            BigDecimal leaseAmount = nz(row.getBigDecimal("leaseAmount"));
            BigDecimal taxAmount = nz(row.getBigDecimal("taxAmount"));
            String leaseGl = glAccount(delegator, organizationPartyId, strategy.billingCreditAccountType(agreement));
            // STEP L5: the tax line follows the timing fixed at the start.
            //  - per installment with a core tax authority: ITM_SALES_TAX (the core credits TaxAuthorityGlAccount)
            //  - per installment without authority: LEASE_CONS_TAX to tax received (STEP L4)
            //  - lump sum: LEASE_CONS_TAX collecting the tax receivable booked at the start
            boolean lumpSum = LeaseTaxHelper.TIMING_AT_START.equals(agreement.getString("taxTimingEnumId"));
            boolean coreTax = !lumpSum && UtilValidate.isNotEmpty(agreement.getString("taxAuthPartyId"))
                    && UtilValidate.isNotEmpty(agreement.getString("taxAuthGeoId"));
            String taxGl = taxAmount.signum() > 0 && !coreTax
                    ? glAccount(delegator, organizationPartyId, lumpSum
                            ? LeaseRevenueStrategy.GL_TAX_RECEIVABLE : LeaseRevenueStrategy.GL_TAX_PAYABLE) : null;

            Map<String, Object> invInput = new HashMap<>();
            invInput.put("invoiceTypeId", "SALES_INVOICE");
            invInput.put("partyIdFrom", organizationPartyId);
            invInput.put("partyId", agreement.getString("lesseePartyId"));
            invInput.put("statusId", "INVOICE_IN_PROCESS");
            invInput.put("currencyUomId", agreement.getString("currencyUomId"));
            invInput.put("invoiceDate", UtilDateTime.nowTimestamp());
            invInput.put("dueDate", dueDate);
            invInput.put("description", "Lease " + leaseAgreementId + " #" + installmentNumber);
            invInput.put("userLogin", userLogin);
            Map<String, Object> inv = dispatcher.runSync("createInvoice", invInput);
            check(inv);
            String invoiceId = (String) inv.get("invoiceId");
            createItem(dispatcher, invoiceId, ITEM_LEASE_PAYMENT, leaseAmount, leaseGl,
                    "Lease payment #" + installmentNumber, userLogin);
            if (taxAmount.signum() > 0 && coreTax) {
                Map<String, Object> taxItem = new HashMap<>();
                taxItem.put("invoiceId", invoiceId);
                taxItem.put("invoiceItemTypeId", ITEM_CORE_SALES_TAX);
                taxItem.put("quantity", BigDecimal.ONE);
                taxItem.put("amount", taxAmount);
                taxItem.put("description", "Consumption tax #" + installmentNumber);
                taxItem.put("taxAuthPartyId", agreement.getString("taxAuthPartyId"));
                taxItem.put("taxAuthGeoId", agreement.getString("taxAuthGeoId"));
                taxItem.put("taxAuthorityRateSeqId", agreement.getString("taxAuthorityRateSeqId"));
                taxItem.put("userLogin", userLogin);
                check(dispatcher.runSync("createInvoiceItem", taxItem));
            } else if (taxAmount.signum() > 0) {
                createItem(dispatcher, invoiceId, ITEM_CONS_TAX, taxAmount, taxGl,
                        "Consumption tax #" + installmentNumber, userLogin);
            }
            BigDecimal billed = leaseAmount.add(taxAmount);

            // STEP L9: payment components. Separate (蛹ｺ蛻・ components credit the lease
            // component revenue account (revenue recognized at billing); their taxable
            // (隱ｲ遞・ consumption tax credits the tax received account (or the core tax
            // authority when the contract has one, per installment). Included (蜷ｫ繧√ｋ)
            // components are folded into the lease payment item (credit the asset account,
            // no new sales), as they were part of the method-1 sales at the start.
            // STEP L11: separate components are credited to the revenue account of their
            // component type (LeaseComponentRevenueAccount, e.g. maintenance -> 菫晏ｮ域侭蜿守寢),
            // else the generic component revenue account; one item per revenue account.
            BigDecimal componentAmount = BigDecimal.ZERO;
            BigDecimal componentTax = BigDecimal.ZERO;
            Map<String, BigDecimal> separateByGlType = new java.util.LinkedHashMap<>();
            BigDecimal includedAmount = BigDecimal.ZERO;
            BigDecimal separateTax = BigDecimal.ZERO;
            for (GenericValue comp : EntityQuery.use(delegator).from("LeaseAgrPaySchedComp")
                    .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", revision,
                            "installmentNumber", installmentNumber).orderBy("componentSeqId").queryList()) {
                BigDecimal amount = nz(comp.getBigDecimal("amount"));
                BigDecimal tax = nz(comp.getBigDecimal("taxAmount"));
                componentAmount = componentAmount.add(amount);
                componentTax = componentTax.add(tax);
                boolean separate = !LeaseComponentCalculator.REV_INCLUDED.equals(comp.getString("revenueTreatmentEnumId"));
                if (separate) {
                    String glType = LeaseComponentSettings.revenueAccountType(delegator, comp.getString("componentTypeEnumId"));
                    separateByGlType.merge(glType, amount, BigDecimal::add);
                    separateTax = separateTax.add(tax);
                } else {
                    includedAmount = includedAmount.add(amount);
                }
            }
            for (Map.Entry<String, BigDecimal> sep : separateByGlType.entrySet()) {
                if (sep.getValue().signum() > 0) {
                    createItem(dispatcher, invoiceId, ITEM_COMPONENT, sep.getValue(),
                            glAccount(delegator, organizationPartyId, sep.getKey()),
                            "Lease components #" + installmentNumber, userLogin);
                }
            }
            if (includedAmount.signum() > 0) {
                createItem(dispatcher, invoiceId, ITEM_COMPONENT, includedAmount, leaseGl,
                        "Lease components (included) #" + installmentNumber, userLogin);
            }
            if (separateTax.signum() > 0) {
                if (coreTax) {
                    Map<String, Object> compTaxItem = new HashMap<>();
                    compTaxItem.put("invoiceId", invoiceId);
                    compTaxItem.put("invoiceItemTypeId", ITEM_CORE_SALES_TAX);
                    compTaxItem.put("quantity", BigDecimal.ONE);
                    compTaxItem.put("amount", separateTax);
                    compTaxItem.put("description", "Component consumption tax #" + installmentNumber);
                    compTaxItem.put("taxAuthPartyId", agreement.getString("taxAuthPartyId"));
                    compTaxItem.put("taxAuthGeoId", agreement.getString("taxAuthGeoId"));
                    compTaxItem.put("taxAuthorityRateSeqId", agreement.getString("taxAuthorityRateSeqId"));
                    compTaxItem.put("userLogin", userLogin);
                    check(dispatcher.runSync("createInvoiceItem", compTaxItem));
                } else {
                    createItem(dispatcher, invoiceId, ITEM_COMPONENT_TAX, separateTax,
                            glAccount(delegator, organizationPartyId, LeaseRevenueStrategy.GL_TAX_PAYABLE),
                            "Component consumption tax #" + installmentNumber, userLogin);
                }
            }
            billed = billed.add(componentAmount).add(componentTax);

            GenericValue link = delegator.makeValue("LeaseInstallmentInvoice");
            link.set("leaseAgreementId", leaseAgreementId);
            link.set("installmentNumber", installmentNumber);
            link.set("scheduleRevisionNumber", revision);
            link.set("invoiceId", invoiceId);
            link.set("dueDate", dueDate);
            link.set("leaseAmount", leaseAmount);
            link.set("principalPortion", row.getBigDecimal("principalPortion"));
            link.set("interestPortion", row.getBigDecimal("interestPortion"));
            link.set("taxAmount", taxAmount);
            link.set("componentAmount", componentAmount);
            link.set("componentTaxAmount", componentTax);
            link.set("billedAmount", billed);
            link.set("createdDate", UtilDateTime.nowTimestamp());
            link.create();

            check(dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap("invoiceId", invoiceId,
                    "statusId", "INVOICE_READY", "userLogin", userLogin)));
            FinanceGlHelper.postUnposted(delegator, dispatcher, "invoiceId", invoiceId, userLogin);
            // STEP L15c: advance lease payments are applied to the new invoice (oldest receipt first).
            LeaseAdvanceServices.applyAdvances(dctx, agreement, userLogin);
            return invoiceResult(invoiceId, billed, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void createItem(LocalDispatcher dispatcher, String invoiceId, String itemTypeId, BigDecimal amount,
            String glAccountId, String description, GenericValue userLogin) throws GenericServiceException {
        Map<String, Object> input = new HashMap<>();
        input.put("invoiceId", invoiceId);
        input.put("invoiceItemTypeId", itemTypeId);
        input.put("quantity", BigDecimal.ONE);
        input.put("amount", amount);
        input.put("description", description);
        input.put("overrideGlAccountId", glAccountId);
        input.put("userLogin", userLogin);
        check(dispatcher.runSync("createInvoiceItem", input));
    }

    private static Map<String, Object> invoiceResult(String invoiceId, BigDecimal billed, boolean created) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("invoiceId", invoiceId);
        result.put("billedAmount", billed);
        result.put("invoiceCreated", created);
        return result;
    }

    // ------------------------------------------------------------------
    // Deferred profit release
    // ------------------------------------------------------------------

    /**
     * Recognize the interest equivalent earned from the day after the last
     * recognition through the recognition date (capped at the last earning day),
     * one record per calendar month. Re-running for a day already recognized
     * creates nothing.
     */
    public static Map<String, Object> recognizeLeaseProfit(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp recognitionDate = context.get("recognitionDate") != null
                ? (Timestamp) context.get("recognitionDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!isActive(agreement)) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            LeaseRevenueStrategy strategy = LeaseRevenueStrategy.REGISTRY.get(agreement.getString("revenueMethodEnumId"));
            if (strategy == null) {
                return error("FinanceLeaseRevenueMethodUnsupported",
                        UtilMisc.toMap("revenueMethodEnumId", agreement.getString("revenueMethodEnumId")), locale);
            }
            if (!strategy.defersProfit()) {
                // STEP L12: no deferred profit (operating lease revenue is booked at billing).
                Map<String, Object> none = ServiceUtil.returnSuccess();
                none.put("recognizedAmount", BigDecimal.ZERO);
                none.put("cumulativeAmount", BigDecimal.ZERO);
                none.put("createdCount", 0L);
                return none;
            }
            LocalDate start = toDate(agreement.getTimestamp("leaseStartDate"));
            LocalDate requested = toDate(recognitionDate);
            if (requested.isBefore(start)) {
                return error("FinanceLeaseRecognitionBeforeStart", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "recognitionDate", toTimestamp(requested)), locale);
            }
            LeaseProfitAllocator allocator = allocator(delegator, agreement);
            LocalDate lastEarning = allocator.lastEarningDay();
            LocalDate target = lastEarning != null && requested.isAfter(lastEarning) ? lastEarning : requested;

            GenericValue last = EntityQuery.use(delegator).from("LeaseProfitRecognition")
                    .where("leaseAgreementId", leaseAgreementId).orderBy("-thruDate").queryFirst();
            LocalDate from = last == null ? start : toDate(last.getTimestamp("thruDate")).plusDays(1);
            BigDecimal cumulative = last == null ? BigDecimal.ZERO : nz(last.getBigDecimal("cumulativeAmount"));

            BigDecimal recognized = BigDecimal.ZERO;
            long createdCount = 0;
            String lastId = last == null ? null : last.getString("leaseProfitRecognitionId");
            while (lastEarning != null && !from.isAfter(target)) {
                LocalDate monthEnd = from.withDayOfMonth(from.lengthOfMonth());
                LocalDate thru = monthEnd.isAfter(target) ? target : monthEnd;
                BigDecimal newCumulative = allocator.cumulativeThrough(thru);
                BigDecimal amount = newCumulative.subtract(cumulative);
                String acctgTransId = null;
                if (amount.signum() != 0) {
                    acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                            "Lease profit release " + leaseAgreementId + " " + from + ".." + thru, toTimestamp(thru),
                            agreement.getString("organizationPartyId"), agreement.getString("lesseePartyId"),
                            agreement.getString("currencyUomId"), strategy.recognitionEntries(agreement, amount), userLogin);
                }
                lastId = delegator.getNextSeqId("LeaseProfitRecognition");
                GenericValue rec = delegator.makeValue("LeaseProfitRecognition");
                rec.set("leaseProfitRecognitionId", lastId);
                rec.set("leaseAgreementId", leaseAgreementId);
                rec.set("fromDate", toTimestamp(from));
                rec.set("thruDate", toTimestamp(thru));
                rec.set("amount", amount);
                rec.set("cumulativeAmount", newCumulative);
                rec.set("currencyUomId", agreement.getString("currencyUomId"));
                rec.set("acctgTransId", acctgTransId);
                rec.set("createdDate", UtilDateTime.nowTimestamp());
                rec.create();
                recognized = recognized.add(amount);
                cumulative = newCumulative;
                createdCount++;
                from = thru.plusDays(1);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("leaseProfitRecognitionId", lastId);
            result.put("recognizedAmount", recognized);
            result.put("cumulativeAmount", cumulative);
            result.put("createdCount", createdCount);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Monthly batch
    // ------------------------------------------------------------------

    /**
     * For each ACTIVE lease (or the given one): bill every installment due through
     * the processing date in due-date order, then recognize the deferred lease profit
     * through the processing date. Re-running creates nothing twice.
     */
    public static Map<String, Object> runLeaseMonthlyAccountingBatch(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String requestedId = (String) context.get("leaseAgreementId");
        Timestamp processingDate = context.get("processingDate") != null
                ? (Timestamp) context.get("processingDate") : UtilDateTime.nowTimestamp();
        long processed = 0;
        long invoices = 0;
        long recognitions = 0;
        long depreciations = 0;
        try {
            List<GenericValue> agreements = new ArrayList<>();
            if (UtilValidate.isNotEmpty(requestedId)) {
                GenericValue agreement = findAgreement(delegator, requestedId);
                if (agreement == null) {
                    return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", requestedId), locale);
                }
                if (!isActive(agreement)) {
                    return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", requestedId), locale);
                }
                agreements.add(agreement);
            } else {
                agreements = EntityQuery.use(delegator).from("LeaseAgreement").where("statusId", "LEASEAGR_ACTIVE")
                        .orderBy("leaseAgreementId").queryList();
            }
            for (GenericValue agreement : agreements) {
                // Each agreement in its own transaction (internal service, require-new-transaction):
                // one failing or long agreement neither times out nor rolls back the others.
                Map<String, Object> one = dctx.getDispatcher().runSync("runLeaseAgreementMonthlyAccounting", UtilMisc.toMap(
                        "leaseAgreementId", agreement.getString("leaseAgreementId"), "processingDate", processingDate,
                        "userLogin", userLogin, "locale", locale));
                if (ServiceUtil.isError(one)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(one));
                }
                invoices += (Long) one.get("createdInvoiceCount");
                recognitions += (Long) one.get("createdRecognitionCount");
                depreciations += one.get("createdDepreciationCount") == null ? 0L : (Long) one.get("createdDepreciationCount");
                processed++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("processedAgreementCount", processed);
            result.put("createdInvoiceCount", invoices);
            result.put("createdRecognitionCount", recognitions);
            result.put("createdDepreciationCount", depreciations);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Monthly accounting of one ACTIVE agreement (internal service of the batch):
     * bill every installment due through the processing date in due-date order, then
     * recognize the deferred lease profit through the processing date.
     */
    public static Map<String, Object> runLeaseAgreementMonthlyAccounting(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp processingDate = context.get("processingDate") != null
                ? (Timestamp) context.get("processingDate") : UtilDateTime.nowTimestamp();
        Timestamp processingEnd = UtilDateTime.getDayEnd(processingDate);
        long invoices = 0;
        try {
            {
                List<GenericValue> rows = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                        .where("leaseAgreementId", leaseAgreementId,
                                "scheduleRevisionNumber", currentScheduleRevision(delegator, leaseAgreementId))
                        .orderBy("dueDate", "installmentNumber").queryList();
                for (GenericValue row : rows) {
                    Timestamp due = row.getTimestamp("dueDate");
                    if (due == null || due.after(processingEnd)) {
                        continue;
                    }
                    Map<String, Object> bill = createLeaseInstallmentInvoice(dctx, UtilMisc.toMap(
                            "leaseAgreementId", leaseAgreementId, "installmentNumber", row.getLong("installmentNumber"),
                            "userLogin", userLogin, "locale", locale));
                    if (ServiceUtil.isError(bill)) {
                        return batchError(leaseAgreementId, ServiceUtil.getErrorMessage(bill), locale);
                    }
                    if (Boolean.TRUE.equals(bill.get("invoiceCreated"))) {
                        invoices++;
                    }
                }
                Map<String, Object> rec = recognizeLeaseProfit(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "recognitionDate", processingDate, "userLogin", userLogin, "locale", locale));
                if (ServiceUtil.isError(rec)) {
                    return batchError(leaseAgreementId, ServiceUtil.getErrorMessage(rec), locale);
                }
                // STEP L12: depreciation of the leased assets of an operating lease.
                long depreciations = 0;
                GenericValue agreement = findAgreement(delegator, leaseAgreementId);
                LeaseRevenueStrategy strategy = agreement == null ? null
                        : LeaseRevenueStrategy.REGISTRY.get(agreement.getString("revenueMethodEnumId"));
                if (strategy != null && strategy.depreciatesAsset()) {
                    Map<String, Object> dep = LeaseDepreciationServices.depreciateLeaseAssets(dctx, UtilMisc.toMap(
                            "leaseAgreementId", leaseAgreementId, "depreciationDate", processingDate,
                            "userLogin", userLogin, "locale", locale));
                    if (ServiceUtil.isError(dep)) {
                        return batchError(leaseAgreementId, ServiceUtil.getErrorMessage(dep), locale);
                    }
                    depreciations = (Long) dep.get("createdCount");
                }
                // STEP L13: re-lease periods (start / billing, monthly fee recognition, depreciation).
                if (agreement != null && "LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                    long[] rel = LeaseReLeaseServices.processReLeases(dctx, agreement, processingDate, userLogin);
                    invoices += rel[0];
                    depreciations += rel[2];
                }
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("createdInvoiceCount", invoices);
                result.put("createdRecognitionCount", rec.get("createdCount"));
                result.put("createdDepreciationCount", depreciations);
                return result;
            }
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> batchError(String leaseAgreementId, String cause, Locale locale) {
        return error("FinanceLeaseMonthlyBatchFailed", UtilMisc.toMap("leaseAgreementId", leaseAgreementId, "cause", cause), locale);
    }

    // ------------------------------------------------------------------
    // Payment receipt and application
    // ------------------------------------------------------------------

    /**
     * Every invoice of a lease as {@code [invoiceId, dueDate]}, oldest due first: the installment
     * invoices, the re-lease invoices (STEP L13) and the termination damages invoice (STEP L14).
     */
    public static List<Object[]> invoiceTargets(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        List<Object[]> targets = new ArrayList<>();
        for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", leaseAgreementId).orderBy("dueDate", "installmentNumber").queryList()) {
            targets.add(new Object[] {link.getString("invoiceId"), link.getTimestamp("dueDate")});
        }
        targets.addAll(LeaseReLeaseServices.reLeaseInvoices(delegator, leaseAgreementId));
        Object[] damagesInvoice = LeaseTerminationServices.damagesInvoice(delegator, leaseAgreementId);
        if (damagesInvoice != null) {
            targets.add(damagesInvoice);
        }
        targets.sort((a, b) -> {
            Timestamp x = (Timestamp) a[1];
            Timestamp y = (Timestamp) b[1];
            return x == null ? (y == null ? 0 : 1) : (y == null ? -1 : x.compareTo(y));
        });
        return targets;
    }

    /**
     * Open invoices of a lease that a payment of the lessee can be applied to (not cancelled /
     * written off, lessee and currency of the agreement, amount not applied &gt; 0), oldest due first.
     */
    public static List<GenericValue> openInvoices(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        List<GenericValue> open = new ArrayList<>();
        String lessee = agreement.getString("lesseePartyId");
        String currency = agreement.getString("currencyUomId");
        for (Object[] target : invoiceTargets(delegator, agreement.getString("leaseAgreementId"))) {
            GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                    .where("invoiceId", target[0]).cache(false).queryOne();
            String status = invoice == null ? null : invoice.getString("statusId");
            if (invoice == null || "INVOICE_CANCELLED".equals(status) || "INVOICE_WRITEOFF".equals(status)
                    || "INVOICE_IN_PROCESS".equals(status)
                    || !lessee.equals(invoice.getString("partyId")) || !currency.equals(invoice.getString("currencyUomId"))) {
                continue;
            }
            if (InvoiceWorker.getInvoiceNotApplied(invoice).signum() > 0) {
                open.add(invoice);
            }
        }
        return open;
    }

    /**
     * Receive a customer payment for a lease and apply it to one lease invoice, or
     * (no invoice given) to the open lease invoices oldest due first. The amount may
     * not exceed the open amount of the target invoice(s).
     */
    public static Map<String, Object> receiveLeasePayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String invoiceId = (String) context.get("invoiceId");
        BigDecimal amount = (BigDecimal) context.get("amount");
        String paymentMethodTypeId = UtilValidate.isNotEmpty((String) context.get("paymentMethodTypeId"))
                ? (String) context.get("paymentMethodTypeId") : "EFT_ACCOUNT";
        Timestamp effectiveDate = context.get("effectiveDate") != null
                ? (Timestamp) context.get("effectiveDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            // STEP L14: payments are still received after an early termination.
            if (!LeaseTerminationServices.isCollectible(agreement)) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (amount == null || amount.signum() <= 0) {
                return error("FinancePaymentAmountInvalid", UtilMisc.toMap(), locale);
            }
            String lessee = agreement.getString("lesseePartyId");
            String currency = agreement.getString("currencyUomId");

            // Target invoices and the amount applied to each (oldest due first).
            List<Object[]> targets = invoiceTargets(delegator, leaseAgreementId);
            if (UtilValidate.isNotEmpty(invoiceId)) {
                targets.removeIf(t -> !invoiceId.equals(t[0]));
            }
            if (UtilValidate.isNotEmpty(invoiceId) && targets.isEmpty()) {
                return error("FinanceLeasePaymentInvoiceMismatch", UtilMisc.toMap("invoiceId", invoiceId,
                        "leaseAgreementId", leaseAgreementId), locale);
            }
            List<Object[]> applications = new ArrayList<>();
            BigDecimal remaining = amount;
            BigDecimal open = BigDecimal.ZERO;
            for (Object[] target : targets) {
                GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                        .where("invoiceId", target[0]).cache(false).queryOne();
                String status = invoice == null ? null : invoice.getString("statusId");
                boolean usable = invoice != null && !"INVOICE_CANCELLED".equals(status) && !"INVOICE_WRITEOFF".equals(status)
                        && lessee.equals(invoice.getString("partyId")) && currency.equals(invoice.getString("currencyUomId"));
                if (!usable) {
                    if (UtilValidate.isNotEmpty(invoiceId)) {
                        return error("FinanceLeasePaymentInvoiceMismatch", UtilMisc.toMap("invoiceId", invoiceId,
                                "leaseAgreementId", leaseAgreementId), locale);
                    }
                    continue;
                }
                BigDecimal notApplied = InvoiceWorker.getInvoiceNotApplied(invoice);
                if (notApplied.signum() <= 0) {
                    continue;
                }
                open = open.add(notApplied);
                if (remaining.signum() > 0) {
                    BigDecimal apply = remaining.min(notApplied);
                    applications.add(new Object[] {invoice.getString("invoiceId"), apply});
                    remaining = remaining.subtract(apply);
                }
            }
            if (remaining.signum() > 0) {
                return error("FinanceLeasePaymentExceedsOpen", UtilMisc.toMap("amount", amount, "openAmount", open), locale);
            }

            Map<String, Object> payInput = new HashMap<>();
            payInput.put("paymentTypeId", "CUSTOMER_PAYMENT");
            payInput.put("partyIdFrom", lessee);
            payInput.put("partyIdTo", agreement.getString("organizationPartyId"));
            payInput.put("statusId", "PMNT_RECEIVED");
            payInput.put("paymentMethodTypeId", paymentMethodTypeId);
            payInput.put("amount", amount);
            payInput.put("currencyUomId", currency);
            payInput.put("effectiveDate", effectiveDate);
            payInput.put("comments", "Lease " + leaseAgreementId);
            payInput.put("userLogin", userLogin);
            Map<String, Object> pay = dispatcher.runSync("createPayment", payInput);
            check(pay);
            String paymentId = (String) pay.get("paymentId");
            for (Object[] application : applications) {
                check(dispatcher.runSync("createPaymentApplication", UtilMisc.toMap("paymentId", paymentId,
                        "invoiceId", application[0], "amountApplied", application[1], "userLogin", userLogin)));
            }
            FinanceGlHelper.postUnposted(delegator, dispatcher, "paymentId", paymentId, userLogin);

            String receiptId = delegator.getNextSeqId("LeasePaymentReceipt");
            GenericValue receipt = delegator.makeValue("LeasePaymentReceipt");
            receipt.set("leasePaymentReceiptId", receiptId);
            receipt.set("leaseAgreementId", leaseAgreementId);
            receipt.set("paymentId", paymentId);
            receipt.set("invoiceId", invoiceId);
            receipt.set("amount", amount);
            receipt.set("currencyUomId", currency);
            receipt.set("receiptDate", effectiveDate);
            receipt.set("createdDate", UtilDateTime.nowTimestamp());
            receipt.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentId", paymentId);
            result.put("leasePaymentReceiptId", receiptId);
            result.put("appliedInvoiceCount", (long) applications.size());
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Balance
    // ------------------------------------------------------------------

    /**
     * Collection status of a lease agreement. The source of truth is the valid
     * payment applications (payments PMNT_RECEIVED / PMNT_CONFIRMED) on the lease
     * invoices and the amounts of the billed installments; the amount applied to an
     * invoice is allocated to tax, then interest, then principal. Billing alone does
     * not reduce the outstanding principal.
     */
    public static Map<String, Object> computeBalance(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        boolean operating = LeaseProductHelper.isOperating(agreement);
        BigDecimal billedLease = BigDecimal.ZERO;
        BigDecimal billedTax = BigDecimal.ZERO;
        BigDecimal billedComponent = BigDecimal.ZERO;
        BigDecimal billedComponentTax = BigDecimal.ZERO;
        BigDecimal applied = BigDecimal.ZERO;
        BigDecimal collectedTax = BigDecimal.ZERO;
        BigDecimal collectedComponent = BigDecimal.ZERO;
        BigDecimal collectedInterest = BigDecimal.ZERO;
        BigDecimal collectedPrincipal = BigDecimal.ZERO;
        long billedCount = 0;
        BigDecimal billedPrincipal = BigDecimal.ZERO;
        for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            billedCount++;
            BigDecimal tax = nz(link.getBigDecimal("taxAmount"));
            BigDecimal interest = nz(link.getBigDecimal("interestPortion"));
            BigDecimal principal = nz(link.getBigDecimal("principalPortion"));
            if (operating) {
                // STEP L12: the whole lease payment is lease revenue (no principal recovery).
                interest = interest.add(principal);
                principal = BigDecimal.ZERO;
            }
            BigDecimal component = nz(link.getBigDecimal("componentAmount"));
            BigDecimal componentTax = nz(link.getBigDecimal("componentTaxAmount"));
            billedPrincipal = billedPrincipal.add(principal);
            billedLease = billedLease.add(nz(link.getBigDecimal("leaseAmount")));
            billedTax = billedTax.add(tax);
            billedComponent = billedComponent.add(component);
            billedComponentTax = billedComponentTax.add(componentTax);
            BigDecimal invoiceApplied = BigDecimal.ZERO;
            for (GenericValue app : EntityQuery.use(delegator).from("PaymentApplication")
                    .where("invoiceId", link.getString("invoiceId")).cache(false).queryList()) {
                GenericValue payment = EntityQuery.use(delegator).from("Payment")
                        .where("paymentId", app.getString("paymentId")).cache(false).queryOne();
                if (isValidPayment(payment)) {
                    invoiceApplied = invoiceApplied.add(nz(app.getBigDecimal("amountApplied")));
                }
            }
            applied = applied.add(invoiceApplied);
            // Allocation order: consumption taxes, then components (separate revenue),
            // then the interest equivalent, then principal recovery.
            BigDecimal allTax = tax.add(componentTax);
            BigDecimal toTax = invoiceApplied.min(allTax).max(BigDecimal.ZERO);
            BigDecimal rest = invoiceApplied.subtract(toTax);
            BigDecimal toComponent = rest.min(component).max(BigDecimal.ZERO);
            rest = rest.subtract(toComponent);
            BigDecimal toInterest = rest.min(interest).max(BigDecimal.ZERO);
            BigDecimal toPrincipal = rest.subtract(toInterest).min(principal).max(BigDecimal.ZERO);
            collectedTax = collectedTax.add(toTax);
            collectedComponent = collectedComponent.add(toComponent);
            collectedInterest = collectedInterest.add(toInterest);
            collectedPrincipal = collectedPrincipal.add(toPrincipal);
        }
        BigDecimal recognized = BigDecimal.ZERO;
        for (GenericValue rec : EntityQuery.use(delegator).from("LeaseProfitRecognition")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            recognized = recognized.add(nz(rec.getBigDecimal("amount")));
        }
        BigDecimal billed = billedLease.add(billedTax).add(billedComponent).add(billedComponentTax);
        // STEP L13: re-lease invoices (fee + tax) are part of the billed / applied totals.
        Map<String, BigDecimal> reLease = LeaseReLeaseServices.balance(delegator, leaseAgreementId);
        billed = billed.add(reLease.get("billedReLeaseAmount")).add(reLease.get("billedReLeaseTaxAmount"));
        applied = applied.add(reLease.get("reLeaseAppliedAmount"));
        // STEP L14: the termination damages invoice.
        Map<String, BigDecimal> termination = LeaseTerminationServices.balance(delegator, leaseAgreementId);
        BigDecimal damages = termination.get("terminationDamagesAmount");
        BigDecimal collectedDamages = termination.get("collectedTerminationAmount");
        billed = billed.add(damages);
        applied = applied.add(collectedDamages);
        boolean terminated = LeaseTerminationServices.isTerminated(agreement)
                || LeaseTerminationServices.findTermination(delegator, leaseAgreementId) != null;
        Map<String, Object> balance = new HashMap<>();
        balance.putAll(reLease);
        balance.putAll(termination);
        // STEP L15c: advance lease payments and security deposits (received / applied / refunded / balance).
        balance.putAll(LeaseAdvanceServices.balance(delegator, leaseAgreementId));
        balance.put("billedCount", billedCount);
        balance.put("billedLeaseAmount", billedLease);
        balance.put("billedTaxAmount", billedTax);
        balance.put("billedComponentAmount", billedComponent);
        balance.put("billedComponentTaxAmount", billedComponentTax);
        balance.put("billedAmount", billed);
        balance.put("appliedAmount", applied);
        balance.put("unpaidBilledAmount", billed.subtract(applied));
        balance.put("collectedTaxAmount", collectedTax);
        balance.put("collectedComponentAmount", collectedComponent);
        balance.put("collectedInterestAmount", collectedInterest);
        balance.put("collectedPrincipalAmount", collectedPrincipal);
        // STEP L14: after a termination the outstanding principal is the unpaid billed principal plus the unpaid damages.
        balance.put("outstandingPrincipal", operating ? BigDecimal.ZERO : terminated
                ? billedPrincipal.subtract(collectedPrincipal).add(damages).subtract(collectedDamages)
                : nz(agreement.getBigDecimal("acquisitionCost")).subtract(collectedPrincipal));
        balance.put("unbilledLeaseAmount", terminated ? BigDecimal.ZERO
                : nz(agreement.getBigDecimal("totalLeaseAmount")).subtract(billedLease));
        balance.put("recognizedProfitAmount", recognized);
        balance.put("deferredProfitBalance", operating || terminated ? BigDecimal.ZERO
                : nz(agreement.getBigDecimal("totalInterestAmount")).subtract(recognized));
        // STEP L12: the leased asset of an operating lease (cost, accumulated depreciation, book value).
        if (operating) {
            BigDecimal cost = nz(agreement.getBigDecimal("acquisitionCost"));
            BigDecimal accumulated = LeaseDepreciationServices.accumulatedDepreciation(delegator, leaseAgreementId, null);
            balance.put("leasedAssetCost", cost);
            balance.put("accumulatedDepreciation", accumulated);
            balance.put("leasedAssetBookValue", cost.subtract(accumulated));
        }
        return balance;
    }

    public static Map<String, Object> getLeaseAgreementBalance(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.putAll(computeBalance(delegator, agreement));
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
