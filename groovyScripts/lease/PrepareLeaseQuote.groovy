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

// Screen data for ViewLeaseQuote (STEP L2): quote header, asset lines and the quote payment schedule.

import org.apache.ofbiz.entity.GenericValue

GenericValue leaseQuote = from('LeaseQuote').where('leaseQuoteId', parameters.leaseQuoteId).queryOne()
context.leaseQuote = leaseQuote
if (leaseQuote == null) {
    return
}
context.currencyUomId = leaseQuote.currencyUomId
context.assetLines = from('LeaseQuoteAsset').where('leaseQuoteId', leaseQuote.leaseQuoteId).orderBy('assetSeqId').queryList()
context.scheduleRows = from('LeasePaymentSchedule').where('leaseQuoteId', leaseQuote.leaseQuoteId)
        .orderBy('installmentNumber').queryList()