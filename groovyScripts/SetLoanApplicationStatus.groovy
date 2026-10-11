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

// Inputs (from context): loanApplicationId, statusId
String loanApplicationId = context.loanApplicationId
String newStatusId = context.statusId

GenericValue loanApplication = from('LoanApplication').where('loanApplicationId', loanApplicationId).queryOne()
if (loanApplication == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanApplicationNotFound',
            [loanApplicationId: loanApplicationId], context.locale))
}

String oldStatusId = loanApplication.statusId

// If the status is unchanged, nothing to do.
if (oldStatusId == newStatusId) {
    Map result = success()
    result.oldStatusId = oldStatusId
    return result
}

// Validate the transition against StatusValidChange (only when there is a current status).
if (oldStatusId != null) {
    GenericValue validChange = from('StatusValidChange')
            .where('statusId', oldStatusId, 'statusIdTo', newStatusId)
            .queryOne()
    if (validChange == null) {
        return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceInvalidStatusChange',
                [oldStatusId: oldStatusId, statusId: newStatusId], context.locale))
    }
}

// Apply the new status.
loanApplication.statusId = newStatusId
loanApplication.store()

// Record status history.
GenericValue history = makeValue('LoanApplicationStatus', [
        loanApplicationId   : loanApplicationId,
        statusId            : newStatusId,
        statusDate          : UtilDateTime.nowTimestamp(),
        changeByUserLoginId : userLogin?.userLoginId])
history.create()

Map result = success()
result.oldStatusId = oldStatusId
return result
