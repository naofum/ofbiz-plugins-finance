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

// Controller event (run-time evaluated Groovy under groovyScripts/, so it needs
// no compile-time dependency on JasperReports; the classes are resolved at
// run time from the loaded jasperreports plugin).
//
// It prepares the loan-quote PDF data and hands it to the generic JasperReports
// view handler through request attributes:
//   - "jrParameters" : Map of header values (lender/borrower/quote header/totals)
//   - "jrDataSource" : JRMapCollectionDataSource over the repayment schedule rows
// Fetching data via the entity engine keeps the JRXML free of DB-specific SQL.

import org.apache.ofbiz.base.location.FlexibleLocation
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.party.party.PartyHelper
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

String loanQuoteId = parameters.loanQuoteId
if (UtilValidate.isEmpty(loanQuoteId)) {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('FinanceUiLabels', 'FinanceQuoteIdMissing', locale))
    return 'error'
}

GenericValue quote = from('LoanQuote').where('loanQuoteId', loanQuoteId).queryOne()
if (quote == null) {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteNotFound',
                    [loanQuoteId: loanQuoteId], locale))
    return 'error'
}

// Related header data (all through the entity engine).
GenericValue application = quote.getRelatedOne('LoanApplication', false)
GenericValue repaymentMethod = quote.getRelatedOne('Enumeration', false)

String borrowerPartyId = application?.applicantPartyId
String borrowerName = UtilValidate.isNotEmpty(borrowerPartyId)
        ? PartyHelper.getPartyName(delegator, borrowerPartyId, false) : ''

// Lender display name (label; a real deployment may resolve the owning company).
String lenderName = UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteLenderName', locale)
String lenderAddress = UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteLenderAddress', locale)

// Resolve the OFBiz logo to an absolute file path for the report image element.
String logoPath = null
try {
    java.net.URL logoUrl = FlexibleLocation.resolveLocation('component://common-theme/webapp/images/ofbiz_logo.png')
    if (logoUrl != null) {
        logoPath = new java.io.File(logoUrl.toURI()).getAbsolutePath()
    }
} catch (Exception e) {
    org.apache.ofbiz.base.util.Debug.logWarning('Could not resolve OFBiz logo for loan quote PDF: ' + e, 'PrepareLoanQuoteReport')
}

// Header parameters passed to the report.
Map jrParameters = [:]
jrParameters.loanQuoteId          = quote.loanQuoteId
jrParameters.loanApplicationId    = quote.loanApplicationId
jrParameters.statusId             = quote.statusId
jrParameters.currencyUomId        = quote.currencyUomId
jrParameters.principalAmount      = quote.getBigDecimal('principalAmount')
jrParameters.annualInterestRate   = quote.getBigDecimal('annualInterestRate')
jrParameters.termMonths           = (quote.get('termMonths') != null) ? new java.math.BigDecimal(quote.get('termMonths').toString()) : null
jrParameters.monthlyPaymentAmount = quote.getBigDecimal('monthlyPaymentAmount')
jrParameters.totalPaymentAmount   = quote.getBigDecimal('totalPaymentAmount')
jrParameters.totalInterestAmount  = quote.getBigDecimal('totalInterestAmount')
jrParameters.firstPaymentDate     = quote.getTimestamp('firstPaymentDate')
jrParameters.createdDate          = quote.getTimestamp('createdDate')
jrParameters.repaymentMethod      = repaymentMethod?.description ?: quote.repaymentMethodEnumId
jrParameters.borrowerPartyId      = borrowerPartyId
jrParameters.borrowerName         = borrowerName
jrParameters.lenderName           = lenderName
jrParameters.lenderAddress        = lenderAddress
jrParameters.logoPath             = logoPath

// Localized labels for the PDF (resolved from FinanceUiLabels for the request locale,
// so the report text follows OFBiz i18n rather than being hard-coded in the JRXML).
['FinanceQuotePdfTitle', 'FinanceQuotePdfQuoteNo', 'FinanceQuotePdfIssueDate',
 'FinanceQuotePdfLender', 'FinanceQuotePdfBorrower', 'FinanceQuotePdfPrincipal',
 'FinanceQuotePdfAnnualRate', 'FinanceQuotePdfTermMonths', 'FinanceQuotePdfRepaymentMethod',
 'FinanceQuotePdfMonthlyPayment', 'FinanceQuotePdfTotalPayment', 'FinanceQuotePdfTotalInterest',
 'FinanceQuotePdfCurrency', 'FinanceQuotePdfColInstallment', 'FinanceQuotePdfColDueDate',
 'FinanceQuotePdfColPayment', 'FinanceQuotePdfColPrincipal', 'FinanceQuotePdfColInterest',
 'FinanceQuotePdfColBalance', 'FinanceQuotePdfPage'].each { key ->
    jrParameters.put(key, UtilProperties.getMessage('FinanceUiLabels', key, locale))
}

// Repayment schedule rows -> list of plain Maps for a JRMapCollectionDataSource.
List<GenericValue> scheduleValues = from('LoanRepaymentSchedule')
        .where('loanQuoteId', loanQuoteId)
        .orderBy('installmentNumber')
        .queryList()

List<Map<String, Object>> scheduleRows = []
for (GenericValue line : scheduleValues) {
    Map<String, Object> row = [:]
    row.installmentNumber = (line.get('installmentNumber') != null) ? new java.math.BigDecimal(line.get('installmentNumber').toString()) : null
    row.dueDate           = line.getTimestamp('dueDate')
    row.paymentAmount     = line.getBigDecimal('paymentAmount')
    row.principalAmount   = line.getBigDecimal('principalAmount')
    row.interestAmount    = line.getBigDecimal('interestAmount')
    row.remainingBalance  = line.getBigDecimal('remainingBalance')
    scheduleRows.add(row)
}

JRMapCollectionDataSource dataSource = new JRMapCollectionDataSource(scheduleRows)

// Hand off to the JasperReports view handler.
request.setAttribute('jrParameters', jrParameters)
request.setAttribute('jrDataSource', dataSource)

return 'success'
