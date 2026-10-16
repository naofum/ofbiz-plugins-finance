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
import org.apache.ofbiz.accounting.invoice.InvoiceWorker
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L15c: advance lease payments (前受リース料) and security deposits (保証金), SPEC_LEASE.md 19.
 *
 * USD lease product TEST_LEASEPROD_USD (payments in advance), a new lessee per test.
 * GL: advance lease payments 224800, security deposits 239500, accounts receivable 120000.
 */
class LeaseAdvanceTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseAdvanceTests(String name) {
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

    /** New lessee with a contracted lease starting on {@code start}; the asset is purchased when {@code purchase}. */
    private String contractedLease(GenericValue userLogin, LocalDate start, long termMonths, boolean purchase = true) {
        String lessee = 'TEST_LSADV_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease advance test lessee'])
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
        // Interest-rate method (the product's rate factor is for 60 months).
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: termMonths,
                leaseStartDate: ts(start), calcInterestRate: new BigDecimal('0.030000'), userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        if (purchase) {
            ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreement.leaseAgreementId, assetSeqId: '00001',
                    purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        }
        return agreement.leaseAgreementId
    }

    private void accept(String agreementId, LocalDate start, GenericValue userLogin) {
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
    }

    private Map receive(String agreementId, String type, BigDecimal amount, GenericValue userLogin) {
        return dispatcher.runSync('receiveLeaseAdvance', [leaseAgreementId: agreementId, advanceTypeEnumId: type,
                amount: amount, userLogin: userLogin])
    }

    private Map bill(String agreementId, long installment, GenericValue userLogin) {
        Map result = dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: installment, userLogin: userLogin])
        ok(result)
        return result
    }

    private Map balance(String agreementId, GenericValue userLogin) {
        Map result = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(result)
        return result
    }

    /** Sum of the entries of the transactions of a payment by "glAccountId:D|C" (optionally of one transaction type). */
    private Map<String, BigDecimal> paymentEntrySums(String paymentId, String acctgTransTypeId = null) {
        Map<String, BigDecimal> sums = [:]
        from('AcctgTrans').where('paymentId', paymentId).queryList().each { GenericValue t ->
            if (acctgTransTypeId != null && t.acctgTransTypeId != acctgTransTypeId) {
                return
            }
            assert t.isPosted == 'Y'
            from('AcctgTransEntry').where('acctgTransId', t.acctgTransId).queryList().each { GenericValue e ->
                String key = e.glAccountId + ':' + e.debitCreditFlag
                sums[key] = (sums[key] ?: BigDecimal.ZERO).add(e.getBigDecimal('amount'))
            }
        }
        return sums
    }

    private BigDecimal appliedFrom(String paymentId, String invoiceId) {
        return from('PaymentApplication').where('paymentId', paymentId, 'invoiceId', invoiceId).queryList()
                .inject(BigDecimal.ZERO) { BigDecimal a, GenericValue p -> a.add(p.getBigDecimal('amountApplied')) }
    }

    private BigDecimal notApplied(String invoiceId) {
        return InvoiceWorker.getInvoiceNotApplied(from('Invoice').where('invoiceId', invoiceId).queryOne())
    }

    // Advance lease payment received before the start: liability, applied at billing oldest first, carried to the next installment.
    void testAdvanceLeasePaymentAppliedAtBilling() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1)
        String agreementId = contractedLease(userLogin, start, 24L)
        List<GenericValue> rev1 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', 1L).orderBy('installmentNumber').queryList()
        BigDecimal first = rev1[0].leaseAmount.add(rev1[0].taxAmount ?: BigDecimal.ZERO)
        BigDecimal advance = first.add(new BigDecimal('1000.00'))

        Map received = receive(agreementId, 'LSADV_LEASE', advance, userLogin)
        ok(received)
        assert received.appliedAmount.compareTo(BigDecimal.ZERO) == 0
        GenericValue payment = from('Payment').where('paymentId', received.paymentId).queryOne()
        assert payment.paymentTypeId == 'CUSTOMER_DEPOSIT'
        assert payment.statusId == 'PMNT_RECEIVED'
        assert payment.overrideGlAccountId == '224800'
        Map<String, BigDecimal> receiptSums = paymentEntrySums(received.paymentId)
        assert receiptSums['224800:C'].compareTo(advance) == 0                    // Cr advance lease payments
        assert receiptSums.findAll { it.key.endsWith(':D') }.values().sum().compareTo(advance) == 0
        GenericValue receipt = from('LeaseAdvanceReceipt').where('leaseAdvanceReceiptId', received.leaseAdvanceReceiptId).queryOne()
        assert receipt.statusId == 'LSADVST_RECEIVED' && receipt.advanceTypeEnumId == 'LSADV_LEASE'
        assert ServiceUtil.isError(receive(agreementId, 'NO_SUCH_TYPE', advance, userLogin))

        accept(agreementId, start, userLogin)
        Map bill1 = bill(agreementId, 1L, userLogin)
        BigDecimal billed1 = bill1.billedAmount
        BigDecimal rest = advance.subtract(billed1)
        assert rest.signum() > 0
        assert appliedFrom(received.paymentId, bill1.invoiceId).compareTo(billed1) == 0
        assert from('Invoice').where('invoiceId', bill1.invoiceId).queryOne().statusId == 'INVOICE_PAID'
        Map<String, BigDecimal> applSums = paymentEntrySums(received.paymentId, 'PAYMENT_APPL')
        assert applSums['224800:D'].compareTo(billed1) == 0                       // Dr advance lease payments
        assert applSums['120000:C'].compareTo(billed1) == 0                       // Cr accounts receivable
        Map bal = balance(agreementId, userLogin)
        assert bal.advanceBalance.compareTo(rest) == 0
        assert bal.unpaidBilledAmount.compareTo(BigDecimal.ZERO) == 0
        assert bal.collectedPrincipalAmount.signum() > 0                          // collected through the advance

        // The rest goes to the next installment; the receipt is then fully applied.
        Map bill2 = bill(agreementId, 2L, userLogin)
        assert appliedFrom(received.paymentId, bill2.invoiceId).compareTo(rest) == 0
        assert notApplied(bill2.invoiceId).compareTo(bill2.billedAmount.subtract(rest)) == 0
        receipt.refresh()
        assert receipt.statusId == 'LSADVST_APPLIED'
        assert receipt.appliedAmount.compareTo(advance) == 0
        bal = balance(agreementId, userLogin)
        assert bal.advanceBalance.compareTo(BigDecimal.ZERO) == 0
        assert bal.advanceAppliedAmount.compareTo(advance) == 0

        // Received while ACTIVE: applied at once to the invoice already open.
        Map more = receive(agreementId, 'LSADV_LEASE', new BigDecimal('500.00'), userLogin)
        ok(more)
        assert more.appliedAmount.compareTo(new BigDecimal('500.00')) == 0
        assert appliedFrom(more.paymentId, bill2.invoiceId).compareTo(new BigDecimal('500.00')) == 0
        // A deposit is not applied automatically.
        Map deposit = receive(agreementId, 'LSADV_DEPOSIT', new BigDecimal('20000.00'), userLogin)
        ok(deposit)
        assert deposit.appliedAmount.compareTo(BigDecimal.ZERO) == 0
        assert paymentEntrySums(deposit.paymentId)['239500:C'].compareTo(new BigDecimal('20000.00')) == 0
        assert balance(agreementId, userLogin).depositBalance.compareTo(new BigDecimal('20000.00')) == 0
        // Settlement only at the end (assets still leased).
        assert ServiceUtil.isError(dispatcher.runSync('settleLeaseAdvances', [leaseAgreementId: agreementId, userLogin: userLogin]))
    }

    // Deposit: the lease is not completed until it is settled; the settlement refunds it (Dr deposits / Cr cash).
    void testDepositSettlementBeforeCompletion() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(13).withDayOfMonth(1)
        String agreementId = contractedLease(userLogin, start, 12L)
        BigDecimal amount = new BigDecimal('50000.00')
        Map deposit = receive(agreementId, 'LSADV_DEPOSIT', amount, userLogin)
        ok(deposit)
        accept(agreementId, start, userLogin)
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        Map bal = balance(agreementId, userLogin)
        assert bal.billedCount == 12L
        assert bal.depositBalance.compareTo(amount) == 0                          // not applied at billing
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: bal.unpaidBilledAmount,
                userLogin: userLogin]))

        Map returned = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                userLogin: userLogin])
        ok(returned)
        assert returned.agreementCompleted == false                               // deposit not settled
        assert from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne().statusId == 'LEASEAGR_ACTIVE'
        Map complete = dispatcher.runSync('completeLeaseAgreement', [leaseAgreementId: agreementId, userLogin: userLogin])
        assert ServiceUtil.isError(complete)

        Map settled = dispatcher.runSync('settleLeaseAdvances', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(settled)
        assert settled.appliedAmount.compareTo(BigDecimal.ZERO) == 0
        assert settled.refundedAmount.compareTo(amount) == 0
        assert settled.agreementCompleted == true
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert agreement.statusId == 'LEASEAGR_COMPLETED'
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne().statusId == 'FINCTR_COMPLETED'
        GenericValue receipt = from('LeaseAdvanceReceipt').where('leaseAdvanceReceiptId', deposit.leaseAdvanceReceiptId).queryOne()
        assert receipt.statusId == 'LSADVST_REFUNDED'
        assert receipt.refundedAmount.compareTo(amount) == 0
        GenericValue refund = from('Payment').where('paymentId', receipt.refundPaymentId).queryOne()
        assert refund.paymentTypeId == 'CUSTOMER_REFUND'
        assert refund.statusId == 'PMNT_SENT'
        assert refund.partyIdTo == agreement.lesseePartyId
        Map<String, BigDecimal> refundSums = paymentEntrySums(refund.paymentId)
        assert refundSums['239500:D'].compareTo(amount) == 0                      // Dr security deposits
        assert refundSums.findAll { it.key.endsWith(':C') }.values().sum().compareTo(amount) == 0   // Cr cash
        assert from('PaymentApplication').where('paymentId', refund.paymentId, 'toPaymentId', deposit.paymentId)
                .queryCount() == 1
        assert balance(agreementId, userLogin).depositBalance.compareTo(BigDecimal.ZERO) == 0
        // Nothing left: a second settlement is not possible (completed).
        assert ServiceUtil.isError(dispatcher.runSync('settleLeaseAdvances', [leaseAgreementId: agreementId, userLogin: userLogin]))
    }

    // After a termination the deposit is applied to the unpaid invoices (installments, then the damages).
    void testDepositAppliedAfterTermination() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1)
        String agreementId = contractedLease(userLogin, start, 24L)
        accept(agreementId, start, userLogin)
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        BigDecimal unpaid = balance(agreementId, userLogin).unpaidBilledAmount
        BigDecimal amount = new BigDecimal('30000.00')
        Map deposit = receive(agreementId, 'LSADV_DEPOSIT', amount, userLogin)
        ok(deposit)

        Map terminated = dispatcher.runSync('terminateLeaseAgreement', [leaseAgreementId: agreementId,
                reason: 'Advance test', userLogin: userLogin])
        ok(terminated)
        String damagesInvoiceId = terminated.invoiceId
        assert damagesInvoiceId
        assert notApplied(damagesInvoiceId).compareTo(terminated.damagesAmount) == 0   // deposit not applied automatically

        Map settled = dispatcher.runSync('settleLeaseAdvances', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(settled)
        BigDecimal open = unpaid.add(terminated.damagesAmount)
        BigDecimal expectedApplied = open.min(amount)
        assert settled.appliedAmount.compareTo(expectedApplied) == 0
        assert settled.refundedAmount.compareTo(amount.subtract(expectedApplied)) == 0
        assert settled.agreementCompleted == false                                // asset not recovered yet
        // Oldest first: the installment invoices are paid before the damages.
        LeaseAccountingServices.invoiceTargets(delegator, agreementId).each { Object[] t ->
            if (t[0] != damagesInvoiceId && unpaid.compareTo(amount) <= 0) {
                assert notApplied(t[0] as String).compareTo(BigDecimal.ZERO) == 0
            }
        }
        if (unpaid.compareTo(amount) < 0) {
            assert appliedFrom(deposit.paymentId, damagesInvoiceId).compareTo(amount.subtract(unpaid).min(terminated.damagesAmount)) == 0
        }
        Map<String, BigDecimal> applSums = paymentEntrySums(deposit.paymentId, 'PAYMENT_APPL')
        assert applSums['239500:D'].compareTo(expectedApplied) == 0
        assert applSums['120000:C'].compareTo(expectedApplied) == 0
        Map bal = balance(agreementId, userLogin)
        assert bal.depositBalance.compareTo(BigDecimal.ZERO) == 0
        assert bal.depositAppliedAmount.compareTo(expectedApplied) == 0
        assert bal.unpaidBilledAmount.compareTo(open.subtract(expectedApplied)) == 0
    }

    // Before the start: the deposit must be refunded before a cancellation; no receipt after the cancellation.
    void testRefundBeforeCancellation() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now()
        String agreementId = contractedLease(userLogin, start, 24L, false)
        BigDecimal amount = new BigDecimal('10000.00')
        ok(receive(agreementId, 'LSADV_DEPOSIT', amount, userLogin))
        ok(receive(agreementId, 'LSADV_LEASE', new BigDecimal('2000.00'), userLogin))
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_CANCELLED', userLogin: userLogin]))

        Map settled = dispatcher.runSync('settleLeaseAdvances', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(settled)
        assert settled.appliedAmount.compareTo(BigDecimal.ZERO) == 0
        assert settled.refundedAmount.compareTo(new BigDecimal('12000.00')) == 0
        assert settled.agreementCompleted == false
        List<GenericValue> receipts = from('LeaseAdvanceReceipt').where('leaseAgreementId', agreementId).queryList()
        assert receipts.size() == 2 && receipts.every { it.statusId == 'LSADVST_REFUNDED' && it.refundPaymentId }
        GenericValue advanceRefund = from('Payment').where('paymentId',
                receipts.find { it.advanceTypeEnumId == 'LSADV_LEASE' }.refundPaymentId).queryOne()
        assert paymentEntrySums(advanceRefund.paymentId)['224800:D'].compareTo(new BigDecimal('2000.00')) == 0

        ok(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId, statusId: 'LEASEAGR_CANCELLED',
                userLogin: userLogin]))
        assert ServiceUtil.isError(receive(agreementId, 'LSADV_DEPOSIT', amount, userLogin))
    }
}
