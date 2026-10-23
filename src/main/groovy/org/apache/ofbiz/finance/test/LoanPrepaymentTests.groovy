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
package org.apache.ofbiz.finance.test

import java.math.BigDecimal
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Tests for the STEP 7 prepayment and loan modification flow: partial prepayment
 * (amount / term reduction), full prepayment, and condition changes that
 * re-amortize the remaining schedule as a new revision.
 */
class LoanPrepaymentTests extends OFBizTestCase {

    LoanPrepaymentTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void approveApplication(String loanApplicationId, GenericValue userLogin) {
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId, statusId: 'LOANAPP_SUBMITTED', userLogin: userLogin]))
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId, statusId: 'LOANAPP_APPROVED', userLogin: userLogin]))
    }

    private void approveUnderwriting(String loanApplicationId, GenericValue userLogin) {
        Map created = dispatcher.runSync('createLoanUnderwriting', [
                loanApplicationId          : loanApplicationId,
                statusId                  : 'LOANUW_IN_REVIEW',
                creditScore               : 750L,
                decision                  : 'PENDING',
                approvedPrincipalAmount   : new BigDecimal('120000.00'),
                approvedAnnualInterestRate: new BigDecimal('0.150000'),
                userLogin                  : userLogin])
        assert ServiceUtil.isSuccess(created)
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: created.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
    }

    private void acceptQuote(String loanQuoteId, GenericValue userLogin) {
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_PRESENTED', userLogin: userLogin]))
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_ACCEPTED', userLogin: userLogin]))
    }

    // Create an active agreement whose first payment is one month in the future.
    private String setupAgreement(GenericValue userLogin) {
        Map appResult = dispatcher.runSync('createLoanApplication', [
                productId               : 'TEST_LOANPROD1',
                applicantPartyId        : 'TEST_BORROWER1',
                currencyUomId           : 'USD',
                requestedPrincipalAmount: new BigDecimal('120000.00'),
                requestedTermMonths     : 12L,
                userLogin               : userLogin])
        assert ServiceUtil.isSuccess(appResult)
        String appId = appResult.loanApplicationId

        approveApplication(appId, userLogin)
        approveUnderwriting(appId, userLogin)

        java.sql.Timestamp firstPaymentDate = java.sql.Timestamp.valueOf(
                java.time.LocalDate.now().plusMonths(1).atStartOfDay())
        Map quoteResult = dispatcher.runSync('createLoanQuote', [
                loanApplicationId : appId,
                principalAmount   : new BigDecimal('120000.00'),
                annualInterestRate: new BigDecimal('0.150000'),
                termMonths        : 12L,
                firstPaymentDate  : firstPaymentDate,
                userLogin         : userLogin])
        assert ServiceUtil.isSuccess(quoteResult)
        acceptQuote(quoteResult.loanQuoteId, userLogin)

        Map agrResult = dispatcher.runSync('createLoanAgreement', [
                loanQuoteId: quoteResult.loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(agrResult)
        return agrResult.loanAgreementId
    }

    private BigDecimal principalSum(String loanAgreementId, long revision) {
        BigDecimal sum = BigDecimal.ZERO
        from('LoanAgreementRepaymentSchedule').where(
                'loanAgreementId', loanAgreementId, 'scheduleRevisionNumber', revision)
                .queryList().each { sum = sum.add(it.getBigDecimal('principalAmount')) }
        return sum
    }

    // Partial prepayment (amount reduction) lowers the principal and re-amortizes.
    void testPartialPrepaymentAmountReduction() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupAgreement(userLogin)

        Map result = dispatcher.runSync('createLoanPrepayment', [
                loanAgreementId       : loanAgreementId,
                prepaymentTypeEnumId  : 'PREPAY_PART_AMOUNT',
                principalAmount       : new BigDecimal('20000.00'),
                userLogin             : userLogin])
        assert ServiceUtil.isSuccess(result)
        assert result.paymentId != null
        assert result.penaltyAmount.compareTo(new BigDecimal('200.00')) == 0

        GenericValue prepayment = from('LoanPrepayment').where(
                'loanAgreementId', loanAgreementId).queryFirst()
        assert prepayment != null
        assert prepayment.principalAmount.compareTo(new BigDecimal('20000.00')) == 0

        Map balance = dispatcher.runSync('getLoanAgreementBalance',
                [loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(balance)
        assert balance.prepaymentPrincipalPaid.compareTo(new BigDecimal('20000.00')) == 0
        assert balance.outstandingPrincipal.compareTo(new BigDecimal('100000.00')) == 0

        List revision2 = from('LoanAgreementRepaymentSchedule').where(
                'loanAgreementId', loanAgreementId, 'scheduleRevisionNumber', 2L).queryList()
        assert revision2.size() == 12
        assert principalSum(loanAgreementId, 2L).setScale(2)
                .compareTo(new BigDecimal('100000.00')) == 0
        // interestThruDate must equal dueDate; interestFromDate continuity must hold.
        for (GenericValue row : revision2) {
            assert row.getTimestamp('interestFromDate') != null
            assert row.getTimestamp('interestThruDate') == row.getTimestamp('dueDate')
        }
    }

    // Partial prepayment (term reduction) shortens the remaining schedule.
    void testPartialPrepaymentTermReduction() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupAgreement(userLogin)

        Map result = dispatcher.runSync('createLoanPrepayment', [
                loanAgreementId       : loanAgreementId,
                prepaymentTypeEnumId  : 'PREPAY_PART_TERM',
                principalAmount       : new BigDecimal('20000.00'),
                userLogin             : userLogin])
        assert ServiceUtil.isSuccess(result)

        List revision2 = from('LoanAgreementRepaymentSchedule').where(
                'loanAgreementId', loanAgreementId, 'scheduleRevisionNumber', 2L).queryList()
        assert revision2.size() > 0
        assert revision2.size() < 12
        assert principalSum(loanAgreementId, 2L).setScale(2)
                .compareTo(new BigDecimal('100000.00')) == 0
    }

    // Full prepayment closes the agreement.
    void testFullPrepayment() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupAgreement(userLogin)

        Map result = dispatcher.runSync('createLoanPrepayment', [
                loanAgreementId      : loanAgreementId,
                prepaymentTypeEnumId : 'PREPAY_FULL',
                userLogin            : userLogin])
        assert ServiceUtil.isSuccess(result)
        assert result.paymentId != null

        assert from('LoanAgreement').where('loanAgreementId', loanAgreementId)
                .queryOne().statusId == 'LOANAGR_CLOSED'
        GenericValue prepayment = from('LoanPrepayment').where(
                'loanAgreementId', loanAgreementId).queryFirst()
        assert prepayment != null
        assert prepayment.prepaymentTypeEnumId == 'PREPAY_FULL'
        assert prepayment.principalAmount.compareTo(new BigDecimal('120000.00')) == 0
        Map balance = dispatcher.runSync('getLoanAgreementBalance',
                [loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(balance)
        assert balance.prepaymentPrincipalPaid.compareTo(new BigDecimal('120000.00')) == 0
        assert balance.outstandingPrincipal.compareTo(BigDecimal.ZERO) == 0
    }

    // A condition change (rate + term) records history and re-amortizes.
    void testLoanModification() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupAgreement(userLogin)

        Map result = dispatcher.runSync('createLoanModification', [
                loanAgreementId       : loanAgreementId,
                newAnnualInterestRate : new BigDecimal('0.100000'),
                newTermMonths         : 24L,
                userLogin             : userLogin])
        assert ServiceUtil.isSuccess(result)

        GenericValue agreement = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
        assert agreement.annualInterestRate.compareTo(new BigDecimal('0.100000')) == 0
        assert agreement.termMonths == 24L

        GenericValue modification = from('LoanModification').where(
                'loanAgreementId', loanAgreementId).queryFirst()
        assert modification != null
        assert modification.oldAnnualInterestRate.compareTo(new BigDecimal('0.150000')) == 0
        assert modification.newAnnualInterestRate.compareTo(new BigDecimal('0.100000')) == 0
        assert modification.oldTermMonths == 12L
        assert modification.newTermMonths == 24L

        List revision2 = from('LoanAgreementRepaymentSchedule').where(
                'loanAgreementId', loanAgreementId, 'scheduleRevisionNumber', 2L).queryList()
        assert revision2.size() == 24
    }
}
