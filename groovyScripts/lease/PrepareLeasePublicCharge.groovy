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

// Screen data for FindLeasePublicCharge (STEP L10 / L10b): the public charge declarations
// of a lessor organization (optionally for a tax year / charge type), with the
// jurisdiction name and payment state.

import org.apache.ofbiz.entity.GenericValue

String organizationPartyId = parameters.organizationPartyId ?: 'Company'
context.organizationPartyId = organizationPartyId
Integer taxYear = parameters.taxYear ? (parameters.taxYear as Integer) : null
context.taxYear = taxYear

Map conditions = ['organizationPartyId': organizationPartyId]
if (taxYear != null) {
    conditions.taxYear = (long) taxYear
}
if (parameters.publicChargeTypeId) {
    conditions.publicChargeTypeId = parameters.publicChargeTypeId
}
context.declarations = from('LeasePublicChargeDecl').where(conditions)
        .orderBy('-taxYear', 'publicChargeTypeId', 'jurisdictionGeoId').queryList().collect { GenericValue d ->
    Map row = new LinkedHashMap(d.getAllFields())
    GenericValue geo = d.jurisdictionGeoId ? from('Geo').where('geoId', d.jurisdictionGeoId).cache().queryOne() : null
    row.jurisdictionName = geo?.geoName ?: d.jurisdictionGeoId
    GenericValue payment = from('LeasePublicChargePayment').where('leasePublicChargeDeclId', d.leasePublicChargeDeclId).queryOne()
    row.paymentDate = payment?.paymentDate
    row.acctgTransId = payment?.acctgTransId
    row.canPay = d.statusId == 'LEASE_PCHG_GENERATED' && (d.taxAmount ?: BigDecimal.ZERO).signum() > 0
    return row
}
