/*
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
 */
package org.apache.ofbiz.finance.test

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeasePublicChargeCalculator
import org.apache.ofbiz.finance.lease.LeasePublicChargeServices
import org.apache.ofbiz.finance.lease.PropertyTaxValuation
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L10 / L10b: public charges (公租公課) of lessor-owned leased assets.
 *
 * L10 (償却資産税, charge type LSPC_DEPR_ASSET): valuation (旧定率法相当, half first
 * year, 5% floor), municipal rate resolution, the estimate supplied to the L9
 * component, per-municipality declaration with the 免税点 (1.5M) judgment, and the
 * payment / expense booking.
 *
 * L10b: the calculators (share, special ratio, unit amount), the asset type ->
 * charge type resolution (vehicles pay the vehicle taxes, not the depreciable
 * asset tax), the vehicle tax estimate and declaration at the prefecture, the
 * ownership share of a co-owned asset, and the migration of the L10 tables.
 *
 * Uses the USD non-transfer lease product TEST_LEASEPROD_USD (base currency of
 * Company). Each test creates its own municipality / prefecture Geo so the
 * per-jurisdiction aggregates are isolated from other leases in the DB.
 */
class LeasePublicChargeTests extends OFBizTestCase {

    LeasePublicChargeTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay())
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private Map<String, BigDecimal> entrySums(String acctgTransId) {
        Map<String, BigDecimal> sums = [:]
        from('AcctgTransEntry').where('acctgTransId', acctgTransId).queryList().each { GenericValue e ->
            String key = e.glAccountId + ':' + e.debitCreditFlag
            sums[key] = (sums[key] ?: BigDecimal.ZERO).add(e.getBigDecimal('amount'))
        }
        return sums
    }

    private String newGeoId(String prefix) {
        String geoId = prefix + delegator.getNextSeqId('Geo')
        return geoId.length() > 20 ? geoId.substring(0, 20) : geoId
    }

    /** A fresh municipality Geo with a depreciable asset tax rate; optionally under a fresh prefecture. */
    private String newMunicipality(BigDecimal rate, String prefectureGeoId = null) {
        String geoId = newGeoId('JPT-')
        delegator.create('Geo', [geoId: geoId, geoTypeId: 'MUNICIPALITY', geoName: 'Test municipality ' + geoId])
        if (prefectureGeoId != null) {
            delegator.create('GeoAssoc', [geoId: prefectureGeoId, geoIdTo: geoId, geoAssocTypeId: 'REGIONS'])
        }
        String rateId = delegator.getNextSeqId('LeasePublicChargeRate')
        delegator.create('LeasePublicChargeRate', [leasePublicChargeRateId: rateId, publicChargeTypeId: 'LSPC_DEPR_ASSET',
                jurisdictionGeoId: geoId, taxRate: rate, standardRate: 'N', fromDate: ts(LocalDate.of(2001, 1, 1))])
        return geoId
    }

    private String newPrefecture() {
        String geoId = newGeoId('JPTP-')
        delegator.create('Geo', [geoId: geoId, geoTypeId: 'PROVINCE', geoName: 'Test prefecture ' + geoId])
        return geoId
    }

    /** New lessee with a lease application of one asset (not yet quoted). */
    private String newApplication(GenericValue userLogin, long termMonths, BigDecimal cost, String fixedAssetTypeId) {
        String lessee = 'TEST_LSPTX_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Public charge test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
                requestedTermMonths: termMonths, userLogin: userLogin])
        ok(app)
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: app.leaseApplicationId,
                fixedAssetTypeId: fixedAssetTypeId, assetName: 'Asset', quantity: BigDecimal.ONE, estimatedAcquisitionCost: cost,
                supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        return app.leaseApplicationId
    }

    /** New lessee with an ACTIVE non-transfer lease; the asset is in municipalityGeoId. */
    private Map activeLease(GenericValue userLogin, LocalDate start, long termMonths, BigDecimal cost,
            String municipalityGeoId, Long usefulLifeYears, String fixedAssetTypeId = 'EQUIPMENT') {
        String appId = newApplication(userLogin, termMonths, cost, fixedAssetTypeId)
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: cost, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: termMonths,
                leaseStartDate: ts(start), userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        String agreementId = agreement.leaseAgreementId
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: '00001',
                purchaseDate: ts(start), userLogin: userLogin]))
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        // Set the asset municipality and useful life for the valuation.
        ok(dispatcher.runSync('setLeaseAssetPublicChargeAttributes', [leaseAgreementId: agreementId, assetSeqId: '00001',
                municipalityGeoId: municipalityGeoId, usefulLifeYears: usefulLifeYears, userLogin: userLogin]))
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        return [leaseAgreementId: agreementId, fixedAssetId: asset.fixedAssetId]
    }

    // ------------------------------------------------------------------
    // L10: depreciable asset tax (償却資産税)
    // ------------------------------------------------------------------

    // Pure valuation: half first year, full years after, 5% floor; assessed value only after acquisition year.
    void testValuationCalculation() {
        BigDecimal cost = new BigDecimal('1000000.00')
        BigDecimal r = PropertyTaxValuation.depreciationRateForLife(6)
        assert r.signum() > 0 && r.compareTo(BigDecimal.ONE) < 0
        // Year 0 = cost; year 1 = cost*(1-r/2); year 2 = that*(1-r).
        BigDecimal v0 = PropertyTaxValuation.valuation(cost, r, 0, 2, RoundingMode.HALF_UP)
        BigDecimal v1 = PropertyTaxValuation.valuation(cost, r, 1, 2, RoundingMode.HALF_UP)
        BigDecimal v2 = PropertyTaxValuation.valuation(cost, r, 2, 2, RoundingMode.HALF_UP)
        assert v0.compareTo(cost) == 0
        BigDecimal expV1 = cost.multiply(BigDecimal.ONE.subtract(r.divide(new BigDecimal('2'), 10, RoundingMode.HALF_UP))).setScale(2, RoundingMode.HALF_UP)
        assert v1.compareTo(expV1) == 0
        assert v2.compareTo(v1) < 0
        // Floor at 5% of cost after many years.
        BigDecimal vFar = PropertyTaxValuation.valuation(cost, r, 50, 2, RoundingMode.HALF_UP)
        assert vFar.compareTo(cost.multiply(new BigDecimal('0.05')).setScale(2, RoundingMode.HALF_UP)) == 0
        // Assessed value: 0 in and before the acquisition year, half-year value the year after.
        assert PropertyTaxValuation.assessedValue(cost, r, 2024, 2024, 2, RoundingMode.HALF_UP).signum() == 0
        assert PropertyTaxValuation.assessedValue(cost, r, 2024, 2025, 2, RoundingMode.HALF_UP).compareTo(v1) == 0
        // Rejected inputs.
        try {
            PropertyTaxValuation.depreciationRateForLife(0)
            assert false
        } catch (IllegalArgumentException ignored) {
        }
    }

    // Rate resolution: municipal row, else standard (seed 1.4%).
    void testRateResolutionAndEstimate() {
        GenericValue userLogin = admin()
        // Standard rate (no municipality) = 1.4%; the default charge type is the depreciable asset tax.
        Map est0 = dispatcher.runSync('resolveLeasePublicChargeEstimate', [acquisitionCost: new BigDecimal('1000000.00'),
                termMonths: 60L, currencyUomId: 'USD', usefulLifeYears: 6L, asOfDate: ts(LocalDate.of(2024, 6, 1)),
                userLogin: userLogin])
        ok(est0)
        assert est0.taxRate.compareTo(new BigDecimal('0.014')) == 0
        assert est0.monthlyEstimate.signum() > 0
        assert est0.yearlyEstimate.signum() > 0

        // Municipal excess rate.
        String muni = newMunicipality(new BigDecimal('0.016'))
        Map est1 = dispatcher.runSync('resolveLeasePublicChargeEstimate', [publicChargeTypeId: 'LSPC_DEPR_ASSET',
                acquisitionCost: new BigDecimal('1000000.00'), termMonths: 60L, municipalityGeoId: muni, currencyUomId: 'USD',
                usefulLifeYears: 6L, asOfDate: ts(LocalDate.of(2024, 6, 1)), userLogin: userLogin])
        ok(est1)
        assert est1.taxRate.compareTo(new BigDecimal('0.016')) == 0
        assert est1.jurisdictionGeoId == muni
        // Higher rate => higher estimate than the standard.
        assert est1.yearlyEstimate.compareTo(est0.yearlyEstimate) > 0
        // Invalid input rejected.
        assert ServiceUtil.isError(dispatcher.runSync('resolveLeasePublicChargeEstimate',
                [acquisitionCost: BigDecimal.ZERO, termMonths: 60L, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('resolveLeasePublicChargeEstimate',
                [publicChargeTypeId: 'NO_SUCH_TYPE', acquisitionCost: BigDecimal.ONE, termMonths: 60L, userLogin: userLogin]))
    }

    // Estimate supplied to the L9 component on quote creation.
    void testEstimateSuppliedToComponent() {
        GenericValue userLogin = admin()
        String muni = newMunicipality(new BigDecimal('0.014'))
        String appId = newApplication(userLogin, 60L, new BigDecimal('1000000.00'), 'EQUIPMENT')
        // Property tax component with no amount: estimated from the valuation x the municipal rate.
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: 60L,
                leaseStartDate: ts(LocalDate.of(2024, 6, 1)),
                components: [[componentTypeEnumId: 'LSCMP_PROP_TAX', taxClassEnumId: 'LSTAX_TAXABLE',
                              revenueTreatmentEnumId: 'LSCMP_REV_SEPARATE', municipalityGeoId: muni, usefulLifeYears: 6L]],
                userLogin: userLogin])
        ok(quote)
        GenericValue comp = from('LeaseQuoteComponent').where('leaseQuoteId', quote.leaseQuoteId, 'componentSeqId', '00001').queryOne()
        assert comp != null
        assert comp.componentTypeEnumId == 'LSCMP_PROP_TAX'
        assert comp.monthlyAmount.signum() > 0                                  // auto-estimated
        Map est = dispatcher.runSync('resolveLeasePublicChargeEstimate', [acquisitionCost: new BigDecimal('1000000.00'),
                termMonths: 60L, municipalityGeoId: muni, currencyUomId: 'USD', usefulLifeYears: 6L,
                asOfDate: ts(LocalDate.of(2024, 6, 1)), userLogin: userLogin])
        ok(est)
        assert comp.monthlyAmount.compareTo(est.monthlyEstimate) == 0
    }

    // Declaration generation: per municipality, 免税点 judgment, tax at the municipal rate; payment books the expense.
    void testDeclarationAndPayment() {
        GenericValue userLogin = admin()
        int taxYear = LocalDate.now().getYear()
        LocalDate acquired = LocalDate.of(taxYear - 2, 6, 1)                    // acquired 2 years before the tax year

        // A municipality whose single asset is above the exemption threshold (免税点 1.5M).
        String muniTaxed = newMunicipality(new BigDecimal('0.014'))
        activeLease(userLogin, acquired, 60L, new BigDecimal('5000000.00'), muniTaxed, 6L)
        // A municipality whose single asset is below the exemption threshold.
        String muniExempt = newMunicipality(new BigDecimal('0.014'))
        activeLease(userLogin, acquired, 60L, new BigDecimal('1000000.00'), muniExempt, 20L)

        Map gen = dispatcher.runSync('generateLeasePublicChargeDeclarations', [taxYear: (long) taxYear,
                publicChargeTypeId: 'LSPC_DEPR_ASSET', userLogin: userLogin])
        ok(gen)

        GenericValue declTaxed = from('LeasePublicChargeDecl').where('organizationPartyId', 'Company',
                'publicChargeTypeId', 'LSPC_DEPR_ASSET', 'taxYear', (long) taxYear, 'jurisdictionGeoId', muniTaxed).queryOne()
        assert declTaxed != null
        assert declTaxed.belowThreshold == 'N'
        assert declTaxed.assetCount == 1L
        assert declTaxed.totalAssessedValue.compareTo(new BigDecimal('1500000')) >= 0
        // Taxable base floored to 1000 yen; tax = base x 1.4% floored to 100 yen.
        BigDecimal base = declTaxed.totalAssessedValue.divideToIntegralValue(new BigDecimal('1000')).multiply(new BigDecimal('1000'))
        assert declTaxed.taxableBase.compareTo(base) == 0
        BigDecimal expectedTax = base.multiply(new BigDecimal('0.014')).divide(new BigDecimal('100'), 0, RoundingMode.DOWN).multiply(new BigDecimal('100'))
        assert declTaxed.taxAmount.compareTo(expectedTax) == 0
        assert declTaxed.taxAmount.signum() > 0
        List<GenericValue> lines = from('LeasePublicChargeDeclAsset').where('leasePublicChargeDeclId', declTaxed.leasePublicChargeDeclId).queryList()
        assert lines.size() == 1
        assert lines[0].ownershipShare.compareTo(BigDecimal.ONE) == 0
        assert lines[0].fullAssessedValue.compareTo(lines[0].assessedValue) == 0

        GenericValue declExempt = from('LeasePublicChargeDecl').where('organizationPartyId', 'Company',
                'publicChargeTypeId', 'LSPC_DEPR_ASSET', 'taxYear', (long) taxYear, 'jurisdictionGeoId', muniExempt).queryOne()
        assert declExempt != null
        assert declExempt.belowThreshold == 'Y'                                 // below 免税点
        assert declExempt.taxableBase.signum() == 0
        assert declExempt.taxAmount.signum() == 0

        // Idempotent: re-running (all charge types) creates nothing new for the municipality.
        Map rerun = dispatcher.runSync('generateLeasePublicChargeDeclarations', [taxYear: (long) taxYear, userLogin: userLogin])
        ok(rerun)
        assert from('LeasePublicChargeDecl').where('organizationPartyId', 'Company', 'publicChargeTypeId', 'LSPC_DEPR_ASSET',
                'taxYear', (long) taxYear, 'jurisdictionGeoId', muniTaxed).queryCount() == 1

        // Payment of the taxed declaration: Dr 租税公課 (601500) / Cr 未払租税公課 (224600), balanced.
        Map pay = dispatcher.runSync('recordLeasePublicChargePayment', [leasePublicChargeDeclId: declTaxed.leasePublicChargeDeclId,
                userLogin: userLogin])
        ok(pay)
        assert pay.paymentCreated == true
        Map<String, BigDecimal> sums = entrySums(pay.acctgTransId)
        assert sums['601500:D'].compareTo(declTaxed.taxAmount) == 0
        assert sums['224600:C'].compareTo(declTaxed.taxAmount) == 0
        assert from('LeasePublicChargeDecl').where('leasePublicChargeDeclId', declTaxed.leasePublicChargeDeclId).queryOne().statusId == 'LEASE_PCHG_PAID'

        // Idempotent payment; a below-threshold declaration has nothing to pay.
        Map payAgain = dispatcher.runSync('recordLeasePublicChargePayment', [leasePublicChargeDeclId: declTaxed.leasePublicChargeDeclId,
                userLogin: userLogin])
        ok(payAgain)
        assert payAgain.paymentCreated == false
        assert ServiceUtil.isError(dispatcher.runSync('recordLeasePublicChargePayment',
                [leasePublicChargeDeclId: declExempt.leasePublicChargeDeclId, userLogin: userLogin]))
    }

    // ------------------------------------------------------------------
    // L10b: generalization to public charges (公租公課)
    // ------------------------------------------------------------------

    // Pure calculators: ownership share, special taxable-base ratio, 免税点 on the taxable base, unit amounts.
    void testCalculatorShareRatioAndUnit() {
        LeasePublicChargeCalculator value = LeasePublicChargeCalculator.REGISTRY.get('LSPC_CALC_VALUE')
        LeasePublicChargeCalculator unit = LeasePublicChargeCalculator.REGISTRY.get('LSPC_CALC_UNIT')
        assert value != null && unit != null
        LeasePublicChargeCalculator.Rules rules = new LeasePublicChargeCalculator.Rules(new BigDecimal('1500000'),
                new BigDecimal('1000'), new BigDecimal('100'), 2, RoundingMode.HALF_UP)
        BigDecimal cost = new BigDecimal('4000000.00')
        BigDecimal r = PropertyTaxValuation.depreciationRateForLife(10)
        BigDecimal full = PropertyTaxValuation.assessedValue(cost, r, 2024, 2025, 2, RoundingMode.HALF_UP)

        // Sole owner, ratio 1: assessed = taxable = full; taxed (above 1.5M).
        def sole = value.assess(rules, new LeasePublicChargeCalculator.AssetInput().acquisitionCost(cost)
                .acquisitionYear(2024).depreciationRate(r), 2025)
        assert sole.fullAssessedValue.compareTo(full) == 0
        assert sole.assessedValue.compareTo(full) == 0
        assert sole.taxableValue.compareTo(full) == 0
        def soleTotals = value.declare(rules, [sole], new BigDecimal('0.014'))
        assert !soleTotals.belowThreshold
        assert soleTotals.taxableBase.compareTo(full.divideToIntegralValue(new BigDecimal('1000')).multiply(new BigDecimal('1000'))) == 0
        assert soleTotals.taxAmount.remainder(new BigDecimal('100')).signum() == 0

        // Share 1/2 and special ratio 1/2: taxable = full x 1/4, below the 免税点 (judged on the taxable base).
        def shared = value.assess(rules, new LeasePublicChargeCalculator.AssetInput().acquisitionCost(cost)
                .acquisitionYear(2024).depreciationRate(r).ownershipShare(new BigDecimal('0.5'))
                .taxableBaseRatio(new BigDecimal('0.5')), 2025)
        assert shared.assessedValue.compareTo(full.multiply(new BigDecimal('0.5')).setScale(2, RoundingMode.HALF_UP)) == 0
        assert shared.taxableValue.compareTo(shared.assessedValue.multiply(new BigDecimal('0.5')).setScale(2, RoundingMode.HALF_UP)) == 0
        def sharedTotals = value.declare(rules, [shared], new BigDecimal('0.014'))
        assert sharedTotals.belowThreshold
        assert sharedTotals.taxAmount.signum() == 0
        // Not assessed in the acquisition year.
        assert value.assess(rules, new LeasePublicChargeCalculator.AssetInput().acquisitionCost(cost)
                .acquisitionYear(2025).depreciationRate(r), 2025) == null

        // Unit amount: 30,500 x 2 units x share 1/2 = 30,500; declaration sums the lines, no 免税点.
        LeasePublicChargeCalculator.Rules unitRules = new LeasePublicChargeCalculator.Rules(null, null, null, 2, RoundingMode.HALF_UP)
        def car = unit.assess(unitRules, new LeasePublicChargeCalculator.AssetInput().unitAmount(new BigDecimal('30500'))
                .quantity(new BigDecimal('2')).ownershipShare(new BigDecimal('0.5')), 2025)
        assert car.chargeAmount.compareTo(new BigDecimal('30500')) == 0
        def car2 = unit.assess(unitRules, new LeasePublicChargeCalculator.AssetInput().unitAmount(new BigDecimal('36000')), 2025)
        def unitTotals = unit.declare(unitRules, [car, car2], null)
        assert !unitTotals.belowThreshold
        assert unitTotals.taxAmount.compareTo(new BigDecimal('66500')) == 0
        // Rejected inputs.
        [{ unit.assess(unitRules, new LeasePublicChargeCalculator.AssetInput(), 2025) },
         { value.assess(rules, new LeasePublicChargeCalculator.AssetInput().acquisitionCost(cost).acquisitionYear(2024)
                 .depreciationRate(r).ownershipShare(new BigDecimal('1.5')), 2025) }].each { Closure c ->
            try {
                c.call()
                assert false
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // Asset type -> charge types: unmapped = 償却資産税; vehicles = 自動車税・重量税 only; vessels = 償却資産税 with
    // the special ratio; a child type inherits the nearest mapped ancestor.
    void testAssetTypeChargeResolution() {
        Map<String, BigDecimal> equipment = LeasePublicChargeServices.chargeTypesForAssetType(delegator, 'EQUIPMENT')
        assert equipment.keySet() == ['LSPC_DEPR_ASSET'] as Set
        Map<String, BigDecimal> vehicle = LeasePublicChargeServices.chargeTypesForAssetType(delegator, 'VEHICLE')
        assert vehicle.keySet() == ['LSPC_AUTO_TAX', 'LSPC_WEIGHT_TAX'] as Set
        Map<String, BigDecimal> vessel = LeasePublicChargeServices.chargeTypesForAssetType(delegator, 'VESSEL')
        assert vessel.keySet() == ['LSPC_DEPR_ASSET'] as Set
        assert vessel.LSPC_DEPR_ASSET.compareTo(new BigDecimal('0.5')) == 0
        assert LeasePublicChargeServices.chargeTypesForAssetType(delegator, null).keySet() == ['LSPC_DEPR_ASSET'] as Set

        String truck = 'TEST_TRUCK_' + delegator.getNextSeqId('FixedAssetType')
        delegator.create('FixedAssetType', [fixedAssetTypeId: truck, parentTypeId: 'VEHICLE', description: 'Test truck'])
        assert LeasePublicChargeServices.chargeTypesForAssetType(delegator, truck).keySet() == ['LSPC_AUTO_TAX', 'LSPC_WEIGHT_TAX'] as Set
    }

    // Vehicles: the quote estimates the vehicle taxes (not the property tax); the declaration of the automobile tax is
    // filed with the prefecture by rate class; an asset with no rate class is skipped; no depreciable asset tax.
    void testVehicleEstimateAndDeclaration() {
        GenericValue userLogin = admin()
        String pref = newPrefecture()
        String muni = newMunicipality(new BigDecimal('0.014'), pref)

        // Quote: property tax equivalent is not applicable to a vehicle.
        String appId = newApplication(userLogin, 60L, new BigDecimal('3000000.00'), 'VEHICLE')
        Map bad = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: 60L,
                leaseStartDate: ts(LocalDate.of(2024, 6, 1)),
                components: [[componentTypeEnumId: 'LSCMP_PROP_TAX', municipalityGeoId: muni]], userLogin: userLogin])
        assert ServiceUtil.isError(bad)
        // Automobile tax (prefecture, class PASS_1500 = 30,500 / year) and weight tax (national, PASS_W1500 = 12,300 / year).
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: 60L,
                leaseStartDate: ts(LocalDate.of(2024, 6, 1)),
                components: [[componentTypeEnumId: 'LSCMP_AUTO_TAX', municipalityGeoId: muni, rateClassCode: 'PASS_1500'],
                             [componentTypeEnumId: 'LSCMP_WEIGHT_TAX', rateClassCode: 'PASS_W1500']], userLogin: userLogin])
        ok(quote)
        GenericValue auto = from('LeaseQuoteComponent').where('leaseQuoteId', quote.leaseQuoteId, 'componentSeqId', '00001').queryOne()
        GenericValue weight = from('LeaseQuoteComponent').where('leaseQuoteId', quote.leaseQuoteId, 'componentSeqId', '00002').queryOne()
        Map autoEst = dispatcher.runSync('resolveLeasePublicChargeEstimate', [publicChargeTypeId: 'LSPC_AUTO_TAX', termMonths: 60L,
                municipalityGeoId: muni, rateClassCode: 'PASS_1500', currencyUomId: 'USD', asOfDate: ts(LocalDate.of(2024, 6, 1)),
                userLogin: userLogin])
        ok(autoEst)
        assert autoEst.jurisdictionGeoId == pref
        assert autoEst.unitAmount.compareTo(new BigDecimal('30500')) == 0
        assert autoEst.yearlyEstimate.compareTo(new BigDecimal('30500')) == 0
        assert auto.monthlyAmount.compareTo(autoEst.monthlyEstimate) == 0
        assert auto.monthlyAmount.compareTo(new BigDecimal('30500').divide(new BigDecimal('12'), 2, RoundingMode.HALF_UP)) == 0
        assert weight.monthlyAmount.compareTo(new BigDecimal('12300').divide(new BigDecimal('12'), 2, RoundingMode.HALF_UP)) == 0
        // Unknown rate class rejected.
        assert ServiceUtil.isError(dispatcher.runSync('resolveLeasePublicChargeEstimate', [publicChargeTypeId: 'LSPC_AUTO_TAX',
                termMonths: 60L, rateClassCode: 'NO_SUCH_CLASS', userLogin: userLogin]))

        // Declaration: two vehicles in the municipality, only one with a rate class.
        int taxYear = LocalDate.now().getYear()
        LocalDate acquired = LocalDate.of(taxYear - 1, 6, 1)                    // before April 1 of the tax year
        Map car = activeLease(userLogin, acquired, 60L, new BigDecimal('3000000.00'), muni, null, 'VEHICLE')
        ok(dispatcher.runSync('setLeaseAssetPublicChargeAttributes', [leaseAgreementId: car.leaseAgreementId, assetSeqId: '00001',
                publicChargeTypeId: 'LSPC_AUTO_TAX', rateClassCode: 'PASS_1500', userLogin: userLogin]))
        activeLease(userLogin, acquired, 60L, new BigDecimal('2500000.00'), muni, null, 'VEHICLE')

        Map gen = dispatcher.runSync('generateLeasePublicChargeDeclarations', [taxYear: (long) taxYear,
                publicChargeTypeId: 'LSPC_AUTO_TAX', userLogin: userLogin])
        ok(gen)
        assert gen.skippedAssetCount >= 1L                                      // the vehicle with no rate class
        GenericValue decl = from('LeasePublicChargeDecl').where('organizationPartyId', 'Company', 'publicChargeTypeId', 'LSPC_AUTO_TAX',
                'taxYear', (long) taxYear, 'jurisdictionGeoId', pref).queryOne()
        assert decl != null
        assert decl.assetCount == 1L
        assert decl.taxAmount.compareTo(new BigDecimal('30500')) == 0
        assert decl.belowThreshold == 'N'
        GenericValue line = from('LeasePublicChargeDeclAsset').where('leasePublicChargeDeclId', decl.leasePublicChargeDeclId,
                'fixedAssetId', car.fixedAssetId).queryOne()
        assert line.rateClassCode == 'PASS_1500'
        assert line.chargeAmount.compareTo(new BigDecimal('30500')) == 0

        // No depreciable asset tax for vehicles.
        ok(dispatcher.runSync('generateLeasePublicChargeDeclarations', [taxYear: (long) taxYear,
                publicChargeTypeId: 'LSPC_DEPR_ASSET', userLogin: userLogin]))
        assert from('LeasePublicChargeDecl').where('publicChargeTypeId', 'LSPC_DEPR_ASSET', 'jurisdictionGeoId', muni).queryCount() == 0

        // Payment books the charge type's accounts.
        Map pay = dispatcher.runSync('recordLeasePublicChargePayment', [leasePublicChargeDeclId: decl.leasePublicChargeDeclId,
                userLogin: userLogin])
        ok(pay)
        Map<String, BigDecimal> sums = entrySums(pay.acctgTransId)
        assert sums['601500:D'].compareTo(new BigDecimal('30500')) == 0
        assert sums['224600:C'].compareTo(new BigDecimal('30500')) == 0
    }

    // A co-owned asset (syndicated lease): the lessor declares its share, and the 免税点 is judged on the share.
    void testCoOwnedAssetShare() {
        GenericValue userLogin = admin()
        int taxYear = LocalDate.now().getYear()
        String muni = newMunicipality(new BigDecimal('0.014'))
        Map lease = activeLease(userLogin, LocalDate.of(taxYear - 2, 6, 1), 60L, new BigDecimal('5000000.00'), muni, 6L)
        // Invalid share rejected; 1/2 accepted.
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAssetPublicChargeAttributes', [leaseAgreementId: lease.leaseAgreementId,
                assetSeqId: '00001', ownershipShare: new BigDecimal('1.5'), userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseAssetPublicChargeAttributes', [leaseAgreementId: lease.leaseAgreementId,
                assetSeqId: '00001', ownershipShare: new BigDecimal('0.5'), userLogin: userLogin]))

        ok(dispatcher.runSync('generateLeasePublicChargeDeclarations', [taxYear: (long) taxYear,
                publicChargeTypeId: 'LSPC_DEPR_ASSET', userLogin: userLogin]))
        GenericValue decl = from('LeasePublicChargeDecl').where('organizationPartyId', 'Company', 'publicChargeTypeId', 'LSPC_DEPR_ASSET',
                'taxYear', (long) taxYear, 'jurisdictionGeoId', muni).queryOne()
        assert decl != null
        GenericValue line = from('LeasePublicChargeDeclAsset').where('leasePublicChargeDeclId', decl.leasePublicChargeDeclId,
                'fixedAssetId', lease.fixedAssetId).queryOne()
        assert line.ownershipShare.compareTo(new BigDecimal('0.5')) == 0
        assert line.assessedValue.compareTo(line.fullAssessedValue.multiply(new BigDecimal('0.5')).setScale(2, RoundingMode.HALF_UP)) == 0
        assert decl.totalAssessedValue.compareTo(line.assessedValue) == 0
        // The whole asset (about 2.86M) would be taxed; the half share (about 1.43M) is below the 免税点.
        assert line.fullAssessedValue.compareTo(new BigDecimal('1500000')) >= 0
        assert decl.belowThreshold == 'Y'
        assert decl.taxAmount.signum() == 0
    }

    // Migration of the STEP L10 tables (LeasePropertyTax*) to LeasePublicCharge*, idempotent.
    void testMigrationFromLegacy() {
        GenericValue userLogin = admin()
        String muni = newGeoId('JPT-')
        delegator.create('Geo', [geoId: muni, geoTypeId: 'MUNICIPALITY', geoName: 'Legacy municipality ' + muni])
        String rateId = 'TLPTR_' + delegator.getNextSeqId('LeasePropertyTaxRate')
        delegator.create('LeasePropertyTaxRate', [leasePropertyTaxRateId: rateId, municipalityGeoId: muni,
                taxRate: new BigDecimal('0.015'), standardRate: 'N', fromDate: ts(LocalDate.of(2001, 1, 1))])
        String oldId = 'TLPTD_' + delegator.getNextSeqId('LeasePropertyTaxDecl')
        delegator.create('LeasePropertyTaxDecl', [leasePropertyTaxDeclId: oldId, organizationPartyId: 'Company', taxYear: 2020L,
                municipalityGeoId: muni, currencyUomId: 'USD', assetCount: 1L, totalAssessedValue: new BigDecimal('2000000'),
                taxableBase: new BigDecimal('2000000'), exemptionThreshold: new BigDecimal('1500000'), belowThreshold: 'N',
                taxRate: new BigDecimal('0.015'), taxAmount: new BigDecimal('30000')])
        delegator.create('LeasePropertyTaxDeclAsset', [leasePropertyTaxDeclId: oldId, fixedAssetId: 'LEGACY_FA_1',
                acquisitionCost: new BigDecimal('3000000'), acquisitionYear: 2018L, usefulLifeYears: 6L,
                assessedValue: new BigDecimal('2000000')])
        delegator.create('LeasePropertyTaxPayment', [leasePropertyTaxDeclId: oldId, paymentDate: ts(LocalDate.of(2020, 6, 30)),
                amount: new BigDecimal('30000'), currencyUomId: 'USD'])

        Map res = dispatcher.runSync('migrateLeasePropertyTaxData', [userLogin: userLogin])
        ok(res)
        assert res.ratesMigrated >= 1L
        assert res.declarationsMigrated >= 1L
        GenericValue rate = from('LeasePublicChargeRate').where('leasePublicChargeRateId', rateId).queryOne()
        assert rate.publicChargeTypeId == 'LSPC_DEPR_ASSET'
        assert rate.jurisdictionGeoId == muni
        GenericValue decl = from('LeasePublicChargeDecl').where('legacyDeclId', oldId).queryOne()
        assert decl != null
        assert decl.publicChargeTypeId == 'LSPC_DEPR_ASSET'
        assert decl.jurisdictionGeoId == muni
        assert decl.taxAmount.compareTo(new BigDecimal('30000')) == 0
        assert decl.statusId == 'LEASE_PCHG_PAID'                               // a payment exists
        GenericValue line = from('LeasePublicChargeDeclAsset').where('leasePublicChargeDeclId', decl.leasePublicChargeDeclId,
                'fixedAssetId', 'LEGACY_FA_1').queryOne()
        assert line.assessedValue.compareTo(new BigDecimal('2000000')) == 0
        assert line.ownershipShare.compareTo(BigDecimal.ONE) == 0
        assert from('LeasePublicChargePayment').where('leasePublicChargeDeclId', decl.leasePublicChargeDeclId).queryOne() != null
        // The migrated rate is used by the rate resolution.
        Map est = dispatcher.runSync('resolveLeasePublicChargeEstimate', [acquisitionCost: new BigDecimal('1000000'),
                termMonths: 12L, municipalityGeoId: muni, userLogin: userLogin])
        ok(est)
        assert est.taxRate.compareTo(new BigDecimal('0.015')) == 0

        // Idempotent.
        Map again = dispatcher.runSync('migrateLeasePropertyTaxData', [userLogin: userLogin])
        ok(again)
        assert again.ratesMigrated == 0L
        assert again.declarationsMigrated == 0L
        assert from('LeasePublicChargeDecl').where('legacyDeclId', oldId).queryCount() == 1
    }
}
