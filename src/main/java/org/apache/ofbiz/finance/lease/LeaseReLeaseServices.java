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
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Re-lease (再リース, STEP L13): renewal periods of an expired lease without
 * ownership transfer (finance non-transfer or operating), on the same agreement.
 *
 * <ul>
 * <li>Register ({@link #createLeaseReLease}): the next period starts the day after
 *     the previous end (lease end or previous re-lease), {@code months} long
 *     (default {@code lease.release.months}); the fee defaults to the original
 *     monthly lease amount x {@code reLeaseAnnualFactor} x months / 12.</li>
 * <li>Start ({@link #billLeaseReLease}): bill the fee once in advance (due at the
 *     period start), crediting unearned re-lease fees, with the consumption tax at
 *     the rate of the period start. At the first re-lease of a finance lease the
 *     residual value is moved from the lease investment to leased assets.</li>
 * <li>Each month ({@link #processReLeases}): recognize the earned fee
 *     (Dr unearned / Cr re-lease revenue) and depreciate the book value of the
 *     assets at the period start to zero over the period (Dr depreciation /
 *     Cr accumulated depreciation). Idempotent per month. A period whose months are
 *     all recognized is completed.</li>
 * </ul>
 * The agreement stays ACTIVE while re-leased; the return and the completion are
 * allowed after the end of the last re-lease ({@link #effectiveEndDate}).
 */
public final class LeaseReLeaseServices {

    private static final String MODULE = LeaseReLeaseServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String STATUS_CONTRACTED = "LSREL_CONTRACTED";
    public static final String STATUS_ACTIVE = "LSREL_ACTIVE";
    public static final String STATUS_COMPLETED = "LSREL_COMPLETED";
    public static final String STATUS_CANCELLED = "LSREL_CANCELLED";
    public static final String GL_UNEARNED = "LEASE_RELEASE_UNEARN";
    public static final String GL_REVENUE = "LEASE_RELEASE_REV";
    public static final String ITEM_RELEASE = "LEASE_RELEASE";
    private static final String ASSET_LEASED = "LSAST_LEASED";

    private LeaseReLeaseServices() {
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

    private static Timestamp plusOneDay(Timestamp ts) {
        return UtilDateTime.adjustTimestamp(ts, Calendar.DAY_OF_MONTH, 1);
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
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

    /** Re-lease periods of an agreement that are not cancelled, in date order. */
    public static List<GenericValue> reLeases(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseReLease").where(EntityCondition.makeCondition(UtilMisc.toList(
                EntityCondition.makeCondition("leaseAgreementId", leaseAgreementId),
                EntityCondition.makeCondition("statusId", EntityOperator.NOT_EQUAL, STATUS_CANCELLED))))
                .orderBy("reLeaseSeqId").cache(false).queryList();
    }

    /** End of the lease including its re-lease periods (the lease end when none). */
    public static Timestamp effectiveEndDate(Delegator delegator, GenericValue agreement) throws GenericEntityException {
        Timestamp end = agreement.getTimestamp("leaseEndDate");
        for (GenericValue r : reLeases(delegator, agreement.getString("leaseAgreementId"))) {
            if (end == null || r.getTimestamp("thruDate").after(end)) {
                end = r.getTimestamp("thruDate");
            }
        }
        return end;
    }

    /** Invoices of the billed re-leases: [invoiceId, dueDate (period start)] in date order. */
    public static List<Object[]> reLeaseInvoices(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        List<Object[]> result = new ArrayList<>();
        for (GenericValue r : reLeases(delegator, leaseAgreementId)) {
            if (UtilValidate.isNotEmpty(r.getString("invoiceId"))) {
                result.add(new Object[] {r.getString("invoiceId"), r.getTimestamp("fromDate"), r});
            }
        }
        return result;
    }

    /** Default fee of a re-lease period: monthly lease amount x annual factor x months / 12. */
    static BigDecimal defaultFee(Delegator delegator, GenericValue agreement, int months, int scale, RoundingMode mode)
            throws GenericEntityException {
        GenericValue terms = LeaseProductHelper.findLeaseProduct(delegator, agreement.getString("productId"));
        BigDecimal factor = terms == null ? null : terms.getBigDecimal("reLeaseAnnualFactor");
        if (factor == null) {
            factor = new BigDecimal(EntityUtilProperties.getPropertyValue("finance", "lease.release.annual.factor", "1.0",
                    delegator).trim());
        }
        return nz(agreement.getBigDecimal("monthlyLeaseAmount")).multiply(factor).multiply(BigDecimal.valueOf(months))
                .divide(BigDecimal.valueOf(12), scale, mode);
    }

    /** Cumulative share of {@code amount} after {@code m} of {@code months} months (last month = all). */
    public static BigDecimal cumulative(BigDecimal amount, int m, int months, int scale, RoundingMode mode) {
        if (m <= 0) {
            return BigDecimal.ZERO.setScale(scale, mode);
        }
        if (m >= months) {
            return amount.setScale(scale, mode);
        }
        return amount.multiply(BigDecimal.valueOf(m)).divide(BigDecimal.valueOf(months), scale, mode);
    }

    /** First lease month number of a re-lease period in LeaseAssetDepreciation (after the term and earlier re-leases). */
    private static int periodOffset(Delegator delegator, GenericValue agreement, GenericValue reLease)
            throws GenericEntityException {
        int offset = agreement.getLong("termMonths").intValue();
        for (GenericValue r : reLeases(delegator, agreement.getString("leaseAgreementId"))) {
            if (r.getString("reLeaseSeqId").compareTo(reLease.getString("reLeaseSeqId")) < 0) {
                offset += r.getLong("months").intValue();
            }
        }
        return offset;
    }

    // ------------------------------------------------------------------
    // Register / cancel
    // ------------------------------------------------------------------

    public static Map<String, Object> createLeaseReLease(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            agreement = findAgreement(delegator, leaseAgreementId);
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (LeaseProductHelper.CLASS_FIN_TRANSFER.equals(agreement.getString("leaseClassEnumId"))) {
                return error("FinanceLeaseReLeaseNotForTransfer", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                if (!ASSET_LEASED.equals(asset.getString("assetStatusId"))) {
                    return error("FinanceLeaseAssetNotLeased", UtilMisc.toMap("assetSeqId", asset.getString("assetSeqId")), locale);
                }
            }
            int months = context.get("months") != null ? ((Long) context.get("months")).intValue()
                    : Integer.parseInt(EntityUtilProperties.getPropertyValue("finance", "lease.release.months", "12",
                            delegator).trim());
            if (months <= 0) {
                return error("FinanceLeaseReLeaseMonthsInvalid", UtilMisc.toMap("months", months), locale);
            }
            String currency = agreement.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
            BigDecimal amount = context.get("reLeaseAmount") != null
                    ? ((BigDecimal) context.get("reLeaseAmount")).setScale(scale, mode)
                    : defaultFee(delegator, agreement, months, scale, mode);
            if (amount.signum() < 0) {
                return error("FinanceLeaseReLeaseAmountInvalid", UtilMisc.toMap("amount", amount), locale);
            }
            Timestamp from = UtilDateTime.getDayStart(plusOneDay(effectiveEndDate(delegator, agreement)));
            Timestamp thru = minusOneDay(addMonths(from, months));
            long seq = EntityQuery.use(delegator).from("LeaseReLease").where("leaseAgreementId", leaseAgreementId).queryCount() + 1;
            String reLeaseSeqId = String.format("%05d", seq);
            GenericValue r = delegator.makeValue("LeaseReLease");
            r.set("leaseAgreementId", leaseAgreementId);
            r.set("reLeaseSeqId", reLeaseSeqId);
            r.set("fromDate", from);
            r.set("thruDate", thru);
            r.set("months", (long) months);
            r.set("reLeaseAmount", amount);
            r.set("currencyUomId", currency);
            r.set("statusId", STATUS_CONTRACTED);
            r.set("recognizedAmount", BigDecimal.ZERO);
            r.set("comments", context.get("comments"));
            r.set("createdDate", UtilDateTime.nowTimestamp());
            r.create();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("reLeaseSeqId", reLeaseSeqId);
            result.put("fromDate", from);
            result.put("thruDate", thru);
            result.put("reLeaseAmount", amount);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Cancel a contracted (not yet billed) re-lease; only the last period can be cancelled. */
    public static Map<String, Object> cancelLeaseReLease(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String reLeaseSeqId = (String) context.get("reLeaseSeqId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            GenericValue r = EntityQuery.use(delegator).from("LeaseReLease")
                    .where("leaseAgreementId", leaseAgreementId, "reLeaseSeqId", reLeaseSeqId).queryOne();
            if (agreement == null || r == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseReLease",
                        "id", leaseAgreementId + "/" + reLeaseSeqId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            r.refresh();
            List<GenericValue> all = reLeases(delegator, leaseAgreementId);
            if (!STATUS_CONTRACTED.equals(r.getString("statusId"))
                    || !all.get(all.size() - 1).getString("reLeaseSeqId").equals(reLeaseSeqId)) {
                return error("FinanceLeaseReLeaseNotCancellable", UtilMisc.toMap("reLeaseSeqId", reLeaseSeqId,
                        "statusId", r.getString("statusId")), locale);
            }
            r.set("statusId", STATUS_CANCELLED);
            r.store();
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Start: billing in advance (+ residual reclassification)
    // ------------------------------------------------------------------

    /** Bill a contracted re-lease (idempotent) and start it. */
    public static Map<String, Object> billLeaseReLease(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String reLeaseSeqId = (String) context.get("reLeaseSeqId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            GenericValue r = EntityQuery.use(delegator).from("LeaseReLease")
                    .where("leaseAgreementId", leaseAgreementId, "reLeaseSeqId", reLeaseSeqId).queryOne();
            if (agreement == null || r == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseReLease",
                        "id", leaseAgreementId + "/" + reLeaseSeqId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            r.refresh();
            agreement = findAgreement(delegator, leaseAgreementId);
            if (STATUS_ACTIVE.equals(r.getString("statusId")) || STATUS_COMPLETED.equals(r.getString("statusId"))) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("invoiceId", r.getString("invoiceId"));
                result.put("invoiceCreated", false);
                return result;
            }
            if (!STATUS_CONTRACTED.equals(r.getString("statusId"))) {
                return error("FinanceLeaseReLeaseNotCancellable", UtilMisc.toMap("reLeaseSeqId", reLeaseSeqId,
                        "statusId", r.getString("statusId")), locale);
            }
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            String invoiceId = start(dctx, agreement, r, (GenericValue) context.get("userLogin"));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("invoiceId", invoiceId);
            result.put("invoiceCreated", true);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static String start(DispatchContext dctx, GenericValue agreement, GenericValue r, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        String organizationPartyId = agreement.getString("organizationPartyId");
        String currency = agreement.getString("currencyUomId");
        int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
        RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
        Timestamp from = r.getTimestamp("fromDate");
        BigDecimal amount = nz(r.getBigDecimal("reLeaseAmount"));

        // The original lease is accounted through its end before the re-lease starts.
        Timestamp leaseEnd = agreement.getTimestamp("leaseEndDate");
        check(LeaseAccountingServices.recognizeLeaseProfit(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                "recognitionDate", leaseEnd, "userLogin", userLogin)));
        if (LeaseProductHelper.isOperating(agreement)) {
            check(LeaseDepreciationServices.depreciateLeaseAssets(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                    "depreciationDate", leaseEnd, "userLogin", userLogin)));
        }

        // Finance lease, first re-lease: the residual value moves to leased assets (depreciated over the re-lease).
        if (!LeaseProductHelper.isOperating(agreement)) {
            Map<String, BigDecimal> residuals = LeaseExpiryServices.allocateResidual(delegator, agreement,
                    agreement.getBigDecimal("residualValue"));
            BigDecimal total = BigDecimal.ZERO;
            List<GenericValue> toStore = new ArrayList<>();
            for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                if (asset.getBigDecimal("reclassifiedResidual") != null) {
                    continue;
                }
                BigDecimal residual = nz(residuals.get(asset.getString("assetSeqId")));
                asset.set("reclassifiedResidual", residual);
                toStore.add(asset);
                total = total.add(residual);
            }
            if (total.signum() > 0) {
                List<FinanceGlHelper.Line> lines = new ArrayList<>();
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ASSET, true, total));
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.assetAccountType(agreement), false, total));
                r.set("reclassAcctgTransId", FinanceGlHelper.postTransaction(delegator, dispatcher,
                        "Re-lease residual " + leaseAgreementId, from, organizationPartyId, null, currency, lines, userLogin));
            }
            for (GenericValue asset : toStore) {
                asset.store();
            }
        }

        // Consumption tax at the rate of the re-lease start (a new taxable supply).
        LeaseTaxHelper.TaxRate taxRate = LeaseTaxHelper.resolveRate(delegator, agreement.getString("productId"), from);
        BigDecimal tax = amount.multiply(taxRate.getRate()).setScale(scale, mode);
        boolean coreTax = UtilValidate.isNotEmpty(taxRate.getTaxAuthPartyId()) && UtilValidate.isNotEmpty(taxRate.getTaxAuthGeoId());

        Map<String, Object> invInput = new HashMap<>();
        invInput.put("invoiceTypeId", "SALES_INVOICE");
        invInput.put("partyIdFrom", organizationPartyId);
        invInput.put("partyId", agreement.getString("lesseePartyId"));
        invInput.put("statusId", "INVOICE_IN_PROCESS");
        invInput.put("currencyUomId", currency);
        invInput.put("invoiceDate", UtilDateTime.nowTimestamp());
        invInput.put("dueDate", from);
        invInput.put("description", "Re-lease " + leaseAgreementId + " #" + r.getString("reLeaseSeqId"));
        invInput.put("userLogin", userLogin);
        Map<String, Object> inv = dispatcher.runSync("createInvoice", invInput);
        check(inv);
        String invoiceId = (String) inv.get("invoiceId");
        item(dispatcher, invoiceId, ITEM_RELEASE, amount, glAccount(delegator, organizationPartyId, GL_UNEARNED),
                "Re-lease fee " + r.getTimestamp("fromDate").toString().substring(0, 10) + ".."
                        + r.getTimestamp("thruDate").toString().substring(0, 10), userLogin);
        if (tax.signum() > 0) {
            if (coreTax) {
                Map<String, Object> taxItem = new HashMap<>();
                taxItem.put("invoiceId", invoiceId);
                taxItem.put("invoiceItemTypeId", LeaseAccountingServices.ITEM_CORE_SALES_TAX);
                taxItem.put("quantity", BigDecimal.ONE);
                taxItem.put("amount", tax);
                taxItem.put("description", "Consumption tax (re-lease)");
                taxItem.put("taxAuthPartyId", taxRate.getTaxAuthPartyId());
                taxItem.put("taxAuthGeoId", taxRate.getTaxAuthGeoId());
                taxItem.put("taxAuthorityRateSeqId", taxRate.getTaxAuthorityRateSeqId());
                taxItem.put("userLogin", userLogin);
                check(dispatcher.runSync("createInvoiceItem", taxItem));
            } else {
                item(dispatcher, invoiceId, LeaseAccountingServices.ITEM_CONS_TAX, tax,
                        glAccount(delegator, organizationPartyId, LeaseRevenueStrategy.GL_TAX_PAYABLE),
                        "Consumption tax (re-lease)", userLogin);
            }
        }
        check(dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap("invoiceId", invoiceId,
                "statusId", "INVOICE_READY", "userLogin", userLogin)));
        FinanceGlHelper.postUnposted(delegator, dispatcher, "invoiceId", invoiceId, userLogin);

        r.set("invoiceId", invoiceId);
        r.set("taxRate", taxRate.getRate());
        r.set("taxAmount", tax);
        r.set("taxAuthPartyId", taxRate.getTaxAuthPartyId());
        r.set("taxAuthGeoId", taxRate.getTaxAuthGeoId());
        r.set("taxAuthorityRateSeqId", taxRate.getTaxAuthorityRateSeqId());
        r.set("statusId", STATUS_ACTIVE);
        r.store();
        return invoiceId;
    }

    private static void item(LocalDispatcher dispatcher, String invoiceId, String itemTypeId, BigDecimal amount,
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

    // ------------------------------------------------------------------
    // Monthly processing
    // ------------------------------------------------------------------

    /**
     * Re-lease accounting of an ACTIVE agreement through {@code date}: start (bill)
     * every contracted period that has begun, then for every started period recognize
     * the fee and depreciate the book value of each month ended on or before the
     * date; complete the periods fully recognized. Idempotent.
     *
     * @return [started, recognized months, depreciated months]
     */
    public static long[] processReLeases(DispatchContext dctx, GenericValue agreement, Timestamp date, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        Timestamp dayEnd = UtilDateTime.getDayEnd(date);
        long started = 0;
        long recognized = 0;
        long depreciated = 0;
        for (GenericValue r : reLeases(delegator, leaseAgreementId)) {
            if (STATUS_CONTRACTED.equals(r.getString("statusId")) && !r.getTimestamp("fromDate").after(dayEnd)) {
                start(dctx, findAgreement(delegator, leaseAgreementId), r, userLogin);
                started++;
            }
            if (!STATUS_ACTIVE.equals(r.getString("statusId"))) {
                continue;
            }
            long[] done = recognizeMonths(dctx, findAgreement(delegator, leaseAgreementId), r, dayEnd, userLogin);
            recognized += done[0];
            depreciated += done[1];
        }
        return new long[] {started, recognized, depreciated};
    }

    private static long[] recognizeMonths(DispatchContext dctx, GenericValue agreement, GenericValue r, Timestamp dayEnd,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        String seq = r.getString("reLeaseSeqId");
        String organizationPartyId = agreement.getString("organizationPartyId");
        String currency = agreement.getString("currencyUomId");
        int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
        RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
        int months = r.getLong("months").intValue();
        BigDecimal fee = nz(r.getBigDecimal("reLeaseAmount"));
        int offset = periodOffset(delegator, agreement, r);
        List<GenericValue> assets = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                .where("leaseAgreementId", leaseAgreementId).orderBy("assetSeqId").queryList();
        // Book value of each asset at the period start (to be depreciated to zero over the period).
        Map<String, BigDecimal> startBook = new LinkedHashMap<>();
        for (GenericValue asset : assets) {
            String a = asset.getString("assetSeqId");
            BigDecimal base = LeaseProductHelper.isOperating(agreement) ? nz(asset.getBigDecimal("acquisitionCost"))
                    : nz(asset.getBigDecimal("reclassifiedResidual"));
            BigDecimal before = BigDecimal.ZERO;
            for (GenericValue d : EntityQuery.use(delegator).from("LeaseAssetDepreciation")
                    .where(EntityCondition.makeCondition(UtilMisc.toList(
                            EntityCondition.makeCondition("leaseAgreementId", leaseAgreementId),
                            EntityCondition.makeCondition("assetSeqId", a),
                            EntityCondition.makeCondition("periodNumber", EntityOperator.LESS_THAN_EQUAL_TO, (long) offset))))
                    .cache(false).queryList()) {
                before = before.add(nz(d.getBigDecimal("amount")));
            }
            startBook.put(a, base.subtract(before).max(BigDecimal.ZERO));
        }
        GenericValue last = EntityQuery.use(delegator).from("LeaseReLeaseRecognition")
                .where("leaseAgreementId", leaseAgreementId, "reLeaseSeqId", seq).orderBy("-monthNumber").cache(false).queryFirst();
        int m = last == null ? 1 : last.getLong("monthNumber").intValue() + 1;
        long recognized = 0;
        long depreciated = 0;
        Timestamp from0 = r.getTimestamp("fromDate");
        for (; m <= months; m++) {
            Timestamp thru = minusOneDay(addMonths(from0, m));
            if (thru.after(dayEnd)) {
                break;
            }
            Timestamp from = addMonths(from0, m - 1);
            // Fee: Dr unearned re-lease fees / Cr re-lease revenue.
            BigDecimal cum = cumulative(fee, m, months, scale, mode);
            BigDecimal amount = cum.subtract(cumulative(fee, m - 1, months, scale, mode));
            String acctgTransId = null;
            if (amount.signum() > 0) {
                List<FinanceGlHelper.Line> lines = new ArrayList<>();
                lines.add(new FinanceGlHelper.Line(GL_UNEARNED, true, amount));
                lines.add(new FinanceGlHelper.Line(GL_REVENUE, false, amount));
                acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                        "Re-lease revenue " + leaseAgreementId + "/" + seq + " #" + m, thru, organizationPartyId,
                        agreement.getString("lesseePartyId"), currency, lines, userLogin);
            }
            GenericValue rec = delegator.makeValue("LeaseReLeaseRecognition");
            rec.set("leaseAgreementId", leaseAgreementId);
            rec.set("reLeaseSeqId", seq);
            rec.set("monthNumber", (long) m);
            rec.set("fromDate", from);
            rec.set("thruDate", thru);
            rec.set("amount", amount);
            rec.set("cumulativeAmount", cum);
            rec.set("acctgTransId", acctgTransId);
            rec.set("createdDate", UtilDateTime.nowTimestamp());
            rec.create();
            r.set("recognizedAmount", cum);
            recognized++;

            // Book value: Dr depreciation of leased assets / Cr accumulated depreciation.
            int periodNumber = offset + m;
            Map<String, BigDecimal> amounts = new LinkedHashMap<>();
            BigDecimal monthTotal = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> e : startBook.entrySet()) {
                BigDecimal a = cumulative(e.getValue(), m, months, scale, mode)
                        .subtract(cumulative(e.getValue(), m - 1, months, scale, mode));
                amounts.put(e.getKey(), a);
                monthTotal = monthTotal.add(a);
            }
            if (monthTotal.signum() > 0) {
                List<FinanceGlHelper.Line> lines = new ArrayList<>();
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_DEPRECIATION, true, monthTotal));
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ACCUM_DEPR, false, monthTotal));
                String depTrans = FinanceGlHelper.postTransaction(delegator, dispatcher,
                        "Re-lease depreciation " + leaseAgreementId + "/" + seq + " #" + m, thru, organizationPartyId, null,
                        currency, lines, userLogin);
                for (GenericValue asset : assets) {
                    String a = asset.getString("assetSeqId");
                    BigDecimal base = LeaseProductHelper.isOperating(agreement) ? nz(asset.getBigDecimal("acquisitionCost"))
                            : nz(asset.getBigDecimal("reclassifiedResidual"));
                    BigDecimal accumulated = LeaseDepreciationServices.accumulatedDepreciation(delegator, leaseAgreementId, a)
                            .add(amounts.get(a));
                    GenericValue d = delegator.makeValue("LeaseAssetDepreciation");
                    d.set("leaseAgreementId", leaseAgreementId);
                    d.set("assetSeqId", a);
                    d.set("periodNumber", (long) periodNumber);
                    d.set("reLeaseSeqId", seq);
                    d.set("fixedAssetId", asset.getString("fixedAssetId"));
                    d.set("fromDate", from);
                    d.set("thruDate", thru);
                    d.set("amount", amounts.get(a));
                    d.set("cumulativeAmount", accumulated);
                    d.set("bookValue", base.subtract(accumulated));
                    d.set("currencyUomId", currency);
                    d.set("acctgTransId", depTrans);
                    d.set("createdDate", UtilDateTime.nowTimestamp());
                    d.create();
                    if (UtilValidate.isNotEmpty(asset.getString("fixedAssetId"))) {
                        GenericValue fa = EntityQuery.use(delegator).from("FixedAsset")
                                .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
                        if (fa != null) {
                            fa.set("depreciation", nz(fa.getBigDecimal("depreciation")).add(amounts.get(a)));
                            fa.store();
                        }
                    }
                }
                depreciated++;
            }
        }
        if (m > months) {
            r.set("statusId", STATUS_COMPLETED);
        }
        r.store();
        return new long[] {recognized, depreciated};
    }

    /**
     * STEP L14: early termination during / before re-leases. The started periods
     * recognize the remaining unearned fee at the termination date (no refund) and
     * are completed; the depreciation of the re-lease stops (the assets are disposed
     * at their book value). Periods not started are cancelled.
     *
     * @return the unearned fee recognized at termination
     */
    public static BigDecimal terminateReLeases(DispatchContext dctx, GenericValue agreement, Timestamp terminationDate,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue r : reLeases(delegator, leaseAgreementId)) {
            String status = r.getString("statusId");
            if (STATUS_CONTRACTED.equals(status)) {
                r.set("statusId", STATUS_CANCELLED);
                r.store();
                continue;
            }
            if (!STATUS_ACTIVE.equals(status)) {
                continue;
            }
            BigDecimal remaining = nz(r.getBigDecimal("reLeaseAmount")).subtract(nz(r.getBigDecimal("recognizedAmount")));
            String acctgTransId = null;
            if (remaining.signum() > 0) {
                List<FinanceGlHelper.Line> lines = new ArrayList<>();
                lines.add(new FinanceGlHelper.Line(GL_UNEARNED, true, remaining));
                lines.add(new FinanceGlHelper.Line(GL_REVENUE, false, remaining));
                acctgTransId = FinanceGlHelper.postTransaction(delegator, dctx.getDispatcher(),
                        "Re-lease revenue at termination " + leaseAgreementId + "/" + r.getString("reLeaseSeqId"),
                        terminationDate, agreement.getString("organizationPartyId"), agreement.getString("lesseePartyId"),
                        agreement.getString("currencyUomId"), lines, userLogin);
            }
            GenericValue last = EntityQuery.use(delegator).from("LeaseReLeaseRecognition")
                    .where("leaseAgreementId", leaseAgreementId, "reLeaseSeqId", r.getString("reLeaseSeqId"))
                    .orderBy("-monthNumber").cache(false).queryFirst();
            long m = last == null ? 1 : last.getLong("monthNumber") + 1;
            GenericValue rec = delegator.makeValue("LeaseReLeaseRecognition");
            rec.set("leaseAgreementId", leaseAgreementId);
            rec.set("reLeaseSeqId", r.getString("reLeaseSeqId"));
            rec.set("monthNumber", m);
            rec.set("fromDate", last == null ? r.getTimestamp("fromDate") : plusOneDay(last.getTimestamp("thruDate")));
            rec.set("thruDate", terminationDate);
            rec.set("amount", remaining);
            rec.set("cumulativeAmount", r.getBigDecimal("reLeaseAmount"));
            rec.set("acctgTransId", acctgTransId);
            rec.set("createdDate", UtilDateTime.nowTimestamp());
            rec.create();
            r.set("recognizedAmount", r.getBigDecimal("reLeaseAmount"));
            r.set("statusId", STATUS_COMPLETED);
            r.store();
            total = total.add(remaining);
        }
        return total;
    }

    /** Service wrapper of {@link #processReLeases}. */
    public static Map<String, Object> processLeaseReLeases(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp date = context.get("processingDate") != null ? (Timestamp) context.get("processingDate")
                : UtilDateTime.nowTimestamp();
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            long[] done = processReLeases(dctx, findAgreement(delegator, leaseAgreementId), date,
                    (GenericValue) context.get("userLogin"));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("startedCount", done[0]);
            result.put("recognizedCount", done[1]);
            result.put("depreciatedCount", done[2]);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Balance and delinquency support
    // ------------------------------------------------------------------

    /** Valid amount applied to an invoice (payments PMNT_RECEIVED / PMNT_CONFIRMED). */
    static BigDecimal applied(Delegator delegator, String invoiceId) throws GenericEntityException {
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue app : EntityQuery.use(delegator).from("PaymentApplication").where("invoiceId", invoiceId)
                .cache(false).queryList()) {
            GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", app.getString("paymentId"))
                    .cache(false).queryOne();
            if (payment != null && ("PMNT_RECEIVED".equals(payment.getString("statusId"))
                    || "PMNT_CONFIRMED".equals(payment.getString("statusId")))) {
                total = total.add(nz(app.getBigDecimal("amountApplied")));
            }
        }
        return total;
    }

    /** Re-lease figures for the collection status: billed fee / tax, collected fee / tax (tax first), recognized. */
    public static Map<String, BigDecimal> balance(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        BigDecimal billedFee = BigDecimal.ZERO;
        BigDecimal billedTax = BigDecimal.ZERO;
        BigDecimal applied = BigDecimal.ZERO;
        BigDecimal collectedTax = BigDecimal.ZERO;
        BigDecimal collectedFee = BigDecimal.ZERO;
        BigDecimal recognized = BigDecimal.ZERO;
        for (GenericValue r : reLeases(delegator, leaseAgreementId)) {
            recognized = recognized.add(nz(r.getBigDecimal("recognizedAmount")));
            if (UtilValidate.isEmpty(r.getString("invoiceId"))) {
                continue;
            }
            BigDecimal fee = nz(r.getBigDecimal("reLeaseAmount"));
            BigDecimal tax = nz(r.getBigDecimal("taxAmount"));
            billedFee = billedFee.add(fee);
            billedTax = billedTax.add(tax);
            BigDecimal app = applied(delegator, r.getString("invoiceId"));
            applied = applied.add(app);
            BigDecimal toTax = app.min(tax).max(BigDecimal.ZERO);
            collectedTax = collectedTax.add(toTax);
            collectedFee = collectedFee.add(app.subtract(toTax).min(fee).max(BigDecimal.ZERO));
        }
        Map<String, BigDecimal> result = new HashMap<>();
        result.put("billedReLeaseAmount", billedFee);
        result.put("billedReLeaseTaxAmount", billedTax);
        result.put("reLeaseAppliedAmount", applied);
        result.put("collectedReLeaseAmount", collectedFee);
        result.put("collectedReLeaseTaxAmount", collectedTax);
        result.put("recognizedReLeaseAmount", recognized);
        result.put("unearnedReLeaseAmount", billedFee.subtract(recognized));
        return result;
    }

    /**
     * Overdue re-lease invoices as of a date, in the row format of the lease
     * delinquency (the fee in the interest bucket, then the tax; no principal).
     */
    public static List<Map<String, Object>> overdue(Delegator delegator, GenericValue agreement, Timestamp asOfDate)
            throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int term = agreement.getLong("termMonths").intValue();
        for (Object[] inv : reLeaseInvoices(delegator, agreement.getString("leaseAgreementId"))) {
            Timestamp due = (Timestamp) inv[1];
            if (due.after(asOfDate)) {
                continue;
            }
            GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", inv[0]).cache(false).queryOne();
            if (invoice == null || "INVOICE_PAID".equals(invoice.getString("statusId"))
                    || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))
                    || "INVOICE_CANCELLED".equals(invoice.getString("statusId"))) {
                continue;
            }
            BigDecimal unpaid = nz(InvoiceWorker.getInvoiceNotApplied(invoice));
            if (unpaid.signum() <= 0) {
                continue;
            }
            GenericValue r = (GenericValue) inv[2];
            BigDecimal fee = nz(r.getBigDecimal("reLeaseAmount"));
            BigDecimal unpaidFee = unpaid.min(fee).max(BigDecimal.ZERO);
            BigDecimal unpaidTax = unpaid.subtract(unpaidFee).max(BigDecimal.ZERO);
            rows.add(UtilMisc.toMap("installmentNumber", (long) (term + Integer.parseInt(r.getString("reLeaseSeqId"))),
                    "dueDate", due, "invoiceId", inv[0], "unpaidAmount", unpaid, "principalAmount", BigDecimal.ZERO,
                    "interestAmount", unpaidFee, "componentAmount", BigDecimal.ZERO, "taxAmount", unpaidTax,
                    "leaseAmount", unpaidFee));
        }
        return rows;
    }
}
