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
import org.apache.ofbiz.finance.lease.LeaseProfitAllocator
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L4: lease billing from the confirmed schedule, release of the deferred lease
 * profit (interest method), the monthly batch and payment application.
 *
 * Uses the USD lease product TEST_LEASEPROD_USD (base currency of Company, no FX in GL),
 * a new lessee per test and lease starts in the past relative to today so that the
 * installments are due.
 */
class LeaseAccountingTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseAccountingTests(String name) {
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

    /** New lessee with a contracted (not yet accepted) lease starting on {@code start}. */
    private String contractedLease(GenericValue userLogin, LocalDate start, long termMonths, BigDecimal calcInterestRate = null) {
        String lessee = 'TEST_LSACT_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease accounting test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
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
        if (calcInterestRate != null) {
            quoteInput.calcInterestRate = calcInterestRate
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

    private void accept(String agreementId, LocalDate start, GenericValue userLogin) {
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
    }

    private String activeLease(GenericValue userLogin, LocalDate start, long termMonths, BigDecimal calcInterestRate = null) {
        String agreementId = contractedLease(userLogin, start, termMonths, calcInterestRate)
        accept(agreementId, start, userLogin)
        return agreementId
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

    private static BigDecimal side(Map<String, BigDecimal> sums, String flag) {
        return sums.findAll { it.key.endsWith(':' + flag) }.values().inject(BigDecimal.ZERO) { a, b -> a.add(b) }
    }

    private List<GenericValue> schedule(String agreementId) {
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision).orderBy('installmentNumber').queryList()
    }

    private Map balance(String agreementId, GenericValue userLogin) {
        Map result = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(result)
        return result
    }

    // Billing: only after acceptance, from the confirmed schedule, no sales at billing, idempotent; batch in due order.
    void testBillingFromConfirmedSchedule() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(3)
        String agreementId = contractedLease(userLogin, start, 60L)

        // Before acceptance: no billing, no profit release, no payment.
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: 1L, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId,
                userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId,
                amount: new BigDecimal('100.00'), userLogin: userLogin]))
        accept(agreementId, start, userLogin)
        List<GenericValue> rows = schedule(agreementId)

        Map bill = dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: 1L, userLogin: userLogin])
        ok(bill)
        assert bill.invoiceCreated == true
        GenericValue row1 = rows[0]
        BigDecimal expected = row1.leaseAmount.add(row1.taxAmount)
        assert row1.leaseAmount.compareTo(new BigDecimal('18000.00')) == 0
        assert row1.taxAmount.compareTo(new BigDecimal('1800.00')) == 0
        assert bill.billedAmount.compareTo(expected) == 0
        GenericValue invoice = from('Invoice').where('invoiceId', bill.invoiceId).queryOne()
        assert invoice.invoiceTypeId == 'SALES_INVOICE'
        assert invoice.statusId == 'INVOICE_READY'
        assert invoice.partyIdFrom == 'Company'
        assert invoice.dueDate == row1.dueDate
        List items = from('InvoiceItem').where('invoiceId', bill.invoiceId).orderBy('invoiceItemSeqId').queryList()
        assert items.collect { it.invoiceItemTypeId } == ['LEASE_PAYMENT', 'LEASE_CONS_TAX']
        GenericValue link = from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId, 'installmentNumber', 1L).queryOne()
        assert link.invoiceId == bill.invoiceId
        assert link.scheduleRevisionNumber == 2L                               // the revision dated from the lease start

        // GL: Dr receivable / Cr lease investment (lease payment) + tax received; no sales.
        GenericValue trans = from('AcctgTrans').where('invoiceId', bill.invoiceId).queryFirst()
        assert trans.isPosted == 'Y'
        Map<String, BigDecimal> sums = entrySums(trans.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['120000:D'].compareTo(expected) == 0
        assert sums['128500:C'].compareTo(row1.leaseAmount) == 0
        assert sums['224500:C'].compareTo(row1.taxAmount) == 0
        assert sums['403500:C'] == null

        // Idempotent; not-yet-due installment rejected.
        Map again = dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: 1L, userLogin: userLogin])
        ok(again)
        assert again.invoiceCreated == false && again.invoiceId == bill.invoiceId
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: 6L, userLogin: userLogin]))

        // Batch: bills every installment due through today (in advance: 4 dues), then releases profit.
        long due = rows.count { !it.dueDate.after(ts(LocalDate.now())) }
        assert due == 4L
        Map batch = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(batch)
        assert batch.processedAgreementCount == 1L
        assert batch.createdInvoiceCount == due - 1
        assert batch.createdRecognitionCount >= 3L
        List links = from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).orderBy('installmentNumber').queryList()
        assert links.size() == due
        (0..<(int) due).each { int i ->
            assert links[i].leaseAmount.compareTo(rows[i].leaseAmount) == 0
            assert links[i].billedAmount.compareTo(rows[i].leaseAmount.add(rows[i].taxAmount)) == 0
        }
        Map rerun = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(rerun)
        assert rerun.createdInvoiceCount == 0L
        assert rerun.createdRecognitionCount == 0L

        // Billing alone does not reduce the outstanding principal.
        Map bal = balance(agreementId, userLogin)
        assert bal.billedCount == due
        assert bal.outstandingPrincipal.compareTo(COST) == 0
        assert bal.unpaidBilledAmount.compareTo(bal.billedAmount) == 0
    }

    // Deferred profit release: interest method, month records, incremental re-runs, total = deferred profit.
    void testDeferredProfitReleaseOverFullTerm() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(14).withDayOfMonth(10)
        String agreementId = activeLease(userLogin, start, 12L, new BigDecimal('0.060000'))
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        BigDecimal totalInterest = agreement.totalInterestAmount
        assert totalInterest.signum() > 0
        List<GenericValue> rows = schedule(agreementId)

        // Interest method in advance: period 1 earns the interest of installment 2.
        LocalDate period1End = start.plusMonths(1).minusDays(1)
        LeaseProfitAllocator allocator = LeaseAccountingServices.allocator(delegator, agreement)
        assert allocator.cumulativeThrough(period1End).compareTo(rows[1].interestPortion) == 0
        assert allocator.total().compareTo(totalInterest) == 0

        // Mid-month run: one record per calendar month, from the lease start.
        LocalDate d1 = start.plusMonths(1).plusDays(5)
        Map r1 = dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId, recognitionDate: ts(d1), userLogin: userLogin])
        ok(r1)
        assert r1.createdCount == 2L
        assert r1.recognizedAmount.compareTo(allocator.cumulativeThrough(d1)) == 0
        List recs = from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).orderBy('fromDate').queryList()
        assert recs[0].fromDate == ts(start)
        assert recs[0].thruDate == ts(start.withDayOfMonth(start.lengthOfMonth()))
        assert recs[1].fromDate == ts(start.withDayOfMonth(start.lengthOfMonth()).plusDays(1))
        assert recs[1].thruDate == ts(d1)

        // Same day again: nothing new. Later day: continues from the day after.
        Map same = dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId, recognitionDate: ts(d1), userLogin: userLogin])
        ok(same)
        assert same.createdCount == 0L
        assert same.recognizedAmount.signum() == 0
        LocalDate d2 = d1.plusDays(10)
        Map r2 = dispatcher.runSync('recognizeLeaseProfit', [leaseAgreementId: agreementId, recognitionDate: ts(d2), userLogin: userLogin])
        ok(r2)
        GenericValue next = from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).orderBy('-thruDate').queryFirst()
        assert next.fromDate == ts(d1.plusDays(1))
        assert r2.cumulativeAmount.compareTo(allocator.cumulativeThrough(d2)) == 0
        assert r2.cumulativeAmount.compareTo(r1.recognizedAmount.add(r2.recognizedAmount)) == 0

        // Each release: Dr deferred lease profit / Cr release, balanced and posted.
        from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).queryList().each { GenericValue rec ->
            if (rec.amount.signum() != 0) {
                Map<String, BigDecimal> s = entrySums(rec.acctgTransId)
                assert s['128700:D'].compareTo(rec.amount) == 0
                assert s['403600:C'].compareTo(rec.amount) == 0
                assert from('AcctgTrans').where('acctgTransId', rec.acctgTransId).queryOne().isPosted == 'Y'
            }
        }

        // Batch through today (after the lease end): every installment billed, deferred profit fully released.
        Map batch = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(batch)
        assert batch.createdInvoiceCount == 12L
        List all = from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).orderBy('fromDate').queryList()
        BigDecimal released = all.inject(BigDecimal.ZERO) { BigDecimal a, GenericValue r -> a.add(r.amount) }
        assert released.compareTo(totalInterest) == 0
        assert all[-1].cumulativeAmount.compareTo(totalInterest) == 0
        (1..<all.size()).each { int i ->                                         // contiguous, no overlap
            assert all[i].fromDate == ts(LeaseAccountingServices.toDate(all[i - 1].thruDate).plusDays(1))
        }
        Map bal = balance(agreementId, userLogin)
        assert bal.deferredProfitBalance.signum() == 0
        assert bal.unbilledLeaseAmount.signum() == 0
        assert bal.billedLeaseAmount.compareTo(agreement.totalLeaseAmount) == 0

        // Deferred profit: released total = start credit; investment: start debit = billed credits (no residual).
        GenericValue acceptance = from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne()
        Map<String, BigDecimal> startSums = entrySums(acceptance.startAcctgTransId)
        assert startSums['128700:C'].compareTo(released) == 0
        BigDecimal billedCredit = BigDecimal.ZERO
        from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryList().each { GenericValue l ->
            billedCredit = billedCredit.add(entrySums(from('AcctgTrans').where('invoiceId', l.invoiceId).queryFirst().acctgTransId)['128500:C'])
        }
        assert startSums['128500:D'].compareTo(billedCredit) == 0

        // Nothing more after the end.
        Map after = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(after)
        assert after.createdInvoiceCount == 0L && after.createdRecognitionCount == 0L
    }

    // Payments: partial payment, application oldest first, tax -> interest -> principal, removal.
    void testPaymentApplicationAndOutstandingPrincipal() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(2)
        String agreementId = activeLease(userLogin, start, 60L)
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        List links = from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).orderBy('installmentNumber').queryList()
        assert links.size() == 3
        GenericValue inv1 = links[0]
        GenericValue inv2 = links[1]
        assert inv1.interestPortion.signum() == 0                               // in advance: first installment has no interest

        // Rejected: zero, wrong invoice, more than the invoice's open amount, more than all open invoices.
        assert ServiceUtil.isError(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId,
                amount: BigDecimal.ZERO, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId,
                invoiceId: 'NOT_A_LEASE_INVOICE', amount: new BigDecimal('10.00'), userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId,
                invoiceId: inv1.invoiceId, amount: inv1.billedAmount.add(BigDecimal.ONE), userLogin: userLogin]))
        BigDecimal allOpen = links.inject(BigDecimal.ZERO) { BigDecimal a, GenericValue l -> a.add(l.billedAmount) }
        assert ServiceUtil.isError(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId,
                amount: allOpen.add(BigDecimal.ONE), userLogin: userLogin]))

        // Partial payment of invoice 1: tax first, then principal (no interest on installment 1).
        BigDecimal partial = inv1.taxAmount.add(new BigDecimal('500.00'))
        Map p1 = dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, invoiceId: inv1.invoiceId,
                amount: partial, userLogin: userLogin])
        ok(p1)
        GenericValue payment = from('Payment').where('paymentId', p1.paymentId).queryOne()
        assert payment.paymentTypeId == 'CUSTOMER_PAYMENT' && payment.statusId == 'PMNT_RECEIVED'
        assert from('AcctgTrans').where('paymentId', p1.paymentId).queryList().every { it.isPosted == 'Y' }
        Map bal = balance(agreementId, userLogin)
        assert bal.collectedTaxAmount.compareTo(inv1.taxAmount) == 0
        assert bal.collectedPrincipalAmount.compareTo(new BigDecimal('500.00')) == 0
        assert bal.outstandingPrincipal.compareTo(COST.subtract(new BigDecimal('500.00'))) == 0
        assert from('Invoice').where('invoiceId', inv1.invoiceId).queryOne().statusId == 'INVOICE_READY'

        // No invoice given: the rest of invoice 1, then invoice 2 in full.
        BigDecimal rest1 = inv1.billedAmount.subtract(partial)
        Map p2 = dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId,
                amount: rest1.add(inv2.billedAmount), userLogin: userLogin])
        ok(p2)
        assert p2.appliedInvoiceCount == 2L
        assert from('Invoice').where('invoiceId', inv1.invoiceId).queryOne().statusId == 'INVOICE_PAID'
        assert from('Invoice').where('invoiceId', inv2.invoiceId).queryOne().statusId == 'INVOICE_PAID'
        bal = balance(agreementId, userLogin)
        BigDecimal principalPaid = inv1.principalPortion.add(inv2.principalPortion)
        assert bal.collectedPrincipalAmount.compareTo(principalPaid) == 0
        assert bal.collectedInterestAmount.compareTo(inv2.interestPortion) == 0
        assert bal.outstandingPrincipal.compareTo(COST.subtract(principalPaid)) == 0
        assert bal.unpaidBilledAmount.compareTo(links[2].billedAmount) == 0
        assert from('LeasePaymentReceipt').where('leaseAgreementId', agreementId).queryCount() == 2

        // Removing the application to invoice 2 restores its open amount and the principal.
        GenericValue application = from('PaymentApplication').where('paymentId', p2.paymentId, 'invoiceId', inv2.invoiceId).queryFirst()
        ok(dispatcher.runSync('removePaymentApplication', [paymentApplicationId: application.paymentApplicationId,
                userLogin: userLogin]))
        assert from('Invoice').where('invoiceId', inv2.invoiceId).queryOne().statusId == 'INVOICE_READY'
        bal = balance(agreementId, userLogin)
        assert bal.outstandingPrincipal.compareTo(COST.subtract(inv1.principalPortion)) == 0
        assert bal.unpaidBilledAmount.compareTo(links[2].billedAmount.add(inv2.billedAmount)) == 0
    }
}
