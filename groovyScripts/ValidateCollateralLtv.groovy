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
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue

// Internal shared validation used by underwriting approval and agreement
// creation. For a secured product (requiresCollateral=Y) the application must
// have declared collateral and each must have a valuation. When the product
// defines a maximum LTV ratio, principalAmount / sum(appraised value) must not
// exceed it.
String loanApplicationId = context.loanApplicationId
BigDecimal principalAmount = context.principalAmount

GenericValue application = from('LoanApplication')
        .where('loanApplicationId', loanApplicationId).queryOne()
if (application == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanApplicationNotFound',
            [loanApplicationId: loanApplicationId], context.locale))
}

GenericValue product = from('FinancialProduct').where('productId', application.productId).queryOne()
boolean requiresCollateral = product != null && 'Y'.equals(product.requiresCollateral)
BigDecimal maxLoanToValueRatio = product == null ? null : product.getBigDecimal('maxLoanToValueRatio')

List<GenericValue> declared = from('LoanApplicationCollateral')
        .where('loanApplicationId', loanApplicationId).queryList()

Map result = success()
result.requiresCollateral = requiresCollateral
result.totalCollateralValue = BigDecimal.ZERO
result.loanToValueRatio = null

if (!requiresCollateral && maxLoanToValueRatio == null && declared.isEmpty()) {
    return result
}

if (requiresCollateral && declared.isEmpty()) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCollateralRequired',
            [loanApplicationId: loanApplicationId], context.locale))
}

BigDecimal totalValue = BigDecimal.ZERO
for (GenericValue declaration : declared) {
    GenericValue latest = from('CollateralValuation')
            .where('collateralId', declaration.collateralId)
            .orderBy('-valuationDate').queryFirst()
    if (latest == null) {
        return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCollateralValuationMissing',
                [collateralId: declaration.collateralId], context.locale))
    }
    totalValue = totalValue.add(latest.getBigDecimal('appraisedValueAmount') ?: BigDecimal.ZERO)
}

result.totalCollateralValue = totalValue

if (maxLoanToValueRatio != null) {
    if (totalValue.signum() <= 0) {
        return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCollateralValuationMissing',
                [collateralId: loanApplicationId], context.locale))
    }
    BigDecimal loanToValueRatio = principalAmount.divide(totalValue, 6, BigDecimal.ROUND_HALF_UP)
    result.loanToValueRatio = loanToValueRatio
    if (loanToValueRatio.compareTo(maxLoanToValueRatio) > 0) {
        return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCollateralLtvExceeded',
                [loanToValueRatio: loanToValueRatio, maxLoanToValueRatio: maxLoanToValueRatio], context.locale))
    }
}

return result
