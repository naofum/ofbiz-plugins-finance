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
import org.apache.ofbiz.finance.lease.LeaseContractAdapter
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L15d: asset split and contract split (物件分割・契約分割), SPEC_LEASE.md 20.
 *
 * USD finance lease product TEST_LEASEPROD_USD and operating lease product TEST_LEASEPROD_OPER
 * (payments in advance), a new lessee per test.
 */
class LeaseSplitTests extends OFBizTestCase {

    LeaseSplitTests(String name) {
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

    /** ACTIVE lease accepted on {@code start} with the given asset lines ([name, quantity, cost]). */
    private String activeLease(GenericValue userLogin, String productId, LocalDate start, long termMonths,
            List<List> assets, BigDecimal residual = null) {
        String lessee = 'TEST_LSSPL_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease split test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: termMonths, userLogin: userLogin])
        ok(app)
        String appId = app.leaseApplicationId
        BigDecimal total = BigDecimal.ZERO
        assets.each { List a ->
            ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: appId, fixedAssetTypeId: 'EQUIPMENT',
                    assetName: a[0], quantity: new BigDecimal(a[1].toString()), estimatedAcquisitionCost: new BigDecimal(a[2].toString()),
                    supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
            total = total.add(new BigDecimal(a[2].toString()))
        }
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: total, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quoteInput = [leaseApplicationId: appId, termMonths: termMonths, leaseStartDate: ts(start),
                calcInterestRate: new BigDecimal('0.030000'), userLogin: userLogin]
        if (residual != null) {
            quoteInput.residualValue = residual
        }
        Map quote = dispatcher.runSync('createLeaseQuote', quoteInput)
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        String agreementId = agreement.leaseAgreementId
        from('LeaseAgreementAsset').where('leaseAgreementId', agreementId).orderBy('assetSeqId').queryList().each { GenericValue a ->
            ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: a.assetSeqId,
                    purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        }
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        return agreementId
    }

    private GenericValue agreement(String id) {
        return from('LeaseAgreement').where('leaseAgreementId', id).queryOne()
    }

    private List<GenericValue> currentSchedule(String agreementId) {
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision).orderBy('installmentNumber').queryList()
    }

    private Map balance(String agreementId, GenericValue userLogin) {
        Map result = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(result)
        return result
    }

    private static BigDecimal sum(List<GenericValue> rows, String field) {
        return rows.inject(BigDecimal.ZERO) { BigDecimal a, GenericValue r -> a.add(r.getBigDecimal(field) ?: BigDecimal.ZERO) }
    }

    /** Sum of the entries of a transaction by "glAccountId:D|C". */
    private Map<String, BigDecimal> entrySums(String acctgTransId) {
        Map<String, BigDecimal> sums = [:]
        from('AcctgTransEntry').where('acctgTransId', acctgTransId).queryList().each { GenericValue e ->
            String key = e.glAccountId + ':' + e.debitCreditFlag
            sums[key] = (sums[key] ?: BigDecimal.ZERO).add(e.getBigDecimal('amount'))
        }
        return sums
    }

    // Asset split: cost, FixedAsset and history by quantity; then the split line moves to its own agreement.
    void testAssetSplitThenContractSplit() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1)
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', start, 24L, [['Printers', 3, '900000.00']])
        GenericValue line = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        String fixedAssetId = line.fixedAssetId
        // The quantity of the new lines must be less than the line quantity.
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreementAsset', [leaseAgreementId: agreementId,
                assetSeqId: '00001', splitQuantity: new BigDecimal('3'), userLogin: userLogin]))
        Map split = dispatcher.runSync('splitLeaseAgreementAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                splitQuantity: BigDecimal.ONE, userLogin: userLogin])
        ok(split)
        assert split.newAssetSeqIds == ['00002']
        line.refresh()
        GenericValue newLine = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00002').queryOne()
        assert line.quantity.compareTo(new BigDecimal('2')) == 0
        assert line.acquisitionCost.compareTo(new BigDecimal('600000.00')) == 0
        assert newLine.quantity.compareTo(BigDecimal.ONE) == 0
        assert newLine.acquisitionCost.compareTo(new BigDecimal('300000.00')) == 0
        assert newLine.assetStatusId == 'LSAST_LEASED'
        assert newLine.sourceAssetSeqId == '00001'
        assert newLine.fixedAssetId != null && newLine.fixedAssetId != fixedAssetId
        assert from('FixedAsset').where('fixedAssetId', fixedAssetId).queryOne().purchaseCost.compareTo(new BigDecimal('600000.00')) == 0
        assert from('FixedAsset').where('fixedAssetId', newLine.fixedAssetId).queryOne().purchaseCost.compareTo(new BigDecimal('300000.00')) == 0
        assert from('LeaseAssetSplit').where('leaseAgreementId', agreementId).queryCount() == 1
        // An agreement with a split asset cannot reverse its acceptance.
        assert ServiceUtil.isError(dispatcher.runSync('reverseLeaseAcceptance', [leaseAgreementId: agreementId,
                reason: 'test', userLogin: userLogin]))

        Map contractSplit = dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                newAgreementAssetSeqIds: '00002', reason: 'One printer to another office', userLogin: userLogin])
        ok(contractSplit)
        List<String> newIds = contractSplit.newLeaseAgreementIds
        assert newIds.size() == 2
        GenericValue first = agreement(newIds[0])
        GenericValue second = agreement(newIds[1])
        assert first.acquisitionCost.compareTo(new BigDecimal('300000.00')) == 0
        assert second.acquisitionCost.compareTo(new BigDecimal('600000.00')) == 0
        assert from('LeaseAgreementAsset').where('leaseAgreementId', newIds[0]).queryOne().fixedAssetId == newLine.fixedAssetId
        assert from('LeaseAgreementAsset').where('leaseAgreementId', newIds[1]).queryOne().fixedAssetId == fixedAssetId
    }

    // Finance lease: schedule, principal, deferred profit, residual, deposits / advances, GL, collection of the original.
    void testFinanceContractSplit() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1)
        BigDecimal residual = new BigDecimal('100000.00')
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', start, 24L,
                [['Machine A', 1, '600000.00'], ['Machine B', 1, '400000.00']], residual)
        ok(dispatcher.runSync('receiveLeaseAdvance', [leaseAgreementId: agreementId, advanceTypeEnumId: 'LSADV_DEPOSIT',
                amount: new BigDecimal('30000.00'), userLogin: userLogin]))
        ok(dispatcher.runSync('receiveLeaseAdvance', [leaseAgreementId: agreementId, advanceTypeEnumId: 'LSADV_LEASE',
                amount: new BigDecimal('1000.00'), applyOrderEnumId: 'LSADV_ORD_LAST', userLogin: userLogin]))
        ok(dispatcher.runSync('createLeaseMaintPlan', [leaseAgreementId: agreementId, assetSeqId: '00002',
                productMaintTypeId: 'LEASE_INSPECTION', intervalMonths: 3L, userLogin: userLogin]))
        List<GenericValue> before = currentSchedule(agreementId)
        GenericValue original = agreement(agreementId)

        // Invalid groups: one group, an unknown or duplicated asset.
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                assetGroups: [['00001', '00002']], userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                assetGroups: [['00001'], ['00009']], userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                assetGroups: [['00001', '00002'], ['00002']], userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                assetGroups: [['00001'], ['00002']], splitDate: ts(LocalDate.now().plusDays(1)), userLogin: userLogin]))
        // The status is changed only by the split.
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_SPLIT', userLogin: userLogin]))

        Map split = dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                assetGroups: [['00001'], ['00002']], reason: 'Split by site', userLogin: userLogin])
        ok(split)
        long k = split.effectiveInstallmentNumber
        assert k > 1
        List<String> newIds = split.newLeaseAgreementIds
        GenericValue a = agreement(newIds[0])
        GenericValue b = agreement(newIds[1])
        original.refresh()
        assert original.statusId == 'LEASEAGR_SPLIT'
        assert from('LeaseAgreementAsset').where('leaseAgreementId', agreementId).queryList().every { it.assetStatusId == 'LSAST_SPLIT' }
        assert a.statusId == 'LEASEAGR_ACTIVE' && b.statusId == 'LEASEAGR_ACTIVE'
        assert a.lesseePartyId == original.lesseePartyId && a.splitFromLeaseAgreementId == agreementId
        assert a.financeContractId != original.financeContractId && b.financeContractId != a.financeContractId
        assert from('FinanceContract').where('financeContractId', a.financeContractId).queryOne().statusId == 'FINCTR_ACTIVE'

        // Schedules: installments k..n, numbers and dates kept, amounts allocated 60 / 40 (sum = the original).
        List<GenericValue> rowsA = currentSchedule(a.leaseAgreementId)
        List<GenericValue> rowsB = currentSchedule(b.leaseAgreementId)
        List<GenericValue> unbilled = before.findAll { it.installmentNumber >= k }
        assert rowsA.size() == unbilled.size() && rowsB.size() == unbilled.size()
        assert rowsA[0].installmentNumber == k && rowsA[0].scheduleRevisionNumber == 1L
        unbilled.eachWithIndex { GenericValue row, int i ->
            assert rowsA[i].dueDate == row.dueDate && rowsB[i].periodFromDate == row.periodFromDate
            assert rowsA[i].leaseAmount.add(rowsB[i].leaseAmount).compareTo(row.leaseAmount) == 0
            assert rowsA[i].taxAmount.add(rowsB[i].taxAmount).compareTo(row.taxAmount) == 0
            assert rowsA[i].leaseAmount.compareTo(row.leaseAmount.multiply(new BigDecimal('0.6')).setScale(2, RoundingMode.HALF_UP)) == 0
        }
        assert a.residualValue.compareTo(new BigDecimal('60000.00')) == 0
        assert b.residualValue.compareTo(new BigDecimal('40000.00')) == 0
        assert rowsA[-1].remainingPrincipal.compareTo(a.residualValue) == 0
        assert rowsB[-1].remainingPrincipal.compareTo(b.residualValue) == 0
        BigDecimal remainingBefore = before.find { it.installmentNumber == k - 1 }.remainingPrincipal
        assert a.openingPrincipal.add(b.openingPrincipal).compareTo(remainingBefore) == 0
        assert sum(rowsA, 'principalPortion').compareTo(a.openingPrincipal.subtract(a.residualValue)) == 0
        assert a.totalLeaseAmount.add(b.totalLeaseAmount).compareTo(sum(unbilled, 'leaseAmount')) == 0

        // Deferred lease profit: what the original had not released is taken over.
        BigDecimal recognizedOriginal = sum(from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).queryList(), 'amount')
        BigDecimal deferred = original.totalInterestAmount.subtract(recognizedOriginal)
        assert a.totalInterestAmount.add(b.totalInterestAmount).compareTo(deferred) == 0
        Map balA = balance(a.leaseAgreementId, userLogin)
        assert balA.outstandingPrincipal.compareTo(a.openingPrincipal) == 0
        assert balA.deferredProfitBalance.compareTo(a.totalInterestAmount) == 0
        assert balA.unbilledLeaseAmount.compareTo(a.totalLeaseAmount) == 0
        // The release of the new agreement through its end equals the deferred profit taken over.
        ok(dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: a.leaseAgreementId,
                recognitionDate: a.leaseEndDate, userLogin: userLogin]))
        assert sum(from('LeaseProfitRecognition').where('leaseAgreementId', a.leaseAgreementId).queryList(), 'amount')
                .compareTo(a.totalInterestAmount) == 0

        // Deposits and advance lease payments transferred 60 / 40 (no balance left on the original).
        Map balOriginal = balance(agreementId, userLogin)
        assert balOriginal.depositBalance.compareTo(BigDecimal.ZERO) == 0
        assert balOriginal.advanceBalance.compareTo(BigDecimal.ZERO) == 0
        assert balA.depositBalance.compareTo(new BigDecimal('18000.00')) == 0
        assert balance(b.leaseAgreementId, userLogin).depositBalance.compareTo(new BigDecimal('12000.00')) == 0
        assert balA.advanceLastBalance.compareTo(new BigDecimal('600.00')) == 0
        GenericValue moved = from('LeaseAdvanceReceipt').where('leaseAgreementId', a.leaseAgreementId,
                'advanceTypeEnumId', 'LSADV_LEASE').queryOne()
        assert moved.applyOrderEnumId == 'LSADV_ORD_LAST' && moved.sourceLeaseAdvanceReceiptId != null

        // History and the recording transaction (balanced, investment and deferred profit).
        List<GenericValue> history = from('LeaseAgreementSplit').where('leaseAgreementId', agreementId).queryList()
        assert history.size() == 2
        assert history.every { it.acctgTransId == split.acctgTransId }
        Map<String, BigDecimal> sums = entrySums(split.acctgTransId)
        BigDecimal debits = sums.findAll { it.key.endsWith(':D') }.values().sum()
        BigDecimal credits = sums.findAll { it.key.endsWith(':C') }.values().sum()
        assert debits.compareTo(credits) == 0 && debits.signum() > 0
        assert sums['128500:D'].compareTo(sums['128500:C']) == 0                  // lease investment moved

        // Maintenance plan moved with its asset.
        assert from('LeaseMaintPlan').where('leaseAgreementId', agreementId).queryOne().thruDate != null
        GenericValue plan = from('LeaseMaintPlan').where('leaseAgreementId', b.leaseAgreementId).queryOne()
        assert plan != null && plan.assetSeqId == '00001' && plan.firstDueDate.after(ts(LocalDate.now()))

        // The original: no more billing; unbilled installments are not overdue; collected, then completed.
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: k, userLogin: userLogin]))
        List<Map> overdue = LeaseContractAdapter.overdueInstallments(delegator, original, ts(LocalDate.now().plusYears(5)))
        assert overdue.every { it.invoiceId != null }
        BigDecimal unpaid = balOriginal.unpaidBilledAmount
        assert unpaid.signum() > 0
        assert balOriginal.unbilledLeaseAmount.compareTo(BigDecimal.ZERO) == 0
        GenericValue originalContract = from('FinanceContract').where('financeContractId', original.financeContractId).queryOne()
        assert originalContract.principalAmount.compareTo(balOriginal.outstandingPrincipal) == 0
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: unpaid, userLogin: userLogin]))
        original.refresh()
        assert original.statusId == 'LEASEAGR_COMPLETED'
        originalContract.refresh()
        assert originalContract.statusId == 'FINCTR_COMPLETED'

        // The new agreements: a change of terms and an early termination work on them.
        Map modified = dispatcher.runSync('createLeaseModification', [leaseAgreementId: b.leaseAgreementId,
                newTermMonths: 20L, reason: 'Shorter term after split', userLogin: userLogin])
        ok(modified)
        List<GenericValue> rowsB2 = currentSchedule(b.leaseAgreementId)
        assert rowsB2[0].installmentNumber == k && rowsB2[-1].installmentNumber == 20L
        assert rowsB2[-1].remainingPrincipal.compareTo(b.residualValue) == 0
        ok(dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: b.leaseAgreementId,
                reason: 'Partial termination via split', userLogin: userLogin]))
        assert agreement(b.leaseAgreementId).statusId == 'LEASEAGR_TERMINATED'
        assert agreement(a.leaseAgreementId).statusId == 'LEASEAGR_ACTIVE'
        // The acceptance of a split agreement belongs to the original.
        assert ServiceUtil.isError(dispatcher.runSync('reverseLeaseAcceptance', [leaseAgreementId: a.leaseAgreementId,
                reason: 'test', userLogin: userLogin]))
    }

    // Operating lease: cost and accumulated depreciation taken over; the new agreements depreciate to their residual.
    void testOperatingContractSplit() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1)
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_OPER', start, 12L,
                [['Forklift A', 1, '300000.00'], ['Forklift B', 1, '200000.00']])
        Map split = dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                newAgreementAssetSeqIds: '00002', userLogin: userLogin])
        ok(split)
        List<String> newIds = split.newLeaseAgreementIds
        GenericValue a = agreement(newIds[0])
        GenericValue b = agreement(newIds[1])
        assert a.acquisitionCost.compareTo(new BigDecimal('200000.00')) == 0
        assert b.acquisitionCost.compareTo(new BigDecimal('300000.00')) == 0
        BigDecimal accumulatedOriginal = sum(from('LeaseAssetDepreciation').where('leaseAgreementId', agreementId).queryList(), 'amount')
        assert accumulatedOriginal.signum() > 0
        BigDecimal accA = sum(from('LeaseAssetDepreciation').where('leaseAgreementId', a.leaseAgreementId).queryList(), 'amount')
        BigDecimal accB = sum(from('LeaseAssetDepreciation').where('leaseAgreementId', b.leaseAgreementId).queryList(), 'amount')
        assert accA.add(accB).compareTo(accumulatedOriginal) == 0
        Map balA = balance(a.leaseAgreementId, userLogin)
        assert balA.leasedAssetCost.compareTo(a.acquisitionCost) == 0
        assert balA.accumulatedDepreciation.compareTo(accA) == 0
        assert balance(agreementId, userLogin).leasedAssetCost.compareTo(BigDecimal.ZERO) == 0
        List<GenericValue> history = from('LeaseAgreementSplit').where('leaseAgreementId', agreementId).queryList()
        assert history.find { it.newLeaseAgreementId == a.leaseAgreementId }.accumulatedDepreciation.compareTo(accA) == 0
        Map<String, BigDecimal> sums = entrySums(split.acctgTransId)
        BigDecimal debits = sums.findAll { it.key.endsWith(':D') }.values().sum()
        BigDecimal credits = sums.findAll { it.key.endsWith(':C') }.values().sum()
        assert debits.compareTo(credits) == 0
        // Depreciation continues on the new agreement and ends at its residual value.
        ok(dispatcher.runSync('depreciateLeaseAssets', [leaseAgreementId: a.leaseAgreementId,
                depreciationDate: a.leaseEndDate, userLogin: userLogin]))
        Map end = balance(a.leaseAgreementId, userLogin)
        assert end.leasedAssetBookValue.compareTo(a.residualValue) == 0
    }

    // Restrictions: an unreversed change of terms; the split date before the start.
    void testSplitRestrictions() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1)
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', start, 24L,
                [['Unit A', 1, '500000.00'], ['Unit B', 1, '500000.00']])
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                newAgreementAssetSeqIds: '00001', splitDate: ts(start.minusDays(1)), userLogin: userLogin]))
        ok(dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId, newTermMonths: 30L,
                reason: 'Longer term', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('splitLeaseAgreement', [leaseAgreementId: agreementId,
                newAgreementAssetSeqIds: '00001', userLogin: userLogin]))
        assert agreement(agreementId).statusId == 'LEASEAGR_ACTIVE'
    }
}
