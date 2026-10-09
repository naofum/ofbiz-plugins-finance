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

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue

// Generic status transition for STEP 2 finance entities.
// Determines the target entity from whichever primary-key parameter is present.
// Supported: LoanQuote (loanQuoteId), LoanUnderwriting (loanUnderwritingId), LoanAgreement (loanAgreementId)

Map entityByPk = [
        loanQuoteId        : 'LoanQuote',
        loanUnderwritingId : 'LoanUnderwriting',
        loanAgreementId    : 'LoanAgreement'
]

String pkField = null
String entityName = null
for (entry in entityByPk) {
    if (context.get(entry.key) != null) {
        pkField = entry.key
        entityName = entry.value
        break
    }
}

if (pkField == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceStatusTargetMissing', context.locale))
}

String pkValue = context.get(pkField)
String newStatusId = context.statusId

GenericValue entityValue = from(entityName).where(pkField, pkValue).queryOne()
if (entityValue == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
            [entityName: entityName, id: pkValue], context.locale))
}

String oldStatusId = entityValue.statusId
if (oldStatusId == newStatusId) {
    Map result = success()
    result.oldStatusId = oldStatusId
    return result
}

if (oldStatusId != null) {
    GenericValue validChange = from('StatusValidChange')
            .where('statusId', oldStatusId, 'statusIdTo', newStatusId)
            .queryOne()
    if (validChange == null) {
        return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceInvalidStatusChange',
                [oldStatusId: oldStatusId, statusId: newStatusId], context.locale))
    }
}

entityValue.statusId = newStatusId
if ('LoanUnderwriting'.equals(entityName)) {
    if ('LOANUW_APPROVED'.equals(newStatusId)) {
        entityValue.decision = 'APPROVED'
        entityValue.reviewDate = UtilDateTime.nowTimestamp()
    } else if ('LOANUW_REJECTED'.equals(newStatusId)) {
        entityValue.decision = 'REJECTED'
        entityValue.reviewDate = UtilDateTime.nowTimestamp()
    }
}
entityValue.store()

Map result = success()
result.oldStatusId = oldStatusId
return result
