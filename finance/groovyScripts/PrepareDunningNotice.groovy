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

// Controller event (run-time evaluated Groovy under groovyScripts/, so it has
// no compile-time dependency on JasperReports). Prepares dunning-notice PDF data
// and hands it to the generic JasperReports view handler via request attributes:
//   - "jrParameters" : Map of header values
//   - "jrDataSource" : JRMapCollectionDataSource over the overdue installments
// Data is fetched through the entity engine to keep the JRXML free of SQL.

import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.party.party.PartyHelper
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

String loanAgreementId = parameters.loanAgreementId
String dunningLevelEnumId = parameters.dunningLevelEnumId

if (UtilValidate.isEmpty(loanAgreementId)) {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
                    [entityName: 'LoanAgreement', id: ''], locale))
    return 'error'
}

GenericValue agreement = from('LoanAgreement').where('loanAgreementId', loanAgreementId).queryOne()
if (agreement == null) {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
                    [entityName: 'LoanAgreement', id: loanAgreementId], locale))
    return 'error'
}

GenericValue application = agreement.getRelatedOne('LoanApplication', false)
String borrowerPartyId = application?.applicantPartyId
String borrowerName = UtilValidate.isNotEmpty(borrowerPartyId)
        ? PartyHelper.getPartyName(delegator, borrowerPartyId, false) : ''

GenericValue delinquency = from('LoanDelinquency')
        .where('loanAgreementId', loanAgreementId).orderBy('-asOfDate').queryFirst()
GenericValue dunningLevel = UtilValidate.isNotEmpty(dunningLevelEnumId)
        ? from('Enumeration').where('enumId', dunningLevelEnumId).queryOne() : null

String lenderName = UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteLenderName', locale)
String lenderAddress = UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanQuoteLenderAddress', locale)

Map jrParameters = [:]
jrParameters.loanAgreementId   = agreement.loanAgreementId
jrParameters.borrowerPartyId   = borrowerPartyId
jrParameters.borrowerName      = borrowerName
jrParameters.currencyUomId     = agreement.currencyUomId
jrParameters.principalAmount   = agreement.getBigDecimal('principalAmount')
jrParameters.annualInterestRate = agreement.getBigDecimal('annualInterestRate')
jrParameters.dunningLevel      = dunningLevel?.description ?: dunningLevelEnumId
jrParameters.daysPastDue       = delinquency == null ? new BigDecimal('0') : new BigDecimal(delinquency.get('daysPastDue').toString())
jrParameters.overduePrincipal  = delinquency?.getBigDecimal('overduePrincipalAmount')
jrParameters.overdueInterest   = delinquency?.getBigDecimal('overdueInterestAmount')
jrParameters.lateFeeAccrued    = delinquency?.getBigDecimal('lateFeeAccruedAmount')
jrParameters.noticeDate        = org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp()
jrParameters.lenderName        = lenderName
jrParameters.lenderAddress     = lenderAddress

['FinanceDunningNotice', 'FinanceDunningLevel', 'FinanceDaysPastDue',
 'FinanceOverduePrincipal', 'FinanceOverdueInterest', 'FinanceLateFee',
 'FinanceLoanAgreement', 'FinanceApplicantPartyId', 'FinanceDueDate',
 'FinanceMonthlyPayment', 'FinancePrincipalPortion', 'FinanceInterestPortion',
 'FinanceQuotePdfPage', 'FinanceCurrencyUomId', 'FinanceInstallmentNumber'].each { key ->
    jrParameters.put(key, UtilProperties.getMessage('FinanceUiLabels', key, locale))
}

// Overdue installments: schedule rows with a due date not after today.
List<GenericValue> scheduleValues = from('LoanAgreementRepaymentSchedule')
        .where('loanAgreementId', loanAgreementId)
        .orderBy('installmentNumber')
        .queryList()

java.sql.Timestamp now = org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp()
List<Map<String, Object>> overdueRows = []
for (GenericValue line : scheduleValues) {
    java.sql.Timestamp dueDate = line.getTimestamp('dueDate')
    if (dueDate != null && dueDate.after(now)) {
        continue
    }
    Map<String, Object> row = [:]
    row.installmentNumber = (line.get('installmentNumber') != null) ? new java.math.BigDecimal(line.get('installmentNumber').toString()) : null
    row.dueDate           = dueDate
    row.paymentAmount     = line.getBigDecimal('paymentAmount')
    row.principalAmount   = line.getBigDecimal('principalAmount')
    row.interestAmount    = line.getBigDecimal('interestAmount')
    overdueRows.add(row)
}

JRMapCollectionDataSource dataSource = new JRMapCollectionDataSource(overdueRows)

request.setAttribute('jrParameters', jrParameters)
request.setAttribute('jrDataSource', dataSource)
return 'success'
