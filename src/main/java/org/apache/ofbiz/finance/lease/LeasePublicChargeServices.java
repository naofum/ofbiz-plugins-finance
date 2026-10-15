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
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Public charges (公租公課) of lessor-owned leased assets (STEP L10, generalized
 * in STEP L10b): 償却資産税, 自動車税種別割, 自動車重量税 and further charge types
 * defined as {@code LeasePublicChargeType} rows.
 *
 * <p>Only finance leases with no ownership transfer (所有権移転外) are the lessor's
 * assets to declare; ownership-transfer leases are declared by the lessee.
 *
 * <ul>
 * <li>Asset type -&gt; charge types ({@link #chargeTypesForAssetType}): the
 *     {@code LeasePublicChargeAssetType} rows of the asset type or its nearest
 *     ancestor, else the charge types with {@code appliesToUnmapped=Y}.</li>
 * <li>Jurisdiction ({@link #resolveJurisdiction}): the municipality, its
 *     prefecture (GeoAssoc REGIONS) or the fixed Geo of the charge type.</li>
 * <li>Rate ({@link #resolveTaxRate} / {@link #resolveUnitAmount}): the
 *     {@code LeasePublicChargeRate} of the jurisdiction (and rate class), else the
 *     standard row, else the charge type default.</li>
 * <li>Calculation: {@link LeasePublicChargeCalculator} by the charge type's
 *     calculation method.</li>
 * </ul>
 */
public final class LeasePublicChargeServices {

    private static final String MODULE = LeasePublicChargeServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String STATUS_GENERATED = "LEASE_PCHG_GENERATED";
    public static final String STATUS_PAID = "LEASE_PCHG_PAID";
    public static final String GL_EXPENSE = "LEASE_PCHG_EXPENSE";
    public static final String GL_PAYABLE = "LEASE_PCHG_PAYABLE";
    public static final String CLASS_NON_TRANSFER = "LEASE_CLS_FIN_NONTR";
    public static final String TYPE_DEPR_ASSET = "LSPC_DEPR_ASSET";
    public static final String JUR_MUNICIPALITY = "LSPC_JUR_MUNI";
    public static final String JUR_PREFECTURE = "LSPC_JUR_PREF";
    public static final String JUR_FIXED = "LSPC_JUR_FIXED";

    private static final int MAX_TYPE_DEPTH = 20;

    private LeasePublicChargeServices() {
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
    // Charge type, asset type, jurisdiction, rate
    // ------------------------------------------------------------------

    public static GenericValue chargeType(Delegator delegator, String publicChargeTypeId) throws GenericEntityException {
        if (UtilValidate.isEmpty(publicChargeTypeId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("LeasePublicChargeType")
                .where("publicChargeTypeId", publicChargeTypeId).cache().queryOne();
    }

    /** The charge type whose estimate is supplied to an L9 component type, or null. */
    public static GenericValue chargeTypeForComponent(Delegator delegator, String componentTypeEnumId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(componentTypeEnumId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("LeasePublicChargeType")
                .where("componentTypeEnumId", componentTypeEnumId).orderBy("sequenceNum").cache().queryFirst();
    }

    static LeasePublicChargeCalculator calculator(GenericValue type) {
        return type == null ? null : LeasePublicChargeCalculator.REGISTRY.get(type.getString("calcMethodEnumId"));
    }

    static LeasePublicChargeCalculator.Rules rules(GenericValue type, int scale, RoundingMode mode) {
        return new LeasePublicChargeCalculator.Rules(type.getBigDecimal("exemptionThreshold"),
                type.getBigDecimal("baseRoundingUnit"), type.getBigDecimal("taxRoundingUnit"), scale, mode);
    }

    /** Assessment date (賦課期日) of a charge type in a tax year. */
    static Timestamp assessmentDate(GenericValue type, int taxYear) {
        int month = type.getLong("assessmentMonth") != null ? type.getLong("assessmentMonth").intValue() : 1;
        int day = type.getLong("assessmentDay") != null ? type.getLong("assessmentDay").intValue() : 1;
        return Timestamp.valueOf(LocalDate.of(taxYear, month, day).atStartOfDay());
    }

    /**
     * Charge types an asset type is subject to, with the special taxable-base
     * ratio of each (1 when none): the rows of the asset type or its nearest
     * ancestor that has rows (excluding {@code applicable=N}); when no type in the
     * ancestry has rows, the charge types with {@code appliesToUnmapped=Y}.
     */
    public static Map<String, BigDecimal> chargeTypesForAssetType(Delegator delegator, String fixedAssetTypeId)
            throws GenericEntityException {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        String typeId = fixedAssetTypeId;
        for (int depth = 0; UtilValidate.isNotEmpty(typeId) && depth < MAX_TYPE_DEPTH; depth++) {
            List<GenericValue> rows = EntityQuery.use(delegator).from("LeasePublicChargeAssetType")
                    .where("fixedAssetTypeId", typeId).orderBy("publicChargeTypeId").cache().queryList();
            if (!rows.isEmpty()) {
                for (GenericValue row : rows) {
                    if (!"N".equals(row.getString("applicable"))) {
                        BigDecimal ratio = row.getBigDecimal("taxableBaseRatio");
                        result.put(row.getString("publicChargeTypeId"), ratio == null ? BigDecimal.ONE : ratio);
                    }
                }
                return result;
            }
            GenericValue fat = EntityQuery.use(delegator).from("FixedAssetType")
                    .where("fixedAssetTypeId", typeId).cache().queryOne();
            typeId = fat == null ? null : fat.getString("parentTypeId");
        }
        for (GenericValue type : EntityQuery.use(delegator).from("LeasePublicChargeType")
                .where("appliesToUnmapped", "Y").orderBy("sequenceNum").cache().queryList()) {
            result.put(type.getString("publicChargeTypeId"), BigDecimal.ONE);
        }
        return result;
    }

    /** Jurisdiction Geo of a charge type for an asset in a municipality (null when unresolved). */
    public static String resolveJurisdiction(Delegator delegator, GenericValue type, String municipalityGeoId)
            throws GenericEntityException {
        String level = type.getString("jurisdictionLevelEnumId");
        if (JUR_FIXED.equals(level)) {
            return type.getString("jurisdictionGeoId");
        }
        if (UtilValidate.isEmpty(municipalityGeoId)) {
            return null;
        }
        if (JUR_PREFECTURE.equals(level)) {
            GenericValue assoc = EntityQuery.use(delegator).from("GeoAssoc")
                    .where("geoIdTo", municipalityGeoId, "geoAssocTypeId", "REGIONS").cache().queryFirst();
            return assoc == null ? null : assoc.getString("geoId");
        }
        return municipalityGeoId;
    }

    /** VALUE rate of a charge type in a jurisdiction (row of the jurisdiction, else standard row, else type default). */
    public static BigDecimal resolveTaxRate(Delegator delegator, GenericValue type, String jurisdictionGeoId, Timestamp asOf)
            throws GenericEntityException {
        String typeId = type.getString("publicChargeTypeId");
        if (UtilValidate.isNotEmpty(jurisdictionGeoId)) {
            for (GenericValue row : EntityQuery.use(delegator).from("LeasePublicChargeRate")
                    .where("publicChargeTypeId", typeId, "jurisdictionGeoId", jurisdictionGeoId)
                    .orderBy("-fromDate").cache(false).queryList()) {
                if (validAt(row, asOf) && row.getBigDecimal("taxRate") != null) {
                    return row.getBigDecimal("taxRate");
                }
            }
        }
        for (GenericValue row : EntityQuery.use(delegator).from("LeasePublicChargeRate")
                .where("publicChargeTypeId", typeId, "standardRate", "Y").orderBy("-fromDate").cache(false).queryList()) {
            if (validAt(row, asOf) && row.getBigDecimal("taxRate") != null && UtilValidate.isEmpty(row.getString("rateClassCode"))) {
                return row.getBigDecimal("taxRate");
            }
        }
        return type.getBigDecimal("defaultTaxRate");
    }

    /** UNIT annual amount of a rate class (row of the jurisdiction, else standard row; null when undefined). */
    public static BigDecimal resolveUnitAmount(Delegator delegator, GenericValue type, String jurisdictionGeoId,
            String rateClassCode, Timestamp asOf) throws GenericEntityException {
        if (UtilValidate.isEmpty(rateClassCode)) {
            return null;
        }
        String typeId = type.getString("publicChargeTypeId");
        if (UtilValidate.isNotEmpty(jurisdictionGeoId)) {
            for (GenericValue row : EntityQuery.use(delegator).from("LeasePublicChargeRate")
                    .where("publicChargeTypeId", typeId, "jurisdictionGeoId", jurisdictionGeoId, "rateClassCode", rateClassCode)
                    .orderBy("-fromDate").cache(false).queryList()) {
                if (validAt(row, asOf) && row.getBigDecimal("unitAmount") != null) {
                    return row.getBigDecimal("unitAmount");
                }
            }
        }
        for (GenericValue row : EntityQuery.use(delegator).from("LeasePublicChargeRate")
                .where("publicChargeTypeId", typeId, "standardRate", "Y", "rateClassCode", rateClassCode)
                .orderBy("-fromDate").cache(false).queryList()) {
            if (validAt(row, asOf) && row.getBigDecimal("unitAmount") != null) {
                return row.getBigDecimal("unitAmount");
            }
        }
        return null;
    }

    private static int defaultUsefulLife(GenericValue type) {
        Long life = type.getLong("defaultUsefulLifeYears");
        return life != null && life > 0 ? life.intValue() : 6;
    }

    /** Useful life of an asset line: the line's value, else the FixedAsset life, else the charge type default. */
    private static int usefulLife(GenericValue type, GenericValue agreementAsset, GenericValue fixedAsset) {
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
        return defaultUsefulLife(type);
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
     * Estimated public charge equivalent of an asset (group) for the L9
     * component. The yearly value is the average over the term of the per-tax-year
     * amounts, the monthly value is that / 12. The 免税点 is not applied (a
     * single contract cannot judge it) and the full asset is estimated (the lessee
     * pays the whole charge equivalent regardless of the lessor's share).
     */
    public static Map<String, Object> resolveLeasePublicChargeEstimate(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String typeId = UtilValidate.isNotEmpty((String) context.get("publicChargeTypeId"))
                ? (String) context.get("publicChargeTypeId") : TYPE_DEPR_ASSET;
        BigDecimal acquisitionCost = (BigDecimal) context.get("acquisitionCost");
        BigDecimal quantity = context.get("quantity") != null ? (BigDecimal) context.get("quantity") : BigDecimal.ONE;
        Long termMonths = (Long) context.get("termMonths");
        String municipalityGeoId = (String) context.get("municipalityGeoId");
        String rateClassCode = (String) context.get("rateClassCode");
        String currencyUomId = (String) context.get("currencyUomId");
        BigDecimal ratio = (BigDecimal) context.get("taxableBaseRatio");
        Timestamp asOf = context.get("asOfDate") != null ? (Timestamp) context.get("asOfDate") : UtilDateTime.nowTimestamp();
        BigDecimal depreciationRate = (BigDecimal) context.get("depreciationRate");
        try {
            GenericValue type = chargeType(delegator, typeId);
            LeasePublicChargeCalculator calc = calculator(type);
            if (calc == null) {
                return error("FinanceLeasePublicChargeTypeInvalid", UtilMisc.toMap("publicChargeTypeId", typeId), locale);
            }
            boolean value = LeasePublicChargeCalculator.CALC_VALUE.equals(type.getString("calcMethodEnumId"));
            if (termMonths == null || termMonths <= 0 || quantity.signum() <= 0
                    || (value && (acquisitionCost == null || acquisitionCost.signum() <= 0))) {
                return error("FinanceLeasePublicChargeEstimateInvalid", UtilMisc.toMap(), locale);
            }
            int usefulLifeYears = context.get("usefulLifeYears") != null
                    ? ((Long) context.get("usefulLifeYears")).intValue() : defaultUsefulLife(type);
            int scale = FinanceInterestCalculator.resolveScale(currencyUomId, delegator);
            RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currencyUomId, delegator);
            String jurisdictionGeoId = resolveJurisdiction(delegator, type, municipalityGeoId);
            BigDecimal rate = value ? resolveTaxRate(delegator, type, jurisdictionGeoId, asOf) : null;
            BigDecimal unitAmount = null;
            if (!value) {
                unitAmount = resolveUnitAmount(delegator, type, jurisdictionGeoId, rateClassCode, asOf);
                if (unitAmount == null) {
                    return error("FinanceLeasePublicChargeRateMissing", UtilMisc.toMap("publicChargeTypeId", typeId,
                            "rateClassCode", rateClassCode == null ? "" : rateClassCode), locale);
                }
            }
            BigDecimal r = value ? (depreciationRate != null ? depreciationRate
                    : PropertyTaxValuation.depreciationRateForLife(usefulLifeYears)) : null;
            int startYear = asOf.toLocalDateTime().getYear();
            // First tax year: the first assessment date after the start (1/1 -> the following year).
            int firstTaxYear = asOf.before(assessmentDate(type, startYear)) ? startYear : startYear + 1;
            LeasePublicChargeCalculator.Rules rules = rules(type, scale, mode);
            LeasePublicChargeCalculator.AssetInput input = new LeasePublicChargeCalculator.AssetInput()
                    .acquisitionCost(acquisitionCost).acquisitionYear(startYear).depreciationRate(r)
                    .quantity(quantity).taxableBaseRatio(ratio).unitAmount(unitAmount);
            int years = (int) Math.ceil(termMonths / 12.0);
            BigDecimal total = BigDecimal.ZERO;
            List<Map<String, Object>> yearlyRows = new ArrayList<>();
            for (int i = 0; i < years; i++) {
                int taxYear = firstTaxYear + i;
                BigDecimal tax = calc.estimateYear(rules, input, taxYear, rate);
                total = total.add(tax);
                yearlyRows.add(UtilMisc.toMap("taxYear", (long) taxYear, "taxAmount", tax));
            }
            BigDecimal avgYearly = total.divide(BigDecimal.valueOf(years), scale, mode);
            BigDecimal monthly = avgYearly.divide(BigDecimal.valueOf(12), scale, mode);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("jurisdictionGeoId", jurisdictionGeoId);
            result.put("taxRate", rate);
            result.put("unitAmount", unitAmount);
            result.put("depreciationRate", r);
            result.put("yearlyEstimate", avgYearly);
            result.put("monthlyEstimate", monthly);
            result.put("totalEstimate", total);
            result.put("yearlyBreakdown", yearlyRows);
            return result;
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Asset attributes
    // ------------------------------------------------------------------

    /**
     * Set the public charge attributes of an agreement asset: location
     * (municipality), useful life, the lessor's ownership share and the rate class
     * of a UNIT charge type.
     */
    public static Map<String, Object> setLeaseAssetPublicChargeAttributes(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        try {
            GenericValue asset = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (asset == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreementAsset",
                        "id", leaseAgreementId + "/" + assetSeqId), locale);
            }
            BigDecimal share = (BigDecimal) context.get("ownershipShare");
            if (share != null && (share.signum() <= 0 || share.compareTo(BigDecimal.ONE) > 0)) {
                return error("FinanceLeasePublicChargeShareInvalid", UtilMisc.toMap("ownershipShare", share), locale);
            }
            if (context.containsKey("municipalityGeoId") && context.get("municipalityGeoId") != null) {
                asset.set("municipalityGeoId", context.get("municipalityGeoId"));
            }
            if (context.get("usefulLifeYears") != null) {
                asset.set("usefulLifeYears", context.get("usefulLifeYears"));
            }
            if (share != null) {
                asset.set("ownershipShare", share);
            }
            asset.store();
            String typeId = (String) context.get("publicChargeTypeId");
            if (UtilValidate.isNotEmpty(typeId)) {
                if (chargeType(delegator, typeId) == null) {
                    return error("FinanceLeasePublicChargeTypeInvalid", UtilMisc.toMap("publicChargeTypeId", typeId), locale);
                }
                GenericValue row = delegator.makeValue("LeaseAssetPublicCharge", UtilMisc.toMap(
                        "leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId, "publicChargeTypeId", typeId,
                        "rateClassCode", context.get("rateClassCode")));
                delegator.createOrStore(row);
            }
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Declaration generation
    // ------------------------------------------------------------------

    /**
     * Generate the public charge declarations of a lessor organization for a tax
     * year: for each charge type (or the given one), take the lessor-owned
     * non-transfer leased assets subject to it that were acquired before its
     * assessment date, assess each, group them by jurisdiction and compute the
     * charge. Idempotent per organization x charge type x tax year x jurisdiction.
     */
    public static Map<String, Object> generateLeasePublicChargeDeclarations(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String organizationPartyId = UtilValidate.isNotEmpty((String) context.get("organizationPartyId"))
                ? (String) context.get("organizationPartyId") : "Company";
        Long taxYearIn = (Long) context.get("taxYear");
        int taxYear = taxYearIn != null ? taxYearIn.intValue() : LocalDate.now().getYear();
        String typeIdIn = (String) context.get("publicChargeTypeId");
        try {
            List<GenericValue> types;
            if (UtilValidate.isNotEmpty(typeIdIn)) {
                GenericValue type = chargeType(delegator, typeIdIn);
                if (calculator(type) == null) {
                    return error("FinanceLeasePublicChargeTypeInvalid", UtilMisc.toMap("publicChargeTypeId", typeIdIn), locale);
                }
                types = UtilMisc.toList(type);
            } else {
                types = EntityQuery.use(delegator).from("LeasePublicChargeType").orderBy("sequenceNum").queryList();
            }

            // Lessor-owned assets still leased: non-transfer finance leases and (STEP L12) operating leases.
            List<GenericValue[]> candidates = new ArrayList<>();
            for (GenericValue agreement : EntityQuery.use(delegator).from("LeaseAgreement")
                    .where(EntityCondition.makeCondition(UtilMisc.toList(
                            EntityCondition.makeCondition("leaseClassEnumId", EntityOperator.IN,
                                    UtilMisc.toList(CLASS_NON_TRANSFER, LeaseProductHelper.CLASS_OPERATING)),
                            EntityCondition.makeCondition("organizationPartyId", organizationPartyId)))).queryList()) {
                String status = agreement.getString("statusId");
                if (!"LEASEAGR_ACTIVE".equals(status) && !"LEASEAGR_COMPLETED".equals(status)) {
                    continue;
                }
                for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                        .where("leaseAgreementId", agreement.getString("leaseAgreementId")).queryList()) {
                    if (UtilValidate.isEmpty(asset.getString("fixedAssetId"))) {
                        continue;
                    }
                    GenericValue fixedAsset = EntityQuery.use(delegator).from("FixedAsset")
                            .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
                    if (fixedAsset == null || !organizationPartyId.equals(fixedAsset.getString("partyId"))) {
                        continue;                                                 // only assets still owned by the lessor
                    }
                    candidates.add(new GenericValue[] {agreement, asset, fixedAsset});
                }
            }

            long skipped = 0;
            long created = 0;
            List<String> declIds = new ArrayList<>();
            for (GenericValue type : types) {
                LeasePublicChargeCalculator calc = calculator(type);
                if (calc == null) {
                    continue;
                }
                String typeId = type.getString("publicChargeTypeId");
                boolean value = LeasePublicChargeCalculator.CALC_VALUE.equals(type.getString("calcMethodEnumId"));
                Timestamp assessDate = assessmentDate(type, taxYear);
                Map<String, List<Map<String, Object>>> byJurisdiction = new LinkedHashMap<>();
                Map<String, String> currencyByJurisdiction = new HashMap<>();
                for (GenericValue[] c : candidates) {
                    GenericValue agreement = c[0];
                    GenericValue asset = c[1];
                    GenericValue fixedAsset = c[2];
                    String fixedAssetTypeId = UtilValidate.isNotEmpty(fixedAsset.getString("fixedAssetTypeId"))
                            ? fixedAsset.getString("fixedAssetTypeId") : asset.getString("fixedAssetTypeId");
                    Map<String, BigDecimal> applicable = chargeTypesForAssetType(delegator, fixedAssetTypeId);
                    if (!applicable.containsKey(typeId)) {
                        continue;
                    }
                    Timestamp acquired = fixedAsset.getTimestamp("dateAcquired");
                    if (acquired == null || !acquired.before(assessDate)) {
                        continue;                                                 // first assessed on a later assessment date
                    }
                    String currency = agreement.getString("currencyUomId");
                    int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
                    RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
                    int acquisitionYear = acquired.toLocalDateTime().getYear();
                    BigDecimal cost = nz(fixedAsset.getBigDecimal("purchaseCost"));
                    if (cost.signum() <= 0) {
                        cost = nz(asset.getBigDecimal("acquisitionCost"));
                    }
                    if (value && cost.signum() <= 0) {
                        continue;
                    }
                    BigDecimal share = asset.getBigDecimal("ownershipShare") != null
                            ? asset.getBigDecimal("ownershipShare") : BigDecimal.ONE;
                    BigDecimal quantity = asset.getBigDecimal("quantity") != null && asset.getBigDecimal("quantity").signum() > 0
                            ? asset.getBigDecimal("quantity") : BigDecimal.ONE;
                    String jurisdictionGeoId = resolveJurisdiction(delegator, type,
                            JUR_FIXED.equals(type.getString("jurisdictionLevelEnumId")) ? null
                                    : municipality(delegator, asset, fixedAsset));
                    if (UtilValidate.isEmpty(jurisdictionGeoId)) {
                        skipped++;                                                // no resolvable location
                        continue;
                    }
                    LeasePublicChargeCalculator.AssetInput input = new LeasePublicChargeCalculator.AssetInput()
                            .acquisitionCost(cost).acquisitionYear(acquisitionYear).quantity(quantity)
                            .ownershipShare(share).taxableBaseRatio(applicable.get(typeId));
                    Integer life = null;
                    BigDecimal r = null;
                    String rateClassCode = null;
                    BigDecimal unitAmount = null;
                    if (value) {
                        life = usefulLife(type, asset, fixedAsset);
                        r = PropertyTaxValuation.depreciationRateForLife(life);
                        input.depreciationRate(r);
                    } else {
                        GenericValue attr = EntityQuery.use(delegator).from("LeaseAssetPublicCharge")
                                .where("leaseAgreementId", asset.getString("leaseAgreementId"),
                                        "assetSeqId", asset.getString("assetSeqId"), "publicChargeTypeId", typeId).queryOne();
                        rateClassCode = attr == null ? null : attr.getString("rateClassCode");
                        unitAmount = resolveUnitAmount(delegator, type, jurisdictionGeoId, rateClassCode, assessDate);
                        if (unitAmount == null) {
                            skipped++;                                            // no rate class / amount
                            continue;
                        }
                        input.unitAmount(unitAmount);
                    }
                    LeasePublicChargeCalculator.AssetLine line =
                            calc.assess(rules(type, scale, mode), input, taxYear);
                    if (line == null) {
                        continue;
                    }
                    Map<String, Object> row = new HashMap<>();
                    row.put("line", line);
                    row.put("fixedAssetId", asset.getString("fixedAssetId"));
                    row.put("leaseAgreementId", asset.getString("leaseAgreementId"));
                    row.put("assetSeqId", asset.getString("assetSeqId"));
                    row.put("assetName", asset.getString("assetName"));
                    row.put("fixedAssetTypeId", fixedAssetTypeId);
                    row.put("acquisitionCost", cost);
                    row.put("acquisitionYear", (long) acquisitionYear);
                    row.put("quantity", quantity);
                    row.put("ownershipShare", share);
                    row.put("usefulLifeYears", life == null ? null : (long) life);
                    row.put("depreciationRate", r);
                    row.put("taxableBaseRatio", value ? applicable.get(typeId) : null);
                    row.put("rateClassCode", rateClassCode);
                    row.put("unitAmount", unitAmount);
                    byJurisdiction.computeIfAbsent(jurisdictionGeoId, k -> new ArrayList<>()).add(row);
                    currencyByJurisdiction.put(jurisdictionGeoId, currency);
                }

                for (Map.Entry<String, List<Map<String, Object>>> entry : byJurisdiction.entrySet()) {
                    String jurisdictionGeoId = entry.getKey();
                    GenericValue existing = EntityQuery.use(delegator).from("LeasePublicChargeDecl")
                            .where("organizationPartyId", organizationPartyId, "publicChargeTypeId", typeId,
                                    "taxYear", (long) taxYear, "jurisdictionGeoId", jurisdictionGeoId).queryFirst();
                    if (existing != null) {
                        declIds.add(existing.getString("leasePublicChargeDeclId"));
                        continue;
                    }
                    String currency = currencyByJurisdiction.get(jurisdictionGeoId);
                    int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
                    RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
                    List<LeasePublicChargeCalculator.AssetLine> lines = new ArrayList<>();
                    for (Map<String, Object> row : entry.getValue()) {
                        lines.add((LeasePublicChargeCalculator.AssetLine) row.get("line"));
                    }
                    BigDecimal rate = value ? resolveTaxRate(delegator, type, jurisdictionGeoId, assessDate) : null;
                    LeasePublicChargeCalculator.Totals totals = calc.declare(rules(type, scale, mode), lines, rate);

                    String declId = delegator.getNextSeqId("LeasePublicChargeDecl");
                    GenericValue decl = delegator.makeValue("LeasePublicChargeDecl");
                    decl.set("leasePublicChargeDeclId", declId);
                    decl.set("publicChargeTypeId", typeId);
                    decl.set("organizationPartyId", organizationPartyId);
                    decl.set("taxYear", (long) taxYear);
                    decl.set("jurisdictionGeoId", jurisdictionGeoId);
                    decl.set("currencyUomId", currency);
                    decl.set("assetCount", (long) entry.getValue().size());
                    decl.set("totalAssessedValue", totals.getTotalAssessedValue());
                    decl.set("taxableBase", totals.getTaxableBase());
                    decl.set("exemptionThreshold", value ? type.getBigDecimal("exemptionThreshold") : null);
                    decl.set("belowThreshold", totals.isBelowThreshold() ? "Y" : "N");
                    decl.set("taxRate", rate);
                    decl.set("taxAmount", totals.getTaxAmount());
                    decl.set("statusId", STATUS_GENERATED);
                    decl.set("generatedDate", UtilDateTime.nowTimestamp());
                    decl.set("createdDate", UtilDateTime.nowTimestamp());
                    decl.create();
                    for (Map<String, Object> row : entry.getValue()) {
                        LeasePublicChargeCalculator.AssetLine line = (LeasePublicChargeCalculator.AssetLine) row.get("line");
                        GenericValue declAsset = delegator.makeValue("LeasePublicChargeDeclAsset");
                        declAsset.set("leasePublicChargeDeclId", declId);
                        for (String f : UtilMisc.toList("fixedAssetId", "leaseAgreementId", "assetSeqId", "assetName",
                                "fixedAssetTypeId", "acquisitionCost", "acquisitionYear", "quantity", "ownershipShare",
                                "usefulLifeYears", "depreciationRate", "taxableBaseRatio", "rateClassCode", "unitAmount")) {
                            declAsset.set(f, row.get(f));
                        }
                        declAsset.set("fullAssessedValue", line.getFullAssessedValue());
                        declAsset.set("assessedValue", line.getAssessedValue());
                        declAsset.set("taxableValue", line.getTaxableValue());
                        declAsset.set("chargeAmount", line.getChargeAmount());
                        declAsset.create();
                    }
                    declIds.add(declId);
                    created++;
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("createdCount", created);
            result.put("declarationCount", (long) declIds.size());
            result.put("skippedAssetCount", skipped);
            result.put("leasePublicChargeDeclIds", declIds);
            return result;
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
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
     * Dr public charge expense (租税公課) / Cr public charge payable, with the GL
     * account types of the charge type. Idempotent per declaration.
     */
    public static Map<String, Object> recordLeasePublicChargePayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String declId = (String) context.get("leasePublicChargeDeclId");
        Timestamp paymentDate = context.get("paymentDate") != null
                ? (Timestamp) context.get("paymentDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue decl = EntityQuery.use(delegator).from("LeasePublicChargeDecl")
                    .where("leasePublicChargeDeclId", declId).queryOne();
            if (decl == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeasePublicChargeDecl", "id", declId), locale);
            }
            GenericValue existing = EntityQuery.use(delegator).from("LeasePublicChargePayment")
                    .where("leasePublicChargeDeclId", declId).queryOne();
            if (existing != null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("acctgTransId", existing.getString("acctgTransId"));
                result.put("paymentCreated", false);
                return result;
            }
            BigDecimal amount = nz(decl.getBigDecimal("taxAmount"));
            if (amount.signum() <= 0) {
                return error("FinanceLeasePublicChargeNothingDue", UtilMisc.toMap("leasePublicChargeDeclId", declId), locale);
            }
            GenericValue type = chargeType(delegator, decl.getString("publicChargeTypeId"));
            String expense = type != null && UtilValidate.isNotEmpty(type.getString("expenseGlAccountTypeId"))
                    ? type.getString("expenseGlAccountTypeId") : GL_EXPENSE;
            String payable = type != null && UtilValidate.isNotEmpty(type.getString("payableGlAccountTypeId"))
                    ? type.getString("payableGlAccountTypeId") : GL_PAYABLE;
            String organizationPartyId = decl.getString("organizationPartyId");
            String currency = decl.getString("currencyUomId");
            // Dr expense / Cr payable (the actual cash disbursement is out of scope).
            List<FinanceGlHelper.Line> lines = new ArrayList<>();
            lines.add(new FinanceGlHelper.Line(expense, true, amount));
            lines.add(new FinanceGlHelper.Line(payable, false, amount));
            String acctgTransId = FinanceGlHelper.postTransaction(delegator, dispatcher,
                    "Lease public charge " + decl.getString("publicChargeTypeId") + " " + declId, paymentDate,
                    organizationPartyId, null, currency, lines, userLogin);

            GenericValue payment = delegator.makeValue("LeasePublicChargePayment");
            payment.set("leasePublicChargeDeclId", declId);
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

    // ------------------------------------------------------------------
    // Migration of the STEP L10 tables (LeasePropertyTax*)
    // ------------------------------------------------------------------

    /**
     * Copy the rows of the STEP L10 tables (LeasePropertyTaxRate / Decl /
     * DeclAsset / Payment) to the LeasePublicCharge* tables as the depreciable
     * asset tax. Idempotent: a rate is copied when no rate with the same id
     * exists; a declaration is copied when no declaration has it as
     * {@code legacyDeclId} and none exists for the same organization x year x
     * jurisdiction (counted as a conflict). The legacy rows are left in place.
     */
    public static Map<String, Object> migrateLeasePropertyTaxData(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            long rates = 0;
            for (GenericValue old : EntityQuery.use(delegator).from("LeasePropertyTaxRate").queryList()) {
                String id = old.getString("leasePropertyTaxRateId");
                if (EntityQuery.use(delegator).from("LeasePublicChargeRate").where("leasePublicChargeRateId", id).queryOne() != null) {
                    continue;
                }
                GenericValue rate = delegator.makeValue("LeasePublicChargeRate");
                rate.set("leasePublicChargeRateId", id);
                rate.set("publicChargeTypeId", TYPE_DEPR_ASSET);
                rate.set("jurisdictionGeoId", old.get("municipalityGeoId"));
                rate.set("taxRate", old.get("taxRate"));
                rate.set("standardRate", old.get("standardRate"));
                rate.set("fromDate", old.get("fromDate"));
                rate.set("thruDate", old.get("thruDate"));
                rate.set("description", old.get("description"));
                rate.create();
                rates++;
            }
            long decls = 0;
            long conflicts = 0;
            for (GenericValue old : EntityQuery.use(delegator).from("LeasePropertyTaxDecl").orderBy("leasePropertyTaxDeclId").queryList()) {
                String oldId = old.getString("leasePropertyTaxDeclId");
                if (EntityQuery.use(delegator).from("LeasePublicChargeDecl").where("legacyDeclId", oldId).queryFirst() != null) {
                    continue;
                }
                if (EntityQuery.use(delegator).from("LeasePublicChargeDecl").where("organizationPartyId",
                        old.get("organizationPartyId"), "publicChargeTypeId", TYPE_DEPR_ASSET, "taxYear", old.get("taxYear"),
                        "jurisdictionGeoId", old.get("municipalityGeoId")).queryFirst() != null) {
                    conflicts++;
                    continue;
                }
                GenericValue oldPayment = EntityQuery.use(delegator).from("LeasePropertyTaxPayment")
                        .where("leasePropertyTaxDeclId", oldId).queryOne();
                String newId = delegator.getNextSeqId("LeasePublicChargeDecl");
                GenericValue decl = delegator.makeValue("LeasePublicChargeDecl");
                decl.set("leasePublicChargeDeclId", newId);
                decl.set("publicChargeTypeId", TYPE_DEPR_ASSET);
                decl.set("jurisdictionGeoId", old.get("municipalityGeoId"));
                for (String f : UtilMisc.toList("organizationPartyId", "taxYear", "currencyUomId", "assetCount",
                        "totalAssessedValue", "taxableBase", "exemptionThreshold", "belowThreshold", "taxRate", "taxAmount",
                        "generatedDate", "createdDate")) {
                    decl.set(f, old.get(f));
                }
                decl.set("statusId", oldPayment != null || "LEASE_PTAX_PAID".equals(old.getString("statusId"))
                        ? STATUS_PAID : STATUS_GENERATED);
                decl.set("legacyDeclId", oldId);
                decl.create();
                for (GenericValue oldAsset : EntityQuery.use(delegator).from("LeasePropertyTaxDeclAsset")
                        .where("leasePropertyTaxDeclId", oldId).queryList()) {
                    GenericValue a = delegator.makeValue("LeasePublicChargeDeclAsset");
                    a.set("leasePublicChargeDeclId", newId);
                    for (String f : UtilMisc.toList("fixedAssetId", "leaseAgreementId", "assetSeqId", "assetName",
                            "acquisitionCost", "acquisitionYear", "usefulLifeYears", "depreciationRate", "assessedValue")) {
                        a.set(f, oldAsset.get(f));
                    }
                    a.set("fullAssessedValue", oldAsset.get("assessedValue"));
                    a.set("taxableValue", oldAsset.get("assessedValue"));
                    a.set("ownershipShare", BigDecimal.ONE);
                    a.set("taxableBaseRatio", BigDecimal.ONE);
                    a.create();
                }
                if (oldPayment != null) {
                    GenericValue p = delegator.makeValue("LeasePublicChargePayment");
                    p.set("leasePublicChargeDeclId", newId);
                    for (String f : UtilMisc.toList("paymentDate", "amount", "currencyUomId", "paymentId", "acctgTransId",
                            "comments", "createdByUserLogin", "createdDate")) {
                        p.set(f, oldPayment.get(f));
                    }
                    p.create();
                }
                decls++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("ratesMigrated", rates);
            result.put("declarationsMigrated", decls);
            result.put("declarationConflicts", conflicts);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
