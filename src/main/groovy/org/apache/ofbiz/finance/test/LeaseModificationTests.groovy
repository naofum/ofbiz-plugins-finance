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
 * STEP L15: change of terms (条件変更). From the first unbilled installment the
 * confirmed schedule is rebuilt as a new revision recovering the same principal;
 * finance lease: Dr lease investment / Cr deferred lease profit by the change of the
 * interest equivalent; operating lease: the remaining depreciable amount is spread
 * over the new remaining months; lump-sum taxed leases cannot change the total.
 */
class LeaseModificationTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseModificationTests(String name) {
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

    private List<GenericValue> schedule(String agreementId, Long revision = null) {
        long rev = revision ?: LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', rev).orderBy('installmentNumber').queryList()
    }

    /** ACTIVE lease accepted on {@code start}; quote by calculation rate or by the product's rate factor. */
    private String activeLease(GenericValue userLogin, String productId, LocalDate start, long term, BigDecimal calcRate) {
        String lessee = 'TEST_LSMOD_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Modification test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: term, userLogin: userLogin])
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
        Map quoteInput = [leaseApplicationId: appId, termMonths: term, leaseStartDate: ts(start), userLogin: userLogin]
        if (calcRate != null) {
            quoteInput.calcInterestRate = calcRate
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

    // Finance lease, term extension: preview, new revision, same principal, kept interest, totals, GL, validation.
    void testFinanceTermExtension() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(3).minusDays(10)
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', start, 48L, new BigDecimal('0.03'))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        GenericValue before = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        long rev = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        List<GenericValue> old = schedule(agreementId)
        assert from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryCount() == 4

        // Validation.
        assert ServiceUtil.isError(dispatcher.runSync('calculateLeaseModification', [leaseAgreementId: agreementId, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('calculateLeaseModification', [leaseAgreementId: agreementId,
                newTermMonths: 61L, userLogin: userLogin]))                     // product max 60
        assert ServiceUtil.isError(dispatcher.runSync('calculateLeaseModification', [leaseAgreementId: agreementId,
                newTermMonths: 3L, userLogin: userLogin]))                      // before the first unbilled installment
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_TERMINATED', userLogin: userLogin]))

        Map preview = dispatcher.runSync('calculateLeaseModification', [leaseAgreementId: agreementId, newTermMonths: 60L,
                userLogin: userLogin])
        ok(preview)
        assert preview.effectiveInstallmentNumber == 5L
        assert preview.newMonthlyLeaseAmount < before.monthlyLeaseAmount
        assert preview.newCalcInterestRate.compareTo(new BigDecimal('0.03')) == 0
        assert preview.interestAdjustment.signum() > 0
        assert schedule(agreementId).size() == 48                               // nothing changed by the preview

        Map mod = dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId, newTermMonths: 60L,
                reason: 'Extension', userLogin: userLogin])
        ok(mod)
        assert mod.scheduleRevisionNumber == rev + 1
        List<GenericValue> rows = schedule(agreementId)
        assert rows.size() == 60
        (0..3).each { int i ->
            assert rows[i].leaseAmount.compareTo(old[i].leaseAmount) == 0
            assert rows[i].interestPortion.compareTo(old[i].interestPortion) == 0
            assert rows[i].dueDate == old[i].dueDate
        }
        // Same principal from installment 5 down to the residual; installment 5 keeps its (advance) interest.
        BigDecimal principal = rows.subList(4, 60).inject(BigDecimal.ZERO) { a, r -> a.add(r.principalPortion) }
        assert principal.compareTo(old[3].remainingPrincipal.subtract(before.residualValue)) == 0
        assert rows[59].remainingPrincipal.compareTo(before.residualValue) == 0
        assert rows[4].interestPortion.compareTo(old[4].interestPortion) == 0
        assert rows[5].leaseAmount.compareTo(mod.newMonthlyLeaseAmount) == 0
        assert rows[59].periodThruDate == ts(start.plusMonths(60).minusDays(1))

        GenericValue after = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert after.termMonths == 60L
        assert after.leaseEndDate == ts(start.plusMonths(60).minusDays(1))
        assert after.monthlyLeaseAmount.compareTo(mod.newMonthlyLeaseAmount) == 0
        BigDecimal delta = mod.newRemainingLeaseAmount.subtract(mod.oldRemainingLeaseAmount)
        assert delta.compareTo(mod.interestAdjustment) == 0                    // principal unchanged
        assert after.totalLeaseAmount.compareTo(before.totalLeaseAmount.add(delta)) == 0
        assert after.totalInterestAmount.compareTo(before.totalInterestAmount.add(mod.interestAdjustment)) == 0
        assert after.totalLeaseAmount.compareTo(rows.inject(BigDecimal.ZERO) { a, r -> a.add(r.leaseAmount) }) == 0
        Map<String, BigDecimal> sums = entrySums(mod.acctgTransId)
        assert sums['128500:D'].compareTo(mod.interestAdjustment) == 0
        assert sums['128700:C'].compareTo(mod.interestAdjustment) == 0
        GenericValue history = from('LeaseModification').where('leaseModificationId', mod.leaseModificationId).queryOne()
        assert history.oldTermMonths == 48L && history.newTermMonths == 60L
        assert history.fromRevisionNumber == rev && history.toRevisionNumber == rev + 1

        // The deferred profit release follows the new schedule (no error, incremental).
        ok(dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId, userLogin: userLogin]))
        Map bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(bal)
        assert bal.unbilledLeaseAmount.compareTo(mod.newRemainingLeaseAmount) == 0
    }

    // Finance lease, monthly amount change: the calculation rate is solved; a decrease reverses the entry.
    void testAmountChangeSolvesRate() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(3).minusDays(10), 48L,
                new BigDecimal('0.03'))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        GenericValue before = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        BigDecimal newAmount = before.monthlyLeaseAmount.subtract(new BigDecimal('100.00'))
        Map mod = dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId, newMonthlyLeaseAmount: newAmount,
                userLogin: userLogin])
        ok(mod)
        assert mod.newMonthlyLeaseAmount.compareTo(newAmount) == 0
        assert mod.newCalcInterestRate < new BigDecimal('0.03')
        assert mod.interestAdjustment.signum() < 0
        List<GenericValue> rows = schedule(agreementId)
        assert rows.size() == 48
        assert rows.subList(4, 48).every { it.leaseAmount.compareTo(newAmount) == 0 }
        assert rows[47].remainingPrincipal.compareTo(before.residualValue) == 0
        Map<String, BigDecimal> sums = entrySums(mod.acctgTransId)
        assert sums['128700:D'].compareTo(mod.interestAdjustment.negate()) == 0
        assert sums['128500:C'].compareTo(mod.interestAdjustment.negate()) == 0
        GenericValue after = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert after.calcInterestRate.compareTo(mod.newCalcInterestRate) == 0
        assert after.leaseEndDate == before.leaseEndDate
    }

    // Operating lease: no GL entry; depreciation after the change spreads the remainder over the new remaining months.
    void testOperatingTermChangeDepreciation() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(6).minusDays(10)
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_OPER', start, 60L, null)
        ok(dispatcher.runSync('depreciateLeaseAssets', [leaseAgreementId: agreementId,
                depreciationDate: ts(start.plusMonths(3).minusDays(1)), userLogin: userLogin]))
        assert from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).queryCount() == 3
        GenericValue before = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        BigDecimal depreciable = COST.subtract(before.residualValue)

        Map mod = dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId, newTermMonths: 48L,
                userLogin: userLogin])
        ok(mod)
        assert mod.acctgTransId == null
        GenericValue history = from('LeaseModification').where('leaseModificationId', mod.leaseModificationId).queryOne()
        assert history.depreciationBaseMonth == 3L
        assert schedule(agreementId).size() == 48

        ok(dispatcher.runSync('depreciateLeaseAssets', [leaseAgreementId: agreementId, userLogin: userLogin]))
        List<GenericValue> deps = from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).orderBy('periodNumber').queryList()
        assert deps.size() == 6
        BigDecimal cum3 = deps[2].cumulativeAmount
        assert cum3.compareTo(LeaseDepreciationServices.cumulative(depreciable, 3, 60, 2, RoundingMode.HALF_UP)) == 0
        BigDecimal cum6 = LeaseDepreciationServices.cumulativeFromBase(depreciable, cum3, 3, 6, 48, 2, RoundingMode.HALF_UP)
        assert deps[5].cumulativeAmount.compareTo(cum6) == 0
        assert deps[3].amount.compareTo(depreciable.subtract(cum3).divide(new BigDecimal('45'), 2, RoundingMode.HALF_UP)) == 0
        // Pure: the spread ends at the depreciable amount.
        assert LeaseDepreciationServices.cumulativeFromBase(depreciable, cum3, 3, 48, 48, 2, RoundingMode.HALF_UP).compareTo(depreciable) == 0
    }

    // Lump-sum taxed leases cannot change the total; re-leased leases cannot be modified.
    void testModificationRestrictions() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(3).minusDays(10), 48L,
                new BigDecimal('0.03'))
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        agreement.taxTimingEnumId = 'LEASE_TAX_AT_START'
        agreement.store()
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId,
                newTermMonths: 60L, userLogin: userLogin]))
        agreement.taxTimingEnumId = 'LEASE_TAX_INSTALL'
        agreement.store()

        delegator.create('LeaseReLease', [leaseAgreementId: agreementId, reLeaseSeqId: '00001', fromDate: agreement.leaseEndDate,
                thruDate: agreement.leaseEndDate, months: 12L, reLeaseAmount: BigDecimal.ONE, statusId: 'LSREL_CONTRACTED'])
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId,
                newTermMonths: 60L, userLogin: userLogin]))
        from('LeaseReLease').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00001').queryOne().remove()
        assert from('LeaseModification').where('leaseAgreementId', agreementId).queryCount() == 0
    }
}
