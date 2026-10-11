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

// Controller event that prepares data for the loan agreement (契約書) PDF.
// Follows the same pattern as PrepareLoanQuoteReport.groovy:
//   - "jrParameters" : header values (lender/borrower/agreement terms/labels)
//   - "jrDataSource" : JRMapCollectionDataSource over confirmed repayment schedule rows
//                      (latest scheduleRevisionNumber)

import org.apache.ofbiz.base.location.FlexibleLocation
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.party.party.PartyHelper
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

String loanAgreementId = parameters.loanAgreementId
if (UtilValidate.isEmpty(loanAgreementId)) {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('FinanceUiLabels', 'FinanceAgreementIdMissing', locale))
    return 'error'
}

GenericValue agreement = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
if (agreement == null) {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanAgreementNotFound',
                    [loanAgreementId: loanAgreementId], locale))
    return 'error'
}

// Related data resolved via the entity engine.
GenericValue application = from('LoanApplication')
        .where('loanApplicationId', agreement.loanApplicationId).queryOne()
GenericValue quote = from('LoanQuote').where('loanQuoteId', agreement.loanQuoteId).queryOne()
GenericValue repaymentMethod = quote?.getRelatedOne('Enumeration', false)

String borrowerPartyId = agreement.borrowerPartyId
String borrowerName = UtilValidate.isNotEmpty(borrowerPartyId)
        ? PartyHelper.getPartyName(delegator, borrowerPartyId, false) : ''

String lenderName    = UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteLenderName', locale)
String lenderAddress = UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteLenderAddress', locale)

// Resolve logo path.
String logoPath = null
try {
    java.net.URL logoUrl = FlexibleLocation.resolveLocation('component://common-theme/webapp/images/ofbiz_logo.png')
    if (logoUrl != null) {
        logoPath = new java.io.File(logoUrl.toURI()).getAbsolutePath()
    }
} catch (Exception e) {
    org.apache.ofbiz.base.util.Debug.logWarning(
            'Could not resolve OFBiz logo for loan agreement PDF: ' + e, 'PrepareLoanAgreementReport')
}

// Resolve the latest scheduleRevisionNumber.
List<GenericValue> revisions = EntityQuery.use(delegator)
        .from('LoanAgreementRepaymentSchedule')
        .where('loanAgreementId', loanAgreementId)
        .orderBy('-scheduleRevisionNumber')
        .queryList()
Long currentRevision = revisions ? (Long) revisions[0].get('scheduleRevisionNumber') : 1L

// Build header parameters.
Map jrParameters = [:]
jrParameters.loanAgreementId    = agreement.loanAgreementId
jrParameters.loanApplicationId  = agreement.loanApplicationId ?: ''
jrParameters.loanQuoteId        = agreement.loanQuoteId ?: ''
jrParameters.currencyUomId      = agreement.currencyUomId ?: ''
jrParameters.principalAmount    = agreement.getBigDecimal('principalAmount')
jrParameters.annualInterestRate = agreement.getBigDecimal('annualInterestRate')
jrParameters.termMonths         = agreement.get('termMonths') != null
        ? new java.math.BigDecimal(agreement.get('termMonths').toString()) : null
jrParameters.repaymentMethod    = repaymentMethod?.description ?: (quote?.repaymentMethodEnumId ?: '')
jrParameters.agreementDate      = agreement.getTimestamp('agreementDate')
jrParameters.firstPaymentDate   = agreement.getTimestamp('firstPaymentDate')
jrParameters.borrowerPartyId    = borrowerPartyId ?: ''
jrParameters.borrowerName       = borrowerName
jrParameters.lenderName         = lenderName
jrParameters.lenderAddress      = lenderAddress
jrParameters.logoPath           = logoPath
jrParameters.scheduleRevision   = currentRevision

// Localized labels for the PDF.
['FinanceAgreementPdfTitle', 'FinanceAgreementPdfAgreementNo', 'FinanceAgreementPdfAgreementDate',
 'FinanceAgreementPdfFirstPaymentDate', 'FinanceAgreementPdfLender', 'FinanceAgreementPdfBorrower',
 'FinanceAgreementPdfPrincipal', 'FinanceAgreementPdfAnnualRate', 'FinanceAgreementPdfTermMonths',
 'FinanceAgreementPdfRepaymentMethod', 'FinanceAgreementPdfCurrency',
 'FinanceAgreementPdfScheduleTitle',
 'FinanceAgreementPdfColInstallment', 'FinanceAgreementPdfColFromDate',
 'FinanceAgreementPdfColThruDate', 'FinanceAgreementPdfColDueDate',
 'FinanceAgreementPdfColPayment', 'FinanceAgreementPdfColPrincipal',
 'FinanceAgreementPdfColInterest', 'FinanceAgreementPdfColBalance',
 'FinanceAgreementPdfPage'].each { key ->
    jrParameters.put(key, UtilProperties.getMessage('FinanceUiLabels', key, locale))
}

// Confirmed repayment schedule rows (latest revision only).
List<GenericValue> scheduleValues = EntityQuery.use(delegator)
        .from('LoanAgreementRepaymentSchedule')
        .where('loanAgreementId', loanAgreementId, 'scheduleRevisionNumber', currentRevision)
        .orderBy('installmentNumber')
        .queryList()

List<Map<String, Object>> scheduleRows = []
for (GenericValue line : scheduleValues) {
    Map<String, Object> row = [:]
    row.installmentNumber = line.get('installmentNumber') != null
            ? new java.math.BigDecimal(line.get('installmentNumber').toString()) : null
    row.interestFromDate  = line.getTimestamp('interestFromDate')
    row.interestThruDate  = line.getTimestamp('interestThruDate')
    row.dueDate           = line.getTimestamp('dueDate')
    row.paymentAmount     = line.getBigDecimal('paymentAmount')
    row.principalAmount   = line.getBigDecimal('principalAmount')
    row.interestAmount    = line.getBigDecimal('interestAmount')
    row.remainingBalance  = line.getBigDecimal('remainingBalance')
    scheduleRows.add(row)
}

JRMapCollectionDataSource dataSource = new JRMapCollectionDataSource(scheduleRows)

request.setAttribute('jrParameters', jrParameters)
request.setAttribute('jrDataSource', dataSource)

return 'success'
