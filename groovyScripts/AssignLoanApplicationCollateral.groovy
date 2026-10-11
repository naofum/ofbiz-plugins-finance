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

import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue

// Declare a collateral on a loan application. Only allowed while the
// application is still in the RECEIVED state.
String loanApplicationId = context.loanApplicationId
String collateralId = context.collateralId

GenericValue application = from('LoanApplication')
        .where('loanApplicationId', loanApplicationId).queryOne()
if (application == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanApplicationNotFound',
            [loanApplicationId: loanApplicationId], context.locale))
}
if (!'LOANAPP_RECEIVED'.equals(application.statusId)) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceApplicationImmutable',
            [loanApplicationId: loanApplicationId], context.locale))
}

GenericValue collateral = from('Collateral').where('collateralId', collateralId).queryOne()
if (collateral == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
            [entityName: 'Collateral', id: collateralId], context.locale))
}

GenericValue existing = from('LoanApplicationCollateral')
        .where('loanApplicationId', loanApplicationId, 'collateralId', collateralId).queryOne()
if (existing == null) {
    makeValue('LoanApplicationCollateral', [
            loanApplicationId : loanApplicationId,
            collateralId      : collateralId,
            declaredValueAmount: context.declaredValueAmount,
            comments          : context.comments]).create()
}
return success()
