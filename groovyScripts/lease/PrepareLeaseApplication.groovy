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

// Screen data for ManageLeaseApplication (STEP L1): requested assets, their
// estimated total, asset types allowed by the product, the latest underwriting,
// quotes and the agreement (STEP L2).

import org.apache.ofbiz.entity.GenericValue

String leaseApplicationId = parameters.leaseApplicationId
context.leaseApplicationId = leaseApplicationId
GenericValue leaseApplication = from('LeaseApplication').where('leaseApplicationId', leaseApplicationId).queryOne()
context.leaseApplication = leaseApplication
if (leaseApplication == null) {
    return
}

List assets = from('LeaseApplicationAsset').where('leaseApplicationId', leaseApplicationId)
        .orderBy('assetSeqId').queryList()
context.assets = assets
BigDecimal total = BigDecimal.ZERO
assets.each { GenericValue a -> total = total.add(a.getBigDecimal('estimatedAcquisitionCost') ?: BigDecimal.ZERO) }
context.estimatedTotalCost = total

List allowed = from('FinancialProductLeaseAssetType').where('productId', leaseApplication.productId).queryList()
context.assetTypeOptions = allowed ?
        from('FixedAssetType').where(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                'fixedAssetTypeId', org.apache.ofbiz.entity.condition.EntityOperator.IN,
                allowed*.fixedAssetTypeId)).orderBy('description').queryList() :
        from('FixedAssetType').orderBy('description').queryList()

context.leaseUnderwriting = from('LoanUnderwriting')
        .where('applicationTypeId', 'LEASE', 'applicationId', leaseApplicationId)
        .orderBy('-createdStamp').queryFirst()

// STEP L2: quotes and the agreement of the application.
context.quotes = from('LeaseQuote').where('leaseApplicationId', leaseApplicationId).orderBy('-createdDate').queryList()
context.currencyUomId = leaseApplication.currencyUomId
context.applicationApproved = leaseApplication.statusId == 'LEASEAPP_APPROVED'
context.leaseAgreement = from('LeaseAgreement').where('leaseApplicationId', leaseApplicationId).queryFirst()
