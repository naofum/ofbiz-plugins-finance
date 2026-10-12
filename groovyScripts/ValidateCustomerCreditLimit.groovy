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
import org.apache.ofbiz.finance.common.FinanceContractHelper

// Common layer (STEP L2): customer-level credit limit check shared by loan and
// lease. When the customer has an active credit line in the currency, the
// existing exposure (CONTRACTED + ACTIVE FinanceContracts of every type) plus
// the new principal must not exceed the limit. Repayments are not netted.
// CreditLine (STEP 5 entity) is treated as customer-level master data.
String customerPartyId = context.customerPartyId
String currencyUomId = context.currencyUomId
BigDecimal principalAmount = context.principalAmount
java.sql.Timestamp now = UtilDateTime.nowTimestamp()

GenericValue activeLine = null
for (GenericValue line : from('CreditLine').where('partyId', customerPartyId).queryList()) {
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

BigDecimal outstanding = FinanceContractHelper.sumExposurePrincipal(delegator, customerPartyId)
BigDecimal limit = activeLine.getBigDecimal('creditLimitAmount')
BigDecimal total = outstanding.add(principalAmount)
result.creditLimitAmount = limit
result.existingOutstanding = outstanding
result.totalExposure = total
if (limit != null && total.compareTo(limit) > 0) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCreditLimitExceeded',
            [partyId: customerPartyId, totalExposure: total, creditLimitAmount: limit], context.locale))
}
return result