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
import org.apache.ofbiz.base.location.FlexibleLocation
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseInquiryHelper
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L8: lease inquiries (collection status, outstanding principal by lessee, asset
 * register) and the documents (acceptance certificate, expiry notice) including the
 * compilation and rendering of LeaseDocument.jrxml to PDF.
 */
class LeaseInquiryTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseInquiryTests(String name) {
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

    /** Accepted lease of TEST_LEASEPROD_USD for the lessee (60 months, started 2 months ago), billed through today. */
    private String activeLease(GenericValue userLogin, String lessee) {
        LocalDate start = LocalDate.now().minusMonths(2)
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
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

    /**
     * Compile, fill and export LeaseDocument.jrxml. JasperReports (jasperreports plugin) is
     * loaded reflectively so the finance plugin keeps no compile-time dependency on it.
     */
    private byte[] renderPdf(Map data) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader()
        def compiler = Class.forName('net.sf.jasperreports.engine.JasperCompileManager', true, loader)
        def filler = Class.forName('net.sf.jasperreports.engine.JasperFillManager', true, loader)
        def exporter = Class.forName('net.sf.jasperreports.engine.JasperExportManager', true, loader)
        def dataSource = Class.forName('net.sf.jasperreports.engine.data.JRMapCollectionDataSource', true, loader)
                .getConstructor(Collection).newInstance(data.rows)
        URL url = FlexibleLocation.resolveLocation('component://finance/webapp/finance/reports/LeaseDocument.jrxml')
        def report = url.openStream().withCloseable { InputStream input -> compiler.compileReport(input) }
        def print = filler.fillReport(report, new HashMap(data.parameters), dataSource)
        return exporter.exportReportToPdf(print)
    }

    // Collection status, outstanding principal by lessee and asset register reflect payments.
    void testLeaseInquiries() {
        GenericValue userLogin = admin()
        String lessee = 'TEST_LSINQ_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease inquiry test lessee'])
        String first = activeLease(userLogin, lessee)
        String second = activeLease(userLogin, lessee)
        // Pay invoice 1 of the first lease (no interest in advance: tax then principal).
        GenericValue inv1 = from('LeaseInstallmentInvoice').where('leaseAgreementId', first, 'installmentNumber', 1L).queryOne()
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: first, invoiceId: inv1.invoiceId,
                amount: inv1.billedAmount, userLogin: userLogin]))

        List<Map> collection = LeaseInquiryHelper.collectionStatus(delegator, null, lessee, null)
        assert collection*.leaseAgreementId.sort() == [first, second].sort()
        Map row1 = collection.find { it.leaseAgreementId == first }
        Map row2 = collection.find { it.leaseAgreementId == second }
        assert row1.billedCount == 3L && row2.billedCount == 3L
        assert row1.outstandingPrincipal.compareTo(COST.subtract(inv1.principalPortion)) == 0
        assert row2.outstandingPrincipal.compareTo(COST) == 0
        assert row1.unpaidBilledAmount.compareTo(row1.billedAmount.subtract(inv1.billedAmount)) == 0
        assert row2.daysPastDue > 0L                                              // installment 1 unpaid since the start
        assert LeaseInquiryHelper.collectionStatus(delegator, 'LEASEAGR_COMPLETED', lessee, null).isEmpty()

        List<Map> outstanding = LeaseInquiryHelper.outstandingPrincipalByLessee(delegator, lessee)
        assert outstanding.size() == 1
        assert outstanding[0].agreementCount == 2L
        assert outstanding[0].currencyUomId == 'USD'
        assert outstanding[0].acquisitionCost.compareTo(COST.multiply(new BigDecimal('2'))) == 0
        assert outstanding[0].outstandingPrincipal.compareTo(row1.outstandingPrincipal.add(row2.outstandingPrincipal)) == 0

        List<Map> assets = LeaseInquiryHelper.assetRegister(delegator, null, lessee, null)
        assert assets.size() == 2
        assert assets.every { it.assetStatusId == 'LSAST_LEASED' && it.ownerPartyId == 'Company' && it.fixedAssetId != null }
        assert LeaseInquiryHelper.assetRegister(delegator, 'LSAST_RETURNED', lessee, null).isEmpty()
        assert LeaseInquiryHelper.assetRegister(delegator, null, lessee, 'COMPUTER_HARDWARE').isEmpty()
    }

    // Acceptance certificate and expiry notice: data, issue date, and a rendered PDF.
    void testLeaseDocuments() {
        GenericValue userLogin = admin()
        String lessee = 'TEST_LSINQ_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease document test lessee'])
        String agreementId = activeLease(userLogin, lessee)

        Map acceptance = LeaseInquiryHelper.documentData(delegator, LeaseInquiryHelper.DOC_ACCEPTANCE, agreementId, Locale.JAPANESE)
        assert acceptance.parameters.documentTitle == '物件受領書'
        assert acceptance.parameters.documentNo == agreementId
        assert acceptance.parameters.lesseeName == 'Lease document test lessee'
        assert acceptance.parameters.line1Value == LocalDate.now().minusMonths(2).toString()
        assert acceptance.rows.size() == 1 && acceptance.rows[0].fixedAssetId != null
        byte[] pdf = renderPdf(acceptance)
        assert pdf.length > 1000 && new String(pdf, 0, 5, 'ISO-8859-1') == '%PDF-'

        // The first print records the issue date; later prints keep it.
        assert from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne().receiptPrintedDate == null
        LeaseInquiryHelper.markAcceptanceCertificateIssued(delegator, agreementId)
        Timestamp issued = from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne().receiptPrintedDate
        assert issued != null
        LeaseInquiryHelper.markAcceptanceCertificateIssued(delegator, agreementId)
        assert from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne().receiptPrintedDate == issued

        // Expiry notice: not available before it is recorded.
        assert LeaseInquiryHelper.documentData(delegator, LeaseInquiryHelper.DOC_EXPIRY, agreementId, Locale.JAPANESE) == null
        ok(dispatcher.runSync('issueLeaseExpiryNotice', [leaseAgreementId: agreementId, userLogin: userLogin]))
        Map expiry = LeaseInquiryHelper.documentData(delegator, LeaseInquiryHelper.DOC_EXPIRY, agreementId, Locale.JAPANESE)
        assert expiry.parameters.documentTitle == 'リース期間満了のお知らせ'
        assert expiry.parameters.bodyText.contains('返還')                       // no ownership transfer
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert expiry.parameters.line2Value == agreement.leaseEndDate.toLocalDateTime().toLocalDate().toString()
        assert renderPdf(expiry).length > 1000

        assert LeaseInquiryHelper.documentData(delegator, 'UNKNOWN', agreementId, Locale.JAPANESE) == null
        assert LeaseInquiryHelper.documentData(delegator, LeaseInquiryHelper.DOC_ACCEPTANCE, 'NO_SUCH', Locale.JAPANESE) == null
    }
}
