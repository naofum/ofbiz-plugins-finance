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
import org.apache.ofbiz.finance.loan.FinanceAccountingServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L15a: reversal of mistaken loan operations (取消): contract activation,
 * prepayment and change of terms. FINANCE_ADMIN only, reason required, only when
 * nothing was processed after the operation.
 */
class LoanReversalTests extends OFBizTestCase {

    LoanReversalTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
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

    private GenericValue nonAdmin() {
        String id = 'TEST_FINUSL_' + delegator.getNextSeqId('UserLogin')
        String group = 'TEST_FINGRL_' + delegator.getNextSeqId('SecurityGroup')
        delegator.create('SecurityGroup', [groupId: group, description: 'Finance operator (test)'])
        ['FINANCE_VIEW', 'FINANCE_CREATE', 'FINANCE_UPDATE', 'FINANCE_ACCOUNTING_CREATE', 'FINANCE_PAYMENT_CREATE'].each { String p ->
            delegator.create('SecurityGroupPermission', [groupId: group, permissionId: p, fromDate: ts(LocalDate.of(2001, 1, 1))])
        }
        delegator.create('UserLogin', [userLoginId: id, enabled: 'Y'])
        delegator.create('UserLoginSecurityGroup', [userLoginId: id, groupId: group, fromDate: ts(LocalDate.of(2001, 1, 1))])
        return from('UserLogin').where('userLoginId', id).queryOne()
    }

    private Map<String, BigDecimal> entrySums(String acctgTransId) {
        Map<String, BigDecimal> sums = [:]
        from('AcctgTransEntry').where('acctgTransId', acctgTransId).queryList().each { GenericValue e ->
            String key = e.glAccountId + ':' + e.debitCreditFlag
            sums[key] = (sums[key] ?: BigDecimal.ZERO).add(e.getBigDecimal('amount'))
        }
        return sums
    }

    private String newQuote(String appId, GenericValue userLogin) {
        Map quote = dispatcher.runSync('createLoanQuote', [loanApplicationId: appId, principalAmount: new BigDecimal('120000.00'),
                annualInterestRate: new BigDecimal('0.150000'), termMonths: 12L,
                firstPaymentDate: ts(LocalDate.now().plusMonths(1)), userLogin: userLogin])
        ok(quote)
        ok(dispatcher.runSync('setLoanQuoteStatus', [loanQuoteId: quote.loanQuoteId, statusId: 'LOANQT_PRESENTED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLoanQuoteStatus', [loanQuoteId: quote.loanQuoteId, statusId: 'LOANQT_ACCEPTED', userLogin: userLogin]))
        return quote.loanQuoteId
    }

    /** Active agreement (first payment one month ahead) and its application. */
    private Map setupAgreement(GenericValue userLogin) {
        Map app = dispatcher.runSync('createLoanApplication', [productId: 'TEST_LOANPROD1', applicantPartyId: 'TEST_BORROWER1',
                currencyUomId: 'USD', requestedPrincipalAmount: new BigDecimal('120000.00'), requestedTermMonths: 12L,
                userLogin: userLogin])
        ok(app)
        String appId = app.loanApplicationId
        ok(dispatcher.runSync('setLoanApplicationStatus', [loanApplicationId: appId, statusId: 'LOANAPP_SUBMITTED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLoanApplicationStatus', [loanApplicationId: appId, statusId: 'LOANAPP_APPROVED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLoanUnderwriting', [loanApplicationId: appId, statusId: 'LOANUW_IN_REVIEW',
                creditScore: 750L, decision: 'PENDING', approvedPrincipalAmount: new BigDecimal('120000.00'),
                approvedAnnualInterestRate: new BigDecimal('0.150000'), userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        Map agr = dispatcher.runSync('createLoanAgreement', [loanQuoteId: newQuote(appId, userLogin), userLogin: userLogin])
        ok(agr)
        return [loanAgreementId: agr.loanAgreementId, loanApplicationId: appId]
    }

    private List<GenericValue> schedule(String loanAgreementId, long revision) {
        return from('LoanAgreementRepaymentSchedule').where('loanAgreementId', loanAgreementId,
                'scheduleRevisionNumber', revision).orderBy('installmentNumber').queryList()
    }

    // Activation: permission, reason, reversal (cancelled, application approved, new agreement possible); blocked after accrual.
    void testReverseActivation() {
        GenericValue userLogin = admin()
        Map ids = setupAgreement(userLogin)
        GenericValue agreement = from('LoanAgreement').where('loanAgreementId', ids.loanAgreementId).queryOne()
        denied('reverseLoanAgreement', [loanAgreementId: ids.loanAgreementId,
                reason: 'Wrong borrower', userLogin: nonAdmin()])
        assert ServiceUtil.isError(dispatcher.runSync('reverseLoanAgreement', [loanAgreementId: ids.loanAgreementId,
                reason: '  ', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('setLoanAgreementStatus', [loanAgreementId: ids.loanAgreementId,
                statusId: 'LOANAGR_CANCELLED', userLogin: userLogin]))

        Map rev = dispatcher.runSync('reverseLoanAgreement', [loanAgreementId: ids.loanAgreementId, reason: 'Wrong borrower',
                userLogin: userLogin])
        ok(rev)
        assert from('LoanAgreement').where('loanAgreementId', ids.loanAgreementId).queryOne().statusId == 'LOANAGR_CANCELLED'
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne().statusId == 'FINCTR_CANCELLED'
        assert from('FinAccount').where('finAccountId', agreement.finAccountId).queryOne().statusId == 'FNACT_CANCELLED'
        assert from('LoanApplication').where('loanApplicationId', ids.loanApplicationId).queryOne().statusId == 'LOANAPP_APPROVED'
        assert from('FinanceReversal').where('financeReversalId', rev.financeReversalId).queryOne().reversalTypeEnumId == 'FINREV_LOAN_ACTIVATE'
        // The application back to APPROVED cannot be done with the generic service.
        // A new agreement can be made from a new quote.
        Map again = dispatcher.runSync('createLoanAgreement', [loanQuoteId: newQuote(ids.loanApplicationId, userLogin),
                userLogin: userLogin])
        ok(again)
        // After an interest accrual it cannot be reversed.
        ok(dispatcher.runSync('runMonthlyInterestAccrual', [loanAgreementId: again.loanAgreementId,
                accrualDate: ts(LocalDate.now().plusDays(20)), userLogin: userLogin]))
        Map blocked = dispatcher.runSync('reverseLoanAgreement', [loanAgreementId: again.loanAgreementId, reason: 'x', userLogin: userLogin])
        assert ServiceUtil.isError(blocked)
        assert ServiceUtil.getErrorMessage(blocked).contains('accrual')
    }

    // Partial prepayment: payment voided, fee reversed, schedule restored; full prepayment: agreement reopened.
    void testReversePrepayment() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupAgreement(userLogin).loanAgreementId
        List<GenericValue> rev1 = schedule(loanAgreementId, 1L)
        Map pre = dispatcher.runSync('createLoanPrepayment', [loanAgreementId: loanAgreementId, prepaymentTypeEnumId: 'PREPAY_PART_AMOUNT',
                principalAmount: new BigDecimal('20000.00'), userLogin: userLogin])
        ok(pre)
        GenericValue p = from('LoanPrepayment').where('loanPrepaymentId', pre.loanPrepaymentId).queryOne()
        assert p.fromRevisionNumber == 1L && p.toRevisionNumber == 2L && p.feeAcctgTransId != null

        denied('reverseLoanPrepayment', [loanPrepaymentId: pre.loanPrepaymentId,
                reason: 'Wrong amount', userLogin: nonAdmin()])
        Map rev = dispatcher.runSync('reverseLoanPrepayment', [loanPrepaymentId: pre.loanPrepaymentId, reason: 'Wrong amount',
                userLogin: userLogin])
        ok(rev)
        assert from('Payment').where('paymentId', pre.paymentId).queryOne().statusId == 'PMNT_VOID'
        p.refresh()
        assert p.reversedDate != null
        assert FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId) == 3L
        List<GenericValue> rev3 = schedule(loanAgreementId, 3L)
        assert rev3.size() == rev1.size()
        (0..<rev1.size()).each { int i -> assert rev3[i].principalAmount.compareTo(rev1[i].principalAmount) == 0 }
        GenericValue reversal = from('FinanceReversal').where('financeReversalId', rev.financeReversalId).queryOne()
        Map<String, BigDecimal> fee = entrySums(p.feeAcctgTransId)
        Map<String, BigDecimal> back = entrySums(reversal.reversingAcctgTransIds)
        fee.each { String k, BigDecimal v -> assert back[k[0..-2] + (k.endsWith('D') ? 'C' : 'D')].compareTo(v) == 0 }
        assert ServiceUtil.isError(dispatcher.runSync('reverseLoanPrepayment', [loanPrepaymentId: pre.loanPrepaymentId,
                reason: 'again', userLogin: userLogin]))

        // Full prepayment closes; the reversal reopens.
        Map full = dispatcher.runSync('createLoanPrepayment', [loanAgreementId: loanAgreementId, prepaymentTypeEnumId: 'PREPAY_FULL',
                userLogin: userLogin])
        ok(full)
        GenericValue agreement = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
        assert agreement.statusId == 'LOANAGR_CLOSED'
        ok(dispatcher.runSync('reverseLoanPrepayment', [loanPrepaymentId: full.loanPrepaymentId, reason: 'Not paid', userLogin: userLogin]))
        assert from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne().statusId == 'LOANAGR_ACTIVE'
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne().statusId == 'FINCTR_ACTIVE'
    }

    // Change of terms: reversal restores the rate / term and schedule; only the latest; blocked after an accrual.
    void testReverseModification() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupAgreement(userLogin).loanAgreementId
        GenericValue before = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
        Map mod1 = dispatcher.runSync('createLoanModification', [loanAgreementId: loanAgreementId,
                newAnnualInterestRate: new BigDecimal('0.120000'), userLogin: userLogin])
        ok(mod1)
        Map mod2 = dispatcher.runSync('createLoanModification', [loanAgreementId: loanAgreementId, newTermMonths: 18L, userLogin: userLogin])
        ok(mod2)
        // Only the latest can be reversed.
        Map notLatest = dispatcher.runSync('reverseLoanModification', [loanModificationId: mod1.loanModificationId, reason: 'x',
                userLogin: userLogin])
        assert ServiceUtil.isError(notLatest)
        ok(dispatcher.runSync('reverseLoanModification', [loanModificationId: mod2.loanModificationId, reason: 'Wrong term',
                userLogin: userLogin]))
        GenericValue mid = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
        assert mid.termMonths == 12L && mid.annualInterestRate.compareTo(new BigDecimal('0.12')) == 0
        long current = FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId)
        assert schedule(loanAgreementId, current).size() == schedule(loanAgreementId, 2L).size()
        ok(dispatcher.runSync('reverseLoanModification', [loanModificationId: mod1.loanModificationId, reason: 'Wrong rate',
                userLogin: userLogin]))
        GenericValue after = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
        assert after.annualInterestRate.compareTo(before.annualInterestRate) == 0
        current = FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId)
        List<GenericValue> restored = schedule(loanAgreementId, current)
        List<GenericValue> rev1 = schedule(loanAgreementId, 1L)
        (0..<rev1.size()).each { int i -> assert restored[i].interestAmount.compareTo(rev1[i].interestAmount) == 0 }

        // After an accrual the next change cannot be reversed.
        Map mod3 = dispatcher.runSync('createLoanModification', [loanAgreementId: loanAgreementId,
                newAnnualInterestRate: new BigDecimal('0.100000'), userLogin: userLogin])
        ok(mod3)
        ok(dispatcher.runSync('runMonthlyInterestAccrual', [loanAgreementId: loanAgreementId,
                accrualDate: ts(LocalDate.now().plusDays(20)), userLogin: userLogin]))
        Map blocked = dispatcher.runSync('reverseLoanModification', [loanModificationId: mod3.loanModificationId, reason: 'x',
                userLogin: userLogin])
        assert ServiceUtil.isError(blocked)
        assert ServiceUtil.getErrorMessage(blocked).contains('accrual')
    }
}
