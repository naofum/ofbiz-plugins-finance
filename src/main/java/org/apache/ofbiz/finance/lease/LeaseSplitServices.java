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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import org.apache.ofbiz.finance.common.FinanceContractPartyHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Asset split and contract split of a lease (物件分割・契約分割, STEP L15d, {@code SPEC_LEASE.md} 20).
 *
 * <ul>
 * <li>Asset split ({@link #splitLeaseAgreementAsset}): a leased line of quantity N is split into
 *     several lines; the acquisition cost, the depreciation records (operating) and the core
 *     FixedAsset (purchase cost, salvage value, depreciation) are allocated by quantity, the last
 *     line absorbing the rounding. No GL entry (the accounts are per agreement). Maintenance plans
 *     and public charge attributes stay on the source line.</li>
 * <li>Contract split ({@link #splitLeaseAgreement}): an ACTIVE agreement (no re-lease, no
 *     unreversed change of terms) is accounted through the split date (billing of the due
 *     installments, deferred profit release, depreciation), then every asset is moved to one of
 *     the new agreements (same lessee) per group. Each new agreement gets a new FinanceContract,
 *     a confirmed schedule (revision 1) of the original installments k..n (k = first unbilled;
 *     numbers and dates kept) allocated by acquisition cost (last agreement absorbs the rounding),
 *     the components, a copy of the acceptance, the deferred lease profit not yet released
 *     (finance) or the depreciation history (operating), the maintenance plans and the public
 *     charge attributes. The advance lease payments / deposits left are transferred by the same
 *     ratio (refund on the original, receipt on the new agreement; no cash). The original becomes
 *     LEASEAGR_SPLIT: no more billing, its billed installments are still collected, and it is
 *     completed when they are settled. A recording transaction moves the lease investment and the
 *     deferred lease profit (or the leased asset and its accumulated depreciation) from the
 *     original to the new agreements.</li>
 * </ul>
 */
public final class LeaseSplitServices {

    private static final String MODULE = LeaseSplitServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String STATUS_SPLIT = "LEASEAGR_SPLIT";
    public static final String ASSET_SPLIT = "LSAST_SPLIT";

    private static final List<String> STAMPS = UtilMisc.toList("lastUpdatedStamp", "lastUpdatedTxStamp",
            "createdStamp", "createdTxStamp");

    private LeaseSplitServices() {
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

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    /** Copy of a value without the entity stamps (for a new row). */
    private static Map<String, Object> copyFields(GenericValue value) {
        Map<String, Object> fields = new HashMap<>(value.getAllFields());
        for (String stamp : STAMPS) {
            fields.remove(stamp);
        }
        return fields;
    }

    // ------------------------------------------------------------------
    // Status helpers (used by the other lease services)
    // ------------------------------------------------------------------

    /** True when the agreement is LEASEAGR_SPLIT (billing moved to the new agreements, still collected). */
    public static boolean isSplit(GenericValue agreement) {
        return agreement != null && STATUS_SPLIT.equals(agreement.getString("statusId"));
    }

    /** True when the agreement was split (LEASEAGR_SPLIT, or completed after the split). */
    public static boolean wasSplit(Delegator delegator, GenericValue agreement) throws GenericEntityException {
        return isSplit(agreement) || EntityQuery.use(delegator).from("LeaseAgreementSplit")
                .where("leaseAgreementId", agreement.getString("leaseAgreementId")).queryCount() > 0;
    }

    /** Principal outstanding at the start of the agreement's schedule (opening principal of a split agreement). */
    public static BigDecimal startPrincipal(GenericValue agreement) {
        return agreement.getBigDecimal("openingPrincipal") != null ? agreement.getBigDecimal("openingPrincipal")
                : nz(agreement.getBigDecimal("acquisitionCost"));
    }

    // ------------------------------------------------------------------
    // Allocation
    // ------------------------------------------------------------------

    /** {@code total} allocated by {@code ratios} (currency rounding); the last share absorbs the rounding. */
    static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> ratios, int scale, RoundingMode mode) {
        BigDecimal t = nz(total);
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < ratios.size(); i++) {
            BigDecimal share = i == ratios.size() - 1 ? t.subtract(sum) : t.multiply(ratios.get(i)).setScale(scale, mode);
            shares.add(share);
            sum = sum.add(share);
        }
        return shares;
    }

    private static String nextAssetSeqId(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        long max = 0;
        for (GenericValue a : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            try {
                max = Math.max(max, Long.parseLong(a.getString("assetSeqId")));
            } catch (NumberFormatException e) {
                max = max + 1;
            }
        }
        return String.format("%05d", max + 1);
    }

    // ------------------------------------------------------------------
    // Asset split
    // ------------------------------------------------------------------

    /**
     * Split a leased asset line of an ACTIVE agreement by quantity: one new line per given quantity,
     * the source keeps the rest.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> splitLeaseAgreementAsset(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        Timestamp splitDate = UtilDateTime.getDayStart(context.get("splitDate") != null
                ? (Timestamp) context.get("splitDate") : UtilDateTime.nowTimestamp());
        List<BigDecimal> quantities = new ArrayList<>();
        if (context.get("quantities") != null) {
            for (Object q : (List<Object>) context.get("quantities")) {
                quantities.add(q instanceof BigDecimal ? (BigDecimal) q : new BigDecimal(q.toString().trim()));
            }
        }
        if (context.get("splitQuantity") != null) {
            quantities.add((BigDecimal) context.get("splitQuantity"));
        }
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
            GenericValue source = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (source == null || !LeaseExpiryServices.ASSET_LEASED.equals(source.getString("assetStatusId"))) {
                return error("FinanceLeaseAssetNotLeased", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            BigDecimal total = nz(source.getBigDecimal("quantity"));
            BigDecimal moved = BigDecimal.ZERO;
            for (BigDecimal q : quantities) {
                if (q == null || q.signum() <= 0) {
                    return error("FinanceLeaseAssetSplitQuantityInvalid", UtilMisc.toMap("quantity", total), locale);
                }
                moved = moved.add(q);
            }
            if (quantities.isEmpty() || moved.compareTo(total) >= 0) {
                return error("FinanceLeaseAssetSplitQuantityInvalid", UtilMisc.toMap("quantity", total), locale);
            }
            String currency = agreement.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
            // Ratios: the source line (kept quantity) first, then the new lines; the last new line absorbs the rounding.
            List<BigDecimal> ratios = new ArrayList<>();
            ratios.add(total.subtract(moved).divide(total, 16, RoundingMode.HALF_UP));
            for (BigDecimal q : quantities) {
                ratios.add(q.divide(total, 16, RoundingMode.HALF_UP));
            }
            List<BigDecimal> costs = allocate(source.getBigDecimal("acquisitionCost"), ratios, scale, mode);
            List<BigDecimal> reclass = source.getBigDecimal("reclassifiedResidual") == null ? null
                    : allocate(source.getBigDecimal("reclassifiedResidual"), ratios, scale, mode);

            // Core FixedAsset: purchase cost, salvage value and depreciation by quantity.
            GenericValue fixedAsset = UtilValidate.isEmpty(source.getString("fixedAssetId")) ? null
                    : EntityQuery.use(delegator).from("FixedAsset").where("fixedAssetId", source.getString("fixedAssetId")).queryOne();
            List<BigDecimal> faCost = fixedAsset == null ? null : allocate(fixedAsset.getBigDecimal("purchaseCost"), ratios, scale, mode);
            List<BigDecimal> faSalvage = fixedAsset == null || fixedAsset.getBigDecimal("salvageValue") == null ? null
                    : allocate(fixedAsset.getBigDecimal("salvageValue"), ratios, scale, mode);
            List<BigDecimal> faDepr = fixedAsset == null || fixedAsset.getBigDecimal("depreciation") == null ? null
                    : allocate(fixedAsset.getBigDecimal("depreciation"), ratios, scale, mode);

            List<String> newSeqIds = new ArrayList<>();
            List<String> newFixedAssetIds = new ArrayList<>();
            for (int i = 0; i < quantities.size(); i++) {
                String newSeq = nextAssetSeqId(delegator, leaseAgreementId);
                String newFixedAssetId = null;
                if (fixedAsset != null) {
                    GenericValue fa = delegator.makeValue("FixedAsset", copyFields(fixedAsset));
                    newFixedAssetId = delegator.getNextSeqId("FixedAsset");
                    fa.set("fixedAssetId", newFixedAssetId);
                    fa.set("fixedAssetName", (fixedAsset.getString("fixedAssetName") == null ? source.getString("assetName")
                            : fixedAsset.getString("fixedAssetName")) + " (" + newSeq + ")");
                    fa.set("purchaseCost", faCost.get(i + 1));
                    if (faSalvage != null) {
                        fa.set("salvageValue", faSalvage.get(i + 1));
                    }
                    if (faDepr != null) {
                        fa.set("depreciation", faDepr.get(i + 1));
                    }
                    fa.create();
                }
                GenericValue line = delegator.makeValue("LeaseAgreementAsset", copyFields(source));
                line.set("assetSeqId", newSeq);
                line.set("quantity", quantities.get(i));
                line.set("acquisitionCost", costs.get(i + 1));
                line.set("fixedAssetId", newFixedAssetId);
                line.set("reclassifiedResidual", reclass == null ? null : reclass.get(i + 1));
                line.set("sourceLeaseAgreementId", leaseAgreementId);
                line.set("sourceAssetSeqId", assetSeqId);
                line.create();
                newSeqIds.add(newSeq);
                newFixedAssetIds.add(newFixedAssetId);

                GenericValue history = delegator.makeValue("LeaseAssetSplit");
                history.set("leaseAssetSplitId", delegator.getNextSeqId("LeaseAssetSplit"));
                history.set("leaseAgreementId", leaseAgreementId);
                history.set("sourceAssetSeqId", assetSeqId);
                history.set("newAssetSeqId", newSeq);
                history.set("sourceQuantity", total);
                history.set("quantity", quantities.get(i));
                history.set("acquisitionCost", costs.get(i + 1));
                history.set("fixedAssetId", newFixedAssetId);
                history.set("splitDate", splitDate);
                history.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
                history.set("createdDate", UtilDateTime.nowTimestamp());
                history.create();
            }
            if (fixedAsset != null) {
                fixedAsset.set("purchaseCost", faCost.get(0));
                if (faSalvage != null) {
                    fixedAsset.set("salvageValue", faSalvage.get(0));
                }
                if (faDepr != null) {
                    fixedAsset.set("depreciation", faDepr.get(0));
                }
                fixedAsset.store();
            }
            source.set("quantity", total.subtract(moved));
            source.set("acquisitionCost", costs.get(0));
            if (reclass != null) {
                source.set("reclassifiedResidual", reclass.get(0));
            }
            source.store();

            // Depreciation records (operating / re-leased): the cumulative amount of each period by quantity.
            List<GenericValue> deps = EntityQuery.use(delegator).from("LeaseAssetDepreciation")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).orderBy("periodNumber").queryList();
            List<BigDecimal> previous = null;
            for (GenericValue dep : deps) {
                List<BigDecimal> cums = allocate(dep.getBigDecimal("cumulativeAmount"), ratios, scale, mode);
                for (int i = 0; i < ratios.size(); i++) {
                    BigDecimal amount = cums.get(i).subtract(previous == null ? BigDecimal.ZERO : previous.get(i));
                    BigDecimal lineCost = costs.get(i);
                    if (reclass != null && !LeaseProductHelper.isOperating(agreement)) {
                        lineCost = reclass.get(i);
                    }
                    if (i == 0) {
                        dep.set("amount", amount);
                        dep.set("cumulativeAmount", cums.get(0));
                        dep.set("bookValue", lineCost.subtract(cums.get(0)));
                        dep.store();
                    } else {
                        GenericValue copy = delegator.makeValue("LeaseAssetDepreciation", copyFields(dep));
                        copy.set("assetSeqId", newSeqIds.get(i - 1));
                        copy.set("fixedAssetId", newFixedAssetIds.get(i - 1));
                        copy.set("amount", amount);
                        copy.set("cumulativeAmount", cums.get(i));
                        copy.set("bookValue", lineCost.subtract(cums.get(i)));
                        copy.create();
                    }
                }
                previous = cums;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("newAssetSeqIds", newSeqIds);
            result.put("newFixedAssetIds", newFixedAssetIds);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Contract split
    // ------------------------------------------------------------------

    /** Asset groups of the request: {@code assetGroups} (lists or comma-separated strings), else the first group and the rest. */
    @SuppressWarnings("unchecked")
    private static List<List<String>> groups(Map<String, Object> context, List<GenericValue> lines) {
        List<List<String>> groups = new ArrayList<>();
        Object given = context.get("assetGroups");
        if (given instanceof List && !((List<Object>) given).isEmpty()) {
            for (Object g : (List<Object>) given) {
                groups.add(parseIds(g));
            }
            return groups;
        }
        List<String> first = parseIds(context.get("newAgreementAssetSeqIds"));
        List<String> rest = new ArrayList<>();
        for (GenericValue line : lines) {
            if (!first.contains(line.getString("assetSeqId"))) {
                rest.add(line.getString("assetSeqId"));
            }
        }
        groups.add(first);
        groups.add(rest);
        return groups;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseIds(Object value) {
        List<String> ids = new ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<Object>) value) {
                if (o != null && UtilValidate.isNotEmpty(o.toString().trim())) {
                    ids.add(o.toString().trim());
                }
            }
        } else if (value != null) {
            for (String s : value.toString().split("[,\\s]+")) {
                if (UtilValidate.isNotEmpty(s.trim())) {
                    ids.add(s.trim());
                }
            }
        }
        return ids;
    }

    /** Unpaid principal of the billed installments (payments allocated tax, components, interest, then principal). */
    static BigDecimal unpaidBilledPrincipal(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        BigDecimal unpaid = BigDecimal.ZERO;
        for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", link.getString("invoiceId"))
                    .cache(false).queryOne();
            if (invoice == null || "INVOICE_CANCELLED".equals(invoice.getString("statusId"))
                    || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))) {
                continue;
            }
            BigDecimal open = nz(InvoiceWorker.getInvoiceNotApplied(invoice));
            // The unpaid amount is principal first (payments go to tax, components and interest first).
            unpaid = unpaid.add(open.min(nz(link.getBigDecimal("principalPortion"))).max(BigDecimal.ZERO));
        }
        return unpaid;
    }

    /** Split an ACTIVE agreement into new agreements per group of assets (see the class comment). */
    public static Map<String, Object> splitLeaseAgreement(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp splitDate = UtilDateTime.getDayStart(context.get("splitDate") != null
                ? (Timestamp) context.get("splitDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String contractId = agreement.getString("financeContractId");
            FinanceContractHelper.lock(delegator, contractId);
            agreement = findAgreement(delegator, leaseAgreementId);

            // 1. Conditions.
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (!LeaseReLeaseServices.reLeases(delegator, leaseAgreementId).isEmpty()) {
                return error("FinanceLeaseSplitReLease", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (EntityQuery.use(delegator).from("LeaseModification")
                    .where("leaseAgreementId", leaseAgreementId, "reversedDate", null).queryCount() > 0) {
                return error("FinanceLeaseSplitModification", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            Timestamp start = agreement.getTimestamp("leaseStartDate");
            Timestamp end = agreement.getTimestamp("leaseEndDate");
            if (splitDate.before(UtilDateTime.getDayStart(start)) || splitDate.after(UtilDateTime.nowTimestamp())
                    || (end != null && splitDate.after(end))) {
                return error("FinanceLeaseSplitDateInvalid", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "splitDate", splitDate), locale);
            }
            List<GenericValue> lines = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).orderBy("assetSeqId").queryList();
            Map<String, GenericValue> lineById = new LinkedHashMap<>();
            for (GenericValue line : lines) {
                if (!LeaseExpiryServices.ASSET_LEASED.equals(line.getString("assetStatusId"))) {
                    return error("FinanceLeaseAssetNotLeased", UtilMisc.toMap("assetSeqId", line.getString("assetSeqId")), locale);
                }
                lineById.put(line.getString("assetSeqId"), line);
            }
            List<List<String>> groups = groups(context, lines);
            Set<String> seen = new HashSet<>();
            for (List<String> g : groups) {
                if (g.isEmpty()) {
                    return error("FinanceLeaseSplitGroupsInvalid", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
                }
                for (String id : g) {
                    if (!lineById.containsKey(id) || !seen.add(id)) {
                        return error("FinanceLeaseSplitGroupsInvalid", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
                    }
                }
            }
            if (groups.size() < 2 || seen.size() != lineById.size()) {
                return error("FinanceLeaseSplitGroupsInvalid", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }

            String organizationPartyId = agreement.getString("organizationPartyId");
            String lessee = agreement.getString("lesseePartyId");
            String currency = agreement.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
            boolean operating = LeaseProductHelper.isOperating(agreement);
            LeaseRevenueStrategy strategy = LeaseRevenueStrategy.REGISTRY.get(agreement.getString("revenueMethodEnumId"));
            boolean defers = strategy != null && strategy.defersProfit();

            // 2. Account the lease through the split date.
            Timestamp dayEnd = UtilDateTime.getDayEnd(splitDate);
            long revision = LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId);
            List<GenericValue> rows = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                    .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", revision)
                    .orderBy("installmentNumber").queryList();
            for (GenericValue row : rows) {
                if (row.getTimestamp("dueDate") != null && !row.getTimestamp("dueDate").after(dayEnd)) {
                    check(LeaseAccountingServices.createLeaseInstallmentInvoice(dctx, UtilMisc.toMap("leaseAgreementId",
                            leaseAgreementId, "installmentNumber", row.getLong("installmentNumber"),
                            "userLogin", userLogin, "locale", locale)));
                }
            }
            check(LeaseAccountingServices.recognizeLeaseProfit(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                    "recognitionDate", splitDate, "userLogin", userLogin, "locale", locale)));
            if (operating) {
                check(LeaseDepreciationServices.depreciateLeaseAssets(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "depreciationDate", splitDate, "userLogin", userLogin, "locale", locale)));
            }
            agreement = findAgreement(delegator, leaseAgreementId);

            long billedMax = 0;
            for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                billedMax = Math.max(billedMax, link.getLong("installmentNumber"));
            }
            long first = rows.get(0).getLong("installmentNumber");
            long k = Math.max(billedMax + 1, first);
            List<GenericValue> unbilled = new ArrayList<>();
            GenericValue previousRow = null;
            for (GenericValue row : rows) {
                long n = row.getLong("installmentNumber");
                if (n >= k) {
                    unbilled.add(row);
                } else if (n == k - 1) {
                    previousRow = row;
                }
            }
            if (unbilled.isEmpty()) {
                return error("FinanceLeaseSplitNothingUnbilled", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }

            // 3. Ratios and the amounts taken over.
            List<BigDecimal> groupCost = new ArrayList<>();
            BigDecimal totalCost = BigDecimal.ZERO;
            for (List<String> g : groups) {
                BigDecimal c = BigDecimal.ZERO;
                for (String id : g) {
                    c = c.add(nz(lineById.get(id).getBigDecimal("acquisitionCost")));
                }
                groupCost.add(c);
                totalCost = totalCost.add(c);
            }
            if (totalCost.signum() <= 0) {
                return error("FinanceLeaseSplitGroupsInvalid", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            List<BigDecimal> ratios = new ArrayList<>();
            for (BigDecimal c : groupCost) {
                ratios.add(c.divide(totalCost, 16, RoundingMode.HALF_UP));
            }
            int groupCount = groups.size();
            BigDecimal remainingBefore = previousRow == null ? startPrincipal(agreement)
                    : nz(previousRow.getBigDecimal("remainingPrincipal"));
            BigDecimal recognized = BigDecimal.ZERO;
            for (GenericValue rec : EntityQuery.use(delegator).from("LeaseProfitRecognition")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                recognized = recognized.add(nz(rec.getBigDecimal("amount")));
            }
            BigDecimal deferred = defers ? nz(agreement.getBigDecimal("totalInterestAmount")).subtract(recognized).max(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            List<BigDecimal> residuals = allocate(agreement.getBigDecimal("residualValue"), ratios, scale, mode);
            List<BigDecimal> openings = allocate(remainingBefore, ratios, scale, mode);
            List<BigDecimal> deferreds = allocate(deferred, ratios, scale, mode);
            List<BigDecimal> monthlies = allocate(agreement.getBigDecimal("monthlyLeaseAmount"), ratios, scale, mode);
            boolean lumpSum = LeaseTaxHelper.TIMING_AT_START.equals(agreement.getString("taxTimingEnumId"));

            // Per installment and group: lease amount, interest, tax.
            Map<Long, List<BigDecimal>> leaseByRow = new LinkedHashMap<>();
            Map<Long, List<BigDecimal>> interestByRow = new LinkedHashMap<>();
            Map<Long, List<BigDecimal>> taxByRow = new LinkedHashMap<>();
            for (GenericValue row : unbilled) {
                long n = row.getLong("installmentNumber");
                leaseByRow.put(n, allocate(row.getBigDecimal("leaseAmount"), ratios, scale, mode));
                interestByRow.put(n, allocate(row.getBigDecimal("interestPortion"), ratios, scale, mode));
                taxByRow.put(n, allocate(row.getBigDecimal("taxAmount"), ratios, scale, mode));
            }
            // Components per installment, component and group.
            List<GenericValue> componentDefs = EntityQuery.use(delegator).from("LeaseAgreementComponent")
                    .where("leaseAgreementId", leaseAgreementId).orderBy("componentSeqId").queryList();
            List<GenericValue> componentRows = EntityQuery.use(delegator).from("LeaseAgrPaySchedComp")
                    .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", revision)
                    .orderBy("installmentNumber", "componentSeqId").queryList();

            // 4. The new agreements.
            Timestamp nextDay = UtilDateTime.adjustTimestamp(splitDate, Calendar.DAY_OF_MONTH, 1);
            GenericValue acceptance = EntityQuery.use(delegator).from("LeaseAcceptance")
                    .where("leaseAgreementId", leaseAgreementId).queryOne();
            GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                    .where("financeContractId", contractId).queryOne();
            List<GenericValue> newAgreements = new ArrayList<>();
            List<String> newIds = new ArrayList<>();
            List<GenericValue> splitRows = new ArrayList<>();
            List<FinanceGlHelper.Line> glLines = new ArrayList<>();
            String asset = LeaseRevenueStrategy.assetAccountType(agreement);
            for (int g = 0; g < groupCount; g++) {
                List<String> ids = groups.get(g);
                String newContractId = FinanceContractHelper.createContract(delegator, FinanceContractHelper.TYPE_LEASE,
                        lessee, organizationPartyId, agreement.getString("productId"), currency, openings.get(g), splitDate,
                        FinanceContractHelper.STATUS_ACTIVE);
                GenericValue newContract = EntityQuery.use(delegator).from("FinanceContract")
                        .where("financeContractId", newContractId).queryOne();
                if (contract != null && UtilValidate.isNotEmpty(contract.getString("parentFinanceContractId"))) {
                    newContract.set("parentFinanceContractId", contract.getString("parentFinanceContractId"));
                    newContract.store();
                }
                // Other parties of the contract (participants, guarantors, ...) as of the split date.
                for (GenericValue party : FinanceContractPartyHelper.findParties(delegator, contractId, null, splitDate)) {
                    if ("Y".equals(party.getString("isPrimary"))) {
                        continue;
                    }
                    GenericValue copy = delegator.makeValue("FinanceContractParty", copyFields(party));
                    copy.set("financeContractId", newContractId);
                    copy.set("fromDate", splitDate);
                    copy.set("createdDate", UtilDateTime.nowTimestamp());
                    copy.create();
                }

                String newId = delegator.getNextSeqId("LeaseAgreement");
                GenericValue b = delegator.makeValue("LeaseAgreement", copyFields(agreement));
                b.set("leaseAgreementId", newId);
                b.set("financeContractId", newContractId);
                b.set("leaseQuoteId", null);
                b.set("statusId", "LEASEAGR_ACTIVE");
                b.set("agreementDate", splitDate);
                b.set("completedDate", null);
                b.set("acquisitionCost", groupCost.get(g));
                b.set("residualValue", residuals.get(g));
                b.set("monthlyLeaseAmount", monthlies.get(g));
                b.set("monthlyRateFactor", groupCost.get(g).signum() == 0 ? null
                        : monthlies.get(g).divide(groupCost.get(g), 6, RoundingMode.HALF_UP));
                b.set("splitFromLeaseAgreementId", leaseAgreementId);
                b.set("openingPrincipal", openings.get(g));
                Set<String> suppliers = new HashSet<>();
                for (String id : ids) {
                    suppliers.add(lineById.get(id).getString("supplierPartyId"));
                }
                b.set("supplierPartyId", suppliers.size() == 1 ? suppliers.iterator().next() : null);
                b.create();

                // Confirmed schedule (revision 1): installments k..n, numbers and dates kept.
                BigDecimal running = openings.get(g);
                BigDecimal totalLease = BigDecimal.ZERO;
                BigDecimal totalInterest = BigDecimal.ZERO;
                BigDecimal totalTax = BigDecimal.ZERO;
                for (int i = 0; i < unbilled.size(); i++) {
                    GenericValue row = unbilled.get(i);
                    long n = row.getLong("installmentNumber");
                    BigDecimal lease = leaseByRow.get(n).get(g);
                    BigDecimal interest = interestByRow.get(n).get(g);
                    BigDecimal principal = lease.subtract(interest);
                    BigDecimal remaining = running.subtract(principal);
                    if (i == unbilled.size() - 1 && remaining.compareTo(residuals.get(g)) != 0) {
                        // The last installment ends exactly at the residual value of the group.
                        principal = running.subtract(residuals.get(g));
                        interest = lease.subtract(principal);
                        remaining = residuals.get(g);
                    }
                    GenericValue line = delegator.makeValue("LeaseAgreementPaymentSchedule", copyFields(row));
                    line.set("leaseAgreementId", newId);
                    line.set("scheduleRevisionNumber", 1L);
                    line.set("leaseAmount", lease);
                    line.set("principalPortion", principal);
                    line.set("interestPortion", interest);
                    line.set("taxAmount", taxByRow.get(n).get(g));
                    line.set("remainingPrincipal", remaining);
                    line.create();
                    running = remaining;
                    totalLease = totalLease.add(lease);
                    totalInterest = totalInterest.add(interest);
                    totalTax = totalTax.add(taxByRow.get(n).get(g));
                }

                // Components: definitions (monthly amount by ratio) and the rows of the new installments.
                BigDecimal includedComponents = BigDecimal.ZERO;
                for (GenericValue def : componentDefs) {
                    String seq = def.getString("componentSeqId");
                    GenericValue d = delegator.makeValue("LeaseAgreementComponent", copyFields(def));
                    d.set("leaseAgreementId", newId);
                    d.set("monthlyAmount", allocate(def.getBigDecimal("monthlyAmount"), ratios, scale, mode).get(g));
                    BigDecimal cTotal = BigDecimal.ZERO;
                    BigDecimal cTax = BigDecimal.ZERO;
                    List<GenericValue> newRows = new ArrayList<>();
                    for (GenericValue c : componentRows) {
                        if (!seq.equals(c.getString("componentSeqId")) || c.getLong("installmentNumber") < k) {
                            continue;
                        }
                        GenericValue nc = delegator.makeValue("LeaseAgrPaySchedComp", copyFields(c));
                        nc.set("leaseAgreementId", newId);
                        nc.set("scheduleRevisionNumber", 1L);
                        BigDecimal amount = allocate(c.getBigDecimal("amount"), ratios, scale, mode).get(g);
                        BigDecimal tax = allocate(c.getBigDecimal("taxAmount"), ratios, scale, mode).get(g);
                        nc.set("amount", amount);
                        nc.set("taxAmount", tax);
                        newRows.add(nc);
                        cTotal = cTotal.add(amount);
                        cTax = cTax.add(tax);
                        if (LeaseComponentCalculator.REV_INCLUDED.equals(c.getString("revenueTreatmentEnumId"))) {
                            includedComponents = includedComponents.add(amount);
                        }
                    }
                    d.set("totalAmount", cTotal);
                    d.set("totalTaxAmount", cTax);
                    d.create();
                    for (GenericValue nc : newRows) {
                        nc.create();
                    }
                }

                // Assets: new lines (same FixedAsset); the original lines are marked as moved.
                List<String> newSeqs = new ArrayList<>();
                Map<String, String> seqMap = new HashMap<>();
                int seqNo = 1;
                for (String id : ids) {
                    GenericValue src = lineById.get(id);
                    String newSeq = String.format("%05d", seqNo++);
                    GenericValue line = delegator.makeValue("LeaseAgreementAsset", copyFields(src));
                    line.set("leaseAgreementId", newId);
                    line.set("assetSeqId", newSeq);
                    line.set("sourceLeaseAgreementId", leaseAgreementId);
                    line.set("sourceAssetSeqId", id);
                    line.create();
                    newSeqs.add(newSeq);
                    seqMap.put(id, newSeq);
                    for (GenericValue pc : EntityQuery.use(delegator).from("LeaseAssetPublicCharge")
                            .where("leaseAgreementId", leaseAgreementId, "assetSeqId", id).queryList()) {
                        GenericValue copy = delegator.makeValue("LeaseAssetPublicCharge", copyFields(pc));
                        copy.set("leaseAgreementId", newId);
                        copy.set("assetSeqId", newSeq);
                        copy.create();
                    }
                }

                // Acceptance (lease start of the original; the start entries stay with the original).
                if (acceptance != null) {
                    GenericValue acc = delegator.makeValue("LeaseAcceptance", copyFields(acceptance));
                    acc.set("leaseAgreementId", newId);
                    acc.set("scheduleRevisionNumber", 1L);
                    acc.set("startAcctgTransId", null);
                    acc.set("startTaxAcctgTransId", null);
                    acc.set("receiptPrintedDate", null);
                    acc.set("comments", "Split from lease " + leaseAgreementId);
                    acc.set("createdDate", UtilDateTime.nowTimestamp());
                    acc.create();
                }

                // Operating: the depreciation history of the moved assets.
                BigDecimal accumulated = BigDecimal.ZERO;
                if (operating) {
                    for (String id : ids) {
                        for (GenericValue dep : EntityQuery.use(delegator).from("LeaseAssetDepreciation")
                                .where("leaseAgreementId", leaseAgreementId, "assetSeqId", id).orderBy("periodNumber").queryList()) {
                            GenericValue copy = delegator.makeValue("LeaseAssetDepreciation", copyFields(dep));
                            copy.set("leaseAgreementId", newId);
                            copy.set("assetSeqId", seqMap.get(id));
                            copy.create();
                            accumulated = accumulated.add(nz(dep.getBigDecimal("amount")));
                        }
                    }
                }

                b.set("totalLeaseAmount", totalLease);
                b.set("totalTaxAmount", totalTax);
                b.set("totalInterestAmount", defers ? deferreds.get(g) : totalInterest);
                b.store();

                // Finance: the release of the new agreement continues from the split date. Its allocator
                // already earned part of the interest of installment k through the split date (released by
                // the original): an opening record (no entry) carries that cumulative, so the new agreement
                // releases exactly the deferred profit taken over.
                if (defers) {
                    LeaseProfitAllocator allocator = LeaseAccountingServices.allocator(delegator, b);
                    GenericValue rec = delegator.makeValue("LeaseProfitRecognition");
                    rec.set("leaseProfitRecognitionId", delegator.getNextSeqId("LeaseProfitRecognition"));
                    rec.set("leaseAgreementId", newId);
                    rec.set("fromDate", UtilDateTime.getDayStart(start));
                    rec.set("thruDate", splitDate);
                    rec.set("amount", BigDecimal.ZERO);
                    rec.set("cumulativeAmount", allocator.total().subtract(deferreds.get(g)));
                    rec.set("currencyUomId", currency);
                    rec.set("createdDate", UtilDateTime.nowTimestamp());
                    rec.create();
                }

                // Transfer entries (same accounts: they record the move per agreement).
                if (operating) {
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ASSET, true, groupCost.get(g)));
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ASSET, false, groupCost.get(g)));
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ACCUM_DEPR, true, accumulated));
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ACCUM_DEPR, false, accumulated));
                } else {
                    BigDecimal investment = totalLease.add(includedComponents).add(residuals.get(g));
                    glLines.add(new FinanceGlHelper.Line(asset, true, investment));
                    glLines.add(new FinanceGlHelper.Line(asset, false, investment));
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_DEFERRED_PROFIT, true, deferreds.get(g)));
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_DEFERRED_PROFIT, false, deferreds.get(g)));
                }
                if (lumpSum) {
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_TAX_RECEIVABLE, true, totalTax));
                    glLines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_TAX_RECEIVABLE, false, totalTax));
                }

                GenericValue split = delegator.makeValue("LeaseAgreementSplit");
                split.set("leaseAgreementId", leaseAgreementId);
                split.set("newLeaseAgreementId", newId);
                split.set("splitDate", splitDate);
                split.set("effectiveInstallmentNumber", k);
                split.set("allocationRatio", ratios.get(g).setScale(6, RoundingMode.HALF_UP));
                split.set("acquisitionCost", groupCost.get(g));
                split.set("residualValue", residuals.get(g));
                split.set("openingPrincipal", openings.get(g));
                split.set("unbilledLeaseAmount", totalLease);
                split.set("deferredProfit", defers ? deferreds.get(g) : null);
                split.set("accumulatedDepreciation", operating ? accumulated : null);
                split.set("reason", context.get("reason"));
                split.set("currencyUomId", currency);
                split.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
                split.set("createdDate", UtilDateTime.nowTimestamp());
                splitRows.add(split);
                newAgreements.add(b);
                newIds.add(newId);

                // Maintenance plans of the moved assets: ended on the original, continued on the new agreement.
                for (GenericValue plan : EntityQuery.use(delegator).from("LeaseMaintPlan")
                        .where("leaseAgreementId", leaseAgreementId, "thruDate", null).queryList()) {
                    String planAsset = plan.getString("assetSeqId");
                    if (!ids.contains(planAsset)) {
                        continue;
                    }
                    int interval = plan.getLong("intervalMonths").intValue();
                    Timestamp due = plan.getTimestamp("firstDueDate") != null ? plan.getTimestamp("firstDueDate")
                            : UtilDateTime.adjustTimestamp(UtilDateTime.getDayStart(start), Calendar.MONTH, interval);
                    for (int i = 0; due.before(nextDay) && i < 1200; i++) {
                        due = UtilDateTime.adjustTimestamp(due, Calendar.MONTH, interval);
                    }
                    check(dispatcher.runSync("endLeaseMaintPlan", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                            "maintPlanSeqId", plan.getString("maintPlanSeqId"), "thruDate", nextDay, "userLogin", userLogin)));
                    Map<String, Object> planInput = new HashMap<>();
                    planInput.put("leaseAgreementId", newId);
                    planInput.put("assetSeqId", seqMap.get(planAsset));
                    planInput.put("productMaintTypeId", plan.getString("productMaintTypeId"));
                    planInput.put("intervalMonths", plan.getLong("intervalMonths"));
                    planInput.put("firstDueDate", due);
                    planInput.put("providerPartyId", plan.getString("providerPartyId"));
                    planInput.put("estimatedCost", plan.getBigDecimal("estimatedCost"));
                    planInput.put("description", plan.getString("description"));
                    planInput.put("userLogin", userLogin);
                    check(dispatcher.runSync("createLeaseMaintPlan", planInput));
                }
            }

            // 5. The original: lines moved, status SPLIT, the exposure is the unpaid billed principal.
            for (GenericValue line : lines) {
                line.set("assetStatusId", ASSET_SPLIT);
                line.store();
            }
            agreement = findAgreement(delegator, leaseAgreementId);
            agreement.set("statusId", STATUS_SPLIT);
            agreement.store();
            if (contract != null) {
                contract.refresh();
                contract.set("principalAmount", unpaidBilledPrincipal(delegator, leaseAgreementId));
                contract.store();
            }
            if (end != null) {
                FinanceWorkEffortHelper.cancelTask(delegator, dispatcher, contractId, FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE,
                        end.toLocalDateTime().toLocalDate().toString(), userLogin);
            }

            // 6. Recording transaction of the move.
            String acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                    "Lease split " + leaseAgreementId + " to " + String.join(", ", newIds), splitDate,
                    organizationPartyId, lessee, currency, glLines, userLogin);

            // 7. Advance lease payments and deposits left: transferred by the same ratio.
            List<BigDecimal[]> transferred = LeaseAdvanceServices.transferForSplit(dctx, agreement, newAgreements, ratios,
                    splitDate, userLogin);
            for (int g = 0; g < groupCount; g++) {
                GenericValue split = splitRows.get(g);
                split.set("acctgTransId", acctgTransId);
                split.set("advanceTransferred", transferred.get(g)[0]);
                split.set("depositTransferred", transferred.get(g)[1]);
                split.create();
            }

            // 8. The original completes once its billed installments are settled.
            boolean completed = LeaseExpiryServices.tryComplete(dctx, findAgreement(delegator, leaseAgreementId), splitDate,
                    userLogin, locale) == null;

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("newLeaseAgreementIds", newIds);
            result.put("effectiveInstallmentNumber", k);
            result.put("acctgTransId", acctgTransId);
            result.put("agreementCompleted", completed);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
