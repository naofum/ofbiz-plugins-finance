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
 * End-to-end tests for the finance loan lifecycle:
 * application -> quote (repayment plan) -> agreement (FinAccount) ->
 * billing (invoice) -> payment receipt (settlement).
 *
 * Relies on seed/demo data loaded by the finance test data file
 * (FinanceTestData.xml): party, financial product and a loan application.
 */
class LoanLifecycleTests extends OFBizTestCase {

    LoanLifecycleTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void resetApplication(String loanApplicationId) {
        GenericValue app = from('LoanApplication').where('loanApplicationId', loanApplicationId).queryOne()
        assert app != null
        app.statusId = 'LOANAPP_RECEIVED'
        app.store()
    }

    private void approveApplication(String loanApplicationId, GenericValue userLogin) {
        resetApplication(loanApplicationId)
        Map submitted = dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId, statusId: 'LOANAPP_SUBMITTED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(submitted)
        Map approved = dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId, statusId: 'LOANAPP_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(approved)
    }

    private String approveUnderwriting(String loanApplicationId, GenericValue userLogin) {
        Map created = dispatcher.runSync('createLoanUnderwriting', [
                loanApplicationId          : loanApplicationId,
                statusId                  : 'LOANUW_IN_REVIEW',
                creditScore               : 750L,
                decision                  : 'PENDING',
                approvedPrincipalAmount   : new BigDecimal('120000.00'),
                approvedAnnualInterestRate: new BigDecimal('0.120000'),
                userLogin                  : userLogin])
        assert ServiceUtil.isSuccess(created)
        String loanUnderwritingId = created.loanUnderwritingId
        assert loanUnderwritingId != null

        Map approved = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: loanUnderwritingId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(approved)
        GenericValue underwriting = from('LoanUnderwriting')
                .where('loanUnderwritingId', loanUnderwritingId).queryOne()
        assert underwriting.statusId == 'LOANUW_APPROVED'
        assert underwriting.decision == 'APPROVED'
        assert underwriting.reviewDate != null
        return loanUnderwritingId
    }

    private String createQuote(String loanApplicationId, BigDecimal principal, long termMonths,
            GenericValue userLogin) {
        Map result = dispatcher.runSync('createLoanQuote', [
                loanApplicationId : loanApplicationId,
                principalAmount   : principal,
                annualInterestRate: new BigDecimal('0.120000'),
                termMonths        : termMonths,
                userLogin         : userLogin])
        assert ServiceUtil.isSuccess(result)
        return result.loanQuoteId
    }

    private void acceptQuote(String loanQuoteId, GenericValue userLogin) {
        Map presented = dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_PRESENTED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(presented)
        Map accepted = dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_ACCEPTED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(accepted)
    }

    // Quote generation must produce a schedule whose principal sums to the loan principal.
    void testCreateQuoteAndSchedule() {
        GenericValue userLogin = admin()
        String loanQuoteId = createQuote('TEST_LOANAPP1', new BigDecimal('120000.00'), 12L, userLogin)

        GenericValue quote = from('LoanQuote').where('loanQuoteId', loanQuoteId).queryOne()
        assert quote != null
        assert quote.monthlyPaymentAmount != null

        List schedule = from('LoanRepaymentSchedule').where('loanQuoteId', loanQuoteId)
                .orderBy('installmentNumber').queryList()
        assert schedule.size() == 12

        BigDecimal principalSum = BigDecimal.ZERO
        for (GenericValue row : schedule) {
            principalSum = principalSum.add(row.getBigDecimal('principalAmount'))
        }
        assert principalSum.setScale(2).compareTo(new BigDecimal('120000.00')) == 0
        assert schedule[-1].getBigDecimal('remainingBalance').compareTo(BigDecimal.ZERO) == 0
    }

    // Equal-principal calculation must use the seed ID and decrease payments over time.
    void testEqualPrincipalQuoteAndUnknownMethod() {
        GenericValue userLogin = admin()
        Map result = dispatcher.runSync('createLoanQuote', [
                loanApplicationId    : 'TEST_LOANAPP1',
                principalAmount      : new BigDecimal('120000.00'),
                annualInterestRate   : new BigDecimal('0.120000'),
                termMonths           : 12L,
                repaymentMethodEnumId: 'LOAN_RPM_EQUAL_PRIN',
                userLogin            : userLogin])
        assert ServiceUtil.isSuccess(result)
        GenericValue quote = from('LoanQuote').where('loanQuoteId', result.loanQuoteId).queryOne()
        assert quote.monthlyPaymentAmount == null
        List schedule = from('LoanRepaymentSchedule').where('loanQuoteId', result.loanQuoteId)
                .orderBy('installmentNumber').queryList()
        assert schedule.size() == 12
        assert schedule[0].principalAmount.compareTo(schedule[1].principalAmount) == 0
        assert schedule[0].paymentAmount.compareTo(schedule[1].paymentAmount) > 0

        Map invalid = dispatcher.runSync('createLoanQuote', [
                loanApplicationId: 'TEST_LOANAPP1', repaymentMethodEnumId: 'UNKNOWN_METHOD',
                userLogin: userLogin])
        assert ServiceUtil.isError(invalid)
    }

    // CRUD services must not bypass any status workflow.
    void testStatusWorkflowCannotBeBypassed() {
        GenericValue userLogin = admin()
        Map appResult = dispatcher.runSync('createLoanApplication', [
                productId               : 'TEST_LOANPROD1',
                applicantPartyId        : 'TEST_BORROWER1',
                statusId                : 'LOANAPP_APPROVED',
                currencyUomId           : 'USD',
                requestedPrincipalAmount: new BigDecimal('50000.00'),
                requestedTermMonths     : 5L,
                userLogin               : userLogin])
        assert ServiceUtil.isSuccess(appResult)
        GenericValue application = from('LoanApplication')
                .where('loanApplicationId', appResult.loanApplicationId).queryOne()
        assert application.statusId == 'LOANAPP_RECEIVED'
        Map appUpdate = dispatcher.runSync('updateLoanApplication', [
                loanApplicationId: application.loanApplicationId,
                statusId: 'LOANAPP_APPROVED', comments: 'normal update', userLogin: userLogin])
        assert ServiceUtil.isSuccess(appUpdate)
        assert from('LoanApplication').where('loanApplicationId', application.loanApplicationId)
                .queryOne().statusId == 'LOANAPP_RECEIVED'

        approveApplication(application.loanApplicationId, userLogin)
        Map approvedUpdate = dispatcher.runSync('updateLoanApplication', [
                loanApplicationId: application.loanApplicationId,
                requestedPrincipalAmount: new BigDecimal('999999.00'), userLogin: userLogin])
        assert ServiceUtil.isError(approvedUpdate)
        assert from('LoanApplication').where('loanApplicationId', application.loanApplicationId)
                .queryOne().requestedPrincipalAmount.compareTo(new BigDecimal('50000.00')) == 0

        String loanQuoteId = createQuote(application.loanApplicationId,
                new BigDecimal('50000.00'), 5L, userLogin)
        Map quoteUpdate = dispatcher.runSync('updateLoanQuote', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_ACCEPTED', userLogin: userLogin])
        assert ServiceUtil.isError(quoteUpdate)
        assert from('LoanQuote').where('loanQuoteId', loanQuoteId).queryOne().statusId == 'LOANQT_CREATED'

        Map uwResult = dispatcher.runSync('createLoanUnderwriting', [
                loanApplicationId: application.loanApplicationId,
                statusId: 'LOANUW_APPROVED', decision: 'APPROVED', creditScore: 700L,
                userLogin: userLogin])
        assert ServiceUtil.isSuccess(uwResult)
        GenericValue underwriting = from('LoanUnderwriting')
                .where('loanUnderwritingId', uwResult.loanUnderwritingId).queryOne()
        assert underwriting.statusId == 'LOANUW_IN_REVIEW'
        assert underwriting.decision == 'PENDING'
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: underwriting.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        Map lockedUpdate = dispatcher.runSync('updateLoanUnderwriting', [
                loanUnderwritingId: underwriting.loanUnderwritingId,
                comments: 'must not change', userLogin: userLogin])
        assert ServiceUtil.isError(lockedUpdate)
    }

    // Invalid shortcuts are rejected; approved underwriting synchronizes its decision.
    void testQuoteAndUnderwritingStatusTransitions() {
        GenericValue userLogin = admin()
        String loanQuoteId = createQuote('TEST_LOANAPP1', new BigDecimal('120000.00'), 12L, userLogin)

        Map invalid = dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_ACCEPTED', userLogin: userLogin])
        assert ServiceUtil.isError(invalid)

        acceptQuote(loanQuoteId, userLogin)
        assert from('LoanQuote').where('loanQuoteId', loanQuoteId).queryOne().statusId == 'LOANQT_ACCEPTED'
        approveUnderwriting('TEST_LOANAPP1', userLogin)
    }

    // Agreement creation enforces all approvals and snapshots the accepted schedule.
    void testCreateAgreementWithConfirmedSchedule() {
        GenericValue userLogin = admin()
        String appId = 'TEST_LOANAPP_AGR'
        resetApplication(appId)
        String loanQuoteId = createQuote(appId, new BigDecimal('100000.00'), 10L, userLogin)

        Map missingApprovals = dispatcher.runSync('createLoanAgreement', [
                loanQuoteId: loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isError(missingApprovals)

        approveApplication(appId, userLogin)
        acceptQuote(loanQuoteId, userLogin)
        Map missingUnderwriting = dispatcher.runSync('createLoanAgreement', [
                loanQuoteId: loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isError(missingUnderwriting)

        approveUnderwriting(appId, userLogin)
        Map agrResult = dispatcher.runSync('createLoanAgreement', [loanQuoteId: loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(agrResult)

        GenericValue agreement = from('LoanAgreement')
                .where('loanAgreementId', agrResult.loanAgreementId).queryOne()
        assert agreement != null
        assert agreement.statusId == 'LOANAGR_ACTIVE'
        assert from('LoanApplication').where('loanApplicationId', appId).queryOne().statusId == 'LOANAPP_CONTRACTED'
        Map contractedUpdate = dispatcher.runSync('updateLoanApplication', [
                loanApplicationId: appId, applicantPartyId: 'Company', userLogin: userLogin])
        assert ServiceUtil.isError(contractedUpdate)
        assert from('LoanApplication').where('loanApplicationId', appId)
                .queryOne().applicantPartyId == 'TEST_BORROWER1'

        GenericValue finAccount = from('FinAccount').where('finAccountId', agrResult.finAccountId).queryOne()
        assert finAccount != null
        assert finAccount.finAccountTypeId == 'LOAN_ACCOUNT'

        List quoteSchedule = from('LoanRepaymentSchedule').where('loanQuoteId', loanQuoteId)
                .orderBy('installmentNumber').queryList()
        List confirmedSchedule = from('LoanAgreementRepaymentSchedule')
                .where('loanAgreementId', agrResult.loanAgreementId)
                .orderBy('installmentNumber').queryList()
        assert confirmedSchedule.size() == 10
        assert confirmedSchedule.size() == quoteSchedule.size()
        for (int i = 0; i < quoteSchedule.size(); i++) {
            assert confirmedSchedule[i].loanQuoteId == loanQuoteId
            assert confirmedSchedule[i].paymentAmount.compareTo(quoteSchedule[i].paymentAmount) == 0
            assert confirmedSchedule[i].remainingBalance.compareTo(quoteSchedule[i].remainingBalance) == 0
        }

        Map retryResult = dispatcher.runSync('createLoanAgreement', [loanQuoteId: loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(retryResult)
        assert retryResult.loanAgreementId == agrResult.loanAgreementId
        assert retryResult.finAccountId == agrResult.finAccountId
        assert from('LoanAgreement').where('loanQuoteId', loanQuoteId).queryList().size() == 1
    }

    // Billing must consume the agreement snapshot and support safe partial settlement.
    void testBillingAndPayment() {
        GenericValue userLogin = admin()
        String appId = 'TEST_LOANAPP_BILL'
        approveApplication(appId, userLogin)
        approveUnderwriting(appId, userLogin)
        String loanQuoteId = createQuote(appId, new BigDecimal('60000.00'), 6L, userLogin)
        acceptQuote(loanQuoteId, userLogin)

        Map agrResult = dispatcher.runSync('createLoanAgreement', [loanQuoteId: loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(agrResult)
        String loanAgreementId = agrResult.loanAgreementId

        Map billResult = dispatcher.runSync('createLoanInstallmentInvoice',
                [loanAgreementId: loanAgreementId, installmentNumber: 1L, userLogin: userLogin])
        assert ServiceUtil.isSuccess(billResult)
        assert billResult.invoiceCreated
        String invoiceId = billResult.invoiceId
        assert invoiceId != null

        GenericValue link = from('LoanInstallmentInvoice')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 1L).queryOne()
        assert link != null
        assert link.invoiceId == invoiceId
        GenericValue confirmed = from('LoanAgreementRepaymentSchedule')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 1L).queryOne()
        assert link.billedAmount.compareTo(confirmed.paymentAmount) == 0

        Map rebillResult = dispatcher.runSync('createLoanInstallmentInvoice',
                [loanAgreementId: loanAgreementId, installmentNumber: 1L, userLogin: userLogin])
        assert ServiceUtil.isSuccess(rebillResult)
        assert !rebillResult.invoiceCreated
        assert rebillResult.invoiceId == invoiceId
        assert rebillResult.billedAmount.compareTo(link.billedAmount) == 0

        BigDecimal billed = link.getBigDecimal('billedAmount')
        Map wrongInvoice = dispatcher.runSync('receiveLoanPayment', [
                loanAgreementId: loanAgreementId, invoiceId: 'NOT_THIS_LOAN',
                amount: billed, userLogin: userLogin])
        assert ServiceUtil.isError(wrongInvoice)

        BigDecimal firstPayment = billed.divide(new BigDecimal('2'), 2, BigDecimal.ROUND_HALF_UP)
        Map firstPayResult = dispatcher.runSync('receiveLoanPayment', [
                loanAgreementId: loanAgreementId, invoiceId: invoiceId,
                amount: firstPayment, userLogin: userLogin])
        assert ServiceUtil.isSuccess(firstPayResult)

        Map cumulativeOverpayment = dispatcher.runSync('receiveLoanPayment', [
                loanAgreementId: loanAgreementId, invoiceId: invoiceId,
                amount: billed, userLogin: userLogin])
        assert ServiceUtil.isError(cumulativeOverpayment)

        BigDecimal finalPayment = billed.subtract(firstPayment)
        Map finalPayResult = dispatcher.runSync('receiveLoanPayment', [
                loanAgreementId: loanAgreementId, invoiceId: invoiceId,
                amount: finalPayment, userLogin: userLogin])
        assert ServiceUtil.isSuccess(finalPayResult)
        assert finalPayResult.paymentId != null

        List receipts = from('LoanPaymentReceipt')
                .where('loanAgreementId', loanAgreementId, 'invoiceId', invoiceId).queryList()
        assert receipts.size() == 2
        assert receipts.sum { it.getBigDecimal('amount') }.compareTo(billed) == 0
        for (GenericValue receipt : receipts) {
            List paymentTransactions = from('AcctgTrans').where('paymentId', receipt.paymentId).queryList()
            assert !paymentTransactions.isEmpty()
            assert paymentTransactions.every { it.isPosted == 'Y' }
        }
        GenericValue finalApplication = from('PaymentApplication')
                .where('paymentId', finalPayResult.paymentId, 'invoiceId', invoiceId).queryOne()
        Map removeApplication = dispatcher.runSync('removePaymentApplication', [
                paymentApplicationId: finalApplication.paymentApplicationId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(removeApplication)
        assert from('Invoice').where('invoiceId', invoiceId).queryOne().statusId == 'INVOICE_READY'
        assert from('PaymentApplication').where('invoiceId', invoiceId).queryList()
                .sum { it.amountApplied }.compareTo(firstPayment) == 0

        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanAgreementStatus', [
                loanAgreementId: loanAgreementId, statusId: 'LOANAGR_CLOSED', userLogin: userLogin]))
        Map inactiveBilling = dispatcher.runSync('createLoanInstallmentInvoice',
                [loanAgreementId: loanAgreementId, installmentNumber: 2L, userLogin: userLogin])
        assert ServiceUtil.isError(inactiveBilling)
        Map inactivePayment = dispatcher.runSync('receiveLoanPayment', [
                loanAgreementId: loanAgreementId, invoiceId: invoiceId,
                amount: new BigDecimal('1.00'), userLogin: userLogin])
        assert ServiceUtil.isError(inactivePayment)
    }

    // Monthly processing must post one balanced GL accrual and bill due installments once.
    void testMonthlyAccrualAndRecurringBillingBatch() {
        GenericValue userLogin = admin()
        String appId = 'TEST_LOANAPP_ACCRUAL'
        approveApplication(appId, userLogin)
        approveUnderwriting(appId, userLogin)
        java.time.LocalDate firstDueDay = java.time.LocalDate.now().minusMonths(1)
        java.sql.Timestamp firstPaymentDate = java.sql.Timestamp.valueOf(firstDueDay.atStartOfDay())
        java.sql.Timestamp agreementDate = java.sql.Timestamp.valueOf(firstDueDay.minusDays(8).atStartOfDay())
        Map quoteResult = dispatcher.runSync('createLoanQuote', [
                loanApplicationId: appId,
                principalAmount: new BigDecimal('36000.00'),
                annualInterestRate: new BigDecimal('0.120000'),
                termMonths: 6L,
                firstPaymentDate: firstPaymentDate,
                userLogin: userLogin])
        assert ServiceUtil.isSuccess(quoteResult)
        String loanQuoteId = quoteResult.loanQuoteId
        acceptQuote(loanQuoteId, userLogin)
        Map agreementResult = dispatcher.runSync('createLoanAgreement', [
                loanQuoteId: loanQuoteId, agreementDate: agreementDate, userLogin: userLogin])
        assert ServiceUtil.isSuccess(agreementResult)
        String loanAgreementId = agreementResult.loanAgreementId
        GenericValue firstInstallment = from('LoanAgreementRepaymentSchedule')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 1L).queryOne()

        java.time.LocalDate firstMonthEndDay = firstDueDay.withDayOfMonth(firstDueDay.lengthOfMonth())
        java.sql.Timestamp processingDate = java.sql.Timestamp.valueOf(firstMonthEndDay.atStartOfDay())
        Map batchResult = dispatcher.runSync('runLoanMonthlyAccountingBatch', [
                processingDate: processingDate,
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(batchResult)
        assert batchResult.processedAgreementCount == 1L
        assert batchResult.createdAccrualCount == 2L
        assert batchResult.createdInvoiceCount == 1L

        List firstMonthAccruals = from('LoanAccrual').where('loanAgreementId', loanAgreementId)
                .orderBy('fromDate').queryList()
        assert firstMonthAccruals.size() == 2
        assert firstMonthAccruals[1].fromDate.time == firstMonthAccruals[0].thruDate.time + 1L
        GenericValue accrual = firstMonthAccruals[0]
        assert accrual != null
        assert accrual.outstandingPrincipal.compareTo(new BigDecimal('36000.00')) == 0
        BigDecimal expectedDailyInterest = new BigDecimal('36000.00')
                .multiply(new BigDecimal('0.120000')).multiply(new BigDecimal('9'))
                .divide(new BigDecimal('365'), 2, BigDecimal.ROUND_HALF_UP)
        assert accrual.interestAmount.compareTo(expectedDailyInterest) == 0
        assert accrual.acctgTransId != null
        GenericValue acctgTrans = from('AcctgTrans').where('acctgTransId', accrual.acctgTransId).queryOne()
        assert acctgTrans != null
        assert acctgTrans.isPosted == 'Y'
        List entries = from('AcctgTransEntry').where('acctgTransId', accrual.acctgTransId).queryList()
        assert entries.size() == 2
        assert entries.find { it.debitCreditFlag == 'D' }.amount.compareTo(accrual.interestAmount) == 0
        assert entries.find { it.debitCreditFlag == 'C' }.amount.compareTo(accrual.interestAmount) == 0

        GenericValue installmentInvoice = from('LoanInstallmentInvoice')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 1L).queryOne()
        assert installmentInvoice != null
        assert installmentInvoice.billedAmount.compareTo(firstInstallment.paymentAmount) == 0
        assert installmentInvoice.accruedInterestAppliedAmount.compareTo(accrual.interestAmount) == 0
        GenericValue invoice = from('Invoice').where('invoiceId', installmentInvoice.invoiceId).queryOne()
        assert invoice.statusId == 'INVOICE_READY'
        List invoiceItems = from('InvoiceItem').where('invoiceId', invoice.invoiceId).queryList()
        assert invoiceItems.find { it.invoiceItemTypeId == 'LOAN_PRINCIPAL' }.overrideGlAccountId == '120000'
        assert invoiceItems.findAll { it.invoiceItemTypeId == 'INV_INTRST_CHRG' }
                .sum { it.amount }.compareTo(firstInstallment.interestAmount) == 0
        GenericValue invoiceTrans = from('AcctgTrans').where('invoiceId', invoice.invoiceId).queryFirst()
        assert invoiceTrans != null
        assert invoiceTrans.isPosted == 'Y'
        List invoiceEntries = from('AcctgTransEntry').where('acctgTransId', invoiceTrans.acctgTransId).queryList()
        assert invoiceEntries.findAll { it.debitCreditFlag == 'D' }.sum { it.amount }
                .compareTo(installmentInvoice.billedAmount) == 0
        assert invoiceEntries.findAll { it.debitCreditFlag == 'C' }.sum { it.amount }
                .compareTo(installmentInvoice.billedAmount) == 0
        BigDecimal totalInterestIncome = entries.findAll {
            it.debitCreditFlag == 'C' && it.glAccountId == '810000'
        }.sum(BigDecimal.ZERO) { it.amount } + invoiceEntries.findAll {
            it.debitCreditFlag == 'C' && it.glAccountId == '810000'
        }.sum(BigDecimal.ZERO) { it.amount }
        assert totalInterestIncome.compareTo(firstInstallment.interestAmount) == 0

        Map retryBatch = dispatcher.runSync('runLoanMonthlyAccountingBatch', [
                processingDate: processingDate,
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(retryBatch)
        assert retryBatch.createdAccrualCount == 0L
        assert retryBatch.createdInvoiceCount == 0L
        assert from('LoanAccrual').where('loanAgreementId', loanAgreementId).queryList().size() == 2
        assert from('LoanInstallmentInvoice').where('loanAgreementId', loanAgreementId).queryList().size() == 1

        Map retryAccrual = dispatcher.runSync('runMonthlyInterestAccrual', [
                loanAgreementId: loanAgreementId, accrualDate: firstInstallment.dueDate,
                userLogin: userLogin])
        assert ServiceUtil.isSuccess(retryAccrual)
        assert !retryAccrual.accrualCreated
        assert retryAccrual.loanAccrualId == accrual.loanAccrualId

        GenericValue secondInstallment = from('LoanAgreementRepaymentSchedule')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 2L).queryOne()
        java.sql.Timestamp secondProcessingDate = java.sql.Timestamp.valueOf(
                secondInstallment.dueDate.toLocalDateTime().toLocalDate().atStartOfDay())
        Map secondBatch = dispatcher.runSync('runLoanMonthlyAccountingBatch', [
                processingDate: secondProcessingDate,
                loanAgreementId: loanAgreementId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(secondBatch)
        assert secondBatch.createdAccrualCount == 1L
        assert secondBatch.createdInvoiceCount == 1L
        GenericValue secondInvoiceLink = from('LoanInstallmentInvoice')
                .where('loanAgreementId', loanAgreementId, 'installmentNumber', 2L).queryOne()
        assert secondInvoiceLink.accruedInterestAppliedAmount.compareTo(secondInstallment.interestAmount) == 0
        List secondInterestItems = from('InvoiceItem').where('invoiceId', secondInvoiceLink.invoiceId,
                'invoiceItemTypeId', 'INV_INTRST_CHRG').queryList()
        assert secondInterestItems.every { it.overrideGlAccountId == '121800' }

        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanAgreementStatus', [
                loanAgreementId: loanAgreementId, statusId: 'LOANAGR_CLOSED', userLogin: userLogin]))
        Map inactiveAccrual = dispatcher.runSync('runMonthlyInterestAccrual', [
                loanAgreementId: loanAgreementId, accrualDate: firstInstallment.dueDate,
                userLogin: userLogin])
        assert ServiceUtil.isError(inactiveAccrual)
    }
}
