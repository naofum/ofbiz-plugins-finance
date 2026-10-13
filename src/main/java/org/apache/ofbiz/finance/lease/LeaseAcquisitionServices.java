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
import java.sql.Timestamp;
import java.util.Calendar;
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
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.BusinessDayCalculator;
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Lease asset purchase, supplier payment and lease start (STEP L3).
 *
 * <ul>
 * <li>Purchase: a core purchase invoice from the supplier (item booked to the lease
 *     asset suspense account; the core posts Dr suspense / Cr accounts payable), a
 *     core FixedAsset owned by the lessor, and the link to the agreement asset.</li>
 * <li>Payment: a core vendor payment applied to the purchase invoice (the core posts
 *     the cash and payable entries).</li>
 * <li>Acceptance: the acceptance date becomes the lease start; the confirmed payment
 *     schedule is re-dated as a new revision, the agreement becomes ACTIVE and the
 *     start entries of the revenue method are posted once (idempotent).</li>
 * </ul>
 */
public final class LeaseAcquisitionServices {

    private static final String MODULE = LeaseAcquisitionServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String ASSET_PENDING = "LSAST_PENDING";
    public static final String ASSET_PURCHASED = "LSAST_PURCHASED";
    public static final String ASSET_LEASED = "LSAST_LEASED";

    private LeaseAcquisitionServices() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    private static Timestamp dayStart(Timestamp ts) {
        return UtilDateTime.getDayStart(ts);
    }

    private static Timestamp addMonths(Timestamp ts, int months) {
        return UtilDateTime.adjustTimestamp(ts, Calendar.MONTH, months);
    }

    private static Timestamp minusOneDay(Timestamp ts) {
        return UtilDateTime.adjustTimestamp(ts, Calendar.DAY_OF_MONTH, -1);
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    private static void check(Map<String, Object> result) throws GenericServiceException {
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    // ------------------------------------------------------------------
    // Purchase
    // ------------------------------------------------------------------

    public static Map<String, Object> recordLeaseAssetPurchase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!"LEASEAGR_CONTRACTED".equals(agreement.getString("statusId"))) {
                return error("FinanceLeasePurchaseRequiresContracted", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            GenericValue asset = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (asset == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreementAsset", "id", assetSeqId), locale);
            }
            if (EntityQuery.use(delegator).from("LeaseAssetPurchase")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne() != null) {
                return error("FinanceLeaseAssetAlreadyPurchased", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            String supplierPartyId = UtilValidate.isNotEmpty((String) context.get("supplierPartyId"))
                    ? (String) context.get("supplierPartyId") : asset.getString("supplierPartyId");
            if (UtilValidate.isEmpty(supplierPartyId)) {
                return error("FinanceLeaseSupplierRequired", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            String organizationPartyId = agreement.getString("organizationPartyId");
            String currencyUomId = agreement.getString("currencyUomId");
            BigDecimal amount = asset.getBigDecimal("acquisitionCost");
            Timestamp purchaseDate = context.get("purchaseDate") != null
                    ? (Timestamp) context.get("purchaseDate") : UtilDateTime.nowTimestamp();
            String suspenseGl = FinanceGlHelper.findGlAccountId(delegator, organizationPartyId,
                    LeaseRevenueStrategy.GL_ASSET_SUSPENSE);
            if (suspenseGl == null) {
                return error("FinanceGlAccountMappingMissing", UtilMisc.toMap("organizationPartyId", organizationPartyId,
                        "glAccountTypeId", LeaseRevenueStrategy.GL_ASSET_SUSPENSE), locale);
            }

            check(dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("partyId", supplierPartyId,
                    "roleTypeId", "BILL_FROM_VENDOR", "userLogin", userLogin)));

            // Supplier invoice; READY lets the core post Dr asset suspense / Cr accounts payable.
            Map<String, Object> invInput = new HashMap<>();
            invInput.put("invoiceTypeId", "PURCHASE_INVOICE");
            invInput.put("partyIdFrom", supplierPartyId);
            invInput.put("partyId", organizationPartyId);
            invInput.put("statusId", "INVOICE_IN_PROCESS");
            invInput.put("currencyUomId", currencyUomId);
            invInput.put("invoiceDate", purchaseDate);
            invInput.put("referenceNumber", context.get("supplierInvoiceRef"));
            invInput.put("description", "Lease asset " + leaseAgreementId + "/" + assetSeqId);
            invInput.put("userLogin", userLogin);
            Map<String, Object> inv = dispatcher.runSync("createInvoice", invInput);
            check(inv);
            String invoiceId = (String) inv.get("invoiceId");
            Map<String, Object> itemInput = new HashMap<>();
            itemInput.put("invoiceId", invoiceId);
            itemInput.put("invoiceItemTypeId", "PINV_PROD_ITEM");
            itemInput.put("quantity", BigDecimal.ONE);
            itemInput.put("amount", amount);
            itemInput.put("description", asset.getString("assetName"));
            itemInput.put("overrideGlAccountId", suspenseGl);
            itemInput.put("userLogin", userLogin);
            check(dispatcher.runSync("createInvoiceItem", itemInput));
            check(dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap("invoiceId", invoiceId,
                    "statusId", "INVOICE_READY", "userLogin", userLogin)));
            FinanceGlHelper.postUnposted(delegator, dispatcher, "invoiceId", invoiceId, userLogin);

            // The leased asset is a fixed asset owned by the lessor.
            Map<String, Object> faInput = new HashMap<>();
            faInput.put("fixedAssetTypeId", asset.getString("fixedAssetTypeId"));
            faInput.put("fixedAssetName", asset.getString("assetName"));
            faInput.put("partyId", organizationPartyId);
            faInput.put("dateAcquired", purchaseDate);
            faInput.put("purchaseCost", amount);
            faInput.put("purchaseCostUomId", currencyUomId);
            faInput.put("userLogin", userLogin);
            Map<String, Object> fa = dispatcher.runSync("createFixedAsset", faInput);
            check(fa);
            String fixedAssetId = (String) fa.get("fixedAssetId");

            asset.set("fixedAssetId", fixedAssetId);
            asset.set("assetStatusId", ASSET_PURCHASED);
            asset.store();
            GenericValue purchase = delegator.makeValue("LeaseAssetPurchase");
            purchase.set("leaseAgreementId", leaseAgreementId);
            purchase.set("assetSeqId", assetSeqId);
            purchase.set("supplierPartyId", supplierPartyId);
            purchase.set("invoiceId", invoiceId);
            purchase.set("supplierInvoiceRef", context.get("supplierInvoiceRef"));
            purchase.set("purchaseDate", purchaseDate);
            purchase.set("amount", amount);
            purchase.set("currencyUomId", currencyUomId);
            purchase.set("fixedAssetId", fixedAssetId);
            purchase.set("createdDate", UtilDateTime.nowTimestamp());
            purchase.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("invoiceId", invoiceId);
            result.put("fixedAssetId", fixedAssetId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Supplier payment
    // ------------------------------------------------------------------

    public static Map<String, Object> payLeaseAssetPurchase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            GenericValue purchase = EntityQuery.use(delegator).from("LeaseAssetPurchase")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (agreement == null || purchase == null) {
                return error("FinanceLeasePurchaseNotFound", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "assetSeqId", assetSeqId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            purchase.refresh();
            if (UtilValidate.isNotEmpty(purchase.getString("paymentId"))) {
                return error("FinanceLeasePurchaseAlreadyPaid", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            String paymentMethodTypeId = UtilValidate.isNotEmpty((String) context.get("paymentMethodTypeId"))
                    ? (String) context.get("paymentMethodTypeId") : "EFT_ACCOUNT";
            Timestamp effectiveDate = context.get("effectiveDate") != null
                    ? (Timestamp) context.get("effectiveDate") : UtilDateTime.nowTimestamp();
            // The core counts only payments effective up to now when it settles the invoice.
            if (effectiveDate.after(UtilDateTime.nowTimestamp())) {
                return error("FinanceLeasePaymentFutureDate", UtilMisc.toMap("effectiveDate", effectiveDate), locale);
            }

            Map<String, Object> payInput = new HashMap<>();
            payInput.put("paymentTypeId", "VENDOR_PAYMENT");
            payInput.put("partyIdFrom", agreement.getString("organizationPartyId"));
            payInput.put("partyIdTo", purchase.getString("supplierPartyId"));
            // Created as SENT: the core posts the outgoing payment (createPayment ECA); the application posts the payable.
            payInput.put("statusId", "PMNT_SENT");
            payInput.put("paymentMethodTypeId", paymentMethodTypeId);
            payInput.put("amount", purchase.getBigDecimal("amount"));
            payInput.put("currencyUomId", purchase.getString("currencyUomId"));
            payInput.put("effectiveDate", effectiveDate);
            payInput.put("comments", "Lease asset " + leaseAgreementId + "/" + assetSeqId);
            payInput.put("userLogin", userLogin);
            Map<String, Object> pay = dispatcher.runSync("createPayment", payInput);
            check(pay);
            String paymentId = (String) pay.get("paymentId");
            check(dispatcher.runSync("createPaymentApplication", UtilMisc.toMap("paymentId", paymentId,
                    "invoiceId", purchase.getString("invoiceId"), "amountApplied", purchase.getBigDecimal("amount"),
                    "userLogin", userLogin)));

            FinanceGlHelper.postUnposted(delegator, dispatcher, "paymentId", paymentId, userLogin);

            purchase.set("paymentId", paymentId);
            purchase.set("paidDate", effectiveDate);
            purchase.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentId", paymentId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Acceptance = lease start
    // ------------------------------------------------------------------

    public static Map<String, Object> acceptLeaseAgreement(DispatchContext dctx, Map<String, Object> context) {
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

            // Idempotent: a second acceptance returns the existing start.
            GenericValue existing = EntityQuery.use(delegator).from("LeaseAcceptance")
                    .where("leaseAgreementId", leaseAgreementId).queryOne();
            if (existing != null) {
                return acceptanceResult(existing, false);
            }
            agreement = findAgreement(delegator, leaseAgreementId);
            if (!"LEASEAGR_CONTRACTED".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAcceptRequiresContracted", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            LeaseRevenueStrategy strategy = LeaseRevenueStrategy.REGISTRY.get(agreement.getString("revenueMethodEnumId"));
            if (strategy == null) {
                return error("FinanceLeaseRevenueMethodUnsupported",
                        UtilMisc.toMap("revenueMethodEnumId", agreement.getString("revenueMethodEnumId")), locale);
            }

            List<GenericValue> assets = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).queryList();
            Timestamp latestPurchase = null;
            for (GenericValue asset : assets) {
                GenericValue purchase = EntityQuery.use(delegator).from("LeaseAssetPurchase")
                        .where("leaseAgreementId", leaseAgreementId, "assetSeqId", asset.getString("assetSeqId")).queryOne();
                if (purchase == null) {
                    return error("FinanceLeaseAcceptRequiresPurchase", UtilMisc.toMap("assetSeqId",
                            asset.getString("assetSeqId")), locale);
                }
                Timestamp pd = purchase.getTimestamp("purchaseDate");
                if (latestPurchase == null || pd.after(latestPurchase)) {
                    latestPurchase = pd;
                }
            }
            Timestamp acceptanceDate = context.get("acceptanceDate") != null
                    ? (Timestamp) context.get("acceptanceDate") : UtilDateTime.nowTimestamp();
            Timestamp start = dayStart(acceptanceDate);
            Timestamp agreementDay = dayStart(agreement.getTimestamp("agreementDate"));
            if (start.before(agreementDay) || (latestPurchase != null && start.before(dayStart(latestPurchase)))) {
                return error("FinanceLeaseAcceptDateInvalid", UtilMisc.toMap("acceptanceDate", start), locale);
            }

            // Re-date the confirmed schedule from the lease start (new revision; amounts unchanged).
            GenericValue latest = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                    .where("leaseAgreementId", leaseAgreementId).orderBy("-scheduleRevisionNumber").queryFirst();
            long baseRevision = latest.getLong("scheduleRevisionNumber");
            long newRevision = baseRevision + 1;
            boolean advance = LeaseProductHelper.TIMING_ADVANCE.equals(agreement.getString("paymentTimingEnumId"));
            Timestamp firstPayment = advance ? start : addMonths(start, 1);
            String dueDateConvention = FinanceInterestCalculator.resolveDueDateConvention(delegator,
                    agreement.getString("productId"));
            // STEP L5: the consumption tax rate and timing are fixed at the lease start.
            LeaseTaxHelper.TaxRate taxRate = LeaseTaxHelper.resolveRate(delegator, agreement.getString("productId"), start);
            String taxTiming = LeaseTaxHelper.resolveTiming(delegator, agreement.getString("productId"));
            boolean lumpSum = LeaseTaxHelper.TIMING_AT_START.equals(taxTiming);
            List<GenericValue> baseRows = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                    .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", baseRevision)
                    .orderBy("installmentNumber").queryList();
            List<BigDecimal> leaseAmounts = new java.util.ArrayList<>();
            for (GenericValue row : baseRows) {
                leaseAmounts.add(row.getBigDecimal("leaseAmount"));
            }
            String currency = agreement.getString("currencyUomId");
            List<BigDecimal> taxes = LeaseTaxHelper.allocate(leaseAmounts, taxRate.getRate(), lumpSum,
                    FinanceInterestCalculator.resolveScale(currency, delegator),
                    FinanceInterestCalculator.resolveRoundingMode(currency, delegator));
            BigDecimal totalTax = BigDecimal.ZERO;
            int index = 0;
            for (GenericValue row : baseRows) {
                int k = row.getLong("installmentNumber").intValue();
                GenericValue line = delegator.makeValue("LeaseAgreementPaymentSchedule", row.getAllFields());
                line.set("scheduleRevisionNumber", newRevision);
                line.set("dueDate", BusinessDayCalculator.adjust(addMonths(firstPayment, k - 1), dueDateConvention));
                line.set("periodFromDate", addMonths(start, k - 1));
                line.set("periodThruDate", minusOneDay(addMonths(start, k)));
                line.set("taxAmount", taxes.get(index));
                totalTax = totalTax.add(taxes.get(index));
                index++;
                line.create();
            }

            int term = agreement.getLong("termMonths").intValue();
            agreement.set("leaseStartDate", start);
            agreement.set("leaseEndDate", minusOneDay(addMonths(start, term)));
            agreement.set("firstPaymentDate", firstPayment);
            agreement.set("taxRate", taxRate.getRate());
            agreement.set("totalTaxAmount", totalTax);
            agreement.set("taxTimingEnumId", taxTiming);
            agreement.set("taxAuthPartyId", taxRate.getTaxAuthPartyId());
            agreement.set("taxAuthGeoId", taxRate.getTaxAuthGeoId());
            agreement.set("taxAuthorityRateSeqId", taxRate.getTaxAuthorityRateSeqId());
            agreement.set("statusId", "LEASEAGR_ACTIVE");
            agreement.store();
            FinanceContractHelper.updateStatus(delegator, agreement.getString("financeContractId"),
                    FinanceContractHelper.STATUS_ACTIVE);
            for (GenericValue asset : assets) {
                asset.set("assetStatusId", ASSET_LEASED);
                asset.store();
            }

            String acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                    "Lease start " + leaseAgreementId, start, agreement.getString("organizationPartyId"),
                    agreement.getString("lesseePartyId"), agreement.getString("currencyUomId"),
                    strategy.startEntries(agreement), userLogin);
            // Lump sum: the tax of all payments is a taxable sale at the start (Dr tax receivable / Cr tax received).
            String startTaxAcctgTransId = null;
            if (lumpSum && totalTax.signum() > 0) {
                startTaxAcctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                        "Lease consumption tax at start " + leaseAgreementId, start,
                        agreement.getString("organizationPartyId"), agreement.getString("lesseePartyId"), currency,
                        UtilMisc.toList(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_TAX_RECEIVABLE, true, totalTax),
                                new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_TAX_PAYABLE, false, totalTax)), userLogin);
            }

            String acceptedBy = UtilValidate.isNotEmpty((String) context.get("acceptedByPartyId"))
                    ? (String) context.get("acceptedByPartyId") : agreement.getString("lesseePartyId");
            GenericValue acceptance = delegator.makeValue("LeaseAcceptance");
            acceptance.set("leaseAgreementId", leaseAgreementId);
            acceptance.set("acceptanceDate", start);
            acceptance.set("acceptedByPartyId", acceptedBy);
            acceptance.set("comments", context.get("comments"));
            acceptance.set("scheduleRevisionNumber", newRevision);
            acceptance.set("startAcctgTransId", acctgTransId);
            acceptance.set("startTaxAcctgTransId", startTaxAcctgTransId);
            acceptance.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
            acceptance.set("createdDate", UtilDateTime.nowTimestamp());
            acceptance.create();
            return acceptanceResult(acceptance, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> acceptanceResult(GenericValue acceptance, boolean created) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("acctgTransId", acceptance.getString("startAcctgTransId"));
        result.put("taxAcctgTransId", acceptance.getString("startTaxAcctgTransId"));
        result.put("scheduleRevisionNumber", acceptance.getLong("scheduleRevisionNumber"));
        result.put("created", created);
        return result;
    }
}
