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
import java.sql.Timestamp
import java.time.LocalDate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L15a: reversal of mistaken lease operations (取消): acceptance, early
 * termination and change of terms. FINANCE_ADMIN only, reason required, only when
 * nothing was processed after the operation; reversing GL entries and the schedule
 * restored as a new revision; the operation can be done again afterwards.
 */
class LeaseReversalTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseReversalTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    /** A user with every finance permission except FINANCE_ADMIN. */
    private GenericValue nonAdmin() {
        String id = 'TEST_FINUSR_' + delegator.getNextSeqId('UserLogin')
        String group = 'TEST_FINGRP_' + delegator.getNextSeqId('SecurityGroup')
        delegator.create('SecurityGroup', [groupId: group, description: 'Finance operator (test)'])
        ['FINANCE_VIEW', 'FINANCE_CREATE', 'FINANCE_UPDATE', 'FINANCE_ACCOUNTING_CREATE', 'FINANCE_PAYMENT_CREATE'].each { String p ->
            delegator.create('SecurityGroupPermission', [groupId: group, permissionId: p, fromDate: ts(LocalDate.of(2001, 1, 1))])
        }
        delegator.create('UserLogin', [userLoginId: id, enabled: 'Y'])
        delegator.create('UserLoginSecurityGroup', [userLoginId: id, groupId: group, fromDate: ts(LocalDate.of(2001, 1, 1))])
        return from('UserLogin').where('userLoginId', id).queryOne()
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay())
    }

    /** The service is refused (an error result or the permission exception of the dispatcher). */
    private void denied(String service, Map input) {
        try {
            assert ServiceUtil.isError(dispatcher.runSync(service, input))
        } catch (org.apache.ofbiz.service.GenericServiceException expected) {
            assert expected.getMessage().contains('FINANCE_ADMIN')
        }
    }

    private void ok(Map result) {        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private Map<String, BigDecimal> entrySums(String acctgTransId) {
        Map<String, BigDecimal> sums = [:]
        from('AcctgTransEntry').where('acctgTransId', acctgTransId).queryList().each { GenericValue e ->
            String key = e.glAccountId + ':' + e.debitCreditFlag
            sums[key] = (sums[key] ?: BigDecimal.ZERO).add(e.getBigDecimal('amount'))
        }
        return sums
    }

    /** The entries of b are those of a with debit and credit swapped. */
    private void assertReversed(String a, String b) {
        Map<String, BigDecimal> x = entrySums(a)
        Map<String, BigDecimal> y = entrySums(b)
        assert !x.isEmpty()
        x.each { String k, BigDecimal v ->
            String flipped = k.endsWith(':D') ? k[0..-2] + 'C' : k[0..-2] + 'D'
            assert y[flipped] != null && y[flipped].compareTo(v) == 0
        }
    }

    private List<GenericValue> schedule(String agreementId, Long revision = null) {
        long rev = revision ?: LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', rev).orderBy('installmentNumber').queryList()
    }

    /** Contracted lease with the asset purchased (not accepted). */
    private String contractedLease(GenericValue userLogin, String productId, LocalDate start, long term, BigDecimal calcRate) {
        String lessee = 'TEST_LSREV_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Reversal test lessee'])
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
        return agreement.leaseAgreementId
    }

    private String activeLease(GenericValue userLogin, String productId, LocalDate start, long term, BigDecimal calcRate) {
        String agreementId = contractedLease(userLogin, productId, start, term, calcRate)
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start), userLogin: userLogin]))
        return agreementId
    }

    // Acceptance: permission, reason, reversal (GL, schedule, statuses), accept again; blocked after billing.
    void testReverseAcceptance() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusDays(3)
        String agreementId = contractedLease(userLogin, 'TEST_LEASEPROD_USD', start, 60L, new BigDecimal('0.03'))
        List<GenericValue> rev1 = schedule(agreementId)
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start), userLogin: userLogin]))
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        GenericValue acceptance = from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne()

        // Only FINANCE_ADMIN; a reason is required; the generic status service cannot do it.
        denied('reverseLeaseAcceptance', [leaseAgreementId: agreementId,
                reason: 'Wrong date', userLogin: nonAdmin()])
        assert ServiceUtil.isError(dispatcher.runSync('reverseLeaseAcceptance', [leaseAgreementId: agreementId,
                reason: '  ', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_CONTRACTED', userLogin: userLogin]))

        Map rev = dispatcher.runSync('reverseLeaseAcceptance', [leaseAgreementId: agreementId, reason: 'Wrong acceptance date',
                userLogin: userLogin])
        ok(rev)
        GenericValue after = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert after.statusId == 'LEASEAGR_CONTRACTED'
        assert after.leaseStartDate == null && after.leaseEndDate == null
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne().statusId == 'FINCTR_CONTRACTED'
        assert from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne() == null
        assert from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne().assetStatusId == 'LSAST_PURCHASED'
        List<GenericValue> restored = schedule(agreementId)
        assert restored[0].scheduleRevisionNumber == acceptance.scheduleRevisionNumber + 1
        assert restored.size() == rev1.size()
        (0..<rev1.size()).each { int i ->
            assert restored[i].dueDate == rev1[i].dueDate
            assert restored[i].leaseAmount.compareTo(rev1[i].leaseAmount) == 0
        }
        GenericValue reversal = from('FinanceReversal').where('financeReversalId', rev.financeReversalId).queryOne()
        assert reversal.reversalTypeEnumId == 'FINREV_LEASE_ACCEPT'
        assert reversal.reason == 'Wrong acceptance date'
        assert reversal.reversedByUserLogin == 'system'
        assert reversal.snapshot.contains('startAcctgTransId=' + acceptance.startAcctgTransId)
        assertReversed(acceptance.startAcctgTransId, reversal.reversingAcctgTransIds.split(',')[0])
        // Nothing to reverse any more.
        assert ServiceUtil.isError(dispatcher.runSync('reverseLeaseAcceptance', [leaseAgreementId: agreementId,
                reason: 'again', userLogin: userLogin]))

        // Accept again on the right date; once billed, the acceptance cannot be reversed.
        LocalDate right = LocalDate.now().minusDays(1)
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(right), userLogin: userLogin]))
        assert from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne().leaseStartDate == ts(right)
        ok(dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId, installmentNumber: 1L, userLogin: userLogin]))
        Map blocked = dispatcher.runSync('reverseLeaseAcceptance', [leaseAgreementId: agreementId, reason: 'x', userLogin: userLogin])
        assert ServiceUtil.isError(blocked)
        assert ServiceUtil.getErrorMessage(blocked).contains('billing')
    }

    // Termination: reversal (damages invoice cancelled, settlement reversed, tasks), terminate again; blocked once paid.
    void testReverseTermination() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(6).minusDays(10), 60L, null)
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        ok(dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId, reason: 'Mistake', userLogin: userLogin]))
        GenericValue t = from('LeaseTermination').where('leaseAgreementId', agreementId).queryOne()
        long billed = from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryCount()

        denied('reverseLeaseTermination', [leaseAgreementId: agreementId,
                reason: 'Wrong contract', userLogin: nonAdmin()])
        Map rev = dispatcher.runSync('reverseLeaseTermination', [leaseAgreementId: agreementId, reason: 'Wrong contract',
                userLogin: userLogin])
        ok(rev)
        assert from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne().statusId == 'LEASEAGR_ACTIVE'
        assert from('LeaseTermination').where('leaseAgreementId', agreementId).queryOne() == null
        assert from('Invoice').where('invoiceId', t.invoiceId).queryOne().statusId == 'INVOICE_CANCELLED'
        GenericValue reversal = from('FinanceReversal').where('financeReversalId', rev.financeReversalId).queryOne()
        assertReversed(t.acctgTransId, reversal.reversingAcctgTransIds.split(',')[0])
        assert reversal.snapshot.contains('damagesAmount=')
        assert from('FinanceContractWorkEffort').where('financeContractId', agreement.financeContractId,
                'financeWorkTypeEnumId', 'FINWE_ASSET_RETURN').queryCount() == 0
        // The installments billed through the termination date stay; the balance has no damages.
        assert from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryCount() == billed
        Map bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(bal)
        assert bal.terminationDamagesAmount.signum() == 0
        assert bal.unbilledLeaseAmount.signum() > 0

        // Terminate again; once the damages are paid it cannot be reversed.
        ok(dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId, reason: 'Real', userLogin: userLogin]))
        assert from('FinanceContractWorkEffort').where('financeContractId', agreement.financeContractId,
                'financeWorkTypeEnumId', 'FINWE_ASSET_RETURN').queryCount() == 1
        bal = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount, userLogin: userLogin]))
        Map blocked = dispatcher.runSync('reverseLeaseTermination', [leaseAgreementId: agreementId, reason: 'x', userLogin: userLogin])
        assert ServiceUtil.isError(blocked)
        assert ServiceUtil.getErrorMessage(blocked).contains('payment')
    }

    // Change of terms: reversal restores the previous schedule and terms; blocked after a later profit release.
    void testReverseModification() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, 'TEST_LEASEPROD_USD', LocalDate.now().minusMonths(3).minusDays(10), 48L,
                new BigDecimal('0.03'))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        GenericValue before = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        List<GenericValue> old = schedule(agreementId)
        Map mod = dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId, newTermMonths: 60L, userLogin: userLogin])
        ok(mod)
        assert schedule(agreementId).size() == 60

        denied('reverseLeaseModification', [leaseAgreementId: agreementId,
                reason: 'Wrong term', userLogin: nonAdmin()])
        Map rev = dispatcher.runSync('reverseLeaseModification', [leaseAgreementId: agreementId, reason: 'Wrong term', userLogin: userLogin])
        ok(rev)
        assert rev.scheduleRevisionNumber == mod.scheduleRevisionNumber + 1
        List<GenericValue> restored = schedule(agreementId)
        assert restored.size() == 48
        (0..<48).each { int i ->
            assert restored[i].leaseAmount.compareTo(old[i].leaseAmount) == 0
            assert restored[i].interestPortion.compareTo(old[i].interestPortion) == 0
        }
        GenericValue after = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert after.termMonths == 48L
        assert after.leaseEndDate == before.leaseEndDate
        assert after.monthlyLeaseAmount.compareTo(before.monthlyLeaseAmount) == 0
        assert after.totalLeaseAmount.compareTo(before.totalLeaseAmount) == 0
        assert after.totalInterestAmount.compareTo(before.totalInterestAmount) == 0
        GenericValue m = from('LeaseModification').where('leaseModificationId', mod.leaseModificationId).queryOne()
        assert m.reversedDate != null && m.financeReversalId == rev.financeReversalId
        GenericValue reversal = from('FinanceReversal').where('financeReversalId', rev.financeReversalId).queryOne()
        assertReversed(mod.acctgTransId, reversal.reversingAcctgTransIds)
        // Nothing left to reverse.
        assert ServiceUtil.isError(dispatcher.runSync('reverseLeaseModification', [leaseAgreementId: agreementId,
                reason: 'again', userLogin: userLogin]))

        // Change again, release deferred profit after it: the change can no longer be reversed.
        ok(dispatcher.runSync('createLeaseModification', [leaseAgreementId: agreementId, newTermMonths: 54L, userLogin: userLogin]))
        ok(dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId,
                recognitionDate: ts(LocalDate.now().plusDays(40)), userLogin: userLogin]))
        Map blocked = dispatcher.runSync('reverseLeaseModification', [leaseAgreementId: agreementId, reason: 'x', userLogin: userLogin])
        assert ServiceUtil.isError(blocked)
        assert ServiceUtil.getErrorMessage(blocked).contains('deferred profit')
    }
}
