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
import org.apache.ofbiz.finance.lease.LeaseTaxHelper
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L5: consumption tax of lease payments. Rate from the core tax authority rate of a
 * product category, fixed at the lease start; per-installment (core tax line) and
 * lump-sum (at the start) timings; currency rounding.
 *
 * Each test creates its own USD lease product and tax category (rates relative to today)
 * with the test tax authority TEST_TAXAUTH_JP (FinanceTestData.xml).
 */
class LeaseTaxTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')
    private static final String AUTH = 'TEST_TAXAUTH_JP'

    LeaseTaxTests(String name) {
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

    /** USD finance lease product in a new tax category; rates: [[percent, from, thru]]. */
    private String taxedProduct(GenericValue userLogin, List<List> rates, String taxTimingEnumId = null, String taxable = 'Y',
            String rateFactor = '0.018000') {
        Map product = dispatcher.runSync('createFinanceProduct', [productName: 'Test Taxed Lease USD',
                financialProductTypeId: 'LEASE_FIN_NONTRANS', currencyUomId: 'USD', minTermMonths: 12L, maxTermMonths: 72L,
                userLogin: userLogin])
        ok(product)
        Map terms = [productId: product.productId, defaultMonthlyRateFactor: new BigDecimal(rateFactor), taxable: taxable,
                     userLogin: userLogin]
        if (taxTimingEnumId) {
            terms.taxTimingEnumId = taxTimingEnumId
        }
        ok(dispatcher.runSync('createFinancialProductLease', terms))
        String categoryId = 'TEST_LSTX_' + delegator.getNextSeqId('ProductCategory')
        delegator.create('ProductCategory', [productCategoryId: categoryId, productCategoryTypeId: 'TAX_CATEGORY',
                categoryName: 'Test lease tax category'])
        delegator.create('ProductCategoryMember', [productCategoryId: categoryId, productId: product.productId,
                fromDate: ts(LocalDate.of(2001, 1, 1))])
        rates.each { List r ->
            delegator.create('TaxAuthorityRateProduct', [taxAuthorityRateSeqId: delegator.getNextSeqId('TaxAuthorityRateProduct'),
                    taxAuthGeoId: 'JPN', taxAuthPartyId: AUTH, taxAuthorityRateTypeId: 'SALES_TAX', productCategoryId: categoryId,
                    taxPercentage: new BigDecimal(r[0] as String), fromDate: ts(r[1] as LocalDate),
                    thruDate: r[2] == null ? null : ts(r[2] as LocalDate)])
        }
        return product.productId
    }

    /** New lessee, contract, purchase and acceptance on {@code start}; returns [agreementId, quoteId]. */
    private List activeLease(GenericValue userLogin, String productId, LocalDate start, long termMonths) {
        String lessee = 'TEST_LSTAX_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease tax test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
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
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: termMonths,
                leaseStartDate: ts(start), userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreement.leaseAgreementId, assetSeqId: '00001',
                purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreement.leaseAgreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        return [agreement.leaseAgreementId, quote.leaseQuoteId]
    }

    private List<GenericValue> schedule(String agreementId) {
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision).orderBy('installmentNumber').queryList()
    }

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

    // Rounding: currency rounding per installment; lump sum rounds the total once (last installment adjusts).
    void testTaxAllocationRounding() {
        List<BigDecimal> amounts = [new BigDecimal('18335'), new BigDecimal('18335'), new BigDecimal('18335')]
        BigDecimal rate = new BigDecimal('0.10')
        List perInstallment = LeaseTaxHelper.allocate(amounts, rate, false, 0, RoundingMode.DOWN)
        assert perInstallment.every { it.compareTo(new BigDecimal('1833')) == 0 }          // 1833.5 truncated (JPY)
        List lumpSum = LeaseTaxHelper.allocate(amounts, rate, true, 0, RoundingMode.DOWN)
        assert lumpSum[0].compareTo(new BigDecimal('1833')) == 0
        assert lumpSum[2].compareTo(new BigDecimal('1834')) == 0                           // 5500 - 1833 x 2
        assert lumpSum.inject(BigDecimal.ZERO) { a, b -> a.add(b) }.compareTo(new BigDecimal('5500')) == 0
        List usd = LeaseTaxHelper.allocate([new BigDecimal('100.05')], new BigDecimal('0.08'), false, 2, RoundingMode.HALF_UP)
        assert usd[0].compareTo(new BigDecimal('8.00')) == 0                               // 8.004 -> 8.00

        // Non-taxable product: rate 0; product without a rate row: lease.tax.rate, no authority.
        GenericValue userLogin = admin()
        String exempt = taxedProduct(userLogin, [[10, LocalDate.of(2001, 1, 1), null]], null, 'N')
        assert LeaseTaxHelper.resolveRate(delegator, exempt, ts(LocalDate.now())).rate.signum() == 0
        LeaseTaxHelper.TaxRate fallback = LeaseTaxHelper.resolveRate(delegator, 'TEST_LEASEPROD_USD', ts(LocalDate.now()))
        assert fallback.rate.compareTo(new BigDecimal('0.10')) == 0
        assert !fallback.hasAuthority()
    }

    // Rate change: a lease keeps the rate valid at its start for every installment; a later lease uses the new rate.
    void testTaxRateFixedAtLeaseStart() {
        GenericValue userLogin = admin()
        LocalDate change = LocalDate.now().minusMonths(1)
        String productId = taxedProduct(userLogin, [[8, LocalDate.of(2001, 1, 1), change], [12, change, null]])

        LocalDate startA = LocalDate.now().minusMonths(3)
        List a = activeLease(userLogin, productId, startA, 60L)
        GenericValue quoteA = from('LeaseQuote').where('leaseQuoteId', a[1]).queryOne()
        assert quoteA.taxRate.compareTo(new BigDecimal('0.08')) == 0
        GenericValue agrA = from('LeaseAgreement').where('leaseAgreementId', a[0]).queryOne()
        assert agrA.taxRate.compareTo(new BigDecimal('0.08')) == 0
        assert agrA.taxTimingEnumId == 'LEASE_TAX_INSTALL'
        assert agrA.taxAuthPartyId == AUTH && agrA.taxAuthGeoId == 'JPN' && agrA.taxAuthorityRateSeqId != null
        List rowsA = schedule(a[0])
        assert rowsA.every { it.taxAmount.compareTo(it.leaseAmount.multiply(new BigDecimal('0.08')).setScale(2, RoundingMode.HALF_UP)) == 0 }
        assert agrA.totalTaxAmount.compareTo(rowsA.inject(BigDecimal.ZERO) { BigDecimal s, GenericValue r -> s.add(r.taxAmount) }) == 0

        // Installments billed after the rate change still carry 8%: core tax line, GL tax received = invoice tax.
        Map batch = dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: a[0], userLogin: userLogin])
        ok(batch)
        List links = from('LeaseInstallmentInvoice').where('leaseAgreementId', a[0]).orderBy('installmentNumber').queryList()
        assert links.size() == 4
        assert links.any { it.dueDate.after(ts(change)) }
        links.each { GenericValue link ->
            assert link.taxAmount.compareTo(new BigDecimal('1440.00')) == 0               // 18,000 x 8%
            List taxItems = from('InvoiceItem').where('invoiceId', link.invoiceId, 'invoiceItemTypeId', 'ITM_SALES_TAX').queryList()
            assert taxItems.size() == 1
            assert taxItems[0].taxAuthPartyId == AUTH && taxItems[0].taxAuthGeoId == 'JPN'
            assert taxItems[0].amount.compareTo(link.taxAmount) == 0
            GenericValue trans = from('AcctgTrans').where('invoiceId', link.invoiceId).queryFirst()
            assert trans.isPosted == 'Y'
            Map<String, BigDecimal> sums = entrySums(trans.acctgTransId)
            assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
            assert sums['224500:C'].compareTo(link.taxAmount) == 0                        // TaxAuthorityGlAccount
            assert sums['128500:C'].compareTo(link.leaseAmount) == 0
            assert sums['120000:D'].compareTo(link.billedAmount) == 0
        }

        // A lease starting after the change uses 12%.
        List b = activeLease(userLogin, productId, LocalDate.now().minusDays(10), 60L)
        GenericValue agrB = from('LeaseAgreement').where('leaseAgreementId', b[0]).queryOne()
        assert agrB.taxRate.compareTo(new BigDecimal('0.12')) == 0
        assert schedule(b[0])[0].taxAmount.compareTo(new BigDecimal('2160.00')) == 0      // 18,000 x 12%
    }

    // Lump sum: the tax of all payments is booked at the start; billing collects the tax receivable.
    void testLumpSumTaxAtStart() {
        GenericValue userLogin = admin()
        String productId = taxedProduct(userLogin, [[10, LocalDate.of(2001, 1, 1), null]], 'LEASE_TAX_AT_START', 'Y', '0.090000')
        List a = activeLease(userLogin, productId, LocalDate.now().minusMonths(14), 12L)
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', a[0]).queryOne()
        assert agreement.taxTimingEnumId == 'LEASE_TAX_AT_START'
        BigDecimal totalTax = agreement.totalLeaseAmount.multiply(new BigDecimal('0.10')).setScale(2, RoundingMode.HALF_UP)
        assert agreement.totalTaxAmount.compareTo(totalTax) == 0
        assert schedule(a[0]).inject(BigDecimal.ZERO) { BigDecimal s, GenericValue r -> s.add(r.taxAmount) }.compareTo(totalTax) == 0

        GenericValue acceptance = from('LeaseAcceptance').where('leaseAgreementId', a[0]).queryOne()
        assert acceptance.startTaxAcctgTransId != null
        Map<String, BigDecimal> startTax = entrySums(acceptance.startTaxAcctgTransId)
        assert startTax['128800:D'].compareTo(totalTax) == 0
        assert startTax['224500:C'].compareTo(totalTax) == 0

        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: a[0], userLogin: userLogin]))
        List links = from('LeaseInstallmentInvoice').where('leaseAgreementId', a[0]).queryList()
        assert links.size() == 12
        BigDecimal collected = BigDecimal.ZERO
        links.each { GenericValue link ->
            assert from('InvoiceItem').where('invoiceId', link.invoiceId, 'invoiceItemTypeId', 'ITM_SALES_TAX').queryCount() == 0
            assert from('InvoiceItem').where('invoiceId', link.invoiceId, 'invoiceItemTypeId', 'LEASE_CONS_TAX').queryCount() == 1
            Map<String, BigDecimal> sums = entrySums(from('AcctgTrans').where('invoiceId', link.invoiceId).queryFirst().acctgTransId)
            assert sums['224500:C'] == null                                              // not taxed again at billing
            collected = collected.add(sums['128800:C'])
        }
        assert collected.compareTo(totalTax) == 0                                         // the receivable nets to zero

        // Idempotent acceptance returns the same tax entry.
        Map again = dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: a[0], userLogin: userLogin])
        ok(again)
        assert again.taxAcctgTransId == acceptance.startTaxAcctgTransId
    }
}
