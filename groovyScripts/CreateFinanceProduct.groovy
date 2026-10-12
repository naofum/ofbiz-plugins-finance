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

// Common layer (STEP L1): create a financial product of any type from the
// shared product screen. Method B: the core Product is referenced 1:1; it is
// created here (productTypeId SERVICE) when it does not exist yet.

import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue

String productId = context.productId ?: delegator.getNextSeqId('Product')
if (from('FinancialProduct').where('productId', productId).queryOne() != null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceProductExists',
            [productId: productId], context.locale))
}
if (from('FinancialProductType').where('financialProductTypeId', context.financialProductTypeId).queryOne() == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
            [entityName: 'FinancialProductType', id: context.financialProductTypeId], context.locale))
}

GenericValue product = from('Product').where('productId', productId).queryOne()
if (product == null) {
    if (!context.productName) {
        return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceProductNameRequired', context.locale))
    }
    makeValue('Product', [productId   : productId,
                          productTypeId: 'SERVICE',
                          internalName : context.productName,
                          productName  : context.productName]).create()
}

GenericValue financialProduct = makeValue('FinancialProduct', [productId: productId])
financialProduct.getModelEntity().getNoPkFieldNames().each { String f ->
    Object value = context.get(f)
    if (value != null && !(value instanceof String && value.isEmpty())) {
        financialProduct.set(f, value)
    }
}
financialProduct.create()

Map result = success()
result.productId = productId
return result
