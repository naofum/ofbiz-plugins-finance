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

// Screen data for FindLeaseExpiry (STEP L7): ACTIVE leases ending within N months
// (default lease.expiry.notice.months) with the expiry notice and the open task count.

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper
import org.apache.ofbiz.finance.lease.LeaseExpiryServices

int months = parameters.monthsAhead ? (parameters.monthsAhead as int) : LeaseExpiryServices.noticeMonths(delegator)
context.monthsAhead = months
context.expiringLeases = LeaseExpiryServices.findExpiring(delegator, UtilDateTime.nowTimestamp(), months).collect { GenericValue a ->
    Map row = new LinkedHashMap(a.getAllFields())
    row.noticeDate = from('LeaseExpiryNotice').where('leaseAgreementId', a.leaseAgreementId).queryOne()?.noticeDate
    row.openTaskCount = FinanceWorkEffortHelper.countOpenTasks(delegator, a.financeContractId)
    return row
}
