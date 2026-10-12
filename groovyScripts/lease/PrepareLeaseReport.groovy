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

// Controller event (STEP L2): data for the lease quote / lease agreement PDF.
// One report (LeaseReport.jrxml) serves both documents; the title and the
// document number label switch on whether leaseAgreementId or leaseQuoteId is given.
// Data is read through the entity engine and passed to the JasperReports view
// handler as "jrParameters" (header) and "jrDataSource" (payment schedule rows).

import org.apache.ofbiz.base.location.FlexibleLocation
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.party.party.PartyHelper
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

String msg(String key, Map args = [:]) {
    return UtilProperties.getMessage('FinanceUiLabels', key, args, locale)
}

String leaseAgreementId = parameters.leaseAgreementId
String leaseQuoteId = parameters.leaseQuoteId
boolean isAgreement = UtilValidate.isNotEmpty(leaseAgreementId)

GenericValue header
List<GenericValue> scheduleValues
List<GenericValue> assets
if (isAgreement) {
    header = from('LeaseAgreement').where('leaseAgreementId', leaseAgreementId).queryOne()
    if (header != null) {
        GenericValue latest = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', leaseAgreementId)
                .orderBy('-scheduleRevisionNumber').queryFirst()
        scheduleValues = latest == null ? [] : from('LeaseAgreementPaymentSchedule')
                .where('leaseAgreementId', leaseAgreementId, 'scheduleRevisionNumber', latest.scheduleRevisionNumber)
                .orderBy('installmentNumber').queryList()
        assets = from('LeaseAgreementAsset').where('leaseAgreementId', leaseAgreementId).orderBy('assetSeqId').queryList()
    }
} else if (UtilValidate.isNotEmpty(leaseQuoteId)) {
    header = from('LeaseQuote').where('leaseQuoteId', leaseQuoteId).queryOne()
    if (header != null) {
        scheduleValues = from('LeasePaymentSchedule').where('leaseQuoteId', leaseQuoteId).orderBy('installmentNumber').queryList()
        assets = from('LeaseQuoteAsset').where('leaseQuoteId', leaseQuoteId).orderBy('assetSeqId').queryList()
    }
}
if (header == null) {
    request.setAttribute('_ERROR_MESSAGE_', msg('FinanceRecordNotFound',
            [entityName: isAgreement ? 'LeaseAgreement' : 'LeaseQuote', id: isAgreement ? leaseAgreementId : leaseQuoteId]))
    return 'error'
}

String lesseePartyId = isAgreement ? header.lesseePartyId
        : from('LeaseApplication').where('leaseApplicationId', header.leaseApplicationId).queryOne()?.applicantPartyId
String lessorName = isAgreement && header.organizationPartyId
        ? PartyHelper.getPartyName(delegator, header.organizationPartyId, false)
        : msg('FinanceLoanQuoteLenderName')

String logoPath = null
try {
    java.net.URL logoUrl = FlexibleLocation.resolveLocation('component://common-theme/webapp/images/ofbiz_logo.png')
    if (logoUrl != null) {
        logoPath = new File(logoUrl.toURI()).getAbsolutePath()
    }
} catch (Exception e) {
    org.apache.ofbiz.base.util.Debug.logWarning('Could not resolve OFBiz logo for lease PDF: ' + e, 'PrepareLeaseReport')
}

String enumDescription(String enumId) {
    GenericValue e = enumId ? from('Enumeration').where('enumId', enumId).cache().queryOne() : null
    return e?.get('description', locale) ?: enumId
}

BigDecimal monthlyTax = scheduleValues ? scheduleValues[0].getBigDecimal('taxAmount') : null
Map jrParameters = [
        documentTitle     : msg(isAgreement ? 'FinanceLeasePdfAgreementTitle' : 'FinanceLeasePdfQuoteTitle'),
        documentNoLabel   : msg(isAgreement ? 'FinanceLeasePdfAgreementNo' : 'FinanceLeasePdfQuoteNo'),
        documentNo        : isAgreement ? header.leaseAgreementId : header.leaseQuoteId,
        documentDate      : isAgreement ? header.getTimestamp('agreementDate') : header.getTimestamp('createdDate'),
        lessorName        : lessorName,
        lesseeName        : lesseePartyId ? PartyHelper.getPartyName(delegator, lesseePartyId, false) : '',
        currencyUomId     : header.currencyUomId,
        acquisitionCost   : header.getBigDecimal('acquisitionCost'),
        residualValue     : header.getBigDecimal('residualValue'),
        termMonths        : header.get('termMonths') != null ? new BigDecimal(header.get('termMonths').toString()) : null,
        paymentTiming     : enumDescription(header.paymentTimingEnumId),
        calcMethod        : enumDescription(header.calcMethodEnumId),
        monthlyLeaseAmount: header.getBigDecimal('monthlyLeaseAmount'),
        monthlyTaxAmount  : monthlyTax,
        totalLeaseAmount  : header.getBigDecimal('totalLeaseAmount'),
        totalTaxAmount    : header.getBigDecimal('totalTaxAmount'),
        calcInterestRate  : header.getBigDecimal('calcInterestRate'),
        monthlyRateFactor : header.getBigDecimal('monthlyRateFactor'),
        leaseClass        : isAgreement ? enumDescription(header.leaseClassEnumId) : null,
        assetSummary      : assets.collect { GenericValue a -> "${a.assetName} x ${a.quantity?.stripTrailingZeros()?.toPlainString()}" }.join(' / '),
        provisionalNote   : msg('FinanceLeasePdfProvisionalNote'),
        logoPath          : logoPath]
['FinanceLeasePdfIssueDate', 'FinanceLeasePdfLessor', 'FinanceLeasePdfLessee', 'FinanceLeasePdfAssets',
 'FinanceLeasePdfAcquisitionCost', 'FinanceLeasePdfResidual', 'FinanceLeasePdfTerm', 'FinanceLeasePdfTiming',
 'FinanceLeasePdfCalcMethod', 'FinanceLeasePdfMonthly', 'FinanceLeasePdfMonthlyTax', 'FinanceLeasePdfTotal',
 'FinanceLeasePdfTotalTax', 'FinanceLeasePdfCalcRate', 'FinanceLeasePdfRateFactor', 'FinanceLeasePdfLeaseClass',
 'FinanceLeasePdfCurrency', 'FinanceLeasePdfColNo', 'FinanceLeasePdfColDueDate', 'FinanceLeasePdfColLease',
 'FinanceLeasePdfColTax', 'FinanceLeasePdfColTotal', 'FinanceLeasePdfColPrincipal', 'FinanceLeasePdfColInterest',
 'FinanceLeasePdfColRemaining', 'FinanceQuotePdfPage'].each { String key -> jrParameters.put(key, msg(key)) }

List<Map<String, Object>> rows = scheduleValues.collect { GenericValue line ->
    BigDecimal lease = line.getBigDecimal('leaseAmount') ?: BigDecimal.ZERO
    BigDecimal tax = line.getBigDecimal('taxAmount') ?: BigDecimal.ZERO
    [installmentNumber : new BigDecimal(line.get('installmentNumber').toString()),
     dueDate           : line.getTimestamp('dueDate'),
     leaseAmount       : lease,
     taxAmount         : tax,
     totalAmount       : lease.add(tax),
     principalPortion  : line.getBigDecimal('principalPortion'),
     interestPortion   : line.getBigDecimal('interestPortion'),
     remainingPrincipal: line.getBigDecimal('remainingPrincipal')] as Map<String, Object>
}

request.setAttribute('jrParameters', jrParameters)
request.setAttribute('jrDataSource', new JRMapCollectionDataSource(rows))
return 'success'