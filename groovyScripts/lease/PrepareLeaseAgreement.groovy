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

// Screen data for ManageLeaseAgreement (STEP L2): agreement header, asset lines
// and the current revision of the confirmed lease payment schedule.

import org.apache.ofbiz.entity.GenericValue

GenericValue leaseAgreement = from('LeaseAgreement').where('leaseAgreementId', parameters.leaseAgreementId).queryOne()
context.leaseAgreement = leaseAgreement
if (leaseAgreement == null) {
    return
}
context.leaseAgreementId = leaseAgreement.leaseAgreementId
context.currencyUomId = leaseAgreement.currencyUomId
context.showFixedAsset = true
context.assetLines = from('LeaseAgreementAsset').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('assetSeqId').queryList()
GenericValue latest = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('-scheduleRevisionNumber').queryFirst()
context.scheduleRevisionNumber = latest?.scheduleRevisionNumber
context.scheduleRows = latest == null ? [] : from('LeaseAgreementPaymentSchedule')
        .where('leaseAgreementId', leaseAgreement.leaseAgreementId, 'scheduleRevisionNumber', latest.scheduleRevisionNumber)
        .orderBy('installmentNumber').queryList()
context.canCancel = leaseAgreement.statusId == 'LEASEAGR_CONTRACTED'