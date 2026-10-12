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
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L3: asset purchase (supplier invoice + FixedAsset), supplier payment and
 * acceptance (lease start, schedule re-dating, method-1 start entries).
 *
 * Uses the USD lease product TEST_LEASEPROD_USD (Company's base currency, so the GL
 * amounts are not converted) and a new lessee per test.
 */
class LeaseAcceptanceTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseAcceptanceTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static Timestamp day(int year, int month, int dayOfMonth) {
        return UtilDateTime.toTimestamp(month, dayOfMonth, year, 0, 0, 0)
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    /** New lessee with a contracted lease agreement for one asset; returns the agreement id. */
    private String contractedAgreement(GenericValue userLogin, String productId = 'TEST_LEASEPROD_USD') {
        String lessee = 'TEST_LSACC_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease acceptance test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: 60L, userLogin: userLogin])
        ok(app)
        String appId = app.leaseApplicationId
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: appId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Production machine', quantity: BigDecimal.ONE, estimatedAcquisitionCost: COST,
                supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: COST, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, leaseStartDate: day(2027, 4, 1),
                userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: day(2027, 3, 15), userLogin: userLogin])
        ok(agreement)
        return agreement.leaseAgreementId
    }

    private Map purchase(String agreementId, GenericValue userLogin, Timestamp date = day(2027, 4, 20)) {
        return dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: '00001',
                purchaseDate: date, supplierInvoiceRef: 'SUP-INV-1', userLogin: userLogin])
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

    // Purchase: supplier invoice (core GL to the suspense account), FixedAsset, link; then payment.
    void testAssetPurchaseAndSupplierPayment() {
        GenericValue userLogin = admin()
        String agreementId = contractedAgreement(userLogin)

        Map bought = purchase(agreementId, userLogin)
        ok(bought)
        GenericValue invoice = from('Invoice').where('invoiceId', bought.invoiceId).queryOne()
        assert invoice.invoiceTypeId == 'PURCHASE_INVOICE'
        assert invoice.partyIdFrom == 'TEST_LS_SUPPLIER1'
        assert invoice.partyId == 'Company'
        assert invoice.statusId == 'INVOICE_READY'
        GenericValue invoiceTrans = from('AcctgTrans').where('invoiceId', bought.invoiceId).queryFirst()
        assert invoiceTrans != null && invoiceTrans.isPosted == 'Y'
        Map<String, BigDecimal> invoiceSums = entrySums(invoiceTrans.acctgTransId)
        assert invoiceSums['147500:D'].compareTo(COST) == 0                     // lease asset suspense
        assert side(invoiceSums, 'C').compareTo(COST) == 0                       // accounts payable

        GenericValue fixedAsset = from('FixedAsset').where('fixedAssetId', bought.fixedAssetId).queryOne()
        assert fixedAsset.partyId == 'Company'
        assert fixedAsset.fixedAssetTypeId == 'EQUIPMENT'
        assert fixedAsset.purchaseCost.compareTo(COST) == 0
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert asset.fixedAssetId == bought.fixedAssetId
        assert asset.assetStatusId == 'LSAST_PURCHASED'

        assert ServiceUtil.isError(purchase(agreementId, userLogin))            // only once
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_CANCELLED', userLogin: userLogin]))          // no cancel after purchase

        assert ServiceUtil.isError(dispatcher.runSync('payLeaseAssetPurchase', [leaseAgreementId: agreementId,
                assetSeqId: '00001', effectiveDate: UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), 30),
                userLogin: userLogin]))                                          // future date rejected
        Map paid = dispatcher.runSync('payLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: '00001',
                userLogin: userLogin])
        ok(paid)
        GenericValue payment = from('Payment').where('paymentId', paid.paymentId).queryOne()
        assert payment.paymentTypeId == 'VENDOR_PAYMENT'
        assert payment.statusId == 'PMNT_SENT'
        assert payment.partyIdTo == 'TEST_LS_SUPPLIER1'
        assert from('Invoice').where('invoiceId', bought.invoiceId).queryOne().statusId == 'INVOICE_PAID'
        assert from('AcctgTrans').where('paymentId', paid.paymentId).queryList().every { it.isPosted == 'Y' }
        assert from('LeaseAssetPurchase').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
                .paymentId == paid.paymentId
        assert ServiceUtil.isError(dispatcher.runSync('payLeaseAssetPurchase', [leaseAgreementId: agreementId,
                assetSeqId: '00001', userLogin: userLogin]))
    }

    // Acceptance: preconditions, start date, schedule revision, ACTIVE, start entries, idempotency.
    void testAcceptanceStartsLease() {
        GenericValue userLogin = admin()
        String agreementId = contractedAgreement(userLogin)

        // Only the acceptance starts a lease; acceptance requires every asset to be purchased.
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_ACTIVE', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId,
                acceptanceDate: day(2027, 5, 10), userLogin: userLogin]))
        Map bought = purchase(agreementId, userLogin)
        ok(bought)
        assert ServiceUtil.isError(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId,
                acceptanceDate: day(2027, 4, 10), userLogin: userLogin]))      // before the purchase date

        Map accepted = dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId,
                acceptanceDate: day(2027, 5, 10), comments: 'Installed and checked', userLogin: userLogin])
        ok(accepted)
        assert accepted.created == true
        assert accepted.scheduleRevisionNumber == 2L

        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert agreement.statusId == 'LEASEAGR_ACTIVE'
        assert agreement.leaseStartDate == day(2027, 5, 10)
        assert agreement.leaseEndDate == day(2032, 5, 9)
        assert agreement.firstPaymentDate == day(2027, 5, 10)                    // payments in advance
        assert from('FinanceContract').where('financeContractId', agreement.financeContractId).queryOne()
                .statusId == 'FINCTR_ACTIVE'
        assert from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
                .assetStatusId == 'LSAST_LEASED'

        // Revision 2 is dated from the lease start; amounts are those of revision 1.
        List rev1 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId, 'scheduleRevisionNumber', 1L)
                .orderBy('installmentNumber').queryList()
        List rev2 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId, 'scheduleRevisionNumber', 2L)
                .orderBy('installmentNumber').queryList()
        assert rev2.size() == 60 && rev1.size() == 60
        assert rev1[0].periodFromDate == day(2027, 4, 1)
        assert rev2[0].periodFromDate == day(2027, 5, 10)
        assert rev2[0].periodThruDate == day(2027, 6, 9)
        assert rev2[0].dueDate == day(2027, 5, 10)
        assert rev2[-1].periodThruDate == agreement.leaseEndDate
        (0..<60).each { int i ->
            assert rev2[i].leaseAmount.compareTo(rev1[i].leaseAmount) == 0
            assert rev2[i].principalPortion.compareTo(rev1[i].principalPortion) == 0
        }

        // Method 1 start entries: balanced; sales - cost of sales = deferred lease profit.
        GenericValue acceptance = from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne()
        assert acceptance.startAcctgTransId == accepted.acctgTransId
        assert acceptance.acceptedByPartyId == agreement.lesseePartyId
        GenericValue startTrans = from('AcctgTrans').where('acctgTransId', accepted.acctgTransId).queryOne()
        assert startTrans.isPosted == 'Y'
        Map<String, BigDecimal> sums = entrySums(accepted.acctgTransId)
        BigDecimal totalLease = agreement.totalLeaseAmount
        BigDecimal interest = agreement.totalInterestAmount
        assert totalLease.compareTo(new BigDecimal('1080000.00')) == 0
        assert interest.compareTo(new BigDecimal('80000.00')) == 0
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['128500:D'].compareTo(totalLease) == 0                       // lease investment asset
        assert sums['403500:C'].compareTo(totalLease) == 0                       // lease sales
        assert sums['503500:D'].compareTo(COST) == 0                             // cost of sales
        assert sums['147500:C'].compareTo(COST) == 0                             // suspense released
        assert sums['503600:D'].compareTo(interest) == 0                         // provision
        assert sums['128700:C'].compareTo(interest) == 0                         // deferred lease profit
        assert sums['403500:C'].subtract(sums['503500:D']).compareTo(sums['128700:C']) == 0
        // The suspense account nets to zero over purchase and start.
        GenericValue invoiceTrans = from('AcctgTrans').where('invoiceId', bought.invoiceId).queryFirst()
        assert entrySums(invoiceTrans.acctgTransId)['147500:D'].compareTo(sums['147500:C']) == 0

        // Idempotent: a second acceptance returns the same start and adds nothing.
        Map again = dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId,
                acceptanceDate: day(2027, 6, 1), userLogin: userLogin])
        ok(again)
        assert again.created == false
        assert again.acctgTransId == accepted.acctgTransId
        assert from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId, 'scheduleRevisionNumber', 3L)
                .queryCount() == 0
        assert ServiceUtil.isError(purchase(agreementId, userLogin))            // no purchase after the start
    }

    // Ownership transfer with residual value: receivable account; the residual stays in the lease asset.
    void testTransferLeaseWithResidualStartEntries() {
        GenericValue userLogin = admin()
        Map product = dispatcher.runSync('createFinanceProduct', [productName: 'Test Transfer Lease USD',
                financialProductTypeId: 'LEASE_FIN_TRANSFER', currencyUomId: 'USD', minTermMonths: 12L, maxTermMonths: 72L,
                userLogin: userLogin])
        ok(product)
        ok(dispatcher.runSync('createFinancialProductLease', [productId: product.productId,
                defaultCalcInterestRate: new BigDecimal('0.030000'), residualValueRate: new BigDecimal('0.100000'),
                userLogin: userLogin]))
        String agreementId = contractedAgreement(userLogin, product.productId)
        ok(purchase(agreementId, userLogin))
        Map accepted = dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId,
                acceptanceDate: day(2027, 5, 1), userLogin: userLogin])
        ok(accepted)

        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        BigDecimal residual = agreement.residualValue
        assert residual.compareTo(new BigDecimal('100000.00')) == 0
        assert agreement.leaseClassEnumId == 'LEASE_CLS_FIN_TRANS'
        Map<String, BigDecimal> sums = entrySums(accepted.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['128500:D'] == null                                          // not the investment asset
        assert sums['128600:D'].compareTo(agreement.totalLeaseAmount.add(residual)) == 0
        assert sums['503500:D'].compareTo(COST.subtract(residual)) == 0
        assert sums['147500:C'].compareTo(COST) == 0
        assert sums['128700:C'].compareTo(agreement.totalInterestAmount) == 0
        assert sums['403500:C'].subtract(sums['503500:D']).compareTo(sums['128700:C']) == 0
    }
}
