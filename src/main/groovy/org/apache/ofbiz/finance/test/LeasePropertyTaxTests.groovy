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
import org.apache.ofbiz.finance.lease.PropertyTaxValuation
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L10: property tax (償却資産税) of lessor-owned leased assets. Valuation
 * (旧定率法相当, half first year, 5% floor), municipal rate resolution, the
 * estimate supplied to the L9 component, per-municipality declaration with the
 * 免税点 (1.5M) judgment, and the payment / expense booking.
 *
 * Uses the USD non-transfer lease product TEST_LEASEPROD_USD (base currency of
 * Company). Each test creates its own municipality Geo and rate so the
 * per-municipality aggregate (免税点) is isolated from other leases in the DB.
 */
class LeasePropertyTaxTests extends OFBizTestCase {

    LeasePropertyTaxTests(String name) {
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

    /** A fresh municipality Geo with a property tax rate. */
    private String newMunicipality(BigDecimal rate) {
        String geoId = 'JPT-' + delegator.getNextSeqId('Geo')
        if (geoId.length() > 20) {
            geoId = geoId.substring(0, 20)
        }
        delegator.create('Geo', [geoId: geoId, geoTypeId: 'MUNICIPALITY', geoName: 'Test municipality ' + geoId])
        String rateId = delegator.getNextSeqId('LeasePropertyTaxRate')
        delegator.create('LeasePropertyTaxRate', [leasePropertyTaxRateId: rateId, municipalityGeoId: geoId,
                taxRate: rate, standardRate: 'N', fromDate: ts(LocalDate.of(2001, 1, 1))])
        return geoId
    }

    /** New lessee with an ACTIVE non-transfer lease; the asset is in municipalityGeoId. */
    private Map activeLease(GenericValue userLogin, LocalDate start, long termMonths, BigDecimal cost,
            String municipalityGeoId, Long usefulLifeYears) {
        String lessee = 'TEST_LSPTX_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Property tax test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
                requestedTermMonths: termMonths, userLogin: userLogin])
        ok(app)
        String appId = app.leaseApplicationId
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: appId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Machine', quantity: BigDecimal.ONE, estimatedAcquisitionCost: cost,
                supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
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
        // Set the asset municipality and useful life for the property tax valuation.
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        asset.municipalityGeoId = municipalityGeoId
        asset.usefulLifeYears = usefulLifeYears
        asset.store()
        return [leaseAgreementId: agreementId, fixedAssetId: asset.fixedAssetId]
    }

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
        // Standard rate (no municipality) = 1.4%.
        Map est0 = dispatcher.runSync('resolveLeasePropertyTaxEstimate', [acquisitionCost: new BigDecimal('1000000.00'),
                termMonths: 60L, currencyUomId: 'USD', usefulLifeYears: 6L, asOfDate: ts(LocalDate.of(2024, 6, 1)),
                userLogin: userLogin])
        ok(est0)
        assert est0.taxRate.compareTo(new BigDecimal('0.014')) == 0
        assert est0.monthlyEstimate.signum() > 0
        assert est0.yearlyEstimate.signum() > 0

        // Municipal excess rate.
        String muni = newMunicipality(new BigDecimal('0.016'))
        Map est1 = dispatcher.runSync('resolveLeasePropertyTaxEstimate', [acquisitionCost: new BigDecimal('1000000.00'),
                termMonths: 60L, municipalityGeoId: muni, currencyUomId: 'USD', usefulLifeYears: 6L,
                asOfDate: ts(LocalDate.of(2024, 6, 1)), userLogin: userLogin])
        ok(est1)
        assert est1.taxRate.compareTo(new BigDecimal('0.016')) == 0
        // Higher rate => higher estimate than the standard.
        assert est1.yearlyEstimate.compareTo(est0.yearlyEstimate) > 0
        // Invalid input rejected.
        assert ServiceUtil.isError(dispatcher.runSync('resolveLeasePropertyTaxEstimate',
                [acquisitionCost: BigDecimal.ZERO, termMonths: 60L, userLogin: userLogin]))
    }

    // Estimate supplied to the L9 component on quote creation.
    void testEstimateSuppliedToComponent() {
        GenericValue userLogin = admin()
        String muni = newMunicipality(new BigDecimal('0.014'))
        String lessee = 'TEST_LSPTX_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'PTax component lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
                requestedTermMonths: 60L, userLogin: userLogin])
        ok(app)
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: app.leaseApplicationId,
                fixedAssetTypeId: 'EQUIPMENT', assetName: 'M', quantity: BigDecimal.ONE,
                estimatedAcquisitionCost: new BigDecimal('1000000.00'), supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        // Property tax component with no amount: estimated from the valuation x the municipal rate.
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: app.leaseApplicationId, termMonths: 60L,
                leaseStartDate: ts(LocalDate.of(2024, 6, 1)),
                components: [[componentTypeEnumId: 'LSCMP_PROP_TAX', taxClassEnumId: 'LSTAX_TAXABLE',
                              revenueTreatmentEnumId: 'LSCMP_REV_SEPARATE', municipalityGeoId: muni, usefulLifeYears: 6L]],
                userLogin: userLogin])
        ok(quote)
        GenericValue comp = from('LeaseQuoteComponent').where('leaseQuoteId', quote.leaseQuoteId, 'componentSeqId', '00001').queryOne()
        assert comp != null
        assert comp.componentTypeEnumId == 'LSCMP_PROP_TAX'
        assert comp.monthlyAmount.signum() > 0                                  // auto-estimated
        Map est = dispatcher.runSync('resolveLeasePropertyTaxEstimate', [acquisitionCost: new BigDecimal('1000000.00'),
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
        Map taxed = activeLease(userLogin, acquired, 60L, new BigDecimal('5000000.00'), muniTaxed, 6L)
        // A municipality whose single asset is below the exemption threshold.
        String muniExempt = newMunicipality(new BigDecimal('0.014'))
        Map exempt = activeLease(userLogin, acquired, 60L, new BigDecimal('1000000.00'), muniExempt, 20L)

        Map gen = dispatcher.runSync('generateLeasePropertyTaxDeclarations', [taxYear: (long) taxYear, userLogin: userLogin])
        ok(gen)

        GenericValue declTaxed = from('LeasePropertyTaxDecl').where('organizationPartyId', 'Company',
                'taxYear', (long) taxYear, 'municipalityGeoId', muniTaxed).queryOne()
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
        assert from('LeasePropertyTaxDeclAsset').where('leasePropertyTaxDeclId', declTaxed.leasePropertyTaxDeclId).queryCount() == 1

        GenericValue declExempt = from('LeasePropertyTaxDecl').where('organizationPartyId', 'Company',
                'taxYear', (long) taxYear, 'municipalityGeoId', muniExempt).queryOne()
        assert declExempt != null
        assert declExempt.belowThreshold == 'Y'                                 // below 免税点
        assert declExempt.taxableBase.signum() == 0
        assert declExempt.taxAmount.signum() == 0

        // Idempotent: re-running creates nothing new.
        Map rerun = dispatcher.runSync('generateLeasePropertyTaxDeclarations', [taxYear: (long) taxYear, userLogin: userLogin])
        ok(rerun)
        assert from('LeasePropertyTaxDecl').where('organizationPartyId', 'Company', 'taxYear', (long) taxYear,
                'municipalityGeoId', muniTaxed).queryCount() == 1

        // Payment of the taxed declaration: Dr property tax expense (601500) / Cr payable (224600), balanced.
        Map pay = dispatcher.runSync('recordLeasePropertyTaxPayment', [leasePropertyTaxDeclId: declTaxed.leasePropertyTaxDeclId,
                userLogin: userLogin])
        ok(pay)
        assert pay.paymentCreated == true
        Map<String, BigDecimal> sums = entrySums(pay.acctgTransId)
        assert sums['601500:D'].compareTo(declTaxed.taxAmount) == 0
        assert sums['224600:C'].compareTo(declTaxed.taxAmount) == 0
        assert from('LeasePropertyTaxDecl').where('leasePropertyTaxDeclId', declTaxed.leasePropertyTaxDeclId).queryOne().statusId == 'LEASE_PTAX_PAID'

        // Idempotent payment; a below-threshold declaration has nothing to pay.
        Map payAgain = dispatcher.runSync('recordLeasePropertyTaxPayment', [leasePropertyTaxDeclId: declTaxed.leasePropertyTaxDeclId,
                userLogin: userLogin])
        ok(payAgain)
        assert payAgain.paymentCreated == false
        assert ServiceUtil.isError(dispatcher.runSync('recordLeasePropertyTaxPayment',
                [leasePropertyTaxDeclId: declExempt.leasePropertyTaxDeclId, userLogin: userLogin]))
    }
}
