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
 * Tests for the STEP 6 delinquency / late fee / dunning flow: past-due
 * detection, classification, late fee accrual with GL, dunning notices and
 * write-off / legal actions.
 */
class LoanDelinquencyTests extends OFBizTestCase {

    LoanDelinquencyTests(String name) {
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

    // Create an active, billed agreement whose single installment was due
    // daysPastDue days ago (so it is delinquent).
    private String setupDelinquentAgreement(long daysPastDue, GenericValue userLogin) {
        Map appResult = dispatcher.runSync('createLoanApplication', [
                productId               : 'TEST_LOANPROD1',
                applicantPartyId        : 'TEST_BORROWER1',
                currencyUomId           : 'USD',
                requestedPrincipalAmount: new BigDecimal('120000.00'),
                requestedTermMonths     : 1L,
                userLogin               : userLogin])
        assert ServiceUtil.isSuccess(appResult)
        String appId = appResult.loanApplicationId

        approveApplication(appId, userLogin)
        approveUnderwriting(appId, userLogin)

        java.sql.Timestamp firstPaymentDate = java.sql.Timestamp.valueOf(
                java.time.LocalDate.now().minusDays(daysPastDue).atStartOfDay())
        java.sql.Timestamp agreementDate = java.sql.Timestamp.valueOf(
                java.time.LocalDate.now().minusDays(daysPastDue + 15L).atStartOfDay())
        Map quoteResult = dispatcher.runSync('createLoanQuote', [
                loanApplicationId : appId,
                principalAmount   : new BigDecimal('120000.00'),
                annualInterestRate: new BigDecimal('0.150000'),
                termMonths        : 1L,
                firstPaymentDate  : firstPaymentDate,
                userLogin         : userLogin])
        assert ServiceUtil.isSuccess(quoteResult)
        String loanQuoteId = quoteResult.loanQuoteId
        acceptQuote(loanQuoteId, userLogin)

        Map agrResult = dispatcher.runSync('createLoanAgreement', [
                loanQuoteId: loanQuoteId, agreementDate: agreementDate, userLogin: userLogin])
        assert ServiceUtil.isSuccess(agrResult)
        String loanAgreementId = agrResult.loanAgreementId

        Map billResult = dispatcher.runSync('createLoanInstallmentInvoice', [
                loanAgreementId: loanAgreementId, installmentNumber: 1L, userLogin: userLogin])
        assert ServiceUtil.isSuccess(billResult)
        return loanAgreementId
    }

    // Past-due detection and product-based classification.
    void testDelinquencyDetectionAndClassification() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupDelinquentAgreement(45L, userLogin)

        Map batch = dispatcher.runSync('runLoanDelinquencyBatch', [
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(batch)

        GenericValue delinquency = from('LoanDelinquency')
                .where('loanAgreementId', loanAgreementId).orderBy('-asOfDate').queryFirst()
        assert delinquency != null
        assert delinquency.daysPastDue >= 31L && delinquency.daysPastDue <= 60L
        assert delinquency.delinquencyLevelEnumId == 'DELINQ_D2'
        assert delinquency.overduePrincipalAmount.compareTo(BigDecimal.ZERO) > 0
    }

    // Late fee accrues on the overdue balance and posts a balanced GL journal.
    void testLateFeeAccrualAndGl() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupDelinquentAgreement(45L, userLogin)

        Map accrual = dispatcher.runSync('runLateFeeAccrual', [
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(accrual)
        assert accrual.accrualCreated
        assert accrual.lateFeeAmount.compareTo(BigDecimal.ZERO) > 0

        GenericValue lateFee = from('LoanLateFeeAccrual')
                .where('loanAgreementId', loanAgreementId).queryFirst()
        assert lateFee != null
        assert lateFee.acctgTransId != null

        GenericValue acctgTrans = from('AcctgTrans').where('acctgTransId', lateFee.acctgTransId).queryOne()
        assert acctgTrans != null
        assert acctgTrans.isPosted == 'Y'
        List entries = from('AcctgTransEntry').where('acctgTransId', lateFee.acctgTransId).queryList()
        assert entries.size() == 2
        assert entries.find { it.debitCreditFlag == 'D' }.glAccountId == '121800'
        assert entries.find { it.debitCreditFlag == 'C' }.glAccountId == '810100'
        assert entries.find { it.debitCreditFlag == 'D' }.amount.compareTo(lateFee.lateFeeAmount) == 0
        assert entries.find { it.debitCreditFlag == 'C' }.amount.compareTo(lateFee.lateFeeAmount) == 0

        Map retry = dispatcher.runSync('runLateFeeAccrual', [
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(retry)
        assert !retry.accrualCreated
    }

    // A dunning notice can be recorded at a dunning level.
    void testDunningNotice() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupDelinquentAgreement(45L, userLogin)

        Map result = dispatcher.runSync('createLoanDunningNotice', [
                loanAgreementId: loanAgreementId, dunningLevelEnumId: 'DUN_L2', userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assert result.noticeCreated

        GenericValue notice = from('LoanDunningNotice')
                .where('loanAgreementId', loanAgreementId, 'dunningLevelEnumId', 'DUN_L2').queryOne()
        assert notice != null
    }

    // At D3 (write-off flag) the invoice is written off and an action recorded.
    void testWriteOff() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupDelinquentAgreement(75L, userLogin)

        Map batch = dispatcher.runSync('runLoanDelinquencyBatch', [
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(batch)

        GenericValue action = from('LoanDelinquencyAction')
                .where('loanAgreementId', loanAgreementId, 'actionTypeEnumId', 'DELINQ_ACT_WRITEOFF').queryOne()
        assert action != null
        assert action.amount.compareTo(BigDecimal.ZERO) > 0

        GenericValue link = from('LoanInstallmentInvoice')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 1L).queryOne()
        assert link != null
        assert from('Invoice').where('invoiceId', link.invoiceId).queryOne().statusId == 'INVOICE_WRITEOFF'
    }

    // At D4 the legal action flag is recorded alongside the write-off.
    void testLegalActionFlag() {
        GenericValue userLogin = admin()
        String loanAgreementId = setupDelinquentAgreement(100L, userLogin)

        Map batch = dispatcher.runSync('runLoanDelinquencyBatch', [
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(batch)

        GenericValue legal = from('LoanDelinquencyAction')
                .where('loanAgreementId', loanAgreementId, 'actionTypeEnumId', 'DELINQ_ACT_LEGAL').queryOne()
        assert legal != null
    }
}
