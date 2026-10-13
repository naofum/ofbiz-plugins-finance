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

// Screen data for the lease inquiries (STEP L8). parameters.inquiry selects the list:
//   collection  - collection status per agreement (契約別リース料回収状況)
//   outstanding - outstanding principal per lessee (未回収元本残高一覧)
//   assets      - asset register (物件台帳)

import org.apache.ofbiz.finance.lease.LeaseInquiryHelper

String lessee = parameters.lesseePartyId ?: null
switch (context.inquiry) {
    case 'collection':
        context.inquiryRows = LeaseInquiryHelper.collectionStatus(delegator, parameters.statusId ?: null, lessee, null)
        break
    case 'outstanding':
        context.inquiryRows = LeaseInquiryHelper.outstandingPrincipalByLessee(delegator, lessee)
        break
    case 'assets':
        context.inquiryRows = LeaseInquiryHelper.assetRegister(delegator, parameters.assetStatusId ?: null, lessee,
                parameters.fixedAssetTypeId ?: null)
        break
    default:
        context.inquiryRows = []
}
