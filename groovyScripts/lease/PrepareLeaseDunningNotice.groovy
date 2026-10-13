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

// Controller event (STEP L6): data of the lease dunning notice PDF. Reuses the
// dunning notice report (DunningNotice.jrxml) with lease wording: the overdue
// installments with their unpaid lease payment, consumption tax and total.

import java.sql.Timestamp
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseContractAdapter
import org.apache.ofbiz.party.party.PartyHelper
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

String label(String key) {
    return UtilProperties.getMessage('FinanceUiLabels', key, locale)
}

String leaseAgreementId = parameters.leaseAgreementId
GenericValue agreement = UtilValidate.isEmpty(leaseAgreementId) ? null
        : from('LeaseAgreement').where('leaseAgreementId', leaseAgreementId).queryOne()
if (agreement == null) {
    request.setAttribute('_ERROR_MESSAGE_', UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
            [entityName: 'LeaseAgreement', id: leaseAgreementId ?: ''], locale))
    return 'error'
}

Timestamp now = UtilDateTime.nowTimestamp()
List<Map> overdue = LeaseContractAdapter.overdueInstallments(delegator, agreement, UtilDateTime.getDayEnd(now))
BigDecimal overdueLease = overdue.inject(BigDecimal.ZERO) { BigDecimal s, Map r -> s.add(r.leaseAmount) }
BigDecimal overdueTax = overdue.inject(BigDecimal.ZERO) { BigDecimal s, Map r -> s.add(r.taxAmount) }
BigDecimal lateFee = from('LoanLateFeeAccrual').where('financeContractId', agreement.financeContractId).queryList()
        .inject(BigDecimal.ZERO) { BigDecimal s, GenericValue a -> s.add(a.lateFeeAmount ?: BigDecimal.ZERO) }
GenericValue delinquency = from('LoanDelinquency').where('financeContractId', agreement.financeContractId)
        .orderBy('-asOfDate').queryFirst()
String dunningLevelEnumId = parameters.dunningLevelEnumId
GenericValue dunningLevel = UtilValidate.isNotEmpty(dunningLevelEnumId)
        ? from('Enumeration').where('enumId', dunningLevelEnumId).queryOne() : null
long daysPastDue = overdue.isEmpty() ? 0L
        : java.time.temporal.ChronoUnit.DAYS.between(((Timestamp) overdue[0].dueDate).toLocalDateTime().toLocalDate(),
                now.toLocalDateTime().toLocalDate())

Map jrParameters = [:]
jrParameters.loanAgreementId  = agreement.leaseAgreementId
jrParameters.borrowerName     = PartyHelper.getPartyName(delegator, agreement.lesseePartyId, false)
jrParameters.currencyUomId    = agreement.currencyUomId
jrParameters.dunningLevel     = dunningLevel?.description ?: (dunningLevelEnumId ?: '')
jrParameters.daysPastDue      = new BigDecimal(Math.max(daysPastDue, delinquency?.daysPastDue ?: 0L))
jrParameters.overduePrincipal = overdueLease
jrParameters.overdueInterest  = overdueTax
jrParameters.lateFeeAccrued   = lateFee
jrParameters.noticeDate       = now
jrParameters.lenderName       = agreement.organizationPartyId
        ? PartyHelper.getPartyName(delegator, agreement.organizationPartyId, false) : label('FinanceLoanQuoteLenderName')
jrParameters.lenderAddress    = label('FinanceLoanQuoteLenderAddress')

// Lease wording for the labels of the shared report.
jrParameters.FinanceDunningNotice     = label('FinanceLeaseDunningNotice')
jrParameters.FinanceDunningLevel      = label('FinanceDunningLevel')
jrParameters.FinanceDaysPastDue       = label('FinanceDaysPastDue')
jrParameters.FinanceOverduePrincipal  = label('FinanceLeaseOverdueLease')
jrParameters.FinanceOverdueInterest   = label('FinanceLeaseOverdueTax')
jrParameters.FinanceLateFee           = label('FinanceLateFee')
jrParameters.FinanceLoanAgreement     = label('FinanceLeaseAgreementId')
jrParameters.FinanceApplicantPartyId  = label('FinanceLesseePartyId')
jrParameters.FinanceDueDate           = label('FinanceDueDate')
jrParameters.FinanceMonthlyPayment    = label('FinanceLeaseUnpaidBilled')
jrParameters.FinancePrincipalPortion  = label('FinanceLeaseAmount')
jrParameters.FinanceInterestPortion   = label('FinanceLeaseTaxAmount')
jrParameters.FinanceQuotePdfPage      = label('FinanceQuotePdfPage')
jrParameters.FinanceCurrencyUomId     = label('FinanceCurrencyUomId')
jrParameters.FinanceInstallmentNumber = label('FinanceInstallmentNumber')

List<Map<String, Object>> rows = overdue.collect { Map r ->
    [installmentNumber: new BigDecimal(r.installmentNumber.toString()), dueDate: r.dueDate,
     paymentAmount: r.unpaidAmount, principalAmount: r.leaseAmount, interestAmount: r.taxAmount]
}
request.setAttribute('jrParameters', jrParameters)
request.setAttribute('jrDataSource', new JRMapCollectionDataSource(rows))
return 'success'
