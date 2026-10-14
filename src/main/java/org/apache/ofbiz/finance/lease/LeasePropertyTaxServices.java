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
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Property tax (償却資産税) of lessor-owned leased assets (STEP L10).
 *
 * <p>Only finance leases with no ownership transfer (所有権移転外) are the lessor's
 * assets to declare; ownership-transfer leases are declared by the lessee.
 *
 * <ul>
 * <li>Rate resolution: the municipal rate ({@link #resolveRate}) is the
 *     {@code LeasePropertyTaxRate} of the asset's municipality valid at a date,
 *     else the active standard row, else {@code lease.propertytax.standard.rate}.</li>
 * <li>Estimate ({@link #resolveLeasePropertyTaxEstimate}): the yearly / monthly
 *     property-tax-equivalent of an asset for the L9 component, from its
 *     {@link PropertyTaxValuation} and the municipal rate, averaged over the term
 *     (the 免税点 is not applied in a single-contract estimate).</li>
 * <li>Declaration ({@link #generateLeasePropertyTaxDeclarations}): group the
 *     lessor's non-transfer assets by municipality as of January 1 of a tax year,
 *     value each with {@link PropertyTaxValuation}, apply the 免税点 (1.5M) per
 *     municipality, and compute the tax at the municipal rate.</li>
 * <li>Payment ({@link #recordLeasePropertyTaxPayment}): record the payment and
 *     book Dr property tax expense (租税公課) / Cr cash or payable.</li>
 * </ul>
 */
public final class LeasePropertyTaxServices {

    private static final String MODULE = LeasePropertyTaxServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String STATUS_GENERATED = "LEASE_PTAX_GENERATED";
    public static final String STATUS_PAID = "LEASE_PTAX_PAID";
    public static final String GL_EXPENSE = "LEASE_PTAX_EXPENSE";
    public static final String GL_PAYABLE = "LEASE_PTAX_PAYABLE";
    public static final String CLASS_NON_TRANSFER = "LEASE_CLS_FIN_NONTR";

    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private LeasePropertyTaxServices() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean validAt(GenericValue row, Timestamp asOf) {
        Timestamp from = row.getTimestamp("fromDate");
        Timestamp thru = row.getTimestamp("thruDate");
        return (from == null || !from.after(asOf)) && (thru == null || thru.after(asOf));
    }

    // ------------------------------------------------------------------
    // Rate / config
    // ------------------------------------------------------------------

    /** Property tax rate of a municipality valid at a date (municipal row, else standard, else property). */
    public static BigDecimal resolveRate(Delegator delegator, String municipalityGeoId, Timestamp asOf)
            throws GenericEntityException {
        if (UtilValidate.isNotEmpty(municipalityGeoId)) {
            for (GenericValue row : EntityQuery.use(delegator).from("LeasePropertyTaxRate")
                    .where("municipalityGeoId", municipalityGeoId).orderBy("-fromDate").cache(false).queryList()) {
                if (validAt(row, asOf) && row.getBigDecimal("taxRate") != null) {
                    return row.getBigDecimal("taxRate");
                }
            }
        }
        for (GenericValue row : EntityQuery.use(delegator).from("LeasePropertyTaxRate")
                .where("standardRate", "Y").orderBy("-fromDate").cache(false).queryList()) {
            if (validAt(row, asOf) && row.getBigDecimal("taxRate") != null) {
                return row.getBigDecimal("taxRate");
            }
        }
        return new BigDecimal(EntityUtilProperties.getPropertyValue("finance", "lease.propertytax.standard.rate",
                "0.014", delegator).trim());
    }

    private static int defaultUsefulLife(Delegator delegator) {
        return Integer.parseInt(EntityUtilProperties.getPropertyValue("finance",
                "lease.propertytax.usefullife.years", "6", delegator).trim());
    }

    private static BigDecimal exemptionThreshold(Delegator delegator) {
        return new BigDecimal(EntityUtilProperties.getPropertyValue("finance",
                "lease.propertytax.exemption.threshold", "1500000", delegator).trim());
    }

    /** Useful life of an asset line: the line's value, else the FixedAsset life, else the default. */
    private static int usefulLife(Delegator delegator, GenericValue agreementAsset, GenericValue fixedAsset) {
        if (agreementAsset != null && agreementAsset.getLong("usefulLifeYears") != null) {
            return agreementAsset.getLong("usefulLifeYears").intValue();
        }
        if (fixedAsset != null && fixedAsset.getTimestamp("dateAcquired") != null
                && fixedAsset.getDate("expectedEndOfLife") != null) {
            int years = fixedAsset.getDate("expectedEndOfLife").toLocalDate().getYear()
                    - fixedAsset.getTimestamp("dateAcquired").toLocalDateTime().getYear();
            if (years > 0) {
                return years;
            }
        }
        return defaultUsefulLife(delegator);
    }

    /** Municipality of an asset line: the line's value, else resolved from the FixedAsset facility address. */
    private static String municipality(Delegator delegator, GenericValue agreementAsset, GenericValue fixedAsset)
            throws GenericEntityException {
        if (agreementAsset != null && UtilValidate.isNotEmpty(agreementAsset.getString("municipalityGeoId"))) {
            return agreementAsset.getString("municipalityGeoId");
        }
        return municipalityFromFacility(delegator, fixedAsset);
    }

    /** FixedAsset.locatedAtFacilityId -> current POSTAL_ADDRESS contact mech -> PostalAddress.municipalityGeoId. */
    public static String municipalityFromFacility(Delegator delegator, GenericValue fixedAsset)
            throws GenericEntityException {
        if (fixedAsset == null || UtilValidate.isEmpty(fixedAsset.getString("locatedAtFacilityId"))) {
            return null;
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        for (GenericValue fcm : EntityQuery.use(delegator).from("FacilityContactMech")
                .where("facilityId", fixedAsset.getString("locatedAtFacilityId")).orderBy("-fromDate").queryList()) {
            if (!validAt(fcm, now)) {
                continue;
            }
            GenericValue address = EntityQuery.use(delegator).from("PostalAddress")
                    .where("contactMechId", fcm.getString("contactMechId")).queryOne();
            if (address != null && UtilValidate.isNotEmpty(address.getString("municipalityGeoId"))) {
                return address.getString("municipalityGeoId");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Estimate supply to the L9 component
    // ------------------------------------------------------------------

    /**
     * Estimated property tax equivalent of an asset for the L9 component. The
     * yearly value is the average over the term of the per-tax-year amounts
     * (valuation as of January 1 x municipal rate), the monthly value is that / 12.
     * The 免税点 is not applied (a single asset / contract cannot judge it).
     */
    public static Map<String, Object> resolveLeasePropertyTaxEstimate(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        BigDecimal acquisitionCost = (BigDecimal) context.get("acquisitionCost");
        Long termMonths = (Long) context.get("termMonths");
        String municipalityGeoId = (String) context.get("municipalityGeoId");
        String currencyUomId = (String) context.get("currencyUomId");
        Timestamp asOf = context.get("asOfDate") != null ? (Timestamp) context.get("asOfDate") : UtilDateTime.nowTimestamp();
        Integer usefulLifeYears = context.get("usefulLifeYears") != null
                ? ((Long) context.get("usefulLifeYears")).intValue() : defaultUsefulLife(delegator);
        BigDecimal depreciationRate = (BigDecimal) context.get("depreciationRate");
        try {
            if (acquisitionCost == null || acquisitionCost.signum() <= 0 || termMonths == null || termMonths <= 0) {
                return error("FinanceLeasePropertyTaxEstimateInvalid", UtilMisc.toMap(), locale);
            }
            int scale = FinanceInterestCalculator.resolveScale(currencyUomId, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currencyUomId, delegator);
            BigDecimal rate = resolveRate(delegator, municipalityGeoId, asOf);
            BigDecimal r = depreciationRate != null ? depreciationRate
                    : PropertyTaxValuation.depreciationRateForLife(usefulLifeYears);
            int startYear = asOf.toLocalDateTime().getYear();
            int years = (int) Math.ceil(termMonths / 12.0);
            BigDecimal totalYearlyTax = BigDecimal.ZERO;
            List<Map<String, Object>> yearlyRows = new ArrayList<>();
            for (int i = 0; i < years; i++) {
                int taxYear = startYear + 1 + i;                                  // first assessed the year after acquisition
                BigDecimal assessed = PropertyTaxValuation.assessedValue(acquisitionCost, r, startYear, taxYear, scale, mode);
                BigDecimal base = assessed.divideToIntegralValue(THOUSAND).multiply(THOUSAND);  // 1000-yen floor
                BigDecimal tax = base.multiply(rate).setScale(scale, mode);
                totalYearlyTax = totalYearlyTax.add(tax);
                yearlyRows.add(UtilMisc.toMap("taxYear", (long) taxYear, "assessedValue", assessed, "taxAmount", tax));
            }
            BigDecimal avgYearly = totalYearlyTax.divide(BigDecimal.valueOf(years), scale, mode);
            BigDecimal monthly = avgYearly.divide(BigDecimal.valueOf(12), scale, mode);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("taxRate", rate);
            result.put("depreciationRate", r);
            result.put("yearlyEstimate", avgYearly);
            result.put("monthlyEstimate", monthly);
            result.put("totalEstimate", totalYearlyTax);
            result.put("yearlyBreakdown", yearlyRows);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Declaration generation
    // ------------------------------------------------------------------

    /**
     * Generate the property tax declarations of a lessor organization for a tax
     * year: group the lessor-owned non-transfer leased assets on the tax rolls as
     * of January 1 by municipality, value each asset, apply the 免税点 per
     * municipality and compute the tax. Idempotent per organization x year x
     * municipality (re-running returns the existing declarations).
     */
    public static Map<String, Object> generateLeasePropertyTaxDeclarations(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String organizationPartyId = UtilValidate.isNotEmpty((String) context.get("organizationPartyId"))
                ? (String) context.get("organizationPartyId") : "Company";
        Long taxYearIn = (Long) context.get("taxYear");
        int taxYear = taxYearIn != null ? taxYearIn.intValue() : LocalDate.now().getYear();
        try {
            Timestamp jan1 = Timestamp.valueOf(LocalDate.of(taxYear, 1, 1).atStartOfDay());
            BigDecimal threshold = exemptionThreshold(delegator);
            long skipped = 0;

            // Lessor-owned non-transfer assets, acquired before January 1 of the tax year, still leased.
            Map<String, List<Map<String, Object>>> byMunicipality = new LinkedHashMap<>();
            Map<String, String> currencyByMunicipality = new HashMap<>();
            List<GenericValue> agreements = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseClassEnumId", CLASS_NON_TRANSFER, "organizationPartyId", organizationPartyId)
                    .queryList();
            for (GenericValue agreement : agreements) {
                String status = agreement.getString("statusId");
                if (!"LEASEAGR_ACTIVE".equals(status) && !"LEASEAGR_COMPLETED".equals(status)) {
                    continue;
                }
                String currency = agreement.getString("currencyUomId");
                int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
                RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
                for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                        .where("leaseAgreementId", agreement.getString("leaseAgreementId")).queryList()) {
                    String fixedAssetId = asset.getString("fixedAssetId");
                    if (UtilValidate.isEmpty(fixedAssetId)) {
                        continue;
                    }
                    GenericValue fixedAsset = EntityQuery.use(delegator).from("FixedAsset")
                            .where("fixedAssetId", fixedAssetId).queryOne();
                    if (fixedAsset == null || !organizationPartyId.equals(fixedAsset.getString("partyId"))) {
                        continue;                                                 // only assets still owned by the lessor
                    }
                    Timestamp acquired = fixedAsset.getTimestamp("dateAcquired");
                    if (acquired == null || !acquired.before(jan1)) {
                        continue;                                                 // acquired this year: first assessed next year
                    }
                    int acquisitionYear = acquired.toLocalDateTime().getYear();
                    BigDecimal cost = nz(fixedAsset.getBigDecimal("purchaseCost"));
                    if (cost.signum() <= 0) {
                        cost = nz(asset.getBigDecimal("acquisitionCost"));
                    }
                    if (cost.signum() <= 0) {
                        continue;
                    }
                    int life = usefulLife(delegator, asset, fixedAsset);
                    BigDecimal r = PropertyTaxValuation.depreciationRateForLife(life);
                    BigDecimal assessed = PropertyTaxValuation.assessedValue(cost, r, acquisitionYear, taxYear, scale, mode);
                    if (assessed.signum() <= 0) {
                        continue;
                    }
                    String municipalityGeoId = municipality(delegator, asset, fixedAsset);
                    if (UtilValidate.isEmpty(municipalityGeoId)) {
                        // No resolvable location: cannot assign to a municipality; skip (count as unassigned).
                        skipped++;
                        continue;
                    }
                    byMunicipality.computeIfAbsent(municipalityGeoId, k -> new ArrayList<>()).add(UtilMisc.toMap(
                            "fixedAssetId", fixedAssetId, "leaseAgreementId", asset.getString("leaseAgreementId"),
                            "assetSeqId", asset.getString("assetSeqId"), "assetName", asset.getString("assetName"),
                            "acquisitionCost", cost, "acquisitionYear", (long) acquisitionYear,
                            "usefulLifeYears", (long) life, "depreciationRate", r, "assessedValue", assessed));
                    currencyByMunicipality.put(municipalityGeoId, currency);
                }
            }

            long created = 0;
            List<String> declIds = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : byMunicipality.entrySet()) {
                String municipalityGeoId = entry.getKey();
                GenericValue existing = EntityQuery.use(delegator).from("LeasePropertyTaxDecl")
                        .where("organizationPartyId", organizationPartyId, "taxYear", (long) taxYear,
                                "municipalityGeoId", municipalityGeoId).queryFirst();
                if (existing != null) {
                    declIds.add(existing.getString("leasePropertyTaxDeclId"));
                    continue;
                }
                String currency = currencyByMunicipality.get(municipalityGeoId);
                BigDecimal totalAssessed = BigDecimal.ZERO;
                for (Map<String, Object> line : entry.getValue()) {
                    totalAssessed = totalAssessed.add((BigDecimal) line.get("assessedValue"));
                }
                boolean below = totalAssessed.compareTo(threshold) < 0;
                BigDecimal taxableBase = below ? BigDecimal.ZERO
                        : totalAssessed.divideToIntegralValue(THOUSAND).multiply(THOUSAND);  // 課税標準は1000円未満切捨
                BigDecimal rate = resolveRate(delegator, municipalityGeoId, jan1);
                // Property tax = taxable base x rate, rounded down to the nearest 100 yen (税額の100円未満切捨).
                BigDecimal taxAmount = below ? BigDecimal.ZERO
                        : taxableBase.multiply(rate).divide(ONE_HUNDRED, 0, RoundingMode.DOWN).multiply(ONE_HUNDRED);

                String declId = delegator.getNextSeqId("LeasePropertyTaxDecl");
                GenericValue decl = delegator.makeValue("LeasePropertyTaxDecl");
                decl.set("leasePropertyTaxDeclId", declId);
                decl.set("organizationPartyId", organizationPartyId);
                decl.set("taxYear", (long) taxYear);
                decl.set("municipalityGeoId", municipalityGeoId);
                decl.set("currencyUomId", currency);
                decl.set("assetCount", (long) entry.getValue().size());
                decl.set("totalAssessedValue", totalAssessed);
                decl.set("taxableBase", taxableBase);
                decl.set("exemptionThreshold", threshold);
                decl.set("belowThreshold", below ? "Y" : "N");
                decl.set("taxRate", rate);
                decl.set("taxAmount", taxAmount);
                decl.set("statusId", STATUS_GENERATED);
                decl.set("generatedDate", UtilDateTime.nowTimestamp());
                decl.set("createdDate", UtilDateTime.nowTimestamp());
                decl.create();
                for (Map<String, Object> line : entry.getValue()) {
                    GenericValue declAsset = delegator.makeValue("LeasePropertyTaxDeclAsset");
                    declAsset.set("leasePropertyTaxDeclId", declId);
                    declAsset.set("fixedAssetId", line.get("fixedAssetId"));
                    declAsset.set("leaseAgreementId", line.get("leaseAgreementId"));
                    declAsset.set("assetSeqId", line.get("assetSeqId"));
                    declAsset.set("assetName", line.get("assetName"));
                    declAsset.set("acquisitionCost", line.get("acquisitionCost"));
                    declAsset.set("acquisitionYear", line.get("acquisitionYear"));
                    declAsset.set("usefulLifeYears", line.get("usefulLifeYears"));
                    declAsset.set("depreciationRate", line.get("depreciationRate"));
                    declAsset.set("assessedValue", line.get("assessedValue"));
                    declAsset.create();
                }
                declIds.add(declId);
                created++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("createdCount", created);
            result.put("declarationCount", (long) declIds.size());
            result.put("skippedAssetCount", skipped);
            result.put("leasePropertyTaxDeclIds", declIds);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Payment and expense booking
    // ------------------------------------------------------------------

    /**
     * Record the payment of a declaration and book the expense:
     * Dr property tax expense (租税公課) / Cr property tax payable (or cash).
     * Idempotent per declaration (re-running returns the existing payment).
     */
    public static Map<String, Object> recordLeasePropertyTaxPayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String declId = (String) context.get("leasePropertyTaxDeclId");
        Timestamp paymentDate = context.get("paymentDate") != null
                ? (Timestamp) context.get("paymentDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue decl = EntityQuery.use(delegator).from("LeasePropertyTaxDecl")
                    .where("leasePropertyTaxDeclId", declId).queryOne();
            if (decl == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeasePropertyTaxDecl", "id", declId), locale);
            }
            GenericValue existing = EntityQuery.use(delegator).from("LeasePropertyTaxPayment")
                    .where("leasePropertyTaxDeclId", declId).queryOne();
            if (existing != null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("acctgTransId", existing.getString("acctgTransId"));
                result.put("paymentCreated", false);
                return result;
            }
            BigDecimal amount = nz(decl.getBigDecimal("taxAmount"));
            if (amount.signum() <= 0) {
                return error("FinanceLeasePropertyTaxNothingDue", UtilMisc.toMap("leasePropertyTaxDeclId", declId), locale);
            }
            String organizationPartyId = decl.getString("organizationPartyId");
            String currency = decl.getString("currencyUomId");
            // Dr property tax expense / Cr property tax payable (the actual cash disbursement is out of scope).
            List<FinanceGlHelper.Line> lines = new ArrayList<>();
            lines.add(new FinanceGlHelper.Line(GL_EXPENSE, true, amount));
            lines.add(new FinanceGlHelper.Line(GL_PAYABLE, false, amount));
            String acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                    "Lease property tax " + declId, paymentDate, organizationPartyId, null, currency, lines, userLogin);

            GenericValue payment = delegator.makeValue("LeasePropertyTaxPayment");
            payment.set("leasePropertyTaxDeclId", declId);
            payment.set("paymentDate", paymentDate);
            payment.set("amount", amount);
            payment.set("currencyUomId", currency);
            payment.set("acctgTransId", acctgTransId);
            payment.set("comments", context.get("comments"));
            payment.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
            payment.set("createdDate", UtilDateTime.nowTimestamp());
            payment.create();

            decl.set("statusId", STATUS_PAID);
            decl.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            result.put("paymentCreated", true);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
