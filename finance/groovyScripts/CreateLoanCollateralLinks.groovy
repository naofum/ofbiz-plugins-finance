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

// Internal shared service used at contract time: create a LoanCollateral lien
// for every collateral declared on the application, assigning each its latest
// appraised value. Idempotent per (agreement, collateral).
String loanAgreementId = context.loanAgreementId
String loanApplicationId = context.loanApplicationId

List<GenericValue> declared = from('LoanApplicationCollateral')
        .where('loanApplicationId', loanApplicationId).queryList()

long createdCount = 0
for (GenericValue declaration : declared) {
    GenericValue existing = from('LoanCollateral')
            .where('loanAgreementId', loanAgreementId, 'collateralId', declaration.collateralId).queryOne()
    if (existing != null) {
        continue
    }
    GenericValue latest = from('CollateralValuation')
            .where('collateralId', declaration.collateralId)
            .orderBy('-valuationDate').queryFirst()
    BigDecimal assignedValue = latest == null ? null : latest.getBigDecimal('appraisedValueAmount')

    makeValue('LoanCollateral', [
            loanAgreementId     : loanAgreementId,
            collateralId        : declaration.collateralId,
            fromDate            : context.fromDate ?: UtilDateTime.nowTimestamp(),
            thruDate            : null,
            assignedValueAmount : assignedValue,
            lienDescription     : context.lienDescription,
            comments            : declaration.comments]).create()
    createdCount++
}

Map result = success()
result.createdCount = createdCount
return result
