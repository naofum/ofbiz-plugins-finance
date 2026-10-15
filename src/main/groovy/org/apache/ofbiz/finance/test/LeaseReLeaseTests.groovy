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
import org.apache.ofbiz.finance.lease.LeaseContractAdapter
import org.apache.ofbiz.finance.lease.LeaseReLeaseServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L13: re-lease (再リース). Renewal periods on the same agreement: the fee is
 * billed once in advance to unearned re-lease fees (224700) and recognized monthly as
 * re-lease revenue (404100); the residual of a finance lease moves to leased assets
 * (165500) at the first re-lease; the book value is depreciated to zero over the
 * re-lease (503800 / 165900); the return is allowed after the last re-lease end.
 *
 * Uses the USD products TEST_LEASEPROD_USD (finance, no transfer) and
 * TEST_LEASEPROD_OPER (operating), 12-month leases with a quote rate factor high
 * enough to recover the cost less the residual, and a new lessee per test.
 */
class LeaseReLeaseTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseReLeaseTests(String name) {
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

    private Map balance(String agreementId, GenericValue userLogin) {
        Map bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(bal)
        return bal
    }

    /** ACTIVE 12-month lease of {@code productId} accepted on {@code start}. */
    private String activeLease(GenericValue userLogin, String productId, LocalDate start, BigDecimal residual, BigDecimal factor) {
        String lessee = 'TEST_LSREL_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Re-lease test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: 12L, userLogin: userLogin])
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
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: 12L, leaseStartDate: ts(start),
                residualValue: residual, monthlyRateFactor: factor, userLogin: userLogin])
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

    // Registration: next period after the previous end, default fee = monthly x factor (1.0) x months / 12; cancel rules.
    void testReLeaseRegistration() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(6),
                new BigDecimal('120000.00'), new BigDecimal('0.08'))
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, months: 0L, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId,
                reLeaseAmount: new BigDecimal('-1'), userLogin: userLogin]))

        Map r1 = dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(r1)
        assert r1.reLeaseSeqId == '00001'
        LocalDate end = agreement.leaseEndDate.toLocalDateTime().toLocalDate()
        assert r1.fromDate == ts(end.plusDays(1))
        assert r1.thruDate == ts(end.plusDays(1).plusMonths(12).minusDays(1))
        assert r1.reLeaseAmount.compareTo(agreement.monthlyLeaseAmount) == 0  // 1 month per year
        Map r2 = dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, months: 6L,
                reLeaseAmount: new BigDecimal('30000'), userLogin: userLogin])
        ok(r2)
        assert r2.fromDate == ts(end.plusDays(1).plusMonths(12))
        assert LeaseReLeaseServices.effectiveEndDate(delegator, agreement) == r2.thruDate

        // Only the last contracted period can be cancelled.
        assert ServiceUtil.isError(dispatcher.runSync('cancelLeaseReLease', [leaseAgreementId: agreementId,
                reLeaseSeqId: '00001', userLogin: userLogin]))
        ok(dispatcher.runSync('cancelLeaseReLease', [leaseAgreementId: agreementId, reLeaseSeqId: '00002', userLogin: userLogin]))
        assert LeaseReLeaseServices.effectiveEndDate(delegator, agreement) == r1.thruDate
        // A re-lease cannot be returned before its end.
        assert ServiceUtil.isError(dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                returnDate: ts(end.plusMonths(1)), userLogin: userLogin]))
    }

    // Finance lease: start (reclassification + advance invoice), monthly recognition and depreciation, delinquency, payment.
    void testReLeaseBillingAndRecognition() {
        GenericValue userLogin = admin()
        BigDecimal residual = new BigDecimal('120000.00')
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(13), residual,
                new BigDecimal('0.08'))
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        Map r1 = dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(r1)
        BigDecimal fee = r1.reLeaseAmount

        Map run = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(run)
        GenericValue rel = from('LeaseReLease').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00001').queryOne()
        assert rel.statusId == 'LSREL_ACTIVE'
        assert rel.invoiceId != null
        // Invoice: Cr 224700 fee, Cr 224500 tax (10%), due at the period start.
        GenericValue invoice = from('Invoice').where('invoiceId', rel.invoiceId).queryOne()
        assert invoice.dueDate == rel.fromDate
        Map<String, BigDecimal> inv = entrySums(from('AcctgTrans').where('invoiceId', rel.invoiceId).queryFirst().acctgTransId)
        assert side(inv, 'D').compareTo(side(inv, 'C')) == 0
        assert inv['224700:C'].compareTo(fee) == 0
        assert inv['224500:C'].compareTo(rel.taxAmount) == 0
        assert rel.taxAmount.compareTo(fee.multiply(new BigDecimal('0.10')).setScale(2, RoundingMode.HALF_UP)) == 0
        // Residual reclassification: Dr 165500 / Cr 128500.
        Map<String, BigDecimal> reclass = entrySums(rel.reclassAcctgTransId)
        assert reclass['165500:D'].compareTo(residual) == 0
        assert reclass['128500:C'].compareTo(residual) == 0
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert asset.reclassifiedResidual.compareTo(residual) == 0

        // Month 1 of the re-lease has ended: fee / 12 recognized, residual / 12 depreciated.
        List<GenericValue> recs = from('LeaseReLeaseRecognition').where('leaseAgreementId', agreementId).queryList()
        assert recs.size() == 1
        BigDecimal month = fee.divide(new BigDecimal('12'), 2, RoundingMode.HALF_UP)
        assert recs[0].amount.compareTo(month) == 0
        Map<String, BigDecimal> recSums = entrySums(recs[0].acctgTransId)
        assert recSums['224700:D'].compareTo(month) == 0
        assert recSums['404100:C'].compareTo(month) == 0
        GenericValue dep = from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00001').queryFirst()
        assert dep.periodNumber == 13L
        assert dep.amount.compareTo(new BigDecimal('10000.00')) == 0
        assert dep.bookValue.compareTo(new BigDecimal('110000.00')) == 0
        Map<String, BigDecimal> depSums = entrySums(dep.acctgTransId)
        assert depSums['503800:D'].compareTo(new BigDecimal('10000.00')) == 0
        assert depSums['165900:C'].compareTo(new BigDecimal('10000.00')) == 0

        // Idempotent.
        Map again = dispatcher.runSync('processLeaseReLeases', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(again)
        assert again.startedCount == 0L && again.recognizedCount == 0L
        assert from('LeaseReLeaseRecognition').where('leaseAgreementId', agreementId).queryCount() == 1

        // Expiry batch: no return task while the re-lease runs.
        ok(dispatcher.runSync('runLeaseExpiryBatch', [userLogin: userLogin]))
        assert from('FinanceContractWorkEffort').where('financeContractId', agreement.financeContractId,
                'financeWorkTypeEnumId', 'FINWE_ASSET_RETURN').queryCount() == 0

        // Delinquency includes the unpaid re-lease invoice (fee as revenue, no principal).
        GenericValue fresh = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        Map row = LeaseContractAdapter.overdueInstallments(delegator, fresh, ts(LocalDate.now().plusDays(1)))
                .find { it.invoiceId == rel.invoiceId }
        assert row != null
        assert row.principalAmount.signum() == 0
        assert row.interestAmount.compareTo(fee) == 0
        assert row.taxAmount.compareTo(rel.taxAmount) == 0

        // Balance and payment of everything billed.
        Map bal = balance(agreementId, userLogin)
        assert bal.billedReLeaseAmount.compareTo(fee) == 0
        assert bal.unearnedReLeaseAmount.compareTo(fee.subtract(month)) == 0
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))
        bal = balance(agreementId, userLogin)
        assert bal.unpaidBilledAmount.signum() == 0
        assert bal.collectedReLeaseAmount.compareTo(fee) == 0
        assert bal.collectedReLeaseTaxAmount.compareTo(rel.taxAmount) == 0
        assert from('Invoice').where('invoiceId', rel.invoiceId).queryOne().statusId == 'INVOICE_PAID'
    }

    // Operating lease: re-lease ended; book value depreciated to 0; return at the last re-lease end; disposal.
    void testOperatingReLeaseReturnAndDisposal() {
        GenericValue userLogin = admin()
        BigDecimal residual = new BigDecimal('850000.00')
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_OPER', LocalDate.now().minusMonths(25), residual,
                new BigDecimal('0.016'))
        ok(dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, userLogin: userLogin]))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        GenericValue rel = from('LeaseReLease').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00001').queryOne()
        assert rel.statusId == 'LSREL_COMPLETED'
        assert rel.recognizedAmount.compareTo(rel.reLeaseAmount) == 0
        assert rel.reclassAcctgTransId == null                                  // operating: already a leased asset
        assert from('LeaseReLeaseRecognition').where('leaseAgreementId', agreementId).queryCount() == 12
        assert from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).queryCount() == 24
        Map bal = balance(agreementId, userLogin)
        assert bal.leasedAssetBookValue.signum() == 0                           // residual depreciated over the re-lease
        assert bal.unearnedReLeaseAmount.signum() == 0
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))

        Map ret = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001', userLogin: userLogin])
        ok(ret)
        assert ret.agreementCompleted == true
        GenericValue settlement = from('LeaseAssetSettlement').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert settlement.residualValue.signum() == 0
        assert settlement.accumulatedDepreciation.compareTo(COST) == 0

        BigDecimal proceeds = new BigDecimal('50000.00')
        Map disp = dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SALE', proceedsAmount: proceeds, userLogin: userLogin])
        ok(disp)
        Map<String, BigDecimal> sums = entrySums(disp.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['165900:D'].compareTo(COST) == 0
        assert sums['165500:C'].compareTo(COST) == 0
        assert sums['403700:C'].compareTo(proceeds) == 0
    }

    // Finance lease: after a completed re-lease the reclassified residual is returned at book value 0.
    void testFinanceReLeaseReturn() {
        GenericValue userLogin = admin()
        BigDecimal residual = new BigDecimal('120000.00')
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(25), residual,
                new BigDecimal('0.08'))
        ok(dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, userLogin: userLogin]))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        Map bal = balance(agreementId, userLogin)
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))
        Map ret = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001', userLogin: userLogin])
        ok(ret)
        assert ret.agreementCompleted == true
        GenericValue settlement = from('LeaseAssetSettlement').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert settlement.acquisitionCost.compareTo(residual) == 0
        assert settlement.accumulatedDepreciation.compareTo(residual) == 0
        assert settlement.residualValue.signum() == 0
        assert from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne().statusId == 'LEASEAGR_COMPLETED'
        // Scrap: Dr 165900 / Cr 165500 (residual), no gain or loss.
        Map disp = dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SCRAP', userLogin: userLogin])
        ok(disp)
        Map<String, BigDecimal> sums = entrySums(disp.acctgTransId)
        assert sums['165900:D'].compareTo(residual) == 0
        assert sums['165500:C'].compareTo(residual) == 0
        assert sums['403700:C'] == null && sums['503700:D'] == null
    }
}
