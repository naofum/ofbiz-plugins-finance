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
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.finance.lease.LeaseDepreciationServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L12: operating lease (オペレーティングリース). The leased asset is the lessor's
 * fixed asset (賃貸資産 165500) depreciated monthly with the lease-term straight-line
 * method to the residual value (Dr 503800 / Cr 165900); the lease payment is lease
 * revenue at billing (Cr 404000); consumption tax per installment; no sales and no
 * deferred profit; at expiry the returned asset is disposed at its book value.
 *
 * Uses the USD operating lease product TEST_LEASEPROD_OPER (residual 20%) and a new
 * lessee per test.
 */
class LeaseOperatingTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseOperatingTests(String name) {
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

    private static BigDecimal side(Map<String, BigDecimal> sums, String flag) {
        return sums.findAll { it.key.endsWith(':' + flag) }.values().inject(BigDecimal.ZERO) { a, b -> a.add(b) }
    }

    /** ACTIVE operating lease accepted on {@code start}; optional quote residual / rate factor. */
    private String activeLease(GenericValue userLogin, LocalDate start, long termMonths, BigDecimal residual = null,
            BigDecimal factor = null) {
        String lessee = 'TEST_LSOPR_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Operating lease test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_OPER', applicantPartyId: lessee,
                requestedTermMonths: termMonths, userLogin: userLogin])
        ok(app)
        String appId = app.leaseApplicationId
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: appId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Machine', quantity: BigDecimal.ONE, estimatedAcquisitionCost: COST,
                supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: COST, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quoteInput = [leaseApplicationId: appId, termMonths: termMonths, leaseStartDate: ts(start), userLogin: userLogin]
        if (residual != null) {
            quoteInput.residualValue = residual
        }
        if (factor != null) {
            quoteInput.monthlyRateFactor = factor
        }
        Map quote = dispatcher.runSync('createLeaseQuote', quoteInput)
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreement.leaseAgreementId, assetSeqId: '00001',
                purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreement.leaseAgreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        return agreement.leaseAgreementId
    }

    // Product rules: operating requires LEASE_REV_OPERATING (default), finance cannot use it, no lump-sum tax.
    void testOperatingProductRules() {
        GenericValue userLogin = admin()
        String oper = 'TEST_OPRP_' + delegator.getNextSeqId('Product')
        delegator.create('Product', [productId: oper, productTypeId: 'SERVICE', internalName: 'Operating rule test'])
        delegator.create('FinancialProduct', [productId: oper, financialProductTypeId: 'LEASE_OPERATING', currencyUomId: 'USD'])
        assert ServiceUtil.isError(dispatcher.runSync('createFinancialProductLease', [productId: oper,
                defaultMonthlyRateFactor: new BigDecimal('0.02'), revenueMethodEnumId: 'LEASE_REV_AT_START', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('createFinancialProductLease', [productId: oper,
                defaultMonthlyRateFactor: new BigDecimal('0.02'), taxTimingEnumId: 'LEASE_TAX_AT_START', userLogin: userLogin]))
        ok(dispatcher.runSync('createFinancialProductLease', [productId: oper, defaultMonthlyRateFactor: new BigDecimal('0.02'),
                residualValueRate: new BigDecimal('0.1'), userLogin: userLogin]))
        GenericValue terms = from('FinancialProductLease').where('productId', oper).queryOne()
        assert terms.leaseClassEnumId == 'LEASE_CLS_OPERATING'
        assert terms.revenueMethodEnumId == 'LEASE_REV_OPERATING'               // derived default

        String fin = 'TEST_OPRF_' + delegator.getNextSeqId('Product')
        delegator.create('Product', [productId: fin, productTypeId: 'SERVICE', internalName: 'Finance rule test'])
        delegator.create('FinancialProduct', [productId: fin, financialProductTypeId: 'LEASE_FIN_NONTRANS', currencyUomId: 'USD'])
        assert ServiceUtil.isError(dispatcher.runSync('createFinancialProductLease', [productId: fin,
                defaultMonthlyRateFactor: new BigDecimal('0.02'), revenueMethodEnumId: 'LEASE_REV_OPERATING', userLogin: userLogin]))
    }

    // Start: Dr leased asset / Cr suspense (no sales); billing: Cr lease revenue; tax per installment; no deferred profit.
    void testOperatingStartAndBilling() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, LocalDate.now().minusMonths(1), 60L)
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert agreement.leaseClassEnumId == 'LEASE_CLS_OPERATING'
        assert agreement.revenueMethodEnumId == 'LEASE_REV_OPERATING'
        assert agreement.taxTimingEnumId == 'LEASE_TAX_INSTALL'
        BigDecimal residual = agreement.residualValue
        assert residual.compareTo(new BigDecimal('200000.00')) == 0

        GenericValue acceptance = from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne()
        Map<String, BigDecimal> start = entrySums(acceptance.startAcctgTransId)
        assert start['165500:D'].compareTo(COST) == 0
        assert start['147500:C'].compareTo(COST) == 0
        assert start['403500:C'] == null && start['503500:D'] == null && start['128700:C'] == null
        assert acceptance.startTaxAcctgTransId == null

        // FixedAsset: salvage value = residual, expected end of life = lease end, depreciation 0.
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        GenericValue fa = from('FixedAsset').where('fixedAssetId', asset.fixedAssetId).queryOne()
        assert fa.salvageValue.compareTo(residual) == 0
        assert fa.expectedEndOfLife.toString() == agreement.leaseEndDate.toString().substring(0, 10)
        assert fa.depreciation.signum() == 0

        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        GenericValue row1 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision, 'installmentNumber', 1L).queryOne()
        Map bill = dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId, installmentNumber: 1L,
                userLogin: userLogin])
        ok(bill)
        GenericValue trans = from('AcctgTrans').where('invoiceId', bill.invoiceId).queryFirst()
        Map<String, BigDecimal> sums = entrySums(trans.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['404000:C'].compareTo(row1.leaseAmount) == 0              // lease revenue at billing
        assert sums['128500:C'] == null && sums['128600:C'] == null
        assert sums['224500:C'].compareTo(row1.taxAmount) == 0
        assert row1.taxAmount.signum() > 0

        // No deferred profit release.
        Map rec = dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(rec)
        assert rec.createdCount == 0L
        assert from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).queryCount() == 0

        // Pay the invoice: no principal recovery; collected = revenue + tax.
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bill.billedAmount, userLogin: userLogin]))
        Map bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(bal)
        assert bal.collectedPrincipalAmount.signum() == 0
        assert bal.collectedInterestAmount.compareTo(row1.leaseAmount) == 0
        assert bal.outstandingPrincipal.signum() == 0
        assert bal.deferredProfitBalance.signum() == 0
        assert bal.leasedAssetCost.compareTo(COST) == 0
    }

    // Depreciation: lease-term straight-line to the residual, monthly via the batch, idempotent, FixedAsset updated.
    void testDepreciation() {
        // Pure calculation: equal months, the last month takes the remainder.
        BigDecimal dep = new BigDecimal('800000.00')
        assert LeaseDepreciationServices.cumulative(dep, 1, 60, 2, RoundingMode.HALF_UP).compareTo(new BigDecimal('13333.33')) == 0
        assert LeaseDepreciationServices.cumulative(dep, 59, 60, 2, RoundingMode.HALF_UP).compareTo(new BigDecimal('786666.67')) == 0
        assert LeaseDepreciationServices.cumulative(dep, 60, 60, 2, RoundingMode.HALF_UP).compareTo(dep) == 0

        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(3)
        String agreementId = activeLease(userLogin, start, 60L)
        // Months 1..3 end on or before yesterday: three depreciations (with the billing).
        Map run = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(run)
        assert run.createdDepreciationCount == 3L
        assert run.createdRecognitionCount == 0L
        List<GenericValue> rows = from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).orderBy('periodNumber').queryList()
        assert rows.size() == 3
        assert rows[0].fromDate == ts(start)
        assert rows[0].thruDate == ts(start.plusMonths(1).minusDays(1))
        BigDecimal cum3 = LeaseDepreciationServices.cumulative(dep, 3, 60, 2, RoundingMode.HALF_UP)
        assert rows[2].cumulativeAmount.compareTo(cum3) == 0
        assert rows[2].bookValue.compareTo(COST.subtract(cum3)) == 0
        assert rows.inject(BigDecimal.ZERO) { a, r -> a.add(r.amount) }.compareTo(cum3) == 0
        Map<String, BigDecimal> sums = entrySums(rows[0].acctgTransId)
        assert sums['503800:D'].compareTo(rows[0].amount) == 0
        assert sums['165900:C'].compareTo(rows[0].amount) == 0
        GenericValue fa = from('FixedAsset').where('fixedAssetId', rows[0].fixedAssetId).queryOne()
        assert fa.depreciation.compareTo(cum3) == 0

        // Idempotent.
        Map again = dispatcher.runSync('depreciateLeaseAssets', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(again)
        assert again.createdCount == 0L
        Map bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(bal)
        assert bal.accumulatedDepreciation.compareTo(cum3) == 0
        assert bal.leasedAssetBookValue.compareTo(COST.subtract(cum3)) == 0

        // A finance lease is not depreciated by the lessor.
        GenericValue finance = from('LeaseAgreement').where('leaseClassEnumId', 'LEASE_CLS_FIN_NONTR', 'statusId', 'LEASEAGR_ACTIVE').queryFirst()
        if (finance != null) {
            assert ServiceUtil.isError(dispatcher.runSync('depreciateLeaseAssets', [leaseAgreementId: finance.leaseAgreementId,
                    userLogin: userLogin]))
        }
    }

    // Expiry: the returned asset is at its residual book value; disposal removes cost and accumulated depreciation.
    void testExpiryReturnAndDisposal() {
        GenericValue userLogin = admin()
        // 12 months, residual 85%, factor 0.016 (12 x 0.016 = 0.192 >= 0.15 recovered); ended a month ago.
        BigDecimal residual = new BigDecimal('850000.00')
        String agreementId = activeLease(userLogin, LocalDate.now().minusMonths(13), 12L, residual, new BigDecimal('0.016'))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        assert from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).queryCount() == 12
        Map bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(bal)
        assert bal.leasedAssetBookValue.compareTo(residual) == 0              // depreciated down to the residual
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))

        // No ownership transfer for an operating lease.
        assert ServiceUtil.isError(dispatcher.runSync('transferLeaseOwnership', [leaseAgreementId: agreementId, userLogin: userLogin]))

        Map ret = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001', userLogin: userLogin])
        ok(ret)
        assert ret.agreementCompleted == true
        GenericValue settlement = from('LeaseAssetSettlement').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert settlement.residualValue.compareTo(residual) == 0              // book value
        assert settlement.acquisitionCost.compareTo(COST) == 0
        assert settlement.accumulatedDepreciation.compareTo(COST.subtract(residual)) == 0

        // Sale above book value: Dr 128900 proceeds, Dr 165900 accumulated, Cr 165500 cost, Cr 403700 gain.
        BigDecimal proceeds = new BigDecimal('900000.00')
        Map disp = dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SALE', proceedsAmount: proceeds, userLogin: userLogin])
        ok(disp)
        assert disp.gainLossAmount.compareTo(new BigDecimal('50000.00')) == 0
        Map<String, BigDecimal> sums = entrySums(disp.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['128900:D'].compareTo(proceeds) == 0
        assert sums['165900:D'].compareTo(COST.subtract(residual)) == 0
        assert sums['165500:C'].compareTo(COST) == 0
        assert sums['403700:C'].compareTo(new BigDecimal('50000.00')) == 0
    }

    // Public charges: the lessor-owned operating lease asset is declared (償却資産税).
    void testOperatingAssetDeclared() {
        GenericValue userLogin = admin()
        int taxYear = LocalDate.now().getYear()
        String muni = 'JPTO-' + delegator.getNextSeqId('Geo')
        muni = muni.length() > 20 ? muni.substring(0, 20) : muni
        delegator.create('Geo', [geoId: muni, geoTypeId: 'MUNICIPALITY', geoName: 'Operating test municipality'])
        String agreementId = activeLease(userLogin, LocalDate.of(taxYear - 2, 6, 1), 60L)
        ok(dispatcher.runSync('setLeaseAssetPublicChargeAttributes', [leaseAgreementId: agreementId, assetSeqId: '00001',
                municipalityGeoId: muni, usefulLifeYears: 6L, userLogin: userLogin]))
        ok(dispatcher.runSync('generateLeasePublicChargeDeclarations', [taxYear: (long) taxYear,
                publicChargeTypeId: 'LSPC_DEPR_ASSET', userLogin: userLogin]))
        GenericValue decl = from('LeasePublicChargeDecl').where('organizationPartyId', 'Company', 'publicChargeTypeId', 'LSPC_DEPR_ASSET',
                'taxYear', (long) taxYear, 'jurisdictionGeoId', muni).queryOne()
        assert decl != null
        assert decl.assetCount == 1L
        assert from('LeasePublicChargeDeclAsset').where('leasePublicChargeDeclId', decl.leasePublicChargeDeclId,
                'leaseAgreementId', agreementId).queryCount() == 1
    }
}
