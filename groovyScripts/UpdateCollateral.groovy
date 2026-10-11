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

String collateralId = context.collateralId
GenericValue collateral = from('Collateral').where('collateralId', collateralId).queryOne()
if (collateral == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
            [entityName: 'Collateral', id: collateralId], context.locale))
}
if ('COLL_RELEASED'.equals(collateral.statusId)) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceCollateralImmutable',
            [collateralId: collateralId], context.locale))
}

['collateralTypeId', 'ownerPartyId', 'fixedAssetId', 'currencyUomId', 'description'].each { String fieldName ->
    if (context.get(fieldName) != null) {
        collateral.set(fieldName, context.get(fieldName))
    }
}
collateral.store()
return success()
