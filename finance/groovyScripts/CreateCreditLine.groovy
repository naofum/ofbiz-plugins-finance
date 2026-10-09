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

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue

// Create a credit line for a business party.
String creditLineId = delegator.getNextSeqId('CreditLine')
GenericValue creditLine = makeValue('CreditLine', [
        creditLineId     : creditLineId,
        creditLineTypeId : context.creditLineTypeId,
        partyId          : context.partyId,
        currencyUomId    : context.currencyUomId,
        fromDate         : context.fromDate ?: UtilDateTime.nowTimestamp(),
        thruDate         : context.thruDate,
        creditLimitAmount: context.creditLimitAmount,
        description      : context.description])
creditLine.create()

Map result = success()
result.creditLineId = creditLineId
return result
