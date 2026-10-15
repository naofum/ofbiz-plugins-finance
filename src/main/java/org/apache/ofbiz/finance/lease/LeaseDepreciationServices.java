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
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Depreciation of the leased assets of an operating lease (STEP L12): the
 * lease-term straight-line method (リース期間定額法). Each agreement asset is
 * depreciated over the lease term to its residual value (the agreement residual
 * allocated by acquisition cost), one amount per lease month:
 * cumulative(k) = (cost - residual) x k / term, rounded with the currency rounding;
 * the last month takes the remainder so the book value ends at the residual.
 *
 * <p>Core {@code calculateFixedAssetDepreciation} is not used: it works in whole
 * years from the current date and books no GL entries. The depreciation total of
 * the core {@code FixedAsset} ({@code depreciation}) is updated here.
 */
public final class LeaseDepreciationServices {

    private static final String MODULE = LeaseDepreciationServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    private LeaseDepreciationServices() {
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

    /** Last day of lease month {@code k} (1-based). */
    static Timestamp periodThru(Timestamp leaseStart, int k) {
        return minusOneDay(addMonths(UtilDateTime.getDayStart(leaseStart), k));
    }

    /** Cumulative depreciation of a depreciable amount after {@code k} of {@code term} months. */
    public static BigDecimal cumulative(BigDecimal depreciable, int k, int term, int scale, RoundingMode mode) {
        if (term <= 0) {
            throw new IllegalArgumentException("term must be greater than zero");
        }
        if (k <= 0) {
            return BigDecimal.ZERO.setScale(scale, mode);
        }
        if (k >= term) {
            return depreciable.setScale(scale, mode);
        }
        return depreciable.multiply(BigDecimal.valueOf(k)).divide(BigDecimal.valueOf(term), scale, mode);
    }

    /**
     * STEP L15: cumulative depreciation after {@code k} months when the term was changed after
     * {@code baseMonth} months with {@code baseCumulative} depreciated: the remainder is spread
     * straight-line over months baseMonth+1..term; the last month takes the remainder.
     */
    public static BigDecimal cumulativeFromBase(BigDecimal depreciable, BigDecimal baseCumulative, int baseMonth, int k,
            int term, int scale, RoundingMode mode) {
        if (k <= baseMonth) {
            return baseCumulative.setScale(scale, mode);
        }
        if (k >= term) {
            return depreciable.setScale(scale, mode);
        }
        BigDecimal remaining = depreciable.subtract(baseCumulative);
        return baseCumulative.add(remaining.multiply(BigDecimal.valueOf(k - baseMonth))
                .divide(BigDecimal.valueOf(term - baseMonth), scale, mode));
    }

    /** Accumulated depreciation of an agreement (or of one asset when assetSeqId is given). */
    public static BigDecimal accumulatedDepreciation(Delegator delegator, String leaseAgreementId, String assetSeqId)
            throws GenericEntityException {
        EntityQuery q = EntityQuery.use(delegator).from("LeaseAssetDepreciation");
        if (UtilValidate.isNotEmpty(assetSeqId)) {
            q.where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId);
        } else {
            q.where("leaseAgreementId", leaseAgreementId);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue row : q.cache(false).queryList()) {
            total = total.add(nz(row.getBigDecimal("amount")));
        }
        return total;
    }

    /**
     * Set the depreciation parameters of the core FixedAsset of each agreement asset at
     * the lease start: salvage value (allocated residual), expected end of life (lease
     * end) and a zero depreciation total.
     */
    public static void initFixedAssets(Delegator delegator, GenericValue agreement) throws GenericEntityException {
        Map<String, BigDecimal> residuals = LeaseExpiryServices.allocateResidual(delegator, agreement,
                agreement.getBigDecimal("residualValue"));
        for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                .where("leaseAgreementId", agreement.getString("leaseAgreementId")).queryList()) {
            if (UtilValidate.isEmpty(asset.getString("fixedAssetId"))) {
                continue;
            }
            GenericValue fa = EntityQuery.use(delegator).from("FixedAsset")
                    .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
            if (fa == null) {
                continue;
            }
            fa.set("salvageValue", residuals.get(asset.getString("assetSeqId")));
            fa.set("expectedEndOfLife", new java.sql.Date(agreement.getTimestamp("leaseEndDate").getTime()));
            fa.set("depreciation", BigDecimal.ZERO);
            fa.store();
        }
    }

    /**
     * Depreciate the leased assets of an ACTIVE operating lease for every lease month
     * ending on or before {@code depreciationDate} that is not yet depreciated: one
     * record per asset x month, one transaction per month
     * (Dr depreciation of leased assets / Cr accumulated depreciation). Idempotent.
     */
    public static Map<String, Object> depreciateLeaseAssets(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp date = UtilDateTime.getDayEnd(context.get("depreciationDate") != null
                ? (Timestamp) context.get("depreciationDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", leaseAgreementId).cache(false).queryOne();
            if (agreement == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceRecordNotFound",
                        UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale));
            }
            if (!LeaseProductHelper.isOperating(agreement)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceLeaseDepreciationNotOperating",
                        UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale));
            }
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceLeaseAccountingRequiresActive",
                        UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale));
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            Timestamp start = agreement.getTimestamp("leaseStartDate");
            int term = agreement.getLong("termMonths").intValue();
            String currency = agreement.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
            List<GenericValue> assets = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).orderBy("assetSeqId").queryList();
            Map<String, BigDecimal> residuals = LeaseExpiryServices.allocateResidual(delegator, agreement,
                    agreement.getBigDecimal("residualValue"));
            GenericValue last = EntityQuery.use(delegator).from("LeaseAssetDepreciation")
                    .where("leaseAgreementId", leaseAgreementId).orderBy("-periodNumber").cache(false).queryFirst();
            int k = last == null ? 1 : last.getLong("periodNumber").intValue() + 1;
            // STEP L15: after a change of the lease term the remaining depreciable amount is spread
            // straight-line over the remaining months from the last modification's base month.
            GenericValue modification = EntityQuery.use(delegator).from("LeaseModification")
                    .where("leaseAgreementId", leaseAgreementId, "reversedDate", null).orderBy("-leaseModificationId")
                    .cache(false).queryFirst();
            int baseMonth = modification == null || modification.getLong("depreciationBaseMonth") == null ? 0
                    : modification.getLong("depreciationBaseMonth").intValue();
            Map<String, BigDecimal> baseCumulative = new HashMap<>();
            if (baseMonth > 0) {
                for (GenericValue asset : assets) {
                    GenericValue at = EntityQuery.use(delegator).from("LeaseAssetDepreciation").where("leaseAgreementId",
                            leaseAgreementId, "assetSeqId", asset.getString("assetSeqId"), "periodNumber", (long) baseMonth)
                            .cache(false).queryOne();
                    baseCumulative.put(asset.getString("assetSeqId"), at == null ? BigDecimal.ZERO : nz(at.getBigDecimal("cumulativeAmount")));
                }
            }
            long created = 0;
            BigDecimal depreciated = BigDecimal.ZERO;
            for (; k <= term; k++) {
                Timestamp thru = periodThru(start, k);
                if (thru.after(date)) {
                    break;
                }
                Timestamp from = UtilDateTime.getDayStart(addMonths(start, k - 1));
                Map<String, BigDecimal> amounts = new LinkedHashMap<>();
                Map<String, BigDecimal> cumulatives = new LinkedHashMap<>();
                BigDecimal monthTotal = BigDecimal.ZERO;
                for (GenericValue asset : assets) {
                    String seq = asset.getString("assetSeqId");
                    BigDecimal cost = nz(asset.getBigDecimal("acquisitionCost"));
                    BigDecimal depreciable = cost.subtract(nz(residuals.get(seq))).max(BigDecimal.ZERO);
                    BigDecimal cum;
                    BigDecimal prev;
                    if (baseMonth > 0 && k > baseMonth) {
                        BigDecimal base = baseCumulative.get(seq);
                        cum = cumulativeFromBase(depreciable, base, baseMonth, k, term, scale, mode);
                        prev = cumulativeFromBase(depreciable, base, baseMonth, k - 1, term, scale, mode);
                    } else {
                        cum = cumulative(depreciable, k, term, scale, mode);
                        prev = cumulative(depreciable, k - 1, term, scale, mode);
                    }
                    BigDecimal amount = cum.subtract(prev);
                    amounts.put(seq, amount);
                    cumulatives.put(seq, cum);
                    monthTotal = monthTotal.add(amount);
                }
                String acctgTransId = null;
                if (monthTotal.signum() > 0) {
                    List<FinanceGlHelper.Line> lines = new ArrayList<>();
                    lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_DEPRECIATION, true, monthTotal));
                    lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_OPER_ACCUM_DEPR, false, monthTotal));
                    acctgTransId = FinanceGlHelper.postTransaction(delegator, dctx.getDispatcher(),
                            "Leased asset depreciation " + leaseAgreementId + " #" + k, thru,
                            agreement.getString("organizationPartyId"), null, currency, lines, userLogin);
                }
                for (GenericValue asset : assets) {
                    String seq = asset.getString("assetSeqId");
                    BigDecimal cost = nz(asset.getBigDecimal("acquisitionCost"));
                    GenericValue rec = delegator.makeValue("LeaseAssetDepreciation");
                    rec.set("leaseAgreementId", leaseAgreementId);
                    rec.set("assetSeqId", seq);
                    rec.set("periodNumber", (long) k);
                    rec.set("fixedAssetId", asset.getString("fixedAssetId"));
                    rec.set("fromDate", from);
                    rec.set("thruDate", thru);
                    rec.set("amount", amounts.get(seq));
                    rec.set("cumulativeAmount", cumulatives.get(seq));
                    rec.set("bookValue", cost.subtract(cumulatives.get(seq)));
                    rec.set("currencyUomId", currency);
                    rec.set("acctgTransId", acctgTransId);
                    rec.set("createdDate", UtilDateTime.nowTimestamp());
                    rec.create();
                    if (UtilValidate.isNotEmpty(asset.getString("fixedAssetId"))) {
                        GenericValue fa = EntityQuery.use(delegator).from("FixedAsset")
                                .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
                        if (fa != null) {
                            fa.set("depreciation", nz(fa.getBigDecimal("depreciation")).add(amounts.get(seq)));
                            fa.store();
                        }
                    }
                }
                depreciated = depreciated.add(monthTotal);
                created++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("createdCount", created);
            result.put("depreciatedAmount", depreciated);
            result.put("accumulatedDepreciation", accumulatedDepreciation(delegator, leaseAgreementId, null));
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
