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
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.DelinquencyComputation
import org.apache.ofbiz.finance.lease.LeaseContractAdapter
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L6: lease delinquency through the common engine (lease contract adapter):
 * detection and classification, late fee on the overdue lease payments (tax
 * included / excluded), write-off and legal flag, dunning notice, and the customer
 * summary across a loan and a lease of the same customer.
 *
 * Each test creates its own USD lease product (late fee 14.6%, classification
 * D1-D4 like TEST_LOANPROD1) and a new lessee; lease starts are relative to today.
 */
class LeaseDelinquencyTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseDelinquencyTests(String name) {
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

    private String newParty(String prefix) {
        String partyId = prefix + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: partyId, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: partyId, groupName: 'Lease delinquency test customer'])
        return partyId
    }

    /** USD lease product with a late fee rate and the D1-D4 classification (write-off from 61 days, legal from 91). */
    private String delinquencyProduct(GenericValue userLogin) {
        Map product = dispatcher.runSync('createFinanceProduct', [productName: 'Test Lease Delinquency USD',
                financialProductTypeId: 'LEASE_FIN_NONTRANS', currencyUomId: 'USD', minTermMonths: 12L, maxTermMonths: 72L,
                userLogin: userLogin])
        ok(product)
        String productId = product.productId
        GenericValue fp = from('FinancialProduct').where('productId', productId).queryOne()
        fp.set('lateFeeAnnualRate', new BigDecimal('0.146000'))
        fp.store()
        ok(dispatcher.runSync('createFinancialProductLease', [productId: productId,
                defaultMonthlyRateFactor: new BigDecimal('0.018000'), userLogin: userLogin]))
        [['DELINQ_D1', 1L, 30L, 'DUN_L1', 'N', 'N'], ['DELINQ_D2', 31L, 60L, 'DUN_L2', 'N', 'N'],
         ['DELINQ_D3', 61L, 90L, 'DUN_L3', 'Y', 'N'], ['DELINQ_D4', 91L, null, 'DUN_L4', 'Y', 'Y']].each { List r ->
            delegator.create('FinancialProductDelinquency', [productId: productId, delinquencyLevelEnumId: r[0],
                    minDaysPastDue: r[1], maxDaysPastDue: r[2], dunningLevelEnumId: r[3], writeoffFlag: r[4],
                    legalActionFlag: r[5]])
        }
        return productId
    }

    /** Accepted lease (60 months, in advance) for the lessee starting on {@code start}, billed through today. */
    private String billedLease(GenericValue userLogin, String productId, String lessee, LocalDate start) {
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: 60L, userLogin: userLogin])
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
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, leaseStartDate: ts(start), userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        String agreementId = agreement.leaseAgreementId
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: '00001',
                purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        return agreementId
    }

    private GenericValue agreementOf(String agreementId) {
        return from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
    }

    private List<GenericValue> invoices(String agreementId) {
        return from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).orderBy('installmentNumber').queryList()
    }

    // Detection, classification and the overdue split; late fee on the overdue total (tax included).
    void testLeaseDelinquencyAndLateFee() {
        GenericValue userLogin = admin()
        String productId = delinquencyProduct(userLogin)
        String lessee = newParty('TEST_LSDLQ_')
        String agreementId = billedLease(userLogin, productId, lessee, LocalDate.now().minusDays(45))
        List<GenericValue> links = invoices(agreementId)
        assert links.size() == 2                                                  // in advance: start and +1 month
        GenericValue inv1 = links[0]
        GenericValue inv2 = links[1]

        // Partial payment of installment 2: its tax and interest are paid first, the principal stays overdue.
        BigDecimal paid2 = inv2.taxAmount.add(inv2.interestPortion).add(new BigDecimal('1000.00'))
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, invoiceId: inv2.invoiceId,
                amount: paid2, userLogin: userLogin]))

        Map batch = dispatcher.runSync('runLoanDelinquencyBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(batch)
        assert batch.createdDelinquencyCount == 1L
        GenericValue agreement = agreementOf(agreementId)
        GenericValue snapshot = from('LoanDelinquency').where('financeContractId', agreement.financeContractId)
                .orderBy('-asOfDate').queryFirst()
        assert snapshot != null
        assert snapshot.loanAgreementId == null
        assert snapshot.daysPastDue == 45L
        assert snapshot.delinquencyLevelEnumId == 'DELINQ_D2'
        BigDecimal expectedPrincipal = inv1.principalPortion.add(inv2.principalPortion.subtract(new BigDecimal('1000.00')))
        assert snapshot.overduePrincipalAmount.compareTo(expectedPrincipal) == 0
        assert snapshot.overdueInterestAmount.compareTo(inv1.interestPortion) == 0   // installment 1 (in advance: 0)
        assert snapshot.overdueTaxAmount.compareTo(inv1.taxAmount) == 0
        BigDecimal overdueTotal = inv1.billedAmount.add(inv2.billedAmount).subtract(paid2)
        assert snapshot.overduePrincipalAmount.add(snapshot.overdueInterestAmount).add(snapshot.overdueTaxAmount)
                .compareTo(overdueTotal) == 0
        // Idempotent per day.
        Map again = dispatcher.runSync('runLoanDelinquencyBatch', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(again)
        assert again.createdDelinquencyCount == 0L

        // Late fee: base = overdue total incl. tax (default); Dr interest receivable / Cr late fee income.
        Map fee = dispatcher.runSync('runLateFeeAccrual', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(fee)
        assert fee.accrualCreated == true
        assert fee.lateFeeBaseAmount.compareTo(overdueTotal) == 0
        assert fee.overdueTax.compareTo(inv1.taxAmount) == 0
        assert fee.lateFeeAmount.signum() > 0
        GenericValue accrual = from('LoanLateFeeAccrual').where('loanLateFeeAccrualId', fee.loanLateFeeAccrualId).queryOne()
        assert accrual.financeContractId == agreement.financeContractId
        List entries = from('AcctgTransEntry').where('acctgTransId', accrual.acctgTransId).queryList()
        assert entries.find { it.debitCreditFlag == 'D' }.glAccountId == '121800'
        assert entries.find { it.debitCreditFlag == 'C' }.glAccountId == '810100'
        assert entries.every { it.amount.compareTo(fee.lateFeeAmount) == 0 }
        Map retry = dispatcher.runSync('runLateFeeAccrual', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(retry)
        assert retry.accrualCreated == false

        // Tax-excluded base (SystemProperty override of lease.latefee.base).
        LeaseContractAdapter adapter = new LeaseContractAdapter()
        delegator.createOrStore(delegator.makeValue('SystemProperty', [systemResourceId: 'finance',
                systemPropertyId: 'lease.latefee.base', systemPropertyValue: 'TAX_EXCLUDED']))
        try {
            DelinquencyComputation excluded = adapter.computeDelinquency(delegator, agreement, UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp()))
            assert excluded.lateFeeBase.compareTo(overdueTotal.subtract(inv1.taxAmount)) == 0
            assert excluded.overdueTotal.compareTo(overdueTotal) == 0
        } finally {
            delegator.removeByAnd('SystemProperty', [systemResourceId: 'finance', systemPropertyId: 'lease.latefee.base'])
        }

        // A fully paid lease is not delinquent.
        String paidLessee = newParty('TEST_LSDLQ_')
        String paidId = billedLease(userLogin, productId, paidLessee, LocalDate.now().minusDays(45))
        BigDecimal open = invoices(paidId).inject(BigDecimal.ZERO) { BigDecimal s, GenericValue l -> s.add(l.billedAmount) }
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: paidId, amount: open, userLogin: userLogin]))
        Map clean = dispatcher.runSync('runLoanDelinquencyBatch', [leaseAgreementId: paidId, userLogin: userLogin])
        ok(clean)
        assert clean.createdDelinquencyCount == 0L
        assert adapter.computeDelinquency(delegator, agreementOf(paidId),
                UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp())).daysPastDue == 0L
    }

    // D4 (100 days): write-off of every open lease invoice against accounts receivable and the legal flag.
    void testLeaseWriteOffAndLegalAction() {
        GenericValue userLogin = admin()
        String productId = delinquencyProduct(userLogin)
        String lessee = newParty('TEST_LSDLQ_')
        String agreementId = billedLease(userLogin, productId, lessee, LocalDate.now().minusDays(100))
        List<GenericValue> links = invoices(agreementId)
        assert links.size() == 4
        BigDecimal open = links.inject(BigDecimal.ZERO) { BigDecimal s, GenericValue l -> s.add(l.billedAmount) }

        ok(dispatcher.runSync('runLoanDelinquencyBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        GenericValue agreement = agreementOf(agreementId)
        GenericValue writeOff = from('LoanDelinquencyAction').where('financeContractId', agreement.financeContractId,
                'actionTypeEnumId', 'DELINQ_ACT_WRITEOFF').queryFirst()
        assert writeOff != null
        assert writeOff.amount.compareTo(open) == 0
        assert from('LoanDelinquencyAction').where('financeContractId', agreement.financeContractId,
                'actionTypeEnumId', 'DELINQ_ACT_LEGAL').queryCount() == 1
        links.each { GenericValue l ->
            assert from('Invoice').where('invoiceId', l.invoiceId).queryOne().statusId == 'INVOICE_WRITEOFF'
        }

        // GL: Dr write-off / Cr accounts receivable for principal, interest and tax (= the open amount).
        List trans = from('AcctgTrans').where('partyId', lessee).queryList()
                .findAll { it.description?.startsWith('Lease write-off') }
        assert trans.size() == 3
        BigDecimal credited = BigDecimal.ZERO
        trans.each { GenericValue t ->
            from('AcctgTransEntry').where('acctgTransId', t.acctgTransId).queryList().each { GenericValue e ->
                if (e.debitCreditFlag == 'C') {
                    assert e.glAccountId == '120000'
                    credited = credited.add(e.amount)
                }
            }
        }
        assert credited.compareTo(open) == 0

        // Written-off invoices are settled: the lease is no longer delinquent.
        assert new LeaseContractAdapter().computeDelinquency(delegator, agreement,
                UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp())).daysPastDue == 0L
    }

    // One customer with a delinquent loan and a delinquent lease: one batch, one summary, dunning per contract.
    void testCustomerSummaryAcrossLoanAndLease() {
        GenericValue userLogin = admin()
        String customer = newParty('TEST_LSDLQ_')
        String loanAgreementId = delinquentLoan(customer, userLogin)
        String productId = delinquencyProduct(userLogin)
        String leaseAgreementId = billedLease(userLogin, productId, customer, LocalDate.now().minusDays(20))
        String loanContractId = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne().financeContractId
        String leaseContractId = agreementOf(leaseAgreementId).financeContractId

        // The batch processes loans and leases from FinanceContract (here, each contract explicitly).
        ok(dispatcher.runSync('runLoanDelinquencyBatch', [financeContractId: loanContractId, userLogin: userLogin]))
        ok(dispatcher.runSync('runLoanDelinquencyBatch', [financeContractId: leaseContractId, userLogin: userLogin]))
        Map notice = dispatcher.runSync('createLoanDunningNotice', [leaseAgreementId: leaseAgreementId,
                dunningLevelEnumId: 'DUN_L1', userLogin: userLogin])
        ok(notice)
        assert notice.noticeCreated == true
        GenericValue leaseNotice = from('LoanDunningNotice').where('loanDunningNoticeId', notice.loanDunningNoticeId).queryOne()
        assert leaseNotice.financeContractId == leaseContractId && leaseNotice.loanAgreementId == null
        ok(dispatcher.runSync('createLoanDunningNotice', [loanAgreementId: loanAgreementId,
                dunningLevelEnumId: 'DUN_L2', userLogin: userLogin]))

        Map summary = dispatcher.runSync('getFinanceCustomerSummary', [customerPartyId: customer, userLogin: userLogin])
        ok(summary)
        assert summary.contracts.find { it.financeContractId == leaseContractId }.agreementId == leaseAgreementId
        assert summary.contracts.find { it.financeContractId == loanContractId }.agreementId == loanAgreementId
        Map leaseRow = summary.latestDelinquencies.find { it.financeContractId == leaseContractId }
        Map loanRow = summary.latestDelinquencies.find { it.financeContractId == loanContractId }
        assert leaseRow != null && leaseRow.contractTypeId == 'LEASE' && leaseRow.agreementId == leaseAgreementId
        assert leaseRow.daysPastDue == 20L && leaseRow.delinquencyLevelEnumId == 'DELINQ_D1'
        assert loanRow != null && loanRow.contractTypeId == 'LOAN'
        assert summary.dunningNotices.collect { it.financeContractId }.containsAll([leaseContractId, loanContractId])
        assert summary.activeExposure.compareTo(COST.add(new BigDecimal('120000.00'))) == 0
    }

    /** Active loan (TEST_LOANPROD1) of the customer whose single installment is 45 days past due. */
    private String delinquentLoan(String customer, GenericValue userLogin) {
        Map app = dispatcher.runSync('createLoanApplication', [productId: 'TEST_LOANPROD1', applicantPartyId: customer,
                currencyUomId: 'USD', requestedPrincipalAmount: new BigDecimal('120000.00'), requestedTermMonths: 1L,
                userLogin: userLogin])
        ok(app)
        String appId = app.loanApplicationId
        ['LOANAPP_SUBMITTED', 'LOANAPP_APPROVED'].each { String s ->
            ok(dispatcher.runSync('setLoanApplicationStatus', [loanApplicationId: appId, statusId: s, userLogin: userLogin]))
        }
        Map uw = dispatcher.runSync('createLoanUnderwriting', [loanApplicationId: appId, statusId: 'LOANUW_IN_REVIEW',
                creditScore: 750L, decision: 'PENDING', approvedPrincipalAmount: new BigDecimal('120000.00'),
                approvedAnnualInterestRate: new BigDecimal('0.150000'), userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        Map quote = dispatcher.runSync('createLoanQuote', [loanApplicationId: appId, principalAmount: new BigDecimal('120000.00'),
                annualInterestRate: new BigDecimal('0.150000'), termMonths: 1L,
                firstPaymentDate: ts(LocalDate.now().minusDays(45)), userLogin: userLogin])
        ok(quote)
        ['LOANQT_PRESENTED', 'LOANQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLoanQuoteStatus', [loanQuoteId: quote.loanQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLoanAgreement', [loanQuoteId: quote.loanQuoteId,
                agreementDate: ts(LocalDate.now().minusDays(60)), userLogin: userLogin])
        ok(agreement)
        ok(dispatcher.runSync('createLoanInstallmentInvoice', [loanAgreementId: agreement.loanAgreementId,
                installmentNumber: 1L, userLogin: userLogin]))
        return agreement.loanAgreementId
    }
}
