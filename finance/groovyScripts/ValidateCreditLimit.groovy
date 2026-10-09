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

import java.math.BigDecimal
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue

// Internal shared validation used by underwriting approval and agreement
// creation. When the applicant has an active credit line (limit-only check),
// the sum of the existing ACTIVE agreement principal and the new principal
// must not exceed the credit limit. Repayments are not netted (simple check).
String loanApplicationId = context.loanApplicationId
BigDecimal principalAmount = context.principalAmount

GenericValue application = from('LoanApplication')
        .where('loanApplicationId', loanApplicationId).queryOne()
if (application == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanApplicationNotFound',
            [loanApplicationId: loanApplicationId], context.locale))
}

String applicantPartyId = application.applicantPartyId
String currencyUomId = application.currencyUomId
java.sql.Timestamp now = UtilDateTime.nowTimestamp()

GenericValue activeLine = null
List<GenericValue> creditLines = from('CreditLine').where('partyId', applicantPartyId).queryList()
for (GenericValue line : creditLines) {
    if (currencyUomId != null && !currencyUomId.equals(line.currencyUomId)) {
        continue
    }
    if (line.fromDate != null && line.getTimestamp('fromDate').after(now)) {
        continue
    }
    if (line.thruDate != null && line.getTimestamp('thruDate').before(now)) {
        continue
    }
    activeLine = line
    break
}

Map result = success()
result.hasCreditLine = activeLine != null
result.creditLimitAmount = null
result.existingOutstanding = BigDecimal.ZERO
result.totalExposure = principalAmount

if (activeLine == null) {
    return result
}

BigDecimal outstanding = BigDecimal.ZERO
List<GenericValue> agreements = from('LoanAgreement')
        .where('borrowerPartyId', applicantPartyId, 'statusId', 'LOANAGR_ACTIVE').queryList()
for (GenericValue agreement : agreements) {
    outstanding = outstanding.add(agreement.getBigDecimal('principalAmount') ?: BigDecimal.ZERO)
}

BigDecimal limit = activeLine.getBigDecimal('creditLimitAmount')
BigDecimal total = outstanding.add(principalAmount)

result.creditLimitAmount = limit
result.existingOutstanding = outstanding
result.totalExposure = total

if (limit != null && total.compareTo(limit) > 0) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCreditLimitExceeded',
            [partyId: applicantPartyId, totalExposure: total, creditLimitAmount: limit], context.locale))
}

return result
