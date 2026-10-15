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
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.finance.lease.LeaseContractAdapter
import org.apache.ofbiz.finance.lease.LeaseTerminationServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L14: early termination (中途解約). Damages (規定損害金) = present value of the
 * unbilled lease payments at the calculation interest rate / 12, not taxable; the
 * finance lease investment is settled (deferred profit removed, damages credited to
 * the investment, difference = termination gain / loss); the operating lease asset
 * stays at its book value and the damages are termination income (404200); during a
 * re-lease no damages and the unearned fees are recognized. The agreement becomes
 * TERMINATED, billing stops, maintenance plans end, recovery tasks are created; the
 * return completes the agreement once everything is settled.
 */
class LeaseTerminationTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseTerminationTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay())
    }

    private static Timestamp today() {
        return UtilDateTime.getDayStart(UtilDateTime.nowTimestamp())
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

    private List<GenericValue> schedule(String agreementId) {
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision).orderBy('installmentNumber').queryList()
    }

    /** ACTIVE lease of {@code productId} accepted on {@code start}. */
    private String activeLease(GenericValue userLogin, String productId, LocalDate start, long term,
            BigDecimal residual = null, BigDecimal factor = null) {
        String lessee = 'TEST_LSTRM_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Termination test lessee'])
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

    // Pure calculation and the damages quote: PV of the unbilled payments at the calculation rate; validation.
    void testDamagesCalculation() {
        assert LeaseTerminationServices.monthsBetween(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 15)).compareTo(new BigDecimal('2')) == 0
        assert LeaseTerminationServices.monthsBetween(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 1, 15)).signum() == 0
        BigDecimal pv = LeaseTerminationServices.presentValue(new BigDecimal('1010'), new BigDecimal('0.01'), BigDecimal.ONE)
        assert pv.setScale(2, RoundingMode.HALF_UP).compareTo(new BigDecimal('1000.00')) == 0
        assert LeaseTerminationServices.presentValue(new BigDecimal('1000'), BigDecimal.ZERO, BigDecimal.TEN).compareTo(new BigDecimal('1000')) == 0

        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(6).minusDays(10), 60L)
        Map calc = dispatcher.runSync('calculateLeaseTerminationDamages', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(calc)
        Timestamp todayEnd = UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp())
        List<GenericValue> unbilled = schedule(agreementId).findAll { it.dueDate.after(todayEnd) }
        assert calc.unbilledInstallmentCount == (long) unbilled.size()
        BigDecimal unbilledAmount = unbilled.inject(BigDecimal.ZERO) { a, r -> a.add(r.leaseAmount) }
        assert calc.unbilledLeaseAmount.compareTo(unbilledAmount) == 0
        assert calc.damagesAmount.signum() > 0 && calc.damagesAmount.compareTo(unbilledAmount) < 0
        assert calc.discountAmount.compareTo(unbilledAmount.subtract(calc.damagesAmount)) == 0
        assert calc.residualIncluded == false
        // Close to the principal still to be recovered by the unbilled payments (same rate, interest method).
        BigDecimal principal = unbilled.inject(BigDecimal.ZERO) { a, r -> a.add(r.principalPortion) }
        assert calc.damagesAmount.subtract(principal).abs().compareTo(principal.multiply(new BigDecimal('0.03'))) < 0

        // Validation: not in the future, not before the start; the generic status service cannot terminate.
        assert ServiceUtil.isError(dispatcher.runSync('calculateLeaseTerminationDamages', [leaseAgreementId: agreementId,
                terminationDate: ts(LocalDate.now().plusDays(3)), userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId,
                terminationDate: ts(LocalDate.now().minusYears(2)), userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_TERMINATED', userLogin: userLogin]))
        assert from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne().statusId == 'LEASEAGR_ACTIVE'
    }

    // Finance lease: termination settles the investment, bills the damages (no tax), stops billing; recovery and completion.
    void testFinanceTermination() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(6).minusDays(10), 60L)
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        Map calc = dispatcher.runSync('calculateLeaseTerminationDamages', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(calc)

        Map term = dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId, reason: 'Business closed',
                userLogin: userLogin])
        ok(term)
        assert term.terminationCreated == true
        assert term.damagesAmount.compareTo(calc.damagesAmount) == 0
        GenericValue fresh = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert fresh.statusId == 'LEASEAGR_TERMINATED'
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne().statusId == 'FINCTR_ACTIVE'
        // Installments due through today are billed; the rest never will be.
        Timestamp todayEnd = UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp())
        List<GenericValue> rows = schedule(agreementId)
        long due = rows.count { !it.dueDate.after(todayEnd) }
        assert from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryCount() == due
        GenericValue next = rows.find { it.dueDate.after(todayEnd) }
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: next.installmentNumber, userLogin: userLogin]))

        // GL: Dr deferred profit (remaining) / Cr investment, gain or loss = damages + carried - net investment.
        GenericValue t = from('LeaseTermination').where('leaseAgreementId', agreementId).queryOne()
        assert t.residualCarried.signum() == 0                                  // residual 0
        assert t.gainLossAmount.compareTo(t.damagesAmount.add(t.residualCarried).subtract(t.netInvestment)) == 0
        Map<String, BigDecimal> settle = entrySums(t.acctgTransId)
        assert side(settle, 'D').compareTo(side(settle, 'C')) == 0
        assert settle['128700:D'].compareTo(t.remainingDeferredProfit) == 0
        assert t.remainingDeferredProfit.signum() > 0
        // Damages invoice: one item, no consumption tax, credits the investment (128500).
        List<GenericValue> items = from('InvoiceItem').where('invoiceId', t.invoiceId).queryList()
        assert items.size() == 1 && items[0].invoiceItemTypeId == 'LEASE_TERM_DAMAGES'
        Map<String, BigDecimal> inv = entrySums(from('AcctgTrans').where('invoiceId', t.invoiceId).queryFirst().acctgTransId)
        assert inv['128500:C'].compareTo(t.damagesAmount) == 0
        assert inv['224500:C'] == null
        // Idempotent.
        Map again = dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(again)
        assert again.terminationCreated == false

        // Recovery task; delinquency includes the damages; the monthly batch skips the agreement.
        assert from('FinanceContractWorkEffort').where('financeContractId', agreement.financeContractId,
                'financeWorkTypeEnumId', 'FINWE_ASSET_RETURN').queryCount() == 1
        Map row = LeaseContractAdapter.overdueInstallments(delegator, fresh, todayEnd).find { it.invoiceId == t.invoiceId }
        assert row != null && row.principalAmount.compareTo(t.damagesAmount) == 0
        assert LeaseContractAdapter.overdueInstallments(delegator, fresh, ts(LocalDate.now().plusYears(10)))
                .every { it.invoiceId != null }                                 // unbilled installments are not overdue
        assert ServiceUtil.isError(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId,
                userLogin: userLogin]))

        // Pay everything; the outstanding principal is the unpaid damages + billed principal.
        Map bal = balance(agreementId, userLogin)
        assert bal.terminationDamagesAmount.compareTo(t.damagesAmount) == 0
        assert bal.unbilledLeaseAmount.signum() == 0 && bal.deferredProfitBalance.signum() == 0
        assert bal.outstandingPrincipal.signum() > 0
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))
        bal = balance(agreementId, userLogin)
        assert bal.unpaidBilledAmount.signum() == 0
        assert bal.outstandingPrincipal.signum() == 0
        assert bal.collectedTerminationAmount.compareTo(t.damagesAmount) == 0

        // Recovery completes the agreement; the asset carried at 0 is sold at a gain.
        Map ret = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001', userLogin: userLogin])
        ok(ret)
        assert ret.agreementCompleted == true
        assert from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne().statusId == 'LEASEAGR_COMPLETED'
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne().statusId == 'FINCTR_COMPLETED'
        GenericValue settlement = from('LeaseAssetSettlement').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert settlement.residualValue.signum() == 0
        Map disp = dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SALE', proceedsAmount: new BigDecimal('1000.00'), userLogin: userLogin])
        ok(disp)
        Map<String, BigDecimal> ds = entrySums(disp.acctgTransId)
        assert ds['128900:D'].compareTo(new BigDecimal('1000.00')) == 0
        assert ds['403700:C'].compareTo(new BigDecimal('1000.00')) == 0
    }

    // Operating lease: damages are termination income; depreciation through the termination; maintenance plans end.
    void testOperatingTermination() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_OPER', LocalDate.now().minusMonths(3).minusDays(10), 60L)
        Map plan = dispatcher.runSync('createLeaseMaintPlan', [leaseAgreementId: agreementId, assetSeqId: '00001',
                productMaintTypeId: 'LEASE_INSPECTION', intervalMonths: 1L, providerPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin])
        ok(plan)
        ok(dispatcher.runSync('runLeaseMaintScheduleBatch', [leaseAgreementId: agreementId, monthsAhead: 3L, userLogin: userLogin]))
        assert from('LeaseMaintRecord').where('leaseAgreementId', agreementId, 'statusId', 'LSMNT_SCHEDULED').queryCount() > 0

        Map term = dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(term)
        GenericValue t = from('LeaseTermination').where('leaseAgreementId', agreementId).queryOne()
        assert t.damagesAmount.signum() > 0
        assert t.gainLossAmount.compareTo(t.damagesAmount) == 0
        assert t.acctgTransId == null                                           // no investment to settle
        Map<String, BigDecimal> inv = entrySums(from('AcctgTrans').where('invoiceId', t.invoiceId).queryFirst().acctgTransId)
        assert inv['404200:C'].compareTo(t.damagesAmount) == 0
        // Depreciation: the three lease months ended before today.
        assert from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).queryCount() == 3
        // Maintenance: the plan ended, future scheduled maintenance cancelled.
        assert from('LeaseMaintPlan').where('leaseAgreementId', agreementId, 'maintPlanSeqId', plan.maintPlanSeqId).queryOne().thruDate != null
        assert from('LeaseMaintRecord').where('leaseAgreementId', agreementId, 'statusId', 'LSMNT_SCHEDULED').queryList()
                .every { !it.scheduledDate.after(UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp())) }
        // Delinquency puts the operating damages in the revenue (interest) bucket.
        GenericValue fresh = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        Map row = LeaseContractAdapter.overdueInstallments(delegator, fresh, UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp()))
                .find { it.invoiceId == t.invoiceId }
        assert row.principalAmount.signum() == 0 && row.interestAmount.compareTo(t.damagesAmount) == 0

        Map bal = balance(agreementId, userLogin)
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))
        Map ret = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001', userLogin: userLogin])
        ok(ret)
        assert ret.agreementCompleted == true
        GenericValue settlement = from('LeaseAssetSettlement').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        bal = balance(agreementId, userLogin)
        assert settlement.accumulatedDepreciation.compareTo(bal.accumulatedDepreciation) == 0
        assert settlement.residualValue.compareTo(COST.subtract(bal.accumulatedDepreciation)) == 0
    }

    // Termination during a re-lease: no damages, the unearned fee is recognized, contracted periods are cancelled.
    void testTerminationDuringReLease() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_OPER', LocalDate.now().minusMonths(13).minusDays(10), 12L,
                new BigDecimal('850000.00'), new BigDecimal('0.016'))
        Map r1 = dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(r1)
        ok(dispatcher.runSync('createLeaseReLease', [leaseAgreementId: agreementId, userLogin: userLogin]))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        GenericValue rel = from('LeaseReLease').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00001').queryOne()
        assert rel.statusId == 'LSREL_ACTIVE'
        BigDecimal before = rel.recognizedAmount
        assert before.signum() > 0 && before.compareTo(rel.reLeaseAmount) < 0

        Map term = dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(term)
        GenericValue t = from('LeaseTermination').where('leaseAgreementId', agreementId).queryOne()
        assert t.damagesAmount.signum() == 0
        assert t.invoiceId == null
        assert t.reLeaseRecognizedAmount.compareTo(rel.reLeaseAmount.subtract(before)) == 0
        rel.refresh()
        assert rel.statusId == 'LSREL_COMPLETED'
        assert rel.recognizedAmount.compareTo(rel.reLeaseAmount) == 0
        assert from('LeaseReLease').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00002').queryOne().statusId == 'LSREL_CANCELLED'
        GenericValue lastRec = from('LeaseReLeaseRecognition').where('leaseAgreementId', agreementId, 'reLeaseSeqId', '00001')
                .orderBy('-monthNumber').queryFirst()
        Map<String, BigDecimal> sums = entrySums(lastRec.acctgTransId)
        assert sums['224700:D'].compareTo(t.reLeaseRecognizedAmount) == 0
        assert sums['404100:C'].compareTo(t.reLeaseRecognizedAmount) == 0
        Map bal = balance(agreementId, userLogin)
        assert bal.unearnedReLeaseAmount.signum() == 0
    }
}
